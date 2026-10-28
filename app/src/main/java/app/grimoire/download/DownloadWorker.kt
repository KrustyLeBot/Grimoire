package app.grimoire.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.grimoire.GrimoireApp
import app.grimoire.R
import app.grimoire.source.ChapterContent
import app.grimoire.source.ChapterInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Downloads one chapter's pages (one job per chapter, so a failure only retries that chapter). */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app get() = applicationContext as GrimoireApp

    override suspend fun doWork(): Result {
        val source = inputData.getString("source") ?: return Result.failure()
        val slug = inputData.getString("slug") ?: return Result.failure()
        val id = inputData.getString("chapter") ?: return Result.failure()
        val name = inputData.getString("name").orEmpty()
        val key = Downloads.key(source, slug, id)
        val src = app.sources.get(source) ?: return Result.failure()
        notify(name, 0f)
        val dir = app.downloads.dir(source, slug, id)
        return try {
            val content = src.content(slug, ChapterInfo(id, name, inputData.getFloat("number", 0f), 0))
            val urls = (content as? ChapterContent.Images)?.urls ?: error("Chapitre sans images")
            dir.deleteRecursively()
            dir.mkdirs()
            urls.forEachIndexed { i, url ->
                fetch(url, File(dir, "%04d.%s".format(i + 1, extension(url))), src.imageHeaders())
                val p = (i + 1f) / urls.size
                setProgress(workDataOf(Downloads.PROGRESS to p))
                notify(name, p)
            }
            app.downloads.writeMeta(dir, name, urls.size)
            app.downloads.finished(key)
            Result.success()
        } catch (e: CancellationException) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /**
     * Android refuses to start a foreground service while the app is in the background (several
     * chapters queued, the screen turned off while reading…): the download then goes on without
     * its notification instead of failing.
     */
    private suspend fun notify(name: String, progress: Float) {
        try {
            setForeground(foreground(name, progress))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun fetch(url: String, target: File, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        app.http.images.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "Image ${r.code}" }
            val tmp = File(target.parentFile, target.name + ".part")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target)) { "Écriture impossible" }
        }
    }

    private fun extension(url: String) = url.substringBefore('?').substringAfterLast('.', "webp").take(4).lowercase()

    private fun foreground(name: String, progress: Float): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_grimoire)
            .setContentTitle("Téléchargement")
            .setContentText(name)
            .setProgress(100, (progress * 100).toInt(), progress == 0f)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 7
    }
}
