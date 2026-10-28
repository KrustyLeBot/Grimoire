package app.grimoire

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import app.grimoire.book.BookReaderModel
import app.grimoire.book.BookReaderScreen
import app.grimoire.ui.BookScreen
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import app.grimoire.data.seriesKey
import app.grimoire.reader.ReaderModel
import app.grimoire.reader.ReaderScreen
import app.grimoire.suite.SuiteTopBar
import app.grimoire.ui.Empty
import app.grimoire.ui.GrimoireTheme
import app.grimoire.ui.HistoryScreen
import app.grimoire.ui.HomeScreen
import app.grimoire.ui.LoginScreen
import app.grimoire.ui.SeriesScreen
import app.grimoire.ui.SettingsScreen
import app.grimoire.ui.SourceEditScreen
import app.grimoire.ui.SourcesScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) receive(intent)
        setContent {
            GrimoireTheme {
                BackHandler(enabled = vm.stack.size > 1) { vm.back() }
                when (val s = vm.screen) {
                    Screen.Home -> HomeScreen(vm)
                    is Screen.Series -> SeriesScreen(vm, s)
                    is Screen.Reader -> Reader(s)
                    Screen.Sources -> SourcesScreen(vm)
                    is Screen.SourceEdit -> SourceEditScreen(vm, s.id)
                    is Screen.Login -> LoginScreen(vm, s.source)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.History -> HistoryScreen(vm)
                    is Screen.Book -> BookScreen(vm, s.id)
                    is Screen.BookReader -> {
                        val model = remember(s.id) { BookReaderModel(vm.app, s.id, vm.app.scope) }
                        BookReaderScreen(model, onBack = { vm.back() })
                    }
                }
            }
        }
    }

    @Composable
    private fun Reader(s: Screen.Reader) {
        val key = seriesKey(s.source, s.slug)
        val cached = remember(key) { vm.app.cache.load(s.source, s.slug) }
        val details = vm.series[key]?.details ?: cached
        if (details == null) {
            LaunchedEffect(key) { vm.loadSeries(s.source, s.slug) }
            val error = vm.series[key]?.error
            Scaffold(topBar = { SuiteTopBar("Lecture", onBack = { vm.back() }) }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (error != null) Empty(error) else CircularProgressIndicator()
                }
            }
            return
        }
        if (details.chapters.none { !it.locked }) {
            Scaffold(topBar = { SuiteTopBar(details.title, onBack = { vm.back() }) }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) { Empty("Aucun chapitre lisible") }
            }
            return
        }
        val model = remember(key, s.chapter) { ReaderModel(vm.app, vm.app.scope, s.source, details, s.chapter) }
        ReaderScreen(model, onBack = { vm.back() })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** An EPUB opened or shared from another app (files, browser downloads), or a link to one. */
    private fun receive(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") vm.downloadBook(uri.toString()) else vm.importBook(uri)
            }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val link = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("""https?://\S+""").find(it)?.value }
                when {
                    stream != null -> vm.importBook(stream)
                    link != null -> vm.downloadBook(link)
                    else -> vm.show("Partage un fichier EPUB, ou un lien vers un .epub")
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.refreshStorage()
        vm.onOpen()
    }

    override fun onStop() {
        super.onStop()
        vm.onLeave()
    }
}
