package app.grimoire.book

import app.grimoire.source.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Title, authors, language and cover of a book, from catalogues rather than from the file. By
 * ISBN: Open Library (covers), the BnF catalogue (every book published in France), then Google
 * Books, which only answers with an API key nowadays (an anonymous call gets 429): it is used
 * when [googleKey] gives one. Each ISBN the file carries is tried in turn (the digital one is
 * often unknown where the print one is); a file without ISBN is searched by title and author.
 */
class BookLookup(private val http: Http, private val googleKey: () -> String?) {
    /** Null when nothing matches; thrown only when every catalogue failed (offline…). */
    suspend fun find(isbns: List<String>, title: String, authors: List<String>, language: String?): BookInfo? {
        val calls = Calls()
        for (isbn in isbns) {
            byIsbn(isbn, calls)?.let { info ->
                return info.copy(isbns = (isbns + info.isbns).distinct(), language = info.language ?: language)
            }
        }
        byTitle(title, authors, language, calls)?.let { return it.copy(language = it.language ?: language) }
        if (!calls.answered) calls.failures.firstOrNull()?.let { throw it }
        return null
    }

    /** Whether any catalogue answered at all, to tell "unknown book" from "no network". */
    private class Calls {
        var answered = false
        val failures = mutableListOf<Exception>()
    }

    private suspend fun byIsbn(isbn: String, calls: Calls): BookInfo? {
        val openLibrary = attempt(calls) {
            BookInfo.fromOpenLibrary(http.get("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data"), isbn)
        }
        val bnf = attempt(calls) {
            val query = URLEncoder.encode("bib.isbn adj \"$isbn\"", "UTF-8")
            BookInfo.fromBnf(http.get("https://catalogue.bnf.fr/api/SRU?version=1.2&operation=searchRetrieve&query=$query&recordSchema=dublincore&maximumRecords=1"), isbn)
        }
        val google = googleKey()?.let { key ->
            attempt(calls) { BookInfo.fromGoogle(http.get("$GOOGLE?q=isbn:$isbn&key=$key")).firstOrNull() }
        }
        // Google has the summaries, the BnF the most accurate French data, Open Library the covers.
        val info = (google ?: bnf ?: openLibrary ?: return null).or(bnf).or(openLibrary)
        val cover = openLibrary?.cover ?: openLibraryCover(isbn) ?: info.cover
        return info.copy(cover = cover, isbns = (listOf(isbn) + info.isbns).distinct())
    }

    private suspend fun byTitle(title: String, authors: List<String>, language: String?, calls: Calls): BookInfo? {
        if (title.isBlank()) return null
        val wanted = simple(title)
        fun matches(t: String) = simple(t).let { it == wanted || it.startsWith(wanted) || wanted.startsWith(it) }
        val author = authors.firstOrNull()?.substringAfterLast(' ')
        googleKey()?.let { key ->
            val q = "intitle:" + enc(title) + (author?.let { "+inauthor:" + enc(it) } ?: "")
            val lang = language?.take(2)?.lowercase()?.let { "&langRestrict=$it" }.orEmpty()
            attempt(calls) { BookInfo.fromGoogle(http.get("$GOOGLE?q=$q$lang&maxResults=10&key=$key")).firstOrNull { matches(it.title) } }
                ?.let { return it }
        }
        val url = "https://openlibrary.org/search.json?title=" + URLEncoder.encode(title, "UTF-8") +
            (author?.let { "&author=" + URLEncoder.encode(it, "UTF-8") } ?: "") + "&limit=5"
        return attempt(calls) { BookInfo.fromOpenLibrarySearch(http.get(url)) }?.takeIf { matches(it.title) }
    }

    private suspend fun <T> attempt(calls: Calls, block: suspend () -> T?): T? = try {
        block().also { calls.answered = true }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        calls.failures += e
        null
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
