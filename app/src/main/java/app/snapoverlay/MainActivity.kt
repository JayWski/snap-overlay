package app.snapoverlay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var overlayButton: Button
    private lateinit var deckList: LinearLayout
    private lateinit var cardListInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(Ui.text(this,
            "Save your decks here, then turn on the floating button and open Marvel Snap. " +
                "Tap the button during a match to see your deck.", 15f))
        root.addView(Ui.spacer(this, 12))

        overlayButton = Button(this).apply { setOnClickListener { toggleOverlay() } }
        root.addView(overlayButton)
        root.addView(Button(this).apply {
            text = "Add deck"
            setOnClickListener { showAddDeck() }
        })

        root.addView(Ui.spacer(this, 16))
        root.addView(Ui.text(this, "My decks", 20f, bold = true))
        deckList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deckList)

        root.addView(Ui.spacer(this, 24))
        cardListInfo = Ui.text(this, "", 13f, Color.DKGRAY)
        root.addView(cardListInfo)
        root.addView(Button(this).apply {
            text = "Update card list"
            setOnClickListener { updateCardList() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        overlayButton.text = if (OverlayService.running) "Turn off floating button" else "Turn on floating button"

        deckList.removeAllViews()
        val decks = DeckStore.all(this)
        val currentId = DeckStore.currentId(this)
        if (decks.isEmpty()) {
            deckList.addView(Ui.text(this,
                "No decks yet. In Marvel Snap, open a deck, tap Copy Deck Code, then come back and tap Add deck.",
                14f, Color.DKGRAY))
        }
        for (deck in decks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = Ui.dp(context, 12)
                setPadding(p, p, p, p)
                background = Ui.rounded(0xFFEDEAF7.toInt(), Ui.dp(context, 8).toFloat())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 8) }
                setOnClickListener { showDeck(deck) }
            }
            val title = if (deck.id == currentId) "${deck.name}  (in the floating button)" else deck.name
            row.addView(Ui.text(this, title, 17f, Color.BLACK, bold = true))
            val names = DeckStore.cards(this, deck).joinToString(", ") { it.name }
            row.addView(Ui.text(this, names, 13f, Color.DKGRAY).apply { maxLines = 2 })
            deckList.addView(row)
        }

        val db = CardDb.get(this)
        cardListInfo.text = "Card list: ${db.size} cards, updated ${db.updated}. Card details come from marvelsnapzone.com."
    }

    // ---- Floating button ----

    private fun toggleOverlay() {
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
            overlayButton.postDelayed({ refresh() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("One-time permission")
                .setMessage("To float over the game, Android needs you to allow \"Display over other apps\" " +
                    "for Snap Overlay. On the next screen, turn the switch on, then come back here.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Only used for the "Snap Overlay is on" notice with its Stop button; the overlay works without it.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        overlayButton.postDelayed({ refresh() }, 300)
    }

    // ---- Adding and showing decks ----

    private fun clipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun showAddDeck() {
        val pad = Ui.dp(this, 20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(context, 8), pad, 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Deck name, e.g. Bounce"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val codeInput = EditText(this).apply {
            hint = "Paste the deck code here"
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        clipboardText()?.let { if (DeckCode.parse(it) != null) codeInput.setText(it) }
        box.addView(nameInput)
        box.addView(codeInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add deck")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = codeInput.text.toString()
                val parsed = DeckCode.parse(text)
                if (parsed == null) {
                    toast("That doesn't look like a deck code. In Marvel Snap, use Copy Deck Code and paste it here.")
                    return@setOnClickListener
                }
                val name = nameInput.text.toString().trim().ifEmpty { "Deck ${DeckStore.all(this).size + 1}" }
                val deck = DeckStore.add(this, name, text, parsed)
                if (DeckStore.currentId(this) == null) DeckStore.setCurrent(this, deck.id)
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun showDeck(deck: Deck) {
        val pad = Ui.dp(this, 16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(context, 8), pad, 0)
        }
        for (card in DeckStore.cards(this, deck)) box.addView(Ui.cardRow(this, card, dark = false))
        box.addView(Button(this).apply {
            text = "Use in floating button"
            isEnabled = DeckStore.currentId(context) != deck.id
            setOnClickListener {
                DeckStore.setCurrent(context, deck.id)
                OverlayService.deckChanged(context)
                isEnabled = false
                refresh()
            }
        })

        AlertDialog.Builder(this)
            .setTitle(deck.name)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Copy code") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Deck code", deck.text))
                toast("Copied. In Marvel Snap, open a deck and use Paste / Import.")
            }
            .setNeutralButton("Rename") { _, _ -> showRename(deck) }
            .setNegativeButton("Delete") { _, _ -> confirmDelete(deck) }
            .show()
    }

    private fun showRename(deck: Deck) {
        val input = EditText(this).apply { setText(deck.name); setSelection(deck.name.length) }
        AlertDialog.Builder(this)
            .setTitle("Rename deck")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) DeckStore.rename(this, deck.id, name)
                OverlayService.deckChanged(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(deck: Deck) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${deck.name}?")
            .setPositiveButton("Delete") { _, _ ->
                DeckStore.delete(this, deck.id)
                OverlayService.deckChanged(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Card list ----

    private fun updateCardList() {
        toast("Updating card list…")
        Thread {
            val result = runCatching { CardDb.download(applicationContext) }
            runOnUiThread {
                result.onSuccess { toast("Card list updated: $it cards.") }
                    .onFailure { toast("Couldn't update the card list: ${it.message}") }
                refresh()
            }
        }.start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
