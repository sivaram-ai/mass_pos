"""Cuts the GO RockIt rocket out of the company logo and writes the Windows icon.

The rocket is the part of `frontend/public/gds-logo.webp` the sign-in screen shows beside the
product name (`ROCKET` in Login.tsx); the wordmark under it turns to mud at 16x16, which is the
size that matters in a taskbar. Run after changing the logo:

    python tools/make-icon.py

It writes tools/gorockit.ico, which jpackage puts on both launchers, and the same rocket as
PNGs in frontend/public/, which is where the taskbar icon of the window the shortcut opens comes
from: the shell runs Edge with --app=, and such a window takes its icon from the page, not from
the launcher that started it. Needs Pillow.
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
LOGO = ROOT / "frontend" / "public" / "gds-logo.webp"
ICON = ROOT / "tools" / "gorockit.ico"
WEB = ROOT / "frontend" / "public"

# Where the rocket sits in the 2000x2000 logo. Keep in step with ROCKET in Login.tsx.
CROP = (574, 293, 574 + 853, 293 + 979)
# A little air around it, so the icon is not jammed against the edges at any size.
MARGIN = 0.06
# Everything Windows asks for, from the taskbar to the large tiles in Explorer.
SIZES = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]
# What the browser asks for: the .ico for the tab, the PNGs the web manifest names for the window.
WEB_SIZES = [192, 512]


def main() -> None:
    logo = Image.open(LOGO).convert("RGBA")
    rocket = logo.crop(CROP)

    side = round(max(rocket.width, rocket.height) * (1 + 2 * MARGIN))
    square = Image.new("RGBA", (side, side), (255, 255, 255, 255))
    square.paste(rocket, ((side - rocket.width) // 2, (side - rocket.height) // 2), rocket)

    square.save(ICON, format="ICO", sizes=[(s, s) for s in SIZES])
    print(f"{ICON.relative_to(ROOT)}: {ICON.stat().st_size} bytes, {len(SIZES)} sizes up to {SIZES[-1]}px")

    # The same square the launchers get, for the page the shortcut opens.
    (WEB / ICON.name).write_bytes(ICON.read_bytes())
    for size in WEB_SIZES:
        out = WEB / f"gorockit-{size}.png"
        square.resize((size, size), Image.LANCZOS).save(out, format="PNG")
        print(f"{out.relative_to(ROOT)}: {out.stat().st_size} bytes")


if __name__ == "__main__":
    main()
