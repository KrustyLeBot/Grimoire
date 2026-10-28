"""Downloaded chapters, in the app's own folder (never synced): one folder per chapter with its
pages (0001.webp…) and a chapter.json written last, so a folder without it is unfinished.
One chapter at a time, in order; a failure is retried twice."""
from __future__ import annotations

import json
import shutil
import threading
from pathlib import Path

from PySide6.QtCore import QObject, Signal

from ..core.model import ChapterInfo
from ..sources.source import Images
from .async_util import run_async

META = "chapter.json"


def key(source: str, slug: str, chapter: str) -> str:
    return f"{source}:{slug.replace('/', '_')}:{chapter}"


class Downloads(QObject):
    changed = Signal()
    failed = Signal(str)

    def __init__(self, root: Path, sources):
        super().__init__()
        self.root = Path(root) / "downloads"
        self.root.mkdir(parents=True, exist_ok=True)
        self.sources = sources
        self.done: set[str] = self._scan()
        self.running: dict[str, float] = {}  # key -> progress 0..1, -1 while waiting
        self._queue: list[tuple[str, str, ChapterInfo]] = []
        self._busy = False
        self._lock = threading.Lock()

    def dir(self, source: str, slug: str, chapter: str) -> Path:
        return self.root / source / slug.replace("/", "_") / chapter

    def is_done(self, source: str, slug: str, chapter: str) -> bool:
        return key(source, slug, chapter) in self.done

    def pages(self, source: str, slug: str, chapter: str) -> list[Path] | None:
        if not self.is_done(source, slug, chapter):
            return None
        return sorted(p for p in self.dir(source, slug, chapter).iterdir() if p.name != META)

    def any_done(self, source: str, slug: str) -> bool:
        prefix = f"{source}:{slug.replace('/', '_')}:"
        return any(k.startswith(prefix) for k in self.done)

    def enqueue(self, source: str, slug: str, chapters: list[ChapterInfo]) -> None:
        for ch in chapters:
            k = key(source, slug, ch.id)
            if ch.locked or k in self.done or k in self.running:
                continue
            self.running[k] = -1
            self._queue.append((source, slug, ch))
        self.changed.emit()
        self._next()

    def cancel_all(self) -> None:
        self._queue.clear()
        self.running = {k: v for k, v in self.running.items() if v >= 0}
        self.changed.emit()

    def _next(self) -> None:
        if self._busy or not self._queue:
            return
        self._busy = True
        source, slug, ch = self._queue.pop(0)
        k = key(source, slug, ch.id)

        def work(attempt: int = 0):
            src = self.sources.get(source)
            if src is None:
                raise OSError("Source introuvable")
            target = self.dir(source, slug, ch.id)
            try:
                content = src.content(slug, ch)
                if not isinstance(content, Images):
                    raise OSError("Chapitre sans images")
                shutil.rmtree(target, ignore_errors=True)
                target.mkdir(parents=True)
                for i, url in enumerate(content.urls):
                    ext = (url.split("?")[0].rsplit(".", 1)[-1] if "." in url.split("?")[0].rsplit("/", 1)[-1] else "webp")[:4].lower()
                    with src.http.stream(url, src.image_headers()) as r:
                        part = target / f"{i + 1:04d}.{ext}.part"
                        with open(part, "wb") as out:
                            for chunk in r.iter_content(128 * 1024):
                                out.write(chunk)
                        part.replace(target / f"{i + 1:04d}.{ext}")
                    with self._lock:
                        self.running[k] = (i + 1) / len(content.urls)
                    self.changed.emit()  # queued to the UI thread by Qt
                (target / META).write_text(json.dumps({"name": ch.name, "pages": len(content.urls)}), encoding="utf-8")
            except Exception:
                shutil.rmtree(target, ignore_errors=True)
                if attempt < 2:
                    return work(attempt + 1)
                raise

        def finished(_=None):
            self.running.pop(k, None)
            self.done.add(k)
            self._busy = False
            self.changed.emit()
            self._next()

        def failed(e):
            self.running.pop(k, None)
            self._busy = False
            self.failed.emit(f"{ch.name} : {e}")
            self.changed.emit()
            self._next()

        run_async(work, finished, failed)

    def delete_series(self, source: str, slug: str) -> None:
        shutil.rmtree(self.root / source / slug.replace("/", "_"), ignore_errors=True)
        self.done = self._scan()
        self.changed.emit()

    def delete_all(self) -> None:
        self.cancel_all()
        shutil.rmtree(self.root, ignore_errors=True)
        self.root.mkdir(parents=True, exist_ok=True)
        self.done = set()
        self.changed.emit()

    def size_bytes(self) -> int:
        return sum(f.stat().st_size for f in self.root.rglob("*") if f.is_file())

    def _scan(self) -> set[str]:
        return {key(s.name, series.name, ch.name) for s in self.root.iterdir() if s.is_dir()
                for series in s.iterdir() if series.is_dir() for ch in series.iterdir() if (ch / META).exists()}
