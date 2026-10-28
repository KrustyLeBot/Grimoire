package app.grimoire.book

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document

/**
 * A chapter of an EPUB prepared for the reader. The book's own markup is kept untouched (the
 * WebView reads XHTML and SVG covers natively) and only gets, by insertion: the reader's style,
 * `#g-flow` around the body content (the element laid out in pages) with an end marker, and the
 * reader's script. The book's own scripts are dropped. Always given back as UTF-8.
 */
object Chapter {
    private val BODY_OPEN = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SCRIPT = Regex("<script\\b[^>]*/>|<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)
    private val ENCODING = Regex("""encoding\s*=\s*["']([\w.:-]+)["']""", RegexOption.IGNORE_CASE)
    private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?([\w.:-]+)""", RegexOption.IGNORE_CASE)

    /** The file's text, decoded with the charset it declares (UTF-8 otherwise). */
    fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val name = ENCODING.find(head)?.groupValues?.get(1) ?: META_CHARSET.find(head)?.groupValues?.get(1)
        val charset = name?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    /** Whether [render] keeps the page as it is (else it is rebuilt as HTML and must be served as such). */
    fun keepsMarkup(source: String): Boolean {
        val open = BODY_OPEN.find(source) ?: return false
        return source.lastIndexOf("</body", ignoreCase = true) > open.range.last
    }

    fun render(source: String, css: String, script: String): String {
        val text = SCRIPT.replace(source, "")
            .replaceFirst(ENCODING, "encoding=\"utf-8\"")
        val open = BODY_OPEN.find(text)
        val close = text.lastIndexOf("</body", ignoreCase = true)
        if (open == null || close < open.range.last) return fallback(text, css, script)
        val start = open.range.last + 1
        val style = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\"/>" +
            "<style id=\"grimoire\">/*<![CDATA[*/$css/*]]>*/</style>"
        return buildString {
            append(text, 0, start)
            append(style)
            append("<div id=\"g-flow\">")
            append(text, start, close)
            append("<span id=\"g-end\"></span></div>")
            append("<script>//<![CDATA[\n").append(script).append("\n//]]></script>")
            append(text, close, text.length)
        }
    }

    /** A page without a proper body: rebuilt as HTML. */
    private fun fallback(source: String, css: String, script: String): String {
        val doc = Jsoup.parse(source)
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.html).charset("UTF-8").prettyPrint(false)
        doc.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1, maximum-scale=1")
        doc.head().appendElement("style").attr("id", "grimoire").appendChild(DataNode(css))
        val body = doc.body()
        val flow = body.prependElement("div").attr("id", "g-flow")
        body.childNodes().toList().filter { it !== flow }.forEach { flow.appendChild(it) }
        flow.appendElement("span").attr("id", "g-end")
        body.appendElement("script").appendChild(DataNode(script))
        return doc.outerHtml()
    }
}
