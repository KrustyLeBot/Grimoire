"""Home: Bibliothèque, Découvrir and Recherche tabs, as on the phone."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QButtonGroup, QHBoxLayout, QLineEdit, QStackedWidget, QTabBar, QVBoxLayout,
                               QWidget)

from ..books import store as books
from ..services.lister import Lister
from . import widgets as w

FEEDS = (("latest", "Récents"), ("popular", "Populaires"))
FILTERS = (("reading", "En cours"), ("all", "Tout"), ("done", "Terminés"))


class HomeScreen(QWidget):
    def __init__(self, window):
        super().__init__()
        self.window, self.app, self.ctrl = window, window.app, window.ctrl
        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 0)
        bar = w.TopBar("Grimoire")
        bar.add(w.tool("add", "Ajouter un livre (EPUB)", window.pick_book))
        bar.add(w.tool("settings", "Paramètres", window.show_settings))
        root.addWidget(bar)
        self.banners = QVBoxLayout()
        root.addLayout(self.banners)
        self.tabs = QTabBar()
        self.tabs.setExpanding(False)
        self.tabs.setDrawBase(False)
        for name in ("Bibliothèque", "Découvrir", "Recherche"):
            self.tabs.addTab(name)
        root.addWidget(self.tabs)
        self.pages = QStackedWidget()
        self.library = LibraryTab(window)
        self.discover = DiscoverTab(window)
        self.search = SearchTab(window)
        for page in (self.library, self.discover, self.search):
            self.pages.addWidget(page)
        root.addWidget(self.pages, 1)
        self.tabs.currentChanged.connect(self._tab)
        self.app.data_changed.connect(self.refresh_banners)
        self.refresh_banners()

    def _tab(self, index: int) -> None:
        self.pages.setCurrentIndex(index)
        self.pages.currentWidget().shown()

    def shown(self) -> None:
        self.pages.currentWidget().shown()

    def refresh_banners(self) -> None:
        w.clear_layout(self.banners)
        if self.app.store.error:
            self.banners.addWidget(w.Banner(self.app.store.error, "Fichier illisible", error=True))


class LibraryTab(QWidget):
    def __init__(self, window):
        super().__init__()
        self.window, self.app, self.ctrl = window, window.app, window.ctrl
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 8, 0, 0)
        row = QHBoxLayout()
        self.filter = "reading"
        group = QButtonGroup(self)
        for fid, text in FILTERS:
            c = w.chip(text)
            c.setChecked(fid == self.filter)
            c.clicked.connect(lambda _=False, f=fid: self._filter(f))
            group.addButton(c)
            row.addWidget(c)
        row.addStretch()
        self.refresh = w.tool("refresh", "Chercher les nouveaux chapitres", lambda: self.ctrl.refresh_library(force=True))
        row.addWidget(self.refresh)
        layout.addLayout(row)
        self.empty = w.label("", "hint")
        self.empty.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(self.empty)
        self.grid = w.CoverGrid(self.app)
        self.grid.opened.connect(self._open)
        layout.addWidget(self.grid, 1)
        self.app.data_changed.connect(self.reload)
        self.app.books_changed.connect(self.reload)
        self.app.downloads.changed.connect(self.reload)
        self.ctrl.series_changed.connect(lambda _k: self.reload())
        self.ctrl.refreshing_changed.connect(lambda on: self.refresh.setEnabled(not on))
        self.reload()

    def shown(self) -> None:
        self.reload()

    def _filter(self, f: str) -> None:
        self.filter = f
        self.reload()

    def reload(self) -> None:
        data = self.app.data
        present = self.app.books.present()
        rows = []
        for item in data.library:
            unread = self.ctrl.unread(item)
            p = data.progress_of(item.key)
            started = p.last is not None or bool(p.read)
            keep = {"reading": not started or unread > 0, "all": True, "done": started and unread == 0}[self.filter]
            if keep:
                rows.append((p.last.at if p.last else item.added, item, unread, p))
        rows.sort(key=lambda r: -r[0])
        cells = []
        for _, item, unread, p in rows:
            if item.source == books.SOURCE:
                read = books.READ in p.read_ids
                state = "Lu" if read else f"{books.percent(p.last)} %" if p.last else "Pas commencé"
                cells.append(w.Cell(item.key, item.title, item.cover or self.app.books.local_cover(item.slug), item.source,
                                    state if item.slug in present else f"{state} · fichier absent", payload=item))
            else:
                subtitle = p.last.chapter_name if p.last else ("Tout lu" if unread == 0 and p.read else "Pas commencé")
                cells.append(w.Cell(item.key, item.title, item.cover, item.source, subtitle,
                                    str(unread) if unread > 0 else None, payload=item))
        self.grid.set_cells(cells)
        self.empty.setVisible(not cells)
        self.empty.setText("Ta bibliothèque est vide.\nTrouve une série dans Découvrir ou Recherche : elle arrive ici dès que tu la lis.\n"
                           "Pour un livre, ouvre un fichier EPUB avec Grimoire ou touche +." if not data.library
                           else f"Rien dans « {dict(FILTERS)[self.filter]} ».")

    def _open(self, cell: w.Cell) -> None:
        item = cell.payload
        if item.source == books.SOURCE:
            self.window.show_book(item.slug)
        else:
            self.window.show_series(item.source, item.slug, item.title, item.cover)


class _ListingTab(QWidget):
    """A grid fed by a Lister, with a "Toutes les sources" / one source choice."""

    def __init__(self, window, fetch):
        super().__init__()
        self.window, self.app, self.ctrl = window, window.app, window.ctrl
        self.lister = Lister(self.app.sources, fetch)
        self.source: str | None = None
        self.layout_ = QVBoxLayout(self)
        self.layout_.setContentsMargins(0, 8, 0, 0)
        self.top = QVBoxLayout()
        self.layout_.addLayout(self.top)
        self.source_row = QHBoxLayout()
        self.layout_.addLayout(self.source_row)
        self.status = w.label("", "hint")
        self.status.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.grid = w.CoverGrid(self.app)
        self.grid.opened.connect(lambda c: window.show_series(c.source, c.payload.slug, c.payload.title, c.payload.cover))
        self.grid.near_end.connect(self.lister.more)
        self.layout_.addWidget(self.status)
        self.retry = w.button("Réessayer", on_click=self.lister.retry)
        self.layout_.addWidget(self.retry, 0, Qt.AlignmentFlag.AlignCenter)
        self.layout_.addWidget(self.grid, 1)
        self.lister.changed.connect(self.render)
        self.app.data_changed.connect(self._sources_changed)
        self._shown_count = 0
        self._enabled: tuple[str, ...] = ()
        self._sources_changed()

    def enabled(self) -> list:
        return [s for s in self.app.data.sources if s.enabled]

    def _sources_changed(self) -> None:
        ids = tuple(s.id for s in self.enabled())
        if ids == self._enabled:
            return
        self._enabled = ids
        w.clear_layout(self.source_row)
        if len(ids) >= 2:
            group = QButtonGroup(self)
            for sid, name in [(None, "Toutes les sources")] + [(s.id, s.name) for s in self.enabled()]:
                c = w.chip(name)
                c.setChecked(sid == self.source)
                c.clicked.connect(lambda _=False, x=sid: self.pick_source(x))
                group.addButton(c)
                self.source_row.addWidget(c)
            self.source_row.addStretch()
        self.lister.clear()
        self.render()

    def scope(self) -> list[str]:
        return [self.source] if self.source else [s.id for s in self.enabled()]

    def pick_source(self, sid: str | None) -> None:
        self.source = sid
        self.restart()

    def restart(self) -> None:
        raise NotImplementedError

    def render(self) -> None:
        listing = self.lister.listing
        show_source = self.source is None and len(self.enabled()) > 1
        if len(listing.items) < self._shown_count or listing.generation != getattr(self, "_generation", None):
            self.grid.set_cells([])
            self._shown_count = 0
            self._generation = listing.generation
        names = {s.id: s.name for s in self.app.data.sources}
        new = listing.items[self._shown_count:]
        self.grid.append([w.Cell(f.key, f.series.title, f.series.cover, f.source, names.get(f.source) if show_source else None,
                                 payload=f.series) for f in new])
        self._shown_count = len(listing.items)
        errors = [f"{names.get(sid, sid)} : {e}" for sid, e in listing.errors]
        text = "Chargement…" if listing.loading else "\n".join(errors) if errors else (
            self.empty_text() if not listing.items and not self.lister.has_next else "")
        self.status.setText(text)
        self.status.setObjectName("error" if errors and not listing.loading else "hint")
        self.status.setStyleSheet("")
        self.status.setVisible(bool(text))
        self.retry.setVisible(bool(errors) and not listing.loading)

    def empty_text(self) -> str:
        return "Rien pour l'instant." if self.enabled() else "Aucune source active : ajoute-en une dans Paramètres › Sources."


class DiscoverTab(_ListingTab):
    def __init__(self, window):
        self.feed = "latest"
        super().__init__(window, lambda src, page: src.latest(page) if self.feed == "latest" else src.popular(page))
        row = QHBoxLayout()
        group = QButtonGroup(self)
        for fid, text in FEEDS:
            c = w.chip(text)
            c.setChecked(fid == self.feed)
            c.clicked.connect(lambda _=False, f=fid: self._feed(f))
            group.addButton(c)
            row.addWidget(c)
        row.addStretch()
        self.top.addLayout(row)

    def _feed(self, f: str) -> None:
        self.feed = f
        self.restart()

    def restart(self) -> None:
        self.lister.reset(self.scope())

    def shown(self) -> None:
        if not self.lister.sources and self.enabled():
            self.restart()


class SearchTab(_ListingTab):
    def __init__(self, window):
        self.query = ""
        super().__init__(window, lambda src, page: src.search(self.query, page))
        self.field = QLineEdit()
        self.field.setPlaceholderText("Rechercher dans toutes les sources (titre)…")
        self.field.setClearButtonEnabled(True)
        self.field.returnPressed.connect(self.run)
        self.top.addWidget(self.field)
        self.web_row = QHBoxLayout()
        self.top.addLayout(self.web_row)
        self.app.data_changed.connect(self._web)
        self.field.textChanged.connect(lambda _t: self._web())
        self._web()

    def _web(self) -> None:
        """Configured websites: the same search, opened inside Grimoire (books found there land in the library)."""
        w.clear_layout(self.web_row)
        searches = self.app.data.web_searches
        if not searches or not self.field.text().strip():
            return
        self.web_row.addWidget(w.label("Sur le web :", "hint", wrap=False))
        for s in searches:
            b = w.chip(s.name, checkable=False)
            b.clicked.connect(lambda _=False, x=s: self.window.show_web(x.url_for(self.field.text()), x.name))
            self.web_row.addWidget(b)
        self.web_row.addStretch()

    def run(self) -> None:
        self.query = self.field.text().strip()
        self.restart()

    def restart(self) -> None:
        if self.query:
            self.lister.reset(self.scope())
        else:
            self.lister.clear()

    def empty_text(self) -> str:
        return "Tape un titre puis Entrée." if not self.query else f"Aucun résultat pour « {self.query} »."

    def shown(self) -> None:
        self.field.setFocus()


