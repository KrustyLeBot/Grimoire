"""The changes the app makes, as pure functions of the state (the same as the Android app's Ops)."""
from __future__ import annotations

import time
from dataclasses import replace
from typing import Callable, Iterable

from .model import (AppData, ChapterInfo, LastRead, LibraryItem, SeriesDetails, SeriesProgress, SourceConfig,
                    WebSearch, series_key)

Op = Callable[[AppData], AppData]


def now_ms() -> int:
    return int(time.time() * 1000)


def _with_progress(d: AppData, key: str, change: Callable[[SeriesProgress], SeriesProgress]) -> AppData:
    items = dict(d.progress)
    items[key] = change(items.get(key) or SeriesProgress())
    return replace(d, progress=tuple(items.items()))


def add_to_library(source: str, details: SeriesDetails, now: int | None = None) -> Op:
    def op(d: AppData) -> AppData:
        key = series_key(source, details.slug)
        if d.item(key):
            return d
        return replace(d, library=d.library + (LibraryItem(source, details.slug, details.title, details.cover, details.kind,
                                                            now or now_ms()),))
    return op


def add_book(item: LibraryItem) -> Op:
    """A book from a file; an existing entry gets the ISBNs it lacked."""
    def op(d: AppData) -> AppData:
        if not d.item(item.key):
            return replace(d, library=d.library + (item,))
        return replace(d, library=tuple(
            replace(i, isbns=tuple(dict.fromkeys(i.isbns + item.isbns)), link=item.link or i.link, page=item.page or i.page)
            if i.key == item.key else i for i in d.library))
    return op


def describe_book(key: str, title: str, cover: str | None, isbns: Iterable[str]) -> Op:
    def op(d: AppData) -> AppData:
        return replace(d, library=tuple(
            replace(i, title=title, cover=cover or i.cover, isbns=tuple(dict.fromkeys(i.isbns + tuple(isbns))))
            if i.key == key else i for i in d.library))
    return op


def describe_series(key: str, title: str, cover: str | None) -> Op:
    return lambda d: replace(d, library=tuple(replace(i, title=title, cover=cover or i.cover) if i.key == key else i
                                              for i in d.library))


def book_position(key: str, index: int, title: str, permille: int, count: int, now: int | None = None) -> Op:
    return lambda d: _with_progress(d, key, lambda p: replace(p, last=LastRead(str(index), title, permille, count, now or now_ms())))


def remove_from_library(key: str) -> Op:
    return lambda d: replace(d, library=tuple(i for i in d.library if i.key != key))


def position(key: str, chapter: ChapterInfo, page: int, pages: int, now: int | None = None) -> Op:
    """Where the reader is: remembered per chapter, and as the series' last read."""
    def change(p: SeriesProgress) -> SeriesProgress:
        page_of = p.page_of
        if chapter.id in p.read_ids:
            page_of.pop(chapter.id, None)
        else:
            page_of[chapter.id] = page
        return replace(p, last=LastRead(chapter.id, chapter.name, page, pages, now or now_ms()), pages=tuple(page_of.items()))
    return lambda d: _with_progress(d, key, change)


def mark_read(key: str, chapter_ids: Iterable[str], now: int | None = None) -> Op:
    ids = list(chapter_ids)

    def change(p: SeriesProgress) -> SeriesProgress:
        read = p.read_ids
        for i in ids:
            read.setdefault(i, now or now_ms())
        return replace(p, read=tuple(read.items()), pages=tuple((k, v) for k, v in p.pages if k not in ids))
    return lambda d: _with_progress(d, key, change)


def mark_unread(key: str, chapter_ids: Iterable[str]) -> Op:
    ids = set(chapter_ids)
    return lambda d: _with_progress(d, key, lambda p: replace(p, read=tuple((k, v) for k, v in p.read if k not in ids)))


def set_mode(key: str, mode: str) -> Op:
    return lambda d: _with_progress(d, key, lambda p: replace(p, mode=mode))


def save_source(config: SourceConfig) -> Op:
    def op(d: AppData) -> AppData:
        if d.source(config.id):
            return replace(d, sources=tuple(config if s.id == config.id else s for s in d.sources))
        return replace(d, sources=d.sources + (config,))
    return op


def remove_source(source_id: str) -> Op:
    return lambda d: replace(d, sources=tuple(s for s in d.sources if s.id != source_id))


def save_web_search(index: int | None, search: WebSearch) -> Op:
    def op(d: AppData) -> AppData:
        items = list(d.web_searches)
        if index is None or not 0 <= index < len(items):
            items.append(search)
        else:
            items[index] = search
        return replace(d, web_searches=tuple(items))
    return op


def remove_web_search(index: int) -> Op:
    return lambda d: replace(d, web_searches=tuple(w for i, w in enumerate(d.web_searches) if i != index))
