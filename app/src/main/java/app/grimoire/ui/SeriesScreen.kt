package app.grimoire.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.grimoire.MainViewModel
import app.grimoire.Screen
import app.grimoire.data.seriesKey
import app.grimoire.download.Downloads
import app.grimoire.source.ChapterInfo
import app.grimoire.suite.ConfirmDialog
import app.grimoire.suite.SuiteBanner
import app.grimoire.suite.SuiteTopBar
import app.grimoire.suite.Tone

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesScreen(vm: MainViewModel, s: Screen.Series) {
    val context = LocalContext.current
    val key = seriesKey(s.source, s.slug)
    LaunchedEffect(key) { vm.loadSeries(s.source, s.slug) }
    val view = vm.series[key]
    val details = view?.details
    val data by vm.data.collectAsState()
    val done by vm.app.downloads.done.collectAsState()
    val running by vm.app.downloads.running.collectAsState()
    val online by vm.app.online.collectAsState()
    val progress = data.progress[key]
    val read = progress?.read.orEmpty()
    var selected by remember(key) { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun downloaded(c: ChapterInfo) = Downloads.key(s.source, s.slug, c.id) in done

    Scaffold(
        topBar = {
            if (selected.isEmpty()) {
                SuiteTopBar(details?.title ?: s.title, onBack = { vm.back() }) {
                    if (details != null) {
                        IconButton(onClick = { vm.toggleLibrary(s.source, details) }) {
                            val inLib = data.library.any { it.key == key }
                            Icon(if (inLib) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, if (inLib) "Retirer de la bibliothèque" else "Ajouter à la bibliothèque")
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Tout marquer comme lu") }, { menu = false; vm.markRead(s.source, s.slug, details.chapters.map { it.id }) }, leadingIcon = { Icon(Icons.Default.DoneAll, null) })
                            DropdownMenuItem({ Text("Tout marquer comme non lu") }, { menu = false; vm.markUnread(s.source, s.slug, details.chapters.map { it.id }) }, leadingIcon = { Icon(Icons.Default.RemoveDone, null) })
                            DropdownMenuItem({ Text("Télécharger les non lus") }, { menu = false; vm.download(s.source, s.slug, details.chapters.filter { it.id !in read }.sortedBy { it.number }) }, enabled = online, leadingIcon = { Icon(Icons.Default.Download, null) })
                            DropdownMenuItem({ Text("Supprimer les téléchargements") }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) })
                            DropdownMenuItem({ Text("Ouvrir sur le site") }, {
                                menu = false
                                vm.app.sources.get(s.source)?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.seriesUrl(s.slug)))) }
                            }, leadingIcon = { Icon(Icons.Default.OpenInBrowser, null) })
                        }
                    }
                }
            } else {
                SelectionBar(
                    count = selected.size,
                    onClose = { selected = emptySet() },
                    onRead = { vm.markRead(s.source, s.slug, selected); selected = emptySet() },
                    onUnread = { vm.markUnread(s.source, s.slug, selected); selected = emptySet() },
                    onDownload = {
                        details?.let { d -> vm.download(s.source, s.slug, d.chapters.filter { it.id in selected }.sortedBy { it.number }) }
                        selected = emptySet()
                    },
                    onReadBelow = {
                        // "Jusqu'ici": every chapter up to the newest one selected.
                        details?.let { d ->
                            val top = d.chapters.filter { it.id in selected }.maxOf { it.number }
                            vm.markRead(s.source, s.slug, d.chapters.filter { it.number <= top }.map { it.id })
                        }
                        selected = emptySet()
                    },
                    online = online,
                )
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Header(vm, s, details)
            }
            view?.error?.let { e -> item { SuiteBanner(e, tone = Tone.Error, title = "Mise à jour impossible") } }
            if (view?.loading == true) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
            if (details != null) {
                val next = vm.nextToRead(s.source, details)
                item {
                    if (next != null) {
                        val resume = progress?.last?.chapter == next.id
                        Button(
                            onClick = { vm.read(s.source, details, next) },
                            enabled = online || downloaded(next),
                            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                        ) { Text(if (resume) "Continuer · ${next.name}" else if (read.isEmpty()) "Lire · ${next.name}" else "Lire la suite · ${next.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                item {
                    Text(
                        "${details.chapters.size} chapitres · ${details.chapters.count { it.id in read }} lus",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
                items(details.chapters, key = { it.id }) { c ->
                    val isRead = c.id in read
                    val isDone = downloaded(c)
                    val pct = running[Downloads.key(s.source, s.slug, c.id)]
                    val available = !c.locked && (online || isDone)
                    val isSelected = c.id in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .combinedClickable(
                                enabled = available || selected.isNotEmpty(),
                                onClick = {
                                    if (selected.isNotEmpty()) selected = if (isSelected) selected - c.id else selected + c.id
                                    else vm.read(s.source, details, c)
                                },
                                onLongClick = { selected = selected + c.id },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .alpha(if (!available) 0.38f else if (isRead) 0.55f else 1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val page = progress?.pages?.get(c.id)
                            Text(
                                listOfNotNull(
                                    c.date.takeIf { it > 0 }?.let { relativeDay(it) },
                                    page?.takeIf { !isRead }?.let { "page ${it + 1}" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            pct != null -> CircularProgressIndicator(progress = { pct }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            isDone -> Icon(Icons.Default.DownloadDone, "Téléchargé", tint = MaterialTheme.colorScheme.primary)
                            !c.locked && online -> IconButton(onClick = { vm.download(s.source, s.slug, listOf(c)) }) { Icon(Icons.Default.Download, "Télécharger") }
                        }
                        if (isRead) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.CheckCircle, "Lu", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            } else if (view?.loading != true) {
                item { Empty(if (online) "Fiche indisponible." else "Hors ligne : cette série n'a pas encore été ouverte.") }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Supprimer les téléchargements ?", "Les chapitres téléchargés de cette série sont effacés du téléphone.", "Supprimer",
            onConfirm = { vm.app.downloads.deleteSeries(s.source, s.slug) }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}

@Composable
private fun Header(vm: MainViewModel, s: Screen.Series, details: app.grimoire.source.SeriesDetails?) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.padding(16.dp)) {
        Cover(details?.cover ?: s.cover, s.source, Modifier.width(110.dp).height(157.dp).clip(MaterialTheme.shapes.medium))
        Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(details?.title ?: s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            listOfNotNull(details?.author, details?.artist?.takeIf { it != details?.author }).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            details?.status?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            val sourceName = vm.data.value.sources.firstOrNull { it.id == s.source }?.name
            if (sourceName != null) Text(sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (details != null) {
        if (details.genres.isNotEmpty()) {
            Text(details.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        details.description?.let { d ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).tappable { expanded = !expanded }) {
                Text(d, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onRead: () -> Unit,
    onUnread: () -> Unit,
    onDownload: () -> Unit,
    onReadBelow: () -> Unit,
    online: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).statusBarsPadding().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Annuler la sélection") }
        Text("$count", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onRead) { Icon(Icons.Default.Done, "Marquer comme lu") }
        IconButton(onClick = onReadBelow) { Icon(Icons.Default.DoneAll, "Marquer lus jusqu'ici") }
        IconButton(onClick = onUnread) { Icon(Icons.Default.RemoveDone, "Marquer comme non lu") }
        IconButton(onClick = onDownload, enabled = online) { Icon(Icons.Default.Download, "Télécharger") }
    }
}
