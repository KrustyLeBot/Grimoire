package app.grimoire.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsbnTest {
    @Test
    fun normalizes13() {
        assertEquals("9782070612758", Isbn.normalize("978-2-07-061275-8"))
        assertEquals("9782070612758", Isbn.normalize("urn:isbn:9782070612758"))
        assertEquals("9782070612758", Isbn.normalize("ISBN 978 2 07 061275 8"))
        assertEquals("9782070612758", Isbn.normalize("ISBN-13 : 978-2-07-061275-8"))
        assertEquals("9791032100004", Isbn.normalize("979-10-321-0000-4"))
    }

    @Test
    fun converts10() {
        assertEquals("9782070612758", Isbn.normalize("2-07-061275-9"))
        assertEquals("9780306406157", Isbn.normalize("0-306-40615-2"))
        assertEquals("9780804429573", Isbn.normalize("080442957X"))
    }

    @Test
    fun rejectsInvalid() {
        assertNull(Isbn.normalize("9782070612759"))
        assertNull(Isbn.normalize("urn:uuid:5f3c2a1e-0000-4000-8000-000000000000"))
        assertNull(Isbn.normalize("123"))
    }

    @Test
    fun allInText() {
        assertEquals(
            listOf("9782070612758", "9780306406157"),
            Isbn.all("Numérique : 978-2-07-061275-8 ; papier : ISBN-10 0-306-40615-2 ; réimpression 9782070612758"),
        )
    }

    @Test
    fun findsInText() {
        assertEquals("9782070612758", Isbn.find("Édition papier : ISBN 978-2-07-061275-8, Gallimard"))
        assertNull(Isbn.find("Gallimard, 2007"))
    }
}
