"""A website inside Grimoire: a configured web search, a book's download page, a source's login.
One persistent profile: logins survive restarts, and its cookies feed the app's HTTP client (an
anti-bot page or a premium login passed here works for the sources too). A file the site offers is
downloaded by the web engine itself (with the site's session), then imported with its link and page."""
from __future__ import annotations

import tempfile
from pathlib import Path

from PySide6.QtCore import QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWebEngineCore import QWebEngineDownloadRequest, QWebEnginePage, QWebEngineProfile
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtWidgets import QProgressBar, QVBoxLayout, QWidget

from ..sources.http import USER_AGENT
from . import widgets as w

_profile: QWebEngineProfile | None = None


def profile(app) -> QWebEngineProfile:
    global _profile
    if _profile is None:
        _profile = QWebEngineProfile("grimoire", app)
        _profile.setPersistentStoragePath(str(app.private / "web"))
        _profile.setPersistentCookiesPolicy(QWebEngineProfile.PersistentCookiesPolicy.ForcePersistentCookies)
        _profile.setHttpUserAgent(USER_AGENT)
        store = _profile.cookieStore()
        store.cookieAdded.connect(lambda c: app.http.add_cookie(
            bytes(c.name()).decode(), bytes(c.value()).decode(), c.domain(), c.path()))
        store.loadAllCookies()
    return _profile


class WebScreen(QWidget):
    def __init__(self, window, url: str, title: str):
        super().__init__()
        self.window, self.app, self.ctrl = window, window.app, window.ctrl
        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 0)
        self.bar = w.TopBar(title, window.back)
        self.bar.add(w.tool("chevron_left", "Page précédente", lambda: self.view.back()))
        self.bar.add(w.tool("refresh", "Recharger", lambda: self.view.reload()))
        self.bar.add(w.tool("open_in_new", "Ouvrir dans le navigateur", lambda: QDesktopServices.openUrl(self.view.url())))
        root.addWidget(self.bar)
        self.progress = QProgressBar()
        self.progress.setTextVisible(False)
        self.progress.setFixedHeight(3)
        root.addWidget(self.progress)
        self.view = QWebEngineView()
        self.view.setPage(QWebEnginePage(profile(self.app), self.view))
        self.view.titleChanged.connect(lambda t: self.bar.title.setText(t or title))
        self.view.loadProgress.connect(lambda p: (self.progress.setValue(p), self.progress.setVisible(p < 100)))
        root.addWidget(self.view, 1)
        self._downloads = []
        profile(self.app).downloadRequested.connect(self._download)
        self.view.setUrl(QUrl(url))

    def _download(self, d: QWebEngineDownloadRequest) -> None:
        if not self.isVisible():
            return
        name = d.downloadFileName() or "livre.epub"
        if not (name.lower().endswith(".epub") or d.mimeType() == "application/epub+zip"):
            self.app.notice.emit("Ce site propose un autre format qu'un EPUB : Grimoire ne lit que les EPUB")
            d.cancel()
            return
        page = self.view.url().toString()
        d.setDownloadDirectory(tempfile.mkdtemp(prefix="grimoire-"))
        d.setDownloadFileName(name if name.lower().endswith(".epub") else name + ".epub")
        self._downloads.append(d)
        self.app.notice.emit("Téléchargement du livre…")

        def changed():
            if d.state() == QWebEngineDownloadRequest.DownloadState.DownloadCompleted:
                self.ctrl.import_book(Path(d.downloadDirectory()) / d.downloadFileName(), link=d.url().toString(),
                                      page=page, delete_after=True)
            elif d.state() in (QWebEngineDownloadRequest.DownloadState.DownloadCancelled,
                               QWebEngineDownloadRequest.DownloadState.DownloadInterrupted):
                self.app.notice.emit(f"Téléchargement interrompu : {d.interruptReasonString()}")
        d.stateChanged.connect(lambda _s: changed())
        d.accept()

    def closing(self) -> None:
        try:
            profile(self.app).downloadRequested.disconnect(self._download)
        except (RuntimeError, TypeError):
            pass
        self.view.setUrl(QUrl("about:blank"))
