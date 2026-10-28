package app.grimoire.book

/**
 * ISBNs as books carry them: ISBN-10 or ISBN-13, with dashes or spaces, as `urn:isbn:`, after
 * "ISBN-13 :"… Always given back as ISBN-13, so one edition has one form.
 */
object Isbn {
    private val CANDIDATE = Regex("""(?<![\dXx])(97[89](?:[\- ]?\d){10}|\d(?:[\- ]?\d){8}[\- ]?[\dXx])(?![\dXx])""")

    /** The ISBN-13 in [raw] (a field holding one ISBN, however written), or null. */
    fun normalize(raw: String): String? = all(raw).firstOrNull()

    /** Every valid ISBN in a free text, in order, as ISBN-13. */
    fun all(text: String): List<String> = CANDIDATE.findAll(text).mapNotNull { strict(it.value) }.distinct().toList()

    fun find(text: String): String? = all(text).firstOrNull()

    private fun strict(candidate: String): String? {
        val d = candidate.uppercase().filter { it.isDigit() || it == 'X' }
        return when {
            d.length == 13 && d.all { it.isDigit() } && check13(d) -> d
            d.length == 10 && d.take(9).all { it.isDigit() } && check10(d) -> to13(d)
            else -> null
        }
    }

    private fun check13(d: String) = d.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum() % 10 == 0

    private fun check10(d: String) = d.mapIndexed { i, c -> (if (c == 'X') 10 else c - '0') * (10 - i) }.sum() % 11 == 0

    private fun to13(d10: String): String {
        val core = "978" + d10.take(9)
        val sum = core.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return core + ((10 - sum % 10) % 10)
    }
}
