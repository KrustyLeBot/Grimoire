"""The built-in source types, and the Source objects of the configured sources."""
from __future__ import annotations

from ..core.model import SourceConfig
from . import poseidon
from .http import Http
from .source import Source, SourceType

TYPES: tuple[SourceType, ...] = (poseidon.TYPE,)


def type_of(type_id: str) -> SourceType | None:
    return next((t for t in TYPES if t.id == type_id), None)


def default_sources() -> tuple[SourceConfig, ...]:
    return tuple(SourceConfig(t.id, t.id, t.name, t.default_base_url) for t in TYPES)


class Sources:
    """Rebuilt when a source's settings change."""

    def __init__(self, store, http: Http):
        self.store, self.http = store, http
        self._built: dict[SourceConfig, Source] = {}

    def get(self, source_id: str) -> Source | None:
        config = self.store.data.source(source_id)
        if config is None:
            return None
        if config not in self._built:
            t = type_of(config.type)
            if t is None:
                return None
            self._built = {c: s for c, s in self._built.items() if c.id != source_id}
            self._built[config] = t.create(config, self.http)
        return self._built[config]

    def enabled(self) -> list[Source]:
        return [s for s in (self.get(c.id) for c in self.store.data.sources if c.enabled) if s is not None]
