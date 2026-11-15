package app.snapoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Shows a small round button over other apps. Tapping it opens a panel with the chosen
 * deck's cards; tapping a card greys it out (drawn). Drag the button to move it.
 */
class OverlayService : Service() {

    companion object {
        @Volatile var running = false
            private set

        private const val ACTION_STOP = "app.snapoverlay.STOP"
        private const val ACTION_RELOAD = "app.snapoverlay.RELOAD"
        private const val CHANNEL = "overlay"
        private const val POS_PREFS = "overlay_position"

        /** Tell a running overlay that the saved decks changed. */
        fun deckChanged(context: Context) {
            if (running) context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_RELOAD))
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panel: View? = null
    private var choosingDeck = false
    private val drawn = mutableSetOf<String>()
    private var shownDeckId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_RELOAD -> if (panel != null) rebuildPanel()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        closePanel()
        runCatching { wm.removeView(bubble) }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating button", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Snap Overlay is on")
            .setContentText("Tap Stop to remove the floating button.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    // ---- The round button ----

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val size = Ui.dp(this, 52)
        bubble = Ui.text(this, "S", 22f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.oval(Ui.PURPLE)
            alpha = 0.9f
            elevation = Ui.dp(context, 6).toFloat()
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
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x; startY = bubbleParams.y
                    touchX = e.rawX; touchY = e.rawY; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        bubbleParams.x = startX + dx; bubbleParams.y = startY + dy
                        wm.updateViewLayout(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        pos.edit().putInt("x", bubbleParams.x).putInt("y", bubbleParams.y).apply()
                    } else {
                        togglePanel()
                    }
                }
            }
            true
        }
        wm.addView(bubble, bubbleParams)
    }

    // ---- The deck panel ----

    private fun togglePanel() {
        if (panel != null) closePanel() else openPanel()
    }

    private fun openPanel() {
        choosingDeck = DeckStore.current(this) == null
        rebuildPanel()
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun rebuildPanel() {
        closePanel()
        val metrics = resources.displayMetrics
        val width = minOf(metrics.widthPixels - Ui.dp(this, 32), Ui.dp(this, 340))
        val view = if (choosingDeck) buildDeckPicker() else buildCardList(metrics.heightPixels)
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = Ui.dp(this@OverlayService, 40)
        }
        wm.addView(view, params)
        panel = view
    }

    private fun panelBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = Ui.dp(context, 12)
        setPadding(p, p, p, p)
        background = Ui.rounded(Ui.PANEL_BG, Ui.dp(context, 14).toFloat())
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
    }

    private fun header(box: LinearLayout, title: String, vararg buttons: Button) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(Ui.text(this, title, 17f, Color.WHITE, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        for (b in buttons) row.addView(b)
        box.addView(row)
    }

    private fun buildDeckPicker(): View {
        val box = panelBox()
        header(box, "Which deck?", smallButton("Close") { closePanel() })
        val decks = DeckStore.all(this)
        if (decks.isEmpty()) {
            box.addView(Ui.text(this,
                "No decks saved yet. Open the Snap Overlay app and tap Add deck.", 14f, Color.LTGRAY))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (deck in decks) {
            list.addView(Button(this).apply {
                text = deck.name
                isAllCaps = false
                setOnClickListener {
                    DeckStore.setCurrent(context, deck.id)
                    choosingDeck = false
                    rebuildPanel()
                }
            })
        }
        box.addView(MaxHeightScrollView(this, resources.displayMetrics.heightPixels / 2).apply { addView(list) })
        return box
    }

    private fun buildCardList(screenHeight: Int): View {
        val deck = DeckStore.current(this)
        if (deck == null) {
            choosingDeck = true
            return buildDeckPicker()
        }
        if (deck.id != shownDeckId) {
            drawn.clear()
            shownDeckId = deck.id
        }
        val cards = DeckStore.cards(this, deck)
        val box = panelBox()
        header(box, deck.name,
            smallButton("Change") { choosingDeck = true; rebuildPanel() },
            smallButton("Close") { closePanel() })
        box.addView(Ui.text(this, "Tap a card when you draw it.", 12f, Color.LTGRAY))

        val left = Ui.text(this, "", 14f, Color.WHITE, bold = true)
        fun updateLeft() {
            val n = cards.count { it.key !in drawn }
            left.text = if (n == 0) "Deck empty" else "$n left in deck · each 1 in $n to draw next"
        }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (card in cards) {
            val row = Ui.cardRow(this, card, dark = true, drawn = card.key in drawn)
            row.setOnClickListener {
                if (!drawn.remove(card.key)) drawn.add(card.key)
                Ui.setDrawn(row, card.key in drawn)
                updateLeft()
            }
            list.addView(row)
        }
        box.addView(MaxHeightScrollView(this, (screenHeight * 0.6).toInt()).apply { addView(list) })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        left.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        footer.addView(left)
        footer.addView(smallButton("Reset") { drawn.clear(); rebuildPanel() })
        box.addView(footer)
        updateLeft()
        return box
    }
}
