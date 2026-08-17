# Stonework Styles

Every vanilla stone texture, and what each one is worth to this mod.

A **style** is one greyscale sprite. The block keeps its own grains' colour, so a mottled block of
slate *is* slate — which is why styles are named for **what a mason did**, never for the rock vanilla
drew them on. See `core/Finish`, `client/FinishBakedModel`, and §9 of `CRAFTED_BLOCKS.md`.

This file exists because the next question is always "how many of these are there, and which are
actually different?" — and both answers are measurable rather than matters of taste. Everything
numeric below was measured against the 1.21.1 client jar by `tools/style_survey.py`.

---

## 1. What a style already costs, and what it already gets

**One sprite — or three, for a style whose ends differ.** Both halves of the multiplication are
already built:

| | how a new style gets it |
|---|---|
| Block, slab, stairs, wall — **rendering** | `FinishBakedModel` derives the finish onto whatever shape it is handed. A wall's model is assembled per state by multipart and has no file to write a counterpart to, so deriving is the only thing that *could* work. |
| Block, slab, stairs, wall — **recipes** | `SlabRecipe` and `CutShapeRecipe` already require every input to agree on a finish and carry it to the output. Cut a mottled block and you get mottled slabs today. |
| Moss, dye, fouling | Overlays wrap *outside* the finish, so they apply to whatever it drew. |
| Hammering back to grains | A finish is worked state, not material. Any style hammers back to its nine grains. |

So "walls, slabs and stairs for each texture" is **not** work to be done — it falls out. What a new
style costs is: one greyscale sprite, one `Finish` constant, one line in `gen_cut_recipes.py`, and
**eight lang keys** (four forms × plain and sole-grain) — and even those are generated, by
`gen_style_lang.py`, with `FinishNamingTest` failing the build if one is missing. **Twenty styles ship
today at 160 keys**, none of them written by hand.

### Sides, ends and underside

A style was one sprite for every face, and for most it still is: vanilla draws them `cube_all`, and a
Fine Tile really is the same tile on all six sides. Five are not, and drawing their side sprite on top
reads as a slab of bedding seen end-on:

| style | vanilla model | sides | ends |
|---|---|---|---|
| **Pebbled** | `cube_bottom_top` | `sandstone` | `sandstone_top`, and a distinct `sandstone_bottom` |
| **Squared** | `cube_column` | `cut_sandstone` | `sandstone_top` |
| **Chiseled Pebbled** | `cube_column` | `chiseled_sandstone` | `sandstone_top` |
| **Fine** | `cube_column` | `deepslate` | `deepslate_top` |
| **Chiseled Mottled** | `cube_column` | `chiseled_tuff` | `chiseled_tuff_top` |

So a style is **one sprite, two, or three** — never more, because a block has only three kinds of face
once the four sides are agreed to match. `cube_column` shares one sprite between top and bottom; only
sandstone draws a third, which is why Pebbled is the only style with its own underside.

The three sandstone-derived styles **share `pebbled_top`**, because vanilla gives all three
`sandstone_top` and they are one family in the cut graph — Squared and Chiseled Pebbled are cut and
carved *from* Pebbled. Sharing the sprite says that.

Which sprite a quad gets is decided from `BakedQuad.getDirection()`, the **world** direction — these
quads were collected after the blockstate rotated them. That is the same property that made overlays
and dye work on stairs and walls for free: a rotated stair's top face is still its top face, with no
per-shape knowledge anywhere.

**This was found by eye, and it had been shipping.** Timothy caught it on Pebbled the day it was added;
Fine and Chiseled Mottled had been drawing their sides on their tops since those styles were built, and
nothing had reported it. `FinishSpriteTest` now checks every sprite a finish names is actually on the
classpath — a mistyped name renders as the missing-texture chequer and logs nothing — and pins the
count of styles with distinct ends at five, so deleting one is a failure rather than a silent reversion.

The one real cost to watch is the **stonecutter menu**: every style cuttable from a given input is a
button in one list. That is a UI budget, not a technical one, and §5 is where it is spent.

---

## 2. The measurements

