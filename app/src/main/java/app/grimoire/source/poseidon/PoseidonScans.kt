package app.grimoire.source.poseidon

import app.grimoire.source.ChapterContent
import app.grimoire.source.ChapterInfo
import app.grimoire.source.Http
import app.grimoire.source.Kind
import app.grimoire.source.NextJs
import app.grimoire.source.OptionSpec
import app.grimoire.source.SeriesDetails
import app.grimoire.source.SeriesPage
import app.grimoire.source.SeriesSummary
import app.grimoire.source.Source
import app.grimoire.source.SourceConfig
import app.grimoire.source.SourceException
import app.grimoire.source.SourceType
import app.grimoire.source.arr
import app.grimoire.source.bool
import app.grimoire.source.num
import app.grimoire.source.obj
import app.grimoire.source.str
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/**
 * Poseidon Scans, a French scanlation site (mostly manhwa) built with Next.js. Follows the
 * community Mihon extension (keiyoushi/extensions-source, src/fr/poseidonscans); see
 * docs/SPECS.md §5. No account is needed; premium chapters (paid until a date, free after) are
 * hidden unless asked for, and need a premium account logged in through the WebView.
 */
class PoseidonScans(override val config: SourceConfig, private val http: Http) : Source {
    private val base = config.baseUrl.trimEnd('/')
    private val showPremium get() = config.options[OPTION_PREMIUM]?.toBoolean() ?: false
    private val rsc = mapOf("RSC" to "1")

    override fun seriesUrl(slug: String) = "$base/serie/$slug"

    override fun imageHeaders() = mapOf(
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
        "Referer" to "$base/",
    )

    private fun cover(slug: String) = "$base/api/covers/$slug.webp"

    override suspend fun latest(page: Int): SeriesPage {
        val body = JSONObject(http.get("$base/api/manga/lastchapters?limit=16&page=$page"))
        val data = body.optJSONArray("data")
        val items = List(data?.length() ?: 0) { i ->
            val o = data!!.getJSONObject(i)
            SeriesSummary(o.getString("slug"), o.getString("title"), cover(o.getString("slug")))
        }
        return SeriesPage(items, hasNext = items.size == 16)
    }

