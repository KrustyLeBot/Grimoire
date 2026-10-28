"""ISBNs as books carry them (ISBN-10 or 13, dashes, `urn:isbn:`, "ISBN-13 :"…), always given back as ISBN-13."""
from __future__ import annotations

import re

_CANDIDATE = re.compile(r"(?<![\dXx])(97[89](?:[\- ]?\d){10}|\d(?:[\- ]?\d){8}[\- ]?[\dXx])(?![\dXx])")


def _check13(d: str) -> bool:
    return sum(int(c) * (1 if i % 2 == 0 else 3) for i, c in enumerate(d)) % 10 == 0


def _check10(d: str) -> bool:
    return sum((10 if c == "X" else int(c)) * (10 - i) for i, c in enumerate(d)) % 11 == 0


def _to13(d10: str) -> str:
    core = "978" + d10[:9]
    total = sum(int(c) * (1 if i % 2 == 0 else 3) for i, c in enumerate(core))
    return core + str((10 - total % 10) % 10)


def _strict(candidate: str) -> str | None:
    d = "".join(c for c in candidate.upper() if c.isdigit() or c == "X")
    if len(d) == 13 and d.isdigit() and _check13(d):
        return d
    if len(d) == 10 and d[:9].isdigit() and _check10(d):
        return _to13(d)
    return None


def all_in(text: str) -> list[str]:
    """Every valid ISBN in a free text, in order, as ISBN-13."""
    out: list[str] = []
    for m in _CANDIDATE.finditer(text):
        isbn = _strict(m.group(0))
        if isbn and isbn not in out:
            out.append(isbn)
    return out


def normalize(raw: str) -> str | None:
    found = all_in(raw)
    return found[0] if found else None
