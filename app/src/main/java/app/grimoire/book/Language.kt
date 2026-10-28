package app.grimoire.book

/**
 * A rough guess of a text's language from its most common words, enough to tell a French summary
 * from an English one. Null when the text is too short or mixed to say.
 */
object Language {
    private val WORDS = mapOf(
        "fr" to setOf("le", "la", "les", "des", "une", "est", "et", "dans", "qui", "que", "pour", "pas", "sur", "avec", "son", "sa", "ses", "du", "au", "aux", "elle", "il"),
        "en" to setOf("the", "and", "of", "to", "is", "in", "that", "his", "her", "with", "for", "was", "on", "as", "by", "he", "she", "it", "an", "from"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "mit", "ein", "eine", "von", "zu", "den", "sich", "auf"),
        "es" to setOf("el", "los", "las", "una", "y", "que", "por", "con", "para", "del", "se", "su", "es", "lo"),
        "it" to setOf("il", "gli", "della", "che", "di", "una", "per", "con", "non", "sono", "nel", "alla"),
    )

    fun guess(text: String): String? {
        val words = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.size < 12) return null
        val scores = WORDS.mapValues { (_, set) -> words.count { it in set } }
        val (best, score) = scores.maxBy { it.value }
        val second = scores.filterKeys { it != best }.maxOf { it.value }
        return if (score >= 3 && score > second * 1.5) best else null
    }

    /** First summary in [language] (or whose language cannot be told); null rather than one in another language. */
    fun pick(language: String?, candidates: List<String?>): String? {
        val lang = language?.take(2)?.lowercase()
        return candidates.filterNotNull().map { it.trim() }.filter { it.length >= 40 }.firstOrNull { s ->
            lang == null || Language.guess(s).let { it == null || it == lang }
        }
    }
}
