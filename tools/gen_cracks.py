#!/usr/bin/env python3
"""Generate the crack overlay, once: a fissure that wanders, widens and spalls chips.

Run offline, never by the game. Writes one sprite; the game draws it and nothing more.

**Three attempts got here, and the wrong turns are worth keeping.**

*Worley cell boundaries* came first. That draws a **mosaic** -- every cell walled off from its
neighbours at even spacing -- which is what dried mud does, or crazed glaze. Not what a struck block
does, and no amount of thinning or softening was going to fix a shape that was wrong at the root.

*A radial impact star* came next, from Timothy's photographs of a quarried block before and after. The
shape was defensible -- the crack is applied by hitting the face, so a strike point is honest -- but at
sixteen pixels it read as a glyph stamped on the stone rather than as damage.

*What vanilla actually does* settled it, and only after Timothy pushed back on the claim that its
cracks follow the mortar lines. They do not. Subtract `deepslate_bricks` from
`cracked_deepslate_bricks` (see `tools/subtract_textures.py`) and there is no fissure in the result at
any threshold -- because vanilla did not overlay a crack, it **redrew the brick edges**. What that diff
does show is the character the first two attempts lacked: cracks that are **irregular in width**,
widening into broken patches rather than running one pixel across for their whole length, with **chips**
spalled off beside them. That is most of what looks like "noise" in the diff, and it is most of what
makes a crack read as a crack.

**Two rendering rules this has to obey**, both learned the hard way.

*Alpha does not fade anything.* Block models draw on `cutout_mipped`, whose shader is

    if (color.a < 0.5) { discard; }

so alpha is a **binary** test at 128, never a blend. A sprite at a third opacity is not a faint crack;
it is no crack at all, every pixel discarded -- which is exactly what happened. Every pixel here is
fully opaque, and lightness comes from the grey value instead.

*And the colour cannot be fixed.* An untinted crack must be darker than whatever it cracks, and no one
colour is darker than both chalk and deepslate. So the overlay is registered **tinted**
(`Overlay.tinted`) and this sprite is greyscale: a tint multiplies, so 128 comes out as that stone at
half brightness, whatever stone it is. The whole mod already worked this way -- greyscale art, colour
from the composition -- and cracks are the first overlay to use it.
"""
import math
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..")
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "granularity", "textures", "block")
SIZE = 16

# Fixed, so the sprite is reproducible: rerunning must not quietly change what players have built with.
SEED = 20260943

# How far each half of the fissure runs, in steps of 0.8 px. It is drawn out from the middle in *both*
# directions -- see below for why that matters.
ARM = 11

# How far it turns per step, in radians. A crack that does not wander is a scratch.
WANDER = 0.55

# How often the crack widens sideways. This is the property vanilla has and the first two attempts
# lacked: a fracture is not one pixel across for its whole run.
WIDEN = 0.6

# How often a chip spalls off beside the crack. Lighter than the fissure itself -- these are shallow.
CHIP = 0.35

# Shorter fractures hung off the main one. Most of what makes the damage read as bad rather than tidy.
BRANCHES = 4

# How far the strike may sit from the middle of the face.
SPREAD = 1.0

# The fissure, as a multiplier: 128 is the stone at half brightness. Chips and shoulders are
# shallower, so they take less off.
CORE = 128
WIDEN_GREYS = (150, 170)
CHIP_GREYS = (170, 190)


def main():
    rng = random.Random(SEED)
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = image.load()

    def put(x, y, grey):
        # Wraps, so a crack running off one edge continues onto the next block rather than stopping
        # dead at the seam.
        px[int(x) % SIZE, int(y) % SIZE] = (grey, grey, max(0, grey - 2), 255)

    def run(x, y, angle, steps):
        """One fracture, wandering as it goes, widening and shedding chips."""
        travelled = []
        for _ in range(steps):
            angle += rng.uniform(-WANDER, WANDER)
            x += math.cos(angle) * 0.8
            y += math.sin(angle) * 0.8
            put(x, y, CORE)
            travelled.append((x, y, angle))
            if rng.random() < WIDEN:
                # Square to the direction of travel, so the crack thickens rather than smears along
                # itself.
                put(x + math.cos(angle + math.pi / 2), y + math.sin(angle + math.pi / 2),
                    rng.choice(WIDEN_GREYS))
            if rng.random() < CHIP:
                put(x + rng.uniform(-2, 2), y + rng.uniform(-2, 2), rng.choice(CHIP_GREYS))
        return travelled

    # Out from the middle in **both** directions, rather than from a random edge. The damage should
    # gather where the hammer landed, and an earlier attempt that pulled the walk inward instead
    # curled it into a blob -- a spiral, not a fracture. Running the fissure through the centre puts
    # the weight in the middle while every stroke still travels outward.
    cx = SIZE / 2 + rng.uniform(-SPREAD, SPREAD)
    cy = SIZE / 2 + rng.uniform(-SPREAD, SPREAD)
    heading = rng.uniform(0, math.tau)
    spine = run(cx, cy, heading, ARM) + run(cx, cy, heading + math.pi, ARM)

    for _ in range(BRANCHES):
        bx, by, bangle = spine[rng.randrange(len(spine))]
        run(bx, by, bangle + rng.choice([-1, 1]) * rng.uniform(0.6, 1.3), max(4, ARM // 2))

    # Crossed with a quarter-turn of itself. One fissure and its branches is a *split*; two running
    # across each other is a block that has been *hit*, which is the verb here. It costs nothing and
    # doubles the damage without doubling the drawing, and because the sprite already wraps, a
    # quarter-turn of it wraps too -- so cracks still line up across block edges either way round.
    #
    # The darker of the two wins at every pixel, so where they cross the fissure stays a fissure
    # rather than being lightened by a chip lying underneath it.
    turned = image.rotate(90)
    tp = turned.load()
    for j in range(SIZE):
        for i in range(SIZE):
            other = tp[i, j]
            if other[3] == 0:
                continue
            here = px[i, j]
            if here[3] == 0 or other[0] < here[0]:
                px[i, j] = other

    image.save(os.path.join(TEX, "cobblestone_cracked.png"))
    drawn = sum(1 for j in range(SIZE) for i in range(SIZE) if px[i, j][3] > 0)
    print(f"cobblestone_cracked.png: {drawn}/{SIZE * SIZE} px — a {2 * ARM}-step fissure with "
          f"{BRANCHES} branches, crossed with a quarter-turn of itself, seed {SEED}")
    print(f"  greyscale multiplier — core {CORE} is the stone at {round(CORE / 255 * 100)}% brightness")
    print("  every pixel opaque, because cutout_mipped discards anything under a = 0.5")


if __name__ == "__main__":
    main()
