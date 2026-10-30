package app.grimoire.ui

import android.annotation.SuppressLint
import android.content.Intent
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
import app.grimoire.Screen
import app.grimoire.suite.SuiteTopBar

/**
 * A post on reddit.com itself, inside Grimoire: the real comments (tree, sorting, more replies),
 * and voting or replying once logged in. The WebView keeps Reddit's cookies, so the login made
 * here once stays. Links leaving Reddit open in the browser; app-only links (intent:, reddit:) are
 * ignored, the page stays.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RedditWebScreen(vm: MainViewModel, s: Screen.RedditWeb) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var current by remember { mutableStateOf(s.url) }
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush() // the login survives the app being killed
            view?.destroy()
        }
    }
    BackHandler { if (view?.canGoBack() == true) view?.goBack() else vm.back() }

    Scaffold(topBar = {
        SuiteTopBar("r/${s.sub}", onBack = { vm.back() }) {
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
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                if (u.scheme != "http" && u.scheme != "https") return true
                                val host = u.host.orEmpty()
                                if (host.endsWith("reddit.com") || host.endsWith("redd.it") || host.endsWith("redditmedia.com")) {
                                    current = u.toString()
                                    return false
                                }
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                                return true
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
