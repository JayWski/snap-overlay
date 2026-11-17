package app.snapoverlay

import org.junit.Assert.assertEquals
import org.junit.Test

class DeckStoreTest {

    @Test fun decksFromVersion01MoveIntoLadder() {
        val old = """[{"id":5,"name":"Bounce","text":"t","code":"c","keys":["Zb4","Lnclt8"]}]"""
        val lib = DeckStore.libraryFromJson(null, old)
        assertEquals(listOf(Folder(DeckStore.LADDER_ID, "Ladder")), lib.folders)
        assertEquals(1, lib.decks.size)
        assertEquals(DeckStore.LADDER_ID, lib.decks[0].folderId)
        assertEquals(listOf("Zb4", "Lnclt8"), lib.decks[0].keys)
        assertEquals("No games yet", lib.decks[0].record)
    }

    @Test fun firstStartHasJustLadder() {
        val lib = DeckStore.libraryFromJson(null, null)
        assertEquals(listOf("Ladder"), lib.folders.map { it.name })
        assertEquals(0, lib.decks.size)
    }

    @Test fun foldersAndRecordsSurviveSaving() {
        val lib = Library(
            listOf(Folder(DeckStore.LADDER_ID, "Ladder"), Folder(7, "Conquest")),
            listOf(Deck(9, "Destroy", "text", "code", listOf("Zb4"), folderId = 7, wins = 5, losses = 3)),
        )
        val back = DeckStore.libraryFromJson(DeckStore.libraryToJson(lib), null)
        assertEquals(lib, back)
        assertEquals("5–3 · 63%", back.decks[0].record)
    }

    @Test fun ladderIsAlwaysThere() {
        val lib = DeckStore.libraryFromJson("""{"folders":[{"id":7,"name":"Conquest"}],"decks":[]}""", null)
        assertEquals(listOf("Ladder", "Conquest"), lib.folders.map { it.name })
    }
}
