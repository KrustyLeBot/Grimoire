package app.grimoire.reader

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.grimoire.GrimoireApp
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(model: ReaderModel, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GrimoireApp
    var menu by remember { mutableStateOf(false) }
    Immersive(hidden = !menu, keepOn = app.settings.keepScreenOn)
    DisposableEffect(Unit) { onDispose { model.leave() } }
    BackHandler { onBack() }

    var page by remember(model.chapter) { mutableIntStateOf(model.startPage) }
    var pages by remember(model.chapter) { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val content = model.loaded) {
            null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                val err = model.error
                if (err == null) CircularProgressIndicator(color = Color.White)
                else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(err, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { model.load(model.chapter) }) { Text("Réessayer") }
                    OutlinedButton(onClick = onBack) { Text("Retour", color = Color.White) }
                }
            }
            is Loaded.Images -> {
                LaunchedEffect(content) { pages = content.pages.size }
                if (model.mode == Mode.SCROLL) {
                    ScrollReader(model, content, onMenu = { menu = !menu }, onPage = { page = it })
                } else {
                    PagesReader(model, content, rtl = app.settings.rightToLeft, onMenu = { menu = !menu }, onPage = { page = it })
                }
            }
            is Loaded.Text -> TextReader(model, content, app.settings.textSize, onMenu = { menu = !menu })
        }

        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xE6101014)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(model.details.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(model.chapter.name, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(Color(0xE6101014)).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (pages > 1 && model.loaded is Loaded.Images) {
                    Text("Page ${page + 1} / $pages", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { model.previous?.let { model.load(it) } }, enabled = model.previous != null) {
                        Icon(Icons.Default.SkipPrevious, "Chapitre précédent", tint = Color.White)
                    }
                    Mode.entries.forEach { m ->
                        FilterChip(model.mode == m, { model.switchMode(m) }, { Text(m.label) })
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { model.next?.let { model.load(it) } }, enabled = model.next != null) {
                        Icon(Icons.Default.SkipNext, "Chapitre suivant", tint = Color.White)
                    }
                }
            }
        }
    }
}

/** Hides the system bars while reading, and keeps the screen on if asked. */
@Composable
internal fun Immersive(hidden: Boolean, keepOn: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    DisposableEffect(hidden) {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun Page(model: Any, headers: Map<String, String>, modifier: Modifier, scale: ContentScale) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    val request = remember(model, attempt) {
        ImageRequest.Builder(context).data(model)
            .apply { if (headers.isNotEmpty()) httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build()) }
            .memoryCacheKey("$model#$attempt")
            .build()
    }
    SubcomposeAsyncImage(
        model = request, contentDescription = null, contentScale = scale, modifier = modifier,
        loading = { Box(Modifier.fillMaxWidth().heightIn(min = 420.dp), Alignment.Center) { CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f)) } },
        error = {
            Box(Modifier.fillMaxWidth().heightIn(min = 300.dp), Alignment.Center) {
                TextButtonWhite("Image non chargée · Réessayer") { attempt++ }
            }
        },
    )
}

@Composable
private fun TextButtonWhite(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(text, color = Color.White) }
}

/**
 * Webtoons: pages one under the other. Pinch to zoom; zoomed in, one finger pans (sideways here,
 * up and down through the list), double tap goes back to the full width. A tap opens the menu.
 */
@Composable
private fun ScrollReader(model: ReaderModel, content: Loaded.Images, onMenu: () -> Unit, onPage: (Int) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = model.startPage.coerceIn(0, content.pages.lastIndex.coerceAtLeast(0)))
    var scale by remember(model.chapter) { mutableFloatStateOf(1f) }
    var offsetX by remember(model.chapter) { mutableFloatStateOf(0f) }

    LaunchedEffect(list, model.chapter) {
        snapshotFlow {
            val info = list.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            // At the end of the list the last page counts as reached.
            if (lastVisible >= content.pages.lastIndex && !list.canScrollForward) content.pages.lastIndex else list.firstVisibleItemIndex
        }.distinctUntilChanged().collect { p ->
            onPage(p)
            model.onPage(p, content.pages.size)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(model.chapter) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        val maxX = (scale - 1f) * size.width / 2f
                        if (fingers >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            val pan = event.calculatePan()
                            offsetX = (offsetX + pan.x).coerceIn(-(scale - 1f) * size.width / 2f, (scale - 1f) * size.width / 2f)
                            list.dispatchRawDelta(-pan.y / scale)
                            event.changes.forEach { it.consume() }
                        } else if (fingers == 1 && scale > 1f) {
                            val pan = event.calculatePan()
                            if (pan.x != 0f || pan.y != 0f) {
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                list.dispatchRawDelta(-pan.y / scale)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(model.chapter) {
                detectTapGestures(
                    onTap = { onMenu() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                        } else {
                            scale = 2f
                        }
                    },
                )
            },
    ) {
        LazyColumn(
            state = list,
            userScrollEnabled = scale == 1f,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
            },
        ) {
            itemsIndexed(content.pages, key = { i, _ -> "${model.chapter.id}-$i" }) { _, p ->
                Page(p, content.headers, Modifier.fillMaxWidth(), ContentScale.FillWidth)
            }
            item(key = "end-${model.chapter.id}") { ChapterEnd(model) }
        }
    }
}

