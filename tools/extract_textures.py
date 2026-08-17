#!/usr/bin/env python3
"""Derives Granularity's block sprites from vanilla's own textures.

The idea (Timothy's): don't draw custom art for every stone make-up. Take vanilla's greyscale stone
as the one and only base, take the *chromatic* part of vanilla's ore textures as overlays, and let
tint supply all the colour. Stone then reads as one continuous, uniform texture across the whole
world, and only its colour changes with the fields.

  base            vanilla stone.png, already pure greyscale, tinted with the AVERAGE rock colour
  ore             iron_ore.png    minus its grey  -> the pinkish specks
  precious ore    copper_ore.png  minus its grey
  gem             diamond_ore.png minus its grey
  (redstone is deliberately left alone -- reserved for something else)

HOW THE GREY IS SUBTRACTED. Not by differencing against stone.png: the ore textures carry their own
rearranged grey background, so a pixel-wise diff flags 55-60% of the image, nearly all of it grey
noise rather than ore. What actually separates them is *saturation*. Vanilla's stone matrix is
exactly neutral (r == g == b) and the ore specks are not, so chroma alone extracts the specks
cleanly -- 63 pixels of iron, 50 of copper, 57 of diamond.

LARGER AND SMALLER AMOUNTS. One sprite per slot count. Below vanilla's own density the specks are
revealed one at a time, smallest first, so a faint step is a scatter of traces; above it the blobs
dilate outward along a signed distance field. The shapes stay vanilla's; only how much of them
there is changes. Distances wrap at the edges, because block textures tile.

Coverage is fitted so that three slots of nine reproduces vanilla's own ore density almost exactly
-- a "normal" ore block looks like the ore block players know -- rising to full coverage at nine.

Run:  python3 tools/extract_textures.py
"""

import glob
import json
import math
import os
import random
import sys
import zipfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "granularity")
TEX = os.path.join(ASSETS, "textures", "block")
MODELS = os.path.join(ASSETS, "models", "block")

SIZE = 16
SLOTS = 9

# A pixel counts as ore if its channels disagree by more than this. Vanilla's stone matrix is
# exactly neutral, so anything above a couple of units is deliberate colour.
CHROMA_THRESHOLD = 8

# Extracted specks are renormalised into this luminance band. Tint multiplies, so the band sets how
# bright a tinted speck can get; too dark and no tint can rescue it.
SPECK_MIN, SPECK_MAX = 130, 255

# Vanilla stone sits at 116-143, i.e. about half brightness. Tint multiplies, so leaving it there
# would halve every colour on top of whatever the palette already is -- red stone would come out
# nearly black. The pattern is stretched into a brighter band instead, which is a linear remap:
# vanilla's texture exactly, with headroom for the multiply.
BASE_MIN, BASE_MAX = 170, 255

# The mortar band and the stone band -- see extract_stoneware.py, which shares them, for why one band
# for both was making composites look flat. Natural stone, gravel and the item sprites keep BASE_MIN
# above: natural stone's base is a whole visible surface rather than mortar showing between stones,
# and gravel's nine regions cover every pixel, so neither has the contrast problem this fixes.
MORTAR_MIN, MORTAR_MAX = 120, 255
STONE_MIN, STONE_MAX = 195, 255

# Exponent fitted so coverage(3/9) lands on vanilla's own ore density (~0.246).
ORE_GAMMA = 1.3

# Layer order and how far each is pushed out of the block face, in sixteenths. 0.05 here is 0.003
# blocks -- enough to order the layers in the depth buffer, far too little to see.
#
# There is no rock overlay. A second rock colour used to be drawn as a masked patch of the same
# stone sprite; averaging the base tint replaces it, and gives a gradient across a region boundary
# where the patch gave a mottle. See CompositionLayers.
# Each mineral class gets TWO overlay slots so a block can show two different colours of the same
# class -- iron and copper in the same rock. Variant "b" reveals its specks in the opposite order
# (largest first) so the two do not nest inside each other: at low counts they occupy different
# specks, which is what makes two colours read as two minerals rather than one recoloured twice.
#
# The smaller of the two is drawn on top, at the larger outset.
# One shared offset, not a ladder. 0.01 of a sixteenth is 0.0006 blocks -- about a hundredth of a
# texture pixel, far below anything visible -- and it is enough to keep the overlays off the base.
# Among themselves the overlays rarely share pixels, and where they do the later draw wins, so they
# do not need separating from each other.
OVERLAY_OUTSET = 0.01

