from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
import json

ROOT = Path(__file__).resolve().parents[1]
IOS = ROOT / "ios" / "LingoPlay" / "Assets.xcassets"
ANDROID = ROOT / "android" / "app" / "src" / "main" / "res"
BRAND = ROOT / "assets" / "brand"

DARK = (5, 7, 22)
PURPLE = (171, 72, 255)
BLUE = (86, 105, 255)
CYAN = (28, 217, 255)
WHITE = (248, 250, 255)


def lerp(a: int, b: int, t: float) -> int:
    return int(round(a + (b - a) * t))


def mix(c1, c2, t: float):
    return tuple(lerp(a, b, t) for a, b in zip(c1, c2))


def master_icon(size: int = 1024, transparent_corners: bool = False) -> Image.Image:
    scale = size / 1024.0
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0) if transparent_corners else DARK + (255,))

    bg = Image.new("RGBA", (size, size), DARK + (255,))
    bd = ImageDraw.Draw(bg)
    # Fast purple -> blue -> cyan diagonal-like gradient using vertical bands.
    for x in range(size):
        t = x / max(1, size - 1)
        edge = mix(PURPLE, CYAN, t)
        base = mix(edge, DARK, 0.58)
        bd.line((x, 0, x, size), fill=base + (255,))
    # Dark center and neon edge glows keep the mark readable at launcher sizes.
    vignette = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    vd = ImageDraw.Draw(vignette)
    vd.ellipse((int(-0.20*size), int(-0.15*size), int(0.72*size), int(0.80*size)), fill=PURPLE + (90,))
    vd.ellipse((int(0.32*size), int(0.18*size), int(1.20*size), int(1.08*size)), fill=CYAN + (72,))
    vignette = vignette.filter(ImageFilter.GaussianBlur(radius=max(1, int(0.20*size))))
    bg.alpha_composite(vignette)

    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    radius = int(218 * scale)
    md.rounded_rectangle((int(54*scale), int(54*scale), int(970*scale), int(970*scale)), radius=radius, fill=255)
    if transparent_corners:
        img.alpha_composite(Image.composite(bg, Image.new("RGBA", bg.size, (0, 0, 0, 0)), mask))
    else:
        img = Image.composite(bg, img, mask)

    # Neon glows.
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    box = (int(138*scale), int(138*scale), int(886*scale), int(886*scale))
    gd.rounded_rectangle(box, radius=int(205*scale), outline=PURPLE + (180,), width=max(1, int(24*scale)))
    gd.arc(box, start=300, end=110, fill=CYAN + (230,), width=max(1, int(28*scale)))
    gd.arc(box, start=110, end=300, fill=PURPLE + (220,), width=max(1, int(24*scale)))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=max(1, int(28*scale))))
    img.alpha_composite(glow)

    draw = ImageDraw.Draw(img)
    # Main rounded glass panel.
    panel = (int(118*scale), int(118*scale), int(906*scale), int(906*scale))
    draw.rounded_rectangle(panel, radius=int(210*scale), fill=(8, 10, 35, 208), outline=(218, 224, 255, 150), width=max(1, int(5*scale)))
    draw.arc(panel, start=300, end=115, fill=CYAN + (255,), width=max(1, int(12*scale)))
    draw.arc(panel, start=115, end=300, fill=PURPLE + (255,), width=max(1, int(12*scale)))

    # Speech ring and tail.
    ring = (int(240*scale), int(242*scale), int(782*scale), int(780*scale))
    draw.ellipse(ring, outline=(225, 235, 255, 245), width=max(1, int(32*scale)))
    draw.arc(ring, start=300, end=100, fill=CYAN + (255,), width=max(1, int(35*scale)))
    draw.arc(ring, start=100, end=300, fill=(203, 139, 255, 255), width=max(1, int(35*scale)))
    tail = [(int(284*scale), int(684*scale)), (int(230*scale), int(824*scale)), (int(390*scale), int(754*scale))]
    draw.polygon(tail, fill=(205, 145, 255, 255))

    # Play triangle.
    tri = [(int(456*scale), int(398*scale)), (int(456*scale), int(626*scale)), (int(650*scale), int(512*scale))]
    draw.polygon(tri, fill=WHITE + (255,))

    # Waveform bars.
    bars_left = [(286, 455, 22, 112), (330, 423, 25, 176), (377, 453, 22, 116)]
    bars_right = [(716, 455, 22, 112), (667, 423, 25, 176), (623, 453, 22, 116)]
    for x, y, w, h in bars_left:
        draw.rounded_rectangle((int(x*scale), int(y*scale), int((x+w)*scale), int((y+h)*scale)), radius=int(10*scale), fill=(211, 130, 255, 255))
    for x, y, w, h in bars_right:
        draw.rounded_rectangle((int(x*scale), int(y*scale), int((x+w)*scale), int((y+h)*scale)), radius=int(10*scale), fill=CYAN + (255,))

    # Small sparkle.
    cx, cy = int(772*scale), int(255*scale)
    r1, r2 = int(28*scale), int(9*scale)
    sparkle = [(cx, cy-r1), (cx+r2, cy-r2), (cx+r1, cy), (cx+r2, cy+r2), (cx, cy+r1), (cx-r2, cy+r2), (cx-r1, cy), (cx-r2, cy-r2)]
    draw.polygon(sparkle, fill=(196, 247, 255, 255))
    return img


