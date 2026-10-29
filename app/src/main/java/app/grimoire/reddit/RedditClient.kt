package app.grimoire.reddit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RedditException(message: String) : Exception(message)

/**
 * Talks to the Reddit proxy with the headers of the settings. Every answer is also kept on disk:
 * when the proxy cannot be reached (offline, NAS off), the last copy of a feed or thread is shown.
 */
class RedditClient(private val config: RedditConfigStore, cacheDir: File) {
    private val dir = File(cacheDir, "reddit").apply { mkdirs() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS) // the proxy may wait for its turn with Reddit
        .followRedirects(false) // a redirect means Cloudflare Access refused the token
        .build()
    private val probe = http.newBuilder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2, TimeUnit.SECONDS).build()

    @Volatile private var lan: Pair<String, Long>? = null // (address chosen, when)

    /** The LAN address when it is set and answers (checked every 2 minutes), else the public one. */
    private fun base(c: RedditConfig): String {
        val lanUrl = c.lanUrl.trim().trimEnd('/')
        val public = c.url.trim().trimEnd('/')
        if (lanUrl.isEmpty()) return public
        lan?.let { (chosen, at) -> if (System.currentTimeMillis() - at < 120_000) return chosen }
        val ok = runCatching {
            probe.newCall(Request.Builder().url("$lanUrl/health").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        val chosen = if (ok) lanUrl else public
        lan = chosen to System.currentTimeMillis()
        return chosen
    }

    private fun call(c: RedditConfig, path: String, body: String? = null): String {
        val request = Request.Builder().url(base(c) + path).apply {
            c.headers.forEach { (k, v) -> header(k, v) }
            // Sent up front: the proxy answers 401 rather than asking.
            if (c.user.isNotBlank()) header("Authorization", Credentials.basic(c.user.trim(), c.password.trim()))
            header("Accept", "application/json")
            if (body != null) post(body.toRequestBody("application/json".toMediaType()))
        }.build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            lan = null
            throw RedditException("Proxy Reddit injoignable : ${e.message ?: "réseau"}")
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            val json = r.header("Content-Type").orEmpty().contains("json")
            if (r.isSuccessful && json) return text
            val error = if (json) runCatching { JSONObject(text).optString("error") }.getOrNull() else null
            throw RedditException(
                when {
                    r.code in 300..399 || (!json && (r.code == 403 || r.isSuccessful)) ->
                        "Accès refusé avant le proxy : vérifie les en-têtes Cloudflare dans les réglages"
                    r.code == 401 -> "Identifiant ou mot de passe refusé par le proxy"
                    !error.isNullOrBlank() -> error
                    r.code in 502..530 -> "Proxy Reddit injoignable (HTTP ${r.code})"
                    else -> "Le proxy a répondu HTTP ${r.code}"
                },
            )
        }
    }

    private fun file(key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.json")
    }

    /**
     * The answer for [path], kept on disk under [key]; the last kept copy (marked stale) when the
     * proxy fails.
     */
    private suspend fun cached(path: String, online: Boolean, key: String = path): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val f = file(key)
        if (!online && f.exists()) return@withContext f.readText() to true
        try {
            val text = call(config.load(), path)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
            text to false
        } catch (e: RedditException) {
            if (f.exists()) f.readText() to true else throw e
        }
    }

    suspend fun feed(sub: String, sort: FeedSort, period: TopPeriod, after: String?, online: Boolean, refresh: Boolean = false): RedditFeed {
        val q = buildString {
            append("/feed/").append(sub).append("?sort=").append(sort.raw)
            if (sort == FeedSort.TOP) append("&t=").append(period.raw)
            if (after != null) append("&after=").append(after)
        }
        val (text, stale) = cached(if (refresh) "$q&refresh=1" else q, online, key = q)
        return RedditJson.feed(text).let { if (stale) it.copy(stale = true) else it }
    }

    suspend fun thread(id: String, sort: CommentSort, comment: String?, online: Boolean): RedditThread {
        val q = "/thread/$id?sort=${sort.raw}" + (comment?.let { "&comment=$it" } ?: "")
        val (text, stale) = cached(q, online)
        return RedditJson.thread(text).let { if (stale) it.copy(stale = true) else it }
    }

    suspend fun more(id: String, children: List<String>, sort: CommentSort, online: Boolean): List<RedditComment> {
        val (text, _) = cached("/more/$id?children=${children.take(100).joinToString(",")}&sort=${sort.raw}", online)
        return RedditJson.more(text)
    }

    /**
     * Tells the proxy which feeds this phone shows, so it keeps them fresh. Sent at every start: if
     * the proxy lost its list (restarted, new container), it gets it back from the apps.
     */
    suspend fun watch(c: RedditConfig = config.load()): Unit = withContext(Dispatchers.IO) {
        val feeds = JSONArray()
        c.subs.forEach { sub -> listOf(FeedSort.HOT, FeedSort.NEW).forEach { feeds.put(JSONObject().put("sub", sub).put("sort", it.raw)) } }
        call(c, "/watch", JSONObject().put("feeds", feeds).toString())
    }

    /** Checks settings before they are saved: the proxy must accept the headers. Null when fine. */
    suspend fun test(c: RedditConfig): String? = try {
        lan = null
        watch(c)
        lan = null
        null
    } catch (e: RedditException) {
        e.message
    }

    /** Deletes the copies kept for offline reading. */
    fun clearCache() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun cacheBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0
}
