package app.grimoire.book

import app.grimoire.source.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Title, authors, summary, language and cover of a book, from the internet rather than from the
 * file: Google Books first (best for French editions), Open Library to fill the gaps and for its
 * larger covers. No account or key. Each ISBN the file carries is tried in turn (the digital one
 * is often unknown where the print one is); a file without ISBN is searched by title and author.
 */
class BookLookup(private val http: Http) {
    /** Null when nothing matches; network errors are thrown. */
    suspend fun find(isbns: List<String>, title: String, authors: List<String>, language: String?): BookInfo? {
        for (isbn in isbns) byIsbn(isbn)?.let { return it.copy(isbns = (isbns + it.isbns).distinct()) }
        return byTitle(title, authors, language)
    }

    private suspend fun byIsbn(isbn: String): BookInfo? {
        val google = BookInfo.fromGoogle(http.get("$GOOGLE?q=isbn:$isbn")).firstOrNull()
        val openLibrary = runCatching {
            BookInfo.fromOpenLibrary(http.get("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data"), isbn)
        }.getOrNull()
        val info = (google ?: openLibrary ?: return null).or(openLibrary)
        val bigCover = openLibrary?.cover ?: openLibraryCover(isbn)
        return (if (bigCover != null) info.copy(cover = bigCover) else info).let { it.copy(isbns = (listOf(isbn) + it.isbns).distinct()) }
    }

    /** The first result whose title matches, in the book's language when it is known. */
    private suspend fun byTitle(title: String, authors: List<String>, language: String?): BookInfo? {
        val q = buildString {
            append("intitle:").append(enc(title))
            authors.firstOrNull()?.let { append("+inauthor:").append(enc(it.substringAfterLast(' '))) }
        }
        val lang = language?.take(2)?.lowercase()?.let { "&langRestrict=$it" }.orEmpty()
        val wanted = simple(title)
        return BookInfo.fromGoogle(http.get("$GOOGLE?q=$q$lang&maxResults=10")).firstOrNull { r ->
            val t = simple(r.title)
            t == wanted || t.startsWith(wanted) || wanted.startsWith(t)
        }
    }

    /** Open Library's cover by ISBN, only if it has one (`default=false` answers 404 otherwise). */
    private suspend fun openLibraryCover(isbn: String): String? = withContext(Dispatchers.IO) {
        val url = "https://covers.openlibrary.org/b/isbn/$isbn-L.jpg?default=false"
        runCatching {
            http.images.newCall(Request.Builder().url(url).head().build()).execute().use { if (it.isSuccessful) url else null }
        }.getOrNull()
    }

    private companion object {
        const val GOOGLE = "https://www.googleapis.com/books/v1/volumes"

        fun enc(s: String) = URLEncoder.encode("\"$s\"", "UTF-8")

        /** Lower case, no accents or punctuation: "L'Étranger" and "l etranger" compare equal. */
        fun simple(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
