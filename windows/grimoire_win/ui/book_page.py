"""The book reader's page: the same style and script as the Android app's reader, so a book
looks and behaves the same on both. The script talks to the app through console messages."""
from __future__ import annotations

import json

COMMON = """
        html, body {{ margin: 0 !important; padding: 0 !important; background: {bg} !important; }}
        body {{ -webkit-text-size-adjust: 100%; }}
        #g-flow {{ box-sizing: border-box; color: {fg} !important; font-size: {size}px !important;
                  line-height: 1.6 !important; overflow-wrap: break-word; hyphens: auto; text-align: start; }}
        #g-flow * {{ color: inherit !important; background-color: transparent !important; max-width: 100% !important; }}
        #g-flow p, #g-flow div, #g-flow span, #g-flow li, #g-flow td, #g-flow blockquote, #g-flow a, #g-flow em, #g-flow i, #g-flow b, #g-flow strong {{
                  font-size: inherit !important; line-height: inherit !important; }}
        #g-flow h1 {{ font-size: 1.6em !important; line-height: 1.3 !important; }}
        #g-flow h2 {{ font-size: 1.35em !important; line-height: 1.3 !important; }}
        #g-flow h3 {{ font-size: 1.2em !important; line-height: 1.3 !important; }}
        #g-flow h4, #g-flow h5, #g-flow h6 {{ font-size: 1.05em !important; line-height: 1.3 !important; }}
        #g-flow a[href], #g-flow a[href] * {{ color: {link} !important; text-decoration: none; }}
        #g-flow img, #g-flow svg, #g-flow video {{ max-width: 100% !important; height: auto !important; }}
        #g-end {{ display: inline-block; width: 1px; height: 1px; }}
    """

PAGED = """
        html, body {{ height: 100% !important; overflow: hidden !important; }}
        #g-flow {{ position: absolute !important; top: 0; left: 0; margin: 0 !important; padding: 32px 24px !important;
                  column-gap: 48px; column-fill: auto; overflow: hidden !important; }}
        #g-flow img, #g-flow svg {{ object-fit: contain; break-inside: avoid; }}
    """

SCROLL = """
        #g-flow {{ padding: 24px 20px 8px !important; }}
        #g-next {{ display: block; margin: 24px auto 64px; font: inherit; font-size: 16px; padding: 10px 22px;
                  border-radius: 22px; border: 1px solid {link}; background: transparent; color: {link}; }}
        #g-last {{ text-align: center; margin: 24px 0 64px; opacity: .7; color: {fg}; }}
    """



def css(bg: str, fg: str, link: str, size: int, paged: bool) -> str:
    text = COMMON + (PAGED if paged else SCROLL + SCROLL_WIDTH)
    return text.format(bg=bg, fg=fg, link=link, size=size)


# On a computer the text keeps a comfortable line length: the flow is at most this wide, centred.
SCROLL_WIDTH = """
        #g-flow {{ max-width: 760px; margin: 0 auto !important; }}
"""

# The page's link to the app: Grimoire.position/next/prev/tap become console messages.
BRIDGE = """
var Grimoire = {
  send: function (m) { console.log('grimoire:' + JSON.stringify(m)); },
  position: function (f) { this.send({t: 'position', f: f}); },
  next: function () { this.send({t: 'next'}); },
  prev: function () { this.send({t: 'prev'}); },
  tap: function (x) { this.send({t: 'tap', x: x}); }
};
"""

