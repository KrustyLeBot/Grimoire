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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
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
    val dark = isSystemInDarkTheme()
    val page = when (model.theme) {
        BookTheme.AUTO -> if (dark) PageColors.DARK else PageColors.LIGHT
        BookTheme.LIGHT -> PageColors.LIGHT
        BookTheme.DARK -> PageColors.DARK
        BookTheme.PAPER -> PageColors.PAPER
    }
    Box(Modifier.fillMaxSize().background(Color(android.graphics.Color.parseColor(page.background)))) {
        val epub = model.epub
        when {
            model.error != null -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(model.error.orEmpty(), textAlign = TextAlign.Center, color = Color(android.graphics.Color.parseColor(page.text)))
                TextButton(onClick = onBack) { Text("Retour") }
            }
            epub == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> BookView(model, epub, page, onTap = { menu = !menu })
        }

        // The menu follows the app's theme; a Surface gives its texts and icons the matching colour.
        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = colors.surfaceContainer, contentColor = colors.onSurface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                    Column(Modifier.weight(1f)) {
                        Text(model.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(model.chapterTitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { toc = true }, enabled = epub?.toc?.isNotEmpty() == true) { Icon(Icons.AutoMirrored.Filled.List, "Table des matières") }
                }
            }
        }
        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = colors.surfaceContainer, contentColor = colors.onSurface) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    // Parts and progress: always on their own row, so they fit any width.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { model.previous() }, enabled = model.index > 0) { Icon(Icons.Default.SkipPrevious, "Partie précédente") }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${(model.overall * 100).toInt()} % · partie ${model.index + 1} / ${model.count}",
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            LinearProgressIndicator(progress = { model.overall }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                        IconButton(onClick = { model.next() }, enabled = model.index < model.count - 1) { Icon(Icons.Default.SkipNext, "Partie suivante") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(model.paged, { model.setMode(true) }, { Text("Pages", maxLines = 1) })
                            FilterChip(!model.paged, { model.setMode(false) }, { Text("Défilement", maxLines = 1) })
                        }
                        IconButton(onClick = { model.changeTextSize(-1) }) { Icon(Icons.Default.TextDecrease, "Texte plus petit") }
                        IconButton(onClick = { model.changeTextSize(1) }) { Icon(Icons.Default.TextIncrease, "Texte plus grand") }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BookTheme.entries.forEach { t ->
                            FilterChip(model.theme == t, { model.chooseTheme(t) }, { Text(t.label, maxLines = 1) })
                        }
                    }
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


