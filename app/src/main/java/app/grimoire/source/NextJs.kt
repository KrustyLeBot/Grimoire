package app.grimoire.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document
import java.util.Base64

/**
 * Reads the data a Next.js site sends along with its pages: the React Flight stream of the App
 * Router (inline `self.__next_f.push(...)` scripts, or a raw `RSC: 1` response) and the Pages
 * Router's `__NEXT_DATA__`. Returns the first JSON object the predicate accepts, with Flight's
 * `$` references resolved.
 *
 * Port of keiyoushi/extensions-source `core/.../keiyoushi/utils/NextJs.kt` (Apache-2.0), kept to
 * the JSON tree (no serializers), which is all the sources here need.
 */
object NextJs {
    private val json = Json { isLenient = true }
    private val NEXT_F = Regex("""self\.__next_f\.push\(\s*(\[.*])\s*\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL)

    /** From an HTML page. */
    fun find(document: Document, predicate: (JsonObject) -> Boolean): JsonObject? {
        val chunks = mutableMapOf<String, String>()
        val models = mutableMapOf<String, JsonElement>()
        val payloads = appRouterPayloads(document, chunks, models).ifEmpty { pagesRouterPayloads(document) }
        return search(payloads, chunks, models, predicate)
    }

    /** From a raw Flight response (a request sent with the `RSC: 1` header). */
    fun find(rsc: String, predicate: (JsonObject) -> Boolean): JsonObject? {
        val chunks = mutableMapOf<String, String>()
        val models = mutableMapOf<String, JsonElement>()
        return search(rscPayloads(rsc, chunks, models), chunks, models, predicate)
    }

    /** First array of objects whose first element the predicate accepts (a list of series, say). */
    fun findList(rsc: String, predicate: (JsonObject) -> Boolean): List<JsonObject>? {
        val chunks = mutableMapOf<String, String>()
        val models = mutableMapOf<String, JsonElement>()
        for (payload in rscPayloads(rsc, chunks, models)) {
            val resolved = resolve(payload, chunks, models)
            firstArray(resolved, predicate)?.let { return it }
        }
        return null
    }

    private fun search(
        payloads: List<JsonElement>,
        chunks: Map<String, String>,
        models: Map<String, JsonElement>,
        predicate: (JsonObject) -> Boolean,
    ): JsonObject? {
        for (payload in payloads) {
            firstObject(resolve(payload, chunks, models), predicate)?.let { return it }
        }
        return null
    }

    private fun firstObject(e: JsonElement, predicate: (JsonObject) -> Boolean): JsonObject? = when (e) {
        is JsonObject -> if (predicate(e)) e else e.values.firstNotNullOfOrNull { firstObject(it, predicate) }
        is JsonArray -> e.firstNotNullOfOrNull { firstObject(it, predicate) }
        else -> null
    }

    private fun firstArray(e: JsonElement, predicate: (JsonObject) -> Boolean): List<JsonObject>? = when (e) {
        is JsonArray -> {
            val first = e.firstOrNull()
            if (first is JsonObject && predicate(first)) e.filterIsInstance<JsonObject>()
            else e.firstNotNullOfOrNull { firstArray(it, predicate) }
        }
        is JsonObject -> e.values.firstNotNullOfOrNull { firstArray(it, predicate) }
        else -> null
    }

    // ---- reference resolution (React Flight markers) ----

    private fun resolve(
        e: JsonElement,
        chunks: Map<String, String>,
        models: Map<String, JsonElement>,
        resolving: Set<String> = emptySet(),
    ): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { resolve(it.value, chunks, models, resolving) })
        is JsonArray -> JsonArray(e.map { resolve(it, chunks, models, resolving) })
        is JsonPrimitive -> {
            val s = e.contentOrNull
            if (e.isString && s != null && s.startsWith("$") && s.length >= 2) {
                when {
                    s == "\$undefined" -> JsonNull
                    s == "\$Infinity" || s == "\$-Infinity" || s == "\$NaN" || s == "\$-0" -> JsonPrimitive(s.substring(1))
                    s[1] == '$' -> JsonPrimitive(s.substring(1))
                    s[1] == 'D' -> JsonPrimitive(s.substring(2)) // date: the ISO-8601 string
                    s[1] == 'n' -> JsonPrimitive(s.substring(2)) // BigInt digits
                    s[1] == 'Q' -> mapRef(s.substring(2), chunks, models, resolving) ?: e
                    s[1] == 'W' -> setRef(s.substring(2), chunks, models, resolving) ?: e
                    s[1] == 'L' || s[1] == '@' -> modelRef(s.substring(2), chunks, models, resolving) ?: e
                    else -> modelRef(s.substring(1), chunks, models, resolving) ?: e
                }
            } else {
                e
            }
        }
    }

    private fun modelRef(reference: String, chunks: Map<String, String>, models: Map<String, JsonElement>, resolving: Set<String>): JsonElement? {
        val segments = reference.split(':')
        val id = segments[0]
        if (segments.size == 1) chunks[id]?.let { return JsonPrimitive(it) }
        if (id in resolving) return null
        val guard = resolving + id
        var value: JsonElement = models[id] ?: return null
        for (i in 1 until segments.size) {
            if (value is JsonPrimitive && value.isString && value.content.startsWith("$")) value = resolve(value, chunks, models, guard)
            value = segment(value, segments[i]) ?: return null
        }
        return resolve(value, chunks, models, guard)
    }

    /** React elements travel as `["$", type, key, props]`: named segments map onto those indices. */
    private fun segment(value: JsonElement, segment: String): JsonElement? = when (value) {
        is JsonObject -> value[segment]
        is JsonArray ->
            if (value.size >= 4 && (value[0] as? JsonPrimitive)?.takeIf { it.isString }?.content == "$") {
                when (segment) {
                    "type" -> value[1]
                    "key" -> value[2]
                    "props" -> value[3]
                    else -> segment.toIntOrNull()?.let { value.getOrNull(it) }
                }
            } else {
                segment.toIntOrNull()?.let { value.getOrNull(it) }
            }
        else -> null
    }

    private fun mapRef(id: String, chunks: Map<String, String>, models: Map<String, JsonElement>, resolving: Set<String>): JsonElement? {
        if (id in resolving) return null
        val entries = models[id] as? JsonArray ?: return null
        val resolved = resolve(entries, chunks, models, resolving + id) as? JsonArray ?: return null
        val pairs = resolved.mapNotNull { (it as? JsonArray)?.takeIf { p -> p.size == 2 } }
        return JsonObject(pairs.associate { (k, v) -> ((k as? JsonPrimitive)?.content ?: k.toString()) to v })
    }

    private fun setRef(id: String, chunks: Map<String, String>, models: Map<String, JsonElement>, resolving: Set<String>): JsonElement? {
        if (id in resolving) return null
        val values = models[id] as? JsonArray ?: return null
        return resolve(values, chunks, models, resolving + id)
    }

    // ---- payloads ----

    /** The pushes are slices of one stream cut anywhere: joined before parsing. `[1]` is text, `[3]` base64. */
    private fun appRouterPayloads(document: Document, chunks: MutableMap<String, String>, models: MutableMap<String, JsonElement>): List<JsonElement> {
        val stream = StringBuilder()
        for (script in document.select("script:not([src])")) {
            val data = script.data().takeIf { "self.__next_f.push" in it } ?: continue
            try {
                val raw = NEXT_F.find(data)?.groupValues?.get(1) ?: continue
                val push = json.parseToJsonElement(raw).jsonArray
                val content = push.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: continue
                when (push[0].jsonPrimitive.intOrNull) {
                    1 -> stream.append(content)
                    3 -> stream.append(String(Base64.getDecoder().decode(content), Charsets.UTF_8))
                }
            } catch (_: Exception) {
                // A script that is not a flight push after all.
            }
        }
        return rscPayloads(stream.toString(), chunks, models)
    }

    private fun pagesRouterPayloads(document: Document): List<JsonElement> {
        val data = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        return try {
            val root = json.parseToJsonElement(data)
            val pageProps = ((root as? JsonObject)?.get("props") as? JsonObject)?.get("pageProps")
            listOfNotNull(pageProps, root)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Rows `<hex id>:<json>` or `<hex id>:T<hex byte length>,<text>`. */
    internal fun rscPayloads(body: String, chunks: MutableMap<String, String>, models: MutableMap<String, JsonElement>): List<JsonElement> {
        val results = mutableListOf<JsonElement>()
        var pos = 0
        while (pos < body.length) {
            val colon = body.indexOf(':', pos)
            if (colon == -1) break
            val id = body.substring(pos, colon)
            if (id.isEmpty() || !id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                pos++
                continue
            }
            pos = colon + 1
            if (pos >= body.length) break
            if (body[pos] == 'T') {
                pos++
                val comma = body.indexOf(',', pos)
                if (comma == -1) break
                val byteLength = body.substring(pos, comma).toIntOrNull(16) ?: break
                pos = comma + 1
                var bytes = 0
                val start = pos
                // The length is in UTF-8 bytes, not characters.
                while (pos < body.length && bytes < byteLength) {
                    when {
                        body[pos].code < 0x80 -> bytes += 1
                        body[pos].code < 0x800 -> bytes += 2
                        Character.isHighSurrogate(body[pos]) -> {
                            bytes += 4
                            pos++
                        }
                        else -> bytes += 3
                    }
                    pos++
                }
                val text = body.substring(start, pos)
                chunks[id] = text
                runCatching { json.parseToJsonElement(text) }.getOrNull()?.let { results += it }
            } else {
                val (element, end) = jsonAt(body, pos)
                if (element != null) {
                    results += element
                    models[id] = element
                }
                pos = end
            }
        }
        return results
    }

    private fun jsonAt(body: String, start: Int): Pair<JsonElement?, Int> {
        var depth = 0
        var inString = false
        var escape = false
        var i = start
        while (i < body.length) {
            val c = body[i++]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) {
                    return runCatching { json.parseToJsonElement(body.substring(start, i)) }.getOrNull() to i
                }
            }
            if (depth == 0 && c.isWhitespace()) {
                return runCatching { json.parseToJsonElement(body.substring(start, i - 1)) }.getOrNull() to i
            }
        }
        return null to i
    }
}

// Small readers for JSON trees.
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()
fun JsonObject.num(key: String): Double? = str(key)?.toDoubleOrNull()
