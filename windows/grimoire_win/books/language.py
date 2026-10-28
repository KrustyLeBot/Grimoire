"""A rough guess of a text's language from its most common words: enough to tell a French summary from an English one."""
from __future__ import annotations

import re

_WORDS = {
    "fr": {"le", "la", "les", "des", "une", "est", "et", "dans", "qui", "que", "pour", "pas", "sur", "avec", "son", "sa", "ses",
           "du", "au", "aux", "elle", "il"},
    "en": {"the", "and", "of", "to", "is", "in", "that", "his", "her", "with", "for", "was", "on", "as", "by", "he", "she", "it",
           "an", "from"},
    "de": {"der", "die", "das", "und", "ist", "nicht", "mit", "ein", "eine", "von", "zu", "den", "sich", "auf"},
    "es": {"el", "los", "las", "una", "y", "que", "por", "con", "para", "del", "se", "su", "es", "lo"},
    "it": {"il", "gli", "della", "che", "di", "una", "per", "con", "non", "sono", "nel", "alla"},
}


def guess(text: str) -> str | None:
    words = [w for w in re.split(r"[^\w']+", text.lower()) if w and not w.isdigit()]
    if len(words) < 12:
        return None
    scores = {lang: sum(w in s for w in words) for lang, s in _WORDS.items()}
    best = max(scores, key=scores.get)
    second = max(v for k, v in scores.items() if k != best)
    return best if scores[best] >= 3 and scores[best] > second * 1.5 else None


def pick(language: str | None, candidates: list[str | None]) -> str | None:
    """First summary in the book's language (or whose language cannot be told); None rather than another language."""
    lang = language[:2].lower() if language else None
    for c in candidates:
        c = (c or "").strip()
        if len(c) >= 40 and (lang is None or guess(c) in (None, lang)):
            return c
    return None
