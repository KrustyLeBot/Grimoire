package app.grimoire.book

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.grimoire.GrimoireApp
import app.grimoire.data.Ops
import app.grimoire.data.seriesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One open book. The position (spine item + fraction of it) is kept in memory as pages turn and
 * written when the reader closes, like the manga reader's; the book is marked read at its end.
 */
class BookReaderModel(private val app: GrimoireApp, val id: String, scope: CoroutineScope) {
    private val key = seriesKey(Books.SOURCE, id)

    var epub by mutableStateOf<Epub?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var index by mutableIntStateOf(0)
        private set
    /** Where the chapter being loaded opens, 0..1. */
    var start by mutableFloatStateOf(0f)
        private set
    var fraction by mutableFloatStateOf(0f)
        private set
    var paged by mutableStateOf(app.store.data.value.progress[key]?.mode != MODE_SCROLL)
        private set
    var textSize by mutableIntStateOf(app.settings.textSize)
        private set
    /** Bumped whenever the page must be reloaded (chapter, mode, text size). */
    var generation by mutableIntStateOf(0)
        private set

    val title get() = app.store.data.value.library.firstOrNull { it.key == key }?.title ?: epub?.title.orEmpty()
    val chapterTitle get() = epub?.chapterTitle(index) ?: "Partie ${index + 1}"
    val count get() = epub?.spine?.size ?: 0
    /** Whole book, 0..1. */
    val overall get() = if (count == 0) 0f else (index + fraction) / count

    init {
        scope.launch {
            try {
                val e = withContext(Dispatchers.IO) { Epub(app.books.file(id)) }
                val last = app.store.data.value.progress[key]?.last
                index = last?.chapter?.toIntOrNull()?.coerceIn(0, e.spine.lastIndex) ?: 0
                start = ((last?.page ?: 0) / 1000f).coerceIn(0f, 1f)
                fraction = start
                epub = e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Livre illisible"
            }
        }
    }

    fun open(i: Int, at: Float = 0f) {
        if (i !in 0 until count) return
        index = i
        start = at
        fraction = at
        generation++
        save()
    }

    fun next() = open(index + 1)

    /** The previous chapter opens on its last page, as when turning pages back. */
    fun previous() = open(index - 1, 1f)

    fun openToc(entry: TocEntry) {
        val i = epub?.spine?.indexOf(entry.path) ?: -1
        if (i >= 0) open(i)
    }

    fun openPath(path: String) {
        val i = epub?.spine?.indexOf(path) ?: -1
        if (i >= 0 && i != index) open(i)
    }

    fun onPosition(f: Float) {
        fraction = f.coerceIn(0f, 1f)
        save()
        if (index == count - 1 && fraction >= 0.98f) markRead()
    }

    fun setMode(paged: Boolean) {
        if (paged == this.paged) return
        this.paged = paged
        start = fraction
        generation++
        app.store.update(op = Ops.setMode(key, if (paged) MODE_PAGES else MODE_SCROLL))
    }

    fun changeTextSize(delta: Int) {
        val size = (textSize + delta).coerceIn(12, 32)
        if (size == textSize) return
        textSize = size
        app.settings.textSize = size
        start = fraction
        generation++
    }

    private fun markRead() {
        if (Books.READ !in app.store.data.value.progress[key]?.read.orEmpty()) app.store.update(op = Ops.markRead(key, listOf(Books.READ)))
    }

    private fun save() {
        if (count == 0) return
        app.store.update(persist = false, op = Ops.bookPosition(key, index, chapterTitle, (fraction * 1000).toInt(), count))
    }

    fun close() {
        epub?.close()
        app.store.flush()
    }

    private companion object {
        const val MODE_PAGES = "pages"
        const val MODE_SCROLL = "scroll"
    }
}