SCRIPT = r"""
(function () {
  var c = __CONFIG__;
  var flow = document.getElementById('g-flow'), end = document.getElementById('g-end');
  var se = document.scrollingElement || document.documentElement;
  var page = 0, fraction = c.start, shown = false, width = 0, height = 0;
  // Pages: the flow gets the window's size in pixels (measured, not 100vh, which a WebView can
  // read as 0 before its first layout) and is split into one column per screen; turning a page
  // scrolls it by one screen width.
  function layout() {
    width = Math.min(window.innerWidth, c.maxWidth); height = window.innerHeight;
    if (!c.paged || width <= 0 || height <= 0) return;
    flow.style.left = Math.max(0, (window.innerWidth - width) / 2) + 'px';
    flow.style.width = width + 'px';
    flow.style.height = height + 'px';
    flow.style.columnWidth = (width - 48) + 'px';
    var media = flow.querySelectorAll('img, svg, video');
    for (var i = 0; i < media.length; i++) {
      media[i].style.maxHeight = (height - 72) + 'px';
      // A cover's wrapper is often height: 100%, which spills it onto an empty second page.
      for (var e = media[i].parentNode; e && e !== flow && e.nodeType === 1; e = e.parentNode) {
        if (e.textContent.replace(/\s/g, '') !== '') break;
        e.style.setProperty('height', 'auto', 'important');
      }
    }
  }
  function count() {
    if (width <= 0) return 1;
    var x = end.getBoundingClientRect().left - flow.getBoundingClientRect().left + flow.scrollLeft;
    var n = Math.floor((x + 1) / width) + 1;
    return isFinite(n) && n > 0 ? n : 1;
  }
  function show(p) { page = p; flow.scrollLeft = p * width; }
  function report() {
    if (c.paged) { var n = count(); fraction = n > 1 ? page / (n - 1) : 1; }
    else { var max = se.scrollHeight - window.innerHeight; fraction = max > 0 ? se.scrollTop / max : 1; }
    if (isFinite(fraction)) Grimoire.position(fraction);
  }
  function go(f) {
    try {
      layout();
      if (c.paged) { var n = count(); show(Math.max(0, Math.min(n - 1, Math.round(f * (n - 1))))); }
      else { se.scrollTop = f * (se.scrollHeight - window.innerHeight); }
      report();
    } finally {
      if (!shown) { shown = true; document.body.style.visibility = 'visible'; }
    }
  }
  window.grimoire = {
    turn: function (d) {
      var n = count(), p = page + d;
      if (p < 0) { Grimoire.prev(); return; }
      if (p >= n) { Grimoire.next(); return; }
      show(p); report();
    }
  };
  document.body.style.visibility = 'hidden';
  if (!c.paged) {
    var tail;
    if (c.last) { tail = document.createElement('p'); tail.id = 'g-last'; tail.textContent = 'Fin du livre'; }
    else {
      tail = document.createElement('button'); tail.id = 'g-next'; tail.textContent = 'Partie suivante';
      tail.addEventListener('click', function (e) { e.stopPropagation(); Grimoire.next(); });
    }
    document.body.appendChild(tail);
    var timer = null;
    window.addEventListener('scroll', function () { if (timer) clearTimeout(timer); timer = setTimeout(report, 250); }, { passive: true });
  }
  document.addEventListener('click', function (e) {
    var t = e.target;
    if (t && t.closest && t.closest('a[href], button')) return;
    Grimoire.tap(e.clientX / window.innerWidth);
  });
  if (c.paged) {
    var x0 = null, y0 = 0;
    document.addEventListener('touchstart', function (e) { x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; }, { passive: true });
    document.addEventListener('touchend', function (e) {
      if (x0 === null) return;
      var dx = e.changedTouches[0].clientX - x0, dy = e.changedTouches[0].clientY - y0; x0 = null;
      if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) window.grimoire.turn(dx < 0 ? 1 : -1);
    }, { passive: true });
  }
  window.addEventListener('resize', function () { go(isFinite(fraction) ? fraction : 0); });
  if (c.paged) {
    var wheelAt = 0;
    window.addEventListener('wheel', function (e) {
      var now = Date.now();
      if (now - wheelAt < 250 || Math.abs(e.deltaY) < 4) return;
      wheelAt = now; window.grimoire.turn(e.deltaY > 0 ? 1 : -1);
    }, { passive: true });
  }
  // Waits for the WebView to have a size, and for images and fonts to be laid out.
  var tries = 0;
  function ready() {
    if ((window.innerWidth <= 0 || window.innerHeight <= 0) && tries++ < 100) { setTimeout(ready, 20); return; }
    setTimeout(function () { go(c.start); }, 30);
  }
  if (document.readyState === 'complete') ready(); else window.addEventListener('load', ready);
  setTimeout(function () { if (!shown) go(c.start); }, 2500);
})();
"""


def script(paged: bool, start: float, last: bool, max_width: int = 820) -> str:
    config = json.dumps({"paged": paged, "start": start, "last": last, "maxWidth": max_width})
    return BRIDGE + SCRIPT.replace("__CONFIG__", config)
