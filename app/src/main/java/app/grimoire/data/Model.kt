package app.grimoire.data

import app.grimoire.source.Kind
import app.grimoire.source.SourceConfig

/**
 * A series in the library. [key] = "source:slug" identifies it everywhere (progress, cache, downloads).
 * A book from a file has the source "files" and its book id as slug (see book/Books), and keeps
 * the ISBNs of its edition so another phone recognises the same book in another file.
 */
data class LibraryItem(
    val source: String,
    val slug: String,
    val title: String,
    val cover: String?,
    val kind: Kind,
    val added: Long,
    val isbns: List<String> = emptyList(),
) {
    val key get() = seriesKey(source, slug)
}

fun seriesKey(source: String, slug: String) = "$source:$slug"

/**
 * Where reading stopped. For a book: [chapter] is the spine index, [page] the position inside it
 * in thousandths, [pages] the number of spine items.
 */
data class LastRead(val chapter: String, val chapterName: String, val page: Int, val pages: Int, val at: Long)

/** Reading state of one series: [read] maps a chapter id to when it was marked read. */
data class SeriesProgress(
    val read: Map<String, Long> = emptyMap(),
    val last: LastRead? = null,
    val mode: String? = null,
    /** Pages of chapters started but not finished, by chapter id. */
    val pages: Map<String, Int> = emptyMap(),
)

/** Everything kept in the synced folder. */
data class AppData(
    val sources: List<SourceConfig> = emptyList(),
    val library: List<LibraryItem> = emptyList(),
    val progress: Map<String, SeriesProgress> = emptyMap(),
)
