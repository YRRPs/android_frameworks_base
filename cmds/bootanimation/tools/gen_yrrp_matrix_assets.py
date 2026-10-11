#!/usr/bin/env python3
# SPDX-FileCopyrightText: Yim's Riced ROM Project
# SPDX-License-Identifier: Apache-2.0
"""Renders the YRRP Matrix boot animation assets.

yrrp-matrix-atlas.png: 512x512 RGB, 8x8 cells of 64 px, white mirrored glyphs on
black: half-width katakana U+FF66-U+FF9D, then the digits 01234578.
yrrp-matrix-logo.png: 1024x300 RGB, "YRRP" in white on black.

Fonts used for the committed PNGs: Noto Sans CJK JP Regular (face 0 of
NotoSansCJK-Regular.ttc) and IBM Plex Mono Bold, both under the SIL Open Font
License 1.1. The PNGs are the source of record; rerun this only to change them.
"""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

CELL = 64
GRID = 8
GLYPHS = [chr(cp) for cp in range(0xFF66, 0xFF9E)] + list("01234578")
LOGO_SIZE = (1024, 300)
LOGO_TEXT = "YRRP"
assert len(GLYPHS) == GRID * GRID


def load_kana_font(path, index):
    return ImageFont.truetype(path, 50, index=index)


def render_cell(font, glyph, mirror=True):
    cell = Image.new("RGB", (CELL, CELL), "black")
    ImageDraw.Draw(cell).text((CELL / 2, CELL / 2), glyph, fill="white", font=font, anchor="mm")
    return ImageOps.mirror(cell) if mirror else cell


def render_atlas(font):
    atlas = Image.new("RGB", (CELL * GRID, CELL * GRID), "black")
    for index, glyph in enumerate(GLYPHS):
        atlas.paste(render_cell(font, glyph), ((index % GRID) * CELL, (index // GRID) * CELL))
    return atlas


def render_logo(font_path):
    width, height = LOGO_SIZE
    size = 290
    font = ImageFont.truetype(font_path, size)
    while font.getlength(LOGO_TEXT) > width - 20:
        size -= 2
        font = ImageFont.truetype(font_path, size)
    logo = Image.new("RGB", LOGO_SIZE, "black")
    draw = ImageDraw.Draw(logo)
    left, top, right, bottom = draw.textbbox((0, 0), LOGO_TEXT, font=font)
    origin = ((width - (right - left)) / 2 - left, (height - (bottom - top)) / 2 - top)
    draw.text(origin, LOGO_TEXT, fill="white", font=font)
    return logo


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kana-font", required=True)
    parser.add_argument("--kana-index", type=int, default=0)
    parser.add_argument("--logo-font", required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    render_atlas(load_kana_font(args.kana_font, args.kana_index)).save(
        args.out / "yrrp-matrix-atlas.png", optimize=True)
    render_logo(args.logo_font).save(args.out / "yrrp-matrix-logo.png", optimize=True)


if __name__ == "__main__":
    main()
