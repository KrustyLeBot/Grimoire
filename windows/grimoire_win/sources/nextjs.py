"""Reads the data a Next.js site sends along with its pages: the React Flight stream of the App
Router (inline `self.__next_f.push(...)` scripts, or a raw `RSC: 1` response) and the Pages
Router's `__NEXT_DATA__`, with Flight's `$` references resolved. Port of the Android app's
NextJs.kt (itself from keiyoushi/extensions-source, Apache-2.0).
"""
from __future__ import annotations

import base64
import json
import re
from typing import Any, Callable

from bs4 import BeautifulSoup

_NEXT_F = re.compile(r"self\.__next_f\.push\(\s*(\[.*])\s*\)\s*;?\s*$", re.S)
_DECODER = json.JSONDecoder()
_HEX = set("0123456789abcdefABCDEF")

Predicate = Callable[[dict], bool]


class _Stream:
    def __init__(self):
        self.chunks: dict[str, str] = {}
        self.models: dict[str, Any] = {}
        self.payloads: list[Any] = []


def find_in_html(html: str, predicate: Predicate) -> dict | None:
    stream = _app_router(html)
    payloads = stream.payloads or _pages_router(html)
    return _search(payloads, stream, predicate)


def find(rsc: str, predicate: Predicate) -> dict | None:
    stream = rsc_payloads(rsc)
    return _search(stream.payloads, stream, predicate)


def find_list(rsc: str, predicate: Predicate) -> list[dict] | None:
    """First array of objects whose first element the predicate accepts (a list of series, say)."""
    stream = rsc_payloads(rsc)
    for payload in stream.payloads:
        found = _first_array(_resolve(payload, stream, frozenset()), predicate)
        if found is not None:
            return found
    return None


def _search(payloads, stream, predicate):
    for payload in payloads:
        found = _first_object(_resolve(payload, stream, frozenset()), predicate)
        if found is not None:
            return found
    return None


def _first_object(e, predicate):
    if isinstance(e, dict):
        if predicate(e):
            return e
        for v in e.values():
            found = _first_object(v, predicate)
            if found is not None:
                return found
    elif isinstance(e, list):
        for v in e:
            found = _first_object(v, predicate)
            if found is not None:
                return found
    return None


def _first_array(e, predicate):
    if isinstance(e, list):
        if e and isinstance(e[0], dict) and predicate(e[0]):
            return [x for x in e if isinstance(x, dict)]
        for v in e:
            found = _first_array(v, predicate)
            if found is not None:
                return found
    elif isinstance(e, dict):
        for v in e.values():
            found = _first_array(v, predicate)
            if found is not None:
                return found
    return None


# ---- references (React Flight markers) ----

def _resolve(e, stream: _Stream, resolving: frozenset):
    if isinstance(e, dict):
        return {k: _resolve(v, stream, resolving) for k, v in e.items()}
    if isinstance(e, list):
        return [_resolve(v, stream, resolving) for v in e]
    if isinstance(e, str) and len(e) >= 2 and e[0] == "$":
        if e == "$undefined":
            return None
        if e in ("$Infinity", "$-Infinity", "$NaN", "$-0"):
            return e[1:]
        tag = e[1]
        if tag == "$":
            return e[1:]
        if tag in "Dn":
            return e[2:]  # date (ISO-8601), BigInt digits
        if tag == "Q":
            return _map_ref(e[2:], stream, resolving, e)
        if tag == "W":
            return _set_ref(e[2:], stream, resolving, e)
        if tag in "L@":
            found = _model_ref(e[2:], stream, resolving)
            return e if found is _MISSING else found
        found = _model_ref(e[1:], stream, resolving)
        return e if found is _MISSING else found
    return e


_MISSING = object()


def _model_ref(reference: str, stream: _Stream, resolving: frozenset):
    segments = reference.split(":")
    ident = segments[0]
    if len(segments) == 1 and ident in stream.chunks:
        return stream.chunks[ident]
    if ident in resolving or ident not in stream.models:
        return _MISSING
    guard = resolving | {ident}
    value = stream.models[ident]
    for segment in segments[1:]:
        if isinstance(value, str) and value.startswith("$"):
            value = _resolve(value, stream, guard)
        value = _segment(value, segment)
        if value is _MISSING:
            return _MISSING
    return _resolve(value, stream, guard)


