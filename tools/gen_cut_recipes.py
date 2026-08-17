#!/usr/bin/env python3
"""Generate every stonecutter cut, and check that none of them pays.

Run offline. Writes `data/granularity/recipe/cut_*.json`, deleting the ones it previously wrote first,
so removing a style here removes its recipes.

**The shape of the graph is the design.** The stonecutter shows what the *input* can become, and offers
one axis per cut -- the styles reachable from this surface, plus the shapes reachable from this form.
That keeps the menu additive rather than multiplied: ten styles and four shapes is fourteen buttons,
not forty, and it grows by one rather than by four when a style is added. The player chains two cuts to
move along both axes, and because a style cut is defined on *every* form, either order works. See
docs/STONEWORK_STYLES.md section 5.

**Why shape cuts are written per finish** rather than as one recipe accepting any finish. The menu
draws `getResultItem()`, so a single any-finish recipe would show a cobbled slab as the preview no
matter what you had inserted. One recipe per (finish, shape) costs nothing -- they are generated -- and
every button then previews the thing it will actually make. The input filter means only the current
finish's few are ever on screen.

**The conservation rule.** A stonecutter consumes exactly one input however many it yields, so a shape
cut can pay. Grains per form: block 9, slab 4, stairs 9, wall 9.

    block -> 2 slabs   8 of 9   fine
    block -> 1 stair   9 of 9   break-even
    block -> 1 wall    9 of 9   break-even

Stairs were the exception until the staircase recipe was fixed: at vanilla's four-from-six a stair cost
a block and a half and so was *worth* 13 grains, which made this one cut a grain press and it had to be
withheld. Six-from-six prices a stair at one block and the exception went away.

Three of these are now break-even, which means there is **no slack left** in the shape axis. Raising any
form's grains, or any cut's count, starts paying out immediately -- so this script refuses to write a
paying cut, and ConservationTest re-checks the shipped files in case one arrives another way.
"""
import glob
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..")
RECIPES = os.path.join(ROOT, "src", "main", "resources", "data", "granularity", "recipe")

# Must match CompositeShapes and Form.grains().
#
# `stairs` was 13 here, because our staircase recipe copied vanilla's lossy four-from-six and a stair
# therefore cost a block and a half. That made it the one shape worth more than the stone it came from,
# and made block -> 1 stair a grain press, so the cut had to be withheld. Yielding six from six prices
# a stair at one whole block -- what a full-height shape should cost, and what a wall already cost --
# and the exception disappeared rather than being managed.
GRAINS = {"block": 9, "slab": 4, "stairs": 9, "wall": 9}

# The style graph: a family head is reachable from smooth, and a family's own workings only from that
# family. Both a smaller menu and a truer account of masonry -- you do not chisel a brick bond out of
# rubble, you lay the bricks and then carve them.
FAMILIES = {
    "smooth": ["mottled", "banded", "fine", "flowstone",
               "pebbled", "bricks", "small_bricks", "polished"],
    "mottled": ["polished_mottled", "mottled_bricks", "chiseled_mottled"],
    "fine": ["polished_fine", "fine_bricks", "fine_tiles", "chiseled_fine"],
    # Pebbled is sedimentary rock as found; squaring it off and carving it are what you then do.
    "pebbled": ["squared", "chiseled_pebbled"],
    "bricks": ["chiseled_bricks"],
    "small_bricks": ["chiseled_small_bricks"],
}

# Every finish that exists as a surface you could be holding, and so every finish whose shapes have to
# be cuttable. Must match core/Finish.
ALL_FINISHES = ["cobbled", "smooth",
                "mottled", "polished_mottled", "mottled_bricks", "chiseled_mottled",
                "banded",
                "fine", "polished_fine", "fine_bricks", "fine_tiles", "chiseled_fine",
                "flowstone",
                "pebbled", "chiseled_pebbled", "squared",
                "bricks", "chiseled_bricks",
                "small_bricks", "chiseled_small_bricks",
                "polished"]

# Shape cuts, as (from form, to form, how many). All three shapes now, since a stair costs a block.
SHAPE_CUTS = [("block", "slab", 2), ("block", "stairs", 1), ("block", "wall", 1)]


def conserves(from_form, to_form, count):
    return GRAINS[to_form] * count <= GRAINS[from_form]


def write(name, recipe):
    with open(os.path.join(RECIPES, f"{name}.json"), "w") as fh:
        json.dump(recipe, fh, indent=2)
        fh.write("\n")


def main():
    for stale in glob.glob(os.path.join(RECIPES, "cut_*.json")):
        os.remove(stale)

    styles = 0
    for source, targets in FAMILIES.items():
        for target in targets:
            # A style cut is 1:1 on the same form, so it cannot touch conservation at all -- and it is
            # written for every form, which is what makes the two-cut chain work in either order.
            for form in ("block", "slab", "stairs", "wall"):
                suffix = "" if form == "block" else f"_{form}"
                recipe = {"type": "granularity:stone_cut", "from": source, "to": target}
                if form != "block":
                    recipe["from_form"] = form
                    recipe["to_form"] = form
                write(f"cut_{source}_to_{target}{suffix}", recipe)
                styles += 1

    shapes = 0
    for from_form, to_form, count in SHAPE_CUTS:
        if not conserves(from_form, to_form, count):
            raise SystemExit(
                f"refusing to write {from_form} -> {count} {to_form}: "
                f"{GRAINS[to_form] * count} grains out of {GRAINS[from_form]} in, which pays")
        for finish in ALL_FINISHES:
            # The finish rides through unchanged: reshaping is not resurfacing.
            write(f"cut_{finish}_{from_form}_to_{to_form}", {
                "type": "granularity:stone_cut",
                "from_form": from_form, "from": finish,
                "to_form": to_form, "to": finish,
                "count": count})
            shapes += 1

    print(f"{styles} style cuts across 4 forms, {shapes} shape cuts across "
          f"{len(ALL_FINISHES)} finishes -- {styles + shapes} files")
    for from_form, to_form, count in SHAPE_CUTS:
        print(f"  {from_form} -> {count} {to_form}: "
              f"{GRAINS[to_form] * count} grains of {GRAINS[from_form]}")
    print("\nMenu size for a smooth block: "
          f"{len(FAMILIES['smooth'])} styles + {len(SHAPE_CUTS)} shapes = "
          f"{len(FAMILIES['smooth']) + len(SHAPE_CUTS)} buttons")


if __name__ == "__main__":
    main()
