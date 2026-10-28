"""Title, authors, summary, language and cover of a book from catalogues rather than from the file,
without account or key: Open Library (covers, summaries) and the BnF catalogue (every book
published in France). Each ISBN the file carries is tried in turn; without ISBN, a search by
title and author. The summary is the first one in the book's language.
"""
from __future__ import annotations

import unicodedata
from dataclasses import replace
from urllib.parse import quote

from ..sources.http import Http
from . import info as info_
from . import language


class _Calls:
    """Whether any catalogue answered at all, to tell "unknown book" from "no network"."""

    def __init__(self):
        self.answered = False
        self.failures: list[Exception] = []

    def attempt(self, fn):
        try:
            value = fn()
            self.answered = True
            return value
        except Exception as e:  # noqa: BLE001 - each catalogue may fail on its own
            self.failures.append(e)
            return None


def _simple(s: str) -> str:
    s = unicodedata.normalize("NFD", s.lower())
    s = "".join(c for c in s if not unicodedata.combining(c))
    return " ".join("".join(c if c.isalnum() else " " for c in s).split())


class Lookup:
    def __init__(self, http: Http):
        self.http = http

    def find(self, isbns: list[str], title: str, authors: list[str], lang: str | None,
             file_summary: str | None) -> info_.BookInfo | None:
        """None when nothing matches; raises only when every catalogue failed (offline…)."""
        calls = _Calls()
        for isbn in isbns:
            found = self._by_isbn(isbn, calls)
            if found:
                info, ol_summary = found
                book_lang = info.language or lang
                return replace(info, isbns=tuple(dict.fromkeys(list(isbns) + list(info.isbns))), language=book_lang,
                               description=language.pick(book_lang, [file_summary, info.description, ol_summary]))
        info = self._by_title(title, authors, calls)
        if info:
            return replace(info, language=info.language or lang, description=language.pick(lang, [file_summary]))
        if not calls.answered and calls.failures:
            raise calls.failures[0]
        return None

    def _by_isbn(self, isbn: str, calls: _Calls):
        ol = calls.attempt(lambda: info_.from_open_library(
            self.http.get(f"https://openlibrary.org/api/books?bibkeys=ISBN:{isbn}&format=json&jscmd=data"), isbn))
        query = quote(f'bib.isbn adj "{isbn}"')
        bnf = calls.attempt(lambda: info_.from_bnf(self.http.get(
            "https://catalogue.bnf.fr/api/SRU?version=1.2&operation=searchRetrieve"
            f"&query={query}&recordSchema=dublincore&maximumRecords=1"), isbn))
        summary = calls.attempt(lambda: self._ol_summary(isbn))
        base = bnf or ol
        if base is None:
            return None
        info = base.merged(ol)
        cover = (ol.cover if ol else None) or self._ol_cover(isbn) or info.cover
        return replace(info, cover=cover, isbns=tuple(dict.fromkeys((isbn,) + info.isbns))), summary

    def _ol_summary(self, isbn: str) -> str | None:
        work = info_.work_of(self.http.get(f"https://openlibrary.org/isbn/{isbn}.json"))
        return info_.summary_of(self.http.get(f"https://openlibrary.org{work}.json")) if work else None

    def _ol_cover(self, isbn: str) -> str | None:
        url = f"https://covers.openlibrary.org/b/isbn/{isbn}-L.jpg?default=false"
        return url if self.http.head_ok(url) else None

    def _by_title(self, title: str, authors: list[str], calls: _Calls) -> info_.BookInfo | None:
        if not title.strip():
            return None
        wanted = _simple(title)
        author = authors[0].split(" ")[-1] if authors else None
        url = f"https://openlibrary.org/search.json?title={quote(title)}" + (f"&author={quote(author)}" if author else "") + "&limit=5"
        found = calls.attempt(lambda: info_.from_open_library_search(self.http.get(url)))
        if found and (_simple(found.title) == wanted or _simple(found.title).startswith(wanted) or wanted.startswith(_simple(found.title))):
            return found
        return None
