"""The image reader (manga, webtoons): Défilement (one long strip) or Pages (one page at a time,
optionally right to left). Downloaded chapters are read from their files, others from the site.
The page is remembered as pages go by (written when the reader closes) and the chapter is marked
read at its last page."""
from __future__ import annotations

from PySide6.QtCore import QEvent, QSize, Qt, QTimer
from PySide6.QtGui import QKeySequence, QPixmap, QShortcut
from PySide6.QtWidgets import (QButtonGroup, QFrame, QHBoxLayout, QLabel, QScrollArea, QStackedWidget, QVBoxLayout,
                               QWidget)

from ..core import ops
from ..core.model import Kind, SeriesDetails, series_key
from ..services.async_util import run_async
from ..sources.http import describe
from ..sources.source import Images

from . import widgets as w

MODES = (("scroll", "Défilement"), ("pages", "Pages"))


def default_mode(raw: str | None, kind: Kind) -> str:
    return raw if raw in ("scroll", "pages") else ("pages" if kind == Kind.MANGA else "scroll")


class ReaderScreen(QWidget):
    def __init__(self, window, source: str, details: SeriesDetails, chapter_id: str):
        super().__init__()
        self.window, self.app, self.ctrl = window, window.app, window.ctrl
        self.source, self.details = source, details
        self.key = series_key(source, details.slug)
        self.chapters = sorted((c for c in details.chapters if not c.locked), key=lambda c: c.number)
        self.chapter = next((c for c in self.chapters if c.id == chapter_id), self.chapters[0])
        self.mode = default_mode(self.ctrl.progress(self.key).mode, details.kind)
        self.zoom = 1.0
        self.pages: list[str] = []
        self.headers: dict[str, str] | None = None
        self.page = 0
        self._generation = 0
        self.setStyleSheet("ReaderScreen { background: #000; }")

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        bar = QFrame()
        bar.setObjectName("bar")
        row = QHBoxLayout(bar)
        row.setContentsMargins(6, 4, 8, 4)
        row.addWidget(w.tool("back", "Retour (Échap)", window.back))
        names = QVBoxLayout()
        names.setSpacing(0)
        self.title = w.label(details.title, wrap=False)
        self.title.setStyleSheet("font-weight: 600;")
        self.subtitle = w.label("", "hint", wrap=False)
        names.addWidget(self.title)
        names.addWidget(self.subtitle)
        row.addLayout(names, 1)
        self.prev_button = w.tool("skip_previous", "Chapitre précédent", self.previous_chapter)
        row.addWidget(self.prev_button)
        group = QButtonGroup(self)
        self.mode_chips = {}
        for mid, text in MODES:
            c = w.chip(text)
            c.setChecked(mid == self.mode)
            c.clicked.connect(lambda _=False, m=mid: self.set_mode(m))
            group.addButton(c)
            self.mode_chips[mid] = c
            row.addWidget(c)
        row.addWidget(w.tool("zoom_out", "Zoom arrière (Ctrl -)", lambda: self.set_zoom(self.zoom / 1.15)))
        row.addWidget(w.tool("zoom_in", "Zoom avant (Ctrl +)", lambda: self.set_zoom(self.zoom * 1.15)))
        self.next_button = w.tool("skip_next", "Chapitre suivant", self.next_chapter)
        row.addWidget(self.next_button)
        root.addWidget(bar)

        self.stack = QStackedWidget()
        root.addWidget(self.stack, 1)
        # Défilement: a column of pages; only those near the screen are loaded.
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setAlignment(Qt.AlignmentFlag.AlignHCenter)
        self.scroll.setStyleSheet("QScrollArea { background: #000; }")
        self.strip = QWidget()
        self.strip.setStyleSheet("background: #000;")
        self.strip_layout = QVBoxLayout(self.strip)
        self.strip_layout.setContentsMargins(0, 0, 0, 0)
        self.strip_layout.setSpacing(0)
        self.strip_layout.setAlignment(Qt.AlignmentFlag.AlignHCenter)
        self.scroll.setWidget(self.strip)
        self.scroll.verticalScrollBar().valueChanged.connect(lambda _v: self._scrolled())
        self.stack.addWidget(self.scroll)
        # Pages: one page fitted in the window; sides or arrow keys turn pages.
        self.single = QLabel()
        self.single.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.single.setStyleSheet("background: #000; color: #ccc;")
        self.single.installEventFilter(self)
        self.stack.addWidget(self.single)
        self.message = QLabel()
        self.message.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.message.setWordWrap(True)
        self.message.setStyleSheet("background: #000; color: #eee; font-size: 12pt;")
        self.stack.addWidget(self.message)

        for keys, fn in ((("Right", "Space", "PgDown"), lambda: self.turn(-1 if self._rtl() else 1)),
                         (("Left", "PgUp"), lambda: self.turn(1 if self._rtl() else -1)),
                         (("Ctrl++", "Ctrl+="), lambda: self.set_zoom(self.zoom * 1.15)),
                         (("Ctrl+-",), lambda: self.set_zoom(self.zoom / 1.15)),
                         (("Ctrl+0",), lambda: self.set_zoom(1.0)),
                         (("N",), self.next_chapter), (("P",), self.previous_chapter)):
            for k in keys:
                QShortcut(QKeySequence(k), self, activated=fn)
        self._labels: list[QLabel] = []
        self._loaded: set[int] = set()
        self._lazy = QTimer(self, singleShot=True, interval=60)
        self._lazy.timeout.connect(self._load_visible)
        self.load(self.chapter)

    def _rtl(self) -> bool:
        return self.mode == "pages" and self.app.config.right_to_left

    # ---- chapters ----

    def load(self, chapter) -> None:
        self.chapter = chapter
        self._generation += 1
        generation = self._generation
        p = self.ctrl.progress(self.key)
        self.page = 0 if chapter.id in p.read_ids else p.page_of.get(chapter.id, 0)
        index = self.chapters.index(chapter)
        self.prev_button.setEnabled(index > 0)
        self.next_button.setEnabled(index < len(self.chapters) - 1)
        self.message.setText("Chargement…")
        self.stack.setCurrentWidget(self.message)
        files = self.app.downloads.pages(self.source, self.details.slug, chapter.id)
        if files is not None:
            self._show([str(f) for f in files], None, generation)
            return
        src = self.app.sources.get(self.source)

        def fetch():
            if src is None:
                raise OSError("Source introuvable")
            return src.content(self.details.slug, chapter)

        def ok(content):
            if isinstance(content, Images):
                self._show(list(content.urls), src.image_headers(), generation)
            else:
                self._fail("Ce chapitre n'est pas fait d'images", generation)
        run_async(fetch, ok, lambda e: self._fail(describe(e), generation))

    def _fail(self, text: str, generation: int) -> None:
        if generation == self._generation:
            self.message.setText(f"{text}\n\n(Échap pour revenir)")
            self.stack.setCurrentWidget(self.message)

    def _show(self, pages: list[str], headers, generation: int) -> None:
        if generation != self._generation:
            return
        self.pages, self.headers = pages, headers
        self.page = min(self.page, max(0, len(pages) - 1))
        self._build()

    def next_chapter(self) -> None:
        i = self.chapters.index(self.chapter)
        if i < len(self.chapters) - 1:
            self.load(self.chapters[i + 1])

    def previous_chapter(self) -> None:
        i = self.chapters.index(self.chapter)
        if i > 0:
            self.load(self.chapters[i - 1])

    # ---- layout ----

    def set_mode(self, mode: str) -> None:
        self.mode = mode
        self.mode_chips[mode].setChecked(True)
        self.app.store.update(ops.set_mode(self.key, mode))
        if self.pages:
            self._build()

    def set_zoom(self, zoom: float) -> None:
        self.zoom = max(0.3, min(4.0, zoom))
        if self.pages:
            self._build(keep=True)

    def _page_width(self) -> int:
        # A strip as wide as the window, up to a comfortable reading width.
        return int(min(self.scroll.viewport().width(), 900) * self.zoom)

    def _build(self, keep: bool = False) -> None:
        if self.mode == "scroll":
            w.clear_layout(self.strip_layout)
            self._labels, self._loaded = [], set()
            width = self._page_width()
            for _ in self.pages:
                lab = QLabel()
                lab.setFixedSize(width, int(width * 1.4))
                lab.setAlignment(Qt.AlignmentFlag.AlignCenter)
                lab.setStyleSheet("color: #666;")
                lab.setText("…")
                self._labels.append(lab)
                self.strip_layout.addWidget(lab, 0, Qt.AlignmentFlag.AlignHCenter)
            end = w.button("Chapitre suivant", "primary", self.next_chapter)
            end.setVisible(self.chapters.index(self.chapter) < len(self.chapters) - 1)
            holder = QWidget()
            hl = QVBoxLayout(holder)
            hl.setContentsMargins(0, 24, 0, 48)
            hl.addWidget(end, 0, Qt.AlignmentFlag.AlignHCenter)
            self.strip_layout.addWidget(holder)
            self.stack.setCurrentWidget(self.scroll)
            target = self.page
            QTimer.singleShot(0, lambda: self._scroll_to(target))
        else:
            self.stack.setCurrentWidget(self.single)
            self._show_single()
        self._status()

    def _scroll_to(self, page: int) -> None:
        self._load_visible()
        if 0 <= page < len(self._labels):
            self.scroll.verticalScrollBar().setValue(self._labels[page].y())
        self._load_visible()

    def _load_visible(self) -> None:
        if not self._labels:
            return
        top = self.scroll.verticalScrollBar().value()
        bottom = top + self.scroll.viewport().height()
        generation = self._generation
        for i, lab in enumerate(self._labels):
            if i in self._loaded or lab.y() > bottom + 2500 or lab.y() + lab.height() < top - 1500:
                continue
            self._loaded.add(i)
            self.app.images.get(self.pages[i], self.headers, lambda img, i=i: self._place(i, img, generation), keep=False)

    def _place(self, i: int, image, generation: int) -> None:
        if generation != self._generation or i >= len(self._labels) or self.mode != "scroll":
            return
        lab = self._labels[i]
        if image is None:
            lab.setText("Image indisponible")
            return
        width = self._page_width()
        pix = QPixmap.fromImage(image)
        scaled = pix.scaledToWidth(int(width * lab.devicePixelRatioF()), Qt.TransformationMode.SmoothTransformation)
        scaled.setDevicePixelRatio(lab.devicePixelRatioF())
        anchor = self.scroll.verticalScrollBar().value()
        above = lab.y() < anchor
        old = lab.height()
        lab.setFixedSize(width, int(scaled.height() / lab.devicePixelRatioF()))
        lab.setPixmap(scaled)
        if above:  # keep the page being read in place when one above it changes height
            self.scroll.verticalScrollBar().setValue(anchor + lab.height() - old)

    def _scrolled(self) -> None:
        self._lazy.start()
        if not self._labels:
            return
        bar = self.scroll.verticalScrollBar()
        middle = bar.value() + self.scroll.viewport().height() // 2
        page = next((i for i, lab in enumerate(self._labels) if lab.y() + lab.height() > middle), len(self._labels) - 1)
        at_end = bar.value() >= bar.maximum() - 4
        self._set_page(len(self._labels) - 1 if at_end else page)

    def _show_single(self) -> None:
        generation = self._generation
        page = self.page
        self.single.setText("…")

        def show(image):
            if generation != self._generation or page != self.page or self.mode != "pages":
                return
            if image is None:
                self.single.setText("Image indisponible")
                return
            size = self.single.size() * self.zoom * self.single.devicePixelRatioF()
            pix = QPixmap.fromImage(image).scaled(size, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            pix.setDevicePixelRatio(self.single.devicePixelRatioF())
            self.single.setPixmap(pix)
        self.app.images.get(self.pages[page], self.headers, show, keep=False)
        # The next page is fetched ahead, so turning is instant.
        if page + 1 < len(self.pages):
            self.app.images.get(self.pages[page + 1], self.headers, lambda _i: None, keep=False)
        self._set_page(page)

    def turn(self, delta: int) -> None:
        if not self.pages:
            return
        if self.mode == "scroll":
            bar = self.scroll.verticalScrollBar()
            bar.setValue(bar.value() + delta * int(self.scroll.viewport().height() * 0.85))
            return
        target = self.page + delta
        if target >= len(self.pages):
            self.next_chapter()
        elif target < 0:
            self.previous_chapter()
        else:
            self.page = target
            self._show_single()

    def eventFilter(self, obj, event):
        if obj is self.single and event.type() == QEvent.Type.MouseButtonRelease:
            left = event.position().x() < self.single.width() / 2
            self.turn((1 if left else -1) if self._rtl() else (-1 if left else 1))
            return True
        return super().eventFilter(obj, event)

    def resizeEvent(self, e):
        super().resizeEvent(e)
        if self.pages and self.mode == "pages":
            self._show_single()

    # ---- position ----

    def _set_page(self, page: int) -> None:
        self.page = page
        self.app.store.update(ops.position(self.key, self.chapter, page, len(self.pages)), persist=False)
        if self.pages and page >= len(self.pages) - 1 and self.chapter.id not in self.ctrl.progress(self.key).read_ids:
            self.app.store.update(ops.mark_read(self.key, [self.chapter.id]))
        self._status()

    def _status(self) -> None:
        total = len(self.pages)
        self.subtitle.setText(f"{self.chapter.name}" + (f"  ·  page {self.page + 1} / {total}" if total else ""))

    def closing(self) -> None:
        self.app.store.flush()

    def sizeHint(self) -> QSize:
        return QSize(900, 700)


