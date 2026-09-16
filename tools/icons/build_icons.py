#!/usr/bin/env python3
"""Generate every Android icon asset for Ping Booster from a single source logo.

Source: tools/icons/logo-source.png  (dark navy square with a mint ring + lightning bolt)
Output: app/src/main/res/... (mipmaps, adaptive icon layers, notification icon,
        in-app logo, monochrome themed-icon layer)
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ASSETS = os.path.dirname(os.path.abspath(__file__))          # tools/icons
REPO = os.path.dirname(os.path.dirname(ASSETS))              # repository root
RES = os.path.join(REPO, "app/src/main/res")
PREVIEW = os.path.join(REPO, "docs/branding")
SRC = os.path.join(ASSETS, "logo-source.png")

MINT = (79, 227, 166)      # accent used by the UI theme
NAVY = (11, 18, 32)        # surface background


def dpi_dir(name: str) -> str:
    path = os.path.join(RES, name)
    os.makedirs(path, exist_ok=True)
    return path


def load_badge() -> Image.Image:
    """Crop the dark rounded-square badge out of the artwork (drops the white margin)."""
    img = Image.open(SRC).convert("RGBA")
    rgb = np.asarray(img).astype(np.float32)[..., :3]

    # The badge is the big non-white region; everything outside it is the page margin.
    dist_white = np.sqrt(((rgb - 255.0) ** 2).mean(axis=2))
    non_white = dist_white > 40.0
    rows = np.where(non_white.any(axis=1))[0]
    cols = np.where(non_white.any(axis=0))[0]
    badge = img.crop((int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1))

    side = min(badge.size)
    badge = badge.crop((0, 0, side, side))  # keep it square
    return badge


def load_glyph(badge: Image.Image):
    """Transparent ring + bolt, extracted from the badge's flat navy background."""
    a = np.asarray(badge).astype(np.float32)
    rgb = a[..., :3]

    # The badge's flat background colour = the most common colour in the crop
    # (the rounded corners still carry page white, so a corner sample is unsafe).
    flat = (rgb // 6).astype(np.int32)
    keys = flat[..., 0] * 10000 + flat[..., 1] * 100 + flat[..., 2]
    values, counts = np.unique(keys, return_counts=True)
    top = values[counts.argmax()]
    mask = keys == top
    bg = rgb[mask].mean(axis=0)

    dist = np.sqrt(((rgb - bg) ** 2).mean(axis=2))
    alpha = np.clip((dist - 10.0) / 45.0, 0.0, 1.0)
    # Drop the faint haze around the glyph -> crisp, light-weight icon.
    alpha = np.clip((alpha - 0.10) / 0.90, 0.0, 1.0)

    out = a.copy()
    out[..., 3] = alpha * 255.0
    glyph = Image.fromarray(out.astype(np.uint8), "RGBA")
    return glyph.crop(glyph.getbbox())


def masked_tile(size: int, radius_ratio: float, circle: bool, badge=None) -> Image.Image:
    """Legacy launcher icon: artwork clipped to a launcher-friendly shape."""
    img = (badge if badge is not None else load_badge()).convert("RGBA")
    if radius_ratio == 0.0 and circle:
        side = min(img.size)
        inset = int(side * 0.012)
        img = img.crop((inset, inset, side - inset, side - inset))
    img = img.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(mask)
    if circle:
        d.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    else:
        r = int(size * 4 * radius_ratio)
        d.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=r, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    img.putalpha(mask)
    return img


def glyph_canvas(glyph: Image.Image, size: int, fill_ratio: float, color=None) -> Image.Image:
    """Centre the glyph on a transparent square canvas."""
    target = int(size * fill_ratio)
    w, h = glyph.size
    scale = target / max(w, h)
    g = glyph.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    if color is not None:
        arr = np.asarray(g).astype(np.float32)
        arr[..., 0], arr[..., 1], arr[..., 2] = color
        g = Image.fromarray(arr.astype(np.uint8), "RGBA")
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(g, ((size - g.width) // 2, (size - g.height) // 2), g)
    return canvas


def bolt_only(glyph: Image.Image) -> Image.Image:
    """Keep just the lightning bolt: the connected shape touching the centre pixel."""
    w, h = glyph.size
    alpha = glyph.split()[3]
    solid = alpha.point(lambda v: 255 if v > 110 else 0)

    filled = solid.copy()
    seed = (w // 2, h // 2)
    if filled.getpixel(seed) != 255:  # never happened with this artwork, but stay safe
        return glyph
    ImageDraw.floodfill(filled, seed, 128, thresh=0)

    keep = np.asarray(filled) == 128
    arr = np.asarray(glyph).copy()
    arr[..., 3] = np.where(keep, arr[..., 3], 0)
    bolt = Image.fromarray(arr, "RGBA")
    bolt = bolt.crop(bolt.getbbox())

    side = max(bolt.size)
    padded = int(side * 1.45)  # notification icons want content padding
    canvas = Image.new("RGBA", (padded, padded), (0, 0, 0, 0))
    canvas.paste(bolt, ((padded - bolt.width) // 2, (padded - bolt.height) // 2), bolt)
    return canvas


def save(img: Image.Image, path: str) -> None:
    img.save(path, "PNG", optimize=True)


def main() -> None:
    badge = load_badge()
    glyph = load_glyph(badge)
    bolt = bolt_only(glyph)
    mono = glyph_canvas(glyph, 432, 0.60, color=(255, 255, 255))

    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    stat = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}
    logo = {"mdpi": 96, "hdpi": 144, "xhdpi": 192, "xxhdpi": 288, "xxxhdpi": 384}

    for d, s in legacy.items():
        save(masked_tile(s, 0.22, False, badge), os.path.join(dpi_dir(f"mipmap-{d}"), "ic_launcher.png"))
        save(masked_tile(s, 0.0, True, badge), os.path.join(dpi_dir(f"mipmap-{d}"), "ic_launcher_round.png"))

    for d, s in stat.items():
        save(glyph_canvas(bolt, s, 0.92, color=(255, 255, 255)),
             os.path.join(dpi_dir(f"drawable-{d}"), "ic_stat_ping.png"))

    for d, s in logo.items():
        save(glyph_canvas(glyph, s, 1.0), os.path.join(dpi_dir(f"drawable-{d}"), "ic_logo.png"))

    # Adaptive icon layers (API 26+): safe zone is the inner 66% of the 108dp canvas.
    nodpi = dpi_dir("drawable-nodpi")
    save(glyph_canvas(glyph, 432, 0.60), os.path.join(nodpi, "ic_launcher_foreground.png"))
    save(mono, os.path.join(nodpi, "ic_launcher_monochrome.png"))

    # Store/preview renders (kept out of the APK budget).
    preview = PREVIEW
    os.makedirs(preview, exist_ok=True)
    save(masked_tile(512, 0.22, False, badge), os.path.join(preview, "icon-512.png"))
    save(masked_tile(192, 0.22, False, badge), os.path.join(preview, "icon-legacy-192.png"))
    save(glyph_canvas(glyph, 256, 1.0), os.path.join(preview, "logo-transparent-256.png"))
    save(glyph_canvas(bolt, 96, 0.92, color=(255, 255, 255)), os.path.join(preview, "ic_stat_96.png"))

    # Small-size legibility sheet (dark + light chrome).
    sheet = Image.new("RGBA", (360, 140), (0, 0, 0, 0))
    x = 10
    for s, bg in ((48, NAVY), (72, NAVY), (96, NAVY), (48, (232, 236, 241)), (72, (232, 236, 241))):
        tile = masked_tile(s, 0.22, False, badge)
        back = Image.new("RGBA", (s + 8, 140), (0, 0, 0, 0))
        ImageDraw.Draw(back).rounded_rectangle((4, (140 - s - 8) // 2, s + 4, (140 - s - 8) // 2 + s + 8),
                                              radius=8, fill=bg + (255,))
        back.paste(tile, (4, (140 - s - 8) // 2 + 4), tile)
        sheet.paste(back, (x, 0), back)
        x += s + 14
    save(sheet, os.path.join(preview, "legibility.png"))

    # Notification icon check: white glyph over a mocked dark status bar.
    bar = Image.new("RGBA", (360, 96), (12, 14, 20, 255))
    for i, (glyph_img, size, x) in enumerate((
            (glyph_canvas(bolt, 48, 0.92, color=(255, 255, 255)), 48, 12),
            (glyph_canvas(glyph, 48, 0.92, color=(255, 255, 255)), 48, 76),
            (glyph_canvas(bolt, 66, 0.92, color=(255, 255, 255)), 66, 140),
            (glyph_canvas(mono, 66, 1.0, color=(255, 255, 255)), 66, 216))):
        bar.paste(glyph_img, (x, (96 - size) // 2), glyph_img)
    save(bar, os.path.join(preview, "notification-check.png"))

    print("icons written")
    for root, _, files in os.walk(RES):
        for f in sorted(files):
            if f.endswith(".png") and ("ic_launcher" in f or "ic_stat" in f or "ic_logo" in f):
                p = os.path.join(root, f)
                print(f"  {os.path.relpath(p, REPO):60s} {os.path.getsize(p)/1024:7.1f} KB")


if __name__ == "__main__":
    main()
