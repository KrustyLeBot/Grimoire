"""Last chapters and books read, by day; one click to pick up where it stopped."""
from __future__ import annotations

from PySide6.QtCore import QSize, Qt
from PySide6.QtWidgets import QListWidget, QListWidgetItem, QVBoxLayout, QWidget

from ..books import store as books
from . import widgets as w
from .series_screen import relative_day


class HistoryScreen(QWidget):
    def __init__(self, window):
        super().__init__()
        self.window, self.app, self.ctrl = window, window.app, window.ctrl
        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)
        root.addWidget(w.TopBar("Historique", window.back))
        self.list = QListWidget()
        self.list.itemActivated.connect(self._open)
        self.list.itemClicked.connect(self._open)
        root.addWidget(self.list, 1)
        self.app.data_changed.connect(self.render)
        self.render()

    def render(self) -> None:
        self.list.clear()
        data = self.app.data
        entries = sorted(((k, p.last) for k, p in data.progress if p.last), key=lambda e: -e[1].at)
        if not entries:
            self.list.addItem("Les chapitres et livres que tu lis apparaîtront ici.")
            return
        day = None
        for key, last in entries:
            label = relative_day(last.at)
            if label != day:
                day = label
                header = QListWidgetItem(label)
                header.setFlags(Qt.ItemFlag.NoItemFlags)
                font = header.font()
                font.setBold(True)
                header.setFont(font)
                self.list.addItem(header)
            source, slug = key.split(":", 1)
            item = data.item(key)
            title = item.title if item else (self.app.cache.load(source, slug).title if self.app.cache.load(source, slug) else slug)
            where = f"{last.chapter_name} · {books.percent(last)} %" if source == books.SOURCE else \
                f"{last.chapter_name} · page {last.page + 1}" + (f" / {last.pages}" if last.pages else "")
            row = QListWidgetItem(f"{title}   —   {where}")
            row.setSizeHint(QSize(0, 34))
            row.setData(Qt.ItemDataRole.UserRole, (source, slug, last.chapter))
            self.list.addItem(row)

    def _open(self, row: QListWidgetItem) -> None:
        target = row.data(Qt.ItemDataRole.UserRole)
        if not target:
            return
        source, slug, chapter = target
        if source == books.SOURCE:
            if slug in self.app.books.present():
                self.window.show_book_reader(slug)
            else:
                self.window.show_book(slug)
            return
        details = self.ctrl.view(source, slug).details
        if details:
            self.window.show_reader(source, details, chapter)
        else:
            item = self.app.data.item(f"{source}:{slug}")
            self.window.show_series(source, slug, item.title if item else slug, item.cover if item else None)
