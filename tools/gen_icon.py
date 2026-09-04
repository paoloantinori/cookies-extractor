#!/usr/bin/env python3
"""Regenerate the ten legacy launcher PNGs under app/src/main/res/mipmap-*.

Usage: python3 tools/gen_icon.py

Same design as res/drawable/ic_launcher_foreground.xml: a tan cookie with a
bite (cookie disc minus an overlapping bite disc) and dark chocolate chips,
on the warm dark background of res/values/colors.xml. The square variant is
full-bleed; the round variant clips the background to a disc.

Prefers Pillow when importable; otherwise falls back to a stdlib PNG writer
(zlib + struct only). Either path is deterministic: no timestamps, no randomness.
"""

import struct
import zlib
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

BACKGROUND = (0x2E, 0x1D, 0x12, 0xFF)
COOKIE = (0xE0, 0xA4, 0x5E, 0xFF)
CHIP = (0x4A, 0x2A, 0x10, 0xFF)

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

# Fractions of the tile edge. The vector cookie is r=30 on a 108dp canvas;
# legacy tiles have no safe-zone margin, so the cookie is drawn larger
# (r=0.32*S) with bite and chips at the same ratios to the cookie radius as
# the vector (bite centre 26/30 per axis, bite r 14/30, y grows downward).
COOKIE_R = 0.32
BITE_OFF = 0.8667 * COOKIE_R
BITE_R = 0.4667 * COOKIE_R
CHIPS = (  # (dx, dy, radius) relative to the tile centre, fractions of the edge
    (-0.1067, -0.1173, 0.0341),
    (0.0960, -0.0533, 0.0299),
    (-0.0853, 0.1067, 0.0320),
    (0.0747, 0.1600, 0.0277),
    (-0.0107, 0.0107, 0.0256),
)


def render_pillow(size, round_bg):
    from PIL import Image, ImageDraw

    ss = 4
    big = size * ss
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0) if round_bg else BACKGROUND)
    if round_bg:
        ImageDraw.Draw(img).ellipse((0, 0, big - 1, big - 1), fill=BACKGROUND)

    cookie = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(cookie)
    c = big / 2
    r1 = COOKIE_R * big
    d.ellipse((c - r1, c - r1, c + r1, c + r1), fill=255)
    bx, by = c + BITE_OFF * big, c - BITE_OFF * big
    rb = BITE_R * big
    d.ellipse((bx - rb, by - rb, bx + rb, by + rb), fill=0)
    img.paste(COOKIE, mask=cookie)

    d = ImageDraw.Draw(img)
    for dx, dy, r in CHIPS:
        x, y, rr = c + dx * big, c + dy * big, r * big
        d.ellipse((x - rr, y - rr, x + rr, y + rr), fill=CHIP)

    return img.resize((size, size), Image.LANCZOS)


def render_stdlib(size, round_bg):
    ss = 4
    half = 0.5
    cells = [(half + i / ss, half + j / ss) for i in range(ss) for j in range(ss)]

    def classify(x, y):
        if round_bg and (x - half) ** 2 + (y - half) ** 2 > half * half:
            return None
        for dx, dy, r in CHIPS:
            px, py = x - half - dx, y - half - dy
            if px * px + py * py < r * r:
                return CHIP
        cdx, cdy = x - half, y - half
        if cdx * cdx + cdy * cdy < COOKIE_R * COOKIE_R:
            bdx, bdy = x - half - BITE_OFF, y - half + BITE_OFF
            if bdx * bdx + bdy * bdy > BITE_R * BITE_R:
                return COOKIE
        return BACKGROUND

    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            tallies = {}
            for sx, sy in cells:
                color = classify((px + sx) / size, (py + sy) / size)
                if color is None:
                    continue
                tallies[color] = tallies.get(color, 0) + 1
            total = sum(tallies.values())
            if total == 0:
                row += bytes(4)
                continue
            r = sum(c[0] * n for c, n in tallies.items()) // total
            g = sum(c[1] * n for c, n in tallies.items()) // total
            b = sum(c[2] * n for c, n in tallies.items()) // total
            a = round(0xFF * total / (ss * ss))
            row += bytes((r, g, b, a))
        rows.append(bytes(row))
    return rows


def png_bytes_pillow(img):
    import io

    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=False)
    return buf.getvalue()


def png_bytes_stdlib(rows):
    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", len(rows[0]) // 4, len(rows), 8, 6, 0, 0, 0)
    raw = b"".join(b"\x00" + row for row in rows)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def main():
    try:
        import PIL  # noqa: F401

        backend = "pillow"
    except ImportError:
        backend = "stdlib"

    written = []
    for dpi, size in DENSITIES.items():
        for name, round_bg in (("ic_launcher.png", False), ("ic_launcher_round.png", True)):
            out = RES / f"mipmap-{dpi}" / name
            out.parent.mkdir(parents=True, exist_ok=True)
            if backend == "pillow":
                data = png_bytes_pillow(render_pillow(size, round_bg))
            else:
                data = png_bytes_stdlib(render_stdlib(size, round_bg))
            out.write_bytes(data)
            written.append(f"{out.relative_to(RES.parent.parent)} {size}x{size} {len(data)}B")

    print(f"backend: {backend}")
    print("\n".join(written))


if __name__ == "__main__":
    main()