Each texture was reduced the way the pipeline reduces it — luminance, then contrast-normalised, so
**colour and brightness are gone** and only the pattern remains — then compared pairwise by RMS.
Across all 1596 pairs the median distance is **0.407**. Read that as the distance between two
unrelated patterns. Reproduce with `python3 tools/style_survey.py --deltas`.

### 2a. What collapses entirely

| pair | distance | meaning |
|---|---|---|
| `quartz_block_side` / `quartz_block_top` | **0.000** | the same sprite twice |
| `sandstone` / `red_sandstone` | **0.009** | one pattern, two colours |
| `cut_sandstone` / `cut_red_sandstone` | **0.010** | one pattern, two colours |

**The red variants are not styles.** Red sandstone *is* sandstone drawn in a redder rock, and in this
mod the rock's colour comes from its grains. Shipping both would be shipping the same sprite twice and
inviting the player to pick a colour that the material is supposed to decide. This is the clearest
vindication the design has had: vanilla needed two blocks where we need none.

Expect the same of any other `X` / `red_X` or `X` / `dark_X` pair added later — **measure before
shipping**.

### 2b. The "large polished" family is three patterns, not one

Distances within the polished cluster (median 0.407 for reference):

|  | p_granite | p_andesite | p_diorite | p_tuff | p_deepslate | smooth_stone |
|---|---|---|---|---|---|---|
| **polished_granite** | — | 0.172 | 0.251 | 0.169 | 0.221 | 0.394 |
| **polished_andesite** | 0.172 | — | 0.316 | 0.169 | 0.207 | 0.458 |
| **polished_diorite** | 0.251 | 0.316 | — | 0.297 | 0.347 | 0.323 |

Three things fall out:

- They are **genuinely distinct**, but closely related — 0.17–0.32 apart, where two shipped styles
  (`stone_bricks` vs `deepslate_bricks`) sit at 0.130. If that gap justifies two styles, this one does
  too.
- **`polished_diorite` is the outlier**, further from the other two (0.25, 0.32) than they are from
  each other (0.17). If only one of the three is taken, diorite is the one that adds something.
- **`polished_tuff` — which already ships as `POLISHED_MOTTLED` — sits between granite and andesite**,
  0.169 from both. So granite and andesite polished are each about as far from a style we already have
  as they are from each other. Adding all three risks three greys nobody can tell apart in a wall.

**Recommendation: take `polished_diorite` first**, and treat granite/andesite as optional. This is a
judgement call about how many near-identical greys are worth a menu entry, and it is Timothy's to
make — the numbers are here so it can be made on evidence.

`smooth_stone` is far from all of them (0.32–0.47) because it is nearly featureless. It is genuinely
its own thing, and already ships as `SMOOTH`.

### 2c. Cracked is a darkening — an overlay, confirmed

| base | pixels moved | of those, darker | mean shift |
|---|---|---|---|
| `stone_bricks` | 64 / 256 | 50 (78%) | −17.6 |
| `deepslate_bricks` | 48 / 256 | 47 (98%) | −28.3 |
| `deepslate_tiles` | 19 / 256 | 19 (100%) | −21.7 |
| `nether_bricks` | 26 / 256 | 24 (92%) | −18.2 |
| `polished_blackstone_bricks` | 46 / 256 | 41 (89%) | −21.2 |

A crack is **a small minority of pixels, made darker, and almost nothing else** — 19 to 64 pixels of
256, of which 78–100% darken, with a firmly negative mean shift. That is the definition of a layer
drawn on top, and it settles cracks as a `Coating` rather than a `Finish`: one crack layer over any
style, instead of one cracked sprite per style.

It also confirms the decision already taken in `next-stonecutter-and-cracks`: **generate the crack
network rather than extract it**. Vanilla's cracks trace the mortar lines of the brick pattern they
were drawn for, so an extracted crack layer would draw ghost bricks across a mottled or banded face.
Generated cracks align with nothing and are therefore correct on every style.

### 2d. Chiseled is **not** an overlay — it is a different drawing

This one came out against expectation and is worth stating plainly.

