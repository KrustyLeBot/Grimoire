package app.grimoire.data

import android.content.Context
import app.grimoire.source.ChapterInfo
import app.grimoire.source.Kind
import app.grimoire.source.SeriesDetails
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The last fetched details and chapter list of each series, private, for offline browsing. */
class SeriesCache(context: Context) {
    private val dir = File(context.filesDir, "series")

    private fun file(source: String, slug: String) = File(File(dir, source), "${slug.replace('/', '_')}.json")

    fun save(source: String, details: SeriesDetails) {
        val f = file(source, details.slug)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(encode(details).toString())
        tmp.renameTo(f)
    }

    fun load(source: String, slug: String): SeriesDetails? =
        file(source, slug).takeIf { it.exists() }?.let { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }

    companion object {
        fun encode(d: SeriesDetails): JSONObject = JSONObject()
            .put("slug", d.slug).put("title", d.title).put("cover", d.cover).put("author", d.author).put("artist", d.artist)
            .put("status", d.status).put("genres", JSONArray(d.genres)).put("description", d.description).put("kind", d.kind.raw)
            .put("chapters", JSONArray(d.chapters.map {
                JSONObject().put("id", it.id).put("name", it.name).put("number", it.number.toDouble()).put("date", it.date).put("locked", it.locked)
            }))

        fun decode(o: JSONObject): SeriesDetails {
            val genres = o.optJSONArray("genres")
            val chapters = o.optJSONArray("chapters")
            return SeriesDetails(
                slug = o.getString("slug"), title = o.optString("title"),
                cover = o.optString("cover").ifBlank { null }, author = o.optString("author").ifBlank { null },
                artist = o.optString("artist").ifBlank { null }, status = o.optString("status").ifBlank { null },
                genres = List(genres?.length() ?: 0) { genres!!.getString(it) },
                description = o.optString("description").ifBlank { null }, kind = Kind.of(o.optString("kind")),
                chapters = List(chapters?.length() ?: 0) {
                    val c = chapters!!.getJSONObject(it)
                    ChapterInfo(c.getString("id"), c.optString("name"), c.optDouble("number").toFloat(), c.optLong("date"), c.optBoolean("locked"))
                },
            )
        }
    }
}
