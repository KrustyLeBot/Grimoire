package app.grimoire.reddit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class FeedView(
    val posts: List<RedditPost> = emptyList(),
    val after: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val stale: Boolean = false,
    /** When the proxy fetched it from Reddit (Unix seconds), for the "not up to date" notice. */
    val fetched: Long = 0,
    val loaded: Boolean = false,
) {
    val end: Boolean get() = loaded && after == null
}

data class ThreadView(
    val thread: RedditThread? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** "more" stubs being loaded, by id. */
    val expanding: Set<String> = emptySet(),
)

/** The Reddit section's state: the settings, the feeds shown, and the threads opened. */
class RedditState(
    private val client: RedditClient,
    private val store: RedditConfigStore,
    private val scope: CoroutineScope,
    private val online: () -> Boolean,
    private val notify: (String) -> Unit,
) {
    var config by mutableStateOf(store.load())
        private set
    /**
     * The subreddit shown, null for all. Filtering is local: the feed holds the latest posts of every
     * followed subreddit, fetched in one request (r/a+b+c/new).
     */
    var filter by mutableStateOf<String?>(null)

    /** Where the list was scrolled: kept while a post is open, so going back lands on the same place. */
    val list = androidx.compose.foundation.lazy.LazyListState()

    val feeds = mutableStateMapOf<String, FeedView>()
    val threads = mutableStateMapOf<String, ThreadView>()

    private fun feedKey() = "all"
    private val allSubs: String? get() = config.subs.takeIf { it.isNotEmpty() }?.joinToString("+")
    private val srt = FeedSort.NEW
    private val per = TopPeriod.DAY

    val feed: FeedView get() = feeds[feedKey()] ?: FeedView()

    /** The posts of the feed that match [filter]. */
    val shown: List<RedditPost> get() = filter.let { f -> if (f == null) feed.posts else feed.posts.filter { it.sub.equals(f, true) } }

    /** A post already fetched with the feed: shown at once when its thread opens. */
    fun post(id: String): RedditPost? = feeds.values.firstNotNullOfOrNull { v -> v.posts.firstOrNull { it.id == id } }

    /** First page of the feed (again when [refresh]); nothing when already shown. */
    fun load(refresh: Boolean = false) {
        val s = allSubs ?: return
        val key = feedKey()
        val current = feeds[key] ?: FeedView()
        if (current.loading || (current.loaded && !refresh)) return
        feeds[key] = current.copy(loading = true, error = null)
        scope.launch {
            feeds[key] = try {
                val f = client.feed(s, srt, per, null, online(), refresh)
                FeedView(f.posts, f.after, stale = f.stale, fetched = f.fetched, loaded = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                current.copy(loading = false, error = e.message ?: "Erreur")
            }
        }
    }

    /** Next page, when the list reaches its end. */
    fun loadMore() {
        val s = allSubs ?: return
        val key = feedKey()
        val current = feeds[key] ?: return
        val after = current.after ?: return
        if (current.loading) return
        feeds[key] = current.copy(loading = true, error = null)
        scope.launch {
            feeds[key] = try {
                val f = client.feed(s, srt, per, after, online())
                val known = current.posts.map { it.id }.toSet()
                current.copy(posts = current.posts + f.posts.filter { it.id !in known }, after = f.after, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                current.copy(loading = false, error = e.message ?: "Erreur")
            }
        }
    }

    fun threadKey(id: String, comment: String?, sort: CommentSort) = "$id|${comment.orEmpty()}|${sort.raw}"

    fun loadThread(id: String, comment: String?, sub: String, sort: CommentSort, force: Boolean = false) {
        val key = threadKey(id, comment, sort)
        val current = threads[key] ?: ThreadView()
        if (current.loading || (current.thread != null && !force)) return
        threads[key] = current.copy(loading = true, error = null)
        scope.launch {
            threads[key] = try {
                ThreadView(client.thread(id, sub, sort, comment, online()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                current.copy(loading = false, error = e.message ?: "Erreur")
            }
        }
    }

    /** Loads the replies of a "more" stub and puts them in its place. */
    fun expand(key: String, postId: String, stub: RedditComment, sort: CommentSort) {
        val current = threads[key] ?: return
        if (stub.id in current.expanding || stub.children.isEmpty()) return
        threads[key] = current.copy(expanding = current.expanding + stub.id)
        scope.launch {
            try {
                val loaded = client.more(postId, stub.children, sort, online())
                val now = threads[key] ?: return@launch
                val t = now.thread ?: return@launch
                val at = t.comments.indexOfFirst { it.more && it.id == stub.id }
                val rest = stub.children.drop(100) // Reddit gives 100 at most per call
                val replacement = loaded + if (rest.isNotEmpty()) listOf(stub.copy(children = rest, count = rest.size)) else emptyList()
                val comments = if (at < 0) t.comments else t.comments.subList(0, at) + replacement + t.comments.subList(at + 1, t.comments.size)
                threads[key] = now.copy(thread = t.copy(comments = comments), expanding = now.expanding - stub.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                threads[key]?.let { threads[key] = it.copy(expanding = it.expanding - stub.id) }
                notify(e.message ?: "Erreur")
            }
        }
    }

    /** Checks then saves the settings. Returns an error message, or null when saved. */
    suspend fun save(c: RedditConfig): String? {
        if (c.url.contains("example.com")) return "Remplace l'adresse d'exemple par celle de ton proxy"
        if (c.subs.isEmpty()) return "Ajoute au moins un subreddit"
        client.test(c)?.let { return it }
        store.save(c)
        config = c
        if (filter != null && c.subs.none { it.equals(filter, true) }) filter = null
        feeds.clear()
        return null
    }

    /** At each start: the proxy learns (again) the feeds to keep fresh. Silent when it fails. */
    fun onOpen() {
        if (!config.configured || !online()) return
        scope.launch { runCatching { client.watch() } }
    }
}
