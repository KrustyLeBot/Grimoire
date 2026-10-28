"""Grimoire's palette (the same as the Android app's SuitePalette, fuchsia accent) and stylesheet."""
from __future__ import annotations

from dataclasses import dataclass

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QGuiApplication, QPalette


@dataclass(frozen=True)
class Scheme:
    background: str
    surface_container: str
    surface_high: str
    surface_highest: str
    outline: str
    outline_variant: str
    on_surface: str
    on_surface_variant: str
    primary: str
    on_primary: str
    primary_container: str
    on_primary_container: str
    secondary_container: str
    on_secondary_container: str
    error: str
    error_container: str
    on_error_container: str


LIGHT = Scheme("#FFF7FA", "#F7EAF3", "#F2E4EE", "#EDDEE9", "#837781", "#BBAEB8", "#383038", "#665C65", "#9B00B4",
               "#FFFFFF", "#FFCDFF", "#67416A", "#F4DBF1", "#5E4C5E", "#A8364B", "#F97386", "#6E0523")
DARK = Scheme("#110D11", "#1E181D", "#241D24", "#2B232B", "#7D727B", "#4E454D", "#F0E1EC", "#B4A7B1", "#F9ACFF",
              "#570066", "#664868", "#FFD7FE", "#473647", "#D0B8CE", "#F97386", "#871C34", "#FF97A3")

_dark = False


def is_dark() -> bool:
    return _dark


def scheme() -> Scheme:
    return DARK if _dark else LIGHT


def apply_theme(app, mode: str) -> None:
    """mode: 'system' | 'light' | 'dark'."""
    global _dark
    hints = app.styleHints()
    if hasattr(hints, "setColorScheme"):
        hints.setColorScheme({"light": Qt.ColorScheme.Light, "dark": Qt.ColorScheme.Dark}.get(mode, Qt.ColorScheme.Unknown))
    _dark = mode == "dark" or (mode == "system" and hints.colorScheme() == Qt.ColorScheme.Dark)
    s = scheme()
    palette = QPalette()
    for role, value in [
        (QPalette.ColorRole.Window, s.background), (QPalette.ColorRole.WindowText, s.on_surface),
        (QPalette.ColorRole.Base, s.background), (QPalette.ColorRole.AlternateBase, s.surface_container),
        (QPalette.ColorRole.Text, s.on_surface), (QPalette.ColorRole.Button, s.surface_container),
        (QPalette.ColorRole.ButtonText, s.on_surface), (QPalette.ColorRole.ToolTipBase, s.surface_high),
        (QPalette.ColorRole.ToolTipText, s.on_surface), (QPalette.ColorRole.Highlight, s.primary),
        (QPalette.ColorRole.HighlightedText, s.on_primary), (QPalette.ColorRole.PlaceholderText, s.on_surface_variant),
        (QPalette.ColorRole.Link, s.primary), (QPalette.ColorRole.Mid, s.outline_variant),
    ]:
        palette.setColor(role, QColor(value))
    app.setPalette(palette)
    app.setStyleSheet(stylesheet(s))