def save_resized(master: Image.Image, path: Path, size: int, rgb: bool = False):
    path.parent.mkdir(parents=True, exist_ok=True)
    out = master.resize((size, size), Image.Resampling.LANCZOS)
    if rgb:
        bg = Image.new("RGB", out.size, DARK)
        if out.mode == "RGBA":
            bg.paste(out, mask=out.getchannel("A"))
        else:
            bg.paste(out)
        out = bg
    out.save(path, optimize=True)


def write_ios_assets(master: Image.Image, mark: Image.Image):
    appset = IOS / "AppIcon.appiconset"
    appset.mkdir(parents=True, exist_ok=True)
    entries = [
        ("iphone", "20x20", "2x", 40), ("iphone", "20x20", "3x", 60),
        ("iphone", "29x29", "2x", 58), ("iphone", "29x29", "3x", 87),
        ("iphone", "40x40", "2x", 80), ("iphone", "40x40", "3x", 120),
        ("iphone", "60x60", "2x", 120), ("iphone", "60x60", "3x", 180),
        ("ipad", "20x20", "1x", 20), ("ipad", "20x20", "2x", 40),
        ("ipad", "29x29", "1x", 29), ("ipad", "29x29", "2x", 58),
        ("ipad", "40x40", "1x", 40), ("ipad", "40x40", "2x", 80),
        ("ipad", "76x76", "1x", 76), ("ipad", "76x76", "2x", 152),
        ("ipad", "83.5x83.5", "2x", 167),
        ("ios-marketing", "1024x1024", "1x", 1024),
    ]
    images = []
    for idiom, points, scale_name, px in entries:
        filename = f"AppIcon-{idiom}-{points.replace('.', '_')}-{scale_name}.png"
        save_resized(master, appset / filename, px, rgb=True)
        images.append({"idiom": idiom, "size": points, "scale": scale_name, "filename": filename})
    (appset / "Contents.json").write_text(json.dumps({"images": images, "info": {"author": "xcode", "version": 1}}, indent=2), encoding="utf-8")

    imageset = IOS / "LingoPlayMark.imageset"
    imageset.mkdir(parents=True, exist_ok=True)
    mark_entries = []
    for scale_name, px in [("1x", 192), ("2x", 384), ("3x", 576)]:
        filename = f"LingoPlayMark-{scale_name}.png"
        save_resized(mark, imageset / filename, px)
        mark_entries.append({"idiom": "universal", "scale": scale_name, "filename": filename})
    (imageset / "Contents.json").write_text(json.dumps({"images": mark_entries, "info": {"author": "xcode", "version": 1}}, indent=2), encoding="utf-8")
    IOS.mkdir(parents=True, exist_ok=True)
    (IOS / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2), encoding="utf-8")


def write_android_assets(master: Image.Image, mark: Image.Image):
    # Android minSdk is 26, so launcher identity is provided by the adaptive
    # mipmap XML; no legacy bitmap launcher fallback is required.
    save_resized(mark, ANDROID / "drawable-nodpi" / "lingoplay_mark.png", 512)


def main():
    BRAND.mkdir(parents=True, exist_ok=True)
    master = master_icon(1024, transparent_corners=False)
    mark = master_icon(1024, transparent_corners=True)
    save_resized(master, BRAND / "LingoPlayIcon-1024.png", 1024, rgb=True)
    save_resized(mark, BRAND / "LingoPlayMark-1024.png", 1024)
    write_ios_assets(master, mark)
    write_android_assets(master, mark)
    print("Generated LingoPlay brand assets for iOS + Android")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        import traceback
        error_path = Path(r"D:\LacViet\Android\brandgen-python-error.log")
        error_path.write_text(traceback.format_exc(), encoding="utf-8")
        raise
