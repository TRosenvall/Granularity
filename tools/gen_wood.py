#!/usr/bin/env python3
"""Derive greyscale log textures from vanilla's own dark oak, for wood the way this mod does stone.

Run offline, never by the game.

**Why our own.** A block tint *multiplies*. Every surface texture in this mod is greyscale so that
multiplying it by a colour gives that colour -- which is what makes one sprite serve every rock, and
one serve every timber. A vanilla log texture is not greyscale, so it cannot be recoloured: dark oak
times grey is darker dark oak, never grey. Borrowing vanilla's sprite therefore means giving up the
colour, and taking the colour means giving up vanilla's sprite.

Our own log breaks that trade. It reads as a log, and it takes any colour asked of it -- oak, or
slate, or iron. The same argument that made every stone surface derive from grains rather than ship
sixteen painted variants; see `docs/MATERIAL_ROSTER.md` and `tools/extract_textures.py`, which does
exactly this for stone.

**Vanilla's grain, not invented grain.** A procedural log was tried first and read as noise: real
bark has long, unevenly spaced striations and rings that are neither concentric nor centred, and none
of that survives being approximated. So the shape is Mojang's, and only the colour is removed --
which is also why it sits beside vanilla wood without looking foreign.

**The values matter.** Converted by luminance, then stretched into the band the sprites already in
use occupy: `stonecutter_side_wood` averages 227, `piston_side_wood` 211. Too dark and every colour
comes out muddy; too flat and the grain disappears.
"""
import glob
import os
import zipfile

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..")
BLOCK = os.path.join(ROOT, "src", "main", "resources", "assets", "granularity", "textures", "block")

# The log vanilla draws with the most contrast, so the grain survives being flattened to grey.
SOURCE = {"log_side": "dark_oak_log", "log_top": "dark_oak_log_top"}

# The band the existing tintable sprites occupy.
DARKEST = 176
LIGHTEST = 255


def find_client_jar():
    """The same jar the other extraction tools read; Gradle has to have run at least once."""
    candidates = glob.glob(os.path.join(ROOT, "build/jars/extra/client/*/client-extra.jar"))
    candidates += glob.glob(os.path.expanduser("~/.gradle/caches/ng_execute/*/client-extra.jar"))
    if not candidates:
        raise SystemExit("client-extra.jar not found; run a Gradle task first")
    return max(candidates, key=os.path.getmtime)


def greyscale(image):
    """Luminance, stretched to fill the band.

    Luminance rather than a plain channel average, because wood is strongly red and averaging the
    channels flattens exactly the contrast that makes it read as wood. Stretching afterwards is what
    puts it on the same footing as the sprites it will sit beside -- an unstretched dark oak converts
    to a narrow, dark band and every colour tinted through it comes out muddy.
    """
    pixels = image.convert("RGBA").load()
    width, height = image.size
    lum = {}
    for x in range(width):
        for y in range(height):
            r, g, b, a = pixels[x, y]
            lum[(x, y)] = (0.2126 * r + 0.7152 * g + 0.0722 * b, a)

    values = [v for v, a in lum.values() if a > 0]
    low, high = min(values), max(values)
    span = max(1.0, high - low)

    out = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    target = out.load()
    for (x, y), (value, alpha) in lum.items():
        stretched = DARKEST + (value - low) / span * (LIGHTEST - DARKEST)
        shade = int(round(max(DARKEST, min(LIGHTEST, stretched))))
        target[x, y] = (shade, shade, shade, alpha)
    return out


def main():
    os.makedirs(BLOCK, exist_ok=True)
    jar = find_client_jar()
    with zipfile.ZipFile(jar) as archive:
        for name, vanilla in SOURCE.items():
            path = "assets/minecraft/textures/block/%s.png" % vanilla
            with archive.open(path) as handle:
                source = Image.open(handle)
                source.load()
            image = greyscale(source)
            image.save(os.path.join(BLOCK, name + ".png"))
            px = image.load()
            values = [px[x, y][0] for x in range(image.width) for y in range(image.height)]
            print("%s.png: from %s, %dx%d, greyscale %d-%d, average %d"
                  % (name, vanilla, image.width, image.height,
                     min(values), max(values), sum(values) / len(values)))


if __name__ == "__main__":
    main()
