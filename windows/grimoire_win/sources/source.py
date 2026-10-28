"""What every source gives (the same contract as the Android app's Source)."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Protocol

from ..core.model import ChapterInfo, SeriesDetails, SeriesPage, SourceConfig
from .http import Http


@dataclass(frozen=True)
class Images:
    urls: tuple[str, ...]


@dataclass(frozen=True)
class Text:
    """For sources of regular books: the chapter's paragraphs."""
    paragraphs: tuple[str, ...]


class Source(Protocol):
    config: SourceConfig

    def popular(self, page: int) -> SeriesPage: ...
    def latest(self, page: int) -> SeriesPage: ...
    def search(self, query: str, page: int) -> SeriesPage: ...
    def details(self, slug: str) -> SeriesDetails: ...
    def content(self, slug: str, chapter: ChapterInfo) -> Images | Text: ...
    def image_headers(self) -> dict[str, str]: ...
    def series_url(self, slug: str) -> str: ...


@dataclass(frozen=True)
class OptionSpec:
    """A boolean option of a source type, shown as a switch in the source's settings."""
    key: str
    label: str
    hint: str
    default: bool


@dataclass(frozen=True)
class SourceType:
    id: str
    name: str
    lang: str
    description: str
    default_base_url: str
    create: Callable[[SourceConfig, Http], Source]
    options: tuple[OptionSpec, ...] = field(default=())
    has_login: bool = False
