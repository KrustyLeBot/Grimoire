"""JSON of the synced files, exactly as the Android app writes them (docs/SPECS.md §3)."""
from __future__ import annotations

import json

from .model import Kind, LastRead, LibraryItem, SeriesProgress, SourceConfig, WebSearch

FORMAT_VERSION = 1


class FormatError(ValueError):
    pass


def _root(text: str) -> dict:
    data = json.loads(text)
    if not isinstance(data, dict):
        raise FormatError("le fichier n'est pas un objet JSON")
    version = int(data.get("format_version", 1))
    if version > FORMAT_VERSION:
        raise FormatError(f"format {version} trop récent pour cette version de Grimoire")
    return data


def _dump(data: dict) -> str:
    return json.dumps({"format_version": FORMAT_VERSION, **data}, ensure_ascii=False, indent=2)


def encode_sources(sources: tuple[SourceConfig, ...], searches: tuple[WebSearch, ...] = ()) -> str:
    return _dump({
        "sources": [{"id": s.id, "type": s.type, "name": s.name, "baseUrl": s.base_url, "enabled": s.enabled,
                     "options": dict(s.options)} for s in sources],
        "web_searches": [{"name": w.name, "url": w.url} for w in searches],
    })


def decode_sources(text: str) -> tuple[SourceConfig, ...]:
    return tuple(
        SourceConfig(o["id"], o["type"], o.get("name", ""), o["baseUrl"], bool(o.get("enabled", True)),
                     tuple((k, str(v)) for k, v in (o.get("options") or {}).items()))
        for o in _root(text).get("sources") or []
    )


def decode_web_searches(text: str) -> tuple[WebSearch, ...]:
    return tuple(WebSearch(o.get("name", ""), o["url"]) for o in _root(text).get("web_searches") or [])


def encode_library(items: tuple[LibraryItem, ...]) -> str:
    out = []
    for i in items:
        o = {"source": i.source, "slug": i.slug, "title": i.title}
        if i.cover:
            o["cover"] = i.cover
        o["kind"] = i.kind.value
        o["added"] = i.added
        if i.isbns:
            o["isbns"] = list(i.isbns)
        if i.link:
            o["link"] = i.link
        if i.page:
            o["page"] = i.page
        out.append(o)
    return _dump({"items": out})


def decode_library(text: str) -> tuple[LibraryItem, ...]:
    return tuple(
        LibraryItem(o["source"], o["slug"], o.get("title", ""), o.get("cover") or None, Kind.of(o.get("kind")),
                    int(o.get("added") or 0), tuple(o.get("isbns") or ()), o.get("link") or None, o.get("page") or None)
        for o in _root(text).get("items") or []
    )


def encode_progress(progress: tuple[tuple[str, SeriesProgress], ...]) -> str:
    series = {}
    for key, p in progress:
        o = {"read": dict(p.read), "pages": dict(p.pages)}
        if p.mode:
            o["mode"] = p.mode
        if p.last:
            o["last"] = {"chapter": p.last.chapter, "name": p.last.chapter_name, "page": p.last.page,
                         "pages": p.last.pages, "at": p.last.at}
        series[key] = o
    return _dump({"series": series})


def decode_progress(text: str) -> tuple[tuple[str, SeriesProgress], ...]:
    out = []
    for key, o in (_root(text).get("series") or {}).items():
        last = o.get("last")
        out.append((key, SeriesProgress(
            read=tuple((k, int(v)) for k, v in (o.get("read") or {}).items()),
            last=LastRead(str(last["chapter"]), last.get("name", ""), int(last.get("page") or 0), int(last.get("pages") or 0),
                          int(last.get("at") or 0)) if last else None,
            mode=o.get("mode") or None,
            pages=tuple((k, int(v)) for k, v in (o.get("pages") or {}).items()),
        )))
    return tuple(out)


