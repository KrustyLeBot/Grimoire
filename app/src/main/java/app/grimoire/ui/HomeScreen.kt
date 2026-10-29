package app.grimoire.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import app.grimoire.Feed
import app.grimoire.Lister
import app.grimoire.MainViewModel
import app.grimoire.Section
import app.grimoire.Screen
import app.grimoire.book.Books
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
    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importBook) }
    Scaffold(
        topBar = {
            SuiteTopBar("Grimoire") {
                IconButton(onClick = { pickBook.launch(arrayOf("application/epub+zip", "application/octet-stream")) }) {
                    Icon(Icons.Default.Add, "Ajouter un livre (EPUB)")
                }
                IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Default.Settings, "Paramètres") }
            }
        },
        bottomBar = {
            // Lecture | Reddit, once Reddit is set up in the settings.
            if (vm.reddit.config.configured) {
                androidx.compose.material3.NavigationBar {
                    Section.entries.forEach { sec ->
                        androidx.compose.material3.NavigationBarItem(
                            selected = vm.section == sec, onClick = { vm.section = sec },
                            icon = {
                                Icon(
                                    if (sec == Section.READING) Icons.Default.MenuBook else Icons.Default.Forum, null,
                                )
                            },
                            label = { Text(sec.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (vm.section == Section.REDDIT && vm.reddit.config.configured) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Banners(vm, online, storeError)
                RedditSection(vm)
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            Banners(vm, online, storeError)
            TabRow(selectedTabIndex = vm.tab.ordinal) {
                HomeTab.entries.forEach { t ->
                    // One line even on narrow screens or with a large system font.
                    Tab(
                        selected = vm.tab == t, onClick = { vm.tab = t },
                        text = { Text(t.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, letterSpacing = 0.sp)) },
                    )
                }
            }
            when (vm.tab) {
                HomeTab.LIBRARY -> LibraryTab(vm, online)
                HomeTab.DISCOVER -> DiscoverTab(vm, online)
                HomeTab.SEARCH -> SearchTab(vm, online)
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
            "Autorise l'accès aux fichiers pour garder ta bibliothèque dans Documents/Grimoire et la synchroniser avec Synchrotron.",
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
    if (vm.syncing) SuiteBanner("Tes lectures se synchronisent ; ce que tu lis entre-temps est gardé.", title = "Synchronisation", icon = Icons.Default.Sync, progress = -1f)
    if (vm.importing) SuiteBanner("Import du livre…", progress = -1f)
    vm.notice?.let { SuiteBanner(it) }
}

private enum class LibraryFilter(val label: String) { READING("En cours"), ALL("Tout"), DONE("Terminés") }

@Composable
private fun LibraryTab(vm: MainViewModel, online: Boolean) {
    val data by vm.data.collectAsState()
    val done by vm.app.downloads.done.collectAsState()
    val books by vm.app.books.present.collectAsState()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.READING) }
    // Reading the series map makes the counts follow refreshes.
    vm.series.size
    val items = data.library
        .map { it to vm.unread(it) }
        .filter { (item, unread) ->
            // Marking chapters read by hand counts as started, like reading one.
            val started = data.progress[item.key]?.let { it.last != null || it.read.isNotEmpty() } == true
            when (filter) {
                LibraryFilter.READING -> !started || unread > 0
                LibraryFilter.ALL -> true
                LibraryFilter.DONE -> started && unread == 0
            }
        }
        .sortedByDescending { (item, _) -> data.progress[item.key]?.last?.at ?: item.added }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LibraryFilter.entries.forEach { f ->
                    FilterChip(filter == f, { filter = f }, { Text(f.label, maxLines = 1, softWrap = false) })
                }
            }
            if (vm.refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { vm.refreshLibrary() }, enabled = online) { Icon(Icons.Default.Refresh, "Chercher les nouveaux chapitres") }
        }
        if (items.isEmpty()) {
            Empty(
                if (data.library.isEmpty()) "Ta bibliothèque est vide.\nTrouve une série dans Découvrir ou Recherche : elle arrive ici dès que tu la lis.\nPour un livre, ouvre un fichier EPUB avec Grimoire ou touche +."
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
                val progress = data.progress[item.key]
                val last = progress?.last
                if (item.source == Books.SOURCE) {
                    val read = Books.READ in progress?.read.orEmpty()
                    val state = when {
                        read -> "Lu"
                        last != null -> "${Books.percent(last)} %"
                        else -> "Pas commencé"
                    }
                    SeriesCell(
                        title = item.title, cover = item.cover ?: vm.app.books.localCover(item.slug), source = item.source,
                        subtitle = if (item.slug in books) state else "$state · fichier absent",
                        onClick = { vm.open(Screen.Book(item.slug)) },
                    )
                } else {
                    val offline = done.any { it.startsWith(seriesKey(item.source, item.slug.replace('/', '_')) + ":") }
                    SeriesCell(
                        title = item.title, cover = item.cover, source = item.source,
                        badge = unread.takeIf { it > 0 }?.toString(),
                        subtitle = last?.chapterName ?: if (unread == 0 && progress?.read.orEmpty().isNotEmpty()) "Tout lu" else "Pas commencé",
                        enabled = online || offline,
                        onClick = { vm.open(Screen.Series(item.source, item.slug, item.title, item.cover)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverTab(vm: MainViewModel, online: Boolean) {
    val data by vm.data.collectAsState()
    val enabled = data.sources.filter { it.enabled }
    LaunchedEffect(online, enabled.map { it.id }) {
        val l = vm.discover.listing
        if (online && enabled.isNotEmpty() && (l.items.isEmpty() || vm.discover.sources.isEmpty()) && !l.loading) vm.discover()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Feed.entries.forEach { f -> FilterChip(vm.feed == f, { vm.discover(feed = f) }, { Text(f.label) }, enabled = online) }
        }
        SourceChips(vm, vm.discoverSource, online) { vm.discover(source = it) }
        when {
            enabled.isEmpty() -> NoSource(vm)
            !online -> Empty("Hors ligne : Découvrir a besoin d'internet.\nTa bibliothèque montre ce qui est téléchargé.")
            else -> ListingGrid(vm, vm.discover, showSource = vm.discoverSource == null && enabled.size > 1, emptyText = "Rien pour l'instant.")
        }
    }
}

@Composable
private fun SearchTab(vm: MainViewModel, online: Boolean) {
    val data by vm.data.collectAsState()
    val enabled = data.sources.filter { it.enabled }
    var text by rememberSaveable { mutableStateOf(vm.query) }
    val scopeName = vm.searchSource?.let { id -> enabled.firstOrNull { it.id == id }?.name }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            text, { text = it },
            placeholder = { Text(if (scopeName != null) "Rechercher dans $scopeName" else "Rechercher dans toutes les sources") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (text.isNotEmpty()) IconButton(onClick = { text = ""; vm.search("") }) { Icon(Icons.Default.Close, "Effacer") }
            },
            singleLine = true, enabled = online,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(text) }),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        )
        SourceChips(vm, vm.searchSource, online) { vm.search(text, it) }
        // Configured websites: the same search, opened inside Grimoire (books found there land in the library).
        if (data.webSearches.isNotEmpty() && text.isNotBlank()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(data.webSearches.size) { i ->
                    val w = data.webSearches[i]
                    AssistChip(
                        onClick = { vm.openWeb(w, text) }, enabled = online,
                        label = { Text(w.name, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.Public, null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
        when {
            enabled.isEmpty() -> NoSource(vm)
            !online -> Empty("Hors ligne : la recherche a besoin d'internet.")
            vm.query.isBlank() -> Empty("Tape un titre puis lance la recherche.")
            else -> ListingGrid(vm, vm.search, showSource = vm.searchSource == null && enabled.size > 1, emptyText = "Aucun résultat pour « ${vm.query} ».")
        }
    }
}

/** "Toutes" then one chip per enabled source; hidden with a single source. */
@Composable
private fun SourceChips(vm: MainViewModel, selected: String?, online: Boolean, onPick: (String?) -> Unit) {
    val data by vm.data.collectAsState()
    val enabled = data.sources.filter { it.enabled }
    if (enabled.size < 2) return
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected == null, { onPick(null) }, { Text("Toutes les sources") }, enabled = online) }
        items(enabled, key = { it.id }) { s -> FilterChip(selected == s.id, { onPick(s.id) }, { Text(s.name) }, enabled = online) }
    }
}

@Composable
private fun NoSource(vm: MainViewModel) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Empty("Aucune source active.")
        TextButton(onClick = { vm.open(Screen.Sources) }) { Text("Gérer les sources") }
    }
}

/** Infinite grid of a [Lister]; a failing source shows its error under the rest, with a retry. */
@Composable
private fun ListingGrid(vm: MainViewModel, lister: Lister, showSource: Boolean, emptyText: String) {
    val data by vm.data.collectAsState()
    val listing = lister.listing
    val grid = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 6 } ?: false }
    }
    LaunchedEffect(nearEnd, listing.items.size) { if (nearEnd) lister.more() }
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(listing.items, key = { it.key }) { f ->
            SeriesCell(
                f.series.title, f.series.cover, f.source,
                subtitle = if (showSource) data.sources.firstOrNull { it.id == f.source }?.name else null,
                onClick = { vm.open(Screen.Series(f.source, f.series.slug, f.series.title, f.series.cover)) },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                listing.errors.forEach { (id, e) ->
                    val name = data.sources.firstOrNull { it.id == id }?.name ?: id
                    Text("$name : $e", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                when {
                    listing.loading -> CircularProgressIndicator()
                    listing.errors.isNotEmpty() -> TextButton(onClick = { lister.retry() }) { Text("Réessayer") }
                    listing.items.isEmpty() && !lister.hasNext -> Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
