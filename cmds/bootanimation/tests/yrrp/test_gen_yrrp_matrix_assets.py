"""Checks the YRRP Matrix asset generator against the layout the shader expects.

Run: KANA_FONT=... LOGO_FONT=... python3 -m unittest test_gen_yrrp_matrix_assets
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageChops

HERE = Path(__file__).resolve().parent
GENERATOR = HERE.parent.parent / "tools" / "gen_yrrp_matrix_assets.py"
COMMITTED = HERE.parents[3] / "core" / "res" / "assets" / "images"
KANA_FONT = os.environ.get("KANA_FONT", "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc")
LOGO_FONT = os.environ.get("LOGO_FONT", "")


@unittest.skipUnless(Path(KANA_FONT).is_file() and Path(LOGO_FONT).is_file(), "fonts missing")
class GeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.out = Path(tempfile.mkdtemp())
        subprocess.run(
            [sys.executable, "-I", str(GENERATOR), "--kana-font", KANA_FONT,
             "--kana-index", "0", "--logo-font", LOGO_FONT, "--out", str(cls.out)],
            check=True)
        cls.atlas = Image.open(cls.out / "yrrp-matrix-atlas.png")
        cls.logo = Image.open(cls.out / "yrrp-matrix-logo.png")

    def test_atlas_is_rgb_8x8_grid_of_64px_cells(self):
        self.assertEqual("RGB", self.atlas.mode)
        self.assertEqual((512, 512), self.atlas.size)

    def test_every_cell_holds_a_glyph_inside_its_cell(self):
        for index in range(64):
            x, y = (index % 8) * 64, (index // 8) * 64
            cell = self.atlas.crop((x, y, x + 64, y + 64)).convert("L")
            box = cell.point(lambda v: 255 if v > 128 else 0).getbbox()
            self.assertIsNotNone(box, f"cell {index} is empty")
            self.assertGreater(box[0], 0, f"cell {index} touches the left edge")
            self.assertLess(box[2], 64, f"cell {index} touches the right edge")
            # LINEAR filtering would bleed an edge-touching row into the next cell row.
            self.assertGreater(box[1], 0, f"cell {index} touches the top edge")
            self.assertLess(box[3], 64, f"cell {index} touches the bottom edge")

    def test_glyphs_are_mirrored(self):
        first = self.atlas.crop((0, 0, 64, 64)).convert("L")
        sys.path.insert(0, str(GENERATOR.parent))
        import gen_yrrp_matrix_assets as gen
        plain = gen.render_cell(gen.load_kana_font(KANA_FONT, 0), gen.GLYPHS[0], mirror=False)
        self.assertIsNotNone(ImageChops.difference(first, plain.convert("L")).getbbox())

    def test_logo_is_rgb_and_centered(self):
        self.assertEqual("RGB", self.logo.mode)
        self.assertEqual((1024, 300), self.logo.size)
        left, top, right, bottom = self.logo.convert("L").point(
            lambda v: 255 if v > 128 else 0).getbbox()
        self.assertLess(abs((left + right) / 2 - 512), 24)
        self.assertLess(abs((top + bottom) / 2 - 150), 24)

    def test_committed_assets_match_the_layout(self):
        for name, size in (("yrrp-matrix-atlas.png", (512, 512)),
                           ("yrrp-matrix-logo.png", (1024, 300))):
            image = Image.open(COMMITTED / name)
            self.assertEqual(("RGB", size), (image.mode, image.size), name)


if __name__ == "__main__":
    unittest.main()
