#!/usr/bin/env python3
"""Recolour an overlay sprite onto the palette of a vanilla block.

Run offline, never by the game. Overlays are hand-cut shapes -- moss was diffed out of vanilla's
mossy cobblestone -- and this is for the case where a shape is right but the colour is not: the slime
overlay is the blood splatter wearing slime's palette, because a splatter is a splatter and vanilla
already decided what slime looks like.

Relative luminance is preserved and mapped onto the darkest-to-lightest range of the palette source,
so the shading of the original survives and the result sits in the same tonal range as the vanilla
block it is named after. The alpha mask is untouched: the shape is the whole point of reusing it.
"""
import os
import sys
import zipfile

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..")
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "granularity", "textures", "block")
SIZE = 16

# output name -> (our sprite to take the shape from, vanilla block to take the palette from)
RECOLOURS = {
    "cobblestone_slime": ("cobblestone_blood", "slime_block"),
}


def luminance(pixel):
    return 0.2126 * pixel[0] + 0.7152 * pixel[1] + 0.0722 * pixel[2]


def find_client_jar():
    import glob
    if len(sys.argv) > 1:
        return sys.argv[1]
    candidates = glob.glob(os.path.join(ROOT, "build/jars/extra/client/*/client-extra.jar"))
    candidates += glob.glob(os.path.expanduser("~/.gradle/caches/ng_execute/*/client-extra.jar"))
    if not candidates:
        raise SystemExit("client-extra.jar not found; run a Gradle task first, or pass the path")
    return candidates[0]


def ramp(image):
    """The palette source's darkest and lightest opaque pixels, to map a shape's shading between."""
    px = image.load()
    opaque = [px[x, y] for y in range(SIZE) for x in range(SIZE) if px[x, y][3] > 0]
    return (min(opaque, key=luminance), max(opaque, key=luminance))


def main():
    with zipfile.ZipFile(find_client_jar()) as zf:
        for name, (shape_name, palette_name) in RECOLOURS.items():
            shape = Image.open(os.path.join(TEX, f"{shape_name}.png")).convert("RGBA")
            with zf.open(f"assets/minecraft/textures/block/{palette_name}.png") as handle:
                palette = Image.open(handle).convert("RGBA").copy()

            dark, light = ramp(palette)
            px = shape.load()
            lums = [luminance(px[x, y]) for y in range(SIZE) for x in range(SIZE) if px[x, y][3] > 0]
            lo, hi = min(lums), max(lums)
            span = max(1e-6, hi - lo)

            out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
            op = out.load()
            for y in range(SIZE):
                for x in range(SIZE):
                    pixel = px[x, y]
                    if pixel[3] == 0:
                        continue
                    t = (luminance(pixel) - lo) / span
                    op[x, y] = tuple(
                        int(round(dark[c] + t * (light[c] - dark[c]))) for c in range(3)
                    ) + (pixel[3],)
            out.save(os.path.join(TEX, f"{name}.png"))
            covered = sum(1 for y in range(SIZE) for x in range(SIZE) if px[x, y][3] > 0)
            print(f"{name:22s} shape from {shape_name} ({covered} px), palette from {palette_name}: "
                  f"#{dark[0]:02X}{dark[1]:02X}{dark[2]:02X} to "
                  f"#{light[0]:02X}{light[1]:02X}{light[2]:02X}")


if __name__ == "__main__":
    main()
