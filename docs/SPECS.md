# Grimoire — specification

A reading app for Android in the personal suite (Synchrotron, Griffonote, Ecoutify, Météorite,
Cerbère): same look, same kit, same release flow. UI in French, code and docs in English.

## 1. Scope

**Now — the "Lecture" section**: manga and webtoons read from *sources* (websites), Mihon-style.
The section is built so a source can also deliver *text* chapters (novels, "regular books"):
the reader has an image mode and a text mode.

**Later**: other sections next to "Lecture" (Reddit, …). The app shell has a list of sections;
the bottom bar only shows up once there are two.

Out of scope for now: loading sources as plugins (Mihon's extension APKs). Source *types* are
built into the app; which sources are added, their address and their options are configured in
the app and stored in a synced file. A new source type comes with an app update.

## 2. Features

| Feature | Behaviour |
|---|---|
| Navigation | Tabs Bibliothèque, Découvrir, Recherche. Paramètres (gear) holds Sources, Historique and the settings. |
| Sources | Paramètres › Sources lists the added sources. "Ajouter une source" picks a type from the built-in catalog; each source has a name, a base URL (sites change domain), options, a WebView login when the site has accounts, and "Tester". Sources can be disabled or removed. |
| Découvrir | Récents or Populaires across every enabled source (pages of each source interleaved) or one source picked with a chip. Covers in a grid, infinite scroll; a failing source shows its error without hiding the others. |
| Search | Searches every enabled source at once, or the one picked; same grid. |
| Series page | Cover, title, author/artist, status, genres, description, "Lire"/"Continuer", add/remove from library, chapter list. Chapters already read are dimmed. |
| Read marks | A chapter is marked read automatically when its last page is reached. Manual: per chapter, "jusqu'ici", or the whole series (read / unread). |
| Library | Series in the library, most recently read first, with the unread count and the last chapter read. Filters: En cours (default), Tout, Terminés. A series is added when reading starts; it can also be added or removed by hand. |
| History | In Paramètres. Last chapters read, grouped by day; tap to resume at the saved page. |
| Reader | Image chapters: "Défilement" (vertical, webtoons) or "Pages" (horizontal, manga, optional right-to-left). Pinch zoom, tap the middle for the menu (chapter, page slider, mode), next/previous chapter. Position saved per chapter. Text chapters: paragraphs with a font size setting. Default mode per series: guessed from the source (webtoon → défilement), changeable and remembered. |
| Downloads | Per chapter, "à partir d'ici", or the whole series. Background job with a notification; Wi-Fi only option. Stored in the app's private storage, never synced. |
| Books (EPUB) | See §8. |
| Offline | No separate tab: the library shows everything, and what cannot be read offline (series or chapters without a download) is greyed out and not tappable. Series pages and chapter lists open from the last saved copy. |
| Sync | The synced folder holds the configuration and the reading state. On open the app asks Synchrotron for a sync, is read-only while it runs (writes wait and are applied after), and re-reads the files. No sync setup inside the app. |
| Updates, settings | The suite kit: quiet update check on open, banner with download and install, Settings with Synchronisation / Mise à jour / À propos sections. |

## 3. Storage

### Synced folder: `Documents/Grimoire/`

Needs "all files access" (asked on first launch). Until granted, the same files live in the app's
private storage and move to the folder once access is given (a file already in the folder wins).
All files are JSON with `format_version: 1`, written atomically (`.tmp` + rename). An unreadable
file is reported and never overwritten.

- `sources.json` — `{"format_version":1,"sources":[{"id","type","name","baseUrl","enabled","options":{…}}]}`
- `library.json` — `{"format_version":1,"items":[{"source","slug","title","cover","kind","added"}]}`
- `progress.json` — per series (key `source:slug`): `read` = map chapter id → time read,
  `last` = `{chapter, page, pages, at}`, `mode` = reader mode, `finished` = time or null.
- `.syncignore` — nothing else should ever be there, but `*.tmp` and the like are excluded anyway.

Chapter ids are the source's own (Poseidon: the chapter number as text, "12", "12.5").

### Private storage (not synced)

- `files/series/<source>/<slug>.json` — last fetched details + chapter list, for offline browsing.
- `getExternalFilesDir("downloads")/<source>/<slug>/<chapter>/0001.webp …` + `chapter.json`
  (title, page count). Deleting a series' downloads removes the folder.
- Cookies (WebView login, anti-bot) in Android's CookieManager.

### Writes during a sync

Every change is an operation on the in-memory state. While a sync runs, operations are kept in a
queue instead of written; when the sync ends the files are re-read and the queued operations are
applied on top, then written. Nothing read during a sync is lost, and nothing synced is overwritten.

## 4. Source model

```kotlin
interface Source {
    val id: String; val name: String; val type: SourceType
    suspend fun popular(page: Int): SeriesPage
    suspend fun latest(page: Int): SeriesPage
    suspend fun search(query: String, page: Int): SeriesPage
    suspend fun details(slug: String): SeriesDetails        // includes the chapter list
    suspend fun content(slug: String, chapter: ChapterInfo): ChapterContent
    fun imageHeaders(): Map<String, String>
}
sealed interface ChapterContent { Images(urls) ; Text(paragraphs) }
```

A `SourceType` (catalog entry) knows its default base URL, its options, whether it has a login,
and builds a `Source` from a `SourceConfig`. Network access goes through one OkHttp client with
Android's cookie store, a mobile Chrome user agent and a per-host pacing (requests to a site are
at least 350 ms apart; images are fetched three at a time at most).

