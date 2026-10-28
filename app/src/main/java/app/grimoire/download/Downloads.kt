package app.grimoire.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.grimoire.source.ChapterInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

/**
 * Downloaded chapters, in the app's private storage (never synced): one folder per chapter with
 * its pages (`0001.webp`…) and a `chapter.json` written last, so a folder without it is unfinished.
 */
class Downloads(private val context: Context) {
    val root: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")

    /** Chapter keys ("source:slug:chapter") fully downloaded. */
    private val _done = MutableStateFlow(scan())
    val done: StateFlow<Set<String>> = _done

    /** Chapter key → progress 0..1 for downloads queued or running. */
    private val _running = MutableStateFlow<Map<String, Float>>(emptyMap())
    val running: StateFlow<Map<String, Float>> = _running

    fun dir(source: String, slug: String, chapter: String) = File(File(File(root, source), slug.replace('/', '_')), chapter)

    fun isDone(source: String, slug: String, chapter: String) = key(source, slug, chapter) in _done.value

    /** Local page files of a downloaded chapter, in order; null when it is not downloaded. */
    fun pages(source: String, slug: String, chapter: String): List<File>? {
        if (!isDone(source, slug, chapter)) return null
        return dir(source, slug, chapter).listFiles { f -> f.name != META }?.sortedBy { it.name }
    }

    fun enqueue(source: String, slug: String, chapters: List<ChapterInfo>, wifiOnly: Boolean) {
        val wm = WorkManager.getInstance(context)
        chapters.filterNot { it.locked || isDone(source, slug, it.id) }.forEach { ch ->
            val k = key(source, slug, ch.id)
            _running.update { it + (k to 0f) }
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setInputData(workDataOf("source" to source, "slug" to slug, "chapter" to ch.id, "name" to ch.name, "number" to ch.number))
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork(k, ExistingWorkPolicy.KEEP, request)
        }
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        _running.value = emptyMap()
    }

    fun delete(source: String, slug: String, chapters: Collection<String>) {
        chapters.forEach { dir(source, slug, it).deleteRecursively() }
        _done.value = scan()
    }

    fun deleteSeries(source: String, slug: String) {
        File(File(root, source), slug.replace('/', '_')).deleteRecursively()
        _done.value = scan()
    }

    fun deleteAll() {
        cancelAll()
        root.deleteRecursively()
        _done.value = emptySet()
    }

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    internal fun progress(k: String, value: Float) = _running.update { it + (k to value) }

    internal fun finished(k: String, ok: Boolean) {
        _running.update { it - k }
        if (ok) _done.update { it + k }
    }

    internal fun writeMeta(dir: File, name: String, pages: Int) =
        File(dir, META).writeText(JSONObject().put("name", name).put("pages", pages).toString())

    /** Folders with a chapter.json: "source/slug/chapter" → key. */
    private fun scan(): Set<String> {
        val out = mutableSetOf<String>()
        root.listFiles()?.forEach { s -> s.listFiles()?.forEach { series -> series.listFiles()?.forEach { ch ->
            if (File(ch, META).exists()) out += key(s.name, series.name, ch.name)
        } } }
        return out
    }

    companion object {
        private const val META = "chapter.json"
        private const val TAG = "download"
        fun key(source: String, slug: String, chapter: String) = "$source:${slug.replace('/', '_')}:$chapter"
    }
}
