package app.grimoire

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.grimoire.data.seriesKey
import app.grimoire.source.SeriesPage
import app.grimoire.source.SeriesSummary
import app.grimoire.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** A series in a listing, with the source it comes from. */
data class Found(val source: String, val series: SeriesSummary) {
    val key get() = seriesKey(source, series.slug)
}

data class Listing(
    val items: List<Found> = emptyList(),
    /** Source id → last page loaded. */
    val pages: Map<String, Int> = emptyMap(),
    /** Sources with nothing more to give. */
    val ended: Set<String> = emptySet(),
    /** Source id → why its last page failed (the others go on). */
    val errors: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
)

/**
 * A paged list over one or several sources: each "more" asks every source that still has pages
 * for its next one, in parallel, and interleaves the answers so no source drowns the others.
 */
class Lister(
    private val scope: CoroutineScope,
    private val source: (String) -> Source?,
    private val describe: (Exception) -> String,
    private val fetch: suspend (Source, Int) -> SeriesPage,
) {
    var listing by mutableStateOf(Listing())
        private set
    var sources: List<String> = emptyList()
        private set
    private var job: Job? = null

    fun reset(ids: List<String>) {
        job?.cancel()
        sources = ids
        listing = Listing()
        more()
    }

    fun clear() {
        job?.cancel()
        sources = emptyList()
        listing = Listing()
    }

    fun retry() {
        listing = listing.copy(errors = emptyMap())
        more()
    }

    val hasNext get() = sources.any { it !in listing.ended && it !in listing.errors }

    fun more() {
        val start = listing
        val pending = sources.filter { it !in start.ended && it !in start.errors }
        if (start.loading || pending.isEmpty()) return
        listing = start.copy(loading = true)
        job = scope.launch {
            val results = pending.map { id ->
                async {
                    val page = (start.pages[id] ?: 0) + 1
                    try {
                        val src = source(id) ?: throw IllegalStateException("Source introuvable")
                        Triple(id, page, Result.success(fetch(src, page)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Triple(id, page, Result.failure<SeriesPage>(e))
                    }
                }
            }.awaitAll()
            var now = listing
            val seen = now.items.mapTo(HashSet()) { it.key }
            val lists = results.map { (id, _, r) -> r.getOrNull()?.items.orEmpty().map { Found(id, it) } }
            val merged = (0 until (lists.maxOfOrNull { it.size } ?: 0))
                .flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
                .filter { seen.add(it.key) }
            results.forEach { (id, page, r) ->
                now = r.fold(
                    onSuccess = { p ->
                        now.copy(
                            pages = now.pages + (id to page),
                            ended = if (!p.hasNext || p.items.isEmpty()) now.ended + id else now.ended,
                        )
                    },
                    onFailure = { e -> now.copy(errors = now.errors + (id to describe(e as? Exception ?: Exception(e)))) },
                )
            }
            listing = now.copy(items = now.items + merged, loading = false)
        }
    }
}
