// reddit-proxy sits between Grimoire and Reddit: one small, cached, rate-limited JSON API for every
// device, so the apps never talk to Reddit directly and never hold Reddit credentials.
package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func env(name, def string) string {
	if v := strings.TrimSpace(os.Getenv(name)); v != "" {
		return v
	}
	return def
}

func envDuration(name string, def time.Duration) time.Duration {
	if d, err := time.ParseDuration(env(name, "")); err == nil && d > 0 {
		return d
	}
	return def
}

func main() {
	health := flag.Bool("health", false, "check that the running proxy answers (Docker healthcheck), then exit")
	flag.Parse()
	listen := env("LISTEN", ":8080")
	if *health {
		os.Exit(checkHealth(listen))
	}

	var keys [][]byte
	for _, k := range strings.Split(env("API_KEYS", ""), ",") {
		if k = strings.TrimSpace(k); k != "" {
			keys = append(keys, []byte(k))
		}
	}
	if len(keys) == 0 {
		log.Fatal("API_KEYS is empty: set at least one key (one per device), the proxy never runs open")
	}

	clientID, secret := env("REDDIT_CLIENT_ID", ""), env("REDDIT_CLIENT_SECRET", "")
	base, rate := env("REDDIT_BASE", "https://www.reddit.com"), 8
	if clientID != "" {
		base, rate = env("REDDIT_BASE", "https://oauth.reddit.com"), 60
	}
	if n, err := strconv.Atoi(env("RATE_PER_MIN", "")); err == nil && n > 0 {
		rate = n
	}
	data := env("DATA_DIR", "/data")
	srv := &Server{
		reddit: &Reddit{
			base:      strings.TrimRight(base, "/"),
			tokenURL:  env("REDDIT_TOKEN_URL", "https://www.reddit.com/api/v1/access_token"),
			userAgent: env("USER_AGENT", "server:grimoire-reddit-proxy:1.0 (personal reader)"),
			clientID:  clientID,
			secret:    secret,
			http:      &http.Client{Timeout: 30 * time.Second},
			limiter:   NewLimiter(rate, 20*time.Second),
		},
		cache:        NewCache(filepath.Join(data, "cache"), 300),
		watch:        NewWatch(filepath.Join(data, "watch.json"), envDuration("WATCH_EXPIRY", 14*24*time.Hour)),
		keys:         keys,
		feedTTL:      envDuration("FEED_TTL", 2*time.Minute),
		threadTTL:    envDuration("THREAD_TTL", 5*time.Minute),
		refreshEvery: envDuration("REFRESH_EVERY", 15*time.Minute),
		flights:      map[string]*flight{},
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go srv.Refresh(ctx)

	server := &http.Server{
		Addr:              listen,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		WriteTimeout:      90 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shut, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shut)
	}()
	mode := "public .json"
	if clientID != "" {
		mode = "OAuth"
	}
	log.Printf("reddit-proxy on %s (%s, %d requests/min, %d keys)", listen, mode, rate, len(keys))
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func checkHealth(listen string) int {
	addr := listen
	if strings.HasPrefix(addr, ":") {
		addr = "127.0.0.1" + addr
	}
	c := http.Client{Timeout: 3 * time.Second}
	resp, err := c.Get("http://" + addr + "/health")
	if err != nil {
		return 1
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
