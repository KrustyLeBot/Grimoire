"""The last fetched details and chapter list of each series, private, for offline browsing."""
from __future__ import annotations

import json
import os
from pathlib import Path

from .model import ChapterInfo, Kind, SeriesDetails


class SeriesCache:
    def __init__(self, root: Path):
        self.root = Path(root)

    def _file(self, source: str, slug: str) -> Path:
        return self.root / source / f"{slug.replace('/', '_')}.json"

    def save(self, source: str, d: SeriesDetails) -> None:
        f = self._file(source, d.slug)
        f.parent.mkdir(parents=True, exist_ok=True)
        tmp = f.with_suffix(".tmp")
        tmp.write_text(json.dumps(encode(d), ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, f)

    def load(self, source: str, slug: str) -> SeriesDetails | None:
        f = self._file(source, slug)
        try:
            return decode(json.loads(f.read_text(encoding="utf-8"))) if f.exists() else None
        except (OSError, ValueError, KeyError):
            return None


def encode(d: SeriesDetails) -> dict:
    return {"slug": d.slug, "title": d.title, "cover": d.cover, "author": d.author, "artist": d.artist, "status": d.status,
            "genres": list(d.genres), "description": d.description, "kind": d.kind.value,
            "chapters": [{"id": c.id, "name": c.name, "number": c.number, "date": c.date, "locked": c.locked} for c in d.chapters]}


def decode(o: dict) -> SeriesDetails:
    return SeriesDetails(
        slug=o["slug"], title=o.get("title", ""), cover=o.get("cover") or None, author=o.get("author") or None,
        artist=o.get("artist") or None, status=o.get("status") or None, genres=tuple(o.get("genres") or ()),
        description=o.get("description") or None, kind=Kind.of(o.get("kind")),
        chapters=tuple(ChapterInfo(str(c["id"]), c.get("name", ""), float(c.get("number") or 0), int(c.get("date") or 0),
                                   bool(c.get("locked"))) for c in o.get("chapters") or ()),
    )