def _segment(value, segment: str):
    """React elements travel as ["$", type, key, props]: named segments map onto those indices."""
    if isinstance(value, dict):
        return value.get(segment, _MISSING)
    if isinstance(value, list):
        if len(value) >= 4 and value[0] == "$":
            named = {"type": 1, "key": 2, "props": 3}
            if segment in named:
                return value[named[segment]]
        if segment.isdigit() and int(segment) < len(value):
            return value[int(segment)]
    return _MISSING


def _map_ref(ident, stream, resolving, original):
    entries = stream.models.get(ident)
    if ident in resolving or not isinstance(entries, list):
        return original
    resolved = _resolve(entries, stream, resolving | {ident})
    return {str(p[0]): p[1] for p in resolved if isinstance(p, list) and len(p) == 2}


def _set_ref(ident, stream, resolving, original):
    values = stream.models.get(ident)
    if ident in resolving or not isinstance(values, list):
        return original
    return _resolve(values, stream, resolving | {ident})


# ---- payloads ----

def _app_router(html: str) -> _Stream:
    """The pushes are slices of one stream cut anywhere: joined before parsing. [1] is text, [3] base64."""
    parts: list[str] = []
    for script in BeautifulSoup(html, "html.parser").find_all("script"):
        if script.get("src"):
            continue
        data = script.string or script.get_text()
        if "self.__next_f.push" not in data:
            continue
        match = _NEXT_F.search(data)
        if not match:
            continue
        try:
            push = json.loads(match.group(1))
            kind, content = push[0], push[1]
            if kind == 1:
                parts.append(content)
            elif kind == 3:
                parts.append(base64.b64decode(content).decode("utf-8"))
        except (ValueError, IndexError, TypeError):
            continue  # a script that is not a flight push after all
    return rsc_payloads("".join(parts))


def _pages_router(html: str) -> list:
    script = BeautifulSoup(html, "html.parser").find("script", id="__NEXT_DATA__")
    if script is None:
        return []
    try:
        root = json.loads(script.string or "")
    except ValueError:
        return []
    page_props = (root.get("props") or {}).get("pageProps") if isinstance(root, dict) else None
    return [x for x in (page_props, root) if x is not None]


def rsc_payloads(body: str) -> _Stream:
    """Rows `<hex id>:<json>` or `<hex id>:T<hex byte length>,<text>`."""
    stream = _Stream()
    pos, n = 0, len(body)
    while pos < n:
        colon = body.find(":", pos)
        if colon == -1:
            break
        ident = body[pos:colon]
        if not ident or any(c not in _HEX for c in ident):
            pos += 1
            continue
        pos = colon + 1
        if pos >= n:
            break
        if body[pos] == "T":
            comma = body.find(",", pos + 1)
            if comma == -1:
                break
            try:
                length = int(body[pos + 1:comma], 16)
            except ValueError:
                break
            pos = start = comma + 1
            size = 0
            while pos < n and size < length:  # the length is in UTF-8 bytes, not characters
                size += len(body[pos].encode("utf-8", "surrogatepass"))
                pos += 1
            text = body[start:pos]
            stream.chunks[ident] = text
            try:
                stream.payloads.append(json.loads(text))
            except ValueError:
                pass
        else:
            try:
                value, end = _DECODER.raw_decode(body, pos)
                stream.payloads.append(value)
                stream.models[ident] = value
                pos = end
            except ValueError:
                newline = body.find("\n", pos)
                pos = n if newline == -1 else newline + 1
    return stream


# Small readers for JSON trees, as on Android.
def s(o: dict | None, key: str) -> str | None:
    v = o.get(key) if isinstance(o, dict) else None
    if v is None or isinstance(v, (dict, list)):
        return None
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def b(o: dict | None, key: str) -> bool | None:
    v = s(o, key)
    return {"true": True, "false": False}.get(v) if v is not None else None


def num(o: dict | None, key: str) -> float | None:
    try:
        v = s(o, key)
        return float(v) if v is not None else None
    except ValueError:
        return None
