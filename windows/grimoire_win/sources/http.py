"""The one HTTP client of the sources and books. Its cookies are fed by the app's web view
(logging in or passing an anti-bot page there also works for the app), with the same user agent.
Requests to one host are paced SPACING apart, so browsing never hammers a site.
"""
from __future__ import annotations

import threading
import time
from urllib.parse import urlparse

import requests


USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/130.0.0.0 Safari/537.36")
SPACING = 0.35


class SourceError(Exception):
    """A source failure, with a message meant for the user (in French)."""


class Http:
    def __init__(self):
        self.session = requests.Session()
        self.session.headers["User-Agent"] = USER_AGENT
        self._lock = threading.Lock()
        self._last: dict[str, float] = {}

    def add_cookie(self, name: str, value: str, domain: str, path: str = "/") -> None:
        self.session.cookies.set(name, value, domain=domain, path=path or "/")

    def _pace(self, url: str) -> None:
        host = urlparse(url).hostname or ""
        with self._lock:
            now = time.monotonic()
            nxt = max(now, self._last.get(host, 0) + SPACING)
            self._last[host] = nxt
        if nxt > now:
            time.sleep(nxt - now)

    def get(self, url: str, headers: dict[str, str] | None = None) -> str:
        """The body of a page, or a SourceError saying what went wrong."""
        self._pace(url)
        r = self.session.get(url, headers=headers or {}, timeout=30)
        if r.ok:
            r.encoding = r.encoding or "utf-8"
            return r.text
        if r.status_code in (403, 503):
            raise SourceError(f"Le site bloque l'accès ({r.status_code}) : ouvre-le une fois depuis les réglages de la source")
        if r.status_code == 404:
            raise SourceError("Page introuvable sur le site (404)")
        raise SourceError(f"Le site a répondu {r.status_code}")

    def stream(self, url: str, headers: dict[str, str] | None = None, timeout: int = 60):
        """An image or file download: not paced (few at once), same cookies and user agent."""
        r = self.session.get(url, headers=headers or {}, stream=True, timeout=timeout)
        r.raise_for_status()
        return r

    def head_ok(self, url: str) -> bool:
        try:
            return self.session.head(url, timeout=15, allow_redirects=True).ok
        except requests.RequestException:
            return False


def describe(e: BaseException) -> str:
    if isinstance(e, SourceError):
        return str(e)
    if isinstance(e, requests.ConnectionError):
        return "Site injoignable : pas de connexion, ou l'adresse de la source a changé"
    if isinstance(e, requests.Timeout):
        return "Le site ne répond pas"
    return str(e) or type(e).__name__


