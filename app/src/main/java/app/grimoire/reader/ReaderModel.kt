package app.grimoire.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.grimoire.GrimoireApp
import app.grimoire.data.Ops
import app.grimoire.data.seriesKey
import app.grimoire.source.ChapterContent
import app.grimoire.source.ChapterInfo
import app.grimoire.source.Kind
import app.grimoire.source.SeriesDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Mode(val raw: String, val label: String) {
    SCROLL("scroll", "Défilement"), PAGES("pages", "Pages");

    companion object {
        fun of(raw: String?, kind: Kind) = entries.firstOrNull { it.raw == raw } ?: if (kind == Kind.MANGA) PAGES else SCROLL
    }
}

/** What a chapter shows: image pages (a URL or a downloaded file each) or text. */
sealed interface Loaded {
    data class Images(val pages: List<Any>, val headers: Map<String, String>) : Loaded
    data class Text(val paragraphs: List<String>) : Loaded
}

/**
 * One reading session of a series: the chapter on screen, its pages, the position (kept in memory
 * while reading and written when leaving, see Store.update), and the read marks.
 */
class ReaderModel(
    private val app: GrimoireApp,
    private val scope: CoroutineScope,
    val source: String,
    val details: SeriesDetails,
    chapterId: String,
) {
    private val key = seriesKey(source, details.slug)
    /** Readable chapters, oldest first. */
    val chapters: List<ChapterInfo> = details.chapters.filterNot { it.locked }.sortedBy { it.number }

    var chapter by mutableStateOf(chapters.firstOrNull { it.id == chapterId } ?: chapters.first())
        private set
    var loaded by mutableStateOf<Loaded?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var mode by mutableStateOf(Mode.of(app.store.data.value.progress[key]?.mode, details.kind))
        private set

    /** Page to open the chapter at (where it was left, else the start). */
    var startPage by mutableStateOf(0)
        private set

    val previous get() = chapters.getOrNull(chapters.indexOf(chapter) - 1)
    val next get() = chapters.getOrNull(chapters.indexOf(chapter) + 1)

    init {
        load(chapter)
    }

    fun load(target: ChapterInfo) {
        chapter = target
        loaded = null
        error = null
        val progress = app.store.data.value.progress[key]
        startPage = if (target.id in progress?.read.orEmpty()) 0 else progress?.pages?.get(target.id) ?: 0
        scope.launch {
            try {
                val files = app.downloads.pages(source, details.slug, target.id)
                loaded = if (files != null) {
                    Loaded.Images(files, emptyMap())
                } else {
                    val src = app.sources.get(source) ?: throw IllegalStateException("Source introuvable")
                    when (val content = src.content(details.slug, target)) {
                        is ChapterContent.Images -> Loaded.Images(content.urls, src.imageHeaders())
                        is ChapterContent.Text -> Loaded.Text(content.paragraphs)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (!app.online.value) "Hors ligne, et ce chapitre n'est pas téléchargé" else e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Called as pages scroll by: kept in memory only (written by [leave]). */
    fun onPage(page: Int, pages: Int) {
        app.store.update(persist = false, op = Ops.position(key, chapter, page, pages))
        if (pages > 0 && page >= pages - 1) markRead()
    }

    fun markRead() {
        if (chapter.id !in app.store.data.value.progress[key]?.read.orEmpty()) {
            app.store.update(op = Ops.markRead(key, listOf(chapter.id)))
        }
    }

    fun switchMode(to: Mode) {
        mode = to
        app.store.update(op = Ops.setMode(key, to.raw))
    }

    fun leave() = app.store.flush()
}
