package app.grimoire.book

import app.grimoire.source.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Title, authors, summary, language and cover of a book, from catalogues rather than from the
 * file, without account or key: Open Library (covers, summaries) and the BnF catalogue (every book
 * published in France). Each ISBN the file carries is tried in turn (the digital one is often
 * unknown where the print one is); a file without ISBN is searched by title and author.
 */
class BookLookup(private val http: Http) {
    /**
     * Null when nothing matches; thrown only when every catalogue failed (offline…). The summary
     * is the first in the book's language among the publisher's in the file ([fileSummary]), the
     * BnF's and Open Library's (mostly English, kept only for a book in English).
     */
    suspend fun find(isbns: List<String>, title: String, authors: List<String>, language: String?, fileSummary: String?): BookInfo? {
        val calls = Calls()
        for (isbn in isbns) {
            byIsbn(isbn, calls)?.let { (info, olSummary) ->
                val lang = info.language ?: language
                return info.copy(
                    isbns = (isbns + info.isbns).distinct(),
                    language = lang,
                    description = Language.pick(lang, listOf(fileSummary, info.description, olSummary)),
                )
            }
        }
        byTitle(title, authors, language, calls)?.let {
            return it.copy(language = it.language ?: language, description = Language.pick(language, listOf(fileSummary)))
        }
        if (!calls.answered) calls.failures.firstOrNull()?.let { throw it }
        return null
    }

    /** Whether any catalogue answered at all, to tell "unknown book" from "no network". */
    private class Calls {
        var answered = false
        val failures = mutableListOf<Exception>()
    }

    /** The edition, and Open Library's summary of the work apart (its language is checked later). */
    private suspend fun byIsbn(isbn: String, calls: Calls): Pair<BookInfo, String?>? {
        val openLibrary = attempt(calls) {
            BookInfo.fromOpenLibrary(http.get("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data"), isbn)
        }
        val bnf = attempt(calls) {
            val query = URLEncoder.encode("bib.isbn adj \"$isbn\"", "UTF-8")
            BookInfo.fromBnf(http.get("https://catalogue.bnf.fr/api/SRU?version=1.2&operation=searchRetrieve&query=$query&recordSchema=dublincore&maximumRecords=1"), isbn)
        }
        val summary = attempt(calls) { openLibrarySummary(isbn) }
        // The BnF has the most accurate French data, Open Library the covers.
        val info = (bnf ?: openLibrary ?: return null).or(openLibrary)
        val cover = openLibrary?.cover ?: openLibraryCover(isbn) ?: info.cover
        return info.copy(cover = cover, isbns = (listOf(isbn) + info.isbns).distinct()) to summary
    }

    private suspend fun byTitle(title: String, authors: List<String>, language: String?, calls: Calls): BookInfo? {
        if (title.isBlank()) return null
        val wanted = simple(title)
        fun matches(t: String) = simple(t).let { it == wanted || it.startsWith(wanted) || wanted.startsWith(it) }
        val author = authors.firstOrNull()?.substringAfterLast(' ')
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

    /** The summary of the work the edition belongs to, when Open Library has one. */
    private suspend fun openLibrarySummary(isbn: String): String? {
        val work = BookInfo.workOf(http.get("https://openlibrary.org/isbn/$isbn.json")) ?: return null
        return BookInfo.summaryOf(http.get("https://openlibrary.org$work.json"))
    }

    /** Open Library's cover by ISBN, only if it has one (`default=false` answers 404 otherwise). */
    private suspend fun openLibraryCover(isbn: String): String? = withContext(Dispatchers.IO) {
        val url = "https://covers.openlibrary.org/b/isbn/$isbn-L.jpg?default=false"
        runCatching {
            http.images.newCall(Request.Builder().url(url).head().build()).execute().use { if (it.isSuccessful) url else null }
        }.getOrNull()
    }

    private companion object {
        /** Lower case, no accents or punctuation: "L'Étranger" and "l etranger" compare equal. */
        fun simple(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
