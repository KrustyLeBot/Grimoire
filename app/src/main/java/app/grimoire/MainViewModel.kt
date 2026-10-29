package app.grimoire

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import app.grimoire.book.BookInfo
import app.grimoire.book.Books
import app.grimoire.book.Epub
import app.grimoire.book.Imported
import app.grimoire.data.LibraryItem
import app.grimoire.data.WebSearch
import app.grimoire.data.Ops
import app.grimoire.data.SeriesProgress
import app.grimoire.data.seriesKey
import app.grimoire.source.Catalog
import app.grimoire.source.Kind
import app.grimoire.source.ChapterInfo
import app.grimoire.source.SeriesDetails
import app.grimoire.source.SourceConfig
import app.grimoire.source.SourceException
import app.grimoire.suite.SuiteUpdates
import app.grimoire.sync.SkipReason
import app.grimoire.sync.SyncState
import app.grimoire.sync.SynchrotronClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

sealed interface Screen {
    data object Home : Screen
    data class Series(val source: String, val slug: String, val title: String, val cover: String?) : Screen
    data class Reader(val source: String, val slug: String, val chapter: String) : Screen
    data object Sources : Screen
    data class SourceEdit(val id: String) : Screen
    data class Login(val source: String) : Screen
    data object Settings : Screen
    data object History : Screen
    data class Book(val id: String) : Screen
    data class BookReader(val id: String) : Screen
    data class Web(val url: String, val title: String) : Screen
}

/** The "Lecture" section's tabs. Other sections (Reddit…) will sit next to it. */
enum class Tab(val label: String) { LIBRARY("Bibliothèque"), DISCOVER("Découvrir"), SEARCH("Recherche") }

enum class Feed(val label: String) { LATEST("Récents"), POPULAR("Populaires") }

/** A series page: the details (from the cache first, then the site), and how its loading went. */
data class SeriesView(val details: SeriesDetails? = null, val loading: Boolean = false, val error: String? = null)


private const val LIBRARY_CHECK_MS = 6 * 3_600_000L
private const val LOOKING = "…"

