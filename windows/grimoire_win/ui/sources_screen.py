"""The configured sources: enable, rename, change a site's address, options, test, login, remove;
and adding a source from the built-in catalogue. Synced with the phone through sources.json."""
from __future__ import annotations

from dataclasses import replace

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QCheckBox, QHBoxLayout, QLineEdit, QMenu, QMessageBox, QScrollArea, QVBoxLayout, QWidget

from ..core import ops
from ..core.model import SourceConfig
from ..services.async_util import run_async
from ..sources import catalog
from ..sources.http import describe
from . import widgets as w


class SourcesScreen(QWidget):
    def __init__(self, window):
        super().__init__()
        self.window, self.app = window, window.app
        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 0)
        bar = w.TopBar("Sources", window.back)
        bar.add(w.button("Ajouter une source", "primary", self._add))
        root.addWidget(bar)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        host = QWidget()
        self.body = QVBoxLayout(host)
        self.body.setContentsMargins(16, 8, 16, 24)
        scroll.setWidget(host)
        root.addWidget(scroll, 1)
        self.app.data_changed.connect(self.render)
        self.render()

    def render(self) -> None:
        w.clear_layout(self.body)
        if not self.app.data.sources:
            self.body.addWidget(w.label("Aucune source. Ajoute-en une avec le bouton en haut.", "hint"))
        for s in self.app.data.sources:
            card = w.Card()
            row = QHBoxLayout()
            col = QVBoxLayout()
            col.addWidget(w.label(s.name, wrap=False))
            col.addWidget(w.label(s.base_url.removeprefix("https://") + ("" if s.enabled else " · désactivée"), "hint"))
            row.addLayout(col, 1)
            enabled = QCheckBox("Active")
            enabled.setChecked(s.enabled)
            enabled.toggled.connect(lambda v, s=s: self.app.store.update(ops.save_source(replace(s, enabled=v))))
            row.addWidget(enabled)
            row.addWidget(w.button("Régler", "outlined", lambda _=False, s=s: self.window.show_source(s.id)))
            card.body.addLayout(row)
            self.body.addWidget(card)
        self.body.addWidget(w.label("Les sources sont intégrées à l'application ; une nouvelle sorte de source arrive avec une mise à jour. "
                                    "Leur réglage est enregistré dans le dossier Grimoire et suit sur tes appareils.", "hint"))
        self.body.addStretch()

    def _add(self) -> None:
        menu = QMenu(self)
        for t in catalog.TYPES:
            menu.addAction(f"{t.name} ({t.lang.upper()}) — {t.description}", lambda t=t: self._create(t))
        menu.exec(self.cursor().pos())

    def _create(self, t) -> None:
        taken = {s.id for s in self.app.data.sources}
        n = 1
        while (t.id if n == 1 else f"{t.id}-{n}") in taken:
            n += 1
        sid = t.id if n == 1 else f"{t.id}-{n}"
        self.app.store.update(ops.save_source(SourceConfig(sid, t.id, t.name, t.default_base_url,
                                                           options=tuple((o.key, str(o.default).lower()) for o in t.options))))
        self.window.show_source(sid)


class SourceEditScreen(QWidget):
    def __init__(self, window, source_id: str):
        super().__init__()
        self.window, self.app = window, window.app
        self.source_id = source_id
        config = self.app.data.source(source_id)
        t = catalog.type_of(config.type) if config else None
        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 0)
        root.addWidget(w.TopBar(config.name if config else "Source", window.back))
        body = QVBoxLayout()
        body.setContentsMargins(16, 8, 16, 24)
        root.addLayout(body)
        if config is None:
            body.addWidget(w.label("Source introuvable.", "hint"))
            root.addStretch()
            return
        card = w.Card()
        card.body.addWidget(w.label(t.name if t else config.type, "section"))
        self.name = QLineEdit(config.name)
        self.name.setPlaceholderText("Nom")
        self.url = QLineEdit(config.base_url)
        self.url.setPlaceholderText("Adresse du site")
        card.body.addWidget(self.name)
        card.body.addWidget(self.url)
        card.body.addWidget(w.label(f"Ces sites changent parfois d'adresse : mets la nouvelle ici. Par défaut : "
                                    f"{t.default_base_url if t else '—'}", "hint"))
        row = QHBoxLayout()
        row.addWidget(w.button("Enregistrer", "primary", self._save))
        row.addWidget(w.button("Tester", "outlined", self._test))
        row.addStretch()
        card.body.addLayout(row)
        self.test_result = w.label("", "hint")
        card.body.addWidget(self.test_result)
        body.addWidget(card)
        if t and t.options:
            card = w.Card()
            card.body.addWidget(w.label("Options", "section"))
            for o in t.options:
                box = QCheckBox(o.label)
                box.setChecked((config.option(o.key) or str(o.default)).lower() == "true")
                box.toggled.connect(lambda v, o=o: self._option(o.key, v))
                card.body.addWidget(box)
                card.body.addWidget(w.label(o.hint, "hint"))
            body.addWidget(card)
        if t and t.has_login:
            card = w.Card()
            card.body.addWidget(w.label("Compte", "section"))
            card.body.addWidget(w.label("Facultatif. Connecte-toi sur le site (ou passe sa vérification anti-robot) : Grimoire réutilise "
                                        "la session.", "hint"))
            card.body.addWidget(w.button("Ouvrir le site pour se connecter", "outlined",
                                         lambda: self.window.show_web(config.base_url, f"Connexion · {config.name}")), 0, Qt.AlignmentFlag.AlignLeft)
            body.addWidget(card)
        card = w.Card()
        card.body.addWidget(w.label("Supprimer", "section"))
        card.body.addWidget(w.label("Ta bibliothèque et tes lectures sont gardées ; elles reviennent si tu rajoutes la source.", "hint"))
        card.body.addWidget(w.button("Supprimer la source", "outlined", self._remove), 0, Qt.AlignmentFlag.AlignLeft)
        body.addWidget(card)
        root.addStretch()

    def _config(self) -> SourceConfig | None:
        return self.app.data.source(self.source_id)

    def _save(self) -> None:
        c = self._config()
        url = self.url.text().strip().rstrip("/")
        if c and url.startswith("http"):
            self.app.store.update(ops.save_source(replace(c, name=self.name.text().strip() or c.name, base_url=url)))
            self.app.notice.emit("Source enregistrée")

    def _option(self, key: str, value: bool) -> None:
        c = self._config()
        if c:
            options = dict(c.options)
            options[key] = "true" if value else "false"
            self.app.store.update(ops.save_source(replace(c, options=tuple(options.items()))))

    def _test(self) -> None:
        self.test_result.setText("Test en cours…")
        src = self.app.sources.get(self.source_id)

        def ok(page):
            self.test_result.setText(f"OK : {len(page.items)} séries récentes trouvées")
        run_async(lambda: src.latest(1), ok, lambda e: self.test_result.setText(f"Échec : {describe(e)}"))

    def _remove(self) -> None:
        c = self._config()
        if c and QMessageBox.question(self, f"Supprimer {c.name} ?", "Ta bibliothèque et tes lectures sont gardées.") == QMessageBox.StandardButton.Yes:
            self.app.store.update(ops.remove_source(self.source_id))
            self.window.back()
