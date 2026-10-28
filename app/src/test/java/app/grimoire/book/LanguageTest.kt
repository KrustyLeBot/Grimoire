package app.grimoire.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageTest {
    private val fr = "Un aviateur tombe en panne dans le désert du Sahara et rencontre un petit garçon venu d'une autre planète, qui lui demande de dessiner un mouton pour son voyage."
    private val en = "A pilot stranded in the Sahara desert meets a young prince who has come from a small asteroid, and who tells him the story of his travels and of the rose he loves."

    @Test
    fun guesses() {
        assertEquals("fr", Language.guess(fr))
        assertEquals("en", Language.guess(en))
        assertNull(Language.guess("Trop court"))
    }

    @Test
    fun picksTheBooksLanguage() {
        assertEquals(fr, Language.pick("fr", listOf(null, en, fr)))
        assertEquals(en, Language.pick("en", listOf(fr, en)))
        assertNull(Language.pick("fr", listOf(en)))
        assertEquals(en, Language.pick(null, listOf(en)))
        assertEquals(fr, Language.pick("fre", listOf("court", fr)))
    }
}
