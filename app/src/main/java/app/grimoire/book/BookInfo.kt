package app.grimoire.book

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** What the internet knows of a book edition. Cached on each phone, never synced. */
data class BookInfo(
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val publisher: String? = null,
    val published: String? = null,
    val pages: Int? = null,
    /** ISO 639 code ("fr", "en"…). */
    val language: String? = null,
    val description: String? = null,
    val cover: String? = null,
    /** The edition's ISBNs (ISBN-13), digital and print, as the catalogues list them. */
    val isbns: List<String> = emptyList(),
) {
    /** Fields this one lacks, taken from [other]; ISBNs are merged. */
    fun or(other: BookInfo?): BookInfo = if (other == null) this else copy(
        subtitle = subtitle ?: other.subtitle,
        authors = authors.ifEmpty { other.authors },
        publisher = publisher ?: other.publisher,
        published = published ?: other.published,
        pages = pages ?: other.pages,
        language = language ?: other.language,
        description = description ?: other.description,
        cover = cover ?: other.cover,
        isbns = (isbns + other.isbns).distinct(),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Open Library edition record (`/isbn/<isbn>.json`): the key of its work. */
        fun workOf(text: String): String? =
            ((json.parseToJsonElement(text) as? JsonObject)?.arr("works")?.firstOrNull() as? JsonObject)?.str("key")

        /** Open Library work record: its description, a string or `{"value": …}`. */
        fun summaryOf(text: String): String? {
            val d = (json.parseToJsonElement(text) as? JsonObject)?.get("description") ?: return null
            return ((d as? JsonObject)?.str("value") ?: (d as? JsonPrimitive)?.contentOrNull)?.trim()?.ifEmpty { null }
        }

        /** Open Library `api/books?jscmd=data` answer for one ISBN. */
        fun fromOpenLibrary(text: String, isbn: String): BookInfo? {
            val b = (json.parseToJsonElement(text) as? JsonObject)?.obj("ISBN:$isbn") ?: return null
            val title = b.str("title") ?: return null
            val ids = b.obj("identifiers")
            return BookInfo(
                title = title,
                subtitle = b.str("subtitle"),
                authors = b.names("authors"),
                publisher = b.names("publishers").firstOrNull(),
                published = b.str("publish_date"),
                pages = b.int("number_of_pages"),
                cover = b.obj("cover")?.let { it.str("large") ?: it.str("medium") },
                isbns = (listOf(isbn) + ids?.strings("isbn_13").orEmpty() + ids?.strings("isbn_10").orEmpty())
                    .mapNotNull(Isbn::normalize).distinct(),
            )
        }

        /** BnF catalogue SRU answer (Dublin Core): the reference for books published in France. */
        fun fromBnf(text: String, isbn: String): BookInfo? {
            val record = Jsoup.parse(text, "", Parser.xmlParser()).getElementsByTag("oai_dc:dc").firstOrNull() ?: return null
            fun all(tag: String) = record.getElementsByTag(tag).map { it.text().trim() }.filter { it.isNotEmpty() }
            val title = all("dc:title").firstOrNull()?.substringBefore(" / ")?.substringBefore(" : ")?.trim()?.ifEmpty { null } ?: return null
            return BookInfo(
                title = title,
                authors = all("dc:creator").map(::bnfName).filter { it.isNotEmpty() },
                publisher = all("dc:publisher").firstOrNull()?.substringBefore(" (")?.trim(),
                published = all("dc:date").firstOrNull(),
                pages = all("dc:format").firstNotNullOfOrNull { Regex("""(\d+)\s*p\.""").find(it)?.groupValues?.get(1)?.toIntOrNull() },
                language = all("dc:language").firstOrNull()?.let(::iso639),
                // Notes ("Bibliogr.", "Index") are short; a summary is a paragraph.
                description = all("dc:description").filter { it.length >= 150 }.maxByOrNull { it.length },
                isbns = (listOf(isbn) + all("dc:identifier").flatMap { Isbn.all(it) }).distinct(),
            )
        }

        /** "Saint-Exupéry, Antoine de (1900-1944). Auteur du texte" → "Antoine de Saint-Exupéry". */
        internal fun bnfName(raw: String): String {
            val name = raw.substringBefore(" (").substringBefore(". ").trim().removeSuffix(".")
            val last = name.substringBefore(", ")
            val first = name.substringAfter(", ", "")
            return if (first.isEmpty()) last else "$first $last"
        }

        /** Catalogues give ISO 639-2 codes ("fre", "eng"), the app shows ISO 639-1. */
        internal fun iso639(code: String): String {
            val c = code.trim().lowercase()
            return BIBLIOGRAPHIC[c] ?: java.util.Locale.forLanguageTag(c).language.takeIf { it.length == 2 } ?: c
        }

        private val BIBLIOGRAPHIC = mapOf(
            "fre" to "fr", "fra" to "fr", "eng" to "en", "ger" to "de", "deu" to "de", "spa" to "es", "ita" to "it",
            "por" to "pt", "dut" to "nl", "nld" to "nl", "jpn" to "ja", "chi" to "zh", "zho" to "zh", "kor" to "ko",
        )

        /** Open Library `search.json` answer: the first work, without its ISBNs (they mix editions and languages). */
        fun fromOpenLibrarySearch(text: String): BookInfo? {
            val doc = (json.parseToJsonElement(text) as? JsonObject)?.arr("docs")?.firstOrNull() as? JsonObject ?: return null
            val title = doc.str("title") ?: return null
            return BookInfo(
                title = title,
                authors = doc.strings("author_name"),
                published = (doc["first_publish_year"] as? JsonPrimitive)?.contentOrNull,
                cover = doc.int("cover_i")?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
            )
        }

        fun encode(i: BookInfo): String = buildJsonObject {
            put("title", i.title)
            i.subtitle?.let { put("subtitle", it) }
            put("authors", JsonArray(i.authors.map(::JsonPrimitive)))
            i.publisher?.let { put("publisher", it) }
            i.published?.let { put("published", it) }
            i.pages?.let { put("pages", it) }
            i.language?.let { put("language", it) }
            i.description?.let { put("description", it) }
            i.cover?.let { put("cover", it) }
            put("isbns", JsonArray(i.isbns.map(::JsonPrimitive)))
        }.toString()

        fun decode(text: String): BookInfo {
            val o = json.parseToJsonElement(text) as JsonObject
            return BookInfo(
                title = o.str("title").orEmpty(), subtitle = o.str("subtitle"), authors = o.strings("authors"),
                publisher = o.str("publisher"), published = o.str("published"), pages = o.int("pages"),
                language = o.str("language"), description = o.str("description"), cover = o.str("cover"),
                isbns = o.strings("isbns"),
            )
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.ifEmpty { null }
        private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        private fun JsonObject.obj(key: String) = this[key] as? JsonObject
        private fun JsonObject.arr(key: String) = this[key] as? JsonArray
        private fun JsonObject.strings(key: String) =
            arr(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
        private fun JsonObject.names(key: String) = arr(key).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }

    }
}
