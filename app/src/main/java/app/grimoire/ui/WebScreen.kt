package app.grimoire.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.grimoire.MainViewModel
import app.grimoire.source.Http
import app.grimoire.suite.SuiteBanner
import app.grimoire.suite.SuiteTopBar
import app.grimoire.suite.Tone

/**
 * A website inside Grimoire (a configured web search, a book's download page). A file the site
 * offers is downloaded by Grimoire itself, with the site's cookies, straight into the library;
 * the link and the page it came from are kept with the book to fetch it again later.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(vm: MainViewModel, url: String, name: String) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf(name) }
    var progress by remember { mutableFloatStateOf(0f) }
    var current by remember { mutableStateOf(url) }
    DisposableEffect(Unit) { onDispose { CookieManager.getInstance().flush() } }
    BackHandler { if (view?.canGoBack() == true) view?.goBack() else vm.back() }

    Scaffold(topBar = {
        SuiteTopBar(title, onBack = { vm.back() }) {
            IconButton(onClick = { view?.reload() }) { Icon(Icons.Default.Refresh, "Recharger") }
            IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current))) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Ouvrir dans le navigateur")
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (progress in 0.01f..0.99f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (vm.importing) SuiteBanner("Téléchargement du livre…", progress = -1f)
            vm.notice?.let { SuiteBanner(it, tone = Tone.Info) }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = Http.USER_AGENT
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                current = url
                            }

                            /** A direct link to an .epub is downloaded rather than shown. */
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                if (u.scheme != "http" && u.scheme != "https") return true
                                if (u.path.orEmpty().lowercase().endsWith(".epub")) {
                                    vm.downloadBook(u.toString(), mapOf("Referer" to current), page = current)
                                    return true
                                }
                                return false
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress / 100f
                            }

                            override fun onReceivedTitle(view: WebView, t: String?) {
                                if (!t.isNullOrBlank()) title = t
                            }
                        }
                        setDownloadListener { link, _, _, _, _ ->
                            if (link.startsWith("http")) vm.downloadBook(link, mapOf("Referer" to current), page = current)
                            else vm.show("Ce site fabrique le fichier dans la page : ouvre-le dans le navigateur, puis le fichier avec Grimoire")
                        }
                        loadUrl(url)
                        view = this
                    }
                },
            )
        }
    }
}