    override suspend fun popular(page: Int): SeriesPage {
        if (page > 1) return SeriesPage(emptyList(), false) // the home page holds the whole list
        val list = PoseidonParser.popular(http.get("$base/", rsc)) ?: throw SourceException("Liste illisible : le site a peut-être changé")
        return SeriesPage(list.map { it.copy(cover = cover(it.slug)) }, hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SeriesPage {
        val url = buildString {
            append("$base/series")
            val params = buildList {
                if (query.isNotBlank()) add("search=" + URLEncoder.encode(query, "UTF-8"))
                if (page > 1) add("page=$page")
            }
            if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
        }
        return PoseidonParser.search(http.get(url), base)
    }

    override suspend fun details(slug: String): SeriesDetails {
        val page = http.get(seriesUrl(slug))
        val info = PoseidonParser.details(page) ?: throw SourceException("Fiche illisible : le site a peut-être changé")
        // The chapter list comes from the RSC stream, which can be partial on a first hit.
        val chapters = PoseidonParser.chapters(http.get(seriesUrl(slug), rsc), showPremium)
            ?: PoseidonParser.chapters(http.get(seriesUrl(slug) + "?_=" + System.currentTimeMillis(), rsc), showPremium)
            ?: emptyList()
        return info.copy(cover = cover(slug), chapters = chapters)
    }

    override suspend fun content(slug: String, chapter: ChapterInfo): ChapterContent {
        val url = "${seriesUrl(slug)}/chapter/${chapter.id}"
        val wanted: (JsonObject) -> Boolean = { it.containsKey("initialData") && it.containsKey("currentChapter") }
        // Like the chapter list, the stream can come back partial: one retry past the cache.
        val data = NextJs.find(http.get(url, rsc), wanted)
            ?: NextJs.find(http.get(url + "?_=" + System.currentTimeMillis(), rsc), wanted)
            ?: throw SourceException("Chapitre illisible : le site a peut-être changé")
        val current = data.obj("currentChapter")
        val locked = current != null && current.bool("isPremium") == true && PoseidonParser.beforeFreeDate(current.str("premiumUntil"))
        if (locked && data.bool("isPremiumUser") != true) {
            throw SourceException(
                if (data.str("sessionStatus") == "unauthenticated") "Chapitre premium : connecte-toi avec un compte premium (réglages de la source)"
                else "Chapitre premium : ton compte n'est pas premium",
            )
        }
        val images = data.obj("initialData")?.arr("images").orEmpty().filterIsInstance<JsonObject>()
            .sortedBy { it.num("order") ?: 0.0 }
            .mapNotNull { it.str("originalUrl") }
            .map { if (it.startsWith("http")) it else base + it }
        if (images.isEmpty()) throw SourceException("Aucune image dans ce chapitre")
        return ChapterContent.Images(images)
    }

    companion object {
        const val OPTION_PREMIUM = "show_premium"

        val TYPE = SourceType(
            id = "poseidon-scans",
            name = "Poseidon Scans",
            lang = "fr",
            description = "Scans en français, surtout des webtoons et manhwas. Sans compte ; les chapitres premium demandent un compte premium.",
            defaultBaseUrl = "https://poseidon-scans.net",
            options = listOf(
                OptionSpec(
                    OPTION_PREMIUM, "Afficher les chapitres premium",
                    "Chapitres payants jusqu'à une date (🔒), lisibles seulement avec un compte premium connecté", default = false,
                ),
            ),
            hasLogin = true,
            create = { config, http -> PoseidonScans(config, http) },
        )
    }
}

/** The parsing, apart from the network so it can be unit-tested on saved pages. */
object PoseidonParser {
    fun popular(rsc: String): List<SeriesSummary>? =
        NextJs.findList(rsc) { it.containsKey("id") && it.containsKey("title") && it.containsKey("slug") }
            ?.mapNotNull { o -> o.str("slug")?.let { SeriesSummary(it, o.str("title").orEmpty(), null) } }

    fun search(html: String, base: String): SeriesPage {
        val document = Jsoup.parse(html, base)
        val items = document.select("div.grid a.block.group").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/serie/", "").substringBefore("/").substringBefore("?")
            val title = a.selectFirst("h2")?.text() ?: return@mapNotNull null
            if (slug.isBlank()) return@mapNotNull null
            val cover = a.selectFirst("img[alt]")?.attr("srcset")?.substringBefore(" ")
                ?.let { URLDecoder.decode(it, "UTF-8").substringAfter("url=").substringBefore("&") }
                ?.takeIf { it.isNotBlank() }
                ?.let { coverUrl(it, base) }
            SeriesSummary(slug, title, cover)
        }
        val hasNext = document.select("nav[aria-label=Pagination] a:contains(Suivant)").isNotEmpty()
        return SeriesPage(items, hasNext)
    }

    private fun coverUrl(path: String, base: String): String = when {
        path.startsWith("http") -> path
        "storage/covers/" in path -> "$base/api/covers/${path.substringAfter("storage/covers/")}"
        path.startsWith("/") -> base + path
        else -> "$base/api/covers/$path"
    }

    fun details(html: String): SeriesDetails? {
        val o = NextJs.find(Jsoup.parse(html)) {
            it.containsKey("title") && it.containsKey("slug") && it.containsKey("description") && it.containsKey("status")
        } ?: return null
        val genres = o.arr("categories").orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { it.str("name")?.trim()?.takeIf { n -> n.isNotBlank() }?.replaceFirstChar { c -> c.titlecase(Locale.FRENCH) } }
        val kind = if (genres.any { it.equals("manga", true) }) Kind.MANGA else Kind.WEBTOON
        return SeriesDetails(
            slug = o.str("slug").orEmpty(),
            title = o.str("title").orEmpty(),
            cover = null,
            author = o.str("author")?.takeIf { it.isNotBlank() },
            artist = o.str("artist")?.takeIf { it.isNotBlank() },
            status = status(o.str("status")),
            genres = genres,
            description = o.str("description")?.trim()?.takeIf { it.isNotEmpty() },
            kind = kind,
        )
    }

    private fun status(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.FRENCH)) {
        "en cours" -> "En cours"
        "terminé" -> "Terminé"
        "en pause", "hiatus" -> "En pause"
        "annulé", "abandonné" -> "Abandonné"
        else -> raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Null when the stream did not carry the list (partial first hit): the caller retries. */
    fun chapters(rsc: String, showPremium: Boolean): List<ChapterInfo>? {
        val page = NextJs.find(rsc) { o -> o.containsKey("manga") && o.obj("manga")?.containsKey("chapters") == true } ?: return null
        val premiumUser = page.bool("isPremiumUser") == true
        val chapters = page.obj("manga")?.arr("chapters").orEmpty().filterIsInstance<JsonObject>()
        if (chapters.isEmpty()) return null
        return chapters.mapNotNull { ch ->
            val number = ch.num("number")?.toFloat() ?: return@mapNotNull null
            val locked = ch.bool("isPremium") == true && beforeFreeDate(ch.str("premiumUntil")) && !premiumUser
            if (locked && !showPremium) return@mapNotNull null
            val id = number.toString().removeSuffix(".0")
            val title = ch.str("title")?.trim()?.takeIf { it.isNotBlank() }
            val volume = ch.bool("isVolume") == true || (number % 1 == 0f && title?.contains("volume", true) == true)
            val base = if (volume) "Volume $id" else "Chapitre $id"
            ChapterInfo(
                id = id,
                name = buildString {
                    if (locked) append("🔒 ")
                    append(if (title != null && !title.equals(base, true)) "$base - $title" else base)
                },
                number = number,
                date = parseDate(ch.str("createdAt")),
                locked = locked,
            )
        }.sortedByDescending { it.number }
    }

    /** The site keeps isPremium set after premiumUntil has passed: past that date it is free. */
    fun beforeFreeDate(premiumUntil: String?): Boolean {
        val until = premiumUntil?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return true
        return until > System.currentTimeMillis()
    }

    private fun parseDate(iso: String?): Long = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}
