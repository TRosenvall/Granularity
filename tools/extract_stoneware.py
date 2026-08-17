#!/usr/bin/env python3
"""Split a vanilla stone texture into a tintable base plus N grain regions.

Run offline, never by the game. The results are PNG sprites written into the mod's assets; at
runtime Minecraft only draws them and applies a tint per layer, exactly as it does for cobblestone.

The rule these serve: a stoneware block is tinted overall by the *average* of its grains, and its
dimpling is split into one region per grain so the individual stones show through — so a furnace
built from eight grains has eight nameable dimples. N is what the recipe costs, not always nine.

**Why a luminance band rather than a threshold.** Cobblestone is nine bright stones on dark mortar,
so "brighter than X" isolates them. A furnace is not built that way: `furnace_side` is mid-tone
mottling sitting above a solid bright band, and any single threshold either merges the mottling with
the band into one 69-pixel mass or finds nothing. Selecting a *band* of luminance excludes the solid
band from above and the dark frame from below, and the mottling falls out in pieces. The band search
reproduces cobblestone's shipped nine regions exactly when pointed at cobblestone, which is the
check that it is a generalisation rather than a different algorithm.
"""
import glob
import os
import sys
import zipfile

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..")
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "granularity", "textures", "block")
SIZE = 16

# Matches extract_textures.py, so a base sprite from either tool tints identically.
BASE_MIN, BASE_MAX = 170, 255

# The mortar band and the stone band. Both tools use these; keep them in step.
#
# These used to be one band -- 170-255 for the base *and* for the stones drawn on it -- so every
# pixel of a composite rendered somewhere between 0.67 and 1.0 of its tint. Half a stop of range
# across a whole block, which tinted reads as flat: the piston came out muddy, with no visible
# difference between the recesses and the raised stone.
#
# Splitting them gives back the contrast the tint was flattening. The base keeps its ceiling and
# drops its floor, so dark parts -- mortar, and the recesses of a machined face -- go darker while
# the lit parts stay lit. The stones sit in a narrow band at the top, so a stone reads brighter than
# the mortar around it whatever colours the two happen to be tinted.
MORTAR_MIN, MORTAR_MAX = 120, 255
STONE_MIN, STONE_MAX = 195, 255

# Output name -> (vanilla texture, how many grains that block costs).
#
# Keyed by output rather than by source because the region count belongs to the *block*, not the
# texture: a dispenser and a furnace share `furnace_side`, but a dispenser costs seven grains and a
# furnace eight, so they need different splits of the same pixels.
GRAINS = {
    "furnace_side": ("furnace_side", 8),
    "furnace_top": ("furnace_top", 8),
    "dispenser_side": ("furnace_side", 7),
    "dispenser_top": ("furnace_top", 7),
    "dropper_side": ("furnace_side", 7),
    "dropper_top": ("furnace_top", 7),
    "observer_side": ("observer_side", 6),
    "observer_top": ("observer_top", 6),
    "piston_side": ("piston_side", 4),
    # Named for the face it lands on rather than the file it came from: `piston_top` is already the
    # vanilla plate on the piston's *front*, which is darkened below, and having both under one name
    # was a trap waiting to be sprung.
    "piston_back": ("piston_bottom", 4),
}

# Faces darkened rather than tinted: output name -> (factor, keep saturated pixels).
#
# Only the furnace. Its mouth cannot take a grain colour, because a tint on that face would colour
# the fire behind it -- and the fire is the one part of a furnace that should look the same whatever
# the furnace is made of. Left at vanilla brightness it reads as pasted on beside the tinted sides,
# so it is moved darker per pixel instead, with the flames themselves spared.
DARKEN = {
    "furnace_front": (0.72, True),
    "furnace_front_on": (0.72, True),
}

