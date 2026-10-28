"""What catalogues know of a book edition (cached on each machine, never synced)."""
from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, field, replace

from bs4 import BeautifulSoup

from . import isbn as isbns_


@dataclass(frozen=True)
class BookInfo:
    title: str
    subtitle: str | None = None
    authors: tuple[str, ...] = ()
    publisher: str | None = None
    published: str | None = None
    pages: int | None = None
    language: str | None = None  # ISO 639-1
    description: str | None = None
    cover: str | None = None
    isbns: tuple[str, ...] = field(default=())

    def merged(self, other: "BookInfo | None") -> "BookInfo":
        """Fields this one lacks, taken from other; ISBNs are merged."""
        if other is None:
            return self
        return replace(self, subtitle=self.subtitle or other.subtitle, authors=self.authors or other.authors,
                       publisher=self.publisher or other.publisher, published=self.published or other.published,
                       pages=self.pages or other.pages, language=self.language or other.language,
                       description=self.description or other.description, cover=self.cover or other.cover,
                       isbns=tuple(dict.fromkeys(self.isbns + other.isbns)))


def encode(i: BookInfo) -> str:
    return json.dumps(asdict(i), ensure_ascii=False)


def decode(text: str) -> BookInfo:
    o = json.loads(text)
    o["authors"] = tuple(o.get("authors") or ())
    o["isbns"] = tuple(o.get("isbns") or ())
    return BookInfo(**{k: v for k, v in o.items() if k in BookInfo.__dataclass_fields__})


def _str(o: dict, key: str) -> str | None:
    v = o.get(key)
    return v.strip() or None if isinstance(v, str) else (str(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None)


def _names(a) -> tuple[str, ...]:
    return tuple(n for n in ((x.get("name") or "").strip() for x in (a or []) if isinstance(x, dict)) if n)


def from_open_library(text: str, isbn: str) -> BookInfo | None:
    """Open Library `api/books?jscmd=data` answer for one ISBN."""
    b = json.loads(text).get(f"ISBN:{isbn}")
    if not isinstance(b, dict) or not _str(b, "title"):
        return None
    ids = b.get("identifiers") or {}
    found = [isbn] + list(ids.get("isbn_13") or []) + list(ids.get("isbn_10") or [])
    cover = b.get("cover") or {}
    pages = b.get("number_of_pages")
    return BookInfo(
        title=_str(b, "title"), subtitle=_str(b, "subtitle"), authors=_names(b.get("authors")),
        publisher=next(iter(_names(b.get("publishers"))), None), published=_str(b, "publish_date"),
        pages=pages if isinstance(pages, int) and pages > 0 else None,
        cover=cover.get("large") or cover.get("medium"),
        isbns=tuple(dict.fromkeys(i for i in (isbns_.normalize(x) for x in found) if i)),
    )


def from_open_library_search(text: str) -> BookInfo | None:
    """First work of a `search.json` answer, without its ISBNs (they mix editions and languages)."""
    docs = json.loads(text).get("docs") or []
    if not docs or not _str(docs[0], "title"):
        return None
    d = docs[0]
    return BookInfo(title=_str(d, "title"), authors=tuple(d.get("author_name") or ()), published=_str(d, "first_publish_year"),
                    cover=f"https://covers.openlibrary.org/b/id/{d['cover_i']}-L.jpg" if d.get("cover_i") else None)


def work_of(text: str) -> str | None:
    """Open Library edition record (`/isbn/<isbn>.json`): the key of its work."""
    works = json.loads(text).get("works") or []
    return works[0].get("key") if works and isinstance(works[0], dict) else None


def summary_of(text: str) -> str | None:
    d = json.loads(text).get("description")
    value = d.get("value") if isinstance(d, dict) else d
    return value.strip() or None if isinstance(value, str) else None


_BIBLIOGRAPHIC = {"fre": "fr", "fra": "fr", "eng": "en", "ger": "de", "deu": "de", "spa": "es", "ita": "it",
                  "por": "pt", "dut": "nl", "nld": "nl", "jpn": "ja", "chi": "zh", "zho": "zh", "kor": "ko"}


def iso639(code: str) -> str:
    c = code.strip().lower()
    return _BIBLIOGRAPHIC.get(c, c[:2] if len(c) == 2 else c)


def bnf_name(raw: str) -> str:
    """"Saint-Exupéry, Antoine de (1900-1944). Auteur du texte" → "Antoine de Saint-Exupéry"."""
    name = raw.split(" (")[0].split(". ")[0].strip().rstrip(".")
    last, _, first = name.partition(", ")
    return f"{first} {last}" if first else last


def from_bnf(text: str, isbn: str) -> BookInfo | None:
    """BnF catalogue SRU answer (Dublin Core): the reference for books published in France."""
    soup = BeautifulSoup(text, "html.parser")
    record = soup.find("oai_dc:dc")
    if record is None:
        return None

    def all_of(tag: str) -> list[str]:
        return [t.get_text().strip() for t in record.find_all(tag) if t.get_text().strip()]
    titles = all_of("dc:title")
    title = titles[0].split(" / ")[0].split(" : ")[0].strip() if titles else ""
    if not title:
        return None
    pages = next((int(m.group(1)) for f in all_of("dc:format") for m in [re.search(r"(\d+)\s*p\.", f)] if m), None)
    publishers, dates, languages = all_of("dc:publisher"), all_of("dc:date"), all_of("dc:language")
    descriptions = [d for d in all_of("dc:description") if len(d) >= 150]  # notes are short, a summary is a paragraph
    return BookInfo(
        title=title, authors=tuple(n for n in (bnf_name(c) for c in all_of("dc:creator")) if n),
        publisher=publishers[0].split(" (")[0].strip() if publishers else None,
        published=dates[0] if dates else None, pages=pages,
        language=iso639(languages[0]) if languages else None,
        description=max(descriptions, key=len) if descriptions else None,
        isbns=tuple(dict.fromkeys([isbn] + [i for d in all_of("dc:identifier") for i in isbns_.all_in(d)])),
    )
