"""Draws the Grimoire icon, the same as the Android launcher icon (fuchsia gradient, open book, gold sparkle), as icon.png and icon.ico.

Usage: python tools/make_icon.py   (needs PySide6 and Pillow)
"""
import os
import sys
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PIL import Image
from PySide6.QtCore import QByteArray
from PySide6.QtGui import QGuiApplication, QImage, QPainter
from PySide6.QtSvg import QSvgRenderer

# The launcher icon's 108-unit grid, cropped to its visible 72-unit square.
SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72">
  <defs>
    <linearGradient id="bg" x1="10" y1="0" x2="98" y2="108" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#C850E0"/><stop offset="1" stop-color="#4E0F66"/></linearGradient>
    <radialGradient id="glow" cx="54" cy="31" r="20" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFE9A8" stop-opacity="0.55"/><stop offset="1" stop-color="#FFE9A8" stop-opacity="0"/></radialGradient>
    <linearGradient id="pl" x1="22" y1="0" x2="52.6" y2="0" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFFFFF"/><stop offset="0.8" stop-color="#F6E7FA"/><stop offset="1" stop-color="#DDBDE8"/></linearGradient>
    <linearGradient id="pr" x1="86" y1="0" x2="55.4" y2="0" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFFFFF"/><stop offset="0.8" stop-color="#F6E7FA"/><stop offset="1" stop-color="#DDBDE8"/></linearGradient>
    <linearGradient id="gold" x1="0" y1="20" x2="0" y2="40" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFF1B8"/><stop offset="1" stop-color="#FFC233"/></linearGradient>
  </defs>
  <rect x="18" y="18" width="72" height="72" rx="16" fill="url(#bg)"/>
  <g transform="translate(54 56) scale(0.86) translate(-54 -56)">
    <circle cx="54" cy="31" r="20" fill="url(#glow)"/>
    <path d="M19,46 C31,41 45,42 54,47 C63,42 77,41 89,46 L89,84 C77,80 63,81 54,86 C45,81 31,80 19,84 Z" fill="#2E0740"/>
    <path d="M22,44.5 C33,40 45,41 52.6,45.5 L52.6,82 C45,78 33,77.5 22,80.5 Z" fill="url(#pl)"/><path d="M86,44.5 C75,40 63,41 55.4,45.5 L55.4,82 C63,78 75,77.5 86,80.5 Z" fill="url(#pr)"/>
    <path d="M27,51 C34,46.8 42,47.4 48.5,51.6 M81,51 C74,46.8 66,47.4 59.5,51.6 M27,57 C34,53.15 42,53.75 48.5,57.6 M81,57 C74,53.15 66,53.75 59.5,57.6 M27,63 C34,59.5 42,60.1 48.5,63.6 M81,63 C74,59.5 66,60.1 59.5,63.6 M27,69 C34,65.85 42,66.44999999999999 48.5,69.6 M81,69 C74,65.85 66,66.44999999999999 59.5,69.6" fill="none" stroke="#D2AEE0" stroke-width="1.7" stroke-linecap="round"/>
    <path d="M52.3,80 L55.7,80 L55.7,88 L54,86 L52.3,88 Z" fill="#FFB020"/>
    <path d="M54,21 C55.44,26.832 57.168,28.56 63,30 C57.168,31.44 55.44,33.168 54,39 C52.56,33.168 50.832,31.44 45,30 C50.832,28.56 52.56,26.832 54,21 Z" fill="url(#gold)"/><path d="M39.5,29.6 C40.044,31.8032 40.6968,32.456 42.9,33 C40.6968,33.544 40.044,34.1968 39.5,36.4 C38.956,34.1968 38.3032,33.544 36.1,33 C38.3032,32.456 38.956,31.8032 39.5,29.6 Z" fill="#FFE08A"/><path d="M69.5,32.9 C69.916,34.5848 70.4152,35.084 72.1,35.5 C70.4152,35.916 69.916,36.4152 69.5,38.1 C69.084,36.4152 68.5848,35.916 66.9,35.5 C68.5848,35.084 69.084,34.5848 69.5,32.9 Z" fill="#FFE08A"/>
  </g>
</svg>"""

out = Path(__file__).resolve().parent.parent / "grimoire_win" / "assets"
out.mkdir(parents=True, exist_ok=True)
app = QGuiApplication(sys.argv)
image = QImage(256, 256, QImage.Format.Format_ARGB32)
image.fill(0)
painter = QPainter(image)
painter.setRenderHint(QPainter.RenderHint.Antialiasing)
QSvgRenderer(QByteArray(SVG.encode())).render(painter)
painter.end()
image.save(str(out / "icon.png"))
Image.open(out / "icon.png").save(out / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("written", out)
