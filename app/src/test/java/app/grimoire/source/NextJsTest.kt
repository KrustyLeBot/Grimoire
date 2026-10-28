package app.grimoire.source

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NextJsTest {
    private fun fixture(name: String) = javaClass.getResource("/reactflight/$name.txt")!!.readText()

    @Test
    fun pathReferences() {
        val o = NextJs.find(fixture("pathrefs")) { it.containsKey("mangaTitle") }!!
        val chapters = o.arr("chapters")!!
        assertEquals("Newest", chapters[0].jsonObject.str("title"))
        assertEquals("Oldest", chapters[1].jsonObject.str("title"))
    }

    @Test
    fun promises() {
        val o = NextJs.find(fixture("promises")) { it.containsKey("mangaTitle") }!!
        assertEquals(listOf("Second", "First"), o.arr("chapters")!!.map { it.jsonObject.str("title") })
    }

    @Test
    fun lazyReferences() {
        val o = NextJs.find(fixture("lazyrefs")) { it.containsKey("mangaTitle") }!!
        assertEquals(listOf("Deferred Title", "42 chapters"), o.arr("children")!!.map { it.jsonPrimitive.content })
    }

    @Test
    fun mapsAndSets() {
        val o = NextJs.find(fixture("collections")) { it.containsKey("items") }!!
        assertEquals("1", o.obj("items")!!.str("a"))
        assertEquals(3, o.arr("tags")!!.size)
        assertEquals(2, o.obj("nested")!!.arr("list")!!.size)
    }

    @Test
    fun markers() {
        val o = NextJs.find(fixture("markers")) { it.containsKey("name") }!!
        assertEquals("123456789012345678901234567890", o.str("big"))
        assertEquals("2024-01-02T03:04:05.000Z", o.str("date"))
        assertEquals("Infinity", o.str("inf"))
        assertNull(o.str("missing"))
    }

    @Test
    fun largeTextChunks() {
        val chunks = mutableMapOf<String, String>()
        NextJs.rscPayloads(fixture("largetext"), chunks, mutableMapOf())
        assertEquals(0x690, chunks["1"]!!.toByteArray().size)
    }

    @Test
    fun findList() {
        val rsc = """0:{"a":[{"id":1,"title":"A","slug":"a"},{"id":2,"title":"B","slug":"b"}]}"""
        val list = NextJs.findList(rsc) { it.containsKey("slug") }
        assertNotNull(list)
        assertEquals(listOf("a", "b"), list!!.map { (it as JsonObject).str("slug") })
    }

}
