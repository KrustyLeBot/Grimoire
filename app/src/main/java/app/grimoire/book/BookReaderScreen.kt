package app.grimoire.book

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.grimoire.GrimoireApp
import app.grimoire.reader.Immersive
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder

private const val HOST = "book.grimoire"

@Composable
fun BookReaderScreen(model: BookReaderModel, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GrimoireApp
    var menu by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf(false) }
    Immersive(hidden = !menu, keepOn = app.settings.keepScreenOn)
    DisposableEffect(Unit) { onDispose { model.close() } }
    BackHandler { if (toc) toc = false else onBack() }

    val colors = MaterialTheme.colorScheme
    val page = Page(colors.surface.css(), colors.onSurface.css(), colors.primary.css())
    Box(Modifier.fillMaxSize().background(colors.surface)) {
        val epub = model.epub
        when {
            model.error != null -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(model.error.orEmpty(), textAlign = TextAlign.Center)
                TextButton(onClick = onBack) { Text("Retour") }
            }
            epub == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> BookView(model, epub, page, onTap = { menu = !menu })
        }

        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(colors.surfaceContainer).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                Column(Modifier.weight(1f)) {
                    Text(model.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(model.chapterTitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { toc = true }, enabled = epub?.toc?.isNotEmpty() == true) { Icon(Icons.AutoMirrored.Filled.List, "Table des matières") }
            }
        }
        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(colors.surfaceContainer).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("${(model.overall * 100).toInt()} % · partie ${model.index + 1} / ${model.count}", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { model.overall }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { model.previous() }, enabled = model.index > 0) { Icon(Icons.Default.SkipPrevious, "Partie précédente") }
                    FilterChip(model.paged, { model.setMode(true) }, { Text("Pages") })
                    FilterChip(!model.paged, { model.setMode(false) }, { Text("Défilement") })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { model.changeTextSize(-1) }) { Icon(Icons.Default.TextDecrease, "Texte plus petit") }
                    IconButton(onClick = { model.changeTextSize(1) }) { Icon(Icons.Default.TextIncrease, "Texte plus grand") }
                    IconButton(onClick = { model.next() }, enabled = model.index < model.count - 1) { Icon(Icons.Default.SkipNext, "Partie suivante") }
                }
            }
        }
    }

    if (toc && model.epub != null) {
        AlertDialog(
            onDismissRequest = { toc = false },
            title = { Text("Table des matières") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(model.epub?.toc.orEmpty()) { _, entry ->
                        val current = model.epub?.spine?.getOrNull(model.index) == entry.path
                        Text(
                            entry.title,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            color = if (current) colors.primary else colors.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable { toc = false; model.openToc(entry) }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { toc = false }) { Text("Fermer") } },
        )
    }
}

private data class Page(val background: String, val text: String, val link: String)

private fun Color.css() = "#%06X".format(toArgb() and 0xFFFFFF)

/**
 * The chapter in a WebView, its files served straight from the EPUB (nothing goes to the
 * network). A small script restyles it (theme colours, text size), lays it out in pages or as one
 * scroll, reports the position and turns pages.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BookView(model: BookReaderModel, epub: Epub, page: Page, onTap: () -> Unit) {
    val bridge = remember(model) { Bridge(model) }
    bridge.onTap = onTap
    AndroidView(
        // Not padded by the system bars: they come and go with the menu, and a resize would
        // shift the pages.
        modifier = Modifier.fillMaxSize().displayCutoutPadding(),
        factory = { context ->
            WebView(context).apply {
                bridge.view = this
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.textZoom = 100
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                addJavascriptInterface(bridge, "Grimoire")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val url = request.url
                        if (url.host != HOST) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        val path = URLDecoder.decode(url.encodedPath.orEmpty().removePrefix("/"), "UTF-8")
                        val stream = runCatching { epub.open(path) }.getOrNull()
                            ?: return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        return WebResourceResponse(mime(path), null, stream)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.host == HOST) model.openPath(URLDecoder.decode(url.encodedPath.orEmpty().removePrefix("/"), "UTF-8"))
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(script(model, page), null)
                    }
                }
            }
        },
        update = { view ->
            val wanted = "${model.generation}|${model.index}"
            if (view.tag != wanted) {
                view.tag = wanted
                view.setBackgroundColor(android.graphics.Color.parseColor(page.background))
                view.loadUrl(urlOf(epub.spine[model.index]))
            }
        },
    )
}

/** What the page's script may call; calls arrive on a WebView thread and are handed to the main one. */
internal class Bridge(private val model: BookReaderModel) {
    private val main = Handler(Looper.getMainLooper())
    var view: WebView? = null
    var onTap: () -> Unit = {}

    @JavascriptInterface
    fun position(fraction: Float) {
        main.post { model.onPosition(fraction) }
    }

    @JavascriptInterface
    fun next() {
        main.post { model.next() }
    }

    @JavascriptInterface
    fun prev() {
        main.post { model.previous() }
    }

    /** Sides of the screen turn pages in Pages mode; anywhere else shows the menu. */
    @JavascriptInterface
    fun tap(x: Float) {
        main.post {
            when {
                model.paged && x < 0.3f -> view?.evaluateJavascript("grimoire.turn(-1)", null)
                model.paged && x > 0.7f -> view?.evaluateJavascript("grimoire.turn(1)", null)
                else -> onTap()
            }
        }
    }
}