| chiseled | vs its base | pixels moved | of those, darker | mean shift | distance |
|---|---|---|---|---|---|
| `chiseled_tuff_bricks` | `tuff_bricks` | **193 / 256** | 90 (47%) | **+0.2** | 0.388 |
| `chiseled_polished_blackstone` | `polished_blackstone` | 172 / 256 | 84 (49%) | **+0.9** | 0.322 |
| `chiseled_tuff` | `polished_tuff` | 168 / 256 | 122 (73%) | −14.7 | 0.397 |
| `chiseled_deepslate` | `polished_deepslate` | 142 / 256 | 123 (87%) | −30.5 | 0.427 |
| `chiseled_stone_bricks` | `stone_bricks` | 132 / 256 | 74 (56%) | **−4.5** | 0.359 |
| `chiseled_sandstone` | `cut_sandstone` | 103 / 256 | 61 (59%) | −7.7 | 0.263 |
| `chiseled_nether_bricks` | `nether_bricks` | 87 / 256 | 31 (36%) | **+6.1** | 0.409 |

Between **34% and 75% of the face is redrawn**, at distances of 0.26–0.43 — at or above the 0.407
median that separates *unrelated* patterns.

The **mean shift** column is the sharpest part of it. Cracked pixels are 78–100% darker and move by
−18 to −28: a shadow cast over the surface. Chiseled pixels are around **half darker and half
lighter**, with a mean shift near **zero** — pixels moving in both directions in equal measure is what
*redrawing* looks like, not what shading looks like. Two of them come out net *brighter*.

**There is no chisel motif to lift off.** A chiseled block is not its base with a carving added; it is
a fresh design that happens to share a palette. So chiseled stays a **`Finish`**, one sprite per
family, exactly as `CHISELED_MOTTLED` and `CHISELED_FINE` already do.

There *is* a way to have the overlay version, and it is worth naming because it is a different
proposal rather than a failed one: **draw our own chisel motif from scratch** — a single authored
sprite, tinted rather than untinted (`OverlayBakedModel.retexture` already keeps a tint index, which
it learned to do for finishes), applied per face over any style. That gives "chiseled anything" for
one sprite instead of one-per-family. It is authoring work rather than extraction, and it would not
look like vanilla's. Both can coexist: vanilla's as finishes, ours as a coating.

---

## 3. The catalogue

**Shipped** = a `Finish` today. **Proposed** = a candidate with a name to approve. **No** = measured
or reasoned out.

### 3a. Rough surfaces — the rock as broken

| vanilla texture | style | status |
|---|---|---|
| `cobblestone` | **Cobbled** | shipped — the default, and the only one that shows the nine grains |
| `tuff` | **Mottled** | shipped |
| `calcite` | **Banded** | shipped |
| `deepslate` | **Fine** | shipped |
| `dripstone_block` | **Flowstone** | shipped |
| `sandstone` | **Pebbled** — a field of small angular stones | **shipped** |
| `basalt_side` | **Columnar** — the fibrous vertical grain | proposed |
| `end_stone` | **Nodular** — lumpy, no bedding | proposed |
| `netherrack` | **Pitted** — gnarled and porous | proposed |
| `granite` / `andesite` / `diorite` | **Speckled** — one of the three, they are the same idea | proposed, pick one |
| `dark_prismarine` | **Woven** — a tight crosshatch | proposed |
| `purpur_block` | — | no: near-identical in role to `Speckled` |
| `cobbled_deepslate`, `blackstone` | — | no: both are "broken rock", which is `Cobbled` |
| `red_sandstone` | — | **no: 0.009 from `sandstone`**, settled |
| `mud`, `packed_mud`, `muddy_mangrove_roots_*` | — | no: not stone |
| `prismarine` | — | no: 16×64, animated |

### 3b. Worked smooth

| vanilla texture | style | status |
|---|---|---|
| `smooth_stone` | **Smooth** | shipped — what a furnace produces |
| `polished_tuff` | **Polished Mottled** | shipped |
| `polished_deepslate` | **Polished Fine** | shipped |
| `polished_diorite` | **Polished** — the large flat polish | **shipped** *(the outlier; see §2b)* |
| `polished_granite`, `polished_andesite` | — | **no**, settled: 0.169 from `POLISHED_MOTTLED`, already shipped |
| `polished_blackstone`, `polished_basalt_side` | — | optional; same cluster |
| `cut_sandstone` | **Squared** — dressed flat and squared off | **shipped** |
| `quartz_block_side` | **Sugared** — fine even granularity | proposed |
| `cut_red_sandstone`, `quartz_block_top` | — | **no: 0.010 and 0.000 duplicates** |

