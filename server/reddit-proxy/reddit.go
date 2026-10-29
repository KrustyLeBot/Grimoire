package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	errBadShape = errors.New("réponse Reddit inattendue")
	// errBusy: the request would wait too long for its turn (Reddit's rate limit); a cached copy is served instead.
	errBusy = errors.New("Reddit limite les requêtes : réessaie dans un instant")
)

// RedditError is a non-200 answer from Reddit.
type RedditError struct{ Status int }

func (e *RedditError) Error() string {
	switch e.Status {
	case http.StatusNotFound:
		return "introuvable sur Reddit"
	case http.StatusForbidden:
		return "privé, banni ou bloqué par Reddit"
	case http.StatusTooManyRequests:
		return "Reddit limite les requêtes : réessaie plus tard"
	}
	return fmt.Sprintf("Reddit a répondu HTTP %d", e.Status)
}

// Limiter spaces the requests to Reddit. Requests from the apps take the next free slot (they are
// what someone is waiting for); background refreshes only use a slot nobody asked for.
type Limiter struct {
	mu       sync.Mutex
	interval time.Duration
	next     time.Time
	waiting  int
	maxWait  time.Duration
}

func NewLimiter(perMinute int, maxWait time.Duration) *Limiter {
	if perMinute < 1 {
		perMinute = 1
	}
	return &Limiter{interval: time.Minute / time.Duration(perMinute), maxWait: maxWait}
}

// Wait takes the next slot for a request someone waits for; errBusy when it is too far away.
func (l *Limiter) Wait(ctx context.Context) error {
	l.mu.Lock()
	now := time.Now()
	at := l.next
	if at.Before(now) {
		at = now
	}
	if at.Sub(now) > l.maxWait {
		l.mu.Unlock()
		return errBusy
	}
	l.next = at.Add(l.interval)
	l.waiting++
	l.mu.Unlock()
	defer func() {
		l.mu.Lock()
		l.waiting--
		l.mu.Unlock()
	}()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(time.Until(at)):
		return nil
	}
}

// TryIdle takes a slot only if it is free right now and nobody is waiting: background work.
func (l *Limiter) TryIdle() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	// Keep one slot of headroom so that a request from an app never waits behind a refresh.
	if l.waiting > 0 || l.next.After(now.Add(-l.interval)) {
		return false
	}
	l.next = now.Add(l.interval)
	return true
}

// PauseUntil moves every request after t (Reddit asked to slow down).
func (l *Limiter) PauseUntil(t time.Time) {
	l.mu.Lock()
	if t.After(l.next) {
		l.next = t
	}
	l.mu.Unlock()
}

// Reddit fetches JSON from Reddit: the public .json endpoints, or the OAuth API when an app is
// configured (application-only token: no user account, more requests allowed).
type Reddit struct {
	base      string // https://www.reddit.com, or https://oauth.reddit.com with OAuth
	tokenURL  string
	userAgent string
	clientID  string
	secret    string
	http      *http.Client
	limiter   *Limiter

	mu       sync.Mutex
	token    string
	tokenEnd time.Time
}

func (r *Reddit) oauth() bool { return r.clientID != "" }

func (r *Reddit) bearer(ctx context.Context) (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.token != "" && time.Now().Before(r.tokenEnd) {
		return r.token, nil
	}
	form := url.Values{"grant_type": {"client_credentials"}}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, r.tokenURL, strings.NewReader(form.Encode()))
	req.SetBasicAuth(r.clientID, r.secret)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("User-Agent", r.userAgent)
	resp, err := r.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("jeton OAuth Reddit refusé (HTTP %d)", resp.StatusCode)
	}
	var tok struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&tok); err != nil || tok.AccessToken == "" {
		return "", errors.New("jeton OAuth Reddit illisible")
	}
	r.token = tok.AccessToken
	r.tokenEnd = time.Now().Add(time.Duration(tok.ExpiresIn)*time.Second - time.Minute)
	return r.token, nil
}

// Get fetches path (with its query) once a slot is free. background requests never wait: they
// report false when no slot is free.
func (r *Reddit) Get(ctx context.Context, path string, query url.Values, background bool) ([]byte, error) {
	if background {
		if !r.limiter.TryIdle() {
			return nil, errBusy
		}
	} else if err := r.limiter.Wait(ctx); err != nil {
		return nil, err
	}
	if query == nil {
		query = url.Values{}
	}
	query.Set("raw_json", "1") // no HTML escaping of &, <, > in the strings
	p := path
	if !r.oauth() && !strings.HasSuffix(p, ".json") {
		p += ".json"
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, r.base+p+"?"+query.Encode(), nil)
	req.Header.Set("User-Agent", r.userAgent)
	req.Header.Set("Accept", "application/json")
	if r.oauth() {
		tok, err := r.bearer(ctx)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Authorization", "Bearer "+tok)
	}
	resp, err := r.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("Reddit injoignable : %w", err)
	}
	defer resp.Body.Close()
	r.obeyLimits(resp)
	if resp.StatusCode != http.StatusOK {
		if resp.StatusCode == http.StatusUnauthorized && r.oauth() {
			r.mu.Lock()
			r.token = ""
			r.mu.Unlock()
		}
		return nil, &RedditError{Status: resp.StatusCode}
	}
	if !strings.Contains(resp.Header.Get("Content-Type"), "json") {
		// Reddit's block page or a login wall, served as HTML with a 200.
		return nil, &RedditError{Status: http.StatusForbidden}
	}
	return io.ReadAll(io.LimitReader(resp.Body, 16<<20))
}

// obeyLimits reads Reddit's rate-limit headers: when the window is used up, or on a 429, nothing
// is sent until the reset.
func (r *Reddit) obeyLimits(resp *http.Response) {
	reset := func() time.Duration {
		for _, h := range []string{"Retry-After", "X-Ratelimit-Reset"} {
			if s, err := strconv.ParseFloat(strings.TrimSpace(resp.Header.Get(h)), 64); err == nil && s > 0 {
				return time.Duration(s * float64(time.Second))
			}
		}
		return time.Minute
	}
	if resp.StatusCode == http.StatusTooManyRequests {
		d := reset()
		log.Printf("reddit: 429, pause %s", d.Round(time.Second))
		r.limiter.PauseUntil(time.Now().Add(d))
		return
	}
	if s := resp.Header.Get("X-Ratelimit-Remaining"); s != "" {
		if left, err := strconv.ParseFloat(s, 64); err == nil && left < 1 {
			r.limiter.PauseUntil(time.Now().Add(reset()))
		}
	}
}
