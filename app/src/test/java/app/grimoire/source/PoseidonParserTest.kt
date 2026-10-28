package app.grimoire.source

import app.grimoire.source.poseidon.PoseidonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseidonParserTest {
    private val future = "2999-01-01T00:00:00.000Z"
    private val past = "2001-01-01T00:00:00.000Z"

    private fun rsc(premiumUser: Boolean) = """
        0:["$","div",null,{"children":"${'$'}L1"}]
        1:{"isPremiumUser":$premiumUser,"manga":{"slug":"solo","chapters":[
        {"number":1,"title":"","createdAt":"2024-01-01T00:00:00.000Z","isPremium":false},
        {"number":2.5,"title":"Le retour","createdAt":"2024-02-01T00:00:00.000Z","isPremium":true,"premiumUntil":"$past"},
        {"number":3,"title":"","createdAt":"2024-03-01T00:00:00.000Z","isPremium":true,"premiumUntil":"$future"}]}}
    """.trimIndent().replace("\n{", "{").replace("[\n", "[")

    @Test
    fun hidesPremiumByDefault() {
        val chapters = PoseidonParser.chapters(rsc(false), showPremium = false)!!
        assertEquals(listOf("2.5", "1"), chapters.map { it.id })
        assertEquals("Chapitre 2.5 - Le retour", chapters[0].name)
        assertEquals("Chapitre 1", chapters[1].name)
    }

    @Test
    fun showsLockedPremium() {
        val chapters = PoseidonParser.chapters(rsc(false), showPremium = true)!!
        assertEquals("3", chapters[0].id)
        assertTrue(chapters[0].locked)
        assertTrue(chapters[0].name.startsWith("🔒"))
    }

    @Test
    fun premiumUserReadsEverything() {
        val chapters = PoseidonParser.chapters(rsc(true), showPremium = false)!!
        assertEquals(3, chapters.size)
        assertFalse(chapters.any { it.locked })
    }

    @Test
    fun partialStreamIsNull() {
        assertNull(PoseidonParser.chapters("""0:["$","div",null,{}]""", showPremium = false))
    }

    @Test
    fun popular() {
        val list = PoseidonParser.popular("""0:{"top":[{"id":1,"title":"Solo","slug":"solo"},{"id":2,"title":"Tower","slug":"tower"}]}""")!!
        assertEquals(listOf("solo", "tower"), list.map { it.slug })
    }

    @Test
    fun search() {
        val html = """
            <div class="grid">
              <a class="block group" href="/serie/solo-leveling"><img alt="x" srcset="/_next/image?url=%2Fstorage%2Fcovers%2Fsolo.webp&amp;w=256 1x"><h2>Solo Leveling</h2></a>
              <a class="block group" href="/serie/tower?x=1"><h2>Tower</h2></a>
            </div>
            <nav aria-label="Pagination"><a href="?page=2">Suivant</a></nav>
        """
        val page = PoseidonParser.search(html, "https://poseidon-scans.net")
        assertEquals(listOf("solo-leveling", "tower"), page.items.map { it.slug })
        assertEquals("https://poseidon-scans.net/api/covers/solo.webp", page.items[0].cover)
        assertNull(page.items[1].cover)
        assertTrue(page.hasNext)
    }

    @Test
    fun details() {
        val flight = """0:{"title":"Solo","slug":"solo","description":" Un chasseur. ","status":"en cours","author":"Chugong","categories":[{"name":"action"},{"name":"manga"}]}"""
        val push = "[1," + kotlinx.serialization.json.JsonPrimitive(flight + "\n") + "]"
        val html = "<html><body><script>self.__next_f.push($push)</script></body></html>"
        val d = PoseidonParser.details(html)!!
        assertEquals("Solo", d.title)
        assertEquals("En cours", d.status)
        assertEquals("Un chasseur.", d.description)
        assertEquals(listOf("Action", "Manga"), d.genres)
        assertEquals(Kind.MANGA, d.kind)
    }
}
