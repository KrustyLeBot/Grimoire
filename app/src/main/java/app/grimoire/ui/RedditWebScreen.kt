package app.grimoire.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
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
import app.grimoire.Screen
import app.grimoire.suite.SuiteTopBar

/**
 * A post on reddit.com itself, inside Grimoire: the real comments (tree, sorting, more replies),
 * and voting or replying once logged in. The WebView keeps Reddit's cookies, so the login made
 * here once stays. Links leaving Reddit open in the browser; app-only links (intent:, reddit:) are
 * ignored, the page stays. Reddit's machine-translated pages (?tl=<language>) are replaced by the
 * original.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RedditWebScreen(vm: MainViewModel, s: Screen.RedditWeb) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var current by remember { mutableStateOf(s.url) }
    // The last original loaded instead of a translation, and when: Reddit sending back to the
    // translation at once means it insists, and the page is left as it is rather than looping.
    var untranslatedAt by remember { mutableStateOf<Pair<String, Long>?>(null) }
    fun original(view: WebView, url: Uri): Boolean {
        if (url.getQueryParameter("tl") == null) return false
        val plain = url.buildUpon().clearQuery().apply {
            url.queryParameterNames.filter { it != "tl" }.forEach { k -> url.getQueryParameters(k).forEach { appendQueryParameter(k, it) } }
        }.build().toString()
        val last = untranslatedAt
        if (last != null && last.first == plain && System.currentTimeMillis() - last.second < 5_000) return false
        untranslatedAt = plain to System.currentTimeMillis()
        view.stopLoading()
        view.loadUrl(plain)
        return true
    }
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush() // the login survives the app being killed
            view?.destroy()
        }
    }
    BackHandler { if (view?.canGoBack() == true) view?.goBack() else vm.back() }

    Scaffold(topBar = {
        SuiteTopBar("r/${s.sub}", onBack = { vm.back() }) {
            IconButton(onClick = { view?.loadUrl("https://www.reddit.com/login/") }) { Icon(Icons.Default.AccountCircle, "Se connecter à Reddit") }
            IconButton(onClick = { vm.open(Screen.RedditThread(s.id, null, s.sub)) }) { Icon(Icons.AutoMirrored.Filled.Article, "Vue Grimoire") }
            IconButton(onClick = { view?.reload() }) { Icon(Icons.Default.Refresh, "Recharger") }
            IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current))) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Ouvrir dans le navigateur")
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (progress in 0.01f..0.99f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Android 13+: the page sees the app's light or dark theme (prefers-color-scheme).
                        if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                if (u.scheme != "http" && u.scheme != "https") return true
                                val host = u.host.orEmpty()
                                if (host.endsWith("reddit.com") || host.endsWith("redd.it") || host.endsWith("redditmedia.com")) {
                                    if (original(view, u)) return true
                                    current = u.toString()
                                    return false
                                }
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                                return true
                            }

                            // Navigations made by the page's own script do not go through shouldOverrideUrlLoading.
                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                original(view, Uri.parse(url))
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                current = url
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                        }
                        loadUrl(s.url)
                        view = this
                    }
                },
            )
        }
    }
}
