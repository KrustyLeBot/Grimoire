"""Everything the screens share: the synced store, the sources, caches, downloads and books."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QObject, QStandardPaths, Signal

from .books.lookup import Lookup
from .books.store import Books
from .config import Config
from .core.series_cache import SeriesCache
from .core.store import Store
from .services.downloads import Downloads
from .services.images import Images
from .sources.catalog import Sources, default_sources
from .sources.http import Http


def data_dir() -> Path:
    path = Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.AppLocalDataLocation) or Path.home() / ".grimoire")
    path.mkdir(parents=True, exist_ok=True)
    return path


class App(QObject):
    data_changed = Signal()
    books_changed = Signal()
    notice = Signal(str)

    def __init__(self, config: Config, workdir: Path, private: Path | None = None):
        super().__init__()
        self.config = config
        private = private or data_dir()
        self.private = private
        self.http = Http()
        self.store = Store(workdir, default_sources)
        self.store.listen(lambda _: self.data_changed.emit())
        self.sources = Sources(self.store, self.http)
        self.cache = SeriesCache(private / "series")
        self.images = Images(private, self.http)
        self.downloads = Downloads(private, self.sources)
        self.books = Books(private)
        self.lookup = Lookup(self.http)
        self.store.load()

    @property
    def data(self):
        return self.store.data

