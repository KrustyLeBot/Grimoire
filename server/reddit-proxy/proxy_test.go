package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeReddit serves the files of testdata/ like Reddit's public .json endpoints, and records what it was asked.
type fakeReddit struct {
	*httptest.Server
	mu     sync.Mutex
	paths  []string
	agents []string
	status atomic.Int32 // non-zero: answer this instead
}

func newFakeReddit(t *testing.T) *fakeReddit {
	f := &fakeReddit{}
	f.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.paths = append(f.paths, r.URL.Path+"?"+r.URL.RawQuery)
		f.agents = append(f.agents, r.UserAgent())
		f.mu.Unlock()
		if s := f.status.Load(); s != 0 {
			if s == http.StatusTooManyRequests {
				w.Header().Set("Retry-After", "2")
			}
			w.WriteHeader(int(s))
			return
		}
		var file string
		switch {
		case strings.HasPrefix(r.URL.Path, "/r/"):
			file = "testdata/feed.json"
		case strings.HasPrefix(r.URL.Path, "/comments/"):
			file = "testdata/thread.json"
		case r.URL.Path == "/api/morechildren.json":
			file = "testdata/more.json"
		default:
			http.NotFound(w, r)
			return
		}
		b, _ := os.ReadFile(file)
		w.Header().Set("Content-Type", "application/json; charset=UTF-8")
		_, _ = w.Write(b)
	}))
	t.Cleanup(f.Close)
	return f
}

func (f *fakeReddit) calls() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.paths)
}

func newTestServer(t *testing.T, reddit *fakeReddit, perMinute int) (*Server, *httptest.Server) {
	data := t.TempDir()
	s := &Server{
		reddit: &Reddit{
			base: reddit.URL, userAgent: "test:grimoire:1 (by u/test)", http: reddit.Client(),
			limiter: NewLimiter(perMinute, 2*time.Second),
		},
		cache:        NewCache(data+"/cache", 50),
		watch:        NewWatch(data+"/watch.json", time.Hour),
		keys:         [][]byte{[]byte("phone-key"), []byte("pc-key")},
		feedTTL:      time.Minute,
		threadTTL:    time.Minute,
		refreshEvery: time.Minute,
		flights:      map[string]*flight{},
	}
	api := httptest.NewServer(s.Handler())
	t.Cleanup(api.Close)
	return s, api
}

func get(t *testing.T, api *httptest.Server, path string, key string, out any) int {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, api.URL+path, nil)
	if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		_ = json.NewDecoder(resp.Body).Decode(out)
	}
	return resp.StatusCode
}

func TestAuth(t *testing.T) {
	reddit := newFakeReddit(t)
	_, api := newTestServer(t, reddit, 600)
	if code := get(t, api, "/feed/france", "", nil); code != http.StatusUnauthorized {
		t.Fatalf("no key: %d", code)
	}
	if code := get(t, api, "/feed/france", "wrong", nil); code != http.StatusUnauthorized {
		t.Fatalf("wrong key: %d", code)
	}
	req, _ := http.NewRequest(http.MethodGet, api.URL+"/feed/france", nil)
	req.Header.Set("X-Api-Key", "pc-key")
	resp, _ := http.DefaultClient.Do(req)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("X-Api-Key: %d", resp.StatusCode)
	}
	if code := get(t, api, "/health", "", nil); code != http.StatusOK {
		t.Fatalf("health must be open (Kuma, Docker): %d", code)
	}
	if reddit.calls() != 1 {
		t.Fatalf("refused requests must not reach Reddit: %d calls", reddit.calls())
	}
}

