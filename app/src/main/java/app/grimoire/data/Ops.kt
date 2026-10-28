package app.grimoire.data

import app.grimoire.source.ChapterInfo
import app.grimoire.source.SeriesDetails
import app.grimoire.source.SourceConfig

/** The changes the app makes, as pure functions of the state (queued as such during a sync). */
object Ops {
    fun addToLibrary(source: String, details: SeriesDetails, now: Long = System.currentTimeMillis()): (AppData) -> AppData = { d ->
        val key = seriesKey(source, details.slug)
        if (d.library.any { it.key == key }) d
        else d.copy(library = d.library + LibraryItem(source, details.slug, details.title, details.cover, details.kind, now))
    }

    fun removeFromLibrary(key: String): (AppData) -> AppData = { d -> d.copy(library = d.library.filterNot { it.key == key }) }

    private fun AppData.withProgress(key: String, change: (SeriesProgress) -> SeriesProgress) =
        copy(progress = progress + (key to change(progress[key] ?: SeriesProgress())))

    /** Where the reader is: remembered per chapter, and as the series' last read. */
    fun position(key: String, chapter: ChapterInfo, page: Int, pages: Int, now: Long = System.currentTimeMillis()): (AppData) -> AppData = { d ->
        d.withProgress(key) { p ->
            p.copy(
                last = LastRead(chapter.id, chapter.name, page, pages, now),
                pages = if (chapter.id in p.read) p.pages - chapter.id else p.pages + (chapter.id to page),
            )
        }
    }

    fun markRead(key: String, chapterIds: Collection<String>, now: Long = System.currentTimeMillis()): (AppData) -> AppData = { d ->
        d.withProgress(key) { p -> p.copy(read = p.read + chapterIds.filterNot { it in p.read }.associateWith { now }, pages = p.pages - chapterIds.toSet()) }
    }

    fun markUnread(key: String, chapterIds: Collection<String>): (AppData) -> AppData = { d ->
        d.withProgress(key) { p -> p.copy(read = p.read - chapterIds.toSet()) }
    }

    fun setMode(key: String, mode: String): (AppData) -> AppData = { d -> d.withProgress(key) { it.copy(mode = mode) } }

    fun saveSource(config: SourceConfig): (AppData) -> AppData = { d ->
        d.copy(sources = if (d.sources.any { it.id == config.id }) d.sources.map { if (it.id == config.id) config else it } else d.sources + config)
    }

    fun removeSource(id: String): (AppData) -> AppData = { d -> d.copy(sources = d.sources.filterNot { it.id == id }) }
}
