"""Paramètres, laid out like the phone's: folder, sources, history, display, reading, downloads,
books, web searches, sync and updates."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QButtonGroup, QCheckBox, QDialog, QDialogButtonBox, QHBoxLayout, QLineEdit, QMessageBox,
                               QScrollArea, QSpinBox, QVBoxLayout, QWidget)

from ..core import ops
from ..core.model import WebSearch
from ..services import updater
from . import widgets as w
from .book_reader import THEMES


def megabytes(n: int) -> str:
    return f"{n / 1_000_000:.0f} Mo"


class SettingsScreen(QWidget):
    def __init__(self, window):
        super().__init__()
        self.window, self.app, self.ctrl = window, window.app, window.ctrl
        outer = QVBoxLayout(self)
        outer.setContentsMargins(8, 8, 8, 0)
        outer.addWidget(w.TopBar("Paramètres", window.back))
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        host = QWidget()
        row = QHBoxLayout(host)
        column = QWidget()
        column.setMaximumWidth(760)
        self.body = QVBoxLayout(column)
        self.body.setContentsMargins(16, 0, 16, 24)
        self.body.setSpacing(6)
        row.addStretch(1)
        row.addWidget(column, 100)
        row.addStretch(1)
        scroll.setWidget(host)
        outer.addWidget(scroll, 1)
        self.app.data_changed.connect(self._refresh_dynamic)
        self.build()

    def section(self, text: str) -> w.Card:
        self.body.addSpacing(12)
        self.body.addWidget(w.label(text, "section"))
        card = w.Card()
        self.body.addWidget(card)
        return card

    def chips(self, card: w.Card, options, current: str, on_pick) -> None:
        row = QHBoxLayout()
        group = QButtonGroup(card)
        for value, text in options:
            c = w.chip(text)
            c.setChecked(value == current)
            c.clicked.connect(lambda _=False, v=value: on_pick(v))
            group.addButton(c)
            row.addWidget(c)
        row.addStretch()
        card.body.addLayout(row)

    def build(self) -> None:
        cfg = self.app.config
        card = self.section("Dossier Grimoire")
        self.folder = w.label(str(self.app.store.root))
        self.folder.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        card.body.addWidget(self.folder)
        card.body.addWidget(w.label("Le dossier que Proton Drive synchronise avec Documents/Grimoire du téléphone : bibliothèque, "
                                    "lectures, sources et recherches web y sont partagées.", "hint"))
        card.body.addWidget(w.button("Choisir un autre dossier", "outlined", self.window.choose_workdir), 0, Qt.AlignmentFlag.AlignLeft)

        card = self.section("Sources")
        self.sources_count = w.label("")
        card.body.addWidget(self.sources_count)
        card.body.addWidget(w.label("Ajouter, régler l'adresse d'un site, se connecter, activer les chapitres premium…", "hint"))
        card.body.addWidget(w.button("Gérer les sources", "outlined", self.window.show_sources), 0, Qt.AlignmentFlag.AlignLeft)

        card = self.section("Historique")
        self.history_count = w.label("")
        card.body.addWidget(self.history_count)
        card.body.addWidget(w.label("Les derniers chapitres et livres lus, par jour : reprends-en un où tu l'avais laissé.", "hint"))
        card.body.addWidget(w.button("Voir l'historique", "outlined", self.window.show_history), 0, Qt.AlignmentFlag.AlignLeft)

        card = self.section("Affichage")
        card.body.addWidget(w.label("Thème de l'application"))
        self.chips(card, (("system", "Système"), ("light", "Clair"), ("dark", "Sombre")), cfg.theme, self.window.set_theme)

        card = self.section("Lecture")
        rtl = QCheckBox("Mode Pages de droite à gauche (mangas japonais)")
        rtl.setChecked(cfg.right_to_left)
        rtl.toggled.connect(lambda v: setattr(cfg, "right_to_left", v))
        card.body.addWidget(rtl)
        size_row = QHBoxLayout()
        size_row.addWidget(w.label("Taille du texte des livres", wrap=False))
        size = QSpinBox()
        size.setRange(12, 32)
        size.setValue(cfg.text_size)
        size.valueChanged.connect(lambda v: setattr(cfg, "text_size", v))
        size_row.addWidget(size)
        size_row.addStretch()
        card.body.addLayout(size_row)
        card.body.addWidget(w.label("Couleurs de lecture des livres"))
        self.chips(card, THEMES, cfg.book_theme, lambda v: setattr(cfg, "book_theme", v))
        card.body.addWidget(w.label("Le mode (Défilement ou Pages) se choisit dans le lecteur et est retenu pour chaque série. "
                                    "Flèches, Espace et molette tournent les pages.", "hint"))

        card = self.section("Téléchargements")
        self.downloads_size = w.label("")
        card.body.addWidget(self.downloads_size)
        card.body.addWidget(w.label("Chapitres gardés sur cet ordinateur seulement : ils ne passent pas par Proton Drive.", "hint"))
        card.body.addWidget(w.button("Tout supprimer", "outlined", self._delete_downloads), 0, Qt.AlignmentFlag.AlignLeft)

        card = self.section("Livres")
        self.books_size = w.label("")
        card.body.addWidget(self.books_size)
        card.body.addWidget(w.label("Fichiers EPUB gardés sur cet ordinateur seulement. Ta lecture est synchronisée, et un livre se "
                                    "retélécharge depuis sa page. Les infos (couverture, auteurs, résumé) viennent d'Open Library et "
                                    "de la BnF, d'après l'ISBN.", "hint"))
        card.body.addWidget(w.button("Supprimer les fichiers", "outlined", self._delete_books), 0, Qt.AlignmentFlag.AlignLeft)

        card = self.section("Recherche web")
        card.body.addWidget(w.label("Des sites où chercher un livre depuis Recherche ou depuis la page d'un livre. Ils s'ouvrent dans "
                                    "Grimoire : un EPUB téléchargé arrive directement dans ta bibliothèque, avec son lien.", "hint"))
        self.searches = QVBoxLayout()
        card.body.addLayout(self.searches)
        card.body.addWidget(w.button("Ajouter un site", "outlined", lambda: self._edit_search(None, WebSearch("", "https://"))),
                            0, Qt.AlignmentFlag.AlignLeft)

        card = self.section("Synchronisation Proton Drive")
        card.body.addWidget(w.label("Par le client Proton Drive", wrap=False))
        card.body.addWidget(w.label("Sur l'ordinateur, c'est l'application Proton Drive qui synchronise le dossier Grimoire ; Grimoire "
                                    "relit les fichiers dès qu'un autre appareil les modifie.", "hint"))

        card = self.section("Mise à jour")
        card.body.addWidget(w.label(f"Build {updater.current_build()}"))
        self.update_status = w.label("", "hint")
        card.body.addWidget(self.update_status)
        card.body.addWidget(w.button("Rechercher une mise à jour", "outlined", lambda: self.window.check_updates(manual=True)),
                            0, Qt.AlignmentFlag.AlignLeft)

        card = self.section("À propos")
        card.body.addWidget(w.label("Grimoire"))
        card.body.addWidget(w.label("Lecture de mangas, webtoons et livres. Bibliothèque et lectures synchronisées avec le téléphone, "
                                    "téléchargements hors ligne.", "hint"))
        card.body.addWidget(w.label("Fait partie de la suite Synchrotron · Griffonote · Ecoutify · Météorite.", "hint"))
        self.body.addStretch()
        self._refresh_dynamic()

    def _refresh_dynamic(self) -> None:
        data = self.app.data
        self.folder.setText(str(self.app.store.root))
        self.sources_count.setText(f"{len(data.sources)} source(s)")
        self.history_count.setText(f"{sum(1 for _, p in data.progress if p.last)} série(s) ou livre(s) commencé(s)")
        self.downloads_size.setText(f"Espace utilisé : {megabytes(self.app.downloads.size_bytes())}")
        self.books_size.setText(f"Fichiers EPUB : {megabytes(self.app.books.size_bytes())}")
        w.clear_layout(self.searches)
        for i, s in enumerate(data.web_searches):
            row = QHBoxLayout()
            col = QVBoxLayout()
            col.addWidget(w.label(s.name, wrap=False))
            col.addWidget(w.label(s.url, "hint"))
            row.addLayout(col, 1)
            row.addWidget(w.tool("settings", "Modifier", lambda _=False, i=i, s=s: self._edit_search(i, s)))
            row.addWidget(w.tool("delete", "Supprimer", lambda _=False, i=i: self.app.store.update(ops.remove_web_search(i))))
            self.searches.addLayout(row)

    def set_update_status(self, text: str) -> None:
        self.update_status.setText(text)

    def _delete_downloads(self) -> None:
        if QMessageBox.question(self, "Supprimer tous les téléchargements ?", "Tes lectures sont gardées.") == QMessageBox.StandardButton.Yes:
            self.app.downloads.delete_all()
            self._refresh_dynamic()

    def _delete_books(self) -> None:
        if QMessageBox.question(self, "Supprimer les fichiers des livres ?",
                                "Les livres restent dans ta bibliothèque avec ta lecture.") == QMessageBox.StandardButton.Yes:
            self.app.books.delete_all()
            self.app.books_changed.emit()
            self._refresh_dynamic()

    def _edit_search(self, index: int | None, search: WebSearch) -> None:
        dialog = QDialog(self)
        dialog.setWindowTitle("Ajouter un site" if index is None else "Modifier le site")
        dialog.setMinimumWidth(460)
        layout = QVBoxLayout(dialog)
        name, url = QLineEdit(search.name), QLineEdit(search.url)
        name.setPlaceholderText("Nom")
        url.setPlaceholderText("https://exemple.com/s/{q}")
        layout.addWidget(name)
        layout.addWidget(url)
        layout.addWidget(w.label("Écris {q} là où va la recherche (titre, auteur ou ISBN), par exemple https://exemple.com/s/{q}", "hint"))
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        save = buttons.button(QDialogButtonBox.StandardButton.Save)

        def valid():
            save.setEnabled(bool(name.text().strip()) and url.text().startswith("http") and WebSearch.PLACEHOLDER in url.text())
        name.textChanged.connect(valid)
        url.textChanged.connect(valid)
        valid()
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        if dialog.exec():
            self.app.store.update(ops.save_web_search(index, WebSearch(name.text().strip(), url.text().strip())))