# Machined faces that take the block's colour like the stone around them.
#
# Greyscaled first, then gain-normalised so the brightest pixel reaches full: tinting a face that
# kept its own hue would give the product of two colours rather than the stone's, which on a piston's
# wood-coloured plate is the difference between "slate piston" and "muddy". The gain preserves
# contrast, unlike the stone base sprites' full stretch -- a dispenser's eye sockets have to stay
# dark or the face stops reading as a face.
#
# The mode says what to do with the pixels that are *not* plain surface, found by saturation:
#
#   "whole"  -- there are none worth separating; the face is one sprite, `_tint`.
#   "lights" -- they are an indicator, not a material. Lifted into `_lit` and drawn over the top with
#               no tint at all, so an observer's back light stays red on a slate observer.
#   "wood"   -- the face is timber rather than stone, and takes the colour of the planks the block
#               was built from instead of the colour of its grains. One sprite, like "whole"; the
#               mode exists to say *which* tint the face wants, not to split it.
#   "timber" -- timber *and* metal on one face. A piston's head is a wooden ram held together with
#               metal: brackets at the corners of the plate, and corners and mid-braces along the
#               band around its edge. Saturation separates them cleanly -- the wood is coloured, the
#               fittings are grey -- into `_wood` and `_metal`, each then taking the colour of what
#               the piston was actually built out of.
#   "timber_band" -- the same, but over the top four rows only. On `piston_side` those four rows are
#               the head's band and everything below them is the piston's stone body, which is
#               already split into grains by GRAINS above. Splitting the whole sprite would drag two
#               hundred pixels of body in as "metal".
#   "framed" -- stone with timber fittings sitting on it. The timber is found by saturation exactly
#               as "timber" does, but what remains is *stone* rather than metal: it goes to `_stone`
#               and takes the block's own stone colour. `stonecutter_top` is this -- a stone surface
#               with a wooden bracket at each corner.
#   "bench"  -- the same, and then the stone is split in two by the timber that divides it.
#               `stonecutter_side` is a wooden frame with a full-width rail across rows 9-10; above
#               that rail is one stone section, below it another, and the whole point of a
#               stonecutter is that those two are built from *different* stones and show it. Writes
#               `_upper`, `_lower` and `_wood`.
#
# The stonecutter carries no dimpling at all, which is what makes all of the above enough for it: it
# is built from smooth stone rather than from chunks, and smooth is precisely the finish in which a
# block has stopped showing its nine grains separately. Every face of it is therefore a machined face
# by the definition above, and wants the gain -- which keeps the frame and the slot dark -- rather
# than the stone stretch, which would flatten them into the surface.
#
# Its saw is deliberately absent from this table. `stonecutter_saw` is 16x48 -- three animation frames
# with an .mcmeta beside them -- and everything here assumes 16x16, so putting it through the tintable
# path would need a strip-aware copy of the mcmeta or the blade would stop turning. It needs none of
# that: vanilla's sprite is already pure greyscale (chroma 0 on every pixel), which is exactly what a
# tintable sprite is, so the model points at `minecraft:block/stonecutter_saw` and lets METAL_TINT
# colour it.
#
# `write_still` below does take one frame out of it, for the opposite purpose: a blade jammed with moss
# has to *stop*, and the way to stop an animation is to draw the quad from a sprite nobody is
# rewriting. See StoppedBladeModel.
TINTABLE = {
    "stonecutter_bottom": "whole",
    "stonecutter_top": "framed",
    "stonecutter_side": "bench",
    "dispenser_front": "whole",
    "dispenser_front_vertical": "whole",
    "dropper_front": "whole",
    "dropper_front_vertical": "whole",
    "observer_front": "whole",
    "observer_back": "lights",
    "observer_back_on": "lights",
    "piston_top": "timber",
    "piston_side": "timber_band",
    "piston_inner": "whole",
}

# How deep the piston's head is, in pixels: the band it wears on a side sprite.
HEAD_BAND = 4