### 3c. Brick bonds

Each is a genuinely different **bond** — the pattern the courses are laid in — and they sit 0.13–0.21
apart, which is the same spacing as two styles already shipped.

| vanilla texture | style | status |
|---|---|---|
| `deepslate_bricks` | **Fine Bricks** | shipped |
| `tuff_bricks` | **Mottled Bricks** | shipped |
| `deepslate_tiles` | **Fine Tiles** | shipped |
| `stone_bricks` | **Bricks** — the plain running bond | **shipped** |
| `nether_bricks` | **Small Bricks** — the tight courses of a fortress | **shipped** |
| `red_nether_bricks` | **Herringbone** — a genuinely different bond, 0.141 away | proposed |
| `polished_blackstone_bricks` | **Ashlar** — large squared blocks | proposed |
| `end_stone_bricks` | **Rubble** — irregular courses | proposed |
| `prismarine_bricks` | **Basket** — square panels | proposed |
| `quartz_bricks` | **Panelled** | proposed |
| `mud_bricks`, `bricks` | — | no: clay and mud, not stonework |

### 3d. Chiseled — finishes, one per family (§2d)

| vanilla texture | style | status |
|---|---|---|
| `chiseled_tuff` | **Chiseled Mottled** | shipped |
| `chiseled_deepslate` | **Chiseled Fine** | shipped |
| `chiseled_stone_bricks` | **Chiseled Bricks** | **shipped** |
| `chiseled_sandstone` | **Chiseled Pebbled** | **shipped** |
| `chiseled_nether_bricks` | **Chiseled Small Bricks** | **shipped** |
| `chiseled_polished_blackstone` | **Chiseled Ashlar** | proposed |
| `chiseled_quartz_block` | **Chiseled Panelled** | proposed |
| `chiseled_tuff_bricks` | **Chiseled Mottled Brick** | proposed |
| `chiseled_red_sandstone` | — | **no: the red twin of `chiseled_sandstone`** |

### 3e. Not styles at all

| vanilla texture | why |
|---|---|
| `cracked_*` (5) | a darkening of a few pixels — a `Coating`, §2c |
| `mossy_cobblestone`, `mossy_stone_bricks` | already an overlay; that is what `Moss` is |
| `stone` | natural stone's own base, derived from grains, not a worked state |
| `*_ore`, `gilded_blackstone`, `nether_quartz_ore` | ore is composition, not surface |
| `smooth_basalt` | inside `Smooth`'s tolerance |
| `reinforced_deepslate_*`, `lodestone_*`, `grindstone_*` | block-specific machinery, not a stonework style |

---

## 4. Counting it

| | styles |
|---|---|
| shipped | **20** (+ `Cobbled`) |
| still proposed | 16 |
| **total if all are taken** | **36 (+ `Cobbled`)** |

At eight lang keys each, all sixteen more would take the file to ~288 keys — all generated, all
enforced by `FinishNamingTest`. The sprites are one greyscale extraction apiece and the `Finish` enum
is a flat list, so nothing remaining here is technically difficult.

**The stonecutter menu is where it hurts**, and §5 is the answer: one axis per cut, so the list is
additive. Twenty styles is currently **11 buttons** in front of a smooth block, not 80.

---

## 5. The cut graph — one axis per cut

A composite has **two** axes: its `Finish` (data on the item) and its `Form` (a separate registered
block — a slab must extend `SlabBlock` to inherit merging, so forms will always be blocks). The
stonecutter offers a move along **one of them per cut**, and that choice is what keeps the menu
usable.

The alternative was every combination: ten styles × four shapes is forty buttons in a menu with room
for twelve, growing by four with every style added. One axis per cut makes the list **additive** — the
styles this surface can become, plus the shapes this form can become. Chain two cuts to move along
both, in either order, because a style cut is defined on *every* form:

```
mottled wall  =  block ─> mottled ─> wall
              or block ─> wall    ─> mottled
```

