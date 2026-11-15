package app.snapoverlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved deck. [text] is exactly what was pasted, so "Copy" gives the game the same thing back. */
data class Deck(val id: Long, val name: String, val text: String, val code: String, val keys: List<String>)

/** A card ready to show: card list info where known, otherwise what the pasted text said. */
data class ShownCard(val key: String, val name: String, val cost: Int?, val power: Int?)

object DeckStore {
    private const val PREFS = "decks"
    private const val KEY_DECKS = "decks"
    private const val KEY_CURRENT = "current"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Deck> {
        val raw = prefs(context).getString(KEY_DECKS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val keys = o.optJSONArray("keys") ?: JSONArray()
            Deck(
                o.getLong("id"), o.getString("name"), o.getString("text"), o.getString("code"),
                (0 until keys.length()).map { keys.getString(it) },
            )
        }
    }

    private fun saveAll(context: Context, decks: List<Deck>) {
        val arr = JSONArray()
        for (d in decks) {
            arr.put(JSONObject().apply {
                put("id", d.id); put("name", d.name); put("text", d.text); put("code", d.code)
                put("keys", JSONArray(d.keys))
            })
        }
        prefs(context).edit().putString(KEY_DECKS, arr.toString()).apply()
    }

    fun add(context: Context, name: String, text: String, parsed: ParsedDeck): Deck {
        val deck = Deck(System.currentTimeMillis(), name, text.trim(), parsed.code, parsed.cards.map { it.key })
        saveAll(context, all(context) + deck)
        return deck
    }

    fun rename(context: Context, id: Long, name: String) =
        saveAll(context, all(context).map { if (it.id == id) it.copy(name = name) else it })

    fun delete(context: Context, id: Long) {
        saveAll(context, all(context).filter { it.id != id })
        if (currentId(context) == id) setCurrent(context, null)
    }

    fun currentId(context: Context): Long? =
        prefs(context).getLong(KEY_CURRENT, -1L).takeIf { it != -1L }

    fun current(context: Context): Deck? {
        val id = currentId(context) ?: return null
        return all(context).firstOrNull { it.id == id }
    }

    fun setCurrent(context: Context, id: Long?) =
        prefs(context).edit().putLong(KEY_CURRENT, id ?: -1L).apply()

    /** The deck's cards, cheapest first, with name/cost/power filled in. */
    fun cards(context: Context, deck: Deck): List<ShownCard> {
        val db = CardDb.get(context)
        val fromText = DeckCode.parse(deck.text)?.cards?.associateBy { it.key } ?: emptyMap()
        return deck.keys.map { key ->
            val info = db.find(key)
            val pasted = fromText[key]
            ShownCard(
                key,
                info?.name ?: pasted?.name ?: key.dropLast(1),
                info?.cost ?: pasted?.cost,
                info?.power,
            )
        }.sortedWith(compareBy<ShownCard>({ it.cost ?: 99 }, { it.name }))
    }
}
