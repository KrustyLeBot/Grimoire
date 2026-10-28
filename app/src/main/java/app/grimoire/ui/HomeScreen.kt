package app.grimoire.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.grimoire.Feed
import app.grimoire.MainViewModel
import app.grimoire.Screen
import app.grimoire.Tab as HomeTab
import app.grimoire.data.seriesKey
import app.grimoire.suite.SuiteBanner
import app.grimoire.suite.SuiteTopBar
import app.grimoire.suite.Tone
import app.grimoire.suite.UpdateBanner

@Composable
fun HomeScreen(vm: MainViewModel) {
    val online by vm.app.online.collectAsState()
    val storeError by vm.store.error.collectAsState()
    Scaffold(
        topBar = {
            SuiteTopBar("Grimoire") {
                IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Default.Settings, "Paramètres") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Banners(vm, online, storeError)
            TabRow(selectedTabIndex = vm.tab.ordinal) {
                HomeTab.entries.forEach { t ->
                    Tab(selected = vm.tab == t, onClick = { vm.tab = t }, text = { Text(t.label) })
                }
            }
            when (vm.tab) {
                HomeTab.LIBRARY -> LibraryTab(vm, online)
                HomeTab.EXPLORE -> ExploreTab(vm, online)
                HomeTab.HISTORY -> HistoryTab(vm, online)
            }
        }
    }
}

@Composable
private fun Banners(vm: MainViewModel, online: Boolean, storeError: String?) {
    val context = LocalContext.current
    UpdateBanner(vm.updates)
    storeError?.let { SuiteBanner(it, tone = Tone.Error, title = "Fichier illisible", icon = Icons.Default.ErrorOutline) }
    if (!vm.storageOk) {
        SuiteBanner(
            "Autorise l'accès aux fichiers pour garder ta bibliothèque dans Documents/Grimoire et la synchroniser avec Proton Drive.",
            tone = Tone.Accent, icon = Icons.Default.Folder,
        ) {
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }.onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            }) { Text("Autoriser") }
        }
    }
    if (!online) SuiteBanner("Seuls les chapitres téléchargés sont lisibles.", title = "Hors ligne", icon = Icons.Default.CloudOff)
    if (vm.syncing) SuiteBanner("Tes lectures se synchronisent ; ce que tu lis entre-temps est gardé.", title = "Synchronisation avec Proton Drive", icon = Icons.Default.Sync, progress = -1f)
    vm.notice?.let { SuiteBanner(it) }
}

private enum class LibraryFilter(val label: String) { READING("En cours"), ALL("Tout"), DONE("Terminés") }

@Composable
private fun LibraryTab(vm: MainViewModel, online: Boolean) {
    val data by vm.data.collectAsState()
    val done by vm.app.downloads.done.collectAsState()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.READING) }
    // Reading the series map makes the counts follow refreshes.
    vm.series.size
    val items = data.library
        .map { it to vm.unread(it) }
        .filter { (item, unread) ->
            val started = data.progress[item.key]?.last != null
            when (filter) {
                LibraryFilter.READING -> !started || unread > 0
                LibraryFilter.ALL -> true
                LibraryFilter.DONE -> started && unread == 0
            }
        }
        .sortedByDescending { (item, _) -> data.progress[item.key]?.last?.at ?: item.added }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryFilter.entries.forEach { f ->
                FilterChip(filter == f, { filter = f }, { Text(f.label) }, modifier = Modifier.padding(end = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            if (vm.refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { vm.refreshLibrary() }, enabled = online) { Icon(Icons.Default.Refresh, "Chercher les nouveaux chapitres") }
        }
        if (items.isEmpty()) {
            Empty(
                if (data.library.isEmpty()) "Ta bibliothèque est vide.\nTrouve une série dans Explorer : elle arrive ici dès que tu la lis."
                else "Rien dans « ${filter.label} ».",
            )
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.first.key }) { (item, unread) ->
                val last = data.progress[item.key]?.last
                val offline = done.any { it.startsWith(seriesKey(item.source, item.slug.replace('/', '_')) + ":") }
                SeriesCell(
                    title = item.title, cover = item.cover, source = item.source,
                    badge = unread.takeIf { it > 0 }?.toString(),
                    subtitle = last?.chapterName ?: "Pas commencé",
                    enabled = online || offline,
                    onClick = { vm.open(Screen.Series(item.source, item.slug, item.title, item.cover)) },
                )
            }
        }
    }
}

