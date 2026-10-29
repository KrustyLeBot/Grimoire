package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

var (
	reSub     = regexp.MustCompile(`^[A-Za-z0-9_]{2,21}(\+[A-Za-z0-9_]{2,21}){0,9}$`)
	reID      = regexp.MustCompile(`^[a-z0-9]{1,12}$`)
	reAfter   = regexp.MustCompile(`^t3_[a-z0-9]{1,12}$`)
	feedSorts = map[string]bool{"hot": true, "new": true, "top": true, "rising": true, "controversial": true}
	times     = map[string]bool{"hour": true, "day": true, "week": true, "month": true, "year": true, "all": true}
	// The app's names for comment orders, and Reddit's.
	commentSorts = map[string]string{"best": "confidence", "top": "top", "new": "new", "controversial": "controversial", "old": "old", "qa": "qa"}
)

type Server struct {
	reddit       *Reddit
	cache        *Cache
	watch        *Watch
	keys         [][]byte
	feedTTL      time.Duration
	threadTTL    time.Duration
	refreshEvery time.Duration

	flightMu sync.Mutex
	flights  map[string]*flight
}

type flight struct {
	done chan struct{}
	body []byte
	err  error
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.health)
	mux.HandleFunc("GET /feed/{sub}", s.auth(s.feed))
	mux.HandleFunc("GET /thread/{id}", s.auth(s.thread))
	mux.HandleFunc("GET /more/{id}", s.auth(s.more))
	mux.HandleFunc("POST /watch", s.auth(s.setWatch))
	return logged(mux)
}

// auth accepts "Authorization: Bearer <key>" or "X-Api-Key: <key>", one key per device.
func (s *Server) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		given := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if given == "" || given == r.Header.Get("Authorization") {
			given = strings.TrimSpace(r.Header.Get("X-Api-Key"))
		}
		for _, k := range s.keys {
			if given != "" && subtle.ConstantTimeCompare([]byte(given), k) == 1 {
				next(w, r)
				return
			}
		}
		fail(w, http.StatusUnauthorized, "clé d'accès au proxy absente ou invalide")
	}
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "watched": len(s.watch.List()), "oauth": s.reddit.oauth()})
}

func (s *Server) feed(w http.ResponseWriter, r *http.Request) {
	sub := r.PathValue("sub")
	q := r.URL.Query()
	sort := q.Get("sort")
	if sort == "" {
		sort = "hot"
	}
	t := q.Get("t")
	after := q.Get("after")
	switch {
	case !reSub.MatchString(sub):
		fail(w, http.StatusBadRequest, "nom de subreddit invalide")
		return
	case !feedSorts[sort]:
		fail(w, http.StatusBadRequest, "tri invalide")
		return
	case t != "" && !times[t]:
		fail(w, http.StatusBadRequest, "période invalide")
		return
	case after != "" && !reAfter.MatchString(after):
		fail(w, http.StatusBadRequest, "page invalide")
		return
	}
	if sort != "top" && sort != "controversial" {
		t = ""
	}
	if after == "" {
		s.watch.Touch(FeedKey{Sub: sub, Sort: sort, Time: t})
	}
	s.serve(w, r, feedCacheKey(FeedKey{sub, sort, t}, after), s.feedTTL, func(ctx context.Context, bg bool) ([]byte, error) {
		return s.fetchFeed(ctx, FeedKey{sub, sort, t}, after, bg)
	})
}

func feedCacheKey(k FeedKey, after string) string {
	return "feed:" + strings.ToLower(k.Sub) + ":" + k.Sort + ":" + k.Time + ":" + after
}

