#!/usr/bin/env python3
"""
Imports the supplied Daysemble logo files into the Android project.

The mapping is EXPLICIT (never inferred from file names or order):
    --classic   supplied logo #1 (cream tile, light)   -> default
    --midnight  supplied logo #2 (black tile, filled)
    --outline   supplied logo #3 (black tile, outlined)
    --wordmark  horizontal "Daysemble." wordmark (optional)

What it does. The artwork itself is never recoloured or redrawn:
  1. Leaves the originals untouched (keep them in brand/).
  2. Finds the rounded tile in each image and makes only the pixels OUTSIDE the tile
     transparent (the supplied files sit on a black square background).
  3. In-app logos: the cut-out tile, resized to --app-size px (default 512),
     saved as res/drawable-nodpi/logo_<variant>.png.
  4. Launcher icons: the artwork from the tile's centre (a soft-edged circle that drops the tile's
     own border and drop shadow), scaled to --launcher-scale of the 108dp adaptive canvas (default
     0.63, so the outer dots stay inside the 66dp safe zone of round masks), on the tile's own
     sampled background colour, for every density.
  4b. Themed-icon monochrome layer: a white silhouette of the same artwork shapes (Android 13+
     launchers tint it), written as ic_launcher_mono_<variant> and referenced from the XML.
  5. Wordmark: trimmed of surrounding empty margin, resized to --wordmark-width px.
  6. Flips <bool name="logo_*_supplied"> flags to true.

Requires Pillow:  pip install pillow
"""
import argparse
import pathlib
import re
import shutil
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
BRAND = ROOT / "brand"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
ADAPTIVE_DP = 108


def cut_out_tile(img: Image.Image, tolerance: int) -> Image.Image:
    """Returns the tile cropped to its bounds with everything outside the tile transparent."""
    rgba = img.convert("RGBA")
    w, h = rgba.size
    alpha = rgba.getchannel("A")
    if alpha.getextrema()[0] < 250 and alpha.getpixel((0, 0)) < 16:
        mask = alpha.point(lambda a: 255 if a > 16 else 0)
    else:
        # Opaque file: the outside is the (near-)uniform colour at the corners. Mark pixels that
        # differ from it, then flood-fill the outside from all four corners.
        rgb = rgba.convert("RGB")
        corner = rgb.getpixel((2, 2))
        diff = ImageChops.difference(rgb, Image.new("RGB", (w, h), corner)).convert("L")
        mask = diff.point(lambda d: 255 if d > tolerance else 0)
        outside = mask.copy()
        for xy in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
            if outside.getpixel(xy) == 0:
                ImageDraw.floodfill(outside, xy, 128)
        mask = outside.point(lambda v: 0 if v == 128 else 255)
        # Soften the cut edge by one pixel to keep the tile's own anti-aliasing smooth.
        mask = mask.filter(ImageFilter.GaussianBlur(0.8))
    box = mask.point(lambda v: 255 if v > 32 else 0).getbbox()
    if box is None:
        sys.exit("Could not detect the tile")
    out = rgba.copy()
    out.putalpha(ImageChops.multiply(alpha, mask))
    return out.crop(box)


