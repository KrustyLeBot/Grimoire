package app.grimoire.data

import app.grimoire.source.Kind
import app.grimoire.source.SourceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CodecTest {
    @Test
    fun sourcesRoundTrip() {
        val list = listOf(SourceConfig("poseidon-scans", "poseidon-scans", "Poseidon", "https://poseidon-scans.net", true, mapOf("show_premium" to "true")))
        assertEquals(list, Codec.sources(Codec.sources(list)))
        val searches = listOf(WebSearch("Ma librairie", "https://example.org/s/{q}"))
        assertEquals(searches, Codec.webSearches(Codec.sources(list, searches)))
        assertEquals(emptyList<WebSearch>(), Codec.webSearches("""{"format_version":1,"sources":[]}"""))
        assertEquals("https://example.org/s/Le%20Petit%20Prince", searches[0].urlFor(" Le Petit Prince "))
    }

    @Test
    fun libraryRoundTrip() {
        val list = listOf(
            LibraryItem("p", "solo", "Solo", "https://x/c.webp", Kind.MANGA, 1000),
            LibraryItem("p", "tower", "Tower", null, Kind.WEBTOON, 2000),
            LibraryItem("files", "isbn-9782070612758", "Le Petit Prince", null, Kind.NOVEL, 3000, listOf("9782070612758", "9780306406157"), "https://example.org/dl/1.epub", "https://example.org/livre/1"),
        )
        assertEquals(list, Codec.library(Codec.library(list)))
    }

    @Test
    fun progressRoundTrip() {
        val map = mapOf(
            "p:solo" to SeriesProgress(mapOf("1" to 10L, "2.5" to 20L), LastRead("3", "Chapitre 3", 4, 12, 30), "pages", mapOf("3" to 4)),
            "p:tower" to SeriesProgress(),
        )
        assertEquals(map, Codec.progress(Codec.progress(map)))
    }

    @Test
    fun refusesNewerFormat() {
        assertThrows(Exception::class.java) { Codec.library("""{"format_version":99,"items":[]}""") }
    }
}