LAYERS = [
    ("ore", "ore_overlay", 1, OVERLAY_OUTSET, SLOTS, False),
    ("ore_b", "ore_overlay_b", 2, OVERLAY_OUTSET, SLOTS, True),
    ("precious", "precious_overlay", 3, OVERLAY_OUTSET, SLOTS, False),
    ("precious_b", "precious_overlay_b", 4, OVERLAY_OUTSET, SLOTS, True),
    ("gem", "gem_overlay", 5, OVERLAY_OUTSET, SLOTS, False),
    ("gem_b", "gem_overlay_b", 6, OVERLAY_OUTSET, SLOTS, True),
]

SOURCES = {
    "ore_overlay": "iron_ore",
    "ore_overlay_b": "iron_ore",
    "precious_overlay": "copper_ore",
    "precious_overlay_b": "copper_ore",
    "gem_overlay": "diamond_ore",
    "gem_overlay_b": "diamond_ore",
}

# Drop items follow the same rule as blocks: greyscale sprite, colour from tint. Vanilla's item
# art is desaturated to luminance and stretched into a band bright enough for a multiply to land
# somewhere useful, exactly as the stone base is.
#
# Naming these materials is deliberately last (Timothy), so the shapes borrow from whatever vanilla
# item reads closest to the class. They are placeholders for shape, not for identity.
ITEM_SOURCES = {
    "rock_chunk": "flint",
    "ore_chunk": "raw_iron",
    "precious_ore_chunk": "raw_copper",
    "gem": "diamond",
    "sand_pile": "sugar",
    "dirt_clod": "brick",
    "clay_ball": "clay_ball",
    # One ingot sprite for every metal. Same economy as the grain items above: greyscale here,
    # coloured at draw time, so adding a metal to the roster costs no texture at all.
    "ingot": "iron_ingot",
}
ITEM_MIN, ITEM_MAX = 120, 255


def find_client_jar():
    if len(sys.argv) > 1:
        return sys.argv[1]
    candidates = glob.glob(os.path.join(ROOT, "build/jars/extra/client/*/client-extra.jar"))
    candidates += glob.glob(os.path.expanduser("~/.gradle/caches/ng_execute/*/client-extra.jar"))
    if not candidates:
        raise SystemExit("client-extra.jar not found; run a Gradle task first, or pass the path")
    return candidates[0]


def load_vanilla(jar, names, item_names=()):
    out = {}
    with zipfile.ZipFile(jar) as zf:
        for name in names:
            with zf.open(f"assets/minecraft/textures/block/{name}.png") as handle:
                out[name] = Image.open(handle).convert("RGBA").copy()
        for name in item_names:
            with zf.open(f"assets/minecraft/textures/item/{name}.png") as handle:
                out[name] = Image.open(handle).convert("RGBA").copy()
    return out


def chroma(pixel):
    r, g, b = pixel[:3]
    return max(r, g, b) - min(r, g, b)


def luminance(pixel):
    r, g, b = pixel[:3]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def brighten_rgba(source, lo_out=BASE_MIN, hi_out=BASE_MAX):
    """Greyscale a texture and stretch it into the tint-friendly band, alpha preserved."""
    px = source.load()
    lums = [luminance(px[x, y]) for y in range(SIZE) for x in range(SIZE)]
    lo, hi = min(lums), max(lums)
    span = max(1e-6, hi - lo)
    out = Image.new("RGBA", (SIZE, SIZE))
    op = out.load()
    for y in range(SIZE):
        for x in range(SIZE):
            t = (luminance(px[x, y]) - lo) / span
            v = int(round(lo_out + t * (hi_out - lo_out)))
            op[x, y] = (v, v, v, px[x, y][3])
    return out


