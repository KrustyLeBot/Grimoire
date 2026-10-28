package app.grimoire.book

/** The page colours of the book reader, chosen apart from the app's own light or dark theme. */
enum class BookTheme(val raw: String, val label: String) {
    AUTO("auto", "Auto"),
    LIGHT("light", "Clair"),
    DARK("dark", "Sombre"),
    PAPER("paper", "Papier");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}

/** CSS colours of a reading page. */
data class PageColors(val background: String, val text: String, val link: String) {
    companion object {
        val LIGHT = PageColors("#FFFFFF", "#1C1B1F", "#7B2D8E")
        val DARK = PageColors("#121212", "#DEDAE0", "#E1A6EE")
        /** Warm paper, brown ink: easier on the eyes for long reading. */
        val PAPER = PageColors("#F4ECD8", "#4B3A2A", "#8A4B2A")
    }
}
