#!/usr/bin/env python3
"""Generate every stonework-style name, for every form, in all three wordings.

Run offline. Rewrites the style keys in `assets/granularity/lang/en_us.json` in place and leaves every
other key alone, so it is safe to run over a file with hand-written entries in it.

**Why a generator.** A style needs twelve keys -- four forms, each in three wordings -- and
`FinishNamingTest` fails the build if one is missing. Twelve styles was 96 keys written by hand; the
styles catalogued in docs/STONEWORK_STYLES.md would take it past 280. That is not work for a person,
and more to the point it is not work a person does *consistently*: the wording rules below are subtle
enough that hand-written keys drift, and drift is invisible until a player reads it.

**The three wordings.** A block of one stone is named after that stone; a mixed block is not; and a
dyed one needs somewhere to put the colour:

    block.granularity.cobblestone.smooth         "Smooth Stone"
    block.granularity.cobblestone.smooth.named   "Smooth %s"        -> "Smooth Slate"
    block.granularity.cobblestone.smooth.dyed    "Smooth %s Stone"  -> "Smooth Red Stone"

The dyed wording exists because English orders its adjectives: a colour goes after a quality and
immediately before the material, so "Smooth Red Basalt" and never "Red Smooth Basalt". A *named* block
gets that free -- the colour rides into the blank alongside the stone -- but a mixed one has no blank,
so this cuts one in the same place. See CompositeBlockItem.getName.

**The rules, read off the keys that already shipped.**

1. A style with no noun of its own describes the stone, so the plain wording says "Stone" where the
   sole-grain wording puts the rock:  "Banded Stone" / "Banded %s".
2. A style that *is* a noun replaces it instead, and the rock goes between:  "Fine Bricks" /
   "Fine %s Bricks".
3. Before a form word the noun goes singular, exactly as vanilla writes "Stone Brick Slab":
   "Fine Brick Slab" / "Fine %s Brick Slab".
4. A prefix that is itself a stone noun does not get "Stone" after it:  "Flowstone", not
   "Flowstone Stone".
"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "granularity", "lang", "en_us.json")

# The four forms a finish can wear, as (block id, the word that ends its name).
FORMS = [("cobblestone", None), ("cobblestone_slab", "Slab"),
         ("cobblestone_stairs", "Stairs"), ("cobblestone_wall", "Wall")]

# style id -> (words before the rock, plural noun or None, that noun in the singular, prefix_is_noun)
#
# Order matches core/Finish so the two can be read side by side.
STYLES = {
    "smooth":                ("Smooth", None, None, False),

    "mottled":               ("Mottled", None, None, False),
    "polished_mottled":      ("Polished Mottled", None, None, False),
    "mottled_bricks":        ("Mottled", "Bricks", "Brick", False),
    "chiseled_mottled":      ("Chiseled Mottled", None, None, False),

    "banded":                ("Banded", None, None, False),

    "fine":                  ("Fine", None, None, False),
    "polished_fine":         ("Polished Fine", None, None, False),
    "fine_bricks":           ("Fine", "Bricks", "Brick", False),
    "fine_tiles":            ("Fine", "Tiles", "Tile", False),
    "chiseled_fine":         ("Chiseled Fine", None, None, False),

    # Rule 4: "Flowstone", never "Flowstone Stone".
    "flowstone":             ("Flowstone", None, None, True),

    "pebbled":               ("Pebbled", None, None, False),
    "chiseled_pebbled":      ("Chiseled Pebbled", None, None, False),
    "squared":               ("Squared", None, None, False),
    # No prefix at all: "Bricks" / "%s Bricks" / "Brick Slab" -- which reads as vanilla's own
    # "Stone Brick Slab" once the rock is substituted in.
    "bricks":                ("", "Bricks", "Brick", False),
    "chiseled_bricks":       ("Chiseled", "Bricks", "Brick", False),
    "small_bricks":          ("Small", "Bricks", "Brick", False),
    "chiseled_small_bricks": ("Chiseled Small", "Bricks", "Brick", False),
    "polished":              ("Polished", None, None, False),
}


def words(*parts):
    """Joined with single spaces, dropping the empty ones -- a styleless prefix must not double up."""
    return " ".join(part for part in parts if part)


# The three wordings a style needs.
#
#   PLAIN  a block of mixed stone, undyed          "Smooth Stone"
#   NAMED  a block of one stone                    "Smooth %s"        -> "Smooth Slate"
#   DYED   mixed stone, dyed all over              "Smooth %s Stone"  -> "Smooth Red Stone"
#
# DYED exists because English orders its adjectives and a colour belongs *after* a quality and
# immediately before the material: "Smooth Red Basalt", never "Red Smooth Basalt". A named block gets
# that for free -- the colour rides along with the stone into the blank NAMED already has. A mixed one
# has no blank to ride into, so this cuts one in the same place.
PLAIN, NAMED, DYED = "plain", "named", "dyed"


def name(style, form_word, wording):
    """One name, in one of the three wordings above."""
    prefix, noun, singular, prefix_is_noun = STYLES[style]
    # The blank stands where the material goes, and is simply absent from the plain wording.
    blank = "" if wording is PLAIN else "%s"

    if noun:
        # Rule 2 and 3: the noun replaces "Stone", and goes singular before a form word. It is itself
        # the material word, so DYED and NAMED land in the same place and read alike.
        head = singular if form_word else noun
        return words(prefix, blank, head, form_word)
    if prefix_is_noun:
        # Rule 4: the prefix is already a stone, so the blank goes in front of it -- "Red Flowstone".
        return words(blank, prefix, form_word)
    # Rule 1: the style describes the stone. A named block puts its own rock where "Stone" was; a
    # dyed mixed one keeps "Stone" and takes the colour just before it.
    return words(prefix, blank, "Stone" if wording is not NAMED else "", form_word)


def main():
    with open(LANG) as fh:
        lang = json.load(fh)

    prefixes = tuple(f"block.granularity.{block}." for block, _ in FORMS)
    known = set(STYLES)
    # Drop only the keys this script owns: a style suffix on one of the four forms. Anything else in
    # the file -- the cobbled base names, items, config, the stonecutter -- is left untouched.
    stale = [key for key in lang
             if any(key.startswith(p)
                    and key[len(p):].removesuffix(".named").removesuffix(".dyed") in known
                    for p in prefixes)]
    for key in stale:
        del lang[key]

    written = 0
    for block, form_word in FORMS:
        for style in STYLES:
            base = f"block.granularity.{block}.{style}"
            lang[base] = name(style, form_word, PLAIN)
            lang[f"{base}.named"] = name(style, form_word, NAMED)
            lang[f"{base}.dyed"] = name(style, form_word, DYED)
            written += 3

    with open(LANG, "w") as fh:
        json.dump(dict(sorted(lang.items())), fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"{written} style keys across {len(STYLES)} styles x {len(FORMS)} forms "
          f"({len(stale)} replaced), {len(lang)} keys in the file")
    print(f"  {'style':<22s} {'plain':<22s} | {'named':<20s} | {'dyed':<22s} | named slab")
    for style in ("banded", "fine_bricks", "flowstone", "bricks", "small_bricks", "polished"):
        print(f"  {style:<22s} {name(style, None, PLAIN):<22s} | {name(style, None, NAMED):<20s} "
              f"| {name(style, None, DYED):<22s} | {name(style, 'Slab', NAMED)}")


if __name__ == "__main__":
    main()
