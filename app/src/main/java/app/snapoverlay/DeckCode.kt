package app.snapoverlay

import java.text.Normalizer
import java.util.Base64

/** One card from a deck code. [key] is the short form the game uses, e.g. "Lnclt8" for Lancelot. */
data class CodeCard(val key: String, val name: String?, val cost: Int?)

data class ParsedDeck(val code: String, val cards: List<CodeCard>)

/**
 * Reads the text the game puts on the clipboard with "Copy Deck Code".
 *
 * That text is a list of "# (cost) Card Name" lines plus one base64 code. Inside the
 * code, the current format is the 12 card ids, comma separated, each shortened by
 * dropping the lowercase vowels (a e i o u y) and adding the original length in hex:
 * "Lancelot" -> "Lnclt" + "8". Older codes were base64 JSON with "CardDefId" fields;
 * both are read.
 */
object DeckCode {

    private val headerLine = Regex("""^#\s*\((\d+)\)\s*(.+?)\s*$""")
    private val base64Token = Regex("""[A-Za-z0-9+/]{16,}={0,2}""")
    private val jsonCardId = Regex(""""CardDefId"\s*:\s*"([^"]+)"""")

    /** Card id as the game writes it: letters and digits only, accents removed. "Araña" -> "Arana". */
    fun idFromName(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("[^A-Za-z0-9]"), "")

    /** Short form used inside deck codes: "Lancelot" -> "Lnclt8". */
    fun shortKey(cardId: String): String =
        cardId.replace(Regex("[aeiouy]"), "") + Integer.toHexString(cardId.length).uppercase()

    fun parse(text: String): ParsedDeck? {
        // Some apps turn the line breaks into a literal "\n" when the text is pasted.
        val clean = text.replace("\\n", "\n").replace("\r", "")
        val lines = clean.lines().map { it.trim() }

        val named = mutableMapOf<String, CodeCard>()
        for (line in lines) {
            val m = headerLine.find(line) ?: continue
            val name = m.groupValues[2]
            val key = shortKey(idFromName(name))
            named[key] = CodeCard(key, name, m.groupValues[1].toIntOrNull())
        }

        for (line in lines) {
            if (line.startsWith("#") && headerLine.matches(line)) continue
            for (token in base64Token.findAll(line.removePrefix("#").trim())) {
                val keys = decodeKeys(token.value) ?: continue
                val cards = keys.map { named[it] ?: CodeCard(it, null, null) }
                return ParsedDeck(token.value, cards)
            }
        }
        return null
    }

    /** Builds a deck code from full card ids, in the same format the game uses today. */
    fun encode(cardIds: List<String>): String =
        Base64.getEncoder().encodeToString(cardIds.joinToString(",") { shortKey(it) }.toByteArray())

    private fun decodeKeys(token: String): List<String>? {
        val decoded = try {
            String(Base64.getDecoder().decode(token), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val trimmed = decoded.trim()
        if (trimmed.startsWith("{")) {
            val ids = jsonCardId.findAll(trimmed).map { shortKey(it.groupValues[1]) }.toList()
            return ids.ifEmpty { null }
        }
        val keys = trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val looksRight = keys.isNotEmpty() && keys.size <= 20 &&
            keys.all { it.matches(Regex("[A-Za-z0-9]+[0-9A-F]")) }
        return if (looksRight) keys else null
    }
}
