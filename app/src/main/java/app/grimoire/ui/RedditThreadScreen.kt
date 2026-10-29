package app.grimoire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.grimoire.MainViewModel
import app.grimoire.Screen
import app.grimoire.reddit.CommentSort
import app.grimoire.reddit.MediaKind
import app.grimoire.reddit.RedditComment
import app.grimoire.reddit.RedditPost
import app.grimoire.reddit.ago
import app.grimoire.reddit.compactCount
import app.grimoire.suite.SuiteBanner
import app.grimoire.suite.SuiteTopBar

/** Colours of the indentation lines, one per depth, like Reddit's. */
private val depthColors = listOf(
    Color(0xFF7B2D8E), Color(0xFF2563EB), Color(0xFF1AAE9F), Color(0xFFF9A825), Color(0xFFE5484D), Color(0xFF6D4AFF),
)

@Composable
fun RedditThreadScreen(vm: MainViewModel, s: Screen.RedditThread) {
    val r = vm.reddit
    var sort by rememberSaveable(s.id, s.comment) { mutableStateOf(CommentSort.BEST) }
    val key = r.threadKey(s.id, s.comment, sort)
    LaunchedEffect(key) { r.loadThread(s.id, s.comment, s.sub, sort) }
    val view = r.threads[key]
    val uri = LocalUriHandler.current
    var sortMenu by remember { mutableStateOf(false) }
    // Collapsed comments, by id: Reddit's own collapsed ones start folded.
    var collapsed by remember(key) { mutableStateOf(setOf<String>()) }
    var seeded by remember(key) { mutableStateOf(false) }
    val thread = view?.thread
    if (thread != null && !seeded) {
        collapsed = thread.comments.filter { !it.more && it.collapsed }.map { it.id }.toSet()
        seeded = true
    }

    Scaffold(
        topBar = {
            SuiteTopBar("r/${s.sub}", onBack = { vm.back() }) {
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Trier les commentaires") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        CommentSort.entries.forEach { c ->
                            DropdownMenuItem({ Text(if (c == sort) "✓ ${c.label}" else c.label) }, { sort = c; sortMenu = false })
                        }
                    }
                }
                IconButton(onClick = { r.loadThread(s.id, s.comment, s.sub, sort, force = true) }) { Icon(Icons.Default.Refresh, "Actualiser") }
                thread?.post?.permalink?.let { p ->
                    IconButton(onClick = { uri.openUri("https://www.reddit.com$p") }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Ouvrir sur Reddit") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                thread == null && view?.error != null -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Empty(view?.error.orEmpty())
                    TextButton(onClick = { r.loadThread(s.id, s.comment, s.sub, sort, force = true) }) { Text("Réessayer") }
                }
                thread == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    if (thread.stale) SuiteBanner("Copie gardée sur le téléphone : le proxy n'a pas pu joindre Reddit.", title = "Hors ligne")
                    val visible = visibleComments(thread.comments, collapsed)
                    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                        item(key = "post") { PostHeader(thread.post, s.comment != null, onOpenUrl = { uri.openUri(it) }, onWhole = {
                            vm.open(Screen.RedditThread(s.id, null, s.sub))
                        }) }
                        if (thread.comments.isEmpty()) item { Empty("Aucun commentaire") }
                        items(visible, key = { (c, _) -> (if (c.more) "more:" else "c:") + c.id + c.parent }) { (c, hidden) ->
                            if (c.more) MoreRow(c, loading = c.id in view?.expanding.orEmpty()) {
                                if (c.continueAt != null) vm.open(Screen.RedditThread(s.id, c.continueAt, s.sub))
                                else r.expand(key, s.id, c, sort)
                            } else CommentRow(c, folded = c.id in collapsed, hidden = hidden) {
                                collapsed = if (c.id in collapsed) collapsed - c.id else collapsed + c.id
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The comments to show, each with how many replies its folding hides: everything after a folded
 * comment that is deeper than it is skipped.
 */
private fun visibleComments(all: List<RedditComment>, collapsed: Set<String>): List<Pair<RedditComment, Int>> {
    val out = ArrayList<Pair<RedditComment, Int>>()
    var i = 0
    while (i < all.size) {
        val c = all[i]
        var j = i + 1
        if (!c.more && c.id in collapsed) {
            while (j < all.size && all[j].depth > c.depth) j++
        }
        out += c to (j - i - 1)
        i = j
    }
    return out
}

@Composable
private fun PostHeader(post: RedditPost, partial: Boolean, onOpenUrl: (String) -> Unit, onWhole: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PostMeta(post, showSub = true)
        Flair(post.flair, post.flairColor)
        Text(post.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        PostMedia(post)
        post.html?.takeIf { it.isNotBlank() }?.let { HtmlText(it) }
        if (post.kind == MediaKind.LINK && post.url != null) {
            OutlinedCard(onClick = { onOpenUrl(post.url) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(post.domain ?: "Lien", fontWeight = FontWeight.SemiBold)
                        Text(post.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        PostStats(post)
        if (partial) TextButton(onClick = onWhole) { Text("Voir toute la discussion") }
    }
    HorizontalDivider()
}

/** The indentation: one thin coloured line per level. */
@Composable
private fun Indent(depth: Int) {
    Row(Modifier.fillMaxHeight()) {
        repeat(depth.coerceAtMost(8)) { d ->
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(2.dp).fillMaxHeight().background(depthColors[d % depthColors.size].copy(alpha = 0.55f)))
        }
    }
}

@Composable
private fun CommentRow(c: RedditComment, folded: Boolean, hidden: Int, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onToggle).padding(end = 12.dp)) {
        Indent(c.depth)
        Column(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (c.deleted && (c.author == "[deleted]" || c.author.isBlank())) "[supprimé]" else "u/${c.author}",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = when {
                        c.mod -> Color(0xFF2E9E5B)
                        c.op -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (c.op) Badge("OP", MaterialTheme.colorScheme.primary)
                if (c.mod) Badge("MOD", Color(0xFF2E9E5B))
                c.flair?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                Text(
                    listOfNotNull(if (c.scoreHidden) "–" else "▲ ${compactCount(c.score)}", ago(c.created), "modifié".takeIf { c.edited }).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            when {
                folded -> Text(
                    if (hidden > 0) "Replié · $hidden réponse${if (hidden > 1) "s" else ""} masquée${if (hidden > 1) "s" else ""}" else "Replié",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                c.deleted -> Text("[supprimé]", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                c.html.isNotBlank() -> HtmlText(c.html)
                else -> Text(c.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color, contentColor = Color.White, shape = MaterialTheme.shapes.extraSmall) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun MoreRow(c: RedditComment, loading: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(enabled = !loading, onClick = onClick)) {
        Indent(c.depth)
        Box(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(
                if (c.continueAt != null) "Continuer la discussion →"
                else "Voir ${if (c.count > 0) c.count else c.children.size} autre${if (c.count > 1) "s" else ""} réponse${if (c.count > 1) "s" else ""}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
