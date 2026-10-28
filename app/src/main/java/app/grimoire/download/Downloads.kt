package app.grimoire.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.BackoffPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.grimoire.source.ChapterInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.io.File

/**
 * Downloaded chapters, in the app's private storage (never synced): one folder per chapter with
 * its pages (`0001.webp`…) and a `chapter.json` written last, so a folder without it is unfinished.
 */
class Downloads(private val context: Context, scope: CoroutineScope) {
    val root: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")

    /** Chapter keys ("source:slug:chapter") fully downloaded. */
    private val _done = MutableStateFlow(scan())
    val done: StateFlow<Set<String>> = _done

    /**
     * Chapter key → progress 0..1 of downloads not finished yet, -1 while waiting (queued, no
     * Wi-Fi, retry pending). Read from WorkManager itself, so it survives a restart of the app and
     * never shows a download that died.
     */
    val running: StateFlow<Map<String, Float>> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG)
        .onEach { infos ->
            if (infos.any { it.state == WorkInfo.State.SUCCEEDED && keyOf(it) !in _done.value }) _done.value = scan()
        }
        .map { infos ->
            infos.filterNot { it.state.isFinished }.mapNotNull { info ->
                keyOf(info)?.let { it to if (info.state == WorkInfo.State.RUNNING) info.progress.getFloat(PROGRESS, 0f) else -1f }
            }.toMap()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private fun keyOf(info: WorkInfo) = info.tags.firstOrNull { it.startsWith(KEY_TAG) }?.removePrefix(KEY_TAG)

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
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setInputData(workDataOf("source" to source, "slug" to slug, "chapter" to ch.id, "name" to ch.name, "number" to ch.number))
                .addTag(TAG)
                .addTag(KEY_TAG + k)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()
            wm.enqueueUniqueWork(k, ExistingWorkPolicy.KEEP, request)
        }
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
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

    internal fun finished(k: String) = _done.update { it + k }

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
        private const val KEY_TAG = "chapter:"
        internal const val PROGRESS = "progress"
        fun key(source: String, slug: String, chapter: String) = "$source:${slug.replace('/', '_')}:$chapter"
    }
}
