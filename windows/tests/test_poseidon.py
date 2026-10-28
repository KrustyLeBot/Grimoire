import json

from grimoire_win.core.model import Kind
from grimoire_win.sources import poseidon

FUTURE, PAST = "2999-01-01T00:00:00.000Z", "2001-01-01T00:00:00.000Z"


def rsc(premium_user):
    data = {"isPremiumUser": premium_user, "manga": {"slug": "solo", "chapters": [
        {"number": 1, "title": "", "createdAt": "2024-01-01T00:00:00.000Z", "isPremium": False},
        {"number": 2.5, "title": "Le retour", "createdAt": "2024-02-01T00:00:00.000Z", "isPremium": True, "premiumUntil": PAST},
        {"number": 3, "title": "", "createdAt": "2024-03-01T00:00:00.000Z", "isPremium": True, "premiumUntil": FUTURE}]}}
    return '0:["$","div",null,{"children":"$L1"}]\n1:' + json.dumps(data)


def test_hides_premium_by_default():
    chapters = poseidon.parse_chapters(rsc(False), False)
    assert [c.id for c in chapters] == ["2.5", "1"]
    assert chapters[0].name == "Chapitre 2.5 - Le retour"
    assert chapters[1].name == "Chapitre 1"


def test_shows_locked_premium():
    chapters = poseidon.parse_chapters(rsc(False), True)
    assert chapters[0].id == "3" and chapters[0].locked and chapters[0].name.startswith("🔒")


def test_premium_user_reads_everything():
    chapters = poseidon.parse_chapters(rsc(True), False)
    assert len(chapters) == 3 and not any(c.locked for c in chapters)


def test_partial_stream_is_none():
    assert poseidon.parse_chapters('0:["$","div",null,{}]', False) is None


def test_popular():
    found = poseidon.parse_popular('0:{"top":[{"id":1,"title":"Solo","slug":"solo"},{"id":2,"title":"Tower","slug":"tower"}]}')
    assert [x.slug for x in found] == ["solo", "tower"]


def test_search():
    html = """<div class="grid">
      <a class="block group" href="/serie/solo-leveling"><img alt="x" srcset="/_next/image?url=%2Fstorage%2Fcovers%2Fsolo.webp&amp;w=256 1x"><h2>Solo Leveling</h2></a>
      <a class="block group" href="/serie/tower?x=1"><h2>Tower</h2></a></div>
      <nav aria-label="Pagination"><a href="?page=2">Suivant</a></nav>"""
    page = poseidon.parse_search(html, "https://poseidon-scans.net")
    assert [x.slug for x in page.items] == ["solo-leveling", "tower"]
    assert page.items[0].cover == "https://poseidon-scans.net/api/covers/solo.webp"
    assert page.items[1].cover is None and page.has_next


def test_details():
    flight = '0:{"title":"Solo","slug":"solo","description":" Un chasseur. ","status":"en cours","author":"Chugong","categories":[{"name":"action"},{"name":"manga"}]}\n'
    html = f"<html><body><script>self.__next_f.push({json.dumps([1, flight])})</script></body></html>"
    d = poseidon.parse_details(html)
    assert (d.title, d.status, d.description, d.genres, d.kind) == ("Solo", "En cours", "Un chasseur.", ("Action", "Manga"), Kind.MANGA)
