"""The EPUB reader: chapters shown by Qt's web engine, their files served straight from the EPUB
(nothing goes to the network), with the Android reader's style and script. Pages (columns, arrow
keys, wheel, side clicks) or Défilement; table of contents; text size; reading colours."""
from __future__ import annotations

import json

from PySide6.QtCore import QBuffer, QByteArray, QIODevice, Qt, QUrl
from PySide6.QtGui import QColor, QKeySequence, QShortcut
from PySide6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile, QWebEngineUrlRequestJob, QWebEngineUrlScheme, QWebEngineUrlSchemeHandler
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtWidgets import QButtonGroup, QFrame, QHBoxLayout, QMenu, QProgressBar, QVBoxLayout, QWidget

from ..books import chapter
from ..books import store as books
from ..books.epub import Epub
from ..core import ops
from ..core.model import series_key
from . import book_page, theme
from . import widgets as w

SCHEME = b"grimoire-book"
THEMES = (("auto", "Auto"), ("light", "Clair"), ("dark", "Sombre"), ("paper", "Papier"))
COLORS = {"light": ("#FFFFFF", "#1C1B1F", "#7B2D8E"), "dark": ("#121212", "#DEDAE0", "#E1A6EE"),
          "paper": ("#F4ECD8", "#4B3A2A", "#8A4B2A")}  # same as the Android app's PageColors

MIME = {"xhtml": "application/xhtml+xml", "xht": "application/xhtml+xml", "xml": "application/xhtml+xml", "html": "text/html",
        "htm": "text/html", "css": "text/css", "jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif",
        "webp": "image/webp", "svg": "image/svg+xml", "ttf": "font/ttf", "otf": "font/otf", "woff": "font/woff", "woff2": "font/woff2"}


def register_scheme() -> None:
    """Must run before the QApplication is created."""
    s = QWebEngineUrlScheme(SCHEME)
    s.setSyntax(QWebEngineUrlScheme.Syntax.Path)
    s.setFlags(QWebEngineUrlScheme.Flag.SecureScheme | QWebEngineUrlScheme.Flag.LocalScheme | QWebEngineUrlScheme.Flag.LocalAccessAllowed)
    QWebEngineUrlScheme.registerScheme(s)


class _Handler(QWebEngineUrlSchemeHandler):
    """Serves the open book's files; its chapters go through chapter.render first."""

    def __init__(self):
        super().__init__()
        self.reader: "BookReaderScreen | None" = None

    def requestStarted(self, job: QWebEngineUrlRequestJob) -> None:
        reader = self.reader
        path = job.requestUrl().path().lstrip("/")
        data = reader.epub.read_bytes(path) if reader and reader.epub else None
        if data is None:
            job.fail(QWebEngineUrlRequestJob.Error.UrlNotFound)
            return
        ext = path.rsplit(".", 1)[-1].lower()
        mime = MIME.get(ext, "application/octet-stream")
        if path in reader.epub.spine:
            source = chapter.decode(data)
            data = chapter.render(source, reader.css(), reader.script(path)).encode("utf-8")
            if not chapter.keeps_markup(source):
                mime = "text/html"
        buffer = QBuffer(job)
        buffer.setData(QByteArray(data))
        buffer.open(QIODevice.OpenModeFlag.ReadOnly)
        job.reply(mime.encode(), buffer)


_handler: _Handler | None = None


def _install() -> _Handler:
    global _handler
    if _handler is None:
        _handler = _Handler()
        QWebEngineProfile.defaultProfile().installUrlSchemeHandler(SCHEME, _handler)
    return _handler


class _Page(QWebEnginePage):
    def __init__(self, reader: "BookReaderScreen"):
        super().__init__(QWebEngineProfile.defaultProfile(), reader)
        self.reader = reader

    def javaScriptConsoleMessage(self, level, message: str, line: int, source: str) -> None:
        if message.startswith("grimoire:"):
            self.reader.message(json.loads(message[len("grimoire:"):]))

    def acceptNavigationRequest(self, url: QUrl, kind, main_frame: bool) -> bool:
        if url.scheme() == SCHEME.decode() and kind == QWebEnginePage.NavigationType.NavigationTypeLinkClicked:
            self.reader.open_path(url.path().lstrip("/"))
            return False
        return url.scheme() == SCHEME.decode()


