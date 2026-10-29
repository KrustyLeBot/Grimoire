package app.grimoire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.grimoire.MainViewModel
import app.grimoire.Screen
import app.grimoire.reddit.FeedSort
import app.grimoire.reddit.MediaKind
import app.grimoire.reddit.RedditPost
import app.grimoire.reddit.TopPeriod
import app.grimoire.reddit.ago
import app.grimoire.reddit.compactCount
import app.grimoire.suite.SuiteBanner
import coil3.compose.AsyncImage

/** Reddit's HTML (already sanitized by Reddit), with working links. */
@Composable
fun HtmlText(html: String, modifier: Modifier = Modifier) {
    val link = MaterialTheme.colorScheme.primary
    val text = remember(html, link) {
        val cleaned = html.replace(Regex("""</?div[^>]*>"""), "").trim()
        AnnotatedString.fromHtml(cleaned, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))
            .let { if (it.text.endsWith("\n")) it.subSequence(0, it.text.trimEnd().length) else it }
    }
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium)
}

/** A Reddit video (HLS, or an animated GIF's MP4), played in place; released when it leaves. */
@Composable
fun VideoPlayer(url: String, aspect: Float, loop: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.5f, 2.5f)).background(Color.Black),
    )
}

private fun aspectOf(w: Int, h: Int) = if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f

