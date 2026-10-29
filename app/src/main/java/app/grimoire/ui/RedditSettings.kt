package app.grimoire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.grimoire.MainViewModel
import app.grimoire.reddit.RedditConfig
import app.grimoire.suite.SectionTitle
import app.grimoire.suite.SettingsCard
import app.grimoire.suite.SettingsHint
import kotlinx.coroutines.launch

/** The Reddit section of the settings: how to reach the proxy, and the subreddits to follow. */
@Composable
fun RedditSettings(vm: MainViewModel) {
    val r = vm.reddit
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(r.config.url) }
    var lanUrl by remember { mutableStateOf(r.config.lanUrl) }
    var user by remember { mutableStateOf(r.config.user) }
    var password by remember { mutableStateOf(r.config.password) }
    var showPassword by remember { mutableStateOf(false) }
    // The Cloudflare Access service token, sent as its two headers.
    var cfId by remember { mutableStateOf(r.config.headers.firstOrNull { it.first.equals(CF_ID, true) }?.second.orEmpty()) }
    var cfSecret by remember { mutableStateOf(r.config.headers.firstOrNull { it.first.equals(CF_SECRET, true) }?.second.orEmpty()) }
    var subs by remember { mutableStateOf(r.config.subs.joinToString(", ")) }
    var showSecret by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var cacheBytes by remember { mutableStateOf(vm.app.redditClient.cacheBytes()) }

    fun draft() = RedditConfig(url.trim(), lanUrl.trim(), user.trim(), password.trim(), cloudflareHeaders(cfId, cfSecret), RedditConfig.parseSubs(subs))

    SectionTitle("Reddit")
    SettingsCard {
        SettingsHint(
            "Les fils passent par ton proxy Reddit (KrustyLeBot/RedditProxy) : il les garde en cache et les tient à jour. " +
                "Ces réglages restent sur ce téléphone, chiffrés.",
        )
        OutlinedTextField(
            url, { url = it }, label = { Text("Adresse du proxy") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            supportingText = { Text("Remplace l'adresse d'exemple par la tienne") },
        )
        OutlinedTextField(
            lanUrl, { lanUrl = it }, label = { Text("Adresse locale (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            supportingText = { Text("ex. http://192.168.1.111:8095 : utilisée quand elle répond (à la maison)") },
        )
        OutlinedTextField(
            user, { user = it }, label = { Text("Utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        OutlinedTextField(
            password, { password = it }, label = { Text("Mot de passe") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPassword) "Masquer" else "Afficher")
                }
            },
        )
        OutlinedTextField(
            cfId, { cfId = it }, label = { Text("Cloudflare Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        OutlinedTextField(
            cfSecret, { cfSecret = it }, label = { Text("Cloudflare Client Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { showSecret = !showSecret }) {
                    Icon(if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showSecret) "Masquer" else "Afficher")
                }
            },
            supportingText = { Text("Le service token de l'application Access du proxy. Vides sans Cloudflare.") },
        )
        OutlinedTextField(
            subs, { subs = it }, label = { Text("Subreddits") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            supportingText = { Text("Séparés par des virgules, ex. france, manga, webtoons") },
        )
        message?.let { (ok, text) ->
            Text(text, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                busy = true; message = null
                scope.launch {
                    val err = vm.app.redditClient.test(draft())
                    busy = false
                    message = if (err == null) true to "Le proxy répond et accepte l'identifiant" else false to err
                }
            }) { Text("Tester") }
            Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                busy = true; message = null
                scope.launch {
                    val err = r.save(draft())
                    busy = false
                    message = if (err == null) true to "Enregistré : l'onglet Reddit est en bas de l'accueil" else false to err
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                Text("Enregistrer")
            }
        }
        Text("Copies hors ligne : ${cacheBytes / 1_000_000} Mo", fontWeight = FontWeight.SemiBold)
        SettingsHint("Les fils et discussions déjà ouverts restent lisibles sans réseau.")
        OutlinedButton(onClick = { vm.app.redditClient.clearCache(); cacheBytes = 0 }, enabled = cacheBytes > 0) { Text("Vider") }
    }
}

private const val CF_ID = "CF-Access-Client-Id"
private const val CF_SECRET = "CF-Access-Client-Secret"

/** The service token as the two headers Cloudflare Access reads; none for an empty field. */
private fun cloudflareHeaders(id: String, secret: String): List<Pair<String, String>> =
    listOfNotNull(id.trim().takeIf { it.isNotEmpty() }?.let { CF_ID to it }, secret.trim().takeIf { it.isNotEmpty() }?.let { CF_SECRET to it })
