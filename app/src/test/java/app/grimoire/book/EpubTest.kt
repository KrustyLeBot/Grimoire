package app.grimoire.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubTest {
    private fun epub(files: Map<String, String>): File {
        val f = File.createTempFile("book", ".epub").apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return f
    }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    @Test
    fun epub3() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">urn:uuid:1234</dc:identifier>
                <dc:identifier>urn:isbn:978-2-07-061275-8</dc:identifier>
                <dc:title>Le Petit Prince</dc:title>
                <dc:creator>Antoine de Saint-Exupéry</dc:creator>
                <dc:language>fr</dc:language>
                <dc:source>Édition papier ISBN 0-306-40615-2</dc:source>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="Text/chap%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/chap2.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="Images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val nav = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol><li><a href="Text/chap%201.xhtml">Chapitre I</a></li><li><a href="Text/chap2.xhtml#s">Chapitre II</a></li></ol></nav>
            </body></html>"""
        Epub(epub(mapOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "OEBPS/nav.xhtml" to nav,
            "OEBPS/Text/chap 1.xhtml" to "<html/>", "OEBPS/Text/chap2.xhtml" to "<html/>"))).use { e ->
            assertEquals("Le Petit Prince", e.title)
            assertEquals(listOf("Antoine de Saint-Exupéry"), e.authors)
            assertEquals("9782070612758", e.isbn)
            assertEquals(listOf("9782070612758", "9780306406157"), e.isbns)
            assertEquals("urn:uuid:1234", e.uniqueId)
            assertEquals(listOf("OEBPS/Text/chap 1.xhtml", "OEBPS/Text/chap2.xhtml"), e.spine)
            assertEquals(listOf("Chapitre I", "Chapitre II"), e.toc.map { it.title })
            assertEquals("s", e.toc[1].fragment)
            assertEquals("OEBPS/Images/cover.jpg", e.coverPath)
            assertTrue(e.exists("OEBPS/Text/chap 1.xhtml"))
            assertEquals("Chapitre II", e.chapterTitle(1))
        }
    }

    @Test
    fun epub2WithNcxAndNoIsbn() {
        val opf = """<?xml version="1.0"?>
            <opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
              <opf:metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Sans ISBN</dc:title>
                <dc:identifier id="BookId" opf:scheme="UUID">abc-def</dc:identifier>
                <opf:meta name="cover" content="cov"/>
              </opf:metadata>
              <opf:manifest>
                <opf:item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <opf:item id="p1" href="part1.html" media-type="application/xhtml+xml"/>
                <opf:item id="cov" href="../cover.png" media-type="image/png"/>
              </opf:manifest>
              <opf:spine toc="ncx"><opf:itemref idref="p1"/></opf:spine>
            </opf:package>"""
        val ncx = """<?xml version="1.0"?><ncx><navMap>
            <navPoint id="a"><navLabel><text>Première partie</text></navLabel><content src="part1.html"/></navPoint>
            </navMap></ncx>"""
        Epub(epub(mapOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "OEBPS/toc.ncx" to ncx, "OEBPS/part1.html" to "<html/>"))).use { e ->
            assertEquals("Sans ISBN", e.title)
            assertNull(e.isbn)
            assertEquals("abc-def", e.uniqueId)
            assertEquals(listOf("OEBPS/part1.html"), e.spine)
            assertEquals("Première partie", e.toc.single().title)
            assertEquals("cover.png", e.coverPath)
        }
    }
}