/**
 * The chapter in a WebView, its files served straight from the EPUB (nothing goes to the
 * network). Each chapter is prepared by [Chapter] with the reader's style and script, which lay
 * it out in pages or as one scroll, report the position and turn pages.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BookView(model: BookReaderModel, epub: Epub, page: PageColors, onTap: () -> Unit) {
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
                        if (url.host != HOST) return empty()
                        val path = URLDecoder.decode(url.encodedPath.orEmpty().removePrefix("/"), "UTF-8")
                        val bytes = runCatching { epub.open(path)?.use { it.readBytes() } }.getOrNull() ?: return empty()
                        if (path !in epub.spine) return WebResourceResponse(mime(path), null, ByteArrayInputStream(bytes))
                        val source = Chapter.decode(bytes)
                        val html = Chapter.render(source, css(model, page), script(model, path, epub))
                        val type = if (Chapter.keepsMarkup(source)) mime(path) else "text/html"
                        return WebResourceResponse(type, "utf-8", ByteArrayInputStream(html.toByteArray()))
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.host == HOST) model.openPath(URLDecoder.decode(url.encodedPath.orEmpty().removePrefix("/"), "UTF-8"))
                        return true
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

private fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

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

private fun css(model: BookReaderModel, page: PageColors): String {
    val common = """
        html, body { margin: 0 !important; padding: 0 !important; background: ${page.background} !important; }
        body { -webkit-text-size-adjust: 100%; }
        #g-flow { box-sizing: border-box; color: ${page.text} !important; font-size: ${model.textSize}px !important;
                  line-height: 1.6 !important; overflow-wrap: break-word; hyphens: auto; text-align: start; }
        #g-flow * { color: inherit !important; background-color: transparent !important; max-width: 100% !important; }
        #g-flow p, #g-flow div, #g-flow span, #g-flow li, #g-flow td, #g-flow blockquote, #g-flow a, #g-flow em, #g-flow i, #g-flow b, #g-flow strong {
                  font-size: inherit !important; line-height: inherit !important; }
        #g-flow h1 { font-size: 1.6em !important; line-height: 1.3 !important; }
        #g-flow h2 { font-size: 1.35em !important; line-height: 1.3 !important; }
        #g-flow h3 { font-size: 1.2em !important; line-height: 1.3 !important; }
        #g-flow h4, #g-flow h5, #g-flow h6 { font-size: 1.05em !important; line-height: 1.3 !important; }
        #g-flow a[href], #g-flow a[href] * { color: ${page.link} !important; text-decoration: none; }
        #g-flow img, #g-flow svg, #g-flow video { max-width: 100% !important; height: auto !important; }
        #g-end { display: inline-block; width: 1px; height: 1px; }
    """
    val layout = if (model.paged) """
        html, body { height: 100% !important; overflow: hidden !important; }
        #g-flow { position: absolute !important; top: 0; left: 0; margin: 0 !important; padding: 32px 24px !important;
                  column-gap: 48px; column-fill: auto; overflow: hidden !important; }
        #g-flow img, #g-flow svg { object-fit: contain; break-inside: avoid; }
    """ else """
        #g-flow { padding: 24px 20px 8px !important; }
        #g-next { display: block; margin: 24px auto 64px; font: inherit; font-size: 16px; padding: 10px 22px;
                  border-radius: 22px; border: 1px solid ${page.link}; background: transparent; color: ${page.link}; }
        #g-last { text-align: center; margin: 24px 0 64px; opacity: .7; color: ${page.text}; }
    """
    return common + layout
}

/** The reader's part of every chapter. Plain ES5 inside CDATA: chapters are often strict XHTML. */
private fun script(model: BookReaderModel, path: String, epub: Epub): String {
    val config = JSONObject()
        .put("paged", model.paged)
        .put("start", if (path == epub.spine.getOrNull(model.index)) model.start.toDouble() else 0.0)
        .put("last", path == epub.spine.last())
    return """
(function () {
  var c = $config;
  var flow = document.getElementById('g-flow'), end = document.getElementById('g-end');
  var se = document.scrollingElement || document.documentElement;
  var page = 0, fraction = c.start, shown = false, width = 0, height = 0;
  // Pages: the flow gets the window's size in pixels (measured, not 100vh, which a WebView can
  // read as 0 before its first layout) and is split into one column per screen; turning a page
  // scrolls it by one screen width.
  function layout() {
    width = window.innerWidth; height = window.innerHeight;
    if (!c.paged || width <= 0 || height <= 0) return;
    flow.style.width = width + 'px';
    flow.style.height = height + 'px';
    flow.style.columnWidth = (width - 48) + 'px';
    var media = flow.querySelectorAll('img, svg, video');
    for (var i = 0; i < media.length; i++) {
      media[i].style.maxHeight = (height - 72) + 'px';
      // A cover's wrapper is often height: 100%, which spills it onto an empty second page.
      for (var e = media[i].parentNode; e && e !== flow && e.nodeType === 1; e = e.parentNode) {
        if (e.textContent.replace(/\s/g, '') !== '') break;
        e.style.setProperty('height', 'auto', 'important');
      }
    }
  }
  function count() {
    if (width <= 0) return 1;
    var x = end.getBoundingClientRect().left - flow.getBoundingClientRect().left + flow.scrollLeft;
    var n = Math.floor((x + 1) / width) + 1;
    return isFinite(n) && n > 0 ? n : 1;
  }
  function show(p) { page = p; flow.scrollLeft = p * width; }
  function report() {
    if (c.paged) { var n = count(); fraction = n > 1 ? page / (n - 1) : 1; }
    else { var max = se.scrollHeight - window.innerHeight; fraction = max > 0 ? se.scrollTop / max : 1; }
    if (isFinite(fraction)) Grimoire.position(fraction);
  }
  function go(f) {
    try {
      layout();
      if (c.paged) { var n = count(); show(Math.max(0, Math.min(n - 1, Math.round(f * (n - 1))))); }
      else { se.scrollTop = f * (se.scrollHeight - window.innerHeight); }
      report();
    } finally {
      if (!shown) { shown = true; document.body.style.visibility = 'visible'; }
    }
  }
  window.grimoire = {
    turn: function (d) {
      var n = count(), p = page + d;
      if (p < 0) { Grimoire.prev(); return; }
      if (p >= n) { Grimoire.next(); return; }
      show(p); report();
    }
  };
  document.body.style.visibility = 'hidden';
  if (!c.paged) {
    var tail;
    if (c.last) { tail = document.createElement('p'); tail.id = 'g-last'; tail.textContent = 'Fin du livre'; }
    else {
      tail = document.createElement('button'); tail.id = 'g-next'; tail.textContent = 'Partie suivante';
      tail.addEventListener('click', function (e) { e.stopPropagation(); Grimoire.next(); });
    }
    document.body.appendChild(tail);
    var timer = null;
    window.addEventListener('scroll', function () { if (timer) clearTimeout(timer); timer = setTimeout(report, 250); }, { passive: true });
  }
  document.addEventListener('click', function (e) {
    var t = e.target;
    if (t && t.closest && t.closest('a[href], button')) return;
    Grimoire.tap(e.clientX / window.innerWidth);
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
  window.addEventListener('resize', function () {
    if (window.innerWidth !== width || window.innerHeight !== height) go(isFinite(fraction) ? fraction : 0);
  });
  // Waits for the WebView to have a size, and for images and fonts to be laid out.
  var tries = 0;
  function ready() {
    if ((window.innerWidth <= 0 || window.innerHeight <= 0) && tries++ < 100) { setTimeout(ready, 20); return; }
    setTimeout(function () { go(c.start); }, 30);
  }
  if (document.readyState === 'complete') ready(); else window.addEventListener('load', ready);
  setTimeout(function () { if (!shown) go(c.start); }, 2500);
})();
"""
}