func TestFeedIsCleanedAndCached(t *testing.T) {
	reddit := newFakeReddit(t)
	s, api := newTestServer(t, reddit, 600)
	var f Feed
	if code := get(t, api, "/feed/france?sort=hot", "phone-key", &f); code != http.StatusOK {
		t.Fatalf("status %d", code)
	}
	if len(f.Posts) != 4 || f.After != "t3_p3" {
		t.Fatalf("posts %d after %q", len(f.Posts), f.After)
	}
	img, self, gallery, video := f.Posts[0], f.Posts[1], f.Posts[2], f.Posts[3]
	if img.Media.Kind != "image" || img.Media.Images[0].URL != "https://preview.redd.it/abc.jpg?width=1200&format=pjpg&s=1" ||
		img.Thumb != "https://preview.redd.it/abc.jpg?width=320&s=3" || img.Flair != "Humour" || img.Ratio != 0.94 {
		t.Fatalf("image post: %+v", img)
	}
	if self.Media.Kind != "text" || !strings.Contains(self.HTML, "<strong>gras</strong>") || self.Text == "" ||
		!self.Edited || !self.Stickied || !self.Locked || self.URL != "" || self.Thumb != "" {
		t.Fatalf("self post: %+v", self)
	}
	if gallery.Media.Kind != "gallery" || len(gallery.Media.Images) != 2 || gallery.Media.Images[0].Caption != "deux" ||
		gallery.Media.Images[1].Width != 800 {
		t.Fatalf("gallery (in gallery_data order): %+v", gallery.Media)
	}
	if video.Media.Kind != "video" || video.Media.Video.HLS == "" || !video.NSFW || video.Thumb != "" {
		t.Fatalf("video: %+v", video)
	}
	if !strings.Contains(reddit.paths[0], "raw_json=1") || !strings.Contains(reddit.paths[0], "/r/france/hot.json") {
		t.Fatalf("request: %s", reddit.paths[0])
	}
	if reddit.agents[0] != "test:grimoire:1 (by u/test)" {
		t.Fatalf("user agent: %s", reddit.agents[0])
	}
	get(t, api, "/feed/france?sort=hot", "pc-key", nil)
	if reddit.calls() != 1 {
		t.Fatal("a fresh feed must come from the cache, for every device")
	}
	if keys := s.watch.List(); len(keys) != 1 || keys[0] != (FeedKey{"france", "hot", ""}) {
		t.Fatalf("opening a feed watches it: %v", keys)
	}
}

func TestStaleCopyWhenRedditFails(t *testing.T) {
	reddit := newFakeReddit(t)
	s, api := newTestServer(t, reddit, 600)
	s.feedTTL = time.Nanosecond
	get(t, api, "/feed/france", "phone-key", nil)
	reddit.status.Store(http.StatusInternalServerError)
	var f Feed
	if code := get(t, api, "/feed/france", "phone-key", &f); code != http.StatusOK || !f.Stale || len(f.Posts) != 4 {
		t.Fatalf("want the stale copy: %d %+v", code, f.Stale)
	}
	var e map[string]string
	if code := get(t, api, "/feed/inconnu", "phone-key", &e); code != http.StatusBadGateway || e["error"] == "" {
		t.Fatalf("nothing cached: want 502 with a message, got %d %v", code, e)
	}
	reddit.status.Store(http.StatusNotFound)
	if code := get(t, api, "/feed/autre", "phone-key", nil); code != http.StatusNotFound {
		t.Fatalf("missing sub: %d", code)
	}
}

func TestRateLimitAnd429(t *testing.T) {
	reddit := newFakeReddit(t)
	s, api := newTestServer(t, reddit, 60) // one request per second
	start := time.Now()
	get(t, api, "/feed/a1", "phone-key", nil)
	get(t, api, "/feed/a2", "phone-key", nil)
	if time.Since(start) < 900*time.Millisecond {
		t.Fatal("requests to Reddit must be spaced")
	}
	reddit.status.Store(http.StatusTooManyRequests)
	if code := get(t, api, "/feed/a3", "phone-key", nil); code != http.StatusServiceUnavailable {
		t.Fatalf("429 with nothing cached: %d", code)
	}
	reddit.status.Store(0)
	before := time.Now()
	if code := get(t, api, "/feed/a4", "phone-key", nil); code != http.StatusOK {
		t.Fatalf("after the pause: %d", code)
	}
	if time.Since(before) < 1500*time.Millisecond {
		t.Fatal("after a 429 Reddit is left alone for Retry-After (2 s)")
	}
	reddit.status.Store(http.StatusTooManyRequests)
	get(t, api, "/feed/a5", "phone-key", nil)
	s.reddit.limiter.PauseUntil(time.Now().Add(time.Minute))
	var e map[string]string
	if code := get(t, api, "/feed/a6", "phone-key", &e); code != http.StatusServiceUnavailable || e["error"] == "" {
		t.Fatalf("a wait longer than the maximum is refused at once: %d %v", code, e)
	}
}

