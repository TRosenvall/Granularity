#!/usr/bin/env python3
"""Subtract one texture from another and write the difference as an overlay sprite.

    python3 tools/subtract_textures.py deepslate_bricks cracked_deepslate_bricks
    python3 tools/subtract_textures.py plain.png damaged.png -o crack.png --threshold 20

Bare names are looked up in the vanilla client jar, so any two vanilla textures can be diffed without
extracting them first; anything containing a dot or a slash is treated as a file path.

**What it is for.** Vanilla ships pairs that differ by one idea -- `X` and `cracked_X`, `cobblestone`
and `mossy_cobblestone` -- and the difference between them is sometimes a reusable layer. Moss was
lifted out exactly this way and is still the moss sprite. It is the cheapest way to find out whether
an effect can be separated from the surface it was drawn on.

**What it will often tell you is "no", and that is the useful answer.** The `cracked_*` textures do not
contain a crack layer: vanilla redrew the brick edges rather than overlaying a fissure, so the
difference is mostly texture noise with no line in it at any threshold. Better to see that in thirty
seconds than to discover it after authoring the rest.

**Output is ready to use as an overlay.** Alpha is binary, because block models draw on
`cutout_mipped` whose shader is `if (color.a < 0.5) discard;` -- there is no partial transparency to be
had, and a sprite full of soft edges renders as nothing at all. The colour is greyscale by default, to
be used with a *tinted* overlay (see `Overlay.tinted`): a tint multiplies, so grey 128 comes out as
whatever stone it lies on at half brightness. Pass --ink to get a fixed colour instead, for an overlay
that should look the same on every block, as moss does.
"""
import argparse
import glob
import io
import os
import zipfile

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..")
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "granularity", "textures", "block")


def find_client_jar():
    candidates = glob.glob(os.path.join(ROOT, "build/jars/extra/client/*/client-extra.jar"))
    candidates += glob.glob(os.path.expanduser("~/.gradle/caches/ng_execute/*/client-extra.jar"))
    if not candidates:
        raise SystemExit("client-extra.jar not found; run a Gradle task first, or pass file paths")
    return candidates[0]


def load(name, jar):
    """A texture by file path, or by vanilla name out of the client jar."""
    if os.path.sep in name or name.endswith(".png"):
        return Image.open(name).convert("RGBA")
    with zipfile.ZipFile(jar) as zf:
        raw = zf.read(f"assets/minecraft/textures/block/{name}.png")
    return Image.open(io.BytesIO(raw)).convert("RGBA")


def luminance(pixel):
    return 0.2126 * pixel[0] + 0.7152 * pixel[1] + 0.0722 * pixel[2]


def components(pixels, size):
    """Connected groups, eight-connected and wrapping, for dropping isolated specks."""
    seen, found = set(), []
    for start in pixels:
        if start in seen:
            continue
        stack, blob = [start], []
        seen.add(start)
        while stack:
            x, y = stack.pop()
            blob.append((x, y))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    neighbour = ((x + dx) % size, (y + dy) % size)
                    if neighbour in pixels and neighbour not in seen:
                        seen.add(neighbour)
                        stack.append(neighbour)
        found.append(blob)
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("base", help="the plain texture, or a vanilla name")
    parser.add_argument("variant", help="the changed texture, or a vanilla name")
    parser.add_argument("-o", "--out", help="where to write; defaults to the mod's block textures")
    parser.add_argument("--threshold", type=float, default=8.0,
                        help="how much a pixel must move to count as changed (default 8 of 255)")
    parser.add_argument("--mode", choices=("darker", "lighter", "any"), default="darker",
                        help="which direction of change to keep (default darker, i.e. damage)")
    parser.add_argument("--min-blob", type=int, default=1,
                        help="drop groups smaller than this, to strip speckle noise")
    parser.add_argument("--ink", help="fixed colour as R,G,B for an untinted overlay; "
                                      "omit for greyscale, to be used with a tinted one")
    parser.add_argument("--floor", type=int, default=70,
                        help="darkest grey to emit, so a crack is a shadow and not a hole")
    parser.add_argument("--gain", type=float, default=2.6,
                        help="how strongly a difference maps to darkness")
    args = parser.parse_args()

    jar = None
    if not (os.path.sep in args.base or args.base.endswith(".png")) or \
       not (os.path.sep in args.variant or args.variant.endswith(".png")):
        jar = find_client_jar()

    base, variant = load(args.base, jar), load(args.variant, jar)
    if base.size != variant.size:
        raise SystemExit(f"sizes differ: {base.size} vs {variant.size}")
    width, height = base.size
    if width != height:
        print(f"note: {width}x{height} is not square — an animation strip will diff frame by frame")

    bp, vp = base.load(), variant.load()
    moved = {}
    for y in range(height):
        for x in range(width):
            shift = luminance(bp[x, y]) - luminance(vp[x, y])   # positive = the variant is darker
            keep = {"darker": shift > args.threshold,
                    "lighter": -shift > args.threshold,
                    "any": abs(shift) > args.threshold}[args.mode]
            if keep:
                moved[(x, y)] = abs(shift)

    kept = set(moved)
    if args.min_blob > 1:
        kept = {p for blob in components(set(moved), width) if len(blob) >= args.min_blob
                for p in blob}

    out = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    op = out.load()
    ink = tuple(int(v) for v in args.ink.split(",")) if args.ink else None
    for (x, y) in kept:
        if ink:
            op[x, y] = (*ink, 255)
        else:
            # Greyscale, to be multiplied by the block's own colour. A bigger difference means a
            # darker multiplier, floored so the deepest part of a crack is still stone in shadow.
            grey = max(args.floor, min(235, int(255 - moved[(x, y)] * args.gain)))
            op[x, y] = (grey, grey, grey, 255)

    destination = args.out or os.path.join(TEX, f"{os.path.basename(args.variant)}_delta.png")
    out.save(destination)

    print(f"{len(moved)} px moved by more than {args.threshold:g}"
          + (f", {len(kept)} kept after dropping groups under {args.min_blob}"
             if args.min_blob > 1 else ""))
    if moved:
        strongest = sorted(moved.values(), reverse=True)[:8]
        print("  strongest shifts: " + ", ".join(f"{v:.0f}" for v in strongest))
    print(f"  wrote {destination}")
    print("  alpha is binary — cutout_mipped discards anything under 0.5, so there is no faint")
    if not ink:
        print("  greyscale — register the overlay with Overlay.tinted so it multiplies the stone")


if __name__ == "__main__":
    main()
