package app.grimoire.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.grimoire.MainViewModel
import app.grimoire.Screen
import app.grimoire.source.Catalog
import app.grimoire.source.Http
import app.grimoire.suite.ConfirmDialog
import app.grimoire.suite.SectionTitle
import app.grimoire.suite.SettingsCard
import app.grimoire.suite.SettingsHint
import app.grimoire.suite.SuiteTopBar

@Composable
fun SourcesScreen(vm: MainViewModel) {
    val data by vm.data.collectAsState()
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { SuiteTopBar("Sources", onBack = { vm.back() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Ajouter une source") })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (data.sources.isEmpty()) Empty("Aucune source. Ajoute-en une avec le bouton en bas.")
            data.sources.forEach { s ->
                ListItem(
                    headlineContent = { Text(s.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(s.baseUrl.removePrefix("https://") + if (s.enabled) "" else " · désactivée") },
                    leadingContent = { Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Switch(s.enabled, { on -> vm.saveSource(s.copy(enabled = on)) }) },
                    modifier = Modifier.tappable { vm.clearTest(); vm.open(Screen.SourceEdit(s.id)) },
                )
            }
            Column(Modifier.padding(16.dp)) {
                SettingsHint(
                    "Les sources sont intégrées à l'application ; une nouvelle sorte de source arrive avec une mise à jour. " +
                        "Leur réglage est enregistré dans Documents/Grimoire et suit sur tes appareils.",
                )
            }
            Spacer(Modifier.height(80.dp))
        }
    }
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Ajouter une source") },
            text = {
                Column {
                    Catalog.types.forEach { t ->
                        ListItem(
                            headlineContent = { Text(t.name, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("${t.lang.uppercase()} · ${t.description}") },
                            modifier = Modifier.tappable {
                                adding = false
                                val id = vm.addSource(t.id)
                                vm.clearTest()
                                vm.open(Screen.SourceEdit(id))
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Annuler") } },
        )
    }
}

@Composable
fun SourceEditScreen(vm: MainViewModel, id: String) {
    val data by vm.data.collectAsState()
    val config = data.sources.firstOrNull { it.id == id } ?: return
    val type = Catalog.type(config.type)
    var name by remember(id) { mutableStateOf(config.name) }
    var url by remember(id) { mutableStateOf(config.baseUrl) }
    var confirmRemove by remember { mutableStateOf(false) }
    val dirty = name != config.name || url.trim().trimEnd('/') != config.baseUrl
    Scaffold(topBar = { SuiteTopBar(config.name, onBack = { vm.back() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle(type?.name ?: config.type)
            SettingsCard {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Adresse du site") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SettingsHint("Ces sites changent parfois d'adresse : mets la nouvelle ici. Par défaut : ${type?.defaultBaseUrl ?: "—"}")
                Row {
                    Button(
                        onClick = { vm.saveSource(config.copy(name = name.trim().ifBlank { config.name }, baseUrl = url.trim().trimEnd('/'))) },
                        enabled = dirty && url.startsWith("http"),
                    ) { Text("Enregistrer") }
                    Spacer(Modifier.padding(4.dp))
                    OutlinedButton(onClick = { vm.testSource(id) }, enabled = !dirty) { Text("Tester") }
                }
                vm.testResult?.let { SettingsHint(it) }
            }
            if (type != null && type.options.isNotEmpty()) {
                SectionTitle("Options")
                SettingsCard {
                    type.options.forEach { o ->
                        val on = config.options[o.key]?.toBoolean() ?: o.default
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(o.label, fontWeight = FontWeight.SemiBold)
                                SettingsHint(o.hint)
                            }
                            Switch(on, { v -> vm.saveSource(config.copy(options = config.options + (o.key to v.toString()))) })
                        }
                    }
                }
            }
            if (type?.hasLogin == true) {
                SectionTitle("Compte")
                SettingsCard {
                    SettingsHint("Facultatif. Connecte-toi sur le site (ou passe sa vérification anti-robot) : Grimoire réutilise la session.")
                    OutlinedButton(onClick = { vm.open(Screen.Login(id)) }) { Text("Ouvrir le site pour se connecter") }
                }
            }
            SectionTitle("Supprimer")
            SettingsCard {
                SettingsHint("Ta bibliothèque et tes lectures sont gardées ; elles reviennent si tu rajoutes la source.")
                OutlinedButton(onClick = { confirmRemove = true }) { Text("Supprimer la source") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmRemove) {
        ConfirmDialog("Supprimer ${config.name} ?", null, "Supprimer", onConfirm = { vm.removeSource(id) }, onDismiss = { confirmRemove = false }, destructive = true)
    }
}

/** The site in a WebView, with the app's user agent: its cookies (login, anti-bot) are shared with Grimoire. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(vm: MainViewModel, id: String) {
    val config = vm.data.value.sources.firstOrNull { it.id == id } ?: return
    DisposableEffect(Unit) { onDispose { CookieManager.getInstance().flush() } }
    Scaffold(topBar = {
        SuiteTopBar("Connexion · ${config.name}", onBack = { vm.back() }) {
            TextButton(onClick = { vm.back() }) { Text("Terminé") }
        }
    }) { padding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = Http.USER_AGENT
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = WebViewClient()
                    loadUrl(config.baseUrl)
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}
