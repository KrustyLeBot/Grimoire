"""The EPUB files of this computer, in the app's own folder: books are not synced (only their
progress is, under their id). A book's id is `isbn-<ISBN-13>`, so the same edition is the same
book on every device; without ISBN it is a hash of the EPUB's own identifier (as on Android).
"""
from __future__ import annotations

import hashlib
import os
import shutil
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from ..core.model import LastRead
from . import info as info_
from .epub import Epub, EpubError

SOURCE = "files"  # library items and progress of books use this as their source
READ = "book"  # the "chapter" marked read when the whole book is


@dataclass(frozen=True)
class Imported:
    id: str
    title: str
    authors: tuple[str, ...]
    language: str | None
    isbns: tuple[str, ...]
    replaced: bool
    link: str | None = None
    page: str | None = None


def id_of(isbn: str | None, unique_id: str | None, title: str, authors: list[str]) -> str:
    if isbn:
        return f"isbn-{isbn}"
    seed = unique_id or f"{title}|{', '.join(authors)}"
    return "id-" + hashlib.sha1(seed.encode("utf-8")).hexdigest()[:16]


def isbn_of(book_id: str) -> str | None:
    return book_id[5:] if book_id.startswith("isbn-") else None


def percent(last: LastRead) -> int:
    """How far into the book a position is, in percent."""
    try:
        index = int(last.chapter)
    except ValueError:
        return 0
    return 0 if last.pages <= 0 else max(0, min(100, int((index + last.page / 1000) / last.pages * 100)))


class Books:
    def __init__(self, root: Path):
        self.dir = Path(root) / "books"
        self.info_dir = Path(root) / "book-info"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.info_dir.mkdir(parents=True, exist_ok=True)

    def file(self, book_id: str) -> Path:
        return self.dir / f"{book_id}.epub"

    def present(self) -> set[str]:
        return {p.stem for p in self.dir.glob("*.epub")}

    def local_cover(self, book_id: str) -> str | None:
        """The cover inside the EPUB, extracted at import: shown when the catalogues have none."""
        f = self.dir / f"{book_id}.cover"
        return f.as_uri() if f.exists() else None

    def import_file(self, path: Path, known: Callable[[list[str]], str | None], link: str | None = None,
                    page: str | None = None) -> Imported:
        tmp = self.dir / f"import-{time.time_ns()}.tmp"
        shutil.copyfile(path, tmp)
        try:
            with open(tmp, "rb") as f:
                if f.read(2) != b"PK":
                    raise EpubError("Ce fichier n'est pas un EPUB")
            with Epub(tmp) as e:
                book_id = known(e.isbns) or id_of(e.isbn, e.unique_id, e.title, e.authors)
                if e.cover_path:
                    data = e.read_bytes(e.cover_path)
                    if data:
                        (self.dir / f"{book_id}.cover").write_bytes(data)
                imported = Imported(book_id, e.title, tuple(e.authors), e.language, tuple(e.isbns),
                                    self.file(book_id).exists(), link, page)
            os.replace(tmp, self.file(book_id))
            return imported
        finally:
            if tmp.exists():
                tmp.unlink()

    def delete(self, book_id: str) -> None:
        for f in (self.file(book_id), self.dir / f"{book_id}.cover"):
            if f.exists():
                f.unlink()

    def delete_all(self) -> None:
        for f in self.dir.iterdir():
            if f.is_file():
                f.unlink()

    def size_bytes(self) -> int:
        return sum(f.stat().st_size for f in self.dir.iterdir() if f.is_file())

    def info(self, book_id: str) -> info_.BookInfo | None:
        f = self.info_dir / f"{book_id}.json"
        try:
            return info_.decode(f.read_text(encoding="utf-8")) if f.exists() else None
        except (OSError, ValueError, TypeError):
            return None

    def save_info(self, book_id: str, value: info_.BookInfo) -> None:
        (self.info_dir / f"{book_id}.json").write_text(info_.encode(value), encoding="utf-8")