# The rows of `stonecutter_side` occupied by the wooden rail that divides the bench, as [lo, hi).
#
# Read off the sprite, not guessed: rows 9 and 10 are the only two that are timber all the way
# across. Everything above is the upper stone section and everything below the lower one, which is
# the division the two-stone recipe is built on.
BENCH_RAIL = (9, 11)

# Above this saturation a pixel is a light rather than a surface, for the faces that ask to keep one.
FIRE_CHROMA = 40


def find_client_jar():
    if len(sys.argv) > 1:
        return sys.argv[1]
    candidates = glob.glob(os.path.join(ROOT, "build/jars/extra/client/*/client-extra.jar"))
    candidates += glob.glob(os.path.expanduser("~/.gradle/caches/ng_execute/*/client-extra.jar"))
    if not candidates:
        raise SystemExit("client-extra.jar not found; run a Gradle task first, or pass the path")
    return candidates[0]


def luminance(pixel):
    return 0.2126 * pixel[0] + 0.7152 * pixel[1] + 0.0722 * pixel[2]


def components(mask):
    """Connected regions, four-connected and wrapping.

    Four rather than eight on purpose: stones that touch only at a corner are different stones, and
    eight-connectivity merges most of a texture into one blob.
    """
    seen = [[False] * SIZE for _ in range(SIZE)]
    found = []
    for y in range(SIZE):
        for x in range(SIZE):
            if not mask[y][x] or seen[y][x]:
                continue
            stack, blob = [(x, y)], []
            seen[y][x] = True
            while stack:
                cx, cy = stack.pop()
                blob.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = (cx + dx) % SIZE, (cy + dy) % SIZE
                    if mask[ny][nx] and not seen[ny][nx]:
                        seen[ny][nx] = True
                        stack.append((nx, ny))
            found.append(blob)
    return found


def best_band(lum, count):
    """The luminance band yielding `count` regions that are as large and as even as possible.

    Coverage is weighted above evenness (the square root damps the balance term): a region too small
    to see cannot show a player which grain it is, and that matters more than the regions matching
    each other.
    """
    levels = sorted({round(v, 3) for row in lum for v in row})
    best = None
    for i, lo in enumerate(levels):
        for hi in levels[i:]:
            mask = [[lo <= lum[y][x] <= hi for x in range(SIZE)] for y in range(SIZE)]
            blobs = [b for b in components(mask) if len(b) >= 2]
            if len(blobs) < count:
                continue
            blobs.sort(key=len, reverse=True)
            top = blobs[:count]
            sizes = [len(b) for b in top]
            score = sum(sizes) * (min(sizes) / max(sizes)) ** 0.5
            if best is None or score > best[0]:
                best = (score, top, sizes, lo, hi)
    return best


def write_base(name, image):
    """The whole face in grey, for the averaged tint to colour."""
    px = image.load()
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    op = out.load()
    lums = [luminance(px[x, y]) for y in range(SIZE) for x in range(SIZE)]
    lo, hi = min(lums), max(lums)
    span = max(1e-6, hi - lo)
    for y in range(SIZE):
        for x in range(SIZE):
            pixel = px[x, y]
            t = (luminance(pixel) - lo) / span
            v = int(round(MORTAR_MIN + t * (MORTAR_MAX - MORTAR_MIN)))
            op[x, y] = (v, v, v, pixel[3])
    out.save(os.path.join(TEX, f"{name}_base.png"))


def write_shapes(name, image, blobs):
    px = image.load()
    lums = [luminance(px[x, y]) for y in range(SIZE) for x in range(SIZE)]
    lo, hi = min(lums), max(lums)
    span = max(1e-6, hi - lo)
    for index, blob in enumerate(blobs, start=1):
        out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        op = out.load()
        for x, y in blob:
            t = (luminance(px[x, y]) - lo) / span
            v = int(round(STONE_MIN + t * (STONE_MAX - STONE_MIN)))
            op[x, y] = (v, v, v, 255)
        out.save(os.path.join(TEX, f"{name}_shape_{index}.png"))