private fun urlOf(path: String) = "https://$HOST/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

private fun mime(path: String) = when (path.substringAfterLast('.').lowercase()) {
    "xhtml", "xht", "xml" -> "application/xhtml+xml"
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

private fun css(model: BookReaderModel, page: Page): String {
    val common = """
        html, body { background: ${page.background} !important; }
        body { color: ${page.text} !important; font-size: ${model.textSize}px !important; line-height: 1.6 !important;
               margin: 0 !important; overflow-wrap: break-word; -webkit-text-size-adjust: 100%; }
        body * { color: inherit !important; background-color: transparent !important; }
        a, a * { color: ${page.link} !important; }
        img, svg, video { max-width: 100% !important; height: auto !important; }
        p { hyphens: auto; }
    """
    val layout = if (model.paged) """
        html { height: 100% !important; overflow: hidden !important; }
        body { height: 100vh !important; box-sizing: border-box !important; padding: 28px 20px !important;
               column-width: calc(100vw - 40px) !important; column-gap: 40px !important; column-fill: auto !important; }
        img, svg { max-height: calc(100vh - 56px) !important; object-fit: contain; break-inside: avoid; }
    """ else """
        body { padding: 24px 20px 24px !important; }
        #grimoire-end { text-align: center; padding: 32px 0 64px; opacity: .8; }
        #grimoire-end button { font: inherit; padding: 10px 20px; border-radius: 20px; border: 1px solid ${page.link};
               background: transparent; color: ${page.link} !important; }
    """
    return common + layout
}

/** Runs once per loaded chapter. Kept in plain ES5: some EPUB pages are parsed as strict XHTML. */
private fun script(model: BookReaderModel, page: Page): String {
    val config = JSONObject()
        .put("css", css(model, page))
        .put("paged", model.paged)
        .put("start", model.start.toDouble())
        .put("last", model.index == model.count - 1)
    return """
(function () {
  var c = $config;
  var head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
  if (!document.querySelector('meta[name=viewport]')) {
    var m = document.createElementNS('http://www.w3.org/1999/xhtml', 'meta');
    m.setAttribute('name', 'viewport'); m.setAttribute('content', 'width=device-width, initial-scale=1');
    head.appendChild(m);
  }
  var s = document.createElementNS('http://www.w3.org/1999/xhtml', 'style');
  s.textContent = c.css; head.appendChild(s);
  var se = document.scrollingElement || document.documentElement;
  var W = function () { return window.innerWidth; };
  if (!c.paged && document.body) {
    var end = document.createElementNS('http://www.w3.org/1999/xhtml', 'div');
    end.id = 'grimoire-end';
    if (c.last) { end.textContent = 'Fin du livre'; }
    else {
      var b = document.createElementNS('http://www.w3.org/1999/xhtml', 'button');
      b.textContent = 'Partie suivante';
      b.addEventListener('click', function (e) { e.stopPropagation(); Grimoire.next(); });
      end.appendChild(b);
    }
    document.body.appendChild(end);
  }
  function count() { return Math.max(1, Math.round(se.scrollWidth / W())); }
  function report() {
    var f;
    if (c.paged) { var n = count(); var p = Math.round(se.scrollLeft / W()); f = n > 1 ? p / (n - 1) : 1; }
    else { var max = se.scrollHeight - window.innerHeight; f = max > 0 ? se.scrollTop / max : 1; }
    Grimoire.position(f);
  }
  var timer = null;
  window.addEventListener('scroll', function () { if (timer) clearTimeout(timer); timer = setTimeout(report, 250); }, { passive: true });
  window.grimoire = {
    turn: function (d) {
      var n = count(); var p = Math.round(se.scrollLeft / W()) + d;
      if (p < 0) { Grimoire.prev(); return; }
      if (p >= n) { Grimoire.next(); return; }
      se.scrollLeft = p * W(); report();
    }
  };
  document.addEventListener('click', function (e) {
    var t = e.target;
    while (t && t.nodeType === 1) { var n = t.nodeName.toLowerCase(); if (n === 'a' || n === 'button') return; t = t.parentNode; }
    Grimoire.tap(e.clientX / W());
  });
  if (c.paged) {
    var x0 = null, y0 = 0;
    document.addEventListener('touchstart', function (e) { x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; }, { passive: true });
    document.addEventListener('touchend', function (e) {
      if (x0 === null) return;
      var dx = e.changedTouches[0].clientX - x0, dy = e.changedTouches[0].clientY - y0; x0 = null;
      if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) window.grimoire.turn(dx < 0 ? 1 : -1);
    }, { passive: true });
  }
  function go() {
    if (c.paged) { var n = count(); se.scrollLeft = Math.round(c.start * (n - 1)) * W(); }
    else { se.scrollTop = c.start * (se.scrollHeight - window.innerHeight); }
    report();
  }
  if (document.readyState === 'complete') setTimeout(go, 80); else window.addEventListener('load', function () { setTimeout(go, 80); });
})();
"""
}
