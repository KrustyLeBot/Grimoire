package app.grimoire.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import app.grimoire.data.WebSearch
import app.grimoire.MainViewModel
import app.grimoire.Screen
import app.grimoire.suite.AboutSettings
import app.grimoire.suite.ConfirmDialog
import app.grimoire.suite.SectionTitle
import app.grimoire.suite.SettingsCard
import app.grimoire.suite.SettingsHint
import app.grimoire.suite.SuiteTopBar
import app.grimoire.suite.SyncSettings
import app.grimoire.suite.UpdateSettings

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings = vm.app.settings
    var rtl by remember { mutableStateOf(settings.rightToLeft) }
    var keepOn by remember { mutableStateOf(settings.keepScreenOn) }
    var textSize by remember { mutableFloatStateOf(settings.textSize.toFloat()) }
    var wifiOnly by remember { mutableStateOf(settings.wifiOnlyDownloads) }
    var size by remember { mutableLongStateOf(vm.app.downloads.sizeBytes()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val data by vm.data.collectAsState()
    var bookBytes by remember { mutableLongStateOf(vm.app.books.sizeBytes()) }
    var confirmBooks by remember { mutableStateOf(false) }
    var googleKey by remember { mutableStateOf(settings.googleBooksKey.orEmpty()) }
    var editing by remember { mutableStateOf<Pair<Int?, WebSearch>?>(null) }

    Scaffold(topBar = { SuiteTopBar("Paramètres", onBack = { vm.back() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Sources")
            SettingsCard {
                Text("${vm.data.value.sources.size} source(s)", fontWeight = FontWeight.SemiBold)
                SettingsHint("Ajouter, régler l'adresse d'un site, se connecter, activer les chapitres premium…")
                OutlinedButton(onClick = { vm.open(Screen.Sources) }) { Text("Gérer les sources") }
            }

            SectionTitle("Historique")
            SettingsCard {
                Text("${vm.data.value.progress.values.count { it.last != null }} série(s) commencée(s)", fontWeight = FontWeight.SemiBold)
                SettingsHint("Les derniers chapitres lus, par jour : reprends l'un d'eux où tu l'avais laissé.")
                OutlinedButton(onClick = { vm.open(Screen.History) }) { Text("Voir l'historique") }
            }

            SectionTitle("Lecture")
            SettingsCard {
                Toggle("Mode Pages de droite à gauche", "Pour les mangas japonais", rtl) { rtl = it; settings.rightToLeft = it }
                Toggle("Garder l'écran allumé", "Pendant la lecture", keepOn) { keepOn = it; settings.keepScreenOn = it }
                Text("Taille du texte (livres) : ${textSize.toInt()}", fontWeight = FontWeight.SemiBold)
                Slider(textSize, { textSize = it }, valueRange = 14f..28f, steps = 13, onValueChangeFinished = { settings.textSize = textSize.toInt() })
                SettingsHint("Le mode (Défilement ou Pages) se choisit dans le lecteur et est retenu pour chaque série.")
            }

            SectionTitle("Téléchargements")
            SettingsCard {
                Toggle("Wi-Fi uniquement", "Les téléchargements attendent le Wi-Fi", wifiOnly) { wifiOnly = it; settings.wifiOnlyDownloads = it }
                Text("Espace utilisé : ${size / 1_000_000} Mo", fontWeight = FontWeight.SemiBold)
                SettingsHint("Gardés sur le téléphone seulement : ils ne passent pas par Proton Drive.")
                OutlinedButton(onClick = { confirmDelete = true }, enabled = size > 0) { Text("Tout supprimer") }
            }

            SectionTitle("Livres")
            SettingsCard {
                Text("Fichiers EPUB : ${bookBytes / 1_000_000} Mo", fontWeight = FontWeight.SemiBold)
                SettingsHint("Gardés sur le téléphone seulement. Ta lecture est synchronisée, et un livre se retélécharge depuis sa page.")
                OutlinedButton(onClick = { confirmBooks = true }, enabled = bookBytes > 0) { Text("Supprimer les fichiers") }
                OutlinedTextField(
                    googleKey, { googleKey = it; settings.googleBooksKey = it },
                    label = { Text("Clé API Google Books (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                SettingsHint("Les infos des livres viennent d'Open Library et de la BnF. Google Books ajoute les résumés, mais demande une clé gratuite (console.cloud.google.com › API Books).")
            }

            SectionTitle("Recherche web")
            SettingsCard {
                SettingsHint("Des sites où chercher un livre depuis Recherche ou depuis la page d'un livre. Ils s'ouvrent dans Grimoire : un EPUB téléchargé arrive directement dans ta bibliothèque, avec son lien.")
                data.webSearches.forEachIndexed { i, w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).tappable { editing = i to w }) {
                            Text(w.name, fontWeight = FontWeight.SemiBold)
                            SettingsHint(w.url)
                        }
                        IconButton(onClick = { vm.removeWebSearch(i) }) { Icon(Icons.Default.DeleteOutline, "Supprimer") }
                    }
                }
                OutlinedButton(onClick = { editing = null to WebSearch("", "https://") }) { Text("Ajouter un site") }
            }

            SyncSettings(vm.store.syncPath)
            UpdateSettings(vm.updates)
            AboutSettings(
                "Grimoire",
                "Lecture de mangas et webtoons depuis des sources, comme Mihon. Bibliothèque et lectures synchronisées, téléchargements hors ligne.",
            )
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmBooks) {
        ConfirmDialog(
            "Supprimer les fichiers des livres ?", "${bookBytes / 1_000_000} Mo libérés. Les livres restent dans ta bibliothèque avec ta lecture.", "Supprimer",
            onConfirm = { vm.app.books.deleteAll(); bookBytes = 0 }, onDismiss = { confirmBooks = false }, destructive = true,
        )
    }
    editing?.let { (index, search) ->
        var name by remember(index) { mutableStateOf(search.name) }
        var url by remember(index) { mutableStateOf(search.url) }
        val valid = name.isNotBlank() && url.startsWith("http") && WebSearch.PLACEHOLDER in url
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (index == null) "Ajouter un site" else "Modifier le site") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it }, label = { Text("Adresse de recherche") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SettingsHint("Écris {q} là où va la recherche (titre, auteur ou ISBN), par exemple https://exemple.com/s/{q}")
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveWebSearch(index, WebSearch(name.trim(), url.trim())); editing = null }, enabled = valid) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Supprimer tous les téléchargements ?", "${size / 1_000_000} Mo libérés. Tes lectures sont gardées.", "Tout supprimer",
            onConfirm = { vm.app.downloads.deleteAll(); size = 0 }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}

@Composable
private fun Toggle(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            SettingsHint(hint)
        }
        Switch(value, onChange)
    }
}
