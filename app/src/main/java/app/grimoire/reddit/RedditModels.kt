package app.grimoire.reddit

import org.json.JSONArray
import org.json.JSONObject

// What the Reddit proxy (KrustyLeBot/RedditProxy) answers: Reddit's data already cleaned and flattened.

data class RedditImage(val url: String, val width: Int, val height: Int, val caption: String?, val mp4: String?)

data class RedditVideo(val hls: String?, val mp4: String?, val width: Int, val height: Int, val duration: Int)

enum class MediaKind { TEXT, IMAGE, GALLERY, VIDEO, LINK }

data class RedditPost(
    val id: String,
    val name: String,
    val sub: String,
    val title: String,
    val author: String,
    val flair: String?,
    val flairColor: String?,
    val score: Int,
    val ratio: Double,
    val comments: Int,
    val created: Long,
    val edited: Boolean,
    val stickied: Boolean,
    val nsfw: Boolean,
    val spoiler: Boolean,
    val locked: Boolean,
    val text: String?,
    val html: String?,
    val url: String?,
    val domain: String?,
    val permalink: String,
    val thumb: String?,
    val kind: MediaKind,
    val images: List<RedditImage>,
    val video: RedditVideo?,
    /** Read from RSS by the proxy: score, ratio and comment count are unknown. */
    val partial: Boolean = false,
)

data class RedditComment(
    val more: Boolean,
    val id: String,
    val parent: String,
    val depth: Int,
    val author: String = "",
    val flair: String? = null,
    val text: String = "",
    val html: String = "",
    val score: Int = 0,
    val scoreHidden: Boolean = false,
    val created: Long = 0,
    val edited: Boolean = false,
    val op: Boolean = false,
    val mod: Boolean = false,
    val stickied: Boolean = false,
    val collapsed: Boolean = false,
    val deleted: Boolean = false,
    /** For "more": how many replies are left out, their ids (load with /more) or the comment to open. */
    val count: Int = 0,
    val children: List<String> = emptyList(),
    val continueAt: String? = null,
)

data class RedditFeed(val posts: List<RedditPost>, val after: String?, val fetched: Long, val stale: Boolean)

data class RedditThread(val post: RedditPost, val comments: List<RedditComment>, val fetched: Long, val stale: Boolean)

enum class FeedSort(val raw: String, val label: String) {
    HOT("hot", "Populaires"), NEW("new", "Récents"), TOP("top", "Top"), RISING("rising", "En hausse")
}

enum class TopPeriod(val raw: String, val label: String) {
    DAY("day", "Jour"), WEEK("week", "Semaine"), MONTH("month", "Mois"), YEAR("year", "Année"), ALL("all", "Tout")
}

enum class CommentSort(val raw: String, val label: String) {
    BEST("best", "Meilleurs"), TOP("top", "Top"), NEW("new", "Récents"), CONTROVERSIAL("controversial", "Controversés"), OLD("old", "Anciens")
}

object RedditJson {
    private fun JSONObject.str(name: String): String? = optString(name, "").takeIf { it.isNotEmpty() && !isNull(name) }

    private fun image(o: JSONObject) = RedditImage(o.getString("url"), o.optInt("w"), o.optInt("h"), o.str("caption"), o.str("mp4"))

    fun post(o: JSONObject): RedditPost {
        val media = o.optJSONObject("media") ?: JSONObject()
        val images = media.optJSONArray("images") ?: JSONArray()
        val video = media.optJSONObject("video")?.let {
            RedditVideo(it.str("hls"), it.str("mp4"), it.optInt("w"), it.optInt("h"), it.optInt("duration"))
        }
        return RedditPost(
            id = o.getString("id"), name = o.optString("name", "t3_" + o.getString("id")), sub = o.optString("sub"),
            title = o.optString("title"), author = o.optString("author"), flair = o.str("flair"), flairColor = o.str("flairColor"),
            score = o.optInt("score"), ratio = o.optDouble("ratio", 0.0), comments = o.optInt("comments"),
            created = o.optLong("created"), edited = o.optBoolean("edited"), stickied = o.optBoolean("stickied"),
            nsfw = o.optBoolean("nsfw"), spoiler = o.optBoolean("spoiler"), locked = o.optBoolean("locked"),
            text = o.str("text"), html = o.str("html"), url = o.str("url"), domain = o.str("domain"),
            permalink = o.optString("permalink"), thumb = o.str("thumb"),
            kind = when (media.optString("kind")) {
                "image" -> MediaKind.IMAGE
                "gallery" -> MediaKind.GALLERY
                "video" -> MediaKind.VIDEO
                "text" -> MediaKind.TEXT
                else -> MediaKind.LINK
            },
            images = (0 until images.length()).map { image(images.getJSONObject(it)) },
            video = video,
            partial = o.optBoolean("partial"),
        )
    }

    fun comment(o: JSONObject): RedditComment {
        val children = o.optJSONArray("children") ?: JSONArray()
        return RedditComment(
            more = o.optString("kind") == "more", id = o.getString("id"), parent = o.optString("parent"), depth = o.optInt("depth"),
            author = o.optString("author"), flair = o.str("flair"), text = o.optString("text"), html = o.optString("html"),
            score = o.optInt("score"), scoreHidden = o.optBoolean("scoreHidden"), created = o.optLong("created"),
            edited = o.optBoolean("edited"), op = o.optBoolean("op"), mod = o.optBoolean("mod"), stickied = o.optBoolean("stickied"),
            collapsed = o.optBoolean("collapsed"), deleted = o.optBoolean("deleted"), count = o.optInt("count"),
            children = (0 until children.length()).map { children.getString(it) }, continueAt = o.str("continueAt"),
        )
    }

    private fun comments(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { comment(a.getJSONObject(it)) }

    fun feed(text: String): RedditFeed {
        val o = JSONObject(text)
        val posts = o.optJSONArray("posts") ?: JSONArray()
        return RedditFeed((0 until posts.length()).map { post(posts.getJSONObject(it)) }, o.str("after"), o.optLong("fetched"), o.optBoolean("stale"))
    }

    fun thread(text: String): RedditThread {
        val o = JSONObject(text)
        return RedditThread(post(o.getJSONObject("post")), comments(o.optJSONArray("comments")), o.optLong("fetched"), o.optBoolean("stale"))
    }

    fun more(text: String): List<RedditComment> = comments(JSONObject(text).optJSONArray("comments"))
}

/** 1 234 → "1,2 k", the way Reddit shows scores (French decimal comma, whatever the locale). */
fun compactCount(n: Int): String {
    fun tenths(unit: Int, suffix: String): String {
        val t = n.toLong() * 10 / unit
        return if (t % 10 == 0L || t >= 100) "${t / 10} $suffix" else "${t / 10},${t % 10} $suffix"
    }
    return when {
        n >= 1_000_000 -> tenths(1_000_000, "M")
        n >= 1_000 -> tenths(1_000, "k")
        else -> n.toString()
    }
}

/** "il y a 3 h", "il y a 2 j"… from a Unix time in seconds. */
fun ago(created: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val s = (now - created).coerceAtLeast(0)
    return when {
        s < 60 -> "à l'instant"
        s < 3600 -> "il y a ${s / 60} min"
        s < 86_400 -> "il y a ${s / 3600} h"
        s < 30 * 86_400 -> "il y a ${s / 86_400} j"
        s < 365 * 86_400 -> "il y a ${s / (30 * 86_400)} mois"
        else -> "il y a ${s / (365 * 86_400)} an${if (s >= 2 * 365 * 86_400) "s" else ""}"
    }
}