def tile_colour(tile: Image.Image) -> str:
    """Tile background colour sampled inside its left edge at mid-height (clear of edge shading)."""
    px = tile.convert("RGB").getpixel((int(tile.width * 0.12), tile.height // 2))
    return "#FF%02X%02X%02X" % px


def monochrome(square: Image.Image, bg_hex: str, lo: int, hi: int) -> Image.Image:
    """
    Silhouette of the artwork for Android 13+ themed icons: every pixel that differs from the tile's
    background becomes opaque white (the launcher tints it), with a soft ramp between [lo, hi] colour
    distance so anti-aliased edges stay smooth. Shapes are kept exactly; only colour is dropped.
    """
    rgb = square.convert("RGB")
    bg = tuple(int(bg_hex[i:i + 2], 16) for i in (3, 5, 7))
    diff = ImageChops.difference(rgb, Image.new("RGB", rgb.size, bg))
    r, g, b = diff.split()
    dist = ImageChops.lighter(ImageChops.lighter(r, g), b)
    span = max(1, hi - lo)
    alpha = dist.point(lambda d: 0 if d <= lo else 255 if d >= hi else int((d - lo) * 255 / span))
    alpha = ImageChops.multiply(alpha, square.getchannel("A"))
    out = Image.new("RGBA", rgb.size, (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def centre_disc(square: Image.Image, radius: float, feather: float) -> Image.Image:
    """Keeps a soft-edged disc of the artwork (radius/feather as fractions of the side)."""
    s = square.width
    big = 4  # supersample the mask for a smooth edge
    m = Image.new("L", (s * big, s * big), 0)
    r = radius * s * big
    c = s * big / 2
    ImageDraw.Draw(m).ellipse((c - r, c - r, c + r, c + r), fill=255)
    m = m.resize((s, s), Image.LANCZOS).filter(ImageFilter.GaussianBlur(feather * s))
    out = square.copy()
    out.putalpha(ImageChops.multiply(square.getchannel("A"), m))
    return out


def resized(im: Image.Image, size: int) -> Image.Image:
    return im.resize((size, size), Image.LANCZOS)


def square_tile(src: pathlib.Path, tolerance: int) -> Image.Image:
    tile = cut_out_tile(Image.open(src), tolerance)
    side = max(tile.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(tile, ((side - tile.width) // 2, (side - tile.height) // 2))
    return square


def import_variant(name: str, src: pathlib.Path, a, mono_src: pathlib.Path | None = None):
    square = square_tile(src, a.tolerance)

    # In-app artwork
    (RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
    resized(square, a.app_size).save(RES / "drawable-nodpi" / f"logo_{name}.png", optimize=True)
    (RES / "drawable" / f"logo_{name}.xml").unlink(missing_ok=True)

    # Launcher foregrounds: centre disc of the artwork (no tile border/shadow), safe-zone sized.
    bg = tile_colour(square)
    disc = centre_disc(square, a.disc_radius, 0.01)
    (RES / "drawable" / f"ic_launcher_fg_{name}.xml").unlink(missing_ok=True)
    # The silhouette needs artwork that separates cleanly from its background. Classic's cream glass
    # square and soft shadows sit too close to its cream tile, so it uses the same composition from
    # the Midnight artwork when that's supplied.
    mono_square = square_tile(mono_src, a.tolerance) if mono_src else square
    mono_bg = tile_colour(mono_square) if mono_src else bg
    # Tighter, harder-edged disc: the silhouette must not pick up the faint tile border near the edge.
    mono_disc = centre_disc(monochrome(mono_square, mono_bg, a.mono_low, a.mono_high), min(a.disc_radius, a.mono_radius), 0.003)
    for density, scale in DENSITIES.items():
        canvas = round(ADAPTIVE_DP * scale)
        inner = round(canvas * a.launcher_scale)
        off = ((canvas - inner) // 2, (canvas - inner) // 2)
        mono = Image.new("RGBA", (canvas, canvas), (255, 255, 255, 0))
        mono.alpha_composite(resized(mono_disc, inner), off)
        mono_out = RES / f"mipmap-{density}" / f"ic_launcher_mono_{name}.png"
        mono_out.parent.mkdir(parents=True, exist_ok=True)
        mono.save(mono_out, optimize=True)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        art = resized(disc, inner)
        fg.alpha_composite(art, off)
        out = RES / f"mipmap-{density}" / f"ic_launcher_fg_{name}.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        fg.save(out, optimize=True)

    (RES / "mipmap-anydpi" / f"ic_launcher_{name}.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated by scripts/import_logos.py from the supplied artwork. -->\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        f'    <background android:drawable="@color/launcher_bg_{name}" />\n'
        f'    <foreground android:drawable="@mipmap/ic_launcher_fg_{name}" />\n'
        '    <!-- Themed icons (Android 13+): silhouette of the same artwork, tinted by the launcher. -->\n'
        f'    <monochrome android:drawable="@mipmap/ic_launcher_mono_{name}" />\n'
        '</adaptive-icon>\n'
    )
    set_colour(f"launcher_bg_{name}", bg)
    set_flag(f"logo_{name}_supplied")
    print(f"  {name}: tile {square.size}, background {bg}" + (f", silhouette from {mono_src.name}" if mono_src else ""))


def import_wordmark(src: pathlib.Path, width: int):
    im = Image.open(src).convert("RGBA")
    # Trim the empty margin (same colour as the corner) around the mark, keeping a small pad.
    bg = Image.new("RGBA", im.size, im.getpixel((2, 2)))
    box = ImageChops.difference(im, bg).convert("L").point(lambda d: 255 if d > 12 else 0).getbbox() or (0, 0, *im.size)
    pad = int(0.04 * im.height)
    box = (max(0, box[0] - pad), max(0, box[1] - pad), min(im.width, box[2] + pad), min(im.height, box[3] + pad))
    im = im.crop(box)
    h = round(im.height * width / im.width)
    (RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
    im.resize((width, h), Image.LANCZOS).save(RES / "drawable-nodpi" / "wordmark.png", optimize=True)
    (RES / "drawable" / "wordmark.xml").unlink(missing_ok=True)
    set_flag("wordmark_supplied")
    print(f"  wordmark: {width}x{h}")


def set_flag(flag: str):
    p = RES / "values" / "brand_assets.xml"
    p.write_text(re.sub(rf'(<bool name="{flag}">)false(</bool>)', r"\1true\2", p.read_text()))


def set_colour(name: str, value: str):
    p = RES / "values" / "launcher_colors.xml"
    s = p.read_text()
    s = re.sub(rf'\s*<color name="{name}">[^<]*</color>', "", s)
    p.write_text(s.replace("</resources>", f'    <color name="{name}">{value}</color>\n</resources>'))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--classic", type=pathlib.Path, help="supplied logo #1 (default)")
    ap.add_argument("--midnight", type=pathlib.Path, help="supplied logo #2")
    ap.add_argument("--outline", type=pathlib.Path, help="supplied logo #3")
    ap.add_argument("--wordmark", type=pathlib.Path)
    ap.add_argument("--app-size", type=int, default=512)
    ap.add_argument("--launcher-scale", type=float, default=0.63,
                    help="tile size as a fraction of the 108dp canvas (0.63 keeps the artwork in the 66dp safe zone)")
    ap.add_argument("--disc-radius", type=float, default=0.49,
                    help="radius of the kept artwork disc, as a fraction of the tile side")
    ap.add_argument("--mono-low", type=int, default=28, help="colour distance below which a pixel is background (themed icon)")
    ap.add_argument("--mono-high", type=int, default=72, help="colour distance above which a pixel is fully artwork (themed icon)")
    ap.add_argument("--mono-radius", type=float, default=0.482,
                    help="disc radius for the themed-icon silhouette (excludes the tile border, which starts near 0.487)")
    ap.add_argument("--wordmark-width", type=int, default=900)
    ap.add_argument("--tolerance", type=int, default=6, help="colour distance separating tile from outer background")
    a = ap.parse_args()
    if not any([a.classic, a.midnight, a.outline, a.wordmark]):
        ap.error("provide at least one asset")
    for name in ("classic", "midnight", "outline"):
        if getattr(a, name):
            import_variant(name, getattr(a, name), a, mono_src=a.midnight if name == "classic" else None)
    if a.wordmark:
        import_wordmark(a.wordmark, a.wordmark_width)
    if a.classic:
        (RES / "drawable" / "splash_icon.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<inset xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:drawable="@mipmap/ic_launcher_fg_classic" android:inset="0dp" />\n'
        )
    print("Done. Review brand/ and the generated icons, then build.")


if __name__ == "__main__":
    main()
