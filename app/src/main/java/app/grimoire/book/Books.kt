package app.grimoire.book

import android.content.Context
import android.net.Uri
import app.grimoire.source.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** A book file just added: what the library needs to show it before the online lookup. */
data class Imported(
    val id: String,
    val title: String,
    val authors: List<String>,
    val language: String?,
    val isbns: List<String>,
    val replaced: Boolean,
    /** The download link, when the file came from one, and the page that offered it. */
    val link: String? = null,
    val page: String? = null,
)

/**
 * The EPUB files of this phone, in the app's private storage: books are not synced (only their
 * progress is, under their id). A book's id is `isbn-<ISBN-13>`, so the same edition bought or
 * downloaded twice, or on another phone, is the same book (another language or edition has its
 * own ISBN, so its own progress); without ISBN it is a hash of the EPUB's own identifier.
 * [import] takes the id of a library book sharing one of the file's ISBNs, when there is one.
 */
class Books(private val context: Context, private val http: Http) {
    private val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "books").also { it.mkdirs() }
    private val infoDir: File get() = File(context.filesDir, "book-info").also { it.mkdirs() }

    private val _present = MutableStateFlow(scan())
    /** Ids of the books whose file is on this phone. */
    val present: StateFlow<Set<String>> = _present

    fun file(id: String) = File(dir, "$id.epub")

    /** The cover inside the EPUB, extracted at import: shown when the internet has none. */
    fun localCover(id: String): String? = File(dir, "$id.cover").takeIf { it.exists() }?.let { "file://${it.path}" }

    suspend fun import(uri: Uri, known: (List<String>) -> String?): Imported = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw EpubException("Fichier illisible")
        input.use { store(it, known) }
    }

    /**
     * A link to an .epub: shared from a browser, offered by a site in the in-app browser (whose
     * cookies come along, so a logged-in download works), or saved with the book.
     */
    suspend fun download(url: String, headers: Map<String, String>, page: String?, known: (List<String>) -> String?): Imported = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.images.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw EpubException("Téléchargement impossible (${r.code}) : le lien a peut-être expiré")
            store(r.body!!.byteStream(), known).copy(link = url, page = page)
        }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        _present.value = scan()
    }

    fun delete(id: String) {
        file(id).delete()
        File(dir, "$id.cover").delete()
        _present.value = scan()
    }

    fun info(id: String): BookInfo? = File(infoDir, "$id.json").takeIf { it.exists() }?.let { runCatching { BookInfo.decode(it.readText()) }.getOrNull() }

    fun saveInfo(id: String, info: BookInfo) = File(infoDir, "$id.json").writeText(BookInfo.encode(info))

    private fun store(input: InputStream, known: (List<String>) -> String?): Imported {
        val tmp = File(dir, "import-${System.nanoTime()}.tmp")
        try {
            tmp.outputStream().use { input.copyTo(it) }
            val head = tmp.inputStream().use { s -> ByteArray(2).also { s.read(it) } }
            if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) throw EpubException("Ce fichier n'est pas un EPUB")
            val imported = Epub(tmp).use { e ->
                val id = known(e.isbns) ?: idOf(e.isbn, e.uniqueId, e.title, e.authors)
                e.coverPath?.let { path -> e.open(path)?.use { s -> File(dir, "$id.cover").outputStream().use { s.copyTo(it) } } }
                Imported(id, e.title, e.authors, e.language, e.isbns, replaced = file(id).exists())
            }
            val target = file(imported.id)
            target.delete()
            if (!tmp.renameTo(target)) throw EpubException("Impossible d'enregistrer le livre")
            _present.value = scan()
            return imported
        } finally {
            tmp.delete()
        }
    }

    private fun scan(): Set<String> = dir.listFiles { f -> f.name.endsWith(".epub") }?.map { it.name.removeSuffix(".epub") }?.toSet().orEmpty()

    companion object {
        /** Library items and progress of books use this as their source. */
        const val SOURCE = "files"
        /** The "chapter" marked read when the whole book is. */
        const val READ = "book"

        fun idOf(isbn: String?, uniqueId: String?, title: String, authors: List<String>): String =
            if (isbn != null) "isbn-$isbn"
            else "id-" + MessageDigest.getInstance("SHA-1").digest((uniqueId ?: "$title|${authors.joinToString()}").toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)

        fun isbnOf(id: String): String? = id.removePrefix("isbn-").takeIf { id.startsWith("isbn-") }

        /** How far into the book a position is, in percent. */
        fun percent(last: app.grimoire.data.LastRead): Int {
            val index = last.chapter.toIntOrNull() ?: return 0
            return if (last.pages <= 0) 0 else (((index + last.page / 1000f) / last.pages) * 100).toInt().coerceIn(0, 100)
        }
    }
}
