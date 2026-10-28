package app.grimoire.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile

/** One entry of the table of contents; [path] is a zip path, [fragment] an anchor inside it. */
data class TocEntry(val title: String, val path: String, val fragment: String?)

/**
 * An EPUB (2 or 3) opened in place: its metadata, reading order (spine) and table of contents.
 * Paths are zip entry paths, already resolved against the package document.
 */
class Epub(file: File) : Closeable {
    private val zip = ZipFile(file)

    val title: String
    val authors: List<String>
    val language: String?
    /** The publisher's summary (`dc:description`), as plain text. */
    val description: String?
    /**
     * Every ISBN the file carries, as ISBN-13: the ones declared as such first, then any other
     * identifier holding one, then `dc:source` (often the print edition's).
     */
    val isbns: List<String>
    val isbn: String? get() = isbns.firstOrNull()
    /** The package's own identifier (a UUID…), to recognise a book without ISBN. */
    val uniqueId: String?
    val spine: List<String>
    val toc: List<TocEntry>
    val coverPath: String?

    init {
        val container = xml(read("META-INF/container.xml") ?: throw EpubException("Pas un EPUB : META-INF/container.xml manque"))
        val opfPath = container.select("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
            ?: throw EpubException("EPUB sans document de paquetage")
        val opf = xml(read(opfPath) ?: throw EpubException("EPUB incomplet : $opfPath manque"))
        val base = opfPath.substringBeforeLast('/', "")

        val metadata = opf.children().firstOrNull()?.children()?.firstOrNull { local(it) == "metadata" }
        fun meta(name: String) = metadata?.children()?.filter { local(it) == name }.orEmpty()

        title = meta("title").firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: file.nameWithoutExtension
        authors = meta("creator").map { it.text().trim() }.filter { it.isNotEmpty() }
        language = meta("language").firstOrNull()?.text()?.trim()
        // Often HTML escaped inside the XML: parsed again to keep only the text.
        description = meta("description").firstOrNull()?.text()?.let { Jsoup.parse(it).text().trim() }?.ifEmpty { null }
        val identifiers = meta("identifier")
        val declared = identifiers.filter { id ->
            id.attributes().any { it.key.substringAfter(':') == "scheme" && it.value.equals("isbn", true) } || "isbn" in id.text().lowercase()
        }
        // A UUID can hold digit runs that pass an ISBN-10 check by chance.
        val others = identifiers.filterNot { it in declared || "uuid" in it.text().lowercase() }
        isbns = (declared + others + meta("source")).flatMap { Isbn.all(it.text()) }.distinct()
        val uniqueKey = opf.children().firstOrNull()?.attr("unique-identifier")
        uniqueId = (identifiers.firstOrNull { it.id() == uniqueKey } ?: identifiers.firstOrNull())?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val manifest = opf.getElementsByTag("item").plus(opf.getElementsByTag("opf:item"))
            .associateBy({ it.attr("id") }, { it })
        fun path(item: Element?) = item?.attr("href")?.takeIf { it.isNotBlank() }?.let { resolve(base, it) }

        val spineEl = opf.getElementsByTag("spine").plus(opf.getElementsByTag("opf:spine")).firstOrNull()
        spine = spineEl?.children()?.filter { local(it) == "itemref" }
            ?.mapNotNull { path(manifest[it.attr("idref")]) }.orEmpty()
        if (spine.isEmpty()) throw EpubException("EPUB sans contenu lisible")

        val navItem = manifest.values.firstOrNull { "nav" in it.attr("properties").split(' ') }
        val ncxItem = manifest[spineEl?.attr("toc").orEmpty()] ?: manifest.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
        toc = path(navItem)?.let { navToc(it) }?.takeIf { it.isNotEmpty() }
            ?: path(ncxItem)?.let { ncxToc(it) }.orEmpty()

        val coverId = meta("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        coverPath = path(manifest.values.firstOrNull { "cover-image" in it.attr("properties").split(' ') })
            ?: path(coverId?.let { manifest[it] })
            ?: path(manifest.values.firstOrNull { it.attr("media-type").startsWith("image/") && "cover" in it.attr("id").lowercase() })
    }

    fun open(path: String): InputStream? = zip.getEntry(path)?.let { zip.getInputStream(it) }

    fun exists(path: String) = zip.getEntry(path) != null

    /** Title of the table-of-contents entry a spine item falls under. */
    fun chapterTitle(spineIndex: Int): String? {
        val byPath = toc.associateBy { it.path }
        for (i in spineIndex downTo 0) byPath[spine[i]]?.let { return it.title }
        return null
    }

    override fun close() = zip.close()

    private fun read(path: String) = open(path)?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun navToc(path: String): List<TocEntry> {
        val doc = Jsoup.parse(read(path) ?: return emptyList())
        val nav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.select("nav").firstOrNull() ?: return emptyList()
        val dir = path.substringBeforeLast('/', "")
        return nav.select("a[href]").mapNotNull { a -> entry(a.text(), dir, a.attr("href")) }
    }

    private fun ncxToc(path: String): List<TocEntry> {
        val doc = xml(read(path) ?: return emptyList())
        val dir = path.substringBeforeLast('/', "")
        return doc.getElementsByTag("navPoint").mapNotNull { p ->
            val label = p.children().firstOrNull { local(it) == "navLabel" }?.text().orEmpty()
            val src = p.children().firstOrNull { local(it) == "content" }?.attr("src").orEmpty()
            entry(label, dir, src)
        }
    }

    private fun entry(title: String, dir: String, href: String): TocEntry? {
        if (href.isBlank() || title.isBlank()) return null
        return TocEntry(title.trim(), resolve(dir, href.substringBefore('#')), href.substringAfter('#', "").ifEmpty { null })
    }

    companion object {
        private fun xml(text: String): Document = Jsoup.parse(text, "", Parser.xmlParser())

        private fun local(e: Element) = e.tagName().substringAfter(':')

        /** [href] relative to the folder [dir] of the document that holds it, as a zip path. */
        fun resolve(dir: String, href: String): String {
            val clean = href.substringBefore('#').replace(" ", "%20")
            val folder = if (dir.isEmpty()) "" else dir.replace(" ", "%20") + "/"
            val path = runCatching { URI("file:///$folder").resolve(clean).path }.getOrNull()
                ?: return (if (dir.isEmpty()) "" else "$dir/") + href.substringBefore('#')
            return path.removePrefix("/")
        }
    }
}

class EpubException(message: String) : Exception(message)