/** The media of a post at full width: image, swipeable gallery, or video player. */
@Composable
fun PostMedia(post: RedditPost, modifier: Modifier = Modifier) {
    var revealed by rememberSaveable(post.id) { mutableStateOf(!post.nsfw && !post.spoiler) }
    if (!revealed && post.kind != MediaKind.TEXT && post.kind != MediaKind.LINK) {
        Hidden(post, modifier) { revealed = true }
        return
    }
    when (post.kind) {
        MediaKind.IMAGE -> post.images.firstOrNull()?.let { img ->
            if (img.mp4 != null) VideoPlayer(img.mp4, aspectOf(img.width, img.height), loop = true, modifier = modifier)
            else AsyncImage(
                img.url, null, contentScale = ContentScale.Fit,
                modifier = modifier.fillMaxWidth().aspectRatio(aspectOf(img.width, img.height).coerceIn(0.4f, 3f)).clip(MaterialTheme.shapes.medium),
            )
        }
        MediaKind.GALLERY -> {
            val pager = rememberPagerState { post.images.size }
            Column(modifier) {
                val first = post.images.first()
                HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(aspectOf(first.width, first.height).coerceIn(0.5f, 2f))) { i ->
                    val img = post.images[i]
                    AsyncImage(img.url, img.caption, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                val caption = post.images.getOrNull(pager.currentPage)?.caption
                Text(
                    "${pager.currentPage + 1} / ${post.images.size}" + (caption?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        MediaKind.VIDEO -> post.video?.let { v ->
            val url = v.hls ?: v.mp4
            if (url != null) VideoPlayer(url, aspectOf(v.width, v.height), loop = false, modifier = modifier)
        }
        else -> Unit
    }
}

@Composable
private fun Hidden(post: RedditPost, modifier: Modifier, onReveal: () -> Unit) {
    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onReveal),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            listOfNotNull("NSFW".takeIf { post.nsfw }, "Spoiler".takeIf { post.spoiler }).joinToString(" · ") + " · toucher pour afficher",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun PostMeta(post: RedditPost, showSub: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (post.stickied) Icon(Icons.Default.PushPin, "Épinglé", Modifier.size(14.dp), tint = Color(0xFF2E9E5B))
        if (post.locked) Icon(Icons.Default.Lock, "Verrouillé", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            listOfNotNull("r/${post.sub}".takeIf { showSub }, "u/${post.author}", ago(post.created), "modifié".takeIf { post.edited }).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Flair(text: String?, color: String?) {
    if (text.isNullOrBlank()) return
    val bg = color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.secondaryContainer
    val fg = if (bg.luminance() > 0.5f) Color(0xFF1C1B1F) else Color.White
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun PostStats(post: RedditPost) {
    if (post.partial) return // read from RSS: no score nor comment count to show
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Default.ArrowUpward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(compactCount(post.score), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(" ${(post.ratio * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.Comment, null, Modifier.padding(start = 12.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(compactCount(post.comments), style = MaterialTheme.typography.labelLarge)
    }
}

/** One post in a feed: what it is about at a glance, light on data (thumbnails, no players). */
@Composable
fun PostCard(post: RedditPost, showSub: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PostMeta(post, showSub)
            Flair(post.flair, post.flairColor)
            Row(verticalAlignment = Alignment.Top) {
                Text(post.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (post.kind == MediaKind.LINK && post.thumb != null && !post.nsfw) {
                    AsyncImage(post.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.padding(start = 8.dp).size(72.dp).clip(MaterialTheme.shapes.small))
                }
            }
            when (post.kind) {
                MediaKind.TEXT -> post.text?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                MediaKind.LINK -> post.domain?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(" $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                else -> Preview(post)
            }
            PostStats(post)
        }
    }
}

@Composable
private fun Preview(post: RedditPost) {
    if (post.nsfw || post.spoiler) {
        Hidden(post, Modifier) {}
        return
    }
    val first = post.images.firstOrNull()
    val url = if (post.kind == MediaKind.VIDEO) post.thumb else first?.url ?: post.thumb
    val aspect = when {
        first != null -> aspectOf(first.width, first.height)
        post.video != null -> aspectOf(post.video.width, post.video.height)
        else -> 16f / 9f
    }.coerceIn(0.8f, 2f)
    Box(Modifier.fillMaxWidth().aspectRatio(aspect).heightIn(max = 420.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        when (post.kind) {
            MediaKind.VIDEO -> Icon(Icons.Default.PlayCircle, "Vidéo", Modifier.align(Alignment.Center).size(56.dp), tint = Color.White)
            MediaKind.GALLERY -> Surface(color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White, shape = MaterialTheme.shapes.small, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Collections, null, Modifier.size(14.dp))
                    Text(" ${post.images.size}", style = MaterialTheme.typography.labelMedium)
                }
            }
            else -> Unit
        }
    }
}

/** The Reddit section: the subreddits of the settings, a sort, and the posts. */
@Composable
fun RedditSection(vm: MainViewModel) {
    val r = vm.reddit
    val feed = r.feed
    LaunchedEffect(r.sub, r.sort, r.period) { r.load() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            r.config.subs.forEach { s -> FilterChip(r.sub == s, { r.sub = s }, { Text("r/$s") }) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            FeedSort.entries.forEach { s -> FilterChip(r.sort == s, { r.sort = s }, { Text(s.label) }) }
            if (r.sort == FeedSort.TOP) TopPeriod.entries.forEach { p -> FilterChip(r.period == p, { r.period = p }, { Text(p.label) }) }
            IconButton(onClick = { r.load(refresh = true) }, enabled = !feed.loading) { Icon(Icons.Default.Refresh, "Actualiser") }
        }
        if (feed.stale) SuiteBanner("Copie gardée sur le téléphone : le proxy n'a pas pu joindre Reddit.", title = "Hors ligne")
        val list = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 4 } == true } }
        LaunchedEffect(nearEnd, feed.posts.size) { if (nearEnd && !feed.end) r.loadMore() }
        when {
            feed.posts.isEmpty() && feed.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            feed.posts.isEmpty() && feed.error != null -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Empty(feed.error)
                TextButton(onClick = { r.load(refresh = true) }) { Text("Réessayer") }
            }
            feed.posts.isEmpty() && feed.loaded -> Empty("Rien ici pour l'instant")
            else -> LazyColumn(
                state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(feed.posts, key = { it.id }) { p ->
                    PostCard(p, showSub = r.sub?.contains('+') == true) { vm.open(Screen.RedditThread(p.id, null, p.sub)) }
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                        when {
                            feed.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                            feed.error != null -> TextButton(onClick = { r.loadMore() }) { Text("${feed.error} · Réessayer") }
                            feed.end -> Text("Fin du fil", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
