"""Poseidon Scans, a French scanlation site (mostly manhwa) built with Next.js. Same protocol as
the Android source (docs/SPECS.md §5): no account needed; premium chapters (paid until a date,
free after) are hidden unless asked for, and need a premium account logged in in the web view.
"""
from __future__ import annotations

import json
import time
from datetime import datetime
from urllib.parse import quote, unquote

from bs4 import BeautifulSoup

from ..core.model import ChapterInfo, Kind, SeriesDetails, SeriesPage, SeriesSummary, SourceConfig
from . import nextjs
from .http import Http, SourceError
from .source import Images, OptionSpec, SourceType

OPTION_PREMIUM = "show_premium"
RSC = {"RSC": "1"}


class PoseidonScans:
    def __init__(self, config: SourceConfig, http: Http):
        self.config = config
        self.http = http
        self.base = config.base_url.rstrip("/")

    @property
    def show_premium(self) -> bool:
        return (self.config.option(OPTION_PREMIUM) or "false").lower() == "true"

    def series_url(self, slug: str) -> str:
        return f"{self.base}/serie/{slug}"

    def image_headers(self) -> dict[str, str]:
        return {"Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8", "Referer": f"{self.base}/"}

    def cover(self, slug: str) -> str:
        return f"{self.base}/api/covers/{slug}.webp"

    def latest(self, page: int) -> SeriesPage:
        body = json.loads(self.http.get(f"{self.base}/api/manga/lastchapters?limit=16&page={page}"))
        items = tuple(SeriesSummary(o["slug"], o["title"], self.cover(o["slug"])) for o in body.get("data") or [])
        return SeriesPage(items, has_next=len(items) == 16)

    def popular(self, page: int) -> SeriesPage:
        if page > 1:
            return SeriesPage((), False)  # the home page holds the whole list
        found = parse_popular(self.http.get(f"{self.base}/", RSC))
        if found is None:
            raise SourceError("Liste illisible : le site a peut-être changé")
        return SeriesPage(tuple(SeriesSummary(x.slug, x.title, self.cover(x.slug)) for x in found), False)

    def search(self, query: str, page: int) -> SeriesPage:
        params = []
        if query.strip():
            params.append("search=" + quote(query.strip()))
        if page > 1:
            params.append(f"page={page}")
        url = f"{self.base}/series" + ("?" + "&".join(params) if params else "")
        return parse_search(self.http.get(url), self.base)

    def details(self, slug: str) -> SeriesDetails:
        info = parse_details(self.http.get(self.series_url(slug)))
        if info is None:
            raise SourceError("Fiche illisible : le site a peut-être changé")
        # The chapter list comes from the RSC stream, which can be partial on a first hit.
        chapters = parse_chapters(self.http.get(self.series_url(slug), RSC), self.show_premium)
        if chapters is None:
            chapters = parse_chapters(self.http.get(f"{self.series_url(slug)}?_={int(time.time() * 1000)}", RSC), self.show_premium)
        from dataclasses import replace
        return replace(info, cover=self.cover(slug), chapters=tuple(chapters or ()))

    def content(self, slug: str, chapter: ChapterInfo) -> Images:
        url = f"{self.series_url(slug)}/chapter/{chapter.id}"

        def wanted(o):
            return "initialData" in o and "currentChapter" in o
        data = nextjs.find(self.http.get(url, RSC), wanted) or \
            nextjs.find(self.http.get(f"{url}?_={int(time.time() * 1000)}", RSC), wanted)
        if data is None:
            raise SourceError("Chapitre illisible : le site a peut-être changé")
        current = data.get("currentChapter") if isinstance(data.get("currentChapter"), dict) else None
        locked = current is not None and nextjs.b(current, "isPremium") is True and before_free_date(nextjs.s(current, "premiumUntil"))
        if locked and nextjs.b(data, "isPremiumUser") is not True:
            raise SourceError("Chapitre premium : connecte-toi avec un compte premium (réglages de la source)"
                              if nextjs.s(data, "sessionStatus") == "unauthenticated" else "Chapitre premium : ton compte n'est pas premium")
        images = [i for i in ((data.get("initialData") or {}).get("images") or []) if isinstance(i, dict)]
        images.sort(key=lambda i: nextjs.num(i, "order") or 0)
        urls = tuple(u if u.startswith("http") else self.base + u for u in (nextjs.s(i, "originalUrl") for i in images) if u)
        if not urls:
            raise SourceError("Aucune image dans ce chapitre")
        return Images(urls)


TYPE = SourceType(
    id="poseidon-scans",
    name="Poseidon Scans",
    lang="fr",
    description="Scans en français, surtout des webtoons et manhwas. Sans compte ; les chapitres premium demandent un compte premium.",
    default_base_url="https://poseidon-scans.net",
    create=PoseidonScans,
    options=(OptionSpec(OPTION_PREMIUM, "Afficher les chapitres premium",
                        "Chapitres payants jusqu'à une date (🔒), lisibles seulement avec un compte premium connecté", False),),
    has_login=True,
)