def chroma(pixel):
    return max(pixel[0], pixel[1], pixel[2]) - min(pixel[0], pixel[1], pixel[2])


def write_darkened(name, image, factor, keep_lights):
    """Every pixel moved darker, optionally sparing the ones that are meant to glow.

    A flat tint would drag the flames toward whatever stone the furnace is made of, which is wrong
    twice over: fire is not stone, and it is the one part of the block that should look the same on
    every furnace. Scaling each pixel instead keeps the mouth's own shading.

    `keep_lights` is off by default because saturation alone does not mean "light". A piston's plate
    is a strongly coloured material and sparing it would leave a bright plate on a dark piston --
    exactly the pasted-on look this function exists to remove.
    """
    px = image.load()
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    op = out.load()
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = px[x, y]
            if keep_lights and chroma(px[x, y]) > FIRE_CHROMA:
                op[x, y] = (r, g, b, a)
            else:
                op[x, y] = (int(r * factor), int(g * factor), int(b * factor), a)
    out.save(os.path.join(TEX, f"{name}_dark.png"))


def write_greyed(name, image, pixels, suffix):
    """One region of a face, greyscaled and gain-normalised, ready to be tinted.

    Greyscale so that the tint is the only colour in the result -- tinting a face that kept its own
    hue gives the product of two colours, which turned a wooden plate muddy rather than woody.

    A single gain rather than the stone sprites' full stretch: those lift their darks to 170 because
    a stone base must not black out its mortar, whereas a muzzle's dark recesses are the drawing and
    flattening them would erase the face. Each region gets its own gain, so a plate and the frame
    around it both land bright and their tints are free to differ.
    """
    px = image.load()
    lums = [luminance(px[x, y]) for x, y in pixels if px[x, y][3] > 0]
    gain = 255.0 / max(1.0, max(lums, default=255.0))
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    op = out.load()
    for x, y in pixels:
        v = min(255, int(round(luminance(px[x, y]) * gain)))
        op[x, y] = (v, v, v, px[x, y][3])
    out.save(os.path.join(TEX, f"{name}{suffix}.png"))
    return gain


def write_tintable(name, image, mode):
    """Splits a machined face into the regions that want different colours, per {@code TINTABLE}."""
    px = image.load()
    everything = [(x, y) for y in range(SIZE) for x in range(SIZE)]
    saturated = [p for p in everything if chroma(px[p[0], p[1]]) > FIRE_CHROMA]
    plain = [p for p in everything if p not in set(saturated)]

    if mode in ("timber", "timber_band"):
        if mode == "timber_band":
            # Exactly the rows `template_piston_head` samples, uv [0, 0, 16, 4].
            saturated = [p for p in saturated if p[1] < HEAD_BAND]
            plain = [p for p in plain if p[1] < HEAD_BAND]
        wood = write_greyed(name, image, saturated, "_wood")
        metal = write_greyed(name, image, plain, "_metal")
        return (f"{len(saturated)} px of wood at gain {wood:.2f}, "
                f"{len(plain)} px of metal at gain {metal:.2f}")

    if mode == "framed":
        wood = write_greyed(name, image, saturated, "_wood")
        stone = write_greyed(name, image, plain, "_stone")
        return (f"{len(saturated)} px of wood at gain {wood:.2f}, "
                f"{len(plain)} px of stone at gain {stone:.2f}")

    if mode == "bench":
        lo, hi = BENCH_RAIL
        # The rail is timber all the way across, so nothing plain falls inside it; the two tests are
        # a partition rather than a filter, and a stray plain pixel in the rail would be a sign the
        # sprite had changed under us.
        upper = [p for p in plain if p[1] < lo and px[p[0], p[1]][3] > 0]
        lower = [p for p in plain if p[1] >= hi and px[p[0], p[1]][3] > 0]
        stranded = [p for p in plain if lo <= p[1] < hi and px[p[0], p[1]][3] > 0]
        wood = write_greyed(name, image, saturated, "_wood")
        write_greyed(name, image, upper, "_upper")
        write_greyed(name, image, lower, "_lower")
        return (f"{len(saturated)} px of wood at gain {wood:.2f}, "
                f"{len(upper)} px above the rail, {len(lower)} px below"
                + (f" -- WARNING {len(stranded)} stone px inside the rail" if stranded else ""))

    if mode in ("whole", "wood"):
        return f"gain {write_greyed(name, image, everything, '_tint'):.2f}"

    if mode == "lights":
        gain = write_greyed(name, image, plain, "_tint")
        # The light keeps vanilla's own pixels: it is not a surface and takes no tint.
        glow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        gp = glow.load()
        for x, y in saturated:
            gp[x, y] = px[x, y]
        glow.save(os.path.join(TEX, f"{name}_lit.png"))
        return f"gain {gain:.2f}, {len(saturated)} lit px split off"


