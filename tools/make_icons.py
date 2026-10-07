"""Draws the launcher banner (TV home row) and icon: a bear face and the app name.

    python tools/make_icons.py        (needs Pillow; uses Segoe UI Bold on Windows, DejaVu Sans Bold elsewhere)
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

RES = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
BG, BEAR, INNER, EYE, TEXT = "#1d1b26", "#c98a4b", "#f1d3b0", "#1d1b26", "#ffffff"


def font(size):
    for name in ("C:/Windows/Fonts/segoeuib.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        if Path(name).exists():
            return ImageFont.truetype(name, size)
    return ImageFont.load_default()


def bear(d, cx, cy, r):
    for ex in (-1, 1):  # ears
        d.ellipse([cx + ex * r * 0.72 - r * 0.38, cy - r * 0.95 - r * 0.38,
                   cx + ex * r * 0.72 + r * 0.38, cy - r * 0.95 + r * 0.38], fill=BEAR)
        d.ellipse([cx + ex * r * 0.72 - r * 0.2, cy - r * 0.95 - r * 0.2,
                   cx + ex * r * 0.72 + r * 0.2, cy - r * 0.95 + r * 0.2], fill=INNER)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=BEAR)
    d.ellipse([cx - r * 0.42, cy + r * 0.05, cx + r * 0.42, cy + r * 0.62], fill=INNER)  # muzzle
    d.ellipse([cx - r * 0.13, cy + r * 0.12, cx + r * 0.13, cy + r * 0.3], fill=EYE)  # nose
    for ex in (-1, 1):
        d.ellipse([cx + ex * r * 0.38 - r * 0.09, cy - r * 0.28, cx + ex * r * 0.38 + r * 0.09, cy - r * 0.1], fill=EYE)


def banner(w=640, h=360):  # 320x180 dp at xhdpi
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    bear(d, h * 0.42, h * 0.56, h * 0.24)
    left, right = h * 0.8, w - h * 0.1
    size = int(h * 0.24)
    while size > 10 and d.textlength("kumapie", font=font(size)) > right - left:
        size -= 2
    d.text((left, h * 0.52), "kumapie", font=font(size), fill=TEXT, anchor="lm")
    out = RES / "drawable-xhdpi" / "banner.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)


def icon(size=192):  # xxxhdpi launcher icon
    img = Image.new("RGB", (size, size), BG)
    bear(ImageDraw.Draw(img), size / 2, size * 0.57, size * 0.3)
    out = RES / "mipmap-xxxhdpi" / "ic_launcher.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)


def adaptive(s=432):  # phone/tablet launcher icon: 108 dp layers at xxxhdpi, the launcher shows the middle 72 dp
    from PIL import ImageFilter
    res = RES.parents[3] / "mobile" / "src" / "main" / "res"
    bg = Image.new("RGB", (s, s))
    d = ImageDraw.Draw(bg)
    top, bottom = (0x2b, 0xc4, 0xb4), (0x0b, 0x4f, 0x77)
    for y in range(s):
        d.line([(0, y), (s, y)], fill=tuple(round(a + (b - a) * y / s) for a, b in zip(top, bottom)))
    shine = Image.new("L", (s, s))
    ImageDraw.Draw(shine).ellipse((-60, -230, s + 60, 190), fill=60)  # glossy light across the top
    bg.paste((255, 255, 255), mask=shine.filter(ImageFilter.GaussianBlur(30)))
    face = Image.new("RGBA", (s * 4, s * 4))  # drawn 4x and scaled down: smooth edges
    bear(ImageDraw.Draw(face), s * 2, s * 4 * 0.57, s * 4 * 0.165)
    face = face.resize((s, s), Image.LANCZOS)
    shadow = Image.new("RGBA", (s, s))
    shadow.paste((0, 0, 0, 110), (7, 10), face.getchannel("A"))
    fg = Image.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(7)), face)
    mono = Image.new("RGBA", (s, s))
    mono.paste((255, 255, 255, 255), (0, 0), face.getchannel("A"))
    (res / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
    bg.save(res / "drawable-nodpi" / "ic_launcher_background.png")
    fg.save(res / "drawable-nodpi" / "ic_launcher_foreground.png")
    mono.save(res / "drawable-nodpi" / "ic_launcher_monochrome.png")
    (res / "mipmap-anydpi-v26").mkdir(exist_ok=True)
    (res / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<!-- Written by tools/make_icons.py -->\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        '</adaptive-icon>\n', encoding="utf-8")


if __name__ == "__main__":
    banner()
    icon()
    adaptive()
    print("banner and icon written under", RES)
