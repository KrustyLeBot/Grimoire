package app.grimoire.reddit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditJsonTest {
    // What the proxy answers (same shapes as its tests produce).
    private val feed = """
        {"stale":true,"sub":"france","sort":"hot","after":"t3_p3","fetched":1793270000,"posts":[
         {"id":"p1","name":"t3_p1","sub":"france","title":"Une image","author":"alice","flair":"Humour","flairColor":"#ffd635",
          "score":1520,"ratio":0.94,"comments":231,"created":1793270000,"url":"https://i.redd.it/abc.jpg","domain":"i.redd.it",
          "permalink":"/r/france/comments/p1/x/","thumb":"https://preview.redd.it/t.jpg",
          "media":{"kind":"image","images":[{"url":"https://preview.redd.it/abc.jpg?s=1","w":1200,"h":900}]}},
         {"id":"p3","name":"t3_p3","sub":"france","partial":true,"title":"Galerie","author":"carol","score":10,"ratio":0.7,"comments":3,"created":1,
          "permalink":"/r/france/comments/p3/g/","media":{"kind":"gallery","images":[{"url":"https://a/1.jpg","w":640,"h":480,"caption":"deux"},{"url":"https://a/2.jpg"}]}},
         {"id":"p4","name":"t3_p4","sub":"france","title":"Vidéo","author":"dan","score":5,"ratio":0.6,"comments":0,"created":1,"nsfw":true,
          "permalink":"/r/france/comments/p4/v/","media":{"kind":"video","video":{"hls":"https://v.redd.it/vid/HLSPlaylist.m3u8","mp4":"https://v.redd.it/vid/DASH_720.mp4","w":1280,"h":720,"duration":31}}},
         {"id":"p2","name":"t3_p2","sub":"france","title":"Question","author":"bob","score":42,"ratio":0.81,"comments":12,"created":1,
          "edited":true,"stickied":true,"locked":true,"text":"Du **gras**","html":"<div class=\"md\"><p>Du <strong>gras</strong></p></div>",
          "permalink":"/r/france/comments/p2/q/","media":{"kind":"text"}}
        ]}
    """.trimIndent()

    @Test fun feed() {
        val f = RedditJson.feed(feed)
        assertEquals(4, f.posts.size)
        assertEquals("t3_p3", f.after)
        assertTrue(f.stale)
        val (img, gallery, video, self) = f.posts
        assertEquals(MediaKind.IMAGE, img.kind)
        assertEquals(1200, img.images[0].width)
        assertEquals("Humour", img.flair)
        assertEquals(MediaKind.GALLERY, gallery.kind)
        assertEquals("deux", gallery.images[0].caption)
        assertTrue(gallery.partial)
        assertFalse(img.partial)
        assertNull(gallery.images[1].caption)
        assertEquals(MediaKind.VIDEO, video.kind)
        assertEquals(31, video.video?.duration)
        assertTrue(video.nsfw)
        assertEquals(MediaKind.TEXT, self.kind)
        assertTrue(self.stickied && self.locked && self.edited)
        assertNull(self.url)
    }

    @Test fun threadAndMore() {
        val t = RedditJson.thread(
            """{"post":{"id":"p2","title":"Q","media":{"kind":"text"}},"fetched":1,"comments":[
               {"kind":"comment","id":"c1","parent":"t3_p2","depth":0,"author":"bob","html":"<p>Hi</p>","score":10,"op":true},
               {"kind":"comment","id":"c2","parent":"t1_c1","depth":1,"author":"[deleted]","text":"[removed]","deleted":true},
               {"kind":"more","id":"c9","parent":"t1_c1","depth":1,"count":4,"children":["c9","c10"]},
               {"kind":"more","id":"_","parent":"t1_c3","depth":10,"continueAt":"c3"}]}""",
        )
        assertFalse(t.stale)
        assertEquals(4, t.comments.size)
        assertTrue(t.comments[0].op && !t.comments[0].more)
        assertTrue(t.comments[1].deleted)
        assertEquals(listOf("c9", "c10"), t.comments[2].children)
        assertEquals("c3", t.comments[3].continueAt)
        assertEquals(2, RedditJson.more("""{"comments":[{"kind":"comment","id":"a","depth":1},{"kind":"comment","id":"b","depth":2}]}""").size)
    }

    @Test fun settingsParsing() {
        assertEquals(listOf("france", "manga", "Python"), RedditConfig.parseSubs("r/france, manga;/r/Python  France\n!bad"))
        assertFalse(RedditConfig(subs = listOf("france")).configured) // still the example address
        assertTrue(RedditConfig(url = "https://reddit.mondomaine.fr/", subs = listOf("france")).configured)
    }

    @Test fun counts() {
        assertEquals("999", compactCount(999))
        assertEquals("1,2 k", compactCount(1234))
        assertEquals("12 k", compactCount(12_345))
        assertEquals("2 k", compactCount(2000))
        assertEquals("1,5 M", compactCount(1_500_000))
        assertEquals("il y a 3 h", ago(0, 3 * 3600 + 10))
    }
}
