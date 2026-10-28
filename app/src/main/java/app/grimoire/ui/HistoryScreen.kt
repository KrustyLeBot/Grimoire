package app.grimoire.ui

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import app.grimoire.MainViewModel
import app.grimoire.Screen
import app.grimoire.data.seriesKey
import app.grimoire.suite.SuiteTopBar

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val online by vm.app.online.collectAsState()
    Scaffold(topBar = { SuiteTopBar("Historique", onBack = { vm.back() }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) { History(vm, online) }
    }
}

@Composable
private fun History(vm: MainViewModel, online: Boolean) {
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

