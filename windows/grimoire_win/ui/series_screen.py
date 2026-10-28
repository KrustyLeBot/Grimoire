"""A series: cover, details, the read / continue button and its chapters, with read marks and
downloads; several chapters can be selected (Ctrl/Shift+click) and marked or downloaded at once."""
from __future__ import annotations

from datetime import datetime

from PySide6.QtCore import QSize, Qt, QUrl
from PySide6.QtGui import QDesktopServices, QPixmap
from PySide6.QtWidgets import (QAbstractItemView, QHBoxLayout, QLabel, QListWidget, QListWidgetItem, QMenu, QMessageBox,
                               QVBoxLayout, QWidget)

from ..core.model import ChapterInfo, series_key
from . import icons, theme
from . import widgets as w


def relative_day(at_ms: int) -> str:
    if not at_ms:
        return ""
    days = (datetime.now() - datetime.fromtimestamp(at_ms / 1000)).days
    if days <= 0:
        return "Aujourd'hui"
    if days == 1:
        return "Hier"
    if days < 7:
        return f"Il y a {days} jours"
    return datetime.fromtimestamp(at_ms / 1000).strftime("%d/%m/%Y")


class SeriesScreen(QWidget):
    def __init__(self, window, source: str, slug: str, title: str, cover: str | None):
        super().__init__()
        self.window, self.app, self.ctrl = window, window.app, window.ctrl
        self.source, self.slug, self.key = source, slug, series_key(source, slug)
        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)
        self.bar = w.TopBar(title, window.back)
        self.bookmark = self.bar.add(w.tool("bookmark_border", "Ajouter à la bibliothèque", self._toggle_library))
        self.bar.add(w.tool("refresh", "Actualiser", lambda: self.ctrl.load_series(source, slug, force=True)))
        self.bar.add(w.tool("more", "Plus", self._menu))
        root.addWidget(self.bar)

        header = QHBoxLayout()
        self.cover = QLabel()
        self.cover.setFixedSize(150, 214)
        self.cover.setStyleSheet(f"background: {theme.scheme().surface_highest}; border-radius: 12px;")
        header.addWidget(self.cover, 0, Qt.AlignmentFlag.AlignTop)
        info = QVBoxLayout()
        self.title = w.label(title, "heading")
        self.meta = w.label("", "hint")
        self.description = w.label("")
        self.description.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.status = w.label("", "hint")
        info.addWidget(self.title)
        info.addWidget(self.meta)
        info.addWidget(self.description)
        info.addStretch(1)
        info.addWidget(self.status)
        self.read_button = w.button("Lire", "primary", self._read_next)
        info.addWidget(self.read_button, 0, Qt.AlignmentFlag.AlignLeft)
        header.addLayout(info, 1)
        root.addLayout(header)

        self.count = w.label("", "section")
        root.addWidget(self.count)
        self.list = QListWidget()
        self.list.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self.list.itemDoubleClicked.connect(self._open_item)
        self.list.itemSelectionChanged.connect(self._selection)
        self.list.setIconSize(QSize(18, 18))
        root.addWidget(self.list, 1)

        # What to do with the selection: names next to the icons, so the two "read" marks are clear.
        self.actions = w.Card()
        row = QHBoxLayout()
        self.selected_label = w.label("", wrap=False)
        row.addWidget(self.selected_label)
        row.addStretch()
        for name, text, fn in (("check", "Lu", self._mark_selected), ("done_all", "Lus jusqu'ici", self._mark_up_to),
                               ("remove_done", "Non lu", self._unmark_selected), ("download", "Télécharger", self._download_selected)):
            b = w.button(text, "action", fn)
            b.setIcon(icons.icon(name, theme.scheme().on_secondary_container, 18))
            row.addWidget(b)
        self.actions.body.addLayout(row)
        self.actions.hide()
        root.addWidget(self.actions)

        self.ctrl.series_changed.connect(self._changed)
        self.app.data_changed.connect(self.render)
        self.app.downloads.changed.connect(self.render)
        if cover:
            self._cover(cover)
        self.ctrl.load_series(source, slug)
        self.render()

    @property
    def details(self):
        return self.ctrl.view(self.source, self.slug).details

    def _cover(self, url: str) -> None:
        src = self.app.sources.get(self.source)

        def show(image):
            if image is not None and not self.cover.isHidden():
                pix = QPixmap.fromImage(image).scaled(self.cover.size() * 2, Qt.AspectRatioMode.KeepAspectRatioByExpanding,
                                                      Qt.TransformationMode.SmoothTransformation)
                pix.setDevicePixelRatio(2)
                self.cover.setPixmap(pix)
        self.app.images.get(url, src.image_headers() if src else None, show)

    def _changed(self, key: str) -> None:
        if key == self.key:
            if self.details and self.details.cover:
                self._cover(self.details.cover)
            self.render()

    def render(self) -> None:
        view = self.ctrl.view(self.source, self.slug)
        d = view.details
        in_library = self.ctrl.in_library(self.source, self.slug)
        self.bookmark.setProperty("icon_name", "bookmark" if in_library else "bookmark_border")
        self.bookmark.setIcon(icons.icon("bookmark" if in_library else "bookmark_border", theme.scheme().on_surface, 22))
        self.bookmark.setToolTip("Retirer de la bibliothèque" if in_library else "Ajouter à la bibliothèque")
        self.status.setText("Chargement…" if view.loading else (f"Mise à jour impossible : {view.error}" if view.error else ""))
        if d is None:
            self.read_button.setEnabled(False)
            self.count.setText("")
            return
        self.title.setText(d.title)
        self.bar.title.setText(d.title)
        source_name = next((s.name for s in self.app.data.sources if s.id == self.source), self.source)
        people = " · ".join(dict.fromkeys(x for x in (d.author, d.artist) if x))
        self.meta.setText(" · ".join(x for x in (people, d.status, ", ".join(d.genres), source_name) if x))
        self.description.setText(d.description or "")
        p = self.ctrl.progress(self.key)
        read = p.read_ids
        nxt = self.ctrl.next_to_read(self.source, d)
        self.read_button.setEnabled(nxt is not None)
        if nxt:
            resume = p.last is not None and p.last.chapter == nxt.id
            self.read_button.setText(f"{'Continuer' if resume else 'Lire' if not read else 'Lire la suite'} · {nxt.name}")
        self.count.setText(f"{len(d.chapters)} chapitres · {sum(1 for c in d.chapters if c.id in read)} lus")
        selected = {i.data(Qt.ItemDataRole.UserRole).id for i in self.list.selectedItems()}
        scroll = self.list.verticalScrollBar().value()
        self.list.blockSignals(True)
        self.list.clear()
        s = theme.scheme()
        for c in d.chapters:
            done = self.app.downloads.is_done(self.source, self.slug, c.id)
            running = self.app.downloads.running.get(f"{self.source}:{self.slug.replace('/', '_')}:{c.id}")
            parts = [relative_day(c.date)]
            page = p.page_of.get(c.id)
            if page is not None and c.id not in read:
                parts.append(f"page {page + 1}")
            if running is not None:
                parts.append("en attente" if running < 0 else f"téléchargement {int(running * 100)} %")
            elif done:
                parts.append("téléchargé")
            text = c.name + ("   —   " + " · ".join(x for x in parts if x) if any(parts) else "")
            item = QListWidgetItem(text)
            item.setSizeHint(QSize(0, 34))
            item.setData(Qt.ItemDataRole.UserRole, c)
            icon = "check" if c.id in read else "download_done" if done else None
            if icon:
                item.setIcon(icons.icon(icon, s.outline if c.id in read else s.primary, 18))
            if c.id in read or c.locked:
                item.setForeground(Qt.GlobalColor.gray)
            if c.locked:
                item.setFlags(item.flags() & ~Qt.ItemFlag.ItemIsEnabled)
            self.list.addItem(item)
            if c.id in selected:
                item.setSelected(True)
        self.list.blockSignals(False)
        self.list.verticalScrollBar().setValue(scroll)
        self._selection()

    # ---- actions ----

    def _toggle_library(self) -> None:
        if self.details:
            self.ctrl.toggle_library(self.source, self.details)

    def _read_next(self) -> None:
        if self.details:
            nxt = self.ctrl.next_to_read(self.source, self.details)
            if nxt:
                self._read(nxt)

    def _read(self, c: ChapterInfo) -> None:
        self.ctrl.add_to_library(self.source, self.details)
        self.window.show_reader(self.source, self.details, c.id)

    def _open_item(self, item: QListWidgetItem) -> None:
        c = item.data(Qt.ItemDataRole.UserRole)
        if not c.locked:
            self._read(c)

    def _selected(self) -> list[ChapterInfo]:
        return [i.data(Qt.ItemDataRole.UserRole) for i in self.list.selectedItems()]

    def _selection(self) -> None:
        n = len(self.list.selectedItems())
        self.actions.setVisible(n > 0)
        self.selected_label.setText("1 chapitre" if n == 1 else f"{n} chapitres")

    def _mark_selected(self) -> None:
        self.ctrl.mark_read(self.source, self.slug, [c.id for c in self._selected()])
        self.list.clearSelection()

    def _mark_up_to(self) -> None:
        """Every chapter up to the newest one selected."""
        sel = self._selected()
        if sel and self.details:
            top = max(c.number for c in sel)
            self.ctrl.mark_read(self.source, self.slug, [c.id for c in self.details.chapters if c.number <= top])
        self.list.clearSelection()

    def _unmark_selected(self) -> None:
        self.ctrl.mark_unread(self.source, self.slug, [c.id for c in self._selected()])
        self.list.clearSelection()

    def _download_selected(self) -> None:
        self.app.downloads.enqueue(self.source, self.slug, sorted(self._selected(), key=lambda c: c.number))
        self.list.clearSelection()

    def _menu(self) -> None:
        d = self.details
        menu = QMenu(self)
        if d:
            read = self.ctrl.progress(self.key).read_ids
            menu.addAction("Tout marquer comme lu", lambda: self.ctrl.mark_read(self.source, self.slug, [c.id for c in d.chapters]))
            menu.addAction("Tout marquer comme non lu", lambda: self.ctrl.mark_unread(self.source, self.slug, [c.id for c in d.chapters]))
            menu.addAction("Télécharger les non lus", lambda: self.app.downloads.enqueue(
                self.source, self.slug, sorted((c for c in d.chapters if c.id not in read), key=lambda c: c.number)))
        menu.addAction("Supprimer les téléchargements", self._delete_downloads)
        src = self.app.sources.get(self.source)
        if src:
            menu.addAction("Ouvrir sur le site", lambda: QDesktopServices.openUrl(QUrl(src.series_url(self.slug))))
        menu.exec(self.cursor().pos())

    def _delete_downloads(self) -> None:
        if QMessageBox.question(self, "Supprimer les téléchargements ?",
                                "Les chapitres téléchargés de cette série sont effacés de cet ordinateur.") == QMessageBox.StandardButton.Yes:
            self.app.downloads.delete_series(self.source, self.slug)