class BookReaderScreen(QWidget):
    def __init__(self, window, book_id: str):
        super().__init__()
        self.window, self.app, self.ctrl = window, window.app, window.ctrl
        self.book_id = book_id
        self.key = series_key(books.SOURCE, book_id)
        cfg = self.app.config
        self.paged = self.ctrl.progress(self.key).mode != "scroll"
        self.text_size = cfg.text_size
        self.theme = cfg.book_theme
        self.epub: Epub | None = None
        last = self.ctrl.progress(self.key).last
        self.index = 0
        self.start = self.fraction = 0.0

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        top = QFrame()
        top.setObjectName("bar")
        row = QHBoxLayout(top)
        row.setContentsMargins(6, 4, 8, 4)
        row.addWidget(w.tool("back", "Retour (Échap)", window.back))
        names = QVBoxLayout()
        names.setSpacing(0)
        item = self.app.data.item(self.key)
        self.title = w.label(item.title if item else "", wrap=False)
        self.title.setStyleSheet("font-weight: 600;")
        self.part = w.label("", "hint", wrap=False)
        names.addWidget(self.title)
        names.addWidget(self.part)
        row.addLayout(names, 1)
        row.addWidget(w.tool("toc", "Table des matières", self._toc))
        root.addWidget(top)

        self.view = QWebEngineView()
        self.page = _Page(self)
        self.view.setPage(self.page)
        self.view.setContextMenuPolicy(Qt.ContextMenuPolicy.NoContextMenu)
        root.addWidget(self.view, 1)

        bottom = QFrame()
        bottom.setObjectName("bar")
        b = QHBoxLayout(bottom)
        b.setContentsMargins(6, 4, 8, 4)
        self.prev = w.tool("skip_previous", "Partie précédente", self.previous)
        b.addWidget(self.prev)
        self.progress = QProgressBar()
        self.progress.setTextVisible(False)
        self.progress.setFixedHeight(4)
        self.progress.setRange(0, 1000)
        middle = QVBoxLayout()
        self.percent = w.label("", "hint", wrap=False)
        self.percent.setAlignment(Qt.AlignmentFlag.AlignCenter)
        middle.addWidget(self.percent)
        middle.addWidget(self.progress)
        b.addLayout(middle, 1)
        self.next = w.tool("skip_next", "Partie suivante", self.next_part)
        b.addWidget(self.next)
        b.addSpacing(16)
        modes = QButtonGroup(self)
        for paged, text in ((True, "Pages"), (False, "Défilement")):
            c = w.chip(text)
            c.setChecked(paged == self.paged)
            c.clicked.connect(lambda _=False, p=paged: self.set_mode(p))
            modes.addButton(c)
            b.addWidget(c)
        b.addWidget(w.tool("text_decrease", "Texte plus petit", lambda: self.change_size(-1)))
        b.addWidget(w.tool("text_increase", "Texte plus grand", lambda: self.change_size(1)))
        themes = QButtonGroup(self)
        for tid, text in THEMES:
            c = w.chip(text)
            c.setChecked(tid == self.theme)
            c.clicked.connect(lambda _=False, t=tid: self.set_theme(t))
            themes.addButton(c)
            b.addWidget(c)
        root.addWidget(bottom)

        for keys, fn in ((("Right", "PgDown", "Space"), lambda: self.turn(1)), (("Left", "PgUp"), lambda: self.turn(-1)),
                         (("Ctrl++", "Ctrl+="), lambda: self.change_size(1)), (("Ctrl+-",), lambda: self.change_size(-1))):
            for k in keys:
                QShortcut(QKeySequence(k), self, activated=fn)

        _install().reader = self
        try:
            self.epub = Epub(self.app.books.file(book_id))
        except Exception as e:  # noqa: BLE001 - shown in the page
            self.view.setHtml(f"<p style='font: 12pt sans-serif; padding: 2em'>Livre illisible : {e}</p>")
            return
        if last:
            try:
                self.index = max(0, min(len(self.epub.spine) - 1, int(last.chapter)))
            except ValueError:
                self.index = 0
            self.start = self.fraction = max(0.0, min(1.0, last.page / 1000))
        self.load()

    # ---- page ----

    def colors(self) -> tuple[str, str, str]:
        t = self.theme if self.theme != "auto" else ("dark" if theme.is_dark() else "light")
        return COLORS[t]

    def css(self) -> str:
        bg, fg, link = self.colors()
        return book_page.css(bg, fg, link, self.text_size, self.paged)

    def script(self, path: str) -> str:
        start = self.start if self.epub and path == self.epub.spine[self.index] else 0.0
        return book_page.script(self.paged, start, path == self.epub.spine[-1])

    def load(self) -> None:
        self.page.setBackgroundColor(QColor(self.colors()[0]))
        path = "/".join(QUrl.toPercentEncoding(p).data().decode() for p in self.epub.spine[self.index].split("/"))
        self.view.setUrl(QUrl(f"{SCHEME.decode()}:/{path}"))
        self.view.setFocus()
        self._status()

    def message(self, m: dict) -> None:
        kind = m.get("t")
        if kind == "position":
            self.fraction = max(0.0, min(1.0, float(m.get("f") or 0)))
            self._save()
        elif kind == "next":
            self.next_part()
        elif kind == "prev":
            self.previous()
        elif kind == "tap" and self.paged:
            x = float(m.get("x") or 0.5)
            if x < 0.3:
                self.turn(-1)
            elif x > 0.7:
                self.turn(1)

    def turn(self, delta: int) -> None:
        if self.paged:
            self.page.runJavaScript(f"window.grimoire && grimoire.turn({delta})")
        else:
            self.page.runJavaScript(f"window.scrollBy(0, {delta} * window.innerHeight * 0.85)")

    def open(self, index: int, at: float = 0.0) -> None:
        if not self.epub or not 0 <= index < len(self.epub.spine):
            return
        self.index, self.start, self.fraction = index, at, at
        self._save()
        self.load()

    def next_part(self) -> None:
        self.open(self.index + 1)

    def previous(self) -> None:
        self.open(self.index - 1, 1.0)  # the previous part opens on its last page

    def open_path(self, path: str) -> None:
        if self.epub and path in self.epub.spine and self.epub.spine.index(path) != self.index:
            self.open(self.epub.spine.index(path))

    def set_mode(self, paged: bool) -> None:
        if paged != self.paged:
            self.paged = paged
            self.start = self.fraction
            self.app.store.update(ops.set_mode(self.key, "pages" if paged else "scroll"))
            self.load()

    def change_size(self, delta: int) -> None:
        size = max(12, min(32, self.text_size + delta))
        if size != self.text_size:
            self.text_size = self.app.config.text_size = size
            self.start = self.fraction
            self.load()

    def set_theme(self, t: str) -> None:
        if t != self.theme:
            self.theme = self.app.config.book_theme = t
            self.start = self.fraction
            self.load()

    def _toc(self) -> None:
        if not self.epub or not self.epub.toc:
            return
        menu = QMenu(self)
        current = self.epub.spine[self.index]
        for entry in self.epub.toc:
            action = menu.addAction(entry.title, lambda e=entry: self.open_path(e.path) if e.path != current else None)
            action.setCheckable(True)
            action.setChecked(entry.path == current)
        menu.exec(self.cursor().pos())

    # ---- position ----

    def _overall(self) -> float:
        n = len(self.epub.spine) if self.epub else 0
        return (self.index + self.fraction) / n if n else 0.0

    def _save(self) -> None:
        if not self.epub:
            return
        title = self.epub.chapter_title(self.index) or f"Partie {self.index + 1}"
        self.app.store.update(ops.book_position(self.key, self.index, title, int(self.fraction * 1000), len(self.epub.spine)), persist=False)
        if self.index == len(self.epub.spine) - 1 and self.fraction >= 0.98 and books.READ not in self.ctrl.progress(self.key).read_ids:
            self.app.store.update(ops.mark_read(self.key, [books.READ]))
        self._status()

    def _status(self) -> None:
        if not self.epub:
            return
        n = len(self.epub.spine)
        self.part.setText(self.epub.chapter_title(self.index) or f"Partie {self.index + 1}")
        self.percent.setText(f"{int(self._overall() * 100)} % · partie {self.index + 1} / {n}")
        self.progress.setValue(int(self._overall() * 1000))
        self.prev.setEnabled(self.index > 0)
        self.next.setEnabled(self.index < n - 1)

    def closing(self) -> None:
        self.app.store.flush()
        if _handler and _handler.reader is self:
            _handler.reader = None
        if self.epub:
            self.epub.close()

