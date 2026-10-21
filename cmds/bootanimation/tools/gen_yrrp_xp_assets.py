#!/usr/bin/env python3
# SPDX-FileCopyrightText: Yim's Riced ROM Project
# SPDX-License-Identifier: Apache-2.0
"""Renders the YRRP Windows XP boot animation assets.

Input: the Windows XP Professional boot capture from Wikimedia Commons,
https://commons.wikimedia.org/wiki/File:Windows_XP_Professional_boot.png
(640x480, 16-colour palette, progress bar at step 6). The artwork is
Microsoft's; the user chose on 2026-10-21 to ship it in this personal ROM.

yrrp-xp-boot.png: 640x480 RGB, the capture with the bar interior
(x 257-377, y 353-363) cleared to black.
yrrp-xp-bar.png: 22x9 RGB, the bar sprite cut from the capture at x 291, y 354.
yrrp-xp-shutdown.png: 720x1608 RGB, an approximate XP "Windows is shutting
down..." screen. No verified reference exists for it.

Font for the committed shutdown PNG: Noto Sans CJK JP Medium (face 0 of
NotoSansCJK-Medium.ttc), SIL Open Font License 1.1. The PNGs are the source of
record; rerun this only to change them.
"""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FRAME_SIZE = (640, 480)
BAR_INTERIOR = (257, 353, 377, 363)  # inclusive
SPRITE_BOX = (291, 354, 313, 363)    # PIL crop box, exclusive right/bottom
LOGO_BOX = (205, 120, 457, 302)
SHUTDOWN_SIZE = (720, 1608)
BODY = (0x5A, 0x7E, 0xDC)
BAND = (0x00, 0x30, 0x9C)
TOP_LINE = (BODY, (0xF2, 0xF6, 0xFF))
BOTTOM_LINE = (BAND, (0xF9, 0x97, 0x36))
TOP_BAND = 0.10
BOTTOM_BAND = 0.12
LINE = 2
LOGO_CENTRE_Y = 0.40
LOGO_SCALE = 0.9
TEXT = "Windows is shutting down..."


def load_capture(path):
    with Image.open(path) as image:
        if image.size != FRAME_SIZE:
            raise SystemExit(f"capture must be 640x480, got {image.size}")
        return image.convert("RGB")


def render_boot(capture):
    boot = capture.copy()
    ImageDraw.Draw(boot).rectangle(BAR_INTERIOR, fill="black")
    return boot


def render_bar(capture):
    return capture.crop(SPRITE_BOX)


def keyed_logo(capture):
    """The logo over transparency: black is clear, grey edges become white with alpha."""
    logo = capture.crop(LOGO_BOX).convert("RGB")
    rgb = logo.tobytes()
    keyed = bytearray()
    for i in range(0, len(rgb), 3):
        r, g, b = rgb[i:i + 3]
        grey = max(r, g, b) - min(r, g, b) <= 4
        keyed += bytes((255, 255, 255, max(r, g, b))) if grey else bytes((r, g, b, 255))
    return Image.frombytes("RGBA", logo.size, bytes(keyed))


def gradient_line(draw, y, width, edge, centre):
    for x in range(width):
        t = 1 - abs(x / (width - 1) * 2 - 1)  # 0 at the edges, 1 in the middle
        colour = tuple(round(e + (c - e) * t) for e, c in zip(edge, centre))
        draw.line((x, y, x, y + LINE - 1), fill=colour)


def render_shutdown(capture, font_path, font_index):
    width, height = SHUTDOWN_SIZE
    image = Image.new("RGB", SHUTDOWN_SIZE, BODY)
    draw = ImageDraw.Draw(image)
    top, bottom = round(height * TOP_BAND), round(height * BOTTOM_BAND)
    draw.rectangle((0, 0, width - 1, top - 1), fill=BAND)
    draw.rectangle((0, height - bottom, width - 1, height - 1), fill=BAND)
    gradient_line(draw, top, width, *TOP_LINE)
    gradient_line(draw, height - bottom - LINE, width, *BOTTOM_LINE)
    logo = keyed_logo(capture)
    scale = width / FRAME_SIZE[0] * LOGO_SCALE
    size = (round(logo.width * scale), round(logo.height * scale))
    logo = logo.resize(size, Image.Resampling.LANCZOS)
    centre_y = height * LOGO_CENTRE_Y
    image.paste(logo, (round((width - size[0]) / 2), round(centre_y - size[1] / 2)), logo)
    font = ImageFont.truetype(font_path, round(width * 0.05), index=font_index)
    draw.text((width / 2, centre_y + size[1] / 2 + width * 0.1), TEXT, fill="white",
              font=font, anchor="ms")
    return image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", required=True)
    parser.add_argument("--font", required=True)
    parser.add_argument("--font-index", type=int, default=0)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    capture = load_capture(args.capture)
    render_boot(capture).save(args.out / "yrrp-xp-boot.png", optimize=True)
    render_bar(capture).save(args.out / "yrrp-xp-bar.png", optimize=True)
    render_shutdown(capture, args.font, args.font_index).save(
        args.out / "yrrp-xp-shutdown.png", optimize=True)


if __name__ == "__main__":
    main()
