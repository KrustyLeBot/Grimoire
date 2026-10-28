"""Building blocks shared by the screens: cover grid, chips, cards, banners, top bar."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from PySide6.QtCore import QModelIndex, QRect, QRectF, QSize, Qt, Signal
from PySide6.QtGui import QBrush, QColor, QFont, QLinearGradient, QPainter, QPainterPath, QStandardItem, QStandardItemModel
from PySide6.QtWidgets import (QAbstractItemView, QFrame, QHBoxLayout, QLabel, QListView, QPushButton, QStyle,
                               QStyledItemDelegate, QToolButton, QVBoxLayout, QWidget)

from . import icons, theme

CELL_W, COVER_H, TEXT_H = 150, 214, 22


@dataclass
class Cell:
    key: str
    title: str
    cover: str | None
    source: str
    subtitle: str | None = None
    badge: str | None = None
    enabled: bool = True
    payload: object = None


class _Delegate(QStyledItemDelegate):
    def __init__(self, grid: "CoverGrid"):
        super().__init__(grid)
        self.grid = grid

    def sizeHint(self, option, index) -> QSize:
        return QSize(CELL_W + 12, COVER_H + TEXT_H + 14)

    def paint(self, painter: QPainter, option, index: QModelIndex) -> None:
        cell: Cell = index.data(Qt.ItemDataRole.UserRole)
        s = theme.scheme()
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        if not cell.enabled:
            painter.setOpacity(0.38)
        r = QRect(option.rect.x() + 6, option.rect.y() + 6, CELL_W, COVER_H)
        path = QPainterPath()
        path.addRoundedRect(QRectF(r), 12, 12)
        painter.setClipPath(path)
        painter.fillRect(r, QColor(s.surface_highest))
        image = self.grid.image(cell)
        if image is not None:
            scaled = image.scaled(r.size() * 2, Qt.AspectRatioMode.KeepAspectRatioByExpanding, Qt.TransformationMode.SmoothTransformation)
            sx, sy = (scaled.width() - r.width() * 2) // 2, (scaled.height() - r.height() * 2) // 2
            painter.drawImage(r, scaled, QRect(sx, sy, r.width() * 2, r.height() * 2))
        else:
            icons.icon("book", s.outline, 40).paint(painter, QRect(r.center().x() - 20, r.center().y() - 20, 40, 40))
        gradient = QLinearGradient(r.x(), r.y() + r.height() * 0.5, r.x(), r.bottom())
        gradient.setColorAt(0, QColor(0, 0, 0, 0))
        gradient.setColorAt(1, QColor(0, 0, 0, 200))
        painter.fillRect(r, QBrush(gradient))
        if option.state & QStyle.StateFlag.State_MouseOver and cell.enabled:
            painter.fillRect(r, QColor(255, 255, 255, 28))
        painter.setPen(QColor("#FFFFFF"))
        font = QFont(painter.font())
        font.setBold(True)
        painter.setFont(font)
        painter.drawText(r.adjusted(8, 0, -8, -8), Qt.AlignmentFlag.AlignBottom | Qt.AlignmentFlag.AlignLeft | Qt.TextFlag.TextWordWrap,
                         cell.title)
        if cell.badge:
            badge = QRect(r.right() - 34, r.y() + 6, 28, 20)
            painter.setPen(Qt.PenStyle.NoPen)
            painter.setBrush(QColor(s.primary))
            painter.drawRoundedRect(badge, 6, 6)
            painter.setPen(QColor(s.on_primary))
            painter.drawText(badge, Qt.AlignmentFlag.AlignCenter, cell.badge)
        painter.setClipping(False)
        if cell.subtitle:
            font.setBold(False)
            font.setPointSizeF(font.pointSizeF() * 0.9)
            painter.setFont(font)
            painter.setPen(QColor(s.on_surface_variant))
            text = painter.fontMetrics().elidedText(cell.subtitle, Qt.TextElideMode.ElideRight, CELL_W - 4)
            painter.drawText(QRect(r.x() + 2, r.bottom() + 4, CELL_W - 4, TEXT_H), Qt.AlignmentFlag.AlignLeft, text)
        painter.restore()


class CoverGrid(QListView):
    """Covers in a grid. `opened(cell)` on click; `near_end` when scrolled close to the bottom."""
    opened = Signal(object)
    near_end = Signal()

    def __init__(self, app, parent=None):
        super().__init__(parent)
        self.app = app
        self.setViewMode(QListView.ViewMode.IconMode)
        self.setResizeMode(QListView.ResizeMode.Adjust)
        self.setMovement(QListView.Movement.Static)
        self.setUniformItemSizes(True)
        self.setSpacing(4)
        self.setMouseTracking(True)
        self.setSelectionMode(QAbstractItemView.SelectionMode.NoSelection)
        self.setVerticalScrollMode(QAbstractItemView.ScrollMode.ScrollPerPixel)
        self.verticalScrollBar().setSingleStep(24)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.model_ = QStandardItemModel(self)
        self.setModel(self.model_)
        self.setItemDelegate(_Delegate(self))
        self.clicked.connect(self._clicked)
        self.verticalScrollBar().valueChanged.connect(self._scrolled)
        self.app.images.loaded.connect(lambda _url: self.viewport().update())

    def image(self, cell: Cell):
        if not cell.cover:
            return None
        image = self.app.images.cached(cell.cover)
        if image is None:
            src = self.app.sources.get(cell.source)
            self.app.images.get(cell.cover, src.image_headers() if src else None, lambda _i: None)
        return image

    def set_cells(self, cells: list[Cell]) -> None:
        self.model_.clear()
        self.append(cells)

    def append(self, cells: list[Cell]) -> None:
        for cell in cells:
            item = QStandardItem()
            item.setData(cell, Qt.ItemDataRole.UserRole)
            item.setEditable(False)
            self.model_.appendRow(item)

    def count(self) -> int:
        return self.model_.rowCount()

    def _clicked(self, index: QModelIndex) -> None:
        cell: Cell = index.data(Qt.ItemDataRole.UserRole)
        if cell.enabled:
            self.opened.emit(cell)

    def _scrolled(self, value: int) -> None:
        bar = self.verticalScrollBar()
        if bar.maximum() - value < 400:
            self.near_end.emit()

    def resizeEvent(self, e):
        super().resizeEvent(e)
        if self.verticalScrollBar().maximum() == 0:
            self.near_end.emit()


def chip(text: str, checkable: bool = True) -> QPushButton:
    b = QPushButton(text)
    b.setObjectName("chip")
    b.setCheckable(checkable)
    b.setCursor(Qt.CursorShape.PointingHandCursor)
    return b


def button(text: str, kind: str = "", on_click: Callable | None = None) -> QPushButton:
    b = QPushButton(text)
    if kind:
        b.setObjectName(kind)
    b.setCursor(Qt.CursorShape.PointingHandCursor)
    if on_click:
        b.clicked.connect(on_click)
    return b


def label(text: str = "", kind: str = "", wrap: bool = True) -> QLabel:
    lab = QLabel(text)
    if kind:
        lab.setObjectName(kind)
    lab.setWordWrap(wrap)
    return lab


def tool(name: str, tip: str, on_click: Callable | None = None, size: int = 22) -> QToolButton:
    b = QToolButton()
    b.setToolTip(tip)
    b.setProperty("icon_name", name)
    b.setIcon(icons.icon(name, theme.scheme().on_surface, size))
    b.setIconSize(QSize(size, size))
    b.setCursor(Qt.CursorShape.PointingHandCursor)
    if on_click:
        b.clicked.connect(on_click)
    return b


def retint(root: QWidget) -> None:
    """Gives every icon button the theme's colour again (after a theme change)."""
    for b in root.findChildren(QToolButton):
        name = b.property("icon_name")
        if name:
            b.setIcon(icons.icon(name, theme.scheme().on_surface, b.iconSize().width()))


