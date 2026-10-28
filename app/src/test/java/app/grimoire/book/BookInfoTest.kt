package app.grimoire.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookInfoTest {
    @Test
    fun openLibrarySummary() {
        assertEquals("/works/OL45883W", BookInfo.workOf("""{"title":"x","works":[{"key":"/works/OL45883W"}]}"""))
        assertNull(BookInfo.workOf("""{"title":"x"}"""))
        assertEquals("Un aviateur…", BookInfo.summaryOf("""{"description":{"type":"/type/text","value":" Un aviateur… "}}"""))
        assertEquals("Court", BookInfo.summaryOf("""{"description":"Court"}"""))
        assertNull(BookInfo.summaryOf("""{"title":"x"}"""))
    }

    @Test
    fun openLibraryFillsGaps() {
        val json = """{"ISBN:9782070612758":{"title":"Le petit prince","authors":[{"name":"Antoine de Saint-Exupéry"}],
            "publishers":[{"name":"Gallimard"}],"number_of_pages":93,"identifiers":{"isbn_10":["2070612759"],"isbn_13":["9782070612758"]},"cover":{"large":"https://covers.openlibrary.org/b/id/1-L.jpg"}}}"""
        val ol = BookInfo.fromOpenLibrary(json, "9782070612758")!!
        assertEquals("Gallimard", ol.publisher)
        assertEquals(listOf("9782070612758"), ol.isbns)
        val merged = BookInfo("Le Petit Prince", description = "Un aviateur…").or(ol)
        assertEquals("Le Petit Prince", merged.title)
        assertEquals("Un aviateur…", merged.description)
        assertEquals(listOf("Antoine de Saint-Exupéry"), merged.authors)
        assertEquals("https://covers.openlibrary.org/b/id/1-L.jpg", merged.cover)
        assertNull(BookInfo.fromOpenLibrary("{}", "9782070612758"))
    }

    @Test
    fun bnf() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <srw:searchRetrieveResponse xmlns:srw="http://www.loc.gov/zing/srw/"><srw:numberOfRecords>1</srw:numberOfRecords>
            <srw:records><srw:record><srw:recordData>
            <oai_dc:dc xmlns:oai_dc="http://www.openarchives.org/OAI/2.0/oai_dc/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:identifier>http://catalogue.bnf.fr/ark:/12148/cb41050418h</dc:identifier>
              <dc:identifier>ISBN 9782070612758</dc:identifier>
              <dc:title>Le petit prince / Antoine de Saint-Exupéry ; avec des aquarelles de l'auteur</dc:title>
              <dc:creator>Saint-Exupéry, Antoine de (1900-1944). Auteur du texte</dc:creator>
              <dc:publisher>Gallimard (Paris)</dc:publisher>
              <dc:date>2007</dc:date>
              <dc:format>1 vol. (93 p.) : ill. en coul. ; 18 cm</dc:format>
              <dc:language>fre</dc:language>
            </oai_dc:dc></srw:recordData></srw:record></srw:records></srw:searchRetrieveResponse>"""
        val i = BookInfo.fromBnf(xml, "9782070612758")!!
        assertEquals("Le petit prince", i.title)
        assertEquals(listOf("Antoine de Saint-Exupéry"), i.authors)
        assertEquals("Gallimard", i.publisher)
        assertEquals(93, i.pages)
        assertEquals("fr", i.language)
        assertEquals(listOf("9782070612758"), i.isbns)
        assertNull(BookInfo.fromBnf("<srw:searchRetrieveResponse><srw:numberOfRecords>0</srw:numberOfRecords></srw:searchRetrieveResponse>", "9782070612758"))
    }

    @Test
    fun openLibrarySearch() {
        val json = """{"numFound":2,"docs":[{"title":"L'Étranger","author_name":["Albert Camus"],"first_publish_year":1942,"cover_i":12345,"isbn":["1","2"]}]}"""
        val i = BookInfo.fromOpenLibrarySearch(json)!!
        assertEquals("L'Étranger", i.title)
        assertEquals("https://covers.openlibrary.org/b/id/12345-L.jpg", i.cover)
        assertEquals(emptyList<String>(), i.isbns)
    }

    @Test
    fun roundTrip() {
        val i = BookInfo("T", "S", listOf("A", "B"), "P", "2001", 10, "fr", "D", "https://c", listOf("9782070612758"))
        assertEquals(i, BookInfo.decode(BookInfo.encode(i)))
        val bare = BookInfo("T")
        assertEquals(bare, BookInfo.decode(BookInfo.encode(bare)))
    }

    @Test
    fun ids() {
        assertEquals("isbn-9782070612758", Books.idOf("9782070612758", "x", "t", emptyList()))
        assertEquals(Books.idOf(null, "urn:uuid:1", "a", emptyList()), Books.idOf(null, "urn:uuid:1", "b", listOf("c")))
        assertEquals("9782070612758", Books.isbnOf("isbn-9782070612758"))
        assertNull(Books.isbnOf("id-abc"))
    }
}