/** Manga: one page per screen, swiped (right to left if asked); taps on the sides turn the page. */
@Composable
private fun PagesReader(model: ReaderModel, content: Loaded.Images, rtl: Boolean, onMenu: () -> Unit, onPage: (Int) -> Unit) {
    val count = content.pages.size + 1 // + the end card
    val pager = rememberPagerState(initialPage = model.startPage.coerceIn(0, count - 1)) { count }
    val scope = rememberCoroutineScope()
    var scale by remember(model.chapter) { mutableFloatStateOf(1f) }
    var offset by remember(model.chapter) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    LaunchedEffect(pager, model.chapter) {
        snapshotFlow { pager.currentPage }.collect { p ->
            scale = 1f
            offset = androidx.compose.ui.geometry.Offset.Zero
            val pageIndex = p.coerceAtMost(content.pages.lastIndex)
            onPage(pageIndex)
            model.onPage(pageIndex, content.pages.size)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(model.chapter) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || (fingers == 1 && scale > 1f)) {
                            if (fingers >= 2) scale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            val pan = event.calculatePan()
                            if (fingers >= 2 || pan != androidx.compose.ui.geometry.Offset.Zero) {
                                val maxX = (scale - 1f) * size.width / 2f
                                val maxY = (scale - 1f) * size.height / 2f
                                offset = androidx.compose.ui.geometry.Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(model.chapter, rtl) {
                detectTapGestures(
                    onTap = { pos ->
                        val third = size.width / 3f
                        val forward = if (rtl) pos.x < third else pos.x > 2 * third
                        val backward = if (rtl) pos.x > 2 * third else pos.x < third
                        when {
                            scale > 1f || (!forward && !backward) -> onMenu()
                            forward -> scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(count - 1)) }
                            else -> scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
                        }
                    },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                        } else {
                            scale = 2f
                        }
                    },
                )
            },
    ) {
        HorizontalPager(
            state = pager,
            reverseLayout = rtl,
            userScrollEnabled = scale == 1f,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { i ->
            if (i == content.pages.size) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { ChapterEnd(model) }
            } else {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    if (i == pager.currentPage) {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                }, Alignment.Center) {
                    Page(content.pages[i], content.headers, Modifier.fillMaxSize(), ContentScale.Fit)
                }
            }
        }
    }
}

@Composable
private fun ChapterEnd(model: ReaderModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Fin · ${model.chapter.name}", color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        val next = model.next
        if (next != null) Button(onClick = { model.load(next) }) { Text("Suivant : ${next.name}") }
        else Text("Dernier chapitre disponible", color = Color.White.copy(alpha = 0.7f))
    }
}

/** Regular books: paragraphs, at the chosen size. */
@Composable
private fun TextReader(model: ReaderModel, content: Loaded.Text, size: Int, onMenu: () -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(list, model.chapter) {
        snapshotFlow { !list.canScrollForward }.distinctUntilChanged().collect { end ->
            model.onPage(if (end) content.paragraphs.lastIndex else list.firstVisibleItemIndex, content.paragraphs.size)
        }
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 48.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { detectTapGestures(onTap = { onMenu() }) },
    ) {
        itemsIndexed(content.paragraphs) { _, p ->
            Text(p, fontSize = size.sp, lineHeight = (size * 1.5f).sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 12.dp))
        }
        item { ChapterEndText(model) }
    }
}

@Composable
private fun ChapterEndText(model: ReaderModel) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val next = model.next
        if (next != null) Button(onClick = { model.load(next) }) { Text("Suivant : ${next.name}") }
    }
}
