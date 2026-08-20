#!/usr/bin/env python3
"""Generate the transmogrification screen's background and its empty-slot outline.

Run offline, never by the game.

**Why generated rather than drawn.** A vanilla container background is not art, it is a diagram --
flat fills and one-pixel bevels on an exact 18-pixel grid. Drawing it by hand means measuring, and
measuring by hand means the slots end up a pixel out from where the menu puts its hit boxes, which is
the kind of wrongness nobody sees but everybody feels. Here the same constants place the pixels and the
slots: SLOT_PITCH and the row coordinates below are the ones BrushMenu uses, so the picture cannot
drift from the thing it is a picture of.

**What it is.** Vanilla's chest layout with the chest taken out: the player's three rows and hotbar at
the bottom exactly where every other container puts them, and one lone slot centred above them for the
costume. Same width, same borders, same colours, so it reads as a normal container.

**And the outline.** The empty costume slot shows a faint cube, so the slot says what it wants before
you have put anything in it. It is drawn here for the same reason as the panel -- it has to sit on the
slot's grid -- and it is a block atlas sprite rather than a GUI file because that is where Minecraft
reads slot backgrounds from.
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..")
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "granularity")
GUI = os.path.join(ASSETS, "textures", "gui")
ITEM = os.path.join(ASSETS, "textures", "item")

# A GUI texture is addressed as a region of a 256x256 sheet, whatever it actually uses.
SHEET = 256

# Vanilla's container width, and a height matching a three-row chest -- so the blank area above the
# inventory is the size players already expect a small container to be.
WIDTH, HEIGHT = 176, 166

# Must match TransmogMenu. The inventory's three rows, then the hotbar.
#
# The costume slots are NOT drawn here. There are between one and four of them depending on what the
# block is made of, so the screen blits them at run time -- reusing a slot already on this sheet, which
# is why there is no separate slot sprite to keep in step with this one.
SLOT_PITCH = 18
INVENTORY_X = 8
INVENTORY_Y = 84
HOTBAR_Y = 142

# Vanilla's container palette, sampled from its own sheets so ours sits beside them without a seam.
PANEL = (198, 198, 198)
PANEL_LIGHT = (255, 255, 255)
PANEL_DARK = (85, 85, 85)
SLOT = (139, 139, 139)
SLOT_DARK = (55, 55, 55)
SLOT_LIGHT = (255, 255, 255)


def bevel(px, x, y, w, h, fill, light, dark):
    """A vanilla-style raised or sunken rectangle: fill, a lit edge, and a shaded one.

    Two pixels of border and no anti-aliasing, because everything else in the GUI is drawn that way
    and a softened edge would read as blurry rather than as polished.
    """
    for j in range(h):
        for i in range(w):
            px[x + i, y + j] = (*fill, 255)
    for i in range(w):
        px[x + i, y] = (*light, 255)
        px[x + i, y + h - 1] = (*dark, 255)
    for j in range(h):
        px[x, y + j] = (*light, 255)
        px[x + w - 1, y + j] = (*dark, 255)


def main():
    os.makedirs(GUI, exist_ok=True)
    os.makedirs(ITEM, exist_ok=True)
    image = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    px = image.load()

    # The panel itself, raised.
    bevel(px, 0, 0, WIDTH, HEIGHT, PANEL, PANEL_LIGHT, PANEL_DARK)

    # Every slot is sunken -- light on the bottom and right rather than the top and left, which is
    # what makes a hole read as a hole next to a raised panel.
    def slot(x, y):
        bevel(px, x, y, SLOT_PITCH, SLOT_PITCH, SLOT, SLOT_DARK, SLOT_LIGHT)

    for row in range(3):
        for col in range(9):
            slot(INVENTORY_X + col * SLOT_PITCH, INVENTORY_Y + row * SLOT_PITCH)
    for col in range(9):
        slot(INVENTORY_X + col * SLOT_PITCH, HOTBAR_Y)

    image.save(os.path.join(GUI, "transmog_panel.png"))
    print(f"transmog_panel.png: {WIDTH}x{HEIGHT} on a {SHEET}x{SHEET} sheet")
    print(f"  36 slots — three rows at y={INVENTORY_Y}, hotbar at y={HOTBAR_Y}, pitch {SLOT_PITCH}")
    ghost()


def ghost():
    """The outline shown in an empty costume slot.

    An outline and nothing else -- the hexagon a cube makes seen from a corner, plus the three edges
    that meet in the middle, in light grey with the faces left fully transparent. Filled faces were
    tried first and read as a dark smudge sitting in the slot rather than as a space waiting for a
    block, which is the opposite of what an empty slot should say.

    Alpha is real here. A slot background goes through the GUI blitter, not the cutout block shader,
    so unlike every overlay texture in this mod it can be genuinely faint instead of dithering for it.
    """
    icon = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(icon)

    # The six corners of the silhouette, and the centre where the three visible edges meet.
    top, upper_l, upper_r = (8, 1), (1, 5), (15, 5)
    lower_l, lower_r, bottom = (1, 11), (15, 11), (8, 15)
    middle = (8, 8)

    line = (176, 176, 176, 210)
    inner = (176, 176, 176, 150)

    draw.line([top, upper_r, lower_r, bottom, lower_l, upper_l, top], fill=line)
    # The Y in the middle: without it the shape is a hexagon rather than a cube.
    for corner in (upper_l, upper_r, bottom):
        draw.line([middle, corner], fill=inner)

    icon.save(os.path.join(ITEM, "empty_transmog_slot.png"))
    print("empty_transmog_slot.png: 16x16 light grey outline, hollow centre")

    # A slot that wants an ingot must not show a cube. Same weight and colour as the block outline so
    # the two read as one family rather than as two different kinds of thing.
    # A slot that wants a grain -- a chunk, an ore, an ingot -- must not show a cube. An irregular
    # lump reads as "a piece of something" the way a trapezoid ingot would not, and the grain slots
    # take ingots and rubble alike. Same weight and colour as the block outline so the two read as one
    # family rather than as two different kinds of thing.
    grain = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    pen = ImageDraw.Draw(grain)
    pen.line([(5, 4), (10, 3), (13, 7), (12, 12), (6, 13), (3, 9), (5, 4)], fill=line)
    pen.line([(7, 7), (9, 6)], fill=inner)
    grain.save(os.path.join(ITEM, "empty_transmog_slot_grain.png"))
    print("empty_transmog_slot_grain.png: 16x16 lump outline")


if __name__ == "__main__":
    main()