def stylesheet(s: Scheme) -> str:
    return f"""
    QMainWindow, QStackedWidget {{ background: {s.background}; }}
    QToolTip {{ background: {s.surface_high}; color: {s.on_surface}; border: 1px solid {s.outline_variant}; }}
    QLabel#title {{ font-size: 16pt; font-weight: 700; }}
    QLabel#heading {{ font-size: 15pt; font-weight: 700; }}
    QLabel#section {{ color: {s.primary}; font-weight: 600; }}
    QLabel#hint {{ color: {s.on_surface_variant}; }}
    QLabel#error {{ color: {s.error}; }}
    QFrame#toast {{ background: {s.surface_high}; border: 1px solid {s.outline_variant}; border-radius: 16px; }}
    QFrame#card {{ background: {s.surface_container}; border-radius: 16px; }}
    QFrame#banner {{ background: {s.primary_container}; border-radius: 16px; }}
    QFrame#banner QLabel {{ color: {s.on_primary_container}; }}
    QFrame#errorBanner {{ background: {s.error_container}; border-radius: 16px; }}
    QFrame#errorBanner QLabel {{ color: {s.on_error_container}; }}
    QFrame#snack {{ background: #322F32; border-radius: 8px; }}
    QFrame#snack QLabel {{ color: #F4EFF4; }}
    QFrame#bar {{ background: {s.surface_container}; }}
    QFrame#pill {{ background: {s.surface_high}; border-radius: 22px; }}
    QFrame#pill QLineEdit {{ border: none; background: transparent; font-size: 11pt; }}
    QLineEdit {{ border: 1px solid {s.outline_variant}; border-radius: 8px; padding: 6px 10px; background: {s.background}; }}
    QLineEdit:focus {{ border-color: {s.primary}; }}
    QPushButton {{ border: none; border-radius: 17px; padding: 8px 18px; min-height: 18px; background: transparent; color: {s.primary}; }}
    QPushButton:hover {{ background: {s.surface_container}; }}
    QPushButton:disabled {{ color: {s.outline}; }}
    QPushButton#primary {{ background: {s.primary}; color: {s.on_primary}; font-weight: 600; }}
    QPushButton#primary:disabled {{ background: {s.outline_variant}; color: {s.on_surface_variant}; }}
    QPushButton#outlined {{ border: 1px solid {s.outline}; }}
    QPushButton#chip {{ border: 1px solid {s.outline_variant}; border-radius: 8px; padding: 6px 12px; min-height: 0; color: {s.on_surface}; }}
    QPushButton#chip:checked {{ background: {s.secondary_container}; color: {s.on_secondary_container}; border-color: transparent; }}
    QPushButton#action {{ color: {s.on_secondary_container}; border-radius: 12px; padding: 6px 10px; }}
    QToolButton {{ border: none; border-radius: 20px; padding: 8px; background: transparent; }}
    QToolButton:hover {{ background: rgba(128, 128, 128, 0.2); }}
    QToolButton:checked {{ background: rgba(128, 128, 128, 0.3); }}
    QTabBar::tab {{ padding: 10px 22px; color: {s.on_surface_variant}; border: none; border-bottom: 3px solid transparent;
                    font-weight: 600; }}
    QTabBar::tab:selected {{ color: {s.primary}; border-bottom-color: {s.primary}; }}
    QTabBar::tab:hover {{ background: {s.surface_container}; }}
    QProgressBar {{ background: {s.outline_variant}; border: none; border-radius: 2px; }}
    QProgressBar::chunk {{ background: {s.primary}; border-radius: 2px; }}
    QScrollArea {{ border: none; background: transparent; }}
    QListView, QListWidget {{ border: none; background: transparent; outline: none; }}
    QListWidget::item {{ border-radius: 8px; padding: 7px 8px; }}
    QListWidget::item:selected {{ background: {s.secondary_container}; color: {s.on_secondary_container}; }}
    QListWidget::item:hover:!selected {{ background: {s.surface_container}; }}
    QScrollBar:vertical {{ width: 10px; background: transparent; }}
    QScrollBar::handle:vertical {{ background: {s.outline_variant}; border-radius: 5px; min-height: 30px; }}
    QScrollBar:horizontal {{ height: 10px; background: transparent; }}
    QScrollBar::handle:horizontal {{ background: {s.outline_variant}; border-radius: 5px; min-width: 30px; }}
    QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; width: 0; }}
    QMenu {{ background: {s.surface_high}; border: 1px solid {s.outline_variant}; padding: 4px; }}
    QMenu::item {{ padding: 6px 18px; border-radius: 6px; }}
    QMenu::item:selected {{ background: {s.secondary_container}; color: {s.on_secondary_container}; }}
    QCheckBox::indicator {{ width: 18px; height: 18px; }}
    """
