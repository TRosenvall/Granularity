#!/usr/bin/env python3
"""Split a full-cube block model into an upper and a lower half, so each can be costumed on its own.

Run offline, never by the game. Rewrites the model files in place.

**Why.** A furnace is one region of stone as far as the models are concerned, so dressing it "by part"
is the same as dressing the whole thing -- there is nothing to choose between. Splitting it in two
gives it a top and a bottom that can wear different rock, which is the division a furnace visibly
already has: its top texture and its side texture are near enough aligned at the halfway line that
cutting there costs nothing.

**How it stays identical when nothing is dressed.** The geometry is the same cube; every element is
cut at y=8 into two boxes drawing the same pixels they drew before. A side face's UV is narrowed to
the half of the sprite it now covers -- the lower box takes v 8..16 and the upper takes v 0..8 -- and
the down and up faces go to the box that owns them. Cullfaces stay valid because both halves still
reach the block's edge.

**Tint indices are the whole point.** The upper half's stone moves into the 10-19 band, which already
means "the second half's surface" everywhere else in this mod, so the renderer, the colour handler and
the overlay wrapper all understand it without being told. The front face keeps tint 22 in both halves
and is therefore never dressed: a furnace door is how you read the block, and covering it up is not
decoration.
"""
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..")
MODELS = os.path.join(ROOT, "src", "main", "resources", "assets", "granularity", "models", "block")

# Where the cut goes, in model space. Half a block.
SEAM = 8

# The tint band meaning "the upper half's surface". Must match CompositeBlockColour.UPPER_BASE.
UPPER_BASE = 10

# The one tint that is not stone and must not move: the furnace door, the observer's eye.
PLAIN_TINT = 22

SIDES = ("north", "south", "west", "east")


def halve(element):
    """One full-cube element as a lower box and an upper box."""
    lower = {"from": [0, 0, 0], "to": [16, SEAM, 16], "faces": {}}
    upper = {"from": [0, SEAM, 0], "to": [16, 16, 16], "faces": {}}

    for name, face in element["faces"].items():
        if name == "down":
            lower["faces"]["down"] = dict(face)
            continue
        if name == "up":
            upper["faces"]["up"] = dict(face)
            upper["faces"]["up"]["tintindex"] = raised(face.get("tintindex"))
            continue
        if name not in SIDES:
            continue

        # A side face now covers half the sprite. v grows downward, so the lower box is the bottom
        # half of the picture and the upper box is the top half -- getting these the wrong way round
        # would flip the texture rather than fail, which is exactly the sort of thing only a person
        # can see.
        bottom = dict(face)
        bottom["uv"] = [0, SEAM, 16, 16]
        lower["faces"][name] = bottom

        top = dict(face)
        top["uv"] = [0, 0, 16, SEAM]
        top["tintindex"] = raised(face.get("tintindex"))
        upper["faces"][name] = top

    return lower, upper


def raised(tint):
    """A tint index moved into the upper half's band, leaving the non-stone ones where they are."""
    if tint is None or tint == PLAIN_TINT:
        return tint
    return tint + UPPER_BASE


def split(path):
    with open(path) as handle:
        model = json.load(handle)

    elements = model.get("elements", [])
    if not elements:
        raise SystemExit("%s has no elements to split" % path)
    for element in elements:
        if element["from"] != [0, 0, 0] or element["to"] != [16, 16, 16]:
            raise SystemExit("%s is not a stack of full cubes; refusing to guess" % path)

    halves = []
    for element in elements:
        lower, upper = halve(element)
        halves.append(lower)
        halves.append(upper)
    model["elements"] = halves

    with open(path, "w") as handle:
        json.dump(model, handle, indent=2)
        handle.write("\n")

    tints = sorted({face.get("tintindex") for part in halves for face in part["faces"].values()
                    if face.get("tintindex") is not None})
    print("%s: %d elements -> %d halves, tints %s"
          % (os.path.basename(path), len(elements), len(halves), tints))


def main():
    names = sys.argv[1:] or ["furnace", "furnace_on"]
    for name in names:
        split(os.path.join(MODELS, name + ".json"))


if __name__ == "__main__":
    main()