class Card(QFrame):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("card")
        self.body = QVBoxLayout(self)
        self.body.setContentsMargins(16, 14, 16, 14)
        self.body.setSpacing(8)


class Banner(QFrame):
    def __init__(self, text: str, title: str | None = None, error: bool = False, parent=None):
        super().__init__(parent)
        self.setObjectName("errorBanner" if error else "banner")
        row = QHBoxLayout(self)
        row.setContentsMargins(16, 10, 10, 10)
        col = QVBoxLayout()
        if title:
            t = QLabel(title)
            t.setStyleSheet("font-weight: 700;")
            col.addWidget(t)
        self.text = label(text)
        col.addWidget(self.text)
        row.addLayout(col, 1)
        self.actions = QHBoxLayout()
        row.addLayout(self.actions)

    def action(self, text: str, on_click: Callable) -> QPushButton:
        b = button(text, on_click=on_click)
        self.actions.addWidget(b)
        return b


class TopBar(QWidget):
    """Title with an optional back arrow, and actions on the right."""

    def __init__(self, title: str, on_back: Callable | None = None):
        super().__init__()
        self.row = QHBoxLayout(self)
        self.row.setContentsMargins(4, 4, 8, 4)
        if on_back:
            self.row.addWidget(tool("back", "Retour (Échap)", on_back))
        self.title = QLabel(title)
        self.title.setObjectName("title")
        self.row.addWidget(self.title, 1)

    def add(self, widget: QWidget) -> QWidget:
        self.row.addWidget(widget)
        return widget


def clear_layout(layout) -> None:
    while layout.count():
        item = layout.takeAt(0)
        if item.widget():
            item.widget().deleteLater()
        elif item.layout():
            clear_layout(item.layout())
