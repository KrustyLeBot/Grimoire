package app.grimoire.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import app.grimoire.source.Catalog
import app.grimoire.source.SourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The synced state (sources, library, progress) and its files in `Documents/Grimoire`
 * (docs/SPECS.md §3). Every change is an operation on the state; while a sync runs, operations
 * wait in a queue and are applied again on top of what the sync brought, then written.
 * An unreadable file raises [error] and is never overwritten.
 */
class Store(private val context: Context) {
    private val publicDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Grimoire")
    private val privateDir get() = File(context.filesDir, "grimoire")

    fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** The folder Synchrotron syncs (meaningful once [hasStorageAccess]). */
    val syncPath: String get() = publicDir.path

    private val dir: File get() = (if (hasStorageAccess()) publicDir else privateDir).also { it.mkdirs() }

    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var syncing = false
    private val queue = mutableListOf<(AppData) -> AppData>()
    /** What the files hold: writes only touch the files that differ from it. */
    private var written = AppData()

    /** Reads the files (after a sync, or at start), then applies what waited during the sync. */
    @Synchronized
    fun load() {
        moveToFolder()
        val loaded = try {
            val sources = read(SOURCES)
            AppData(
                sources = sources?.let(Codec::sources) ?: defaultSources(),
                webSearches = sources?.let(Codec::webSearches).orEmpty(),
                library = read(LIBRARY)?.let(Codec::library).orEmpty(),
                progress = read(PROGRESS)?.let(Codec::progress).orEmpty(),
            ).also { _error.value = null }
        } catch (e: Exception) {
            _error.value = e.message
            return
        }
        written = loaded
        var next = loaded
        queue.forEach { next = it(next) }
        queue.clear()
        _data.value = next
        write()
    }

    @Synchronized
    fun setSyncing(on: Boolean) {
        syncing = on
    }

    /**
     * Applies [op]. With [persist] false the change stays in memory until the next [flush] or
     * persisted change: the reader's position moves at every page, and writing each one would
     * wake Synchrotron's watcher for every page turned.
     */
    @Synchronized
    fun update(persist: Boolean = true, op: (AppData) -> AppData) {
        if (_error.value != null) return
        val after = op(_data.value)
        if (after == _data.value) return
        _data.value = after
        if (syncing) queue += op else if (persist) write()
    }

    @Synchronized
    fun flush() {
        if (!syncing && _error.value == null) write()
    }

    private fun write() {
        val now = _data.value
        try {
            if (now.sources != written.sources || now.webSearches != written.webSearches) {
                save(SOURCES, Codec.sources(now.sources, now.webSearches))
            }
            if (now.library != written.library) save(LIBRARY, Codec.library(now.library))
            if (now.progress != written.progress) save(PROGRESS, Codec.progress(now.progress))
            if (now != written) ensureSyncIgnore()
            written = now
        } catch (e: Exception) {
            _error.value = "Enregistrement impossible : ${e.message}"
        }
    }

    /** A sync may be replacing the file: a parse failure is read again once before being reported. */
    private fun read(name: String): String? {
        val f = File(dir, name)
        return try {
            AtomicFiles.read(f) { text -> org.json.JSONObject(text); text }
        } catch (e: UnreadableFileException) {
            throw IllegalStateException("${f.path} est illisible. Il ne sera pas modifié.")
        }
    }

    private fun save(name: String, text: String) = AtomicFiles.write(File(dir, name), text)

    private fun ensureSyncIgnore() {
        val f = File(dir, ".syncignore")
        if (!f.exists()) AtomicFiles.write(f, "# Read by Synchrotron: downloads and caches live in the app's private storage, nothing to exclude here.\n")
    }

    /** Once access is granted, the private files move to the folder; a file already there wins. */
    private fun moveToFolder() {
        if (!hasStorageAccess() || !privateDir.exists()) return
        publicDir.mkdirs()
        privateDir.listFiles()?.forEach { f ->
            val target = File(publicDir, f.name)
            if (!target.exists()) f.copyTo(target)
            f.delete()
        }
        privateDir.delete()
    }

    /** A fresh install starts with the built-in catalog's sources, ready to browse. */
    private fun defaultSources() = Catalog.types.map { SourceConfig(it.id, it.id, it.name, it.defaultBaseUrl) }

    private companion object {
        const val SOURCES = "sources.json"
        const val LIBRARY = "library.json"
        const val PROGRESS = "progress.json"
    }
}
