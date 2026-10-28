"""Settings of this computer (Windows registry), not synced: they depend on the machine."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QSettings


class Config:
    def __init__(self, name: str = "GrimoireWindows"):
        self._s = QSettings("Grimoire", name)

    def _get(self, key: str, default):
        value = self._s.value(key, default)
        if isinstance(default, bool):
            return str(value).lower() == "true"
        if isinstance(default, int):
            try:
                return int(value)
            except (TypeError, ValueError):
                return default
        return value

    def _set(self, key: str, value) -> None:
        self._s.setValue(key, ("true" if value else "false") if isinstance(value, bool) else value)
        self._s.sync()

    @property
    def workdir(self) -> Path | None:
        value = self._get("workdir", "")
        return Path(value) if value else None

    @workdir.setter
    def workdir(self, path: Path) -> None:
        self._set("workdir", str(path))

    geometry = property(lambda self: self._s.value("geometry"), lambda self, v: self._set("geometry", v))
    theme = property(lambda self: str(self._get("theme", "system")), lambda self, v: self._set("theme", v))
    # Pages mode right to left (Japanese manga).
    right_to_left = property(lambda self: self._get("rtl", False), lambda self, v: self._set("rtl", v))
    text_size = property(lambda self: self._get("text_size", 18), lambda self, v: self._set("text_size", int(v)))
    # Page colours of the book reader (auto, light, dark, paper), apart from the app's theme.
    book_theme = property(lambda self: str(self._get("book_theme", "auto")), lambda self, v: self._set("book_theme", v))
    library_checked_at = property(lambda self: self._get("library_checked_at", 0), lambda self, v: self._set("library_checked_at", int(v)))
