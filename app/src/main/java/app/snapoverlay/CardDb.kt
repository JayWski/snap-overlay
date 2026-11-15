package app.snapoverlay

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class CardInfo(val id: String, val name: String, val cost: Int, val power: Int)

/**
 * The card list (name, cost, power). A copy ships inside the app (assets/cards.json).
 * "Update card list" downloads a newer one from marvelsnapzone.com and keeps it in the
 * app's own storage, which is then used instead of the built-in copy.
 */
class CardDb private constructor(private val byKey: Map<String, CardInfo>, val updated: String) {

    fun find(key: String): CardInfo? = byKey[key]
    val size: Int get() = byKey.size

    companion object {
        const val SOURCE_URL = "https://marvelsnapzone.com/getinfo/?searchtype=cards&searchcardstype=true"
        private const val SAVED_FILE = "cards.json"

        @Volatile private var cached: CardDb? = null

        fun get(context: Context): CardDb {
            cached?.let { return it }
            val saved = File(context.filesDir, SAVED_FILE)
            val db = (if (saved.exists()) runCatching { fromSlimJson(saved.readText()) }.getOrNull() else null)
                ?: fromSlimJson(context.assets.open(SAVED_FILE).bufferedReader().use { it.readText() })
            cached = db
            return db
        }

        /** Reads our slim format: {"updated": "...", "cards": [{"id","name","cost","power"}]}. */
        fun fromSlimJson(text: String): CardDb {
            val root = JSONObject(text)
            val arr = root.getJSONArray("cards")
            val map = HashMap<String, CardInfo>()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val info = CardInfo(c.getString("id"), c.getString("name"), c.getInt("cost"), c.getInt("power"))
                map[DeckCode.shortKey(info.id)] = info
            }
            return CardDb(map, root.optString("updated", "?"))
        }

        /** Turns the site's full card list into our slim format. */
        fun slimFromSite(text: String, today: String): String {
            val cards = JSONObject(text).getJSONObject("success").getJSONArray("cards")
            val out = org.json.JSONArray()
            for (i in 0 until cards.length()) {
                val c = cards.getJSONObject(i)
                val id = c.optString("carddefid")
                if (id.isEmpty() || !c.has("cost") || !c.has("power")) continue
                out.put(JSONObject().apply {
                    put("id", id)
                    put("name", c.optString("name", id).replace(Regex("<[^>]*>"), ""))
                    put("cost", c.getInt("cost"))
                    put("power", c.getInt("power"))
                })
            }
            if (out.length() < 300) throw IllegalStateException("The card list looked too short (${out.length()} cards).")
            return JSONObject().put("updated", today).put("cards", out).toString()
        }

        /** Downloads a fresh card list. Call from a background thread. Returns the number of cards. */
        fun download(context: Context): Int {
            val conn = URL(SOURCE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; SnapOverlay card list)")
            try {
                if (conn.responseCode != 200) throw IllegalStateException("The site answered ${conn.responseCode}.")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                val slim = slimFromSite(body, today)
                val db = fromSlimJson(slim)
                File(context.filesDir, SAVED_FILE).writeText(slim)
                cached = db
                return db.size
            } finally {
                conn.disconnect()
            }
        }
    }
}
