from conftest import FIXTURES

from grimoire_win.sources import nextjs


def fixture(name):
    return (FIXTURES / "reactflight" / f"{name}.txt").read_text(encoding="utf-8")


def test_path_references():
    o = nextjs.find(fixture("pathrefs"), lambda x: "mangaTitle" in x)
    assert [c["title"] for c in o["chapters"]] == ["Newest", "Oldest"]


def test_promises():
    o = nextjs.find(fixture("promises"), lambda x: "mangaTitle" in x)
    assert [c["title"] for c in o["chapters"]] == ["Second", "First"]


def test_lazy_references():
    o = nextjs.find(fixture("lazyrefs"), lambda x: "mangaTitle" in x)
    assert o["children"] == ["Deferred Title", "42 chapters"]


def test_maps_and_sets():
    o = nextjs.find(fixture("collections"), lambda x: "items" in x)
    assert nextjs.s(o["items"], "a") == "1"
    assert len(o["tags"]) == 3
    assert len(o["nested"]["list"]) == 2


def test_markers():
    o = nextjs.find(fixture("markers"), lambda x: "name" in x)
    assert o["big"] == "123456789012345678901234567890"
    assert o["date"] == "2024-01-02T03:04:05.000Z"
    assert o["inf"] == "Infinity"
    assert o["missing"] is None


def test_large_text_chunks():
    stream = nextjs.rsc_payloads(fixture("largetext"))
    assert len(stream.chunks["1"].encode()) == 0x690


def test_find_list():
    rsc = '0:{"a":[{"id":1,"title":"A","slug":"a"},{"id":2,"title":"B","slug":"b"}]}'
    assert [x["slug"] for x in nextjs.find_list(rsc, lambda x: "slug" in x)] == ["a", "b"]


def test_app_router_html():
    flight = '0:{"title":"Solo","slug":"solo"}\n'
    import json
    html = f"<html><body><script>self.__next_f.push({json.dumps([1, flight])})</script></body></html>"
    assert nextjs.find_in_html(html, lambda x: "slug" in x)["title"] == "Solo"