def brighten(stone):
    """Linear remap of vanilla stone into the tint-friendly band. Pattern is untouched."""
    px = stone.load()
    values = [px[x, y][0] for y in range(SIZE) for x in range(SIZE)]
    lo, hi = min(values), max(values)
    span = max(1, hi - lo)
    out = Image.new("RGBA", (SIZE, SIZE))
    op = out.load()
    for y in range(SIZE):
        for x in range(SIZE):
            t = (px[x, y][0] - lo) / span
            v = int(round(BASE_MIN + t * (BASE_MAX - BASE_MIN)))
            op[x, y] = (v, v, v, 255)
    return out


def extract_specks(image):
    """The chromatic pixels of an ore texture, as a mask plus a renormalised grey value."""
    px = image.load()
    mask = [[False] * SIZE for _ in range(SIZE)]
    lum = [[0.0] * SIZE for _ in range(SIZE)]
    values = []
    for y in range(SIZE):
        for x in range(SIZE):
            if chroma(px[x, y]) > CHROMA_THRESHOLD:
                mask[y][x] = True
                lum[y][x] = luminance(px[x, y])
                values.append(lum[y][x])

    lo, hi = min(values), max(values)
    span = max(1e-6, hi - lo)
    grey = [[0] * SIZE for _ in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            if mask[y][x]:
                t = (lum[y][x] - lo) / span
                grey[y][x] = int(round(SPECK_MIN + t * (SPECK_MAX - SPECK_MIN)))
    return mask, grey, len(values)


def signed_distance(mask):
    """Distance to the nearest mask boundary, negative inside. Wraps, because textures tile."""
    def wrapped(dx, dy):
        dx = min(abs(dx), SIZE - abs(dx))
        dy = min(abs(dy), SIZE - abs(dy))
        return math.hypot(dx, dy)

    inside = [(x, y) for y in range(SIZE) for x in range(SIZE) if mask[y][x]]
    outside = [(x, y) for y in range(SIZE) for x in range(SIZE) if not mask[y][x]]

    field = [[0.0] * SIZE for _ in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            others = outside if mask[y][x] else inside
            if not others:
                field[y][x] = 0.0
                continue
            nearest = min(wrapped(x - ox, y - oy) for ox, oy in others)
            field[y][x] = -nearest if mask[y][x] else nearest
    return field


def components(mask):
    """Connected specks, 8-connected and wrapping at the edges."""
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
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        nx, ny = (cx + dx) % SIZE, (cy + dy) % SIZE
                        if mask[ny][nx] and not seen[ny][nx]:
                            seen[ny][nx] = True
                            stack.append((nx, ny))
            found.append(blob)
    return found


def pixel_order(mask, field, largest_first=False):
    """Every pixel, ordered by when it should appear as the ore amount grows.

    Thresholding the distance field directly does not work: vanilla's specks are one or two pixels
    thin, so erosion has almost no resolution and three different targets all resolved to the same
    63 pixels. Ordering pixels explicitly gives exact counts at every step.

    Specks appear smallest-first, so the faintest step reads as a scatter of traces rather than one
    solid lump -- which is what a block holding one ore in nine should look like. Beyond vanilla's
    own coverage the order continues outward by distance, dilating the blobs that are already there.
    """
    blobs = sorted(components(mask), key=lambda b: (len(b), min(b)), reverse=largest_first)
    order = []
    for rank, blob in enumerate(blobs):
        # Within a speck, the most interior pixel first, so a partial speck grows from its core.
        for x, y in sorted(blob, key=lambda p: (field[p[1]][p[0]], p[1], p[0])):
            order.append((x, y))
    outside = [(x, y) for y in range(SIZE) for x in range(SIZE) if not mask[y][x]]
    order.extend(sorted(outside, key=lambda p: (field[p[1]][p[0]], p[1], p[0])))
    return order


def nearest_value(grey, mask, x, y):
    best, value = None, SPECK_MIN
    for oy in range(SIZE):
        for ox in range(SIZE):
            if not mask[oy][ox]:
                continue
            dx = min(abs(x - ox), SIZE - abs(x - ox))
            dy = min(abs(y - oy), SIZE - abs(y - oy))
            d = dx * dx + dy * dy
            if best is None or d < best:
                best, value = d, grey[oy][ox]
    return value


def write_speck_steps(name, source, steps, largest_first=False):
    """One sprite per slot count, from vanilla's specks grown and shrunk."""
    mask, grey, vanilla_count = extract_specks(source)
    field = signed_distance(mask)
    order = pixel_order(mask, field, largest_first)

    written = []
    for k in range(1, steps + 1):
        target = int(round(pow(k / SLOTS, ORE_GAMMA) * SIZE * SIZE))
        target = max(1, min(target, len(order)))
        img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        px = img.load()
        for x, y in order[:target]:
            # Dilated pixels have no colour of their own, so they borrow the nearest speck's
            # value; the interior keeps vanilla's own shading.
            value = grey[y][x] if mask[y][x] else nearest_value(grey, mask, x, y)
            # Binary alpha: the cutout test admits nothing in between.
            px[x, y] = (value, value, value, 255)
        img.save(os.path.join(TEX, f"{name}_{k}.png"))
        written.append(target)
    return written, vanilla_count


def write_item_sprites(vanilla):
    """Greyscale item sprites, so one item per class covers every colour by tint."""
    out_dir = os.path.join(ASSETS, "textures", "item")
    model_dir = os.path.join(ASSETS, "models", "item")
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(model_dir, exist_ok=True)

    written = []
    for name, source in ITEM_SOURCES.items():
        src = vanilla[source]
        px = src.load()
        lums = [luminance(px[x, y]) for y in range(src.height) for x in range(src.width)
                if px[x, y][3] > 0]
        lo, hi = min(lums), max(lums)
        span = max(1e-6, hi - lo)

        img = Image.new("RGBA", src.size, (0, 0, 0, 0))
        op = img.load()
        for y in range(src.height):
            for x in range(src.width):
                r, g, b, a = px[x, y]
                if a == 0:
                    continue
                t = (luminance((r, g, b)) - lo) / span
                v = int(round(ITEM_MIN + t * (ITEM_MAX - ITEM_MIN)))
                op[x, y] = (v, v, v, a)
        img.save(os.path.join(out_dir, f"{name}.png"))

        model = {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"granularity:item/{name}"},
        }
        with open(os.path.join(model_dir, f"{name}.json"), "w") as handle:
            json.dump(model, handle, indent=2)
            handle.write("\n")
        written.append(name)
    return written


# Cobblestone is treated exactly like the ores: a greyscale base plus overlays, one per tintable
# region. The difference is what a region means. Vanilla's cobblestone has well-defined lighter
# areas separated by darker mortar lines, and *those lighter areas are the individual stones* -- so
# the nine largest become nine overlays, one per slot, each carrying the exact colour of the grain
# that fills it. Everything left over (mortar, offcuts) stays on the base and is tinted with the
# average, the same way natural stone is.
COBBLE_THRESHOLD = 115
COBBLE_SHAPES = SLOTS


def cobble_shapes(stone):
    """The nine largest light areas of the cobblestone texture, as pixel lists."""
    px = stone.load()
    lum = [[luminance(px[x, y]) for x in range(SIZE)] for y in range(SIZE)]
    mask = [[lum[y][x] > COBBLE_THRESHOLD for x in range(SIZE)] for y in range(SIZE)]

    seen = [[False] * SIZE for _ in range(SIZE)]
    blobs = []
    for y in range(SIZE):
        for x in range(SIZE):
            if not mask[y][x] or seen[y][x]:
                continue
            stack, blob = [(x, y)], []
            seen[y][x] = True
            while stack:
                cx, cy = stack.pop()
                blob.append((cx, cy))
                # Four-connected and wrapping: the texture tiles, so a stone may straddle an edge.
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = (cx + dx) % SIZE, (cy + dy) % SIZE
                    if mask[ny][nx] and not seen[ny][nx]:
                        seen[ny][nx] = True
                        stack.append((nx, ny))
            blobs.append(blob)

    # Largest first, ties broken by position so the assignment of slot to stone is stable.
    blobs.sort(key=lambda b: (-len(b), min(b)))
    return blobs[:COBBLE_SHAPES]


def write_cobble_shapes(stone):
    px = stone.load()
    lums = [luminance(px[x, y]) for y in range(SIZE) for x in range(SIZE)]
    lo, hi = min(lums), max(lums)
    span = max(1e-6, hi - lo)

    written = []
    for index, blob in enumerate(cobble_shapes(stone), start=1):
        img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        op = img.load()
        for x, y in blob:
            t = (luminance(px[x, y]) - lo) / span
            v = int(round(STONE_MIN + t * (STONE_MAX - STONE_MIN)))
            op[x, y] = (v, v, v, 255)
        img.save(os.path.join(TEX, f"cobblestone_shape_{index}.png"))
        written.append(len(blob))
    return written


def gravel_shapes(gravel):
    """Nine arbitrary regions of the gravel texture.

    Gravel is a muddle -- there are no well-defined stones to find the way cobblestone has them, so
    any partition will do. A smooth field sliced into nine gives blobby contiguous regions rather
    than scattered pixels, which reads as lumps of different material rather than static.
    """
    field = patch_field(seed=90210, lattice=4)
    order = sorted(((field[y][x], x, y) for y in range(SIZE) for x in range(SIZE)), key=lambda t: t[0])
    groups = [[] for _ in range(SLOTS)]
    per = len(order) / SLOTS
    for index, (_, x, y) in enumerate(order):
        groups[min(SLOTS - 1, int(index / per))].append((x, y))
    return groups


def write_partition_shapes(name, source, groups):
    """One sprite per region, carrying that region's pixels from the source texture."""
    px = source.load()
    lums = [luminance(px[x, y]) for y in range(SIZE) for x in range(SIZE)]
    lo, hi = min(lums), max(lums)
    span = max(1e-6, hi - lo)
    written = []
    for index, group in enumerate(groups, start=1):
        img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        op = img.load()
        for x, y in group:
            t = (luminance(px[x, y]) - lo) / span
            v = int(round(BASE_MIN + t * (BASE_MAX - BASE_MIN)))
            op[x, y] = (v, v, v, 255)
        img.save(os.path.join(TEX, f"{name}_shape_{index}.png"))
        written.append(len(group))
    return written


def patch_field(seed=20250811, lattice=4):
    """A smooth, tiling field used to carve a texture into contiguous regions."""
    rng = random.Random(seed)
    grid = [[rng.random() for _ in range(lattice)] for _ in range(lattice)]

    def fade(t):
        return t * t * t * (t * (t * 6 - 15) + 10)

    field = [[0.0] * SIZE for _ in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            fx, fy = x / SIZE * lattice, y / SIZE * lattice
            x0, y0 = int(fx), int(fy)
            tx, ty = fade(fx - x0), fade(fy - y0)
            v00 = grid[y0 % lattice][x0 % lattice]
            v10 = grid[y0 % lattice][(x0 + 1) % lattice]
            v01 = grid[(y0 + 1) % lattice][x0 % lattice]
            v11 = grid[(y0 + 1) % lattice][(x0 + 1) % lattice]
            field[y][x] = (v00 * (1 - tx) + v10 * tx) * (1 - ty) + (v01 * (1 - tx) + v11 * tx) * ty
    return field


def cube_faces(tint_index):
    return {
        face: {"texture": "#all", "cullface": face, "tintindex": tint_index}
        for face in ("down", "up", "north", "south", "west", "east")
    }


def cut_out(image, groups):
    """Clears the pixels claimed by the overlays, leaving only the matrix behind them."""
    px = image.load()
    for group in groups:
        for x, y in group:
            px[x, y] = (0, 0, 0, 0)
    return image


# How many steps the stone->deepslate ramp is cut into. Each band is a whole sprite, so this trades
# atlas space and quad-cache entries against visible banding on a tall cliff face. Sixteen over a
# 384-block world is a step every 24 blocks, which the composition tint largely hides.
DEPTH_BANDS = 16


def mean_level(image):
    """Mean luminance of a sprite, for comparing two vanilla textures' tone."""
    px = image.convert("RGBA").load()
    total = sum(px[x, y][0] for y in range(SIZE) for x in range(SIZE))
    return total / (SIZE * SIZE)


def blend_steps(low, high, steps):
    """The ramp from one greyscale sprite to another, inclusive of both ends."""
    out = []
    lo = low.convert("RGBA").load()
    hi = high.convert("RGBA").load()
    for k in range(steps):
        t = k / (steps - 1)
        image = low.convert("RGBA").copy()
        px = image.load()
        for y in range(SIZE):
            for x in range(SIZE):
                a, b = lo[x, y], hi[x, y]
                px[x, y] = tuple(round(a[c] * (1 - t) + b[c] * t) for c in range(3)) + (a[3],)
        out.append(image)
    return out


def write_model(name, texture, tint_index, outset, particle=None):
    model = {
        # Inherits the standard block display transforms. Without a parent the item form renders
        # at raw model scale -- enormous in the GUI and in hand.
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout_mipped",
        "textures": {
            "all": f"granularity:block/{texture}",
            "particle": f"granularity:block/{particle or 'stone_base'}",
        },
        "elements": [
            {
                "from": [-outset, -outset, -outset],
                "to": [16 + outset, 16 + outset, 16 + outset],
                "faces": cube_faces(tint_index),
            }
        ],
    }
    with open(os.path.join(MODELS, f"{name}.json"), "w") as handle:
        json.dump(model, handle, indent=2)
        handle.write("\n")


def clean(prefix, directory, keep, suffix):
    for stale in os.listdir(directory):
        if stale.startswith(prefix) and stale.endswith(suffix):
            index = stale[len(prefix):-len(suffix)]
            if index.isdigit() and int(index) > keep:
                os.remove(os.path.join(directory, stale))


# ---------------------------------------------------------------------------------------------
# Stonework styles: the vanilla art for blocks this mod suppressed, recovered as *cuts* rather than
# as materials. A style is a pattern, not a rock -- greyscale like everything else here, so the
# block's own grains still supply the colour. A tuff-patterned block of slate is slate-coloured.
#
# Each of these becomes one Finish value and needs nothing else: FinishBakedModel derives the
# geometry from whatever shape it is given and simply wears this sprite, so a style costs one
# texture and works on blocks, slabs, stairs and walls alike.
STYLES = {
    # family        vanilla texture
    "mottled":            "tuff",
    "mottled_polished":   "polished_tuff",
    "mottled_bricks":     "tuff_bricks",
    "mottled_chiseled":   "chiseled_tuff",
    "banded":             "calcite",
    "fine":               "deepslate",
    "fine_polished":      "polished_deepslate",
    "fine_bricks":        "deepslate_bricks",
    "fine_tiled":         "deepslate_tiles",
    "fine_chiseled":      "chiseled_deepslate",
    "flowstone":          "dripstone_block",

    # Added 2026-08-17. Every one of these was measured against the shipped styles first, with
    # tools/style_survey.py -- which is what kept `red_sandstone` (0.009 from `sandstone`),
    # `cut_red_sandstone` (0.010) and `chiseled_red_sandstone` off this list. A red variant is the same
    # pattern in a redder rock, and in this mod the rock's colour comes from its grains, so shipping
    # both would be shipping one sprite twice and inviting the player to pick a colour the material is
    # supposed to decide.
    #
    # Only `polished_diorite` of the "large polished" trio: granite and andesite sit 0.169 from
    # `polished_tuff`, which already ships as Polished Mottled, whereas diorite is the outlier at
    # 0.25-0.32 and so the one that adds something a wall can tell apart.
    "pebbled":            "sandstone",
    "pebbled_chiseled":   "chiseled_sandstone",
    "squared":            "cut_sandstone",
    "bricks":             "stone_bricks",
    "bricks_chiseled":    "chiseled_stone_bricks",
    "small_bricks":       "nether_bricks",
    "small_bricks_chiseled": "chiseled_nether_bricks",
    "polished":           "polished_diorite",
}

# The faces that are *not* the sides, for the styles whose vanilla block draws its ends differently.
#
# A style was one sprite for every face, which is right for most of them -- vanilla's own model is
# `cube_all` and a Fine Tile really is the same tile on all six sides. But five of these are not:
# sandstone and cut sandstone and chiseled sandstone are `cube_bottom_top` or `cube_column`, and so are
# deepslate and chiseled tuff. Drawn with the side sprite on top they read as a slab of bedding seen
# end-on, which is wrong in exactly the way only a person notices -- Timothy did, on Pebbled, and the
# same fault had been shipping unremarked on Fine and Chiseled Mottled since those styles were added.
#
# `cube_column` shares one sprite between top and bottom, so most of these need a single `_top`. Only
# sandstone distinguishes its underside, and gets a `_bottom` as well.
#
# Pebbled's top is shared by its whole family: Squared and Chiseled Pebbled are cut and carved *from*
# it, and vanilla uses `sandstone_top` for all three ends. Sharing the sprite says that, and is the
# reason the cut graph puts them in one family.
STYLE_ENDS = {
    "pebbled_top":          "sandstone_top",
    "pebbled_bottom":       "sandstone_bottom",
    "fine_top":             "deepslate_top",
    "mottled_chiseled_top": "chiseled_tuff_top",
}


def main():
    os.makedirs(TEX, exist_ok=True)
    os.makedirs(MODELS, exist_ok=True)

    jar = find_client_jar()
    vanilla = load_vanilla(jar, ["stone", "deepslate"] + list(SOURCES.values())
                           + list(STYLES.values()) + list(STYLE_ENDS.values()),
                           item_names=list(ITEM_SOURCES.values()))
    print("vanilla source:", jar)

    stone = vanilla["stone"]
    px = stone.load()
    neutral = all(px[x, y][0] == px[x, y][1] == px[x, y][2] for y in range(SIZE) for x in range(SIZE))
    values = [px[x, y][0] for y in range(SIZE) for x in range(SIZE)]
    print(f"stone.png is pure greyscale: {neutral}, range {min(values)}-{max(values)}")

    stone = brighten(stone)
    px = stone.load()
    values = [px[x, y][0] for y in range(SIZE) for x in range(SIZE)]
    print(f"base rescaled for tint headroom: range {min(values)}-{max(values)}")
    stone.save(os.path.join(TEX, "stone_base.png"))

    for _, name, _, _, steps, largest_first in LAYERS:
        counts, vanilla_count = write_speck_steps(
            name, vanilla[SOURCES[name]], steps, largest_first)
        variant = "b (largest-first)" if largest_first else "a (smallest-first)"
        print(f"{name:20s} {variant:20s} vanilla={vanilla_count:3d} px  steps={counts}")

    write_model("natural_stone_base", "stone_base", 0, 0.0)

    # One sprite per stonework style. Normalised into the same tint band as stone so that every
    # style takes a composition's colour the same way -- a style must read as a different *cut* of
    # the same rock, not as a different brightness of it.
    for style, source in sorted(STYLES.items()):
        image = brighten_rgba(vanilla[source])
        image.save(os.path.join(TEX, f"{style}_base.png"))
    for name, source in sorted(STYLE_ENDS.items()):
        brighten_rgba(vanilla[source]).save(os.path.join(TEX, f"{name}.png"))
    print(f"stonework styles: {len(STYLES)} side sprites from "
          f"{', '.join(sorted(STYLES.values()))}")
    print(f"  plus {len(STYLE_ENDS)} end sprites: "
          f"{', '.join(f'{n} <- {s}' for n, s in sorted(STYLE_ENDS.items()))}")

    # The depth ramp: one sprite per band, stone at the top of the world grading into deepslate at
    # the bottom. Blended into the sprite rather than drawn as a second translucent layer on top,
    # which is what keeps it free at render time -- a band is just a different opaque texture, and
    # the model system already selects among prebaked models per block. A translucent overlay would
    # have cost a second quad and a sorted render pass on every stone block in the world.
    # Deepslate must stay *darker* than stone, so it is not rescaled into the same window. brighten()
    # normalises for tint headroom, and normalising both would have thrown away the one thing the
    # ramp is for: the ground getting darker as you go down. Its window is scaled by the ratio of the
    # two vanilla textures' own means, so the relationship between them is vanilla's, not invented.
    ratio = mean_level(vanilla["deepslate"]) / mean_level(vanilla["stone"])
    deep = brighten_rgba(vanilla["deepslate"], BASE_MIN * ratio, BASE_MAX * ratio)
    print(f"deepslate is {ratio:.2f}x stone's brightness; ramp keeps that")
    ramp = blend_steps(stone, deep, DEPTH_BANDS)
    for k, image in enumerate(ramp):
        image.save(os.path.join(TEX, f"stone_depth_{k}.png"))
        write_model(f"natural_stone_depth_{k}", f"stone_depth_{k}", 0, 0.0)
    clean("natural_stone_depth_", MODELS, DEPTH_BANDS - 1, ".json")
    clean("stone_depth_", TEX, DEPTH_BANDS - 1, ".png")
    print(f"depth ramp: {DEPTH_BANDS} bands, stone -> deepslate")
    for family, sprite, tint, outset, steps, _ in LAYERS:
        # The model is named for the family and the texture for the sprite; they are different
        # strings ("rock" vs "rock_overlay") and conflating them writes files the Java never looks
        # for, which shows up only as a wall of missing-model warnings at bake time.
        for k in range(1, steps + 1):
            write_model(f"natural_stone_{family}_{k}", f"{sprite}_{k}", tint, outset)
        clean(f"natural_stone_{family}_", MODELS, steps, ".json")
        clean(f"{sprite}_", TEX, steps, ".png")

    cobble = load_vanilla(jar, ["cobblestone"])["cobblestone"]
    brighten_rgba(cobble).save(os.path.join(TEX, "cobblestone_full.png"))
    shapes = cobble_shapes(cobble)
    cut_out(brighten_rgba(cobble, MORTAR_MIN, MORTAR_MAX), shapes).save(
        os.path.join(TEX, "cobblestone_base.png"))
    shape_sizes = write_cobble_shapes(cobble)
    print(f"cobblestone: matrix + {len(shape_sizes)} stone shapes, px {shape_sizes}")
    write_model("cobblestone_base", "cobblestone_base", 0, 0.0, particle="cobblestone_full")
    for index in range(1, COBBLE_SHAPES + 1):
        write_model(f"cobblestone_shape_{index}", f"cobblestone_shape_{index}", index, 0.0,
                    particle="cobblestone_full")

    gravel = load_vanilla(jar, ["gravel"])["gravel"]
    brighten_rgba(gravel).save(os.path.join(TEX, "gravel_full.png"))
    regions = gravel_shapes(gravel)
    cut_out(brighten_rgba(gravel), regions).save(os.path.join(TEX, "gravel_base.png"))
    gravel_sizes = write_partition_shapes("gravel", gravel, regions)
    print(f"gravel: matrix + {len(gravel_sizes)} regions, px {gravel_sizes}")
    write_model("gravel_base", "gravel_base", 0, 0.0, particle="gravel_full")
    for index in range(1, SLOTS + 1):
        write_model(f"gravel_shape_{index}", f"gravel_shape_{index}", index, 0.0,
                    particle="gravel_full")

    items = write_item_sprites(vanilla)
    print(f"item sprites (greyscale, tinted at runtime): {', '.join(items)}")

    total = 1 + sum(entry[4] for entry in LAYERS)
    print(f"sprites: {total} (1 base + {len(LAYERS)} overlay families x {SLOTS})")


if __name__ == "__main__":
    main()