# ---- parsing (pure, tested) ----

def parse_popular(rsc: str) -> list[SeriesSummary] | None:
    found = nextjs.find_list(rsc, lambda o: "id" in o and "title" in o and "slug" in o)
    if found is None:
        return None
    return [SeriesSummary(nextjs.s(o, "slug"), nextjs.s(o, "title") or "") for o in found if nextjs.s(o, "slug")]


def _cover_url(path: str, base: str) -> str:
    if path.startswith("http"):
        return path
    if "storage/covers/" in path:
        return f"{base}/api/covers/{path.split('storage/covers/', 1)[1]}"
    if path.startswith("/"):
        return base + path
    return f"{base}/api/covers/{path}"


def parse_search(html: str, base: str) -> SeriesPage:
    soup = BeautifulSoup(html, "html.parser")
    items = []
    for a in soup.select("div.grid a.block.group"):
        href = a.get("href", "")
        slug = href.split("/serie/", 1)[1].split("/")[0].split("?")[0] if "/serie/" in href else ""
        h2 = a.find("h2")
        if not slug or h2 is None:
            continue
        cover = None
        img = a.select_one("img[alt]")
        srcset = img.get("srcset", "") if img else ""
        if srcset:
            raw = unquote(srcset.split(" ")[0])
            raw = raw.split("url=", 1)[1] if "url=" in raw else raw
            raw = raw.split("&")[0]
            cover = _cover_url(raw, base) if raw else None
        items.append(SeriesSummary(slug, h2.get_text(strip=True), cover))
    has_next = any("Suivant" in x.get_text() for x in soup.select('nav[aria-label="Pagination"] a'))
    return SeriesPage(tuple(items), has_next)


_STATUS = {"en cours": "En cours", "terminé": "Terminé", "en pause": "En pause", "hiatus": "En pause",
           "annulé": "Abandonné", "abandonné": "Abandonné"}


def parse_details(html: str) -> SeriesDetails | None:
    o = nextjs.find_in_html(html, lambda x: all(k in x for k in ("title", "slug", "description", "status")))
    if o is None:
        return None
    genres = []
    for c in o.get("categories") or []:
        name = (nextjs.s(c, "name") or "").strip()
        if name:
            genres.append(name[0].upper() + name[1:])
    kind = Kind.MANGA if any(g.lower() == "manga" for g in genres) else Kind.WEBTOON
    raw_status = (nextjs.s(o, "status") or "").strip()
    return SeriesDetails(
        slug=nextjs.s(o, "slug") or "", title=nextjs.s(o, "title") or "",
        author=(nextjs.s(o, "author") or "").strip() or None, artist=(nextjs.s(o, "artist") or "").strip() or None,
        status=_STATUS.get(raw_status.lower(), raw_status or None), genres=tuple(genres),
        description=(nextjs.s(o, "description") or "").strip() or None, kind=kind,
    )


def _date(iso: str | None) -> int:
    if not iso:
        return 0
    try:
        return int(datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return 0


def before_free_date(premium_until: str | None) -> bool:
    """The site keeps isPremium set after premiumUntil has passed: past that date it is free."""
    until = _date(premium_until)
    return True if not until else until > time.time() * 1000


def parse_chapters(rsc: str, show_premium: bool) -> list[ChapterInfo] | None:
    """None when the stream did not carry the list (partial first hit): the caller retries."""
    page = nextjs.find(rsc, lambda o: isinstance(o.get("manga"), dict) and "chapters" in o["manga"])
    if page is None:
        return None
    premium_user = nextjs.b(page, "isPremiumUser") is True
    raw = [c for c in page["manga"].get("chapters") or [] if isinstance(c, dict)]
    if not raw:
        return None
    out = []
    for ch in raw:
        number = nextjs.num(ch, "number")
        if number is None:
            continue
        locked = nextjs.b(ch, "isPremium") is True and before_free_date(nextjs.s(ch, "premiumUntil")) and not premium_user
        if locked and not show_premium:
            continue
        # Same ids as the Android app (Float.toString without ".0"): read marks are shared.
        ident = str(int(number)) if number == int(number) else str(number)
        title = (nextjs.s(ch, "title") or "").strip() or None
        volume = nextjs.b(ch, "isVolume") is True or (number == int(number) and title is not None and "volume" in title.lower())
        base = f"Volume {ident}" if volume else f"Chapitre {ident}"
        name = ("🔒 " if locked else "") + (f"{base} - {title}" if title and title.lower() != base.lower() else base)
        out.append(ChapterInfo(ident, name, number, _date(nextjs.s(ch, "createdAt")), locked))
    return sorted(out, key=lambda c: -c.number)
