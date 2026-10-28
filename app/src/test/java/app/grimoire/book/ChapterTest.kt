package app.grimoire.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTest {
    private val xhtml = """<?xml version="1.0" encoding="iso-8859-1"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head><title>I</title><script type="text/javascript">alert(1)</script></head>
        <body class="chap"><h1>Chapitre I</h1><p><a id="p1"/>Lorsque j'avais six ans</p></body></html>"""

    @Test
    fun insertsAroundTheBody() {
        val out = Chapter.render(xhtml, css = "p{}", script = "var a = 1 < 2;")
        assertFalse("alert(1)" in out)
        assertTrue("encoding=\"utf-8\"" in out)
        assertTrue(out.contains("""<body class="chap"><meta name="viewport""""))
        assertTrue(out.contains("""<div id="g-flow"><h1>Chapitre I</h1><p><a id="p1"/>Lorsque j'avais six ans</p><span id="g-end"></span></div>"""))
        assertTrue(out.contains("<script>//<![CDATA[\nvar a = 1 < 2;\n//]]></script></body></html>"))
    }

    @Test
    fun withoutBody() {
        val out = Chapter.render("<p>Un</p><p>deux</p>", css = "", script = "x()")
        assertTrue(out.contains("""<div id="g-flow"><p>Un</p><p>deux</p><span id="g-end"></span></div>"""))
        assertTrue(out.contains("x()"))
    }

    @Test
    fun decodesDeclaredCharset() {
        val bytes = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><p>été</p>".toByteArray(Charsets.ISO_8859_1)
        assertTrue("été" in Chapter.decode(bytes))
        assertEquals("<p>été</p>", Chapter.decode("<p>été</p>".toByteArray()))
    }
}
