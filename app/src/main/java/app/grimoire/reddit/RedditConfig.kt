package app.grimoire.reddit

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * How to reach the Reddit proxy, and which subreddits to follow. Device-only: the headers carry the
 * proxy key and the Cloudflare service token, so the whole config is encrypted with an AES-GCM key
 * that lives in the Android Keystore, and is never written to the synced folder.
 */
data class RedditConfig(
    val url: String = EXAMPLE_URL,
    /** Optional http://IP:port used instead of [url] whenever it answers (at home, without Cloudflare). */
    val lanUrl: String = "",
    /** Sent with every request, e.g. Authorization: Bearer <key>, CF-Access-Client-Id, CF-Access-Client-Secret. */
    val headers: List<Pair<String, String>> = emptyList(),
    val subs: List<String> = emptyList(),
) {
    /** Still the example address: Reddit stays hidden until the real one is set. */
    val configured: Boolean get() = subs.isNotEmpty() && url.isNotBlank() && !url.contains("example.com")

    companion object {
        const val EXAMPLE_URL = "https://reddit.example.com/"
        private val subName = Regex("^[A-Za-z0-9_]{2,21}$")

        /** "Nom: valeur" lines into headers; lines without a name or a colon are dropped. */
        fun parseHeaders(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val name = line.substring(0, i).trim()
            val value = line.substring(i + 1).trim()
            if (name.isEmpty() || value.isEmpty() || name.any { it.isWhitespace() }) null else name to value
        }

        fun formatHeaders(headers: List<Pair<String, String>>) = headers.joinToString("\n") { "${it.first}: ${it.second}" }

        /** "france, manga r/Python" into [france, manga, Python]: commas, spaces or lines, r/ optional. */
        fun parseSubs(text: String): List<String> = text.split(',', ' ', '\n', ';')
            .map { it.trim().removePrefix("/").removePrefix("r/").removePrefix("/") }
            .filter { subName.matches(it) }
            .distinctBy { it.lowercase() }
    }
}

class RedditConfigStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "reddit.bin")

    @Volatile private var cached: RedditConfig? = null

    @Synchronized
    fun load(): RedditConfig = cached ?: (decrypt() ?: RedditConfig()).also { cached = it }

    @Synchronized
    fun save(config: RedditConfig) {
        val json = JSONObject()
            .put("url", config.url.trim())
            .put("lanUrl", config.lanUrl.trim())
            .put("headers", JSONArray().apply { config.headers.forEach { put(JSONArray().put(it.first).put(it.second)) } })
            .put("subs", JSONArray(config.subs))
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(json.toString().toByteArray())
        val tmp = File(file.parentFile, "reddit.tmp")
        tmp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ct)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("Réglages Reddit non enregistrés")
        }
        cached = config
    }

    private fun decrypt(): RedditConfig? {
        if (!file.exists()) return null
        return try {
            val raw = file.readBytes()
            val ivLen = raw[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(1, 1 + ivLen)))
            }
            val o = JSONObject(String(cipher.doFinal(raw, 1 + ivLen, raw.size - 1 - ivLen)))
            val h = o.optJSONArray("headers") ?: JSONArray()
            val s = o.optJSONArray("subs") ?: JSONArray()
            RedditConfig(
                url = o.optString("url", RedditConfig.EXAMPLE_URL),
                lanUrl = o.optString("lanUrl"),
                headers = (0 until h.length()).map { h.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } },
                subs = (0 until s.length()).map { s.getString(it) },
            )
        } catch (e: Exception) {
            null // Keystore key gone (restored phone): the settings have to be entered again
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "grimoire_reddit"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