@Composable
private fun ExploreTab(vm: MainViewModel, online: Boolean) {
    val data by vm.data.collectAsState()
    var query by rememberSaveable { mutableStateOf(vm.globalQuery) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Rechercher dans toutes les sources") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true, enabled = online,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.searchAll(query) }),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        if (vm.globalQuery.isNotBlank() && query == vm.globalQuery) {
            LazyColumn(Modifier.fillMaxSize()) {
                vm.globalResults.forEach { (sourceId, result) ->
                    val name = data.sources.firstOrNull { it.id == sourceId }?.name ?: sourceId
                    item(key = "h-$sourceId") {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    }
                    item(key = "r-$sourceId") {
                        when {
                            result.loading -> Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator() }
                            result.error != null -> Text(result.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                            result.items.isEmpty() -> Text("Aucun résultat", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(result.items, key = { it.slug }) { s ->
                                    Box(Modifier.width(110.dp)) {
                                        SeriesCell(s.title, s.cover, sourceId, onClick = { vm.open(Screen.Series(sourceId, s.slug, s.title, s.cover)) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return
        }
        Text("Sources", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        val enabled = data.sources.filter { it.enabled }
        if (enabled.isEmpty()) Empty("Aucune source active.\nAjoute-en une dans Paramètres › Sources.")
        enabled.forEach { s ->
            ListItem(
                headlineContent = { Text(s.name, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(s.baseUrl.removePrefix("https://")) },
                leadingContent = { Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.padding(horizontal = 4.dp).then(
                    if (online) Modifier.tappable { vm.browse(s.id, Feed.POPULAR, ""); vm.open(Screen.Browse(s.id)) } else Modifier,
                ),
            )
        }
        TextButton(onClick = { vm.open(Screen.Sources) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Gérer les sources") }
    }
}

@Composable
private fun HistoryTab(vm: MainViewModel, online: Boolean) {
    val data by vm.data.collectAsState()
    val done by vm.app.downloads.done.collectAsState()
    val entries = data.progress.mapNotNull { (key, p) -> p.last?.let { key to it } }.sortedByDescending { it.second.at }
    if (entries.isEmpty()) {
        Empty("Les chapitres que tu lis apparaîtront ici.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        var day = ""
        entries.forEach { (key, last) ->
            val label = relativeDay(last.at)
            if (label != day) {
                day = label
                item(key = "d-$key") { Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
            }
            val source = key.substringBefore(':')
            val slug = key.substringAfter(':')
            val item = data.library.firstOrNull { it.key == key }
            val title = item?.title ?: vm.app.cache.load(source, slug)?.title ?: slug
            val cover = item?.cover
            val available = online || "${seriesKey(source, slug.replace('/', '_'))}:${last.chapter}" in done
            item(key = key) {
                ListItem(
                    headlineContent = { Text(title, maxLines = 1) },
                    supportingContent = { Text("${last.chapterName} · page ${last.page + 1}${if (last.pages > 0) " / ${last.pages}" else ""}") },
                    leadingContent = { Cover(cover, source, Modifier.size(48.dp, 64.dp)) },
                    modifier = if (available) Modifier.tappable { vm.open(Screen.Reader(source, slug, last.chapter)) } else Modifier.alpha(0.38f),
                )
            }
        }
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun Modifier.tappable(onClick: () -> Unit): Modifier = clickable(onClick = onClick)
