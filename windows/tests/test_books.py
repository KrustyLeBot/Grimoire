import json
import zipfile

import pytest

from grimoire_win.books import chapter, info, isbn, language
from grimoire_win.books.epub import Epub, EpubError
from grimoire_win.books.store import Books, id_of, isbn_of, percent
from grimoire_win.core.model import LastRead

CONTAINER = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

OPF3 = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:1234</dc:identifier>
    <dc:identifier>urn:isbn:978-2-07-061275-8</dc:identifier>
    <dc:title>Le Petit Prince</dc:title>
    <dc:creator>Antoine de Saint-Exupéry</dc:creator>
    <dc:language>fr</dc:language>
    <dc:description>&lt;p&gt;Un aviateur &lt;b&gt;perdu&lt;/b&gt;.&lt;/p&gt;</dc:description>
    <dc:source>Édition papier ISBN 0-306-40615-2</dc:source>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="Text/chap%201.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="Text/chap2.xhtml" media-type="application/xhtml+xml"/>
    <item id="img" href="Images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>"""

NAV = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol><li><a href="Text/chap%201.xhtml">Chapitre I</a></li><li><a href="Text/chap2.xhtml#s">Chapitre II</a></li></ol></nav>
</body></html>"""


def make_epub(tmp_path, files, name="book.epub"):
    path = tmp_path / name
    with zipfile.ZipFile(path, "w") as z:
        for n, t in files.items():
            z.writestr(n, t)
    return path


def test_isbn():
    assert isbn.normalize("978-2-07-061275-8") == "9782070612758"
    assert isbn.normalize("ISBN-13 : 978-2-07-061275-8") == "9782070612758"
    assert isbn.normalize("2-07-061275-9") == "9782070612758"
    assert isbn.normalize("080442957X") == "9780804429573"
    assert isbn.normalize("979-10-321-0000-4") == "9791032100004"
    assert isbn.normalize("9782070612759") is None
    assert isbn.normalize("urn:uuid:5f3c2a1e-0000-4000-8000-000000000000") is None
    assert isbn.all_in("Numérique : 978-2-07-061275-8 ; papier : ISBN-10 0-306-40615-2 ; 9782070612758") == ["9782070612758", "9780306406157"]


def test_epub3(tmp_path):
    path = make_epub(tmp_path, {"META-INF/container.xml": CONTAINER, "OEBPS/content.opf": OPF3, "OEBPS/nav.xhtml": NAV,
                                "OEBPS/Text/chap 1.xhtml": "<html/>", "OEBPS/Text/chap2.xhtml": "<html/>"})
    with Epub(path) as e:
        assert e.title == "Le Petit Prince" and e.authors == ["Antoine de Saint-Exupéry"]
        assert e.isbns == ["9782070612758", "9780306406157"] and e.unique_id == "urn:uuid:1234"
        assert e.description == "Un aviateur perdu."
        assert e.spine == ["OEBPS/Text/chap 1.xhtml", "OEBPS/Text/chap2.xhtml"]
        assert [t.title for t in e.toc] == ["Chapitre I", "Chapitre II"] and e.toc[1].fragment == "s"
        assert e.cover_path == "OEBPS/Images/cover.jpg"
        assert e.chapter_title(1) == "Chapitre II"


def test_epub2_ncx_without_isbn(tmp_path):
    opf = """<?xml version="1.0"?><opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
      <opf:metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Sans ISBN</dc:title>
      <dc:identifier id="BookId" opf:scheme="UUID">abc-def</dc:identifier><opf:meta name="cover" content="cov"/></opf:metadata>
      <opf:manifest><opf:item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
      <opf:item id="p1" href="part1.html" media-type="application/xhtml+xml"/><opf:item id="cov" href="../cover.png" media-type="image/png"/></opf:manifest>
      <opf:spine toc="ncx"><opf:itemref idref="p1"/></opf:spine></opf:package>"""
    ncx = """<?xml version="1.0"?><ncx><navMap><navPoint id="a"><navLabel><text>Première partie</text></navLabel>
      <content src="part1.html"/></navPoint></navMap></ncx>"""
    path = make_epub(tmp_path, {"META-INF/container.xml": CONTAINER, "OEBPS/content.opf": opf, "OEBPS/toc.ncx": ncx,
                                "OEBPS/part1.html": "<html/>"})
    with Epub(path) as e:
        assert (e.title, e.isbn, e.unique_id, e.spine) == ("Sans ISBN", None, "abc-def", ["OEBPS/part1.html"])
        assert e.toc[0].title == "Première partie" and e.cover_path == "cover.png"


def test_not_an_epub(tmp_path):
    with pytest.raises(EpubError):
        Epub(make_epub(tmp_path, {"x.txt": "y"}))


def test_ids_match_the_android_app():
    assert id_of("9782070612758", "x", "t", []) == "isbn-9782070612758"
    # sha1("urn:uuid:1") as on Android (Books.idOf)
    assert id_of(None, "urn:uuid:1", "a", []) == "id-" + __import__("hashlib").sha1(b"urn:uuid:1").hexdigest()[:16]
    assert id_of(None, None, "T", ["A", "B"]) == "id-" + __import__("hashlib").sha1("T|A, B".encode()).hexdigest()[:16]
    assert isbn_of("isbn-9782070612758") == "9782070612758" and isbn_of("id-abc") is None
    assert percent(LastRead("1", "x", 500, 4, 0)) == 37


def test_import_attaches_to_a_known_book(tmp_path):
    epub = make_epub(tmp_path, {"META-INF/container.xml": CONTAINER, "OEBPS/content.opf": OPF3, "OEBPS/nav.xhtml": NAV,
                                "OEBPS/Text/chap 1.xhtml": "<html/>", "OEBPS/Text/chap2.xhtml": "<html/>",
                                "OEBPS/Images/cover.jpg": "jpg"})
    books = Books(tmp_path / "data")
    imported = books.import_file(epub, lambda isbns: "isbn-9780306406157" if "9780306406157" in isbns else None)
    assert imported.id == "isbn-9780306406157" and not imported.replaced
    assert books.present() == {"isbn-9780306406157"} and books.local_cover(imported.id)
    assert books.import_file(epub, lambda _: None).id == "isbn-9782070612758"


def test_catalogues():
    ol = json.dumps({"ISBN:9782070612758": {"title": "Le petit prince", "authors": [{"name": "Antoine de Saint-Exupéry"}],
                                            "publishers": [{"name": "Gallimard"}], "number_of_pages": 93,
                                            "identifiers": {"isbn_10": ["2070612759"]},
                                            "cover": {"large": "https://covers.openlibrary.org/b/id/1-L.jpg"}}})
    i = info.from_open_library(ol, "9782070612758")
    assert (i.publisher, i.pages, i.isbns) == ("Gallimard", 93, ("9782070612758",))
    bnf = """<srw:searchRetrieveResponse><srw:records><srw:record><srw:recordData><oai_dc:dc>
      <dc:identifier>ISBN 9782070612758</dc:identifier>
      <dc:title>Le petit prince / Antoine de Saint-Exupéry ; avec des aquarelles de l'auteur</dc:title>
      <dc:creator>Saint-Exupéry, Antoine de (1900-1944). Auteur du texte</dc:creator>
      <dc:publisher>Gallimard (Paris)</dc:publisher><dc:date>2007</dc:date>
      <dc:format>1 vol. (93 p.) : ill. en coul. ; 18 cm</dc:format><dc:language>fre</dc:language>
      </oai_dc:dc></srw:recordData></srw:record></srw:records></srw:searchRetrieveResponse>"""
    b = info.from_bnf(bnf, "9782070612758")
    assert (b.title, b.authors, b.publisher, b.pages, b.language) == ("Le petit prince", ("Antoine de Saint-Exupéry",), "Gallimard", 93, "fr")
    assert info.from_bnf("<srw:searchRetrieveResponse/>", "x") is None
    assert info.work_of('{"works":[{"key":"/works/OL1W"}]}') == "/works/OL1W"
    assert info.summary_of('{"description":{"value":" Un aviateur… "}}') == "Un aviateur…"
    merged = info.BookInfo("T", description="D").merged(i)
    assert merged.authors == ("Antoine de Saint-Exupéry",) and merged.description == "D"
    assert info.decode(info.encode(merged)) == merged


def test_language():
    fr = "Un aviateur tombe en panne dans le désert du Sahara et rencontre un petit garçon venu d'une autre planète, qui lui demande de dessiner un mouton pour son voyage."
    en = "A pilot stranded in the Sahara desert meets a young prince who has come from a small asteroid, and who tells him the story of his travels and of the rose he loves."
    assert language.guess(fr) == "fr" and language.guess(en) == "en"
    assert language.pick("fr", [None, en, fr]) == fr
    assert language.pick("fr", [en]) is None
    assert language.pick("fre", ["court", fr]) == fr


def test_chapter_render():
    xhtml = """<?xml version="1.0" encoding="iso-8859-1"?><html xmlns="http://www.w3.org/1999/xhtml"><head><script>alert(1)</script></head><body class="chap"><p><a id="p1"/>Texte</p></body></html>"""
    out = chapter.render(xhtml, "p{}", "var a = 1 < 2;")
    assert "alert(1)" not in out and 'encoding="utf-8"' in out
    assert '<div id="g-flow"><p><a id="p1"/>Texte</p><span id="g-end"></span></div>' in out
    assert "<script>//<![CDATA[\nvar a = 1 < 2;\n//]]></script></body></html>" in out
    assert chapter.keeps_markup(xhtml) and not chapter.keeps_markup("<p>x</p>")
    assert '<div id="g-flow"><p>Un</p><span id="g-end"></span></div>' in chapter.render("<p>Un</p>", "", "")
    assert "été" in chapter.decode('<?xml version="1.0" encoding="ISO-8859-1"?><p>été</p>'.encode("latin-1"))
