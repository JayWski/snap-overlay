package app.snapoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/**
 * The whole app: a round "S" button over other apps, and a panel that opens when it's tapped.
 * The panel has folders (Ladder plus one per game mode), decks with win/loss records, a card
 * list for the deck you're playing, and the + button for new decks.
 * Close it with Quit in the panel, or by dragging the S onto the ✕ at the bottom of the screen.
 */
class OverlayService : Service() {

    companion object {
        @Volatile var running = false
            private set

        private const val ACTION_STOP = "app.snapoverlay.STOP"
        private const val ACTION_OPEN = "app.snapoverlay.OPEN"
        private const val CHANNEL = "overlay"
        private const val POS_PREFS = "overlay_position"
        private const val DECK_SIZE = 12
        /** How see-through the S is when not touched (0 = invisible, 1 = solid). */
        private const val IDLE_ALPHA = 0.45f

        /** Starts the overlay, or opens its panel if it's already running. */
        fun open(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_OPEN))
        }
    }

    /** The screens of the panel. The panel keeps a back stack of these. */
    private sealed class Screen {
        object Home : Screen()
        data class FolderView(val folderId: Long) : Screen()
        data class DeckView(val deckId: Long) : Screen()
        data class Create(val folderId: Long) : Screen()
        data class Paste(val folderId: Long) : Screen()
        data class Pick(val folderId: Long) : Screen()
        data class NamePicked(val folderId: Long) : Screen()
        data class RenameDeck(val deckId: Long) : Screen()
        data class MoveDeck(val deckId: Long) : Screen()
        object NewFolder : Screen()
        data class RenameFolder(val folderId: Long) : Screen()
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var closeTarget: TextView? = null

    private var panel: PanelFrame? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private val stack = mutableListOf<Screen>(Screen.Home)

    /** Cards marked as drawn, per deck, until the match is recorded or reset. */
    private val drawn = mutableMapOf<Long, MutableSet<String>>()
    /** The last recorded result, so it can be undone: deck id and whether it was a win. */
    private var lastResult: Pair<Long, Boolean>? = null
    /** Waiting for a second tap to confirm a delete: what would be deleted. */
    private var confirmDelete: String? = null
    /** Cards chosen in the card picker. */
    private val picked = mutableListOf<CardInfo>()
    private var pickQuery = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
        // Keep the card list current: update it quietly every time the overlay starts.
        CardDb.refreshInBackground(this) { ok -> if (ok) main.post { if (panel != null && stack.last() == Screen.Home) render() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_OPEN -> {
                stack.clear(); stack.add(Screen.Home)
                openPanel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        closePanel()
        hideCloseTarget()
        runCatching { wm.removeView(bubble) }
        super.onDestroy()
    }

    private fun quit() {
        Toast.makeText(this, "Snap Overlay closed", Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Floating button", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Snap Overlay is on")
            .setContentText("Tap Quit to close it.")
            .addAction(Notification.Action.Builder(null, "Quit", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun overlayParams(width: Int, height: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or extraFlags,
        PixelFormat.TRANSLUCENT,
    )

    // ======================= The round S button =======================

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val size = Ui.dp(this, 54)
        bubble = Ui.text(this, "S", 23f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.oval(Ui.PURPLE)
            alpha = IDLE_ALPHA
        }
        val pos = getSharedPreferences(POS_PREFS, MODE_PRIVATE)
        bubbleParams = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pos.getInt("x", 0)
            y = pos.getInt("y", Ui.dp(this@OverlayService, 160))
        }

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
        val slop = Ui.dp(this, 8)
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bubble.alpha = 1f
                    startX = bubbleParams.x; startY = bubbleParams.y
                    touchX = e.rawX; touchY = e.rawY; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                        showCloseTarget()
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx; bubbleParams.y = startY + dy
                        wm.updateViewLayout(bubble, bubbleParams)
                        highlightCloseTarget(overCloseTarget(e.rawX, e.rawY))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    bubble.alpha = IDLE_ALPHA
                    if (dragging) {
                        val drop = e.actionMasked == MotionEvent.ACTION_UP && overCloseTarget(e.rawX, e.rawY)
                        hideCloseTarget()
                        if (drop) {
                            quit()
                        } else {
                            pos.edit().putInt("x", bubbleParams.x).putInt("y", bubbleParams.y).apply()
                        }
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        togglePanel()
                    }
                }
            }
            true
        }
        wm.addView(bubble, bubbleParams)
    }

    /** The ✕ at the bottom of the screen that appears while the S is dragged. */
    private fun showCloseTarget() {
        if (closeTarget != null) return
        val size = Ui.dp(this, 64)
        val view = Ui.text(this, "✕", 28f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.oval(0xCC000000.toInt())
        }
        val params = overlayParams(size, size, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = Ui.dp(this@OverlayService, 56)
        }
        wm.addView(view, params)
        closeTarget = view
    }

    private fun hideCloseTarget() {
        closeTarget?.let { runCatching { wm.removeView(it) } }
        closeTarget = null
    }

    private fun overCloseTarget(rawX: Float, rawY: Float): Boolean {
        val target = closeTarget ?: return false
        if (!target.isAttachedToWindow || target.width == 0) return false
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val cx = loc[0] + target.width / 2f
        val cy = loc[1] + target.height / 2f
        return hypot(rawX - cx, rawY - cy) < Ui.dp(this, 70)
    }

    private fun highlightCloseTarget(on: Boolean) {
        val target = closeTarget ?: return
        target.background = Ui.oval(if (on) Ui.LOSS_RED else 0xCC000000.toInt())
        target.scaleX = if (on) 1.2f else 1f
        target.scaleY = if (on) 1.2f else 1f
    }

    // ======================= The panel =======================

    /** The panel's outer frame. Catches the phone's Back button while the keyboard is in use. */
    private inner class PanelFrame(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) back()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun togglePanel() {
        if (panel != null) closePanel() else openPanel()
    }

    private fun openPanel() {
        if (panel == null) {
            val metrics = resources.displayMetrics
            val width = minOf(metrics.widthPixels - Ui.dp(this, 24), Ui.dp(this, 380))
            val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = Ui.dp(this@OverlayService, 36)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }
            val frame = PanelFrame(this)
            wm.addView(frame, params)
            panel = frame
            panelParams = params
        }
        render()
    }

    private fun closePanel() {
        panel?.let { p ->
            hideKeyboard(p)
            runCatching { wm.removeView(p) }
        }
        panel = null
        panelParams = null
    }

    private fun go(screen: Screen) {
        confirmDelete = null
        stack.add(screen)
        render()
    }

    private fun back() {
        confirmDelete = null
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            render()
        } else {
            closePanel()
        }
    }

    /** Replaces the screen on top of the stack, e.g. after saving a new deck. */
    private fun replaceTop(vararg screens: Screen) {
        confirmDelete = null
        stack.removeAt(stack.size - 1)
        stack.addAll(screens)
        render()
    }

    /** The folder the + should save into, based on where you are. */
    private fun currentFolderId(): Long {
        for (s in stack.asReversed()) {
            when (s) {
                is Screen.FolderView -> return s.folderId
                is Screen.DeckView -> DeckStore.deck(this, s.deckId)?.let { return it.folderId }
                is Screen.Create -> return s.folderId
                else -> {}
            }
        }
        return DeckStore.LADDER_ID
    }

    private fun needsKeyboard(screen: Screen) = when (screen) {
        is Screen.Paste, is Screen.Pick, is Screen.NamePicked, is Screen.RenameDeck,
        is Screen.NewFolder, is Screen.RenameFolder -> true
        else -> false
    }

    private fun render() {
        val frame = panel ?: return
        val params = panelParams ?: return
        val screen = stack.last()

        // Text boxes only work if the panel can take the keyboard; otherwise let the game keep it.
        val focusable = needsKeyboard(screen)
        val newFlags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!focusable) hideKeyboard(frame)
        if (newFlags != params.flags) {
            params.flags = newFlags
            wm.updateViewLayout(frame, params)
        }

        val box = Ui.vertical(this).apply {
            val p = Ui.dp(context, 12)
            setPadding(p, p, p, p)
            background = Ui.rounded(Ui.PANEL_BG, Ui.dp(context, 16).toFloat())
        }
        box.addView(header(titleOf(screen), showBack = stack.size > 1))
        val body = Ui.vertical(this)
        when (screen) {
            Screen.Home -> homeScreen(body)
            is Screen.FolderView -> folderScreen(body, screen.folderId)
            is Screen.DeckView -> deckScreen(body, screen.deckId)
            is Screen.Create -> createScreen(body, screen.folderId)
            is Screen.Paste -> pasteScreen(body, screen.folderId)
            is Screen.Pick -> pickScreen(body)
            is Screen.NamePicked -> namePickedScreen(body, screen.folderId)
            is Screen.RenameDeck -> renameDeckScreen(body, screen.deckId)
            is Screen.MoveDeck -> moveDeckScreen(body, screen.deckId)
            Screen.NewFolder -> newFolderScreen(body)
            is Screen.RenameFolder -> renameFolderScreen(body, screen.folderId)
        }
        box.addView(body)

        frame.removeAllViews()
        frame.addView(box)
    }

    private fun titleOf(screen: Screen): String = when (screen) {
        Screen.Home -> "Snap Overlay"
        is Screen.FolderView -> DeckStore.folder(this, screen.folderId)?.name ?: "Folder"
        is Screen.DeckView -> DeckStore.deck(this, screen.deckId)?.name ?: "Deck"
        is Screen.Create -> "New deck"
        is Screen.Paste -> "Paste a deck code"
        is Screen.Pick -> "Pick cards  ${picked.size}/$DECK_SIZE"
        is Screen.NamePicked -> "Name your deck"
        is Screen.RenameDeck -> "Rename deck"
        is Screen.MoveDeck -> "Move to folder"
        Screen.NewFolder -> "New folder"
        is Screen.RenameFolder -> "Rename folder"
    }

    /** Top row on every screen: back, title, + (new deck), Quit, close. */
    private fun header(title: String, showBack: Boolean): View {
        val row = Ui.horizontal(this)
        if (showBack) row.addView(Ui.button(this, "←", sizeSp = 17f) { back() })
        row.addView(Ui.text(this, title, 18f, Color.WHITE, bold = true).apply {
            tag = "title"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(context, 8); marginEnd = Ui.dp(context, 6)
            }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(Ui.button(this, "+", Ui.PURPLE, sizeSp = 20f) {
            if (stack.last() !is Screen.Create) go(Screen.Create(currentFolderId()))
        }.apply { setPadding(Ui.dp(context, 16), Ui.dp(context, 4), Ui.dp(context, 16), Ui.dp(context, 4)) })
        row.addView(Ui.spacer(this, 1).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 6), 1) })
        row.addView(Ui.button(this, "Quit", Ui.LOSS_RED, sizeSp = 13f) { quit() })
        row.addView(Ui.spacer(this, 1).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 6), 1) })
        row.addView(Ui.button(this, "✕", sizeSp = 15f) { closePanel() })
        return row
    }

    private fun scrolling(content: View, share: Double = 0.55): View =
        MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * share).toInt()).apply {
            layoutParams = Ui.matchWidth(context, 8)
            addView(content)
        }

    private fun note(body: LinearLayout, message: String) {
        body.addView(Ui.text(this, message, 13f, Ui.MUTED).apply { layoutParams = Ui.matchWidth(context, 8) })
    }

    private fun buttonRow(body: LinearLayout, vararg buttons: View) {
        val row = Ui.horizontal(this).apply { layoutParams = Ui.matchWidth(context, 10) }
        for (b in buttons) {
            b.layoutParams = Ui.weighted(this)
            row.addView(b)
        }
        body.addView(row)
    }

    // ---------- Home: the folders ----------

    private fun homeScreen(body: LinearLayout) {
        val lib = DeckStore.load(this)
        val list = Ui.vertical(this)
        for (folder in lib.folders) {
            val decks = lib.decks.filter { it.folderId == folder.id }
            val wins = decks.sumOf { it.wins }
            val losses = decks.sumOf { it.losses }
            val count = if (decks.size == 1) "1 deck" else "${decks.size} decks"
            val record = if (wins + losses > 0) " · $wins–$losses" else ""
            list.addView(Ui.listRow(this, "📁  ${folder.name}", count + record) { go(Screen.FolderView(folder.id)) })
        }
        body.addView(scrolling(list))
        buttonRow(body, Ui.button(this, "New folder") { go(Screen.NewFolder) })
        val db = CardDb.get(this)
        note(body, "Card list: ${db.size} cards, updated ${db.updated} (from marvelsnapzone.com).")
    }

    private fun newFolderScreen(body: LinearLayout) {
        note(body, "One folder per game mode, e.g. Conquest or an event mode.")
        val name = Ui.input(this, "Folder name")
        body.addView(name)
        buttonRow(body, Ui.button(this, "Save", Ui.PURPLE) {
            val n = name.text.toString().trim()
            if (n.isEmpty()) return@button toast("Type a name first.")
            val folder = DeckStore.addFolder(this, n)
            replaceTop(Screen.FolderView(folder.id))
        })
        focusKeyboard(name)
    }

    // ---------- A folder: its decks ----------

    private fun folderScreen(body: LinearLayout, folderId: Long) {
        val decks = DeckStore.decksIn(this, folderId)
        if (decks.isEmpty()) {
            note(body, "No decks here yet. Tap + at the top to add one.")
        } else {
            val list = Ui.vertical(this)
            for (deck in decks) list.addView(Ui.listRow(this, deck.name, deck.record) { go(Screen.DeckView(deck.id)) })
            body.addView(scrolling(list))
        }
        val buttons = mutableListOf<View>(Ui.button(this, "Rename folder") { go(Screen.RenameFolder(folderId)) })
        if (folderId != DeckStore.LADDER_ID) {
            val key = "folder:$folderId"
            buttons += Ui.button(this, if (confirmDelete == key) "Tap again to delete" else "Delete folder", Ui.LOSS_RED) {
                if (confirmDelete == key) {
                    DeckStore.deleteFolder(this, folderId)
                    confirmDelete = null
                    stack.removeAt(stack.size - 1)
                    render()
                } else {
                    confirmDelete = key
                    render()
                }
            }
        }
        buttonRow(body, *buttons.toTypedArray())
        if (confirmDelete == "folder:$folderId") note(body, "Its decks will move to Ladder.")
    }

    private fun renameFolderScreen(body: LinearLayout, folderId: Long) {
        val folder = DeckStore.folder(this, folderId) ?: return back()
        val name = Ui.input(this, "Folder name").apply { setText(folder.name); setSelection(folder.name.length) }
        body.addView(name)
        buttonRow(body, Ui.button(this, "Save", Ui.PURPLE) {
            val n = name.text.toString().trim()
            if (n.isEmpty()) return@button toast("Type a name first.")
            DeckStore.renameFolder(this, folderId, n)
            back()
        })
        focusKeyboard(name)
    }

    // ---------- A deck: record, cards, actions ----------

    private fun deckScreen(body: LinearLayout, deckId: Long) {
        val deck = DeckStore.deck(this, deckId) ?: return back()
        val marked = drawn.getOrPut(deckId) { mutableSetOf() }
        val cards = DeckStore.cards(this, deck)

        // Record and the one-tap result buttons.
        body.addView(Ui.text(this, "Record: ${deck.record}", 15f, Color.WHITE, bold = true).apply {
            layoutParams = Ui.matchWidth(context, 8)
        })
        buttonRow(body,
            Ui.button(this, "Won", Ui.WIN_GREEN) { recordResult(deck, won = true) },
            Ui.button(this, "Lost", Ui.LOSS_RED) { recordResult(deck, won = false) })
        val last = lastResult
        if (last != null && last.first == deckId) {
            val row = Ui.horizontal(this).apply { layoutParams = Ui.matchWidth(context, 6) }
            row.addView(Ui.text(this, if (last.second) "Win recorded." else "Loss recorded.", 13f, Ui.MUTED).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Ui.button(this, "Undo", sizeSp = 13f) {
                DeckStore.updateDeck(this, deckId) {
                    if (last.second) it.copy(wins = maxOf(0, it.wins - 1)) else it.copy(losses = maxOf(0, it.losses - 1))
                }
                lastResult = null
                render()
            })
            body.addView(row)
        }

        // The cards. Tap one when you draw it.
        val left = Ui.text(this, "", 13f, Ui.MUTED)
        fun updateLeft() {
            val n = cards.count { it.key !in marked }
            left.text = if (n == 0) "Deck empty." else "Tap a card when you draw it · $n left · each 1 in $n next draw"
        }
        left.layoutParams = Ui.matchWidth(this, 10)
        body.addView(left)
        val list = Ui.vertical(this)
        for (card in cards) {
            val row = Ui.cardRow(this, card, drawn = card.key in marked)
            row.setOnClickListener {
                if (!marked.remove(card.key)) marked.add(card.key)
                Ui.setDrawn(row, card.key in marked)
                updateLeft()
            }
            list.addView(row)
        }
        body.addView(scrolling(list, 0.45))
        updateLeft()

        // Deck actions.
        buttonRow(body,
            Ui.button(this, "Reset cards", sizeSp = 13f) { marked.clear(); render() },
            Ui.button(this, "Copy code", sizeSp = 13f) { copyDeck(deck) })
        val delKey = "deck:$deckId"
        buttonRow(body,
            Ui.button(this, "Rename", sizeSp = 13f) { go(Screen.RenameDeck(deckId)) },
            Ui.button(this, "Move", sizeSp = 13f) { go(Screen.MoveDeck(deckId)) },
            Ui.button(this, if (confirmDelete == delKey) "Sure?" else "Delete", Ui.LOSS_RED, sizeSp = 13f) {
                if (confirmDelete == delKey) {
                    DeckStore.deleteDeck(this, deckId)
                    drawn.remove(deckId)
                    confirmDelete = null
                    stack.removeAt(stack.size - 1)
                    render()
                } else {
                    confirmDelete = delKey
                    render()
                }
            })
    }

    private fun recordResult(deck: Deck, won: Boolean) {
        DeckStore.updateDeck(this, deck.id) { if (won) it.copy(wins = it.wins + 1) else it.copy(losses = it.losses + 1) }
        lastResult = deck.id to won
        drawn[deck.id]?.clear() // ready for the next match
        render()
    }

    private fun copyDeck(deck: Deck) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Deck code", deck.text))
        toast("Deck code copied. In Marvel Snap, open your decks and use Paste/Import.")
    }

    private fun renameDeckScreen(body: LinearLayout, deckId: Long) {
        val deck = DeckStore.deck(this, deckId) ?: return back()
        val name = Ui.input(this, "Deck name").apply { setText(deck.name); setSelection(deck.name.length) }
        body.addView(name)
        buttonRow(body, Ui.button(this, "Save", Ui.PURPLE) {
            val n = name.text.toString().trim()
            if (n.isEmpty()) return@button toast("Type a name first.")
            DeckStore.updateDeck(this, deckId) { it.copy(name = n) }
            back()
        })
        focusKeyboard(name)
    }

    private fun moveDeckScreen(body: LinearLayout, deckId: Long) {
        val deck = DeckStore.deck(this, deckId) ?: return back()
        val list = Ui.vertical(this)
        for (folder in DeckStore.folders(this)) {
            val here = folder.id == deck.folderId
            list.addView(Ui.listRow(this, "📁  ${folder.name}", if (here) "It's here now" else null) {
                DeckStore.updateDeck(this, deckId) { it.copy(folderId = folder.id) }
                back()
            })
        }
        body.addView(scrolling(list))
    }

    // ---------- + : making a deck ----------

    private fun createScreen(body: LinearLayout, folderId: Long) {
        note(body, "How do you want to make it?")
        body.addView(Ui.listRow(this, "📋  Paste a deck code",
            "In Marvel Snap, open a deck and tap Copy Deck Code first.") { go(Screen.Paste(folderId)) })
        body.addView(Ui.listRow(this, "🃏  Pick cards", "Search and tap 12 cards. Makes a code you can import.") {
            picked.clear(); pickQuery = ""
            go(Screen.Pick(folderId))
        })
    }

    /** Deck name box plus a choice of folder. Returns a function that reads the chosen folder. */
    private fun nameAndFolder(body: LinearLayout, folderId: Long, suggestedName: String): Pair<EditText, () -> Long> {
        val name = Ui.input(this, "Deck name, e.g. Bounce").apply { setText(suggestedName) }
        body.addView(name)
        body.addView(Ui.text(this, "Folder", 13f, Ui.MUTED).apply { layoutParams = Ui.matchWidth(context, 10) })
        var chosen = folderId
        val folders = DeckStore.folders(this)
        if (folders.none { it.id == chosen }) chosen = DeckStore.LADDER_ID
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buttons = mutableListOf<Pair<Long, TextView>>()
        fun paint() = buttons.forEach { (id, b) ->
            b.background = Ui.rounded(if (id == chosen) Ui.PURPLE else Ui.BUTTON_BG, Ui.dp(this, 8).toFloat())
        }
        for (f in folders) {
            val b = Ui.button(this, f.name, sizeSp = 14f) { chosen = f.id; paint() }
            b.layoutParams = Ui.matchWidth(this, 4)
            buttons += f.id to b
            row.addView(b)
        }
        paint()
        body.addView(scrolling(row, 0.2))
        return name to { chosen }
    }

    private fun pasteScreen(body: LinearLayout, folderId: Long) {
        val code = Ui.input(this, "Deck code appears here", multiLine = true)
        body.addView(code)
        buttonRow(body, Ui.button(this, "Paste") {
            val clip = clipboardText()
            if (clip == null || DeckCode.parse(clip) == null) {
                toast("No deck code on the clipboard. In Marvel Snap, tap Copy Deck Code first.")
            } else {
                code.setText(clip)
            }
        })
        val (name, folder) = nameAndFolder(body, folderId, "")
        buttonRow(body, Ui.button(this, "Save deck", Ui.PURPLE) {
            val text = code.text.toString()
            val parsed = DeckCode.parse(text) ?: return@button toast("That doesn't look like a deck code. Tap Paste.")
            val deckName = name.text.toString().trim().ifEmpty { "New deck" }
            val deck = DeckStore.addDeck(this, deckName, text, parsed.cards.map { it.key }, parsed.code, folder())
            stack.removeAll { it is Screen.Create || it is Screen.Paste }
            stack.add(Screen.DeckView(deck.id))
            render()
        })
        clipboardText()?.let { if (DeckCode.parse(it) != null) code.setText(it) }
        focusKeyboard(name)
    }

    private fun pickScreen(body: LinearLayout) {
        val db = CardDb.get(this)
        val search = Ui.input(this, "Search cards").apply { setText(pickQuery); setSelection(pickQuery.length) }
        body.addView(search)

        val chosenList = Ui.vertical(this)
        val results = Ui.vertical(this)
        val next = Ui.button(this, "Next", Ui.PURPLE) {
            if (picked.size == DECK_SIZE) go(Screen.NamePicked((stack.last() as Screen.Pick).folderId))
            else toast("Pick ${DECK_SIZE - picked.size} more.")
        }

        fun refresh() {
            chosenList.removeAllViews()
            if (picked.isEmpty()) {
                chosenList.addView(Ui.text(this, "Tap cards below to add them.", 13f, Ui.MUTED))
            }
            for (c in picked.sortedWith(compareBy<CardInfo>({ it.cost }, { it.name }))) {
                val row = Ui.cardRow(this, ShownCard(DeckCode.shortKey(c.id), c.name, c.cost, c.power), trailing = "✕")
                row.setOnClickListener { picked.remove(c); refresh() }
                chosenList.addView(row)
            }
            results.removeAllViews()
            val q = pickQuery.trim().lowercase()
            val matches = db.released.filter { q.isEmpty() || it.name.lowercase().contains(q) }
            for (c in matches.take(60)) {
                val already = picked.any { it.id == c.id }
                val row = Ui.cardRow(this, ShownCard(DeckCode.shortKey(c.id), c.name, c.cost, c.power),
                    drawn = already, trailing = if (already) "✓" else "+")
                row.setOnClickListener {
                    when {
                        already -> picked.removeAll { it.id == c.id }
                        picked.size >= DECK_SIZE -> toast("A deck has $DECK_SIZE cards. Tap ✕ on one to swap it.")
                        else -> picked.add(c)
                    }
                    refresh()
                }
                results.addView(row)
            }
            if (matches.isEmpty()) results.addView(Ui.text(this, "No cards match.", 13f, Ui.MUTED))
            next.alpha = if (picked.size == DECK_SIZE) 1f else 0.5f
            (panel?.findViewWithTag<TextView>("title"))?.text = "Pick cards  ${picked.size}/$DECK_SIZE"
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { pickQuery = s?.toString() ?: ""; refresh() }
        })

        body.addView(Ui.text(this, "Your deck", 13f, Ui.MUTED, bold = true).apply { layoutParams = Ui.matchWidth(context, 8) })
        body.addView(scrolling(chosenList, 0.22))
        body.addView(Ui.text(this, "All cards", 13f, Ui.MUTED, bold = true).apply { layoutParams = Ui.matchWidth(context, 8) })
        body.addView(scrolling(results, 0.3))
        buttonRow(body, next)
        refresh()
    }

    private fun namePickedScreen(body: LinearLayout, folderId: Long) {
        if (picked.size != DECK_SIZE) return back()
        val (name, folder) = nameAndFolder(body, folderId, "")
        buttonRow(body, Ui.button(this, "Save deck", Ui.PURPLE) {
            val deckName = name.text.toString().trim().ifEmpty { "New deck" }
            val text = DeckCode.gameText(picked)
            val deck = DeckStore.addDeck(this, deckName, text, picked.map { DeckCode.shortKey(it.id) },
                DeckCode.encode(picked.map { it.id }), folder())
            picked.clear(); pickQuery = ""
            stack.removeAll { it is Screen.Create || it is Screen.Pick || it is Screen.NamePicked }
            stack.add(Screen.DeckView(deck.id))
            render()
            toast("Saved. Tap Copy code to import it into Marvel Snap.")
        })
        focusKeyboard(name)
    }

    // ---------- Helpers ----------

    private fun clipboardText(): String? = runCatching {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return@runCatching null
        if (clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(this)?.toString()
    }.getOrNull()

    private fun focusKeyboard(field: EditText) {
        field.post {
            field.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