func (s *Server) fetchFeed(ctx context.Context, k FeedKey, after string, background bool) ([]byte, error) {
	query := url.Values{"limit": {"25"}}
	if after != "" {
		query.Set("after", after)
	}
	if k.Time != "" {
		query.Set("t", k.Time)
	}
	body, err := s.reddit.Get(ctx, "/r/"+k.Sub+"/"+k.Sort, query, background)
	if err != nil {
		return nil, err
	}
	f, err := parseFeed(body, k.Sub, k.Sort)
	if err != nil {
		return nil, errBadShape
	}
	f.Fetched = time.Now().Unix()
	return json.Marshal(f)
}

func (s *Server) thread(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	q := r.URL.Query()
	sort, ok := commentSorts[q.Get("sort")]
	if q.Get("sort") == "" {
		sort, ok = "confidence", true
	}
	comment := q.Get("comment")
	if !reID.MatchString(id) || !ok || (comment != "" && !reID.MatchString(comment)) {
		fail(w, http.StatusBadRequest, "requête invalide")
		return
	}
	path := "/comments/" + id
	if comment != "" {
		path += "/_/" + comment
	}
	s.serve(w, r, "thread:"+id+":"+comment+":"+sort, s.threadTTL, func(ctx context.Context, _ bool) ([]byte, error) {
		body, err := s.reddit.Get(ctx, path, url.Values{"sort": {sort}, "limit": {"200"}}, false)
		if err != nil {
			return nil, err
		}
		t, err := parseThread(body)
		if err != nil {
			return nil, errBadShape
		}
		t.Fetched = time.Now().Unix()
		return json.Marshal(t)
	})
}

// more loads replies Reddit left out of a thread ("voir N autres réponses").
func (s *Server) more(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	q := r.URL.Query()
	children := q.Get("children")
	sort, ok := commentSorts[q.Get("sort")]
	if q.Get("sort") == "" {
		sort, ok = "confidence", true
	}
	if !reID.MatchString(id) || !validChildren(children) || !ok {
		fail(w, http.StatusBadRequest, "requête invalide")
		return
	}
	s.serve(w, r, "more:"+id+":"+sort+":"+children, s.threadTTL, func(ctx context.Context, _ bool) ([]byte, error) {
		body, err := s.reddit.Get(ctx, "/api/morechildren", url.Values{
			"api_type": {"json"}, "link_id": {"t3_" + id}, "children": {children}, "sort": {sort}, "limit_children": {"false"},
		}, false)
		if err != nil {
			return nil, err
		}
		comments, err := parseMore(body, id)
		if err != nil {
			return nil, errBadShape
		}
		return json.Marshal(map[string]any{"comments": comments, "fetched": time.Now().Unix()})
	})
}

// validChildren: 1 to 100 comment ids, comma separated (Reddit's limit per morechildren call).
func validChildren(s string) bool {
	ids := strings.Split(s, ",")
	if s == "" || len(ids) > 100 {
		return false
	}
	for _, id := range ids {
		if !reID.MatchString(id) {
			return false
		}
	}
	return true
}

