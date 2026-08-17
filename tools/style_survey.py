#!/usr/bin/env python3
"""Measure which vanilla stone textures are genuinely different patterns.

Run offline, before deciding whether a texture is worth shipping as a stonework style. It writes
nothing; it answers a question. See docs/STONEWORK_STYLES.md, which is built on its output.

**The question it exists to answer.** A style in this mod is a *greyscale* sprite — the block's own
grains supply the colour. So two vanilla textures that differ only in hue are, here, the same style
drawn twice, and shipping both would invite the player to pick a colour that the material is supposed
to decide. `red_sandstone` is the worked example: it comes out 0.009 from `sandstone`, which is to say
identical. That is not a detail to be discovered after authoring the sprite and the eight lang keys.

**How it measures.** Each texture is reduced the way the pipeline reduces it — luminance, then
contrast-normalised to 0..1 — so brightness and colour are gone and only the pattern remains. Two
textures are then compared by RMS over the 256 pixels. Across every pair the median lands near 0.405;
read that as the distance between two patterns with nothing to do with each other.

    < 0.05     the same pattern. Do not ship both.
    0.13-0.25  a real but close relative -- the spacing between styles already shipped.
    > 0.40     unrelated.

**The two deltas.** `--deltas` additionally asks what a `cracked_` or `chiseled_` texture *does* to
the texture it was drawn over, which is what decides whether it is a layer or a drawing:

    cracked   19-64 px of 256 move and nearly all darken  -> a layer on top. A Coating.
    chiseled  87-193 px move                              -> a different drawing. A Finish.

That asymmetry is the whole finish-versus-overlay argument, and it is measured rather than argued.
"""
import argparse
import glob
import io
import itertools
import os
import sys
import zipfile

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..")
SIZE = 16

# Everything that could plausibly be a stonework style. Grouped only for readability; the comparison
# is over the whole set, because the interesting collisions cross groups -- `polished_tuff` sits
# between `polished_granite` and `polished_andesite`, which is the sort of thing a per-group survey
# would hide.
CANDIDATES = """
    stone granite andesite diorite tuff calcite deepslate cobbled_deepslate dripstone_block
    basalt_side blackstone netherrack end_stone sandstone red_sandstone purpur_block
    dark_prismarine quartz_block_side smooth_basalt cobblestone

    smooth_stone polished_granite polished_andesite polished_diorite polished_deepslate
    polished_blackstone polished_basalt_side polished_tuff cut_sandstone cut_red_sandstone
    quartz_block_top

    bricks stone_bricks deepslate_bricks deepslate_tiles tuff_bricks mud_bricks nether_bricks
    red_nether_bricks polished_blackstone_bricks end_stone_bricks prismarine_bricks quartz_bricks

    cracked_stone_bricks cracked_deepslate_bricks cracked_deepslate_tiles cracked_nether_bricks
    cracked_polished_blackstone_bricks

    chiseled_stone_bricks chiseled_deepslate chiseled_sandstone chiseled_red_sandstone
    chiseled_nether_bricks chiseled_polished_blackstone chiseled_quartz_block chiseled_tuff
    chiseled_tuff_bricks
""".split()

# What each worked texture was drawn over. Guessed nowhere: these are the pairings vanilla's own
# recipes make, which is what makes the delta meaningful.
DERIVED_FROM = {
    "cracked_stone_bricks": "stone_bricks",
    "cracked_deepslate_bricks": "deepslate_bricks",
    "cracked_deepslate_tiles": "deepslate_tiles",
    "cracked_nether_bricks": "nether_bricks",
    "cracked_polished_blackstone_bricks": "polished_blackstone_bricks",
    "chiseled_stone_bricks": "stone_bricks",
    "chiseled_deepslate": "polished_deepslate",
    "chiseled_sandstone": "cut_sandstone",
    "chiseled_nether_bricks": "nether_bricks",
    "chiseled_polished_blackstone": "polished_blackstone",
    "chiseled_tuff": "polished_tuff",
    "chiseled_tuff_bricks": "tuff_bricks",
}

# A pixel has to move by more than this to count as changed rather than as dithering noise.
MOVED = 12

# Below this two textures are the same pattern in different colours and only one should ship.
SAME = 0.05


