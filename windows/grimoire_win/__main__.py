from __future__ import annotations

import io
import sys
from pathlib import Path

INSTANCE = "grimoire-windows"


def main() -> int:
    # A "windowed" executable has no console: some libraries crash without stdout/stderr.
    if sys.stdout is None:
        sys.stdout = io.StringIO()
    if sys.stderr is None:
        sys.stderr = io.StringIO()

    from PySide6.QtCore import QByteArray
    from PySide6.QtGui import QIcon
    from PySide6.QtNetwork import QLocalServer, QLocalSocket
    from PySide6.QtWidgets import QApplication, QMessageBox

    from .ui.book_reader import register_scheme

    register_scheme()
    app = QApplication(sys.argv)
    app.setApplicationName("Grimoire")
    app.setOrganizationName("Grimoire")
    app.setStyle("Fusion")
    app.setWindowIcon(QIcon(str(Path(__file__).parent / "assets" / "icon.png")))

    # One window: an EPUB opened while Grimoire runs goes to the open window.
    paths = sys.argv[1:]
    socket = QLocalSocket()
    socket.connectToServer(INSTANCE)
    if socket.waitForConnected(300):
        socket.write(QByteArray("\n".join(paths).encode("utf-8")))
        socket.waitForBytesWritten(1000)
        return 0

    from .config import Config
    from .context import App
    from .ui import theme
    from .ui.main_window import MainWindow, ask_workdir

    config = Config()
    theme.apply_theme(app, config.theme)
    workdir = config.workdir
    if workdir is None or not workdir.is_dir():
        if workdir is not None:
            QMessageBox.warning(None, "Grimoire", f"Le dossier {workdir} est introuvable. Choisis-en un autre.")
        workdir = ask_workdir(None, first_run=workdir is None)
        if workdir is None:
            return 0
        config.workdir = workdir

    window = MainWindow(App(config, workdir))
    server = QLocalServer()
    QLocalServer.removeServer(INSTANCE)
    server.listen(INSTANCE)

    def incoming():
        conn = server.nextPendingConnection()

        def read():
            window.open_paths([p for p in bytes(conn.readAll()).decode("utf-8").splitlines() if p])
            window.showNormal()
            window.raise_()
            window.activateWindow()
        conn.readyRead.connect(read)
    server.newConnection.connect(incoming)
    window.show()
    if paths:
        window.open_paths(paths)
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
