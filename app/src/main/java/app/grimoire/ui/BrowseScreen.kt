package app.grimoire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.grimoire.BrowseView
import app.grimoire.Feed
import app.grimoire.MainViewModel
import app.grimoire.Screen
import app.grimoire.suite.SuiteTopBar

@Composable
fun BrowseScreen(vm: MainViewModel, source: String) {
    val data by vm.data.collectAsState()
    val name = data.sources.firstOrNull { it.id == source }?.name ?: source
    val view = vm.browse[source] ?: BrowseView()
    var query by rememberSaveable(source) { mutableStateOf(view.query) }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 6 } ?: false } }
    LaunchedEffect(nearEnd, view.items.size) { if (nearEnd) vm.more(source) }

    Scaffold(topBar = { SuiteTopBar(name, onBack = { vm.back() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Feed.entries.forEach { f ->
                    FilterChip(view.feed == f, { if (f != Feed.SEARCH) vm.browse(source, f, "") else vm.browse[source] = view.copy(feed = f, items = emptyList(), page = 0, hasNext = true) }, { Text(f.label) })
                }
            }
            if (view.feed == Feed.SEARCH) {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("Titre…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.browse(source, Feed.SEARCH, query) }),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(view.items, key = { it.slug }) { s ->
                    SeriesCell(s.title, s.cover, source, onClick = { vm.open(Screen.Series(source, s.slug, s.title, s.cover)) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                        when {
                            view.loading -> CircularProgressIndicator()
                            view.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(view.error, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { vm.more(source) }) { Text("Réessayer") }
                            }
                            view.items.isEmpty() && view.page > 0 -> Text("Aucun résultat")
                        }
                    }
                }
            }
        }
    }
}
