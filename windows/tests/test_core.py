import json

import pytest

from grimoire_win.core import codec, ops
from grimoire_win.core.model import ChapterInfo, Kind, LibraryItem, SourceConfig, WebSearch
from grimoire_win.core.store import Store

POSEIDON = SourceConfig("poseidon-scans", "poseidon-scans", "Poseidon Scans", "https://poseidon-scans.net")


def store(tmp_path):
    s = Store(tmp_path, lambda: (POSEIDON,))
    s.load()
    return s


def test_reads_the_android_files(tmp_path):
    # As the Android app writes them (org.json, toString(2)).
    (tmp_path / "library.json").write_text(json.dumps({"format_version": 1, "items": [
        {"source": "poseidon-scans", "slug": "solo", "title": "Solo", "cover": "https://c", "kind": "webtoon", "added": 5},
        {"source": "files", "slug": "isbn-9782070612758", "title": "Le Petit Prince", "kind": "novel", "added": 6,
         "isbns": ["9782070612758"], "link": "https://x/1.epub", "page": "https://x/livre"}]}))
    (tmp_path / "progress.json").write_text(json.dumps({"format_version": 1, "series": {
        "poseidon-scans:solo": {"read": {"1": 10}, "pages": {"2": 4}, "mode": "pages",
                                "last": {"chapter": "2", "name": "Chapitre 2", "page": 4, "pages": 20, "at": 30}}}}))
    (tmp_path / "sources.json").write_text(json.dumps({"format_version": 1, "sources": [
        {"id": "poseidon-scans", "type": "poseidon-scans", "name": "Poseidon", "baseUrl": "https://p", "enabled": True,
         "options": {"show_premium": "true"}}], "web_searches": [{"name": "Site", "url": "https://s/{q}"}]}))
    s = store(tmp_path)
    assert s.error is None
    assert [i.slug for i in s.data.library] == ["solo", "isbn-9782070612758"]
    assert s.data.library[1].link == "https://x/1.epub" and s.data.library[1].kind is Kind.NOVEL
    p = s.data.progress_of("poseidon-scans:solo")
    assert p.read_ids == {"1": 10} and p.page_of == {"2": 4} and p.last.chapter_name == "Chapitre 2"
    assert s.data.sources[0].option("show_premium") == "true"
    assert s.data.web_searches == (WebSearch("Site", "https://s/{q}"),)


def test_round_trips_and_writes_only_what_changed(tmp_path):
    s = store(tmp_path)
    assert not (tmp_path / "library.json").exists()
    s.update(ops.add_book(LibraryItem("files", "isbn-1", "Livre", None, Kind.NOVEL, 1, ("9782070612758",))))
    assert (tmp_path / "library.json").exists() and (tmp_path / ".syncignore").exists()
    assert not (tmp_path / "progress.json").exists()
    again = store(tmp_path)
    assert again.data.library == s.data.library


def test_positions_wait_for_flush_and_survive_a_reload(tmp_path):
    s = store(tmp_path)
    ch = ChapterInfo("3", "Chapitre 3", 3, 0)
    s.update(ops.position("p:solo", ch, 5, 20), persist=False)
    assert not (tmp_path / "progress.json").exists()
    s.load()  # e.g. Proton Drive brought another device's files
    assert s.data.progress_of("p:solo").last.page == 5
    s.flush()
    assert store(tmp_path).data.progress_of("p:solo").page_of == {"3": 5}


def test_unreadable_file_is_never_overwritten(tmp_path):
    (tmp_path / "library.json").write_text("{oops")
    s = store(tmp_path)
    assert "illisible" in s.error
    s.update(ops.remove_from_library("x"))
    assert (tmp_path / "library.json").read_text() == "{oops"


def test_refuses_a_newer_format(tmp_path):
    with pytest.raises(ValueError):
        codec.decode_library('{"format_version": 99, "items": []}')


def test_read_marks():
    from grimoire_win.core.model import AppData
    d = ops.mark_read("k", ["1", "2"], now=7)(AppData())
    assert d.progress_of("k").read_ids == {"1": 7, "2": 7}
    d = ops.mark_unread("k", ["1"])(d)
    assert d.progress_of("k").read_ids == {"2": 7}


def test_web_search_url():
    assert WebSearch("x", "https://s/{q}?a=1").url_for(" Le Petit Prince ") == "https://s/Le%20Petit%20Prince?a=1"
