# reddit-proxy

A small service on the NAS between Grimoire and Reddit. Every device asks it; it asks Reddit, caches
the answers, keeps the feeds the apps follow fresh in the background, and never exceeds Reddit's rate
limit. Go, standard library only: a 6.7 MB static binary on an empty image, about 8 MB of RAM.

- **Source**: Reddit's public `.json` endpoints (about 10 requests/min per IP, read only). With
  `REDDIT_CLIENT_ID` / `REDDIT_CLIENT_SECRET` (an approved OAuth app) it switches to the OAuth API,
  application-only, 60 requests/min. Reddit credentials live only in the proxy's `.env`.
- **Rate**: requests are spaced (`RATE_PER_MIN`, default 8). An app's request takes the next slot; a
  background refresh only uses a slot nobody needs. `X-Ratelimit-Remaining` / `-Reset` and `429` /
  `Retry-After` pause everything until the reset. A request that would wait more than 20 s gets a 503.
- **Cache**: feeds 2 min, threads 5 min, shared by all devices; in memory (300 entries) and on disk
  (`/data/cache`, kept 7 days). When Reddit fails, the last copy is served with `"stale": true`.
- **Watch**: opening a feed's first page, or `POST /watch`, adds it to `/data/watch.json`; watched
  feeds are refreshed every 15 min. The apps send their list at each start, so a lost file refills
  itself; a feed nobody mentioned for 14 days is dropped.
- **Access**: every route but `/health` needs `Authorization: Bearer <key>` or `X-Api-Key: <key>`, one
  of `API_KEYS` (one per device). Logs hold method, route, status and duration only.

## API

| Route | Answer |
|---|---|
| `GET /health` | `{"ok":true,"watched":N,"oauth":false}` (no key: Docker and Kuma) |
| `GET /feed/<sub>?sort=hot\|new\|top\|rising\|controversial&t=day…&after=t3_x` | `{sub, sort, posts[], after, fetched, stale?}`; `<sub>` may be `a+b` |
| `GET /thread/<id>?sort=best\|top\|new\|controversial\|old\|qa&comment=<id>` | `{post, comments[], fetched}`; `comment` opens a sub-thread |
| `GET /more/<id>?children=a,b,c&sort=` | `{comments[]}`: replies Reddit left out (up to 100 ids) |
| `POST /watch` `{"feeds":[{"sub":"france","sort":"hot"},{"sub":"x","sort":"top","t":"week"}]}` | `{"watched":N}` |

`?refresh=1` bypasses a fresh cache entry. Errors: `{"error":"…"}` with 400 (invalid input), 401
(key), 403/404 (from Reddit), 502 (Reddit unreachable), 503 + `Retry-After` (rate limited).

A post: `id, name, sub, title, author, flair, flairColor, score, ratio, comments, created, edited,
stickied, nsfw, spoiler, locked, text (Markdown), html, url, domain, permalink, thumb, media`, where
`media.kind` is `text`, `image`, `gallery` (`images[]` with `url, w, h, caption, mp4`), `video`
(`video.hls`, `video.mp4` without sound, `w, h, duration`) or `link`. Comments come flat, depth first:
`{kind:"comment", id, parent, depth, author, flair, text, html, score, scoreHidden, created, edited,
op, mod, stickied, collapsed, deleted}` or `{kind:"more", id, parent, depth, count, children[]}`
(load with `/more`) / `{kind:"more", continueAt}` (open `/thread/<id>?comment=<continueAt>`).

## Build, test, deploy

```
go test -race ./...                       # against a fake Reddit (testdata/)
docker build -t grimoire-reddit-proxy .   # what the CI does
```

Every push to `main` touching `server/reddit-proxy/` tests and publishes
`ghcr.io/krustylebot/grimoire-reddit-proxy:latest` (and `:<commit>`) for amd64
(`.github/workflows/reddit-proxy.yml`). Deployment on the NAS: `deploy/docker-compose.yml` and
`deploy/.env.example`.
