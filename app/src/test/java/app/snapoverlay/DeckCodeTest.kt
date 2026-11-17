package app.snapoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Base64

class DeckCodeTest {

    // The owner's real decks, exactly as Marvel Snap copies them (with literal "\n").
    private val arthurDeck = """# (1) Zabu\n# (2) Surge\n# (2) Vampire Killmonger\n# (2) Captain Britain\n# (3) Mordred\n# (4) Jubilee\n# (4) Jubilee Silver Surfer\n# (4) Arthur Pendragon\n# (4) En Sabah Nur\n# (4) Lancelot\n# (4) Iron Lad\n# (6) Anti-Polar Magneto\n#\nWmI0LExuY2x0OCxDcHRuQnJ0bkUsQXJ0aHJQbmRyZ25GLE1yZHJkNyxBbnRQbHJNZ250MTAsSXJuTGQ3LEVuU2JoTnJBLEpibDcsU3JnNSxWbXByS2xsbW5ncjExLEpibFNsdnJTcmZyMTM=\n#\n# To use this deck, copy it to your clipboard and paste it from the deck editing menu in MARVEL SNAP."""
    private val spiderDeck = """# (1) Araña\n# (1) Ghost-Spider\n# (1) Iron Fist\n# (1) Vulture\n# (2) Madame Web\n# (2) Dagger\n# (2) Sparky\n# (2) Kraven\n# (2) Doctor Strange\n# (3) Human Torch\n# (3) Spider-Punk\n# (3) Friendly Neighborhood Carnage\n#\nQXJuNSxHaHN0U3BkckIsSXJuRnN0OCxWbHRyNyxNZG1XYjksRGdncjYsU3ByazYsS3J2bjYsRGN0clN0cm5nRCxIbW5UcmNoQSxTcGRyUG5rQSxGcm5kbE5naGJyaGRDcm5nMUI=\n#\n# To use this deck, copy it to your clipboard and paste it from the deck editing menu in MARVEL SNAP."""
    private val thanosDeck = """# (1) America Chavez\n# (1) Spider-Man Brand New Day\n# (1) Nightcrawler\n# (1) Black Cat\n# (1) Hydra Bob\n# (2) Adam Warlock\n# (2) Domino\n# (3) Marvel Boy\n# (3) Venus\n# (3) Jane Foster Fractured Frontier\n# (3) Elsa Bloodstone\n# (5) Thanos Fractured Frontier\n#\nQW1yY0NodnpELFNwZHJNbkJybmROd0QxNCxOZ2h0Y3J3bHJDLEJsY2tDdDgsSGRyQmI4LEFkbVdybGNrQixWbnM1LE1ydmxCOSxKbkZzdHJGcmN0cmRGcm50cjFCLFRobnNGcmN0cmRGcm50cjE3LEVsc0JsZHN0bkUsRG1uNg==\n#\n# To use this deck, copy it to your clipboard and paste it from the deck editing menu in MARVEL SNAP."""

    private val db: CardDb by lazy { CardDb.fromSlimJson(File("src/main/assets/cards.json").readText()) }

    @Test fun shortKeysFollowTheGameRule() {
        assertEquals("Lnclt8", DeckCode.shortKey("Lancelot"))
        assertEquals("HdrBb8", DeckCode.shortKey("HydraBob"))
        assertEquals("FrndlNghbrhdCrng1B", DeckCode.shortKey("FriendlyNeighborhoodCarnage"))
        assertEquals("Arana", DeckCode.idFromName("Araña"))
        assertEquals("AntiPolarMagneto", DeckCode.idFromName("Anti-Polar Magneto"))
    }

    @Test fun allThreeDecksReadWithNamesAndCosts() {
        for (text in listOf(arthurDeck, spiderDeck, thanosDeck)) {
            val deck = DeckCode.parse(text)
            assertNotNull(deck)
            assertEquals(12, deck!!.cards.size)
            for (card in deck.cards) {
                assertNotNull("name for ${card.key}", card.name)
                assertNotNull("cost for ${card.key}", card.cost)
            }
        }
    }

    @Test fun realLineBreaksWorkToo() {
        val deck = DeckCode.parse(spiderDeck.replace("\\n", "\n"))
        assertEquals(12, deck!!.cards.size)
        assertEquals("Araña", deck.cards.first { it.key == "Arn5" }.name)
    }

    @Test fun codeOnlyWithoutCardLines() {
        val deck = DeckCode.parse("QXJuNSxHaHN0U3BkckIsSXJuRnN0OCxWbHRyNyxNZG1XYjksRGdncjYsU3ByazYsS3J2bjYsRGN0clN0cm5nRCxIbW5UcmNoQSxTcGRyUG5rQSxGcm5kbE5naGJyaGRDcm5nMUI=")
        assertEquals(12, deck!!.cards.size)
        assertNull(deck.cards[0].name)
    }

    @Test fun oldJsonStyleCodes() {
        val json = """{"Name":"Old","Cards":[{"CardDefId":"Zabu"},{"CardDefId":"Lancelot"}]}"""
        val deck = DeckCode.parse(Base64.getEncoder().encodeToString(json.toByteArray()))
        assertEquals(listOf("Zb4", "Lnclt8"), deck!!.cards.map { it.key })
    }

    @Test fun notADeckCode() {
        assertNull(DeckCode.parse("hello there"))
        assertNull(DeckCode.parse(""))
    }

    @Test fun encodeMatchesTheGame() {
        val ids = listOf("Arana", "GhostSpider", "IronFist", "Vulture", "MadameWeb", "Dagger", "Sparky",
            "Kraven", "DoctorStrange", "HumanTorch", "SpiderPunk", "FriendlyNeighborhoodCarnage")
        assertEquals(DeckCode.parse(spiderDeck)!!.code, DeckCode.encode(ids))
    }

    @Test fun cardListHasEveryCardWithPower() {
        for (text in listOf(arthurDeck, spiderDeck, thanosDeck)) {
            for (card in DeckCode.parse(text)!!.cards) {
                val info = db.find(card.key)
                assertNotNull("card list is missing ${card.name}", info)
                assertEquals("cost of ${card.name}", card.cost, info!!.cost)
            }
        }
        assertEquals(6, db.find("Lnclt8")!!.power)
    }

    @Test fun pickedDeckTextReadsBackLikeAGameCode() {
        val ids = listOf("Arana", "GhostSpider", "IronFist", "Vulture", "MadameWeb", "Dagger", "Sparky",
            "Kraven", "DoctorStrange", "HumanTorch", "SpiderPunk", "FriendlyNeighborhoodCarnage")
        val cards = ids.map { id -> db.find(DeckCode.shortKey(id))!! }
        val text = DeckCode.gameText(cards)
        val parsed = DeckCode.parse(text)!!
        assertEquals(DeckCode.parse(spiderDeck)!!.code, parsed.code)
        assertEquals(12, parsed.cards.count { it.name != null && it.cost != null })
        org.junit.Assert.assertTrue(text.startsWith("# (1) "))
    }

    @Test fun releasedCardsAreListedForThePicker() {
        org.junit.Assert.assertTrue(db.released.size > 300)
        org.junit.Assert.assertTrue(db.released.any { it.name == "Lancelot" })
        org.junit.Assert.assertTrue(db.released.none { it.name.contains("Skill 01") })
    }
}
