# Grimoire

Android reader for manga and webtoons (and, later, regular books), built on *sources* the way
[Mihon](https://mihon.app) is: each source is a website the app knows how to read, added and
configured in the app. First source: **Poseidon Scans** (French scanlation site).

Part of the personal suite (Synchrotron · Griffonote · Ecoutify · Météorite · Cerbère): same look,
same self-update, same Proton Drive sync through Synchrotron.

## Features

- **Bibliothèque**: series being read, with unread counts and the next chapter to read. Offline,
  only what is downloaded can be opened; the rest is greyed out.
- **Découvrir**: latest or popular series across every enabled source (or one), interleaved.
- **Recherche**: search everywhere, or in one source.
- **Historique** (in Paramètres): last chapters read, by day.
- **Lecteur**: vertical scroll (webtoons) or pages (manga, optional right-to-left), zoom, keeps the
  page, marks a chapter read when its last page is reached. Text sources get a text reader.
- Manual marking: one chapter, a selection, everything up to a chapter, or the whole series.
- Offline downloads (Wi-Fi only by default), stored in the app's private storage: never synced.
- **Books (EPUB)**: open an EPUB from a file manager, a browser download or a shared link, or
  with « + ». The book is identified by its ISBN; title, authors, summary, language and cover
  come from the BnF catalogue and Open Library rather than from the file. Reader in pages or scroll, light / dark / paper colours,
  table of contents, text size; position and "read" are synced, the file is not: another phone
  shows the book with its ISBN until its EPUB is opened there too.

## Data

`Documents/Grimoire/` — add this folder in Synchrotron to sync it with Proton Drive:

| File | Content |
|---|---|
| `sources.json` | configured sources (type, name, address, options) |
| `library.json` | series in the library |
| `progress.json` | per series or book: chapters read, last position, reader mode |
| `.syncignore` | created by the app; keeps temporary files out of the sync |

Every write is atomic; an unreadable file is reported and never overwritten. While Synchrotron
syncs (on each opening), changes wait in memory and are applied on top of what it brought.

Private (never synced): series cache, downloaded chapters, EPUB files and their online
descriptions, image cache, site cookies.

## Sources

Built in (`source/Catalog`); adding a new *kind* of source means an app update, but any number
of configured instances can exist (another address if the site moves, options, login).
Poseidon Scans needs no account; premium chapters need a premium login, done in a WebView from
the source's settings. Protocol details: [docs/SPECS.md](docs/SPECS.md).

## Reddit (Android)

A « Lecture | Reddit » bar appears at the bottom of the home screen once Reddit is set up in
Paramètres › Reddit. The app never talks to Reddit: it reads the JSON of the self-hosted proxy
[KrustyLeBot/RedditProxy](https://github.com/KrustyLeBot/RedditProxy), which caches, keeps the
followed feeds fresh and respects Reddit's rate limit.

- **Settings** (device only, encrypted with an Android Keystore key, never in the synced folder):
  proxy address (an example address by default: the real one is entered on the phone), optional LAN
  address used whenever it answers, headers sent with every request (`Nom: valeur` lines, e.g.
  `Authorization: Bearer <key>` and the Cloudflare service token), subreddits. Saved only once the
  proxy accepted them (`POST /watch`).
- **Feed**: subreddit and sort chips (Populaires, Récents, Top with a period, En hausse), endless
  scrolling with `after`. **Thread**: post (HTML, image, swipeable gallery, HLS video or GIF as MP4
  with media3), comments depth first with coloured indentation, folded by a tap, OP and moderators
  marked, « Voir N autres réponses » (`/more`) and « Continuer la discussion » (sub-thread).
- **Watch**: at each start the app sends its subreddits (hot and new) to the proxy, which refreshes
  them in the background; a proxy that lost its list gets it back from the apps.
- **Offline**: every feed page and thread fetched is kept in the cache folder; when the proxy cannot
  be reached the last copy is shown with a « Hors ligne » banner.

Code: `app/src/main/java/app/grimoire/reddit/` (config, client, models, state) and
`ui/Reddit*.kt`.

## Windows app

`windows/`: the same app for the computer (Python, PySide6 with Qt WebEngine, one `Grimoire.exe`),
with the same features and the same files. Pick the Grimoire folder that the Proton Drive client
syncs (`Documents/Grimoire` on the phone): library, progress, sources and web searches are shared;
the file is re-read whenever Proton brings another device's version. Downloads, EPUB files and
caches stay in `%LOCALAPPDATA%\Grimoire`. EPUBs open with "Ouvrir avec", by drag and drop or with
« + »; a second launch hands its file to the open window. Self-update from the same `latest`
release (the `.exe` asset). Tests: `cd windows && python -m pytest tests -q`.

## Build & release

CI only (`.github/workflows/build-apk.yml`): tests, signed APK, Windows exe, rolling `latest`
release carrying both. Signing
key and secrets: [docs/RELEASE.md](docs/RELEASE.md). Install:
<https://github.com/KrustyLeBot/Grimoire/releases/download/latest/grimoire.apk> (Android),
<https://github.com/KrustyLeBot/Grimoire/releases/download/latest/Grimoire.exe> (Windows)
