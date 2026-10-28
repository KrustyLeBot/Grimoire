import json
import time

import pytest
from PySide6.QtWidgets import QApplication

from grimoire_win.config import Config
from grimoire_win.context import App
from grimoire_win.core.model import ChapterInfo, Kind, SeriesDetails


@pytest.fixture(scope="module")
def qapp():
    return QApplication.instance() or QApplication([])


@pytest.fixture
def window(qapp, tmp_path):
    from grimoire_win.ui.main_window import MainWindow
    work, private = tmp_path / "work", tmp_path / "private"
    work.mkdir()
    (work / "library.json").write_text(json.dumps({"format_version": 1, "items": [
        {"source": "poseidon-scans", "slug": "solo", "title": "Solo Leveling", "kind": "webtoon", "added": 5},
        {"source": "files", "slug": "isbn-9782070612758", "title": "Le Petit Prince", "kind": "novel", "added": 6}]}))
    (work / "progress.json").write_text(json.dumps({"format_version": 1, "series": {
        "poseidon-scans:solo": {"read": {"1": 1}, "pages": {}, "last": {"chapter": "1", "name": "Chapitre 1", "page": 3, "pages": 4,
                                                                     "at": int(time.time() * 1000)}}}}))
    config = Config("GrimoireWindowsTest")
    app = App(config, work, private)
    app.cache.save("poseidon-scans", SeriesDetails("solo", "Solo Leveling", kind=Kind.WEBTOON, chapters=(
        ChapterInfo("2", "Chapitre 2", 2, 0), ChapterInfo("1", "Chapitre 1", 1, 0))))
    w = MainWindow(app)
    yield w
    w.close()


def test_library_shows_series_and_books(window):
    grid = window.home.library.grid
    assert grid.count() == 2
    titles = {grid.model_.item(i).data(0x0100).title for i in range(grid.count())}
    assert titles == {"Solo Leveling", "Le Petit Prince"}


def test_screens_open_and_close(window):
    window.show_series("poseidon-scans", "solo", "Solo Leveling", None)
    series = window.stack.currentWidget()
    assert series.list.count() == 2 and "Lire la suite" in series.read_button.text()
    series.list.item(0).setSelected(True)
    series._mark_selected()
    assert window.app.data.progress_of("poseidon-scans:solo").read_ids.keys() == {"1", "2"}
    window.back()
    window.show_book("isbn-9782070612758")
    window.back()
    window.show_settings()
    window.back()
    window.show_history()
    window.back()
    window.show_sources()
    window.show_source("poseidon-scans")
    window.back()
    window.back()
    assert window.stack.currentWidget() is window.home


def test_marks_reach_the_file(window):
    from grimoire_win.core import ops
    window.app.store.update(ops.mark_read("poseidon-scans:solo", ["2"]))
    saved = json.loads((window.app.store.root / "progress.json").read_text())
    assert "2" in saved["series"]["poseidon-scans:solo"]["read"]
