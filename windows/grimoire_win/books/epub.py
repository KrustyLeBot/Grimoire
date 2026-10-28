"""An EPUB (2 or 3) opened in place: metadata, reading order (spine) and table of contents.
Paths are zip entry paths, resolved against the package document. Same reading as the Android app.
"""
from __future__ import annotations

import posixpath
import zipfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote

import warnings

from bs4 import BeautifulSoup, XMLParsedAsHTMLWarning

from . import isbn as isbns_


# html.parser on purpose: it keeps "dc:title" names and copes with the stray markup of real books.
warnings.filterwarnings("ignore", category=XMLParsedAsHTMLWarning)


class EpubError(Exception):
    pass


@dataclass(frozen=True)
class TocEntry:
    title: str
    path: str
    fragment: str | None


def _local(tag) -> str:
    return (tag.name or "").split(":")[-1]


def _soup(text: str) -> BeautifulSoup:
    # html.parser keeps prefixed names ("dc:title") and copes with stray markup.
    return BeautifulSoup(text, "html.parser")


def resolve(folder: str, href: str) -> str:
    """href relative to the folder of the document that holds it, as a zip path."""
    href = unquote(href.split("#")[0])
    return posixpath.normpath(posixpath.join(folder, href)).lstrip("/") if href else folder


class Epub:
    def __init__(self, path: Path | str):
        try:
            self._zip = zipfile.ZipFile(path)
        except (zipfile.BadZipFile, OSError) as e:
            raise EpubError(f"Pas un EPUB : {e}") from e
        container = self._read("META-INF/container.xml")
        if container is None:
            raise EpubError("Pas un EPUB : META-INF/container.xml manque")
        rootfile = next((t for t in _soup(container).find_all(True) if _local(t) == "rootfile" and t.get("full-path")), None)
        if rootfile is None:
            raise EpubError("EPUB sans document de paquetage")
        opf_path = rootfile["full-path"]
        opf_text = self._read(opf_path)
        if opf_text is None:
            raise EpubError(f"EPUB incomplet : {opf_path} manque")
        opf = _soup(opf_text)
        base = posixpath.dirname(opf_path)
        package = next((t for t in opf.find_all(True) if _local(t) == "package"), None)
        metadata = next((t for t in opf.find_all(True) if _local(t) == "metadata"), None)

        def meta(name: str):
            return [t for t in metadata.find_all(True) if _local(t) == name] if metadata else []

        def text(t) -> str:
            return t.get_text().strip()

        title = next((text(t) for t in meta("title") if text(t)), "")
        self.title = title or Path(str(path)).stem
        self.authors = [text(t) for t in meta("creator") if text(t)]
        self.language = next((text(t) for t in meta("language")), None) or None
        description = next((text(t) for t in meta("description")), None)
        # Often HTML escaped inside the XML: parsed again to keep only the text.
        self.description = (" ".join(BeautifulSoup(description, "html.parser").get_text().split()) or None) if description else None
        identifiers = meta("identifier")

        def declared(t) -> bool:
            return any(k.split(":")[-1] == "scheme" and v.lower() == "isbn" for k, v in t.attrs.items()) or "isbn" in text(t).lower()
        first = [t for t in identifiers if declared(t)]
        # A UUID can hold digit runs that pass an ISBN-10 check by chance.
        others = [t for t in identifiers if t not in first and "uuid" not in text(t).lower()]
        found: list[str] = []
        for t in first + others + meta("source"):
            for i in isbns_.all_in(text(t)):
                if i not in found:
                    found.append(i)
        self.isbns = found
        unique_key = package.get("unique-identifier") if package else None
        unique = next((t for t in identifiers if t.get("id") == unique_key), identifiers[0] if identifiers else None)
        self.unique_id = (text(unique) or None) if unique is not None else None

        items = [t for t in opf.find_all(True) if _local(t) == "item"]
        manifest = {t.get("id"): t for t in items}

        def path_of(item) -> str | None:
            href = item.get("href") if item is not None else None
            return resolve(base, href) if href else None
        spine_el = next((t for t in opf.find_all(True) if _local(t) == "spine"), None)
        self.spine = [p for p in (path_of(manifest.get(t.get("idref"))) for t in (spine_el.find_all(True) if spine_el else [])
                                  if _local(t) == "itemref") if p]
        if not self.spine:
            raise EpubError("EPUB sans contenu lisible")

        nav = next((t for t in items if "nav" in (t.get("properties") or "").split()), None)
        ncx = manifest.get(spine_el.get("toc")) if spine_el is not None and spine_el.get("toc") else None
        ncx = ncx or next((t for t in items if t.get("media-type") == "application/x-dtbncx+xml"), None)
        self.toc = (self._nav_toc(path_of(nav)) if nav is not None else []) or (self._ncx_toc(path_of(ncx)) if ncx is not None else [])

        cover_id = next((t.get("content") for t in meta("meta") if t.get("name") == "cover"), None)
        cover = next((t for t in items if "cover-image" in (t.get("properties") or "").split()), None)
        cover = cover or (manifest.get(cover_id) if cover_id else None)
        cover = cover or next((t for t in items if (t.get("media-type") or "").startswith("image/") and "cover" in (t.get("id") or "").lower()), None)
        self.cover_path = path_of(cover) if cover is not None else None

    @property
    def isbn(self) -> str | None:
        return self.isbns[0] if self.isbns else None

    def read_bytes(self, path: str) -> bytes | None:
        try:
            return self._zip.read(path)
        except KeyError:
            return None

    def _read(self, path: str) -> str | None:
        data = self.read_bytes(path)
        return data.decode("utf-8", "replace") if data is not None else None

    def chapter_title(self, index: int) -> str | None:
        by_path = {}
        for e in self.toc:
            by_path.setdefault(e.path, e)
        for i in range(index, -1, -1):
            if self.spine[i] in by_path:
                return by_path[self.spine[i]].title
        return None

    def close(self) -> None:
        self._zip.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def _entry(self, title: str, folder: str, href: str) -> TocEntry | None:
        if not href.strip() or not title.strip():
            return None
        return TocEntry(" ".join(title.split()), resolve(folder, href), href.split("#", 1)[1] if "#" in href else None)

    def _nav_toc(self, path: str | None) -> list[TocEntry]:
        text = self._read(path) if path else None
        if not text:
            return []
        doc = BeautifulSoup(text, "html.parser")
        navs = doc.find_all("nav")
        nav = next((n for n in navs if n.get("epub:type") == "toc"), navs[0] if navs else None)
        if nav is None:
            return []
        folder = posixpath.dirname(path)
        return [e for e in (self._entry(a.get_text(), folder, a.get("href", "")) for a in nav.find_all("a")) if e]

    def _ncx_toc(self, path: str | None) -> list[TocEntry]:
        text = self._read(path) if path else None
        if not text:
            return []
        doc = _soup(text)
        folder = posixpath.dirname(path)
        out = []
        for point in (t for t in doc.find_all(True) if _local(t) == "navpoint"):
            label = next((c for c in point.find_all(True, recursive=False) if _local(c) == "navlabel"), None)
            content = next((c for c in point.find_all(True, recursive=False) if _local(c) == "content"), None)
            e = self._entry(label.get_text() if label else "", folder, content.get("src", "") if content else "")
            if e:
                out.append(e)
        return out


