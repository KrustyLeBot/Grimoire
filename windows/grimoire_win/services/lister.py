"""A paged list over one or several sources: each "more" asks every source that still has pages
for its next one and interleaves the answers so no source drowns the others."""
from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Callable

from PySide6.QtCore import QObject, Signal

from ..core.model import SeriesPage, SeriesSummary, series_key
from ..sources.http import describe
from .async_util import run_async


@dataclass(frozen=True)
class Found:
    source: str
    series: SeriesSummary

    @property
    def key(self) -> str:
        return series_key(self.source, self.series.slug)


@dataclass(frozen=True)
class Listing:
    items: tuple[Found, ...] = ()
    pages: tuple[tuple[str, int], ...] = ()
    ended: frozenset[str] = frozenset()
    errors: tuple[tuple[str, str], ...] = ()
    loading: bool = False
    generation: int = field(default=0, compare=False)


def interleave(lists: list[list[Found]], seen: set[str]) -> list[Found]:
    out = []
    for i in range(max((len(x) for x in lists), default=0)):
        for x in lists:
            if i < len(x) and x[i].key not in seen:
                seen.add(x[i].key)
                out.append(x[i])
    return out


class Lister(QObject):
    changed = Signal()

    def __init__(self, sources, fetch: Callable[[object, int], SeriesPage]):
        super().__init__()
        self.sources_registry = sources
        self.fetch = fetch
        self.listing = Listing()
        self.sources: list[str] = []
        self._generation = 0

    def reset(self, ids: list[str]) -> None:
        self._generation += 1
        self.sources = ids
        self.listing = Listing(generation=self._generation)
        self.changed.emit()
        self.more()

    def clear(self) -> None:
        self._generation += 1
        self.sources = []
        self.listing = Listing(generation=self._generation)
        self.changed.emit()

    def retry(self) -> None:
        self.listing = replace(self.listing, errors=())
        self.more()

    @property
    def has_next(self) -> bool:
        errors = dict(self.listing.errors)
        return any(s not in self.listing.ended and s not in errors for s in self.sources)

    def more(self) -> None:
        start = self.listing
        errors = dict(start.errors)
        pending = [s for s in self.sources if s not in start.ended and s not in errors]
        if start.loading or not pending:
            return
        self.listing = replace(start, loading=True)
        self.changed.emit()
        generation = self._generation
        pages = dict(start.pages)

        def work():
            results = []
            for sid in pending:  # one request per source; the sources pace themselves
                page = pages.get(sid, 0) + 1
                try:
                    src = self.sources_registry.get(sid)
                    if src is None:
                        raise OSError("Source introuvable")
                    results.append((sid, page, self.fetch(src, page), None))
                except Exception as e:  # noqa: BLE001 - shown per source
                    results.append((sid, page, None, e))
            return results

        def done(results):
            if generation != self._generation:
                return
            now = self.listing
            seen = {f.key for f in now.items}
            merged = interleave([[Found(sid, s) for s in r.items] if r else [] for sid, _, r, _ in results], seen)
            pages_now, ended, errs = dict(now.pages), set(now.ended), dict(now.errors)
            for sid, page, r, e in results:
                if r is not None:
                    pages_now[sid] = page
                    if not r.has_next or not r.items:
                        ended.add(sid)
                else:
                    errs[sid] = describe(e)
            self.listing = replace(now, items=now.items + tuple(merged), pages=tuple(pages_now.items()), ended=frozenset(ended),
                                   errors=tuple(errs.items()), loading=False)
            self.changed.emit()

        run_async(work, done, lambda e: None)