/** What the lookup takes from the file: what to search with, and the publisher's summary. */
private data class FileMeta(val title: String, val authors: List<String>, val language: String?, val description: String?)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as GrimoireApp
    val store = app.store
    val data get() = store.data
    val updates = SuiteUpdates(app, app.updater, viewModelScope)

    // ---- navigation ----
    var stack by mutableStateOf(listOf<Screen>(Screen.Home))
        private set
    val screen get() = stack.last()
    var tab by mutableStateOf(Tab.LIBRARY)

    fun open(s: Screen) {
        stack = stack + s
    }

    fun back(): Boolean {
        if (stack.size == 1) return false
        stack = stack.dropLast(1)
        return true
    }

    // ---- storage & sync ----
    var storageOk by mutableStateOf(store.hasStorageAccess())
        private set
    var syncing by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    private var noticeJob: Job? = null
    private var stopSync: (() -> Unit)? = null

    fun show(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(4_000)
            notice = null
        }
    }

    fun onOpen() {
        val access = store.hasStorageAccess()
        if (access != storageOk) {
            storageOk = access
            store.load() // moves the private files into the folder
        }
        syncOnOpen()
        updates.checkOnOpen()
        if (System.currentTimeMillis() - app.settings.libraryCheckedAt > LIBRARY_CHECK_MS) refreshLibrary()
    }

    fun onLeave() = store.flush()

    /** Asks Synchrotron for a sync; changes made meanwhile wait and are applied on what it brings. */
    private fun syncOnOpen() {
        if (syncing || !storageOk) return
        stopSync?.invoke()
        stopSync = SynchrotronClient.syncOnOpen(app, store.syncPath) { state ->
            when (state) {
                SyncState.Syncing -> {
                    store.flush()
                    store.setSyncing(true)
                    syncing = true
                }
                is SyncState.Done -> {
                    store.setSyncing(false)
                    syncing = false
                    store.load()
                    if (state.changes > 0) show("Bibliothèque synchronisée")
                }
                is SyncState.Failed, SyncState.TimedOut, is SyncState.Skipped -> {
                    val wasSyncing = syncing
                    store.setSyncing(false)
                    syncing = false
                    if (wasSyncing) store.load() // applies what waited
                    when {
                        state is SyncState.Failed -> show("Échec de la synchro")
                        state is SyncState.Skipped && state.reason == SkipReason.NEEDS_SETUP -> show("Termine la configuration dans Synchrotron")
                    }
                }
            }
        }
    }

    override fun onCleared() {
        stopSync?.invoke()
        store.flush()
    }

    // ---- library & history ----

    fun progress(key: String): SeriesProgress = data.value.progress[key] ?: SeriesProgress()

    /** Chapters known for a series (cache), to count the unread ones. */
    fun cachedChapters(source: String, slug: String): List<ChapterInfo> =
        (series[seriesKey(source, slug)]?.details ?: app.cache.load(source, slug))?.chapters.orEmpty()

    fun unread(item: LibraryItem): Int {
        val read = progress(item.key).read
        if (item.source == Books.SOURCE) return if (Books.READ in read) 0 else 1
        return cachedChapters(item.source, item.slug).count { !it.locked && it.id !in read }
    }

    /** Readable offline: at least one chapter downloaded. */
    fun availableOffline(source: String, slug: String): Boolean {
        val prefix = app.downloads.done.value
        val k = seriesKey(source, slug.replace('/', '_')) + ":"
        return prefix.any { it.startsWith(k) }
    }

    fun removeFromLibrary(key: String) = store.update(op = Ops.removeFromLibrary(key))

    /**
     * Refreshes the chapter lists of the library, one series after the other, gently: a new
     * chapter makes a finished series "En cours" again. Done on opening every few hours, or by hand.
     */
    var refreshing by mutableStateOf(false)
        private set

    fun refreshLibrary() {
        if (refreshing || !app.online.value) return
        refreshing = true
        viewModelScope.launch {
            try {
                data.value.library.filter { it.source != Books.SOURCE }.sortedByDescending { progress(it.key).last?.at ?: it.added }.forEach { item ->
                    loadSeries(item.source, item.slug, force = true).join()
                }
                app.settings.libraryCheckedAt = System.currentTimeMillis()
            } finally {
                refreshing = false
            }
        }
    }

    // ---- series ----
    val series = mutableStateMapOf<String, SeriesView>()

    fun loadSeries(source: String, slug: String, force: Boolean = false): Job {
        val key = seriesKey(source, slug)
        val current = series[key] ?: SeriesView(app.cache.load(source, slug))
        series[key] = current
        if ((current.details != null && !force) || current.loading || !app.online.value) return Job().apply { complete() }
        series[key] = current.copy(loading = true, error = null)
        return viewModelScope.launch {
            series[key] = try {
                val src = app.sources.get(source) ?: throw SourceException("Source introuvable : ajoute-la dans Paramètres › Sources")
                val details = src.details(slug)
                app.cache.save(source, details)
                // The library keeps the title and cover up to date.
                if (data.value.library.any { it.key == key }) {
                    store.update(op = { d -> d.copy(library = d.library.map { if (it.key == key) it.copy(title = details.title, cover = details.cover) else it }) })
                }
                SeriesView(details)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (series[key] ?: SeriesView()).copy(loading = false, error = describe(e))
            }
        }
    }

    fun inLibrary(source: String, slug: String) = data.value.library.any { it.key == seriesKey(source, slug) }

    fun toggleLibrary(source: String, details: SeriesDetails) {
        val key = seriesKey(source, details.slug)
        store.update(op = if (inLibrary(source, details.slug)) Ops.removeFromLibrary(key) else Ops.addToLibrary(source, details))
    }

    fun markRead(source: String, slug: String, ids: Collection<String>) = store.update(op = Ops.markRead(seriesKey(source, slug), ids))

    fun markUnread(source: String, slug: String, ids: Collection<String>) = store.update(op = Ops.markUnread(seriesKey(source, slug), ids))

    fun download(source: String, slug: String, chapters: List<ChapterInfo>) {
        app.downloads.enqueue(source, slug, chapters, app.settings.wifiOnlyDownloads)
        show(if (chapters.size == 1) "Téléchargement ajouté" else "${chapters.size} chapitres à télécharger")
    }

    /** The chapter to open with "Lire"/"Continuer": the one in progress, else the first unread. */
    fun nextToRead(source: String, details: SeriesDetails): ChapterInfo? {
        val p = progress(seriesKey(source, details.slug))
        val readable = details.chapters.filterNot { it.locked }.sortedBy { it.number }
        p.last?.let { last -> readable.firstOrNull { it.id == last.chapter && it.id !in p.read }?.let { return it } }
        return readable.firstOrNull { it.id !in p.read } ?: readable.lastOrNull()
    }

    fun read(source: String, details: SeriesDetails, chapter: ChapterInfo) {
        store.update(op = Ops.addToLibrary(source, details))
        open(Screen.Reader(source, details.slug, chapter.id))
    }

    // ---- books (EPUB files) ----

    var importing by mutableStateOf(false)
        private set

    /** The library book sharing one of these ISBNs: the same edition, whatever file it came in. */
    private fun knownBook(isbns: List<String>): String? = data.value.library.firstOrNull { item ->
        item.source == Books.SOURCE && (Books.isbnOf(item.slug) in isbns || item.isbns.any { it in isbns })
    }?.slug

    fun importBook(uri: Uri) = addBook { app.books.import(uri, ::knownBook) }

    /** [page]: the web page the download was started from, saved to come back to it. */
    fun downloadBook(url: String, headers: Map<String, String> = emptyMap(), page: String? = null) =
        addBook { app.books.download(url, headers, page, ::knownBook) }

    private fun addBook(load: suspend () -> Imported) {
        importing = true
        viewModelScope.launch {
            try {
                val b = load()
                store.update(op = Ops.addBook(LibraryItem(Books.SOURCE, b.id, b.title, null, Kind.NOVEL, System.currentTimeMillis(), b.isbns, b.link, b.page)))
                show(if (b.replaced) "Fichier de « ${b.title} » remplacé" else "« ${b.title} » ajouté à la bibliothèque")
                open(Screen.Book(b.id))
                describeBook(b.id, force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show("Import impossible : ${describe(e)}")
            } finally {
                importing = false
            }
        }
    }

    /** Online description of each book, from the cache or looked up by ISBN. */
    val bookInfo = mutableStateMapOf<String, BookInfo>()
    val bookLookup = mutableStateMapOf<String, String>()

    fun bookInfo(id: String): BookInfo? = bookInfo[id] ?: app.books.info(id)?.also { bookInfo[id] = it }

    fun describeBook(id: String, force: Boolean = false) {
        if ((!force && bookInfo(id) != null) || bookLookup[id] == LOOKING || !app.online.value) return
        val key = seriesKey(Books.SOURCE, id)
        bookLookup[id] = LOOKING
        viewModelScope.launch {
            bookLookup[id] = try {
                val item = data.value.library.firstOrNull { it.key == key }
                val file = app.books.file(id)
                val meta = if (file.exists()) withContext(Dispatchers.IO) { Epub(file).use { FileMeta(it.title, it.authors, it.language, it.description) } } else null
                val isbns = (listOfNotNull(Books.isbnOf(id)) + item?.isbns.orEmpty()).distinct()
                val info = app.lookup.find(isbns, meta?.title ?: item?.title.orEmpty(), meta?.authors.orEmpty(), meta?.language, meta?.description)
                if (info == null) {
                    "Livre inconnu des catalogues en ligne"
                } else {
                    app.books.saveInfo(id, info)
                    bookInfo[id] = info
                    store.update(op = Ops.describeBook(key, info.title, info.cover, info.isbns))
                    ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Infos en ligne indisponibles : ${describe(e)}"
            }
        }
    }

    fun deleteBookFile(id: String) = app.books.delete(id)

    // ---- web searches ----

    fun saveWebSearch(index: Int?, search: WebSearch) = store.update(op = Ops.saveWebSearch(index, search))

    fun removeWebSearch(index: Int) = store.update(op = Ops.removeWebSearch(index))

    fun openWeb(search: WebSearch, query: String) = open(Screen.Web(search.urlFor(query), search.name))

    fun removeBook(id: String) {
        removeFromLibrary(seriesKey(Books.SOURCE, id))
        app.books.delete(id)
        back()
    }

    // ---- discover & search ----

    /** Enabled sources, or the one picked. */
    private fun sourcesFor(source: String?) = source?.let { listOf(it) } ?: app.sources.enabled().map { it.config.id }

    var feed by mutableStateOf(Feed.LATEST)
        private set
    /** Null: every enabled source. */
    var discoverSource by mutableStateOf<String?>(null)
        private set
    val discover = Lister(viewModelScope, app.sources::get, ::describe) { src, page ->
        if (feed == Feed.LATEST) src.latest(page) else src.popular(page)
    }

    fun discover(feed: Feed = this.feed, source: String? = discoverSource) {
        this.feed = feed
        discoverSource = source
        discover.reset(sourcesFor(source))
    }

    var query by mutableStateOf("")
        private set
    var searchSource by mutableStateOf<String?>(null)
        private set
    val search = Lister(viewModelScope, app.sources::get, ::describe) { src, page -> src.search(query, page) }

    fun search(query: String = this.query, source: String? = searchSource) {
        this.query = query.trim()
        searchSource = source
        if (this.query.isBlank()) search.clear() else search.reset(sourcesFor(source))
    }

    // ---- sources ----

    fun addSource(typeId: String): String {
        val type = Catalog.type(typeId) ?: return ""
        val taken = data.value.sources.map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) type.id else "${type.id}-$it" }.first { it !in taken }
        store.update(op = Ops.saveSource(SourceConfig(id, type.id, type.name, type.defaultBaseUrl, options = type.options.associate { it.key to it.default.toString() })))
        return id
    }

    fun saveSource(config: SourceConfig) {
        store.update(op = Ops.saveSource(config))
        // A new address or option: lists made with the old one are reloaded.
        discover.clear()
        if (query.isNotBlank()) search()
    }

    fun removeSource(id: String) {
        store.update(op = Ops.removeSource(id))
        back()
    }

    var testResult by mutableStateOf<String?>(null)
        private set

    fun testSource(id: String) {
        testResult = "Test en cours…"
        viewModelScope.launch {
            testResult = try {
                val src = app.sources.get(id) ?: throw SourceException("Source introuvable")
                val n = src.latest(1).items.size
                "OK : $n séries récentes trouvées"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Échec : ${describe(e)}"
            }
        }
    }

    fun clearTest() {
        testResult = null
    }

    fun describe(e: Exception): String = when (e) {
        is SourceException -> e.message ?: "Erreur de la source"
        is java.net.UnknownHostException -> "Site injoignable : pas de connexion, ou l'adresse de la source a changé"
        is java.net.SocketTimeoutException, is java.net.ConnectException -> "Le site ne répond pas"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun refreshStorage() {
        storageOk = store.hasStorageAccess()
    }
}
