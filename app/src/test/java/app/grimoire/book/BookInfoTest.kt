package app.grimoire.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookInfoTest {
    @Test
    fun google() {
        val json = """{"totalItems":1,"items":[{"volumeInfo":{"title":"Le Petit Prince","authors":["Antoine de Saint-Exupéry"],
            "publisher":"Gallimard","publishedDate":"2007","description":"Un aviateur…","pageCount":96,"language":"fr",
            "industryIdentifiers":[{"type":"ISBN_10","identifier":"2070612759"},{"type":"ISBN_13","identifier":"9782070612758"},{"type":"OTHER","identifier":"UOM:39015"}],
            "imageLinks":{"thumbnail":"http://books.google.com/books/content?id=x&printsec=frontcover&img=1&zoom=1&edge=curl&source=gbs_api"}}}]}"""
        val i = BookInfo.fromGoogle(json).single()
        assertEquals("Le Petit Prince", i.title)
        assertEquals(listOf("Antoine de Saint-Exupéry"), i.authors)
        assertEquals(96, i.pages)
        assertEquals("https://books.google.com/books/content?id=x&printsec=frontcover&img=1&zoom=1&source=gbs_api", i.cover)
        assertEquals(listOf("9782070612758"), i.isbns)
        assertEquals("fr", i.language)
        assertEquals(emptyList<BookInfo>(), BookInfo.fromGoogle("""{"totalItems":0}"""))
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