// setWatch receives the feeds an app shows; they are refreshed in the background from now on.
func (s *Server) setWatch(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Feeds []FeedKey `json:"feeds"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&req); err != nil || len(req.Feeds) > 100 {
		fail(w, http.StatusBadRequest, "liste de fils invalide")
		return
	}
	var keys []FeedKey
	for _, k := range req.Feeds {
		if k.Sort == "" {
			k.Sort = "hot"
		}
		if !reSub.MatchString(k.Sub) || !feedSorts[k.Sort] || (k.Time != "" && !times[k.Time]) {
			fail(w, http.StatusBadRequest, "fil invalide : "+k.Sub)
			return
		}
		if k.Sort != "top" && k.Sort != "controversial" {
			k.Time = ""
		}
		keys = append(keys, k)
	}
	s.watch.Touch(keys...)
	writeJSON(w, http.StatusOK, map[string]any{"watched": len(s.watch.List())})
}

// serve answers from the cache while it is fresh; otherwise fetches (one fetch per key at a time),
// and falls back to the cached copy, marked stale, when Reddit cannot be reached or refuses.
func (s *Server) serve(w http.ResponseWriter, r *http.Request, key string, ttl time.Duration, fetch func(context.Context, bool) ([]byte, error)) {
	cached, at, ok := s.cache.Get(key)
	if ok && time.Since(at) < ttl && r.URL.Query().Get("refresh") != "1" {
		writeRaw(w, cached)
		return
	}
	body, err := s.once(key, func() ([]byte, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		return fetch(ctx, false)
	})
	if err == nil {
		s.cache.Put(key, body)
		writeRaw(w, body)
		return
	}
	if ok {
		writeRaw(w, markStale(cached))
		return
	}
	var re *RedditError
	switch {
	case errors.As(err, &re) && (re.Status == http.StatusNotFound || re.Status == http.StatusForbidden):
		fail(w, re.Status, err.Error())
	case errors.Is(err, errBusy) || (errors.As(err, &re) && re.Status == http.StatusTooManyRequests):
		w.Header().Set("Retry-After", "30")
		fail(w, http.StatusServiceUnavailable, err.Error())
	default:
		log.Printf("fetch %s: %v", strings.SplitN(key, ":", 2)[0], err)
		fail(w, http.StatusBadGateway, err.Error())
	}
}

// once runs fn for key, sharing its result with the requests for the same key that arrive meanwhile.
func (s *Server) once(key string, fn func() ([]byte, error)) ([]byte, error) {
	s.flightMu.Lock()
	if f, ok := s.flights[key]; ok {
		s.flightMu.Unlock()
		<-f.done
		return f.body, f.err
	}
	f := &flight{done: make(chan struct{})}
	s.flights[key] = f
	s.flightMu.Unlock()
	f.body, f.err = fn()
	close(f.done)
	s.flightMu.Lock()
	delete(s.flights, key)
	s.flightMu.Unlock()
	return f.body, f.err
}

// Refresh keeps the watched feeds fresh, one at a time, with the slots no app request needs.
func (s *Server) Refresh(ctx context.Context) {
	tick := time.NewTicker(15 * time.Second)
	defer tick.Stop()
	prune := time.NewTicker(time.Hour)
	defer prune.Stop()
	s.cache.Prune(7 * 24 * time.Hour)
	for {
		select {
		case <-ctx.Done():
			return
		case <-prune.C:
			s.cache.Prune(7 * 24 * time.Hour)
		case <-tick.C:
			s.refreshOne(ctx)
		}
	}
}

func (s *Server) refreshOne(ctx context.Context) {
	var oldest *FeedKey
	var oldestAt time.Time
	for _, k := range s.watch.List() {
		_, at, ok := s.cache.Get(feedCacheKey(k, ""))
		if ok && time.Since(at) < s.refreshEvery {
			continue
		}
		if oldest == nil || !ok || at.Before(oldestAt) {
			k := k
			oldest, oldestAt = &k, at
			if !ok {
				break
			}
		}
	}
	if oldest == nil {
		return
	}
	key := feedCacheKey(*oldest, "")
	c, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	body, err := s.once(key, func() ([]byte, error) { return s.fetchFeed(c, *oldest, "", true) })
	switch {
	case err == nil:
		s.cache.Put(key, body)
	case errors.Is(err, errBusy):
	default:
		log.Printf("refresh r/%s %s: %v", oldest.Sub, oldest.Sort, err)
	}
}

func markStale(body []byte) []byte {
	if len(body) < 2 || body[0] != '{' {
		return body
	}
	return append([]byte(`{"stale":true,`), body[1:]...)
}

func writeRaw(w http.ResponseWriter, body []byte) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write(body)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (s *statusWriter) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

// logged logs method, route, status and duration: never a header, a key or a content.
func logged(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		h.ServeHTTP(sw, r)
		if r.URL.Path != "/health" {
			log.Printf("%s %s %d %s", r.Method, r.URL.Path, sw.status, time.Since(start).Round(time.Millisecond))
		}
	})
}
