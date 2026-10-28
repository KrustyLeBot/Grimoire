package app.grimoire.source

/** How chapters of a series are best read: the reader's default mode follows it. */
enum class Kind(val raw: String) {
    MANGA("manga"), WEBTOON("webtoon"), NOVEL("novel");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: WEBTOON
    }
}

data class SeriesSummary(val slug: String, val title: String, val cover: String?)

data class SeriesPage(val items: List<SeriesSummary>, val hasNext: Boolean)

/** [id] is the source's own chapter id (Poseidon: the number as text); [locked] = premium, not readable. */
data class ChapterInfo(
    val id: String,
    val name: String,
    val number: Float,
    val date: Long,
    val locked: Boolean = false,
)

data class SeriesDetails(
    val slug: String,
    val title: String,
    val cover: String?,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val kind: Kind = Kind.WEBTOON,
    /** Newest first. */
    val chapters: List<ChapterInfo> = emptyList(),
)

sealed interface ChapterContent {
    data class Images(val urls: List<String>) : ChapterContent
    /** For sources of regular books: the chapter's paragraphs. */
    data class Text(val paragraphs: List<String>) : ChapterContent
}

/** A source the user added: a [type] from the catalog, with its address and options. */
data class SourceConfig(
    val id: String,
    val type: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val options: Map<String, String> = emptyMap(),
)

interface Source {
    val config: SourceConfig
    suspend fun popular(page: Int): SeriesPage
    suspend fun latest(page: Int): SeriesPage
    suspend fun search(query: String, page: Int): SeriesPage
    suspend fun details(slug: String): SeriesDetails
    suspend fun content(slug: String, chapter: ChapterInfo): ChapterContent
    /** Headers to send with every image of this source. */
    fun imageHeaders(): Map<String, String>
    /** Where to open a series in a browser (and the WebView login). */
    fun seriesUrl(slug: String): String
}

/** A boolean option of a source type, shown as a switch in the source's settings. */
data class OptionSpec(val key: String, val label: String, val hint: String, val default: Boolean)

/** An entry of the built-in catalog: what "Ajouter une source" offers. */
data class SourceType(
    val id: String,
    val name: String,
    val lang: String,
    val description: String,
    val defaultBaseUrl: String,
    val options: List<OptionSpec> = emptyList(),
    /** The site has accounts (premium chapters…): a WebView login is offered. */
    val hasLogin: Boolean = false,
    val create: (SourceConfig, Http) -> Source,
)

object Catalog {
    val types: List<SourceType> = listOf(app.grimoire.source.poseidon.PoseidonScans.TYPE)

    fun type(id: String) = types.firstOrNull { it.id == id }
}

class SourceException(message: String) : Exception(message)
