"""The synced state and its files in the Grimoire folder that Proton Drive syncs with the phone.

Every change is an operation on the state. Changes made with persist=False (the reader's position,
at every page) stay in memory until the next flush or persisted change, and are applied again on
top of the files when they are re-read after a sync brought a new version. Every write is atomic
(temp file + rename); an unreadable file raises `error` and is never overwritten.
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Callable

from . import codec
from .model import AppData, SourceConfig
from .ops import Op

SOURCES, LIBRARY, PROGRESS = "sources.json", "library.json", "progress.json"
SYNCIGNORE_TEXT = "# Read by Synchrotron: downloads and caches live in the app's private storage, nothing to exclude here.\n"


class Store:
    def __init__(self, root: Path, default_sources: Callable[[], tuple[SourceConfig, ...]]):
        self.root = Path(root)
        self._default_sources = default_sources
        self.data = AppData()
        self.error: str | None = None
        self._written = AppData()
        self._pending: list[Op] = []
        self._listeners: list[Callable[[AppData], None]] = []

    def listen(self, callback: Callable[[AppData], None]) -> None:
        self._listeners.append(callback)

    def _notify(self) -> None:
        for callback in list(self._listeners):
            callback(self.data)

    def load(self) -> None:
        """Reads the files (at start, or after Proton Drive changed them), then what was not written yet."""
        try:
            sources = self._read(SOURCES)
            loaded = AppData(
                sources=codec.decode_sources(sources) if sources is not None else self._default_sources(),
                web_searches=codec.decode_web_searches(sources) if sources is not None else (),
                library=codec.decode_library(self._read(LIBRARY) or '{"items": []}'),
                progress=codec.decode_progress(self._read(PROGRESS) or '{"series": {}}'),
            )
            self.error = None
        except (OSError, ValueError) as e:
            self.error = str(e)
            self._notify()
            return
        self._written = loaded
        data = loaded
        for op in self._pending:
            data = op(data)
        self.data = data
        self._notify()

    def update(self, op: Op, persist: bool = True) -> None:
        if self.error:
            return
        after = op(self.data)
        if after == self.data:
            return
        self.data = after
        if persist:
            self._write()
        else:
            self._pending.append(op)
        self._notify()

    def flush(self) -> None:
        if not self.error:
            self._write()

    def differs_on_disk(self) -> bool:
        """Whether a file no longer holds what this app wrote (another device's change came in)."""
        try:
            return any(self._read(name) != text for name, text in self._texts(self._written).items())
        except (OSError, ValueError):
            return True

    def _texts(self, d: AppData) -> dict[str, str]:
        return {
            SOURCES: codec.encode_sources(d.sources, d.web_searches),
            LIBRARY: codec.encode_library(d.library),
            PROGRESS: codec.encode_progress(d.progress),
        }

    def _write(self) -> None:
        now = self.data
        try:
            self.root.mkdir(parents=True, exist_ok=True)
            if now.sources != self._written.sources or now.web_searches != self._written.web_searches:
                self._save(SOURCES, codec.encode_sources(now.sources, now.web_searches))
            if now.library != self._written.library:
                self._save(LIBRARY, codec.encode_library(now.library))
            if now.progress != self._written.progress:
                self._save(PROGRESS, codec.encode_progress(now.progress))
            if now != self._written:
                ignore = self.root / ".syncignore"
                if not ignore.exists():
                    ignore.write_text(SYNCIGNORE_TEXT, encoding="utf-8")
            self._written = now
            self._pending.clear()
        except OSError as e:
            self.error = f"Enregistrement impossible : {e}"

    def _read(self, name: str) -> str | None:
        path = self.root / name
        if not path.exists():
            return None
        text = path.read_text(encoding="utf-8")
        try:
            codec._root(text)
        except ValueError as e:
            raise ValueError(f"{path} est illisible ({e}). Il ne sera pas modifié.") from e
        return text

    def _save(self, name: str, text: str) -> None:
        target = self.root / name
        tmp = self.root / f"{name}.tmp"
        tmp.write_text(text, encoding="utf-8")
        os.replace(tmp, target)
