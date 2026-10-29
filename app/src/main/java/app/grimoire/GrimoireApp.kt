package app.grimoire

import android.app.Application
import app.grimoire.book.BookLookup
import app.grimoire.book.Books
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.grimoire.data.SeriesCache
import app.grimoire.data.Store
import app.grimoire.download.Downloads
import app.grimoire.source.Http
import app.grimoire.source.Sources
import app.grimoire.suite.SuiteUpdater
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GrimoireApp : Application(), SingletonImageLoader.Factory {
    val http by lazy { Http() }
    val store by lazy { Store(this) }
    val cache by lazy { SeriesCache(this) }
    val downloads by lazy { Downloads(this, scope) }
    val sources by lazy { Sources(store, http) }
    val updater by lazy { SuiteUpdater(this, "KrustyLeBot/Grimoire", "$packageName.fileprovider") }
    val settings by lazy { Settings(this) }
    val books by lazy { Books(this, http) }
    val lookup by lazy { BookLookup(http) }
    val redditConfig by lazy { app.grimoire.reddit.RedditConfigStore(this) }
    val redditClient by lazy { app.grimoire.reddit.RedditClient(redditConfig, cacheDir) }

    /** Work that outlives a screen (marking a chapter read when leaving the reader, say). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    override fun onCreate() {
        super.onCreate()
        store.load()
        val cm = getSystemService(ConnectivityManager::class.java)
        _online.value = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _online.value = true }
            override fun onLost(network: Network) { _online.value = false }
        })
    }

    /** Covers and pages: Coil with the sources' client (cookies, user agent) and a 512 MB disk cache. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { http.images })) }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(512L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
