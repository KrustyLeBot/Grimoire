"""A book: what the catalogues say of it (found by ISBN), how far it is read, and its file on this computer."""
from __future__ import annotations


from PySide6.QtCore import Qt
from PySide6.QtGui import QGuiApplication, QPixmap
from PySide6.QtWidgets import QHBoxLayout, QLabel, QMenu, QMessageBox, QProgressBar, QScrollArea, QVBoxLayout, QWidget

from ..books import store as books
from ..core.model import series_key
from . import theme
from . import widgets as w

LANGUAGES = {"fr": "Français", "en": "Anglais", "de": "Allemand", "es": "Espagnol", "it": "Italien", "pt": "Portugais",
             "nl": "Néerlandais", "ja": "Japonais", "zh": "Chinois", "ko": "Coréen"}


class BookScreen(QWidget):
    def __init__(self, window, book_id: str):
        super().__init__()
        self.window, self.app, self.ctrl = window, window.app, window.ctrl
        self.book_id, self.key = book_id, series_key(books.SOURCE, book_id)
        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)
        self.bar = w.TopBar("Livre", window.back)
        self.bar.add(w.tool("more", "Plus", self._menu))
        root.addWidget(self.bar)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        host = QWidget()
        self.body = QVBoxLayout(host)
        self.body.setContentsMargins(8, 8, 8, 24)
        scroll.setWidget(host)
        root.addWidget(scroll, 1)
        self.app.data_changed.connect(self.render)
        self.app.books_changed.connect(self.render)
        self.ctrl.book_info_changed.connect(lambda i: self.render() if i == book_id else None)
        self.ctrl.importing_changed.connect(lambda _b: self.render())
        self.ctrl.describe_book(book_id)
        self.render()

    def render(self) -> None:
        w.clear_layout(self.body)
        item = self.app.data.item(self.key)
        info = self.ctrl.info(self.book_id)
        p = self.ctrl.progress(self.key)
        read = books.READ in p.read_ids
        has_file = self.book_id in self.app.books.present()
        isbns = list(dict.fromkeys(([books.isbn_of(self.book_id)] if books.isbn_of(self.book_id) else []) +
                                   list(item.isbns if item else ()) + list(info.isbns if info else ())))
        title = (info.title if info else None) or (item.title if item else "Livre")
        self.bar.title.setText(title)

        header = QHBoxLayout()
        cover = QLabel()
        cover.setFixedSize(150, 225)
        cover.setStyleSheet(f"background: {theme.scheme().surface_highest}; border-radius: 12px;")
        header.addWidget(cover, 0, Qt.AlignmentFlag.AlignTop)
        url = (info.cover if info else None) or (item.cover if item else None) or self.app.books.local_cover(self.book_id)
        if url:
            def show(img, cover=cover):
                if img is not None:
                    pix = QPixmap.fromImage(img).scaled(cover.size() * 2, Qt.AspectRatioMode.KeepAspectRatioByExpanding,
                                                        Qt.TransformationMode.SmoothTransformation)
                    pix.setDevicePixelRatio(2)
                    try:
                        cover.setPixmap(pix)
                    except RuntimeError:
                        pass  # the screen was rebuilt meanwhile
            self.app.images.get(url, None, show)
        col = QVBoxLayout()
        col.addWidget(w.label(title, "heading"))
        if info and info.subtitle:
            col.addWidget(w.label(info.subtitle))
        if info and info.authors:
            col.addWidget(w.label(", ".join(info.authors), "hint"))
        meta = [x for x in (info.publisher if info else None, (info.published or "")[:4] if info else None,
                            f"{info.pages} pages" if info and info.pages else None,
                            LANGUAGES.get(info.language or "", info.language) if info else None) if x]
        if meta:
            col.addWidget(w.label(" · ".join(meta), "hint"))
        last = p.last
        percent = 100 if read else books.percent(last) if last else 0
        col.addWidget(w.label("Lu" if read else f"{percent} % · {last.chapter_name}" if last else "Pas commencé", "section"))
        if last or read:
            bar = QProgressBar()
            bar.setTextVisible(False)
            bar.setFixedHeight(4)
            bar.setValue(percent)
            col.addWidget(bar)
        if has_file:
            col.addWidget(w.button("Continuer" if last and not read else "Lire", "primary",
                                   lambda: self.window.show_book_reader(self.book_id)), 0, Qt.AlignmentFlag.AlignLeft)
        col.addStretch()
        header.addLayout(col, 1)
        self.body.addLayout(header)

        if not has_file:
            # Books are not synced: on this computer the file has to be fetched again.
            banner = w.Banner("Les livres ne sont pas synchronisés, seule ta lecture l'est. Récupère l'EPUB de cette édition"
                              + (f" (ISBN {isbns[0]})" if isbns else "") + " puis ouvre-le avec Grimoire : tu reprendras où tu en étais.",
                              "Fichier absent sur cet ordinateur")
            banner.action("Choisir le fichier", self.window.pick_book)
            self.body.addWidget(banner)
            if item and item.link:
                self.body.addWidget(w.button("Téléchargement…" if self.ctrl.importing else "Retélécharger", "primary",
                                             lambda: self.ctrl.download_book(item.link, page=item.page)), 0, Qt.AlignmentFlag.AlignLeft)
            if item and item.page:
                self.body.addWidget(w.button("Ouvrir la page de téléchargement", "outlined",
                                             lambda: self.window.show_web(item.page, "Téléchargement")), 0, Qt.AlignmentFlag.AlignLeft)
            query = isbns[0] if isbns else title
            for s in self.app.data.web_searches:
                self.body.addWidget(w.button(f"Chercher sur {s.name}", "outlined",
                                             lambda _=False, s=s: self.window.show_web(s.url_for(query), s.name)), 0, Qt.AlignmentFlag.AlignLeft)

        status = self.ctrl.book_lookup.get(self.book_id)
        if status == "…":
            self.body.addWidget(w.label("Recherche des infos en ligne…", "hint"))
        elif status and not info:
            self.body.addWidget(w.label(status, "hint"))
        if info and info.description:
            d = w.label(info.description)
            d.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
            self.body.addWidget(d)
        if isbns:
            card = w.Card()
            card.body.addWidget(w.label("ISBN" if len(isbns) == 1 else "ISBN de l'édition", "section"))
            for x in isbns:
                row = QHBoxLayout()
                lab = w.label(x)
                lab.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
                row.addWidget(lab, 1)
                row.addWidget(w.tool("copy", "Copier l'ISBN", lambda _=False, x=x: self._copy(x)))
                card.body.addLayout(row)
            card.body.addWidget(w.label("Une autre langue ou une autre édition a son propre ISBN, et donc sa propre lecture.", "hint"))
            self.body.addWidget(card)
        self.body.addStretch()

    def _copy(self, text: str) -> None:
        QGuiApplication.clipboard().setText(text)
        self.app.notice.emit("ISBN copié")

    def _menu(self) -> None:
        read = books.READ in self.ctrl.progress(self.key).read_ids
        menu = QMenu(self)
        if read:
            menu.addAction("Marquer comme non lu", lambda: self.ctrl.mark_unread(books.SOURCE, self.book_id, [books.READ]))
        else:
            menu.addAction("Marquer comme lu", lambda: self.ctrl.mark_read(books.SOURCE, self.book_id, [books.READ]))
        if self.book_id in self.app.books.present():
            menu.addAction("Supprimer le fichier de cet ordinateur", self._delete_file)
        menu.addAction("Retirer de la bibliothèque", self._remove)
        menu.exec(self.cursor().pos())

    def _delete_file(self) -> None:
        if QMessageBox.question(self, "Supprimer le fichier ?", "Le livre reste dans ta bibliothèque avec ta progression ; "
                                "il faudra rouvrir l'EPUB pour le lire.") == QMessageBox.StandardButton.Yes:
            self.app.books.delete(self.book_id)
            self.app.books_changed.emit()

    def _remove(self) -> None:
        if QMessageBox.question(self, "Retirer ce livre ?", "Il disparaît de la bibliothèque sur tous tes appareils, "
                                "et son fichier de cet ordinateur.") == QMessageBox.StandardButton.Yes:
            self.ctrl.remove_book(self.book_id)
            self.window.back()


