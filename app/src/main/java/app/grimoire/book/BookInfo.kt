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
    /** The edition's ISBNs (ISBN-13), digital and print, as the catalogue lists them. */
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

        /** Every volume of a Google Books `volumes` answer, in its order. */
        fun fromGoogle(text: String): List<BookInfo> =
            (json.parseToJsonElement(text) as? JsonObject)?.arr("items").orEmpty().mapNotNull { item ->
                val v = (item as? JsonObject)?.obj("volumeInfo") ?: return@mapNotNull null
                val title = v.str("title") ?: return@mapNotNull null
                BookInfo(
                    title = title,
                    subtitle = v.str("subtitle"),
                    authors = v.strings("authors"),
                    publisher = v.str("publisher"),
                    published = v.str("publishedDate"),
                    pages = v.int("pageCount"),
                    language = v.str("language"),
                    description = v.str("description"),
                    cover = v.obj("imageLinks")?.let { it.str("thumbnail") ?: it.str("smallThumbnail") }
                        ?.replace("http://", "https://")?.replace("&edge=curl", ""),
                    isbns = v.arr("industryIdentifiers").orEmpty()
                        .mapNotNull { (it as? JsonObject)?.str("identifier")?.let(Isbn::normalize) }.distinct(),
                )
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
