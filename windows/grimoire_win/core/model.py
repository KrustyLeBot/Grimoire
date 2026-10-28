"""The synced data, the same as the Android app's (docs/SPECS.md §3)."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from urllib.parse import quote


class Kind(Enum):
    """How chapters of a series are best read: the reader's default mode follows it."""
    MANGA = "manga"
    WEBTOON = "webtoon"
    NOVEL = "novel"

    @staticmethod
    def of(raw: str | None) -> "Kind":
        return next((k for k in Kind if k.value == raw), Kind.WEBTOON)


def series_key(source: str, slug: str) -> str:
    return f"{source}:{slug}"


@dataclass(frozen=True)
class SourceConfig:
    id: str
    type: str
    name: str
    base_url: str
    enabled: bool = True
    options: tuple[tuple[str, str], ...] = ()

    def option(self, key: str) -> str | None:
        return dict(self.options).get(key)


@dataclass(frozen=True)
class WebSearch:
    """A website to search on: [url] holds `{q}` where the search goes."""
    name: str
    url: str

    PLACEHOLDER = "{q}"

    def url_for(self, query: str) -> str:
        return self.url.replace(self.PLACEHOLDER, quote(query.strip(), safe=""))


@dataclass(frozen=True)
class LibraryItem:
    """A series in the library; a book from a file has the source "files" and its book id as slug."""
    source: str
    slug: str
    title: str
    cover: str | None
    kind: Kind
    added: int
    isbns: tuple[str, ...] = ()
    link: str | None = None
    page: str | None = None

    @property
    def key(self) -> str:
        return series_key(self.source, self.slug)


@dataclass(frozen=True)
class LastRead:
    """Where reading stopped. For a book: chapter = spine index, page = thousandths, pages = spine length."""
    chapter: str
    chapter_name: str
    page: int
    pages: int
    at: int


@dataclass(frozen=True)
class SeriesProgress:
    read: tuple[tuple[str, int], ...] = ()
    last: LastRead | None = None
    mode: str | None = None
    pages: tuple[tuple[str, int], ...] = ()

    @property
    def read_ids(self) -> dict[str, int]:
        return dict(self.read)

    @property
    def page_of(self) -> dict[str, int]:
        return dict(self.pages)


@dataclass(frozen=True)
class AppData:
    sources: tuple[SourceConfig, ...] = ()
    web_searches: tuple[WebSearch, ...] = ()
    library: tuple[LibraryItem, ...] = ()
    progress: tuple[tuple[str, SeriesProgress], ...] = ()

    def progress_of(self, key: str) -> SeriesProgress:
        return dict(self.progress).get(key) or SeriesProgress()

    def item(self, key: str) -> LibraryItem | None:
        return next((i for i in self.library if i.key == key), None)

    def source(self, source_id: str) -> SourceConfig | None:
        return next((s for s in self.sources if s.id == source_id), None)


# ---- what sources give ----

@dataclass(frozen=True)
class SeriesSummary:
    slug: str
    title: str
    cover: str | None = None


@dataclass(frozen=True)
class SeriesPage:
    items: tuple[SeriesSummary, ...]
    has_next: bool


@dataclass(frozen=True)
class ChapterInfo:
    """id is the source's own chapter id (Poseidon: the number as text); locked = premium, not readable."""
    id: str
    name: str
    number: float
    date: int
    locked: bool = False


@dataclass(frozen=True)
class SeriesDetails:
    slug: str
    title: str
    cover: str | None = None
    author: str | None = None
    artist: str | None = None
    status: str | None = None
    genres: tuple[str, ...] = ()
    description: str | None = None
    kind: Kind = Kind.WEBTOON
    chapters: tuple[ChapterInfo, ...] = field(default=())  # newest first
