"""The window: one screen at a time, stacked like the phone's navigation (Échap goes back)."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QEvent, QFileSystemWatcher, QTimer
from PySide6.QtGui import QKeySequence, QShortcut
from PySide6.QtWidgets import QApplication, QFileDialog, QFrame, QHBoxLayout, QLabel, QMainWindow, QMessageBox, QStackedWidget

from ..controller import Controller
from ..services import updater
from ..services.async_util import run_async
from . import theme
from . import widgets as w
from .book_reader import BookReaderScreen
from .book_screen import BookScreen
from .history_screen import HistoryScreen
from .home import HomeScreen
from .reader_screen import ReaderScreen
from .series_screen import SeriesScreen
from .settings_screen import SettingsScreen
from .sources_screen import SourceEditScreen, SourcesScreen
from .update_toast import UpdateToast
from .web import WebScreen

EPUB_FILTER = "Livres EPUB (*.epub);;Tous les fichiers (*)"


def ask_workdir(parent, first_run: bool, start: Path | None = None) -> Path | None:
    if first_run:
        QMessageBox.information(
            parent, "Grimoire",
            "Choisis le dossier Grimoire synchronisé avec le téléphone (Documents/Grimoire) : celui du client Proton Drive, "
            "ou Apps/Grimoire sur le partage du serveur Synchrotron.\n"
            "S'il est vide, Grimoire y créera ta bibliothèque.")
    chosen = QFileDialog.getExistingDirectory(parent, "Dossier Grimoire", str(start or Path.home()))
    return Path(chosen) if chosen else None


class MainWindow(QMainWindow):
    def __init__(self, app):
        super().__init__()
        self.app = app
        self.ctrl = Controller(app)
        self.toast: UpdateToast | None = None
        self.settings_screen: SettingsScreen | None = None
        self.setWindowTitle("Grimoire")
        self.resize(1200, 820)
        self.setAcceptDrops(True)
        self.stack = QStackedWidget()
        self.setCentralWidget(self.stack)
        self.home = HomeScreen(self)
        self.stack.addWidget(self.home)

        self.snack = QFrame(self)
        self.snack.setObjectName("snack")
        row = QHBoxLayout(self.snack)
        row.setContentsMargins(16, 8, 16, 8)
        self.snack_text = QLabel()
        row.addWidget(self.snack_text)
        self.snack.hide()
        self.snack_timer = QTimer(self, singleShot=True, interval=4500)
        self.snack_timer.timeout.connect(self.snack.hide)
        app.notice.connect(self.show_notice)
        app.downloads.failed.connect(lambda m: self.show_notice(f"Téléchargement impossible : {m}"))
        self.ctrl.book_imported.connect(self._book_imported)

        QShortcut(QKeySequence("Escape"), self, activated=self.back)
        QShortcut(QKeySequence("Alt+Left"), self, activated=self.back)
        QShortcut(QKeySequence("Ctrl+O"), self, activated=self.pick_book)
        QShortcut(QKeySequence("F11"), self, activated=lambda: self.showNormal() if self.isFullScreen() else self.showFullScreen())

        # Another device's files can come in at any time (Proton Drive client, server share): debounce, then re-read.
        self.watcher = QFileSystemWatcher(self)
        self.watch_timer = QTimer(self, singleShot=True, interval=1000)
        self.watch_timer.timeout.connect(self._disk_changed)
        self.watcher.directoryChanged.connect(lambda _: self.watch_timer.start())
        self.watcher.fileChanged.connect(lambda _: self.watch_timer.start())
        self._watch()
        # Over the server's SMB share Windows does not always report changes: also look every 30 s and
        # whenever the window comes back to the front (cheap: re-reads three small files).
        self.poll_timer = QTimer(self, interval=30_000)
        self.poll_timer.timeout.connect(self._disk_changed)
        self.poll_timer.start()

        self._cleanup_timer = QTimer(self)
        self._cleanup_timer.timeout.connect(lambda: updater.cleanup_previous() and self._cleanup_timer.stop())
        if not updater.cleanup_previous():
            self._cleanup_timer.start(2000)
        QTimer.singleShot(5000, self.check_updates)
        self._update_timer = QTimer(self)
        self._update_timer.timeout.connect(self.check_updates)
        self._update_timer.start(6 * 3600 * 1000)
        QTimer.singleShot(1500, lambda: self.ctrl.refresh_library(force=False))
        if app.config.geometry:
            self.restoreGeometry(app.config.geometry)
        self.home.shown()

    # ---- navigation ----

    def open(self, screen) -> None:
        self.stack.addWidget(screen)
        self.stack.setCurrentWidget(screen)

    def back(self) -> None:
        current = self.stack.currentWidget()
        if current is self.home:
            return
        if hasattr(current, "closing"):
            current.closing()
        self.stack.removeWidget(current)
        current.deleteLater()
        if current is self.settings_screen:
            self.settings_screen = None
        top = self.stack.widget(self.stack.count() - 1)
        self.stack.setCurrentWidget(top)
        if hasattr(top, "shown"):
            top.shown()

    def show_series(self, source: str, slug: str, title: str, cover: str | None) -> None:
        self.open(SeriesScreen(self, source, slug, title, cover))

    def show_reader(self, source: str, details, chapter_id: str) -> None:
        if any(not c.locked for c in details.chapters):
            self.open(ReaderScreen(self, source, details, chapter_id))

    def show_book(self, book_id: str) -> None:
        self.open(BookScreen(self, book_id))

    def show_book_reader(self, book_id: str) -> None:
        self.open(BookReaderScreen(self, book_id))

    def show_web(self, url: str, title: str) -> None:
        self.open(WebScreen(self, url, title))

    def show_settings(self) -> None:
        self.settings_screen = SettingsScreen(self)
        self.open(self.settings_screen)

    def show_sources(self) -> None:
        self.open(SourcesScreen(self))

    def show_source(self, source_id: str) -> None:
        self.open(SourceEditScreen(self, source_id))

    def show_history(self) -> None:
        self.open(HistoryScreen(self))

    def _book_imported(self, book_id: str) -> None:
        current = self.stack.currentWidget()
        if isinstance(current, BookScreen) and current.book_id == book_id:
            current.render()
        else:
            self.show_book(book_id)
        self.raise_()
        self.activateWindow()

    # ---- books coming in ----

    def pick_book(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Ajouter un livre", str(Path.home()), EPUB_FILTER)
        if path:
            self.ctrl.import_book(path)

    def open_paths(self, paths: list[str]) -> None:
        """EPUBs opened with Grimoire (Explorer's "Ouvrir avec", a second launch) or dropped on the window."""
        for p in paths:
            if p.lower().startswith(("http://", "https://")):
                self.ctrl.download_book(p)
            elif Path(p).is_file():
                self.ctrl.import_book(p)

    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()

    def dropEvent(self, e):
        self.open_paths([u.toLocalFile() or u.toString() for u in e.mimeData().urls()])

    # ---- folder ----

    def choose_workdir(self) -> None:
        chosen = ask_workdir(self, first_run=False, start=self.app.store.root)
        if chosen:
            self.app.store.flush()
            self.app.config.workdir = chosen
            self.app.store.root = chosen
            self.app.store.load()
            self._watch()

    def _watch(self) -> None:
        if self.watcher.files():
            self.watcher.removePaths(self.watcher.files())
        if self.watcher.directories():
            self.watcher.removePaths(self.watcher.directories())
        root = self.app.store.root
        if root.is_dir():
            self.watcher.addPath(str(root))
            files = [str(root / n) for n in ("sources.json", "library.json", "progress.json") if (root / n).exists()]
            if files:
                self.watcher.addPaths(files)

    def changeEvent(self, event) -> None:
        super().changeEvent(event)
        if event.type() == QEvent.Type.ActivationChange and self.isActiveWindow():
            self._disk_changed()

    def _disk_changed(self) -> None:
        self._watch()  # a file replaced by a rename is no longer watched
        if self.app.store.differs_on_disk():
            self.app.store.load()

    # ---- look ----

    def set_theme(self, mode: str) -> None:
        self.app.config.theme = mode
        theme.apply_theme(QApplication.instance(), mode)
        w.retint(self)
        self.home.library.reload()

    def show_notice(self, text: str) -> None:
        self.snack_text.setText(text)
        self.snack.adjustSize()
        self.snack.move((self.width() - self.snack.width()) // 2, self.height() - self.snack.height() - 24)
        self.snack.show()
        self.snack.raise_()
        self.snack_timer.start()

    def resizeEvent(self, e):
        super().resizeEvent(e)
        if self.toast and self.toast.isVisible():
            self.toast.place()

    # ---- self-update ----

    def check_updates(self, manual: bool = False) -> None:
        def done(release):
            if release and updater.is_newer(release):
                if self.toast:
                    self.toast.deleteLater()
                self.toast = UpdateToast(self, release)
                self.toast.show()
                text = f"Build {release.build} disponible."
            else:
                text = "Tu as la dernière version."
            if manual and self.settings_screen:
                self.settings_screen.set_update_status(text)

        def failed(e):
            if manual and self.settings_screen:
                self.settings_screen.set_update_status(f"Vérification impossible : {e}")
        run_async(updater.latest, done, failed)

    def closeEvent(self, e):
        current = self.stack.currentWidget()
        if hasattr(current, "closing"):
            current.closing()
        self.app.store.flush()
        self.app.config.geometry = self.saveGeometry()
        super().closeEvent(e)
