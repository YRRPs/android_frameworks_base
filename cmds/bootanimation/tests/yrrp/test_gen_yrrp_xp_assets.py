# SPDX-FileCopyrightText: Yim's Riced ROM Project
# SPDX-License-Identifier: Apache-2.0
"""Checks the YRRP XP assets against the geometry the renderer expects.

Run: XP_CAPTURE=... XP_FONT=... python3 -m unittest test_gen_yrrp_xp_assets
"""

import sys

sys.dont_write_bytecode = True  # keep __pycache__ out of the source tree

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageChops

from test_gen_yrrp_matrix_assets import FORBIDDEN_CHUNKS, png_chunk_types

HERE = Path(__file__).resolve().parent
GENERATOR = HERE.parent.parent / "tools" / "gen_yrrp_xp_assets.py"
COMMITTED = HERE.parents[3] / "core" / "res" / "assets" / "images"
XP_CAPTURE = os.environ.get("XP_CAPTURE", "")
XP_FONT = os.environ.get("XP_FONT", "/usr/share/fonts/noto-cjk/NotoSansCJK-Medium.ttc")
NAMES = {"yrrp-xp-boot.png": (640, 480), "yrrp-xp-bar.png": (22, 9),
         "yrrp-xp-shutdown.png": (720, 1608)}
PALETTE = [(0, 0, 0), (64, 64, 64), (168, 48, 7), (232, 103, 31), (231, 176, 24),
           (176, 176, 176), (79, 95, 0), (128, 136, 0), (135, 208, 0), (40, 56, 199),
           (88, 120, 239), (135, 159, 240), (119, 119, 119), (239, 239, 239),
           (240, 240, 240), (255, 255, 255)]
SPRITE = ["9999990099999900999999", "aaaaaa00aaaaaa00aaaaaa", "bbbbbb00bbbbbb00bbbbbb",
          "bbbbbb00bbbbbb00bbbbbb", "aaaaaa00aaaaaa00bbbbbb", "99999900aaaaaa00aaaaaa",
          "9999990099999900aaaaaa", "9999990099999900999999", "9999990099999900999999"]


def load(name):
    with Image.open(COMMITTED / name) as image:
        return image.copy()


class CommittedAssetsTest(unittest.TestCase):
    """Always runs: AImageDecoder needs plain 8-bit RGB PNGs."""

    def test_assets_are_plain_rgb_at_their_sizes(self):
        for name, size in NAMES.items():
            data = (COMMITTED / name).read_bytes()
            self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8], name)
            self.assertEqual(b"\x08\x02", data[24:26], f"{name} is not 8-bit RGB")
            self.assertFalse(FORBIDDEN_CHUNKS & set(png_chunk_types(data)), name)
            image = load(name)
            self.assertEqual(("RGB", size), (image.mode, image.size), name)

    def test_boot_frame_uses_only_the_xp_palette(self):
        colours = {colour for _, colour in load("yrrp-xp-boot.png").getcolors(4096)}
        self.assertLessEqual(colours, set(PALETTE))

    def test_boot_frame_keeps_the_bar_frame_and_clears_its_interior(self):
        boot = load("yrrp-xp-boot.png")
        self.assertEqual((176, 176, 176), boot.getpixel((255, 358)))
        self.assertEqual((64, 64, 64), boot.getpixel((256, 358)))
        for y in range(353, 364):
            for x in range(257, 378):
                self.assertEqual((0, 0, 0), boot.getpixel((x, y)), (x, y))

    def test_boot_frame_holds_the_logo(self):
        orange = sum(n for n, c in load("yrrp-xp-boot.png").getcolors(4096) if c == PALETTE[3])
        self.assertGreater(orange, 1000)

    def test_bar_is_the_professional_sprite(self):
        bar = load("yrrp-xp-bar.png")
        for y, row in enumerate(SPRITE):
            for x, index in enumerate(row):
                self.assertEqual(PALETTE[int(index, 16)], bar.getpixel((x, y)), (x, y))

    def test_shutdown_bands_and_body(self):
        shutdown = load("yrrp-xp-shutdown.png")
        self.assertEqual((0x00, 0x30, 0x9C), shutdown.getpixel((0, 0)))
        self.assertEqual((0x5A, 0x7E, 0xDC), shutdown.getpixel((0, 800)))
        self.assertEqual((0x00, 0x30, 0x9C), shutdown.getpixel((0, 1607)))
        top_line = shutdown.getpixel((360, 161))
        for actual, expected in zip(top_line, (0xF2, 0xF6, 0xFF)):
            self.assertLessEqual(abs(actual - expected), 2)


@unittest.skipUnless(Path(XP_CAPTURE).is_file() and Path(XP_FONT).is_file(),
                     "capture or font missing")
class GeneratorTest(unittest.TestCase):
    def test_generator_reproduces_the_committed_assets(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run([sys.executable, "-I", str(GENERATOR), "--capture", XP_CAPTURE,
                            "--font", XP_FONT, "--out", tmp], check=True)
            for name in NAMES:
                with Image.open(Path(tmp) / name) as fresh:
                    diff = ImageChops.difference(fresh.convert("RGB"), load(name)).getbbox()
                self.assertIsNone(diff, name)


if __name__ == "__main__":
    unittest.main()
