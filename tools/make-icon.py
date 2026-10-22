"""Cuts the GO RockIt rocket out of the company logo and writes the Windows icon.

The rocket is the part of `frontend/public/gds-logo.webp` the sign-in screen shows beside the
product name (`ROCKET` in Login.tsx); the wordmark under it turns to mud at 16x16, which is the
size that matters in a taskbar. Run after changing the logo:

    python tools/make-icon.py

It writes tools/gorockit.ico, which jpackage puts on both launchers. Needs Pillow.
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
LOGO = ROOT / "frontend" / "public" / "gds-logo.webp"
ICON = ROOT / "tools" / "gorockit.ico"

# Where the rocket sits in the 2000x2000 logo. Keep in step with ROCKET in Login.tsx.
CROP = (574, 293, 574 + 853, 293 + 979)
# A little air around it, so the icon is not jammed against the edges at any size.
MARGIN = 0.06
# Everything Windows asks for, from the taskbar to the large tiles in Explorer.
SIZES = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]


def main() -> None:
    logo = Image.open(LOGO).convert("RGBA")
    rocket = logo.crop(CROP)

    side = round(max(rocket.width, rocket.height) * (1 + 2 * MARGIN))
    square = Image.new("RGBA", (side, side), (255, 255, 255, 255))
    square.paste(rocket, ((side - rocket.width) // 2, (side - rocket.height) // 2), rocket)

    square.save(ICON, format="ICO", sizes=[(s, s) for s in SIZES])
    print(f"{ICON.relative_to(ROOT)}: {ICON.stat().st_size} bytes, {len(SIZES)} sizes up to {SIZES[-1]}px")


if __name__ == "__main__":
    main()
