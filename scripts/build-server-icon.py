"""Draws the 64 x 64 server-icon.png that Minecraft shows in the multiplayer list (the server sends it with its status
reply, and the pack's servers.dat carries the same bytes so the entry has it before the first ping). Same look as the
site's favicon (designs-26.3/website/build-site-images.py): dark panel, copper ring, ivory S.
Writes pack-26.3-private/server-icon.png and copies it into pack-26.3-private/server-prod/ (the staged server)."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import shutil

PORT = Path(__file__).resolve().parents[1]
PACK = PORT / "pack-26.3-private"
BOLD = "C:/Windows/Fonts/trebucbd.ttf"
PANEL_TOP, PANEL_BOTTOM = (36, 50, 42), (22, 30, 26)
COPPER, COPPER_DARK, IVORY = (203, 144, 96), (120, 80, 48), (238, 231, 207)


def build():
    scale = 8                                            # draw big, then shrink: smooth edges at 64 px
    size = 64 * scale
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    radius = 11 * scale
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=PANEL_BOTTOM)
    for y in range(size):                                # vertical shade inside the panel
        t = y / (size - 1)
        colour = tuple(round(a + (b - a) * t) for a, b in zip(PANEL_TOP, PANEL_BOTTOM))
        draw.line([(0, y), (size, y)], fill=colour + (255,))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    image.putalpha(mask)
    draw = ImageDraw.Draw(image)
    for x in range(0, size, 8 * scale):                  # faint blueprint grid, like the site card
        draw.line([(x, 0), (x, size)], fill=(44, 58, 50, 255))
        draw.line([(0, x), (size, x)], fill=(44, 58, 50, 255))
    draw.rounded_rectangle([2 * scale, 2 * scale, size - 2 * scale - 1, size - 2 * scale - 1], radius=radius - 2 * scale,
                           outline=COPPER_DARK, width=5 * scale)
    draw.rounded_rectangle([3 * scale, 3 * scale, size - 3 * scale - 1, size - 3 * scale - 1], radius=radius - 3 * scale,
                           outline=COPPER, width=3 * scale)
    font = ImageFont.truetype(BOLD, 50 * scale)
    box = draw.textbbox((0, 0), "S", font=font)
    x = (size - (box[2] - box[0])) / 2 - box[0]
    y = (size - (box[3] - box[1])) / 2 - box[1]
    draw.text((x + 2 * scale, y + 2 * scale), "S", font=font, fill=(0, 0, 0, 110))     # soft shadow
    draw.text((x, y), "S", font=font, fill=IVORY)
    small = image.resize((64, 64), Image.LANCZOS)
    out = PACK / "server-icon.png"
    small.save(out, optimize=True)
    shutil.copyfile(out, PACK / "server-prod" / "server-icon.png")
    print(out, out.stat().st_size, "bytes", small.size)


if __name__ == "__main__":
    build()