def write_still(zf, source, name):
    """The first frame of an animation strip, on its own, as a sprite that never moves.

    A stonecutter whose blade is packed with moss has stopped, and it should look stopped -- a jammed
    saw that goes on spinning is the sort of thing that reads as a bug rather than as a mechanic. The
    blade's sprite is vanilla's own `stonecutter_saw`, 16x48 and animated, so stopping it means drawing
    the quad from a *different* sprite: an animation lives in one atlas slot whose pixels are rewritten
    each tick, and a still copy is simply a slot nobody rewrites.

    Frame 0 rather than a blend of the three, because the frames are the same blade at three rotations
    and averaging them would smear it.
    """
    with zf.open(f"assets/minecraft/textures/block/{source}.png") as handle:
        strip = Image.open(handle).convert("RGBA").copy()
    still = strip.crop((0, 0, SIZE, SIZE))
    still.save(os.path.join(TEX, f"{name}.png"))
    return strip.size


def main():
    jar = find_client_jar()
    with zipfile.ZipFile(jar) as zf:
        for name, (source, count) in GRAINS.items():
            with zf.open(f"assets/minecraft/textures/block/{source}.png") as handle:
                image = Image.open(handle).convert("RGBA").copy()
            px = image.load()
            lum = [[luminance(px[x, y]) for x in range(SIZE)] for y in range(SIZE)]
            found = best_band(lum, count)
            if found is None:
                print(f"{name}: no band yields {count} regions -- left alone")
                continue
            _, blobs, sizes, lo, hi = found
            write_base(name, image)
            write_shapes(name, image, blobs)
            print(f"{name:16s} <- {source:16s} {count} regions, band {lo:5.0f}..{hi:5.0f}, "
                  f"px {str(sizes):34s} covering {sum(sizes):3d}/{SIZE * SIZE}")

        for name, mode in TINTABLE.items():
            with zf.open(f"assets/minecraft/textures/block/{name}.png") as handle:
                image = Image.open(handle).convert("RGBA").copy()
            print(f"{name:26s} tintable, {write_tintable(name, image, mode)}")

        size = write_still(zf, "stonecutter_saw", "stonecutter_saw_still")
        print(f"{'stonecutter_saw_still':26s} frame 0 of {size[0]}x{size[1]}, for a jammed blade")

        for name, (factor, keep_lights) in DARKEN.items():
            with zf.open(f"assets/minecraft/textures/block/{name}.png") as handle:
                image = Image.open(handle).convert("RGBA").copy()
            px = image.load()
            lights = sum(1 for y in range(SIZE) for x in range(SIZE)
                         if keep_lights and chroma(px[x, y]) > FIRE_CHROMA)
            write_darkened(name, image, factor, keep_lights)
            print(f"{name:26s} darkened to {factor:.0%}, {lights} lit px left untouched")


if __name__ == "__main__":
    main()
