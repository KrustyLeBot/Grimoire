"""Covers and pages: fetched with their source's headers, kept in a disk cache and a small memory cache."""
from __future__ import annotations

import hashlib
from collections import OrderedDict
from pathlib import Path
from typing import Callable

from PySide6.QtCore import QObject, QUrl, Signal
from PySide6.QtGui import QImage

from ..sources.http import Http
from .async_util import run_async


class Images(QObject):
    loaded = Signal(str)  # url

    def __init__(self, root: Path, http: Http, memory: int = 300):
        super().__init__()
        self.dir = Path(root) / "images"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.http = http
        self._memory: OrderedDict[str, QImage] = OrderedDict()
        self._limit = memory
        self._waiting: dict[str, list[Callable[[QImage | None], None]]] = {}

    def cached(self, url: str) -> QImage | None:
        image = self._memory.get(url)
        if image is not None:
            self._memory.move_to_end(url)
        return image

    def _remember(self, url: str, image: QImage) -> None:
        self._memory[url] = image
        while len(self._memory) > self._limit:
            self._memory.popitem(last=False)

    def get(self, url: str, headers: dict[str, str] | None, done: Callable[[QImage | None], None], keep: bool = True) -> None:
        """done(image or None) on the UI thread. keep=False for reader pages, which would flood the memory cache."""
        image = self.cached(url)
        if image is not None:
            done(image)
            return
        if url in self._waiting:
            self._waiting[url].append(done)
            return
        self._waiting[url] = [done]

        def fetch() -> QImage:
            local = QUrl(url).toLocalFile() if url.startswith("file:") else (url if Path(url).is_absolute() and Path(url).exists() else None)
            if local:
                img = QImage(local)
            else:
                path = self.dir / hashlib.sha1(url.encode()).hexdigest()
                if not path.exists():
                    with self.http.stream(url, headers, timeout=40) as r:
                        tmp = path.with_suffix(".part")
                        with open(tmp, "wb") as out:
                            for chunk in r.iter_content(128 * 1024):
                                out.write(chunk)
                        tmp.replace(path)
                img = QImage(str(path))
            if img.isNull():
                raise OSError("image illisible")
            return img

        def finish(img: QImage | None) -> None:
            if img is not None and keep:
                self._remember(url, img)
            for callback in self._waiting.pop(url, []):
                callback(img)
            self.loaded.emit(url)

        run_async(fetch, finish, lambda _e: finish(None))

    def size_bytes(self) -> int:
        return sum(f.stat().st_size for f in self.dir.iterdir() if f.is_file())
