package main

import (
	"container/list"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Cache keeps cleaned answers in memory (a bounded LRU) and on disk under /data/cache, so feeds and
// opened threads stay readable after a restart or while Reddit refuses requests.
type Cache struct {
	dir   string
	max   int
	mu    sync.Mutex
	items map[string]*list.Element
	order *list.List
}

type entry struct {
	key     string
	body    []byte
	fetched time.Time
}

func NewCache(dir string, max int) *Cache {
	if dir != "" {
		_ = os.MkdirAll(dir, 0o755)
	}
	return &Cache{dir: dir, max: max, items: map[string]*list.Element{}, order: list.New()}
}

func (c *Cache) file(key string) string {
	h := sha1.Sum([]byte(key))
	return filepath.Join(c.dir, hex.EncodeToString(h[:])+".json")
}

// Get returns the cached body and when it was fetched; ok false when nothing is cached.
func (c *Cache) Get(key string) ([]byte, time.Time, bool) {
	c.mu.Lock()
	if el, ok := c.items[key]; ok {
		c.order.MoveToFront(el)
		e := el.Value.(*entry)
		c.mu.Unlock()
		return e.body, e.fetched, true
	}
	c.mu.Unlock()
	if c.dir == "" {
		return nil, time.Time{}, false
	}
	f := c.file(key)
	body, err := os.ReadFile(f)
	if err != nil {
		return nil, time.Time{}, false
	}
	st, err := os.Stat(f)
	if err != nil {
		return nil, time.Time{}, false
	}
	c.remember(key, body, st.ModTime())
	return body, st.ModTime(), true
}

func (c *Cache) Put(key string, body []byte) {
	now := time.Now()
	c.remember(key, body, now)
	if c.dir != "" {
		if err := writeAtomic(c.file(key), body); err != nil {
			log.Printf("cache: %v", err)
		}
	}
}

func (c *Cache) remember(key string, body []byte, at time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if el, ok := c.items[key]; ok {
		el.Value = &entry{key, body, at}
		c.order.MoveToFront(el)
		return
	}
	c.items[key] = c.order.PushFront(&entry{key, body, at})
	for c.order.Len() > c.max {
		old := c.order.Back()
		c.order.Remove(old)
		delete(c.items, old.Value.(*entry).key)
	}
}

// Prune deletes the cached files older than maxAge.
func (c *Cache) Prune(maxAge time.Duration) {
	if c.dir == "" {
		return
	}
	entries, _ := os.ReadDir(c.dir)
	cutoff := time.Now().Add(-maxAge)
	for _, e := range entries {
		if info, err := e.Info(); err == nil && info.ModTime().Before(cutoff) {
			_ = os.Remove(filepath.Join(c.dir, e.Name()))
		}
	}
}

func writeAtomic(path string, body []byte) error {
	tmp := path + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	_, err = f.Write(body)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(tmp, path)
	}
	if err != nil {
		_ = os.Remove(tmp)
	}
	return err
}

// FeedKey identifies one first page that the apps want kept fresh.
type FeedKey struct {
	Sub  string `json:"sub"`
	Sort string `json:"sort"`
	Time string `json:"t,omitempty"`
}

// Watch is the set of feeds the apps asked for, with the last time an app mentioned each. The apps
// send their list at every start: when this file is lost, the list comes back by itself.
type Watch struct {
	path   string
	expiry time.Duration
	mu     sync.Mutex
	feeds  map[FeedKey]time.Time
}

func NewWatch(path string, expiry time.Duration) *Watch {
	w := &Watch{path: path, expiry: expiry, feeds: map[FeedKey]time.Time{}}
	if path == "" {
		return w
	}
	var saved []struct {
		FeedKey
		Seen int64 `json:"seen"`
	}
	if b, err := os.ReadFile(path); err == nil && json.Unmarshal(b, &saved) == nil {
		for _, s := range saved {
			w.feeds[s.FeedKey] = time.Unix(s.Seen, 0)
		}
	}
	return w
}

func (w *Watch) Touch(keys ...FeedKey) {
	w.mu.Lock()
	now := time.Now()
	changed := false
	for _, k := range keys {
		if last, ok := w.feeds[k]; !ok || now.Sub(last) > time.Hour {
			changed = true
		}
		w.feeds[k] = now
	}
	w.mu.Unlock()
	if changed {
		w.save()
	}
}

// List returns the feeds still wanted, dropping those no app mentioned within the expiry.
func (w *Watch) List() []FeedKey {
	w.mu.Lock()
	cutoff := time.Now().Add(-w.expiry)
	var out []FeedKey
	dropped := false
	for k, seen := range w.feeds {
		if seen.Before(cutoff) {
			delete(w.feeds, k)
			dropped = true
			continue
		}
		out = append(out, k)
	}
	w.mu.Unlock()
	if dropped {
		w.save()
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Sub+out[i].Sort < out[j].Sub+out[j].Sort })
	return out
}

func (w *Watch) save() {
	if w.path == "" {
		return
	}
	w.mu.Lock()
	type saved struct {
		FeedKey
		Seen int64 `json:"seen"`
	}
	out := make([]saved, 0, len(w.feeds))
	for k, t := range w.feeds {
		out = append(out, saved{k, t.Unix()})
	}
	w.mu.Unlock()
	b, _ := json.Marshal(out)
	if err := writeAtomic(w.path, b); err != nil {
		log.Printf("watch: %v", err)
	}
}