def find_client_jar():
    if len(sys.argv) > 1 and sys.argv[1].endswith(".jar"):
        return sys.argv[1]
    candidates = glob.glob(os.path.join(ROOT, "build/jars/extra/client/*/client-extra.jar"))
    candidates += glob.glob(os.path.expanduser("~/.gradle/caches/ng_execute/*/client-extra.jar"))
    if not candidates:
        raise SystemExit("client-extra.jar not found; run a Gradle task first, or pass the path")
    return candidates[0]


def luminance(pixel):
    return 0.2126 * pixel[0] + 0.7152 * pixel[1] + 0.0722 * pixel[2]


def load(zf, name):
    """One texture as a grid of luminances, or None if it is not a plain 16x16 face."""
    try:
        raw = zf.read(f"assets/minecraft/textures/block/{name}.png")
    except KeyError:
        return None
    image = Image.open(io.BytesIO(raw)).convert("RGBA")
    if image.size != (SIZE, SIZE):
        # An animation strip, which is a different kind of thing and never a style.
        return None
    px = image.load()
    return [[luminance(px[x, y]) for x in range(SIZE)] for y in range(SIZE)]


def normalised(grid):
    """Contrast-stretched to 0..1, which is what removes brightness as well as colour."""
    values = [v for row in grid for v in row]
    lo, hi = min(values), max(values)
    span = max(1e-6, hi - lo)
    return [[(v - lo) / span for v in row] for row in grid]


def distance(a, b):
    total = sum((a[y][x] - b[y][x]) ** 2 for y in range(SIZE) for x in range(SIZE))
    return (total / (SIZE * SIZE)) ** 0.5


def survey(zf, limit):
    grids = {}
    for name in CANDIDATES:
        grid = load(zf, name)
        if grid is None:
            print(f"  skipped {name} -- missing, or not a plain 16x16 face")
            continue
        grids[name] = normalised(grid)

    pairs = sorted((distance(grids[a], grids[b]), a, b)
                   for a, b in itertools.combinations(sorted(grids), 2))
    median = pairs[len(pairs) // 2][0]

    print(f"\n{len(grids)} textures, {len(pairs)} pairs. Median distance {median:.3f} "
          f"-- that is 'unrelated'.\n")
    print("Closest pairs:\n")
    for gap, a, b in pairs[:limit]:
        verdict = "  <-- SAME PATTERN, ship one" if gap < SAME else ""
        print(f"  {gap:.3f}  {a:<34s} {b}{verdict}")

    duplicates = [p for p in pairs if p[0] < SAME]
    print(f"\n{len(duplicates)} pair(s) under {SAME}: the same pattern in a different colour, which "
          f"in this mod is not a style at all -- the grains decide colour.")
    return grids


def deltas(zf):
    """What a worked texture does to the one it was drawn over: a layer, or a new drawing?"""
    print("\nWhat the worked textures do to their base:\n")
    print(f"  {'texture':<36s}{'over':<28s}{'moved':>10s}{'darker':>8s}{'shift':>8s}{'dist':>7s}")
    for name, base_name in DERIVED_FROM.items():
        worked, base = load(zf, name), load(zf, base_name)
        if worked is None or base is None:
            continue
        shifts = [worked[y][x] - base[y][x] for y in range(SIZE) for x in range(SIZE)]
        moved = [s for s in shifts if abs(s) > MOVED]
        darker = sum(1 for s in moved if s < 0)
        mean = sum(moved) / max(1, len(moved))
        gap = distance(normalised(base), normalised(worked))
        print(f"  {name:<36s}{base_name:<28s}{len(moved):>7d}/256{darker:>8d}"
              f"{mean:>+8.1f}{gap:>7.3f}")
    print("\n  A few pixels, nearly all darker  -> a layer drawn on top. Ship it as a Coating.")
    print("  A third to three quarters moved  -> a different drawing. Ship it as a Finish.")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("jar", nargs="?", help="client-extra.jar; found automatically if omitted")
    parser.add_argument("--deltas", action="store_true",
                        help="also measure what cracked_/chiseled_ do to their base")
    parser.add_argument("--top", type=int, default=22, help="how many close pairs to list")
    args = parser.parse_args()

    with zipfile.ZipFile(args.jar or find_client_jar()) as zf:
        survey(zf, args.top)
        if args.deltas:
            deltas(zf)


if __name__ == "__main__":
    main()