func TestThreadAndMore(t *testing.T) {
	reddit := newFakeReddit(t)
	_, api := newTestServer(t, reddit, 600)
	var th Thread
	if code := get(t, api, "/thread/p2?sort=top", "phone-key", &th); code != http.StatusOK {
		t.Fatalf("status %d", code)
	}
	if th.Post.ID != "p2" || len(th.Comments) != 6 {
		t.Fatalf("thread: %+v", th)
	}
	kinds := []string{}
	for _, c := range th.Comments {
		kinds = append(kinds, c.Kind+":"+c.ID)
	}
	if strings.Join(kinds, " ") != "comment:c1 comment:c2 more:c9 comment:c3 more:_ more:c20" {
		t.Fatalf("depth-first order: %v", kinds)
	}
	op, deleted, more, mod, cont := th.Comments[0], th.Comments[1], th.Comments[2], th.Comments[3], th.Comments[4]
	if !op.OP || op.Flair != "Paris" || op.HTML == "" || !deleted.Deleted || deleted.Depth != 1 {
		t.Fatalf("comments: %+v %+v", op, deleted)
	}
	if more.Count != 4 || len(more.Children) != 2 || !mod.Mod || !mod.ScoreHide || !mod.Edited {
		t.Fatalf("more/mod: %+v %+v", more, mod)
	}
	if cont.ContinueAt != "c3" {
		t.Fatalf("continue this thread: %+v", cont)
	}
	if !strings.Contains(reddit.paths[0], "/comments/p2.json") || !strings.Contains(reddit.paths[0], "sort=top") {
		t.Fatalf("request: %s", reddit.paths[0])
	}
	var m struct{ Comments []Comment }
	if code := get(t, api, "/more/p2?children=c9,c10", "phone-key", &m); code != http.StatusOK || len(m.Comments) != 2 || m.Comments[1].Depth != 2 {
		t.Fatalf("more: %d %+v", code, m)
	}
	if !strings.Contains(reddit.paths[1], "link_id=t3_p2") || !strings.Contains(reddit.paths[1], "children=c9%2Cc10") {
		t.Fatalf("morechildren request: %s", reddit.paths[1])
	}
	if code := get(t, api, "/thread/p2?comment=c3", "phone-key", nil); code != http.StatusOK ||
		!strings.Contains(reddit.paths[2], "/comments/p2/_/c3.json") {
		t.Fatalf("continue thread request: %s", reddit.paths[2])
	}
	if code := get(t, api, "/more/p2?children=../x", "phone-key", nil); code != http.StatusBadRequest {
		t.Fatalf("bad children: %d", code)
	}
}

func TestWatchIsRefreshedInTheBackground(t *testing.T) {
	reddit := newFakeReddit(t)
	s, api := newTestServer(t, reddit, 600)
	body := `{"feeds":[{"sub":"france","sort":"new"},{"sub":"manga","sort":"top","t":"week"}]}`
	req, _ := http.NewRequest(http.MethodPost, api.URL+"/watch", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer phone-key")
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("watch: %v %v", err, resp)
	}
	resp.Body.Close()
	if reddit.calls() != 0 {
		t.Fatal("watching fetches nothing by itself")
	}
	time.Sleep(250 * time.Millisecond) // one idle slot (headroom) before background work
	s.refreshOne(context.Background())
	time.Sleep(250 * time.Millisecond)
	s.refreshOne(context.Background())
	if reddit.calls() != 2 {
		t.Fatalf("both watched feeds refreshed: %d calls %v", reddit.calls(), reddit.paths)
	}
	get(t, api, "/feed/manga?sort=top&t=week", "phone-key", nil)
	if reddit.calls() != 2 {
		t.Fatal("a refreshed feed is served from the cache")
	}
	// The list survives a restart; and when it is lost, the apps send it again.
	if keys := NewWatch(s.watch.path, time.Hour).List(); len(keys) != 2 {
		t.Fatalf("watch list on disk: %v", keys)
	}
}

func TestCacheSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	c := NewCache(dir, 2)
	c.Put("a", []byte(`{"x":1}`))
	c.Put("b", []byte(`{"x":2}`))
	c.Put("c", []byte(`{"x":3}`))
	if body, _, ok := NewCache(dir, 2).Get("a"); !ok || string(body) != `{"x":1}` {
		t.Fatal("evicted from memory, still on disk")
	}
	if string(markStale([]byte(`{"x":1}`))) != `{"stale":true,"x":1}` {
		t.Fatal("markStale")
	}
}

func TestInvalidInput(t *testing.T) {
	reddit := newFakeReddit(t)
	_, api := newTestServer(t, reddit, 600)
	for _, p := range []string{"/feed/a", "/feed/fr..ance", "/feed/france?sort=best", "/feed/france?after=xyz", "/thread/P2", "/thread/p2?sort=hot"} {
		if code := get(t, api, p, "phone-key", nil); code != http.StatusBadRequest {
			t.Fatalf("%s: %d", p, code)
		}
	}
	if reddit.calls() != 0 {
		t.Fatal("invalid requests never reach Reddit")
	}
	if code := get(t, api, "/feed/france+manga", "phone-key", nil); code != http.StatusOK {
		t.Fatalf("multi-sub feed: %d", code)
	}
}