## 5. Poseidon Scans

French scanlation site, mostly manhwa/webtoons. Next.js (App Router) site at
`https://poseidon-scans.net`. No public API documentation; this follows the community Mihon
extension (`keiyoushi/extensions-source`, `src/fr/poseidonscans`, Apache-2.0), which is kept up to
date by its users.

- **Account**: not needed to read. Some recent chapters are *premium* (`isPremium` with a
  `premiumUntil` date, after which they become free). They are hidden by default (option "Afficher
  les chapitres premium"); reading one needs a logged-in premium account, done in the source's
  WebView login, whose cookies the app reuses.
- **Latest**: `GET /api/manga/lastchapters?limit=16&page=N` → `{"data":[{"title","slug"}]}`.
- **Popular**: `GET /` with header `RSC: 1` → React Flight stream containing the list of
  `{id, title, slug}`.
- **Search**: `GET /series?search=<q>&page=N` (HTML): `div.grid a.block.group` → link, `h2` title,
  `img[srcset]` cover; next page when `nav[aria-label=Pagination] a:contains(Suivant)` exists.
- **Cover**: `/api/covers/<slug>.webp`.
- **Details**: `GET /serie/<slug>` (HTML); the flight data inside `self.__next_f.push(...)` scripts
  holds `{title, slug, description, status, author, artist, categories[{name}]}`.
- **Chapters**: `GET /serie/<slug>` with `RSC: 1` → `{isPremiumUser, manga:{slug, chapters:[{number,
  title, createdAt, isPremium, premiumUntil, isVolume}]}}` (dates as `$D<iso>`). The stream can be
  partial on a first hit: retried once with a cache-busting query.
- **Pages**: `GET /serie/<slug>/chapter/<number>` with `RSC: 1` → `{currentChapter:{isPremium,
  premiumUntil}, initialData:{images:[{originalUrl, order}]}, isPremiumUser, sessionStatus}`.
- **Images**: `Accept: image/avif,image/webp,*/*`, `Referer: <baseUrl>/`.
- **Status** words: en cours, terminé, en pause/hiatus, annulé/abandonné.

The React Flight decoding (`self.__next_f` chunks, `<id>:` rows, `T` text rows, `$`-references,
`$D` dates, `$Q`/`$W`/`$L`/`$@`) is a port of keiyoushi's `NextJs.kt` on kotlinx.serialization's
JSON tree, with their fixtures as unit tests.

## 6. Architecture

```
app/src/main/java/app/grimoire/
  GrimoireApp, MainActivity, MainViewModel
  data/       Store (synced files, op queue), Library, Progress, SourcesConfig, SeriesCache, Files
  source/     Source model, catalog, Http (client, pacing, cookies), NextJs, poseidon/PoseidonScans
  download/   Downloads (state), DownloadWorker
  reader/     ReaderScreen (images: pages / scroll, zoom), TextReader, ReaderViewModel
  ui/         Home (Bibliothèque, Découvrir, Recherche), Series, History, Sources, Settings, theme
  suite/      the suite kit (copy)
  sync/       SynchrotronClient (copy)
```

Images through Coil 3 with the shared OkHttp client; a downloaded page is loaded from its file.

## 7. Release

Same as the suite: `.github/workflows/build-apk.yml` builds `assembleRelease` signed with the key
from the `release` environment (`GRIMOIRE_KEYSTORE_BASE64`, `GRIMOIRE_KEYSTORE_PASSWORD`,
`GRIMOIRE_KEY_PASSWORD`, alias `grimoire`), runs the unit tests, and publishes the rolling `latest`
release ("Grimoire build N", `grimoire.apk`). Without the key it compiles and tests but publishes
nothing and fails. The app updates itself from that release (suite kit).

## 8. Books from EPUB files

- **Getting a file in**: "Ouvrir avec Grimoire" on an EPUB (file manager, browser download), an
  EPUB or a link to one shared to Grimoire, an https link ending in `.epub`, or « + » on the home
  screen. The file is copied to private storage (`books/<id>.epub`), never synced.
- **Identity**: the book id is `isbn-<ISBN-13>` from the file's identifiers (declared ISBNs, other
  identifiers holding one, then `dc:source`, often the print edition); ISBN-10s are converted.
  Without ISBN: `id-<hash of the package identifier>`. On import, a library book sharing any ISBN
  with the file gets the file (same edition in another file, or on another phone). Each language
  or edition has its own ISBN, hence its own progress.
- **Description**: looked up online, not taken from the file, without account or key: the BnF
  catalogue (SRU, Dublin Core) and Open Library (data, covers, the work's summary) by ISBN, each
  ISBN of the file in turn; without ISBN, an Open Library search by title and author. Cached per phone (`book-info/<id>.json`); the title, cover
  URL and the edition's ISBNs go to `library.json` so every phone shows them. The EPUB's own cover
  is only a fallback on the phone that has the file.
- **Progress**: in `progress.json` under `files:<id>`: `last.chapter` = spine index, `last.page` =
  position in it in thousandths, `last.pages` = spine length; `read` holds `book` once finished
  (automatically at the end, or by hand).
- **Missing file**: a book in the library whose file is not on this phone shows "fichier absent",
  its ISBN(s) to copy, and "Choisir le fichier".
- **Reader**: WebView fed from the zip (no network), text size and page colours forced (Auto,
  Clair, Sombre, Papier, apart from the app's theme), "Pages" (CSS columns sized in pixels, swipe
  or side taps) or "Défilement", table of contents, previous/next part.

