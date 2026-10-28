"""A chapter of an EPUB prepared for the reader, as on Android: the book's markup is kept (the web
engine reads XHTML and SVG covers natively) and only gets, by insertion, the reader's style, a
#g-flow wrapper around the body content (laid out in pages) with an end marker, and the reader's
script. The book's own scripts are dropped. Always given back as UTF-8.
"""
from __future__ import annotations

import re

from bs4 import BeautifulSoup

_BODY_OPEN = re.compile(r"<body\b[^>]*>", re.I)
_SCRIPT = re.compile(r"<script\b[^>]*/>|<script\b[^>]*>[\s\S]*?</script\s*>", re.I)
_ENCODING = re.compile(r"""encoding\s*=\s*["']([\w.:-]+)["']""", re.I)
_META_CHARSET = re.compile(r"""<meta[^>]+charset\s*=\s*["']?([\w.:-]+)""", re.I)
_VIEWPORT = '<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1"/>'


def decode(data: bytes) -> str:
    head = data[:1024].decode("latin-1")
    m = _ENCODING.search(head) or _META_CHARSET.search(head)
    try:
        return data.decode(m.group(1) if m else "utf-8")
    except (LookupError, UnicodeDecodeError):
        return data.decode("utf-8", "replace")


def keeps_markup(source: str) -> bool:
    m = _BODY_OPEN.search(source)
    return bool(m) and source.lower().rfind("</body") > m.end() - 1


def render(source: str, css: str, script: str) -> str:
    text = _ENCODING.sub('encoding="utf-8"', _SCRIPT.sub("", source), count=1)
    m = _BODY_OPEN.search(text)
    close = text.lower().rfind("</body")
    if not m or close < m.end() - 1:
        return _fallback(text, css, script)
    start = m.end()
    return (text[:start] + _VIEWPORT + f'<style id="grimoire">/*<![CDATA[*/{css}/*]]>*/</style>' + '<div id="g-flow">'
            + text[start:close] + '<span id="g-end"></span></div>'
            + f"<script>//<![CDATA[\n{script}\n//]]></script>" + text[close:])


def _fallback(source: str, css: str, script: str) -> str:
    """A page without a proper body: rebuilt as HTML."""
    soup = BeautifulSoup(source, "html.parser")
    body_html = str(soup.body.decode_contents()) if soup.body else str(soup)
    return ("<!DOCTYPE html><html><head>" + _VIEWPORT + f'<style id="grimoire">{css}</style></head><body>'
            + f'<div id="g-flow">{body_html}<span id="g-end"></span></div><script>{script}</script></body></html>')
