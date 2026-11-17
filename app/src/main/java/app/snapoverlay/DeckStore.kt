package app.snapoverlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Folder(val id: Long, val name: String)

/** A saved deck. [text] is the game-style text, so "Copy code" gives the game the same thing back. */
data class Deck(
    val id: Long,
    val name: String,
    val text: String,
    val code: String,
    val keys: List<String>,
    val folderId: Long = DeckStore.LADDER_ID,
    val wins: Int = 0,
    val losses: Int = 0,
) {
    val games: Int get() = wins + losses
    /** "5–3 · 63%", or "No games yet". */
    val record: String
        get() = if (games == 0) "No games yet" else "$wins–$losses · ${(wins * 100 + games / 2) / games}%"
}

/** A card ready to show: card list info where known, otherwise what the pasted text said. */
data class ShownCard(val key: String, val name: String, val cost: Int?, val power: Int?)

/** Everything the app saves: folders and decks. */
data class Library(val folders: List<Folder>, val decks: List<Deck>)

object DeckStore {
    const val LADDER_ID = 1L
    private const val PREFS = "decks"
    private const val KEY_LIBRARY = "library"
    private const val KEY_OLD_DECKS = "decks" // version 0.1 kept only a deck list here

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Reading and writing (pure functions, tested without a phone) ----

    fun libraryFromJson(libraryJson: String?, oldDecksJson: String?): Library {
        if (libraryJson != null) {
            val root = runCatching { JSONObject(libraryJson) }.getOrNull()
            if (root != null) {
                val folders = root.optJSONArray("folders").objects().map { Folder(it.getLong("id"), it.getString("name")) }
                val decks = root.optJSONArray("decks").objects().map(::deckFromJson)
                return Library(withLadder(folders), decks)
            }
        }
        // First start after updating from 0.1: its decks all go into Ladder.
        val oldDecks = oldDecksJson?.let { runCatching { JSONArray(it) }.getOrNull() }.objects().map(::deckFromJson)
        return Library(withLadder(emptyList()), oldDecks)
    }

    fun libraryToJson(library: Library): String {
        val folders = JSONArray()
        for (f in library.folders) folders.put(JSONObject().put("id", f.id).put("name", f.name))
        val decks = JSONArray()
        for (d in library.decks) {
            decks.put(JSONObject().apply {
                put("id", d.id); put("name", d.name); put("text", d.text); put("code", d.code)
                put("keys", JSONArray(d.keys)); put("folder", d.folderId)
                put("wins", d.wins); put("losses", d.losses)
            })
        }
        return JSONObject().put("folders", folders).put("decks", decks).toString()
    }

    private fun withLadder(folders: List<Folder>): List<Folder> =
        if (folders.any { it.id == LADDER_ID }) folders else listOf(Folder(LADDER_ID, "Ladder")) + folders

    private fun deckFromJson(o: JSONObject): Deck {
        val keys = o.optJSONArray("keys") ?: JSONArray()
        return Deck(
            o.getLong("id"), o.getString("name"), o.getString("text"), o.getString("code"),
            (0 until keys.length()).map { keys.getString(it) },
            o.optLong("folder", LADDER_ID), o.optInt("wins", 0), o.optInt("losses", 0),
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    // ---- Saved on the phone ----

    fun load(context: Context): Library {
        val p = prefs(context)
        return libraryFromJson(p.getString(KEY_LIBRARY, null), p.getString(KEY_OLD_DECKS, null))
    }

    private fun save(context: Context, library: Library) {
        prefs(context).edit().putString(KEY_LIBRARY, libraryToJson(library)).remove(KEY_OLD_DECKS).apply()
    }

    private fun change(context: Context, edit: (Library) -> Library) = save(context, edit(load(context)))

    private fun newId(library: Library): Long =
        maxOf(System.currentTimeMillis(), (library.decks.map { it.id } + library.folders.map { it.id }).maxOrNull() ?: 0L) + 1

    fun folders(context: Context): List<Folder> = load(context).folders
    fun decksIn(context: Context, folderId: Long): List<Deck> = load(context).decks.filter { it.folderId == folderId }
    fun deck(context: Context, id: Long): Deck? = load(context).decks.firstOrNull { it.id == id }
    fun folder(context: Context, id: Long): Folder? = load(context).folders.firstOrNull { it.id == id }

    fun addFolder(context: Context, name: String): Folder {
        var made: Folder? = null
        change(context) { lib -> Folder(newId(lib), name).also { made = it }.let { lib.copy(folders = lib.folders + it) } }
        return made!!
    }

    fun renameFolder(context: Context, id: Long, name: String) = change(context) { lib ->
        lib.copy(folders = lib.folders.map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Deletes a folder. Its decks move to Ladder. Ladder itself can't be deleted. */
    fun deleteFolder(context: Context, id: Long) {
        if (id == LADDER_ID) return
        change(context) { lib ->
            lib.copy(
                folders = lib.folders.filter { it.id != id },
                decks = lib.decks.map { if (it.folderId == id) it.copy(folderId = LADDER_ID) else it },
            )
        }
    }

    fun addDeck(context: Context, name: String, text: String, keys: List<String>, code: String, folderId: Long): Deck {
        var made: Deck? = null
        change(context) { lib ->
            Deck(newId(lib), name, text.trim(), code, keys, folderId).also { made = it }
                .let { lib.copy(decks = lib.decks + it) }
        }
        return made!!
    }

    fun updateDeck(context: Context, id: Long, edit: (Deck) -> Deck) = change(context) { lib ->
        lib.copy(decks = lib.decks.map { if (it.id == id) edit(it) else it })
    }

    fun deleteDeck(context: Context, id: Long) = change(context) { lib ->
        lib.copy(decks = lib.decks.filter { it.id != id })
    }

    /** The deck's cards, cheapest first, with name/cost/power filled in. */
    fun cards(context: Context, deck: Deck): List<ShownCard> {
        val db = CardDb.get(context)
        val fromText = DeckCode.parse(deck.text)?.cards?.associateBy { it.key } ?: emptyMap()
        return deck.keys.map { key ->
            val info = db.find(key)
            val pasted = fromText[key]
            ShownCard(key, info?.name ?: pasted?.name ?: key, info?.cost ?: pasted?.cost, info?.power)
        }.sortedWith(compareBy<ShownCard>({ it.cost ?: 99 }, { it.name }))
    }
}
