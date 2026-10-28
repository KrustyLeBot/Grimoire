package app.grimoire.source

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one HTTP client of the sources. Cookies are Android's WebView store, so logging in (or
 * passing an anti-bot page) in the source's WebView also works for the app. The user agent is a
 * mobile Chrome's, like the WebView's. Requests to one host are paced [SPACING_MS] apart, so
 * browsing never hammers a site.
 */
class Http {
    private val lastRequest = mutableMapOf<String, Long>()

    private val pacing = Interceptor { chain ->
        val host = chain.request().url.host
        val wait = synchronized(lastRequest) {
            val now = System.currentTimeMillis()
            val next = maxOf(now, (lastRequest[host] ?: 0) + SPACING_MS)
            lastRequest[host] = next
            next - now
        }
        if (wait > 0) Thread.sleep(wait)
        chain.proceed(chain.request())
    }

    private val cookies = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
            cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull() ?: return emptyList()
            return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
        }
    }

    /** For images: no pacing (Coil limits how many run at once), same cookies and user agent. */
    val images: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        .build()

    private val pages: OkHttpClient = images.newBuilder().addInterceptor(pacing).build()

    /** The body of a GET, or a [SourceException] saying what went wrong in French. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        pages.newCall(request).await().use { r ->
            when {
                r.isSuccessful -> r.body?.string().orEmpty()
                r.code == 403 || r.code == 503 -> throw SourceException("Le site bloque l'accès (${r.code}) : ouvre-le une fois dans la WebView de la source")
                r.code == 404 -> throw SourceException("Page introuvable sur le site (404)")
                else -> throw SourceException("Le site a répondu ${r.code}")
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
        })
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        private const val SPACING_MS = 350L
    }
}
