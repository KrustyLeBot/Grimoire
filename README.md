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

## Data

`Documents/Grimoire/` — add this folder in Synchrotron to sync it with Proton Drive:

| File | Content |
|---|---|
| `sources.json` | configured sources (type, name, address, options) |
| `library.json` | series in the library |
| `progress.json` | per series: chapters read, last position, reader mode |
| `.syncignore` | created by the app; keeps temporary files out of the sync |

Every write is atomic; an unreadable file is reported and never overwritten. While Synchrotron
syncs (on each opening), changes wait in memory and are applied on top of what it brought.

Private (never synced): series cache, downloaded chapters, image cache, site cookies.

## Sources

Built in (`source/Catalog`); adding a new *kind* of source means an app update, but any number
of configured instances can exist (another address if the site moves, options, login).
Poseidon Scans needs no account; premium chapters need a premium login, done in a WebView from
the source's settings. Protocol details: [docs/SPECS.md](docs/SPECS.md).

## Build & release

CI only (`.github/workflows/build-apk.yml`): tests, signed APK, rolling `latest` release. Signing
key and secrets: [docs/RELEASE.md](docs/RELEASE.md). Install:
<https://github.com/KrustyLeBot/Grimoire/releases/download/latest/grimoire.apk>
