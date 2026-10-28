package app.grimoire.data

import app.grimoire.source.Kind
import app.grimoire.source.SourceConfig
import org.json.JSONArray
import org.json.JSONObject

/** The synced files' JSON (docs/SPECS.md §3), apart from the file handling so it can be tested. */
object Codec {
    const val FORMAT_VERSION = 1

    fun sources(list: List<SourceConfig>): String = root().put(
        "sources",
        JSONArray(list.map { s ->
            JSONObject().put("id", s.id).put("type", s.type).put("name", s.name).put("baseUrl", s.baseUrl)
                .put("enabled", s.enabled).put("options", JSONObject(s.options as Map<*, *>))
        }),
    ).toString(2)

    fun sources(text: String): List<SourceConfig> = objects(check(text).optJSONArray("sources")).map { o ->
        val options = o.optJSONObject("options")
        SourceConfig(
            id = o.getString("id"), type = o.getString("type"), name = o.optString("name"), baseUrl = o.getString("baseUrl"),
            enabled = o.optBoolean("enabled", true),
            options = options?.keys()?.asSequence()?.associateWith { options.getString(it) }.orEmpty(),
        )
    }

    fun library(list: List<LibraryItem>): String = root().put(
        "items",
        JSONArray(list.map { i ->
            JSONObject().put("source", i.source).put("slug", i.slug).put("title", i.title).put("cover", i.cover)
                .put("kind", i.kind.raw).put("added", i.added)
        }),
    ).toString(2)

    fun library(text: String): List<LibraryItem> = objects(check(text).optJSONArray("items")).map { o ->
        LibraryItem(
            source = o.getString("source"), slug = o.getString("slug"), title = o.optString("title"),
            cover = o.optString("cover").ifBlank { null }, kind = Kind.of(o.optString("kind")), added = o.optLong("added"),
        )
    }

    fun progress(map: Map<String, SeriesProgress>): String {
        val series = JSONObject()
        map.forEach { (key, p) ->
            series.put(
                key,
                JSONObject()
                    .put("read", JSONObject(p.read as Map<*, *>))
                    .put("pages", JSONObject(p.pages as Map<*, *>))
                    .put("mode", p.mode)
                    .put("last", p.last?.let {
                        JSONObject().put("chapter", it.chapter).put("name", it.chapterName).put("page", it.page)
                            .put("pages", it.pages).put("at", it.at)
                    }),
            )
        }
        return root().put("series", series).toString(2)
    }

    fun progress(text: String): Map<String, SeriesProgress> {
        val series = check(text).optJSONObject("series") ?: return emptyMap()
        return series.keys().asSequence().associateWith { key ->
            val o = series.getJSONObject(key)
            val read = o.optJSONObject("read")
            val pages = o.optJSONObject("pages")
            SeriesProgress(
                read = read?.keys()?.asSequence()?.associateWith { read.getLong(it) }.orEmpty(),
                pages = pages?.keys()?.asSequence()?.associateWith { pages.getInt(it) }.orEmpty(),
                mode = o.optString("mode").ifBlank { null },
                last = o.optJSONObject("last")?.let {
                    LastRead(it.getString("chapter"), it.optString("name"), it.optInt("page"), it.optInt("pages"), it.optLong("at"))
                },
            )
        }
    }

    private fun root() = JSONObject().put("format_version", FORMAT_VERSION)

    private fun check(text: String): JSONObject {
        val o = JSONObject(text)
        val version = o.optInt("format_version", 1)
        require(version <= FORMAT_VERSION) { "format $version trop récent pour cette version de Grimoire" }
        return o
    }

    private fun objects(arr: JSONArray?): List<JSONObject> = List(arr?.length() ?: 0) { arr!!.getJSONObject(it) }
}
