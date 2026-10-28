"""What the screens do with the shared state (the Android app's MainViewModel)."""
from __future__ import annotations

import os
import tempfile
import time as _time
from dataclasses import dataclass as _dataclass
from pathlib import Path

from PySide6.QtCore import QObject, Signal

from .books import store as _books
from .books.epub import Epub as _Epub
from .context import App
from .core import ops as _ops
from .core.model import ChapterInfo, Kind, LibraryItem, SeriesDetails, series_key
from .services.async_util import run_async
from .sources.http import describe

LIBRARY_CHECK_MS = 6 * 3600 * 1000


@_dataclass
class SeriesView:
    details: SeriesDetails | None = None
    loading: bool = False
    error: str | None = None


class Controller(QObject):
    series_changed = Signal(str)  # series key
    book_imported = Signal(str)  # book id
    book_info_changed = Signal(str)
    importing_changed = Signal(bool)
    refreshing_changed = Signal(bool)

    def __init__(self, app: App):
        super().__init__()
        self.app = app
        self.series: dict[str, SeriesView] = {}
        self.book_info: dict[str, object] = {}
        self.book_lookup: dict[str, str] = {}
        self.importing = False
        self.refreshing = False

    # ---- library ----

    def progress(self, key: str):
        return self.app.data.progress_of(key)

    def cached_chapters(self, source: str, slug: str) -> tuple[ChapterInfo, ...]:
        view = self.series.get(series_key(source, slug))
        details = view.details if view and view.details else self.app.cache.load(source, slug)
        return details.chapters if details else ()

    def unread(self, item: LibraryItem) -> int:
        read = self.progress(item.key).read_ids
        if item.source == _books.SOURCE:
            return 0 if _books.READ in read else 1
        return sum(1 for c in self.cached_chapters(item.source, item.slug) if not c.locked and c.id not in read)

    def refresh_library(self, force: bool = True) -> None:
        now = int(_time.time() * 1000)
        if self.refreshing or (not force and now - self.app.config.library_checked_at < LIBRARY_CHECK_MS):
            return
        items = sorted((i for i in self.app.data.library if i.source != _books.SOURCE),
                       key=lambda i: -((self.progress(i.key).last.at if self.progress(i.key).last else 0) or i.added))
        if not items:
            return
        self.refreshing = True
        self.refreshing_changed.emit(True)
        remaining = [len(items)]

        def one_done(_key):
            remaining[0] -= 1
            if remaining[0] == 0:
                self.refreshing = False
                self.app.config.library_checked_at = int(_time.time() * 1000)
                self.refreshing_changed.emit(False)
        for i in items:
            self.load_series(i.source, i.slug, force=True, done=one_done)

    # ---- series ----

    def view(self, source: str, slug: str) -> SeriesView:
        key = series_key(source, slug)
        if key not in self.series:
            self.series[key] = SeriesView(self.app.cache.load(source, slug))
        return self.series[key]

    def load_series(self, source: str, slug: str, force: bool = False, done=None) -> None:
        key = series_key(source, slug)
        view = self.view(source, slug)
        if (view.details and not force) or view.loading:
            if done:
                done(key)
            return
        view.loading, view.error = True, None
        self.series_changed.emit(key)

        def fetch():
            src = self.app.sources.get(source)
            if src is None:
                raise OSError("Source introuvable : ajoute-la dans Paramètres › Sources")
            return src.details(slug)

        def ok(details: SeriesDetails):
            self.app.cache.save(source, details)
            # The library keeps the title and cover up to date.
            self.app.store.update(_ops.describe_series(key, details.title, details.cover))
            self.series[key] = SeriesView(details)
            self.series_changed.emit(key)
            if done:
                done(key)

        def failed(e):
            view.loading, view.error = False, describe(e)
            self.series_changed.emit(key)
            if done:
                done(key)
        run_async(fetch, ok, failed)

    def in_library(self, source: str, slug: str) -> bool:
        return self.app.data.item(series_key(source, slug)) is not None

    def toggle_library(self, source: str, details: SeriesDetails) -> None:
        key = series_key(source, details.slug)
        self.app.store.update(_ops.remove_from_library(key) if self.app.data.item(key) else _ops.add_to_library(source, details))

    def mark_read(self, source: str, slug: str, ids) -> None:
        self.app.store.update(_ops.mark_read(series_key(source, slug), list(ids)))

    def mark_unread(self, source: str, slug: str, ids) -> None:
        self.app.store.update(_ops.mark_unread(series_key(source, slug), list(ids)))

    def next_to_read(self, source: str, details: SeriesDetails) -> ChapterInfo | None:
        p = self.progress(series_key(source, details.slug))
        readable = sorted((c for c in details.chapters if not c.locked), key=lambda c: c.number)
        if p.last:
            current = next((c for c in readable if c.id == p.last.chapter), None)
            if current and current.id not in p.read_ids:
                return current
        return next((c for c in readable if c.id not in p.read_ids), readable[-1] if readable else None)

    def add_to_library(self, source: str, details: SeriesDetails) -> None:
        self.app.store.update(_ops.add_to_library(source, details))

    # ---- books ----

    def known_book(self, isbns: list[str]) -> str | None:
        return next((i.slug for i in self.app.data.library if i.source == _books.SOURCE and
                     (_books.isbn_of(i.slug) in isbns or any(x in isbns for x in i.isbns))), None)

    def import_book(self, path, link: str | None = None, page: str | None = None, delete_after: bool = False) -> None:
        self._set_importing(True)

        def work():
            try:
                return self.app.books.import_file(Path(path), self.known_book, link, page)
            finally:
                if delete_after:
                    Path(path).unlink(missing_ok=True)

        def ok(b):
            self._set_importing(False)
            self.app.store.update(_ops.add_book(LibraryItem(_books.SOURCE, b.id, b.title, None, Kind.NOVEL,
                                                            int(_time.time() * 1000), b.isbns, b.link, b.page)))
            self.app.notice.emit(f"Fichier de « {b.title} » remplacé" if b.replaced else f"« {b.title} » ajouté à la bibliothèque")
            self.app.books_changed.emit()
            self.book_imported.emit(b.id)
            self.describe_book(b.id, force=True)

        def failed(e):
            self._set_importing(False)
            self.app.notice.emit(f"Import impossible : {describe(e)}")
        run_async(work, ok, failed)

    def download_book(self, url: str, page: str | None = None, referer: str | None = None) -> None:
        """A link to an .epub: downloaded with the app's cookies, then imported with its link and page."""
        self._set_importing(True)

        def work():
            fd, tmp = tempfile.mkstemp(suffix=".epub")
            os.close(fd)
            with self.app.http.stream(url, {"Referer": referer} if referer else None) as r, open(tmp, "wb") as out:
                for chunk in r.iter_content(256 * 1024):
                    out.write(chunk)
            return tmp

        def ok(tmp):
            self._set_importing(False)
            self.import_book(tmp, link=url, page=page, delete_after=True)

        def failed(e):
            self._set_importing(False)
            self.app.notice.emit(f"Téléchargement impossible : {describe(e)} — le lien a peut-être expiré")
        run_async(work, ok, failed)

    def _set_importing(self, value: bool) -> None:
        self.importing = value
        self.importing_changed.emit(value)

    def info(self, book_id: str):
        if book_id not in self.book_info:
            cached = self.app.books.info(book_id)
            if cached:
                self.book_info[book_id] = cached
        return self.book_info.get(book_id)

    def describe_book(self, book_id: str, force: bool = False) -> None:
        if (not force and self.info(book_id)) or self.book_lookup.get(book_id) == "…":
            return
        key = series_key(_books.SOURCE, book_id)
        item = self.app.data.item(key)
        self.book_lookup[book_id] = "…"
        self.book_info_changed.emit(book_id)

        def work():
            meta = None
            f = self.app.books.file(book_id)
            if f.exists():
                with _Epub(f) as e:
                    meta = (e.title, e.authors, e.language, e.description)
            isbns = list(dict.fromkeys([x for x in [_books.isbn_of(book_id)] if x] + list(item.isbns if item else ())))
            return self.app.lookup.find(isbns, meta[0] if meta else (item.title if item else ""), meta[1] if meta else [],
                                        meta[2] if meta else None, meta[3] if meta else None)

        def ok(found):
            if found is None:
                self.book_lookup[book_id] = "Livre inconnu des catalogues en ligne"
            else:
                self.app.books.save_info(book_id, found)
                self.book_info[book_id] = found
                self.book_lookup[book_id] = ""
                self.app.store.update(_ops.describe_book(key, found.title, found.cover, found.isbns))
            self.book_info_changed.emit(book_id)

        def failed(e):
            self.book_lookup[book_id] = f"Infos en ligne indisponibles : {describe(e)}"
            self.book_info_changed.emit(book_id)
        run_async(work, ok, failed)

    def remove_book(self, book_id: str) -> None:
        self.app.store.update(_ops.remove_from_library(series_key(_books.SOURCE, book_id)))
        self.app.books.delete(book_id)
        self.app.books_changed.emit()
