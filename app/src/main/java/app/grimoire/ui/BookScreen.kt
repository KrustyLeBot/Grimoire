package app.grimoire.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.grimoire.MainViewModel
import app.grimoire.Screen
import app.grimoire.book.Books
import app.grimoire.data.seriesKey
import app.grimoire.suite.ConfirmDialog
import app.grimoire.suite.SettingsCard
import app.grimoire.suite.SettingsHint
import app.grimoire.suite.SuiteBanner
import app.grimoire.suite.SuiteTopBar
import app.grimoire.suite.Tone
import java.util.Locale

/** A book: what the internet says of it (found by ISBN), how far it is read, and its file on this phone. */
@Composable
fun BookScreen(vm: MainViewModel, id: String) {
    val context = LocalContext.current
    val data by vm.data.collectAsState()
    val present by vm.app.books.present.collectAsState()
    val key = seriesKey(Books.SOURCE, id)
    val item = data.library.firstOrNull { it.key == key }
    val progress = data.progress[key]
    val info = vm.bookInfo[id]
    val hasFile = id in present
    val read = Books.READ in progress?.read.orEmpty()
    val isbns = (listOfNotNull(Books.isbnOf(id)) + item?.isbns.orEmpty() + info?.isbns.orEmpty()).distinct()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importBook) }
    LaunchedEffect(id) { vm.describeBook(id) }

    Scaffold(topBar = {
        SuiteTopBar(info?.title ?: item?.title ?: "Livre", onBack = { vm.back() }) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus") }
            DropdownMenu(menu, { menu = false }) {
                if (read) DropdownMenuItem({ Text("Marquer comme non lu") }, { menu = false; vm.markUnread(Books.SOURCE, id, listOf(Books.READ)) }, leadingIcon = { Icon(Icons.Default.RemoveDone, null) })
                else DropdownMenuItem({ Text("Marquer comme lu") }, { menu = false; vm.markRead(Books.SOURCE, id, listOf(Books.READ)) }, leadingIcon = { Icon(Icons.Default.Done, null) })
                if (hasFile) DropdownMenuItem({ Text("Supprimer le fichier du téléphone") }, { menu = false; confirm = "file" }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) })
                DropdownMenuItem({ Text("Retirer de la bibliothèque") }, { menu = false; confirm = "remove" }, leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) })
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(16.dp)) {
                Cover(info?.cover ?: item?.cover ?: vm.app.books.localCover(id), Books.SOURCE, Modifier.width(110.dp).height(165.dp).clip(MaterialTheme.shapes.medium))
                Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(info?.title ?: item?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    info?.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    info?.authors?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    listOfNotNull(info?.publisher, info?.published?.take(4), info?.pages?.let { "$it pages" }, info?.language?.let(::languageName))
                        .takeIf { it.isNotEmpty() }
                        ?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            val last = progress?.last
            val percent = if (read) 100 else last?.let(Books::percent) ?: 0
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    when {
                        read -> "Lu"
                        last != null -> "$percent % · ${last.chapterName}"
                        else -> "Pas commencé"
                    },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (last != null || read) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }

            if (hasFile) {
                Button(onClick = { vm.open(Screen.BookReader(id)) }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) {
                    Text(if (last != null && !read) "Continuer" else "Lire")
                }
            } else {
                // Books are not synced: on this phone the file has to be fetched again.
                SuiteBanner(
                    "Les livres ne sont pas synchronisés, seule ta lecture l'est. Récupère l'EPUB de cette édition" +
                        (if (isbns.isNotEmpty()) " (ISBN ${isbns.first()})" else "") + " puis ouvre-le avec Grimoire : tu reprendras où tu en étais.",
                    tone = Tone.Accent, title = "Fichier absent sur ce téléphone", icon = Icons.Default.FileOpen,
                ) {
                    OutlinedButton(onClick = { pick.launch(arrayOf("application/epub+zip", "application/octet-stream")) }) { Text("Choisir le fichier") }
                }
                GetAgain(vm, item?.link, item?.page, isbns.firstOrNull() ?: info?.title ?: item?.title.orEmpty())
                Spacer(Modifier.height(8.dp))
            }

            vm.bookLookup[id]?.takeIf { it.length > 1 && info == null }?.let { SuiteBanner(it, tone = Tone.Info) }

            info?.description?.let { d ->
                Text(d, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            if (isbns.isNotEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    SettingsCard {
                        Text(if (isbns.size == 1) "ISBN" else "ISBN de l'édition", fontWeight = FontWeight.SemiBold)
                        isbns.forEach { isbn ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(isbn, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { copy(context, isbn) }) { Icon(Icons.Default.ContentCopy, "Copier l'ISBN") }
                            }
                        }
                        SettingsHint("Une autre langue ou une autre édition a son propre ISBN, et donc sa propre lecture.")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (confirm) {
        "file" -> ConfirmDialog(
            "Supprimer le fichier ?", "Le livre reste dans ta bibliothèque avec ta progression ; il faudra rouvrir l'EPUB pour le lire.",
            "Supprimer", onConfirm = { vm.deleteBookFile(id) }, onDismiss = { confirm = null }, destructive = true,
        )
        "remove" -> ConfirmDialog(
            "Retirer ce livre ?", "Il disparaît de la bibliothèque sur tous tes appareils, et son fichier de ce téléphone.",
            "Retirer", onConfirm = { vm.removeBook(id) }, onDismiss = { confirm = null }, destructive = true,
        )
    }
}

/**
 * Ways to get the file back: its download link, the page it came from (when the link has expired:
 * captcha, rotating links), or the configured web searches with its ISBN.
 */
@Composable
private fun GetAgain(vm: MainViewModel, link: String?, page: String?, query: String) {
    val data by vm.data.collectAsState()
    val online by vm.app.online.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (link != null) {
            Button(onClick = { vm.downloadBook(link, page = page) }, enabled = online && !vm.importing, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.importing) "Téléchargement…" else "Retélécharger")
            }
        }
        if (page != null) {
            OutlinedButton(onClick = { vm.open(Screen.Web(page, "Téléchargement")) }, enabled = online, modifier = Modifier.fillMaxWidth()) {
                Text("Ouvrir la page de téléchargement")
            }
        }
        data.webSearches.forEach { w ->
            OutlinedButton(onClick = { vm.openWeb(w, query) }, enabled = online, modifier = Modifier.fillMaxWidth()) {
                Text("Chercher sur ${w.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ISBN", text))
}

private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.FRENCH).replaceFirstChar { it.titlecase(Locale.FRENCH) }.ifBlank { code }