### The style axis

A family head is reachable from smooth; a family's own workings only from within that family. Both a
smaller menu and a truer account of masonry — you do not chisel a brick bond out of rubble, you lay
the bricks and then carve them.

```
smooth ─┬─> mottled ──┬─> polished_mottled
        │             ├─> mottled_bricks
        │             └─> chiseled_mottled
        ├─> banded
        ├─> fine ─────┬─> polished_fine
        │             ├─> fine_bricks
        │             ├─> fine_tiles
        │             └─> chiseled_fine
        └─> flowstone
```

**Keep that shape.** Adding the proposed styles under the same rule gives roughly ten families of
three to four, and no menu longer than today's.

### The shape axis, and the staircase that had to be repriced

A stonecutter consumes **exactly one** input however many it yields, so unlike a crafting recipe there
is no pattern to price it with. `Form.grains()` is the arithmetic:

| cut | grains out | of | verdict |
|---|---|---|---|
| block → 2 slabs | 8 | 9 | fine |
| block → 1 stair | 9 | 9 | break-even |
| block → 1 wall | 9 | 9 | break-even |

**The stair was the exception, and the fix was upstream of the cut.** Vanilla's staircase yields four
from six, and we had inherited the loss — so a stair cost a block and a half and was therefore *worth*
thirteen grains. That made it the one shape worth more than the stone it came from, and made
`block → 1 stair` a grain press, so the cut had to be withheld while every other shape had one.

Rather than manage the exception, **our staircase yields six from six**. A stair now costs exactly one
block, which is what a full-height shape should cost and what a wall already cost; `STAIRS_GRAINS`
drops from 13 to 9 and the cut becomes legal on its own arithmetic. This is the only place the mod
deliberately diverges from a vanilla ratio, and the anomaly it removes is why.

**Two of the three are now exact, so the shape axis has no headroom at all.** Raising any form's grains,
or any cut's count, starts paying out immediately. Both guards were broken on purpose to confirm they
fire: `gen_cut_recipes.py` refuses to *write* a paying cut, and `ConservationTest` walks the **shipped
files** — the guard that also catches a hand-written recipe, a datapack, or an edited generator.

### Why shape cuts are written per finish

One any-finish recipe per shape would work mechanically, but the menu draws `getResultItem()` — so
every shape button would preview a *cobbled* slab regardless of what you inserted. One recipe per
(finish, shape) costs nothing, since they are generated, and every button previews what it will
actually make. The input filter means only the current finish's few are ever on screen.

Today: **76 style cuts** (19 × 4 forms) + **63 shape cuts** (21 finishes × 3 shapes) = 139 files, and
**11 buttons** in front of a player holding a smooth block.

---

## 6. Adding one — the five steps, in order

1. **Measure it first.** `python3 tools/style_survey.py`. Anything under ~0.05 from a shipped style is
   the same pattern and must not ship; the red variants are the worked example. **Do this before
   authoring anything** — it is the step that saves a sprite, eight keys and a menu entry.
2. **Name it for the work, not the rock**, and name it from the **greyscale**. A mottled block of slate
   *is* slate, so `Tuff` is a claim this mod never makes. And colour misleads: `sandstone` reads as
   bedding in yellow and as a field of small stones in grey, which is why it shipped as **Pebbled**
   rather than "Bedded".
3. **`tools/extract_textures.py`** — one line in `STYLES`, mapping the style name to its vanilla
   texture. Writes `<name>_base.png` into `textures/block/`, which vanilla's `directory` atlas source
   stitches automatically; no atlas entry and no model reference is needed.
4. **`core/Finish`** — one constant carrying that sprite. Then **`tools/gen_style_lang.py`** for the
   eight names and **`tools/gen_cut_recipes.py`** for its cuts. Never write lang keys by hand: the
   wording rules are subtle enough to drift, and drift is invisible until a player reads it.
5. **Put it in the cut graph under its family** in `FAMILIES`, not on the list hanging off smooth —
   that is what keeps the menu additive.

Then run the client and read the count: `Finish-capable models: N variants across M finishes` should
show your new total. Rendering fails silently, so the number is the check.
