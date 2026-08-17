# Crafted Blocks

**Design: Timothy Rosenvall, 2026-08-12. Supersedes parts of Phase 4 as first built. Companion to
[MATERIAL_ROSTER.md](MATERIAL_ROSTER.md) and [BLOCK_COVERAGE.md](BLOCK_COVERAGE.md).**

Stone, ore, precious ore and gem form a subset with its own arithmetic. This is that arithmetic.

---

## 1. Combining

Shapeless *within a square* — it does not matter which grains go where, only that they fill one.

| grains | shape | gives |
|---|---|---|
| 4 | any 2×2 square | **gravel** |
| 9 | the full 3×3 | **cobblestone** |

The 2×2 works in the player's inventory grid as well as on a table, so gravel is makeable without a
crafting table. Only the four mineral classes combine this way.

## 2. Naming is arithmetic too

| composition | name |
|---|---|
| all 4 or all 9 grains the same | **"Granite Cobblestone"**, **"Iron Gravel"** |
| anything mixed | plain **"Cobblestone"**, plain **"Gravel"** |

A single grain type earns a name. A mixture does not, **and nearness does not count**: five chalk
and four shale average to something very close to diorite, and it is still just "Cobblestone". The
name follows what the block is made of, never what it happens to resemble.

This is the rule the first implementation got wrong — it snapped the average to the nearest named
stone and produced "nine diorite" from a chalk/shale mix.

## 3. What smelting produces

Smelting does **not** change the grains. It changes the block, and how that block presents them.

| the cobblestone is | smelting gives | renders |
|---|---|---|
| all rock | **Smooth Stone** | averaged into one colour |
| rock + any mineral | **Ore Block** | like the ore block you originally mined |
| all mineral, no rock | **Alloy Block** | averaged into one colour |

The middle row closes a loop worth noticing: mine an iron ore block, get eight rock grains and one
iron, recombine and smelt, and you have the ore block back. The material system round-trips.

Naming follows §2 throughout — a smelted single-grain block is "Granite Smooth Stone", a mixed one
is just "Smooth Stone".

## 4. Rendering: the light areas are the stones

Both crafted blocks need regions that accept a colour overlay, and they differ in kind.

**Cobblestone** has well-defined lighter areas separated by darker lines. **Those lighter areas are
the individual stones**, and each becomes its own overlay. Eight rock grains and one diamond means
one of the light areas carries the diamond's colour — you can see the diamond sitting in the
cobble.

That is what makes cobblestone the block that "shows the constituent colours as distinct rocks"
(design §3), against natural stone which averages. Two reductions of the same composition.

**Gravel** is a muddle. Which pixels a grain claims does not matter, so any partition of the
texture will do.

## 5. Grains are the items they already are

**A grain is backed by an existing item.** The grain for iron ore is vanilla's raw iron; the grain
for a diamond is the diamond. Registration therefore takes three things:

```
register(item, colour, class)
```

— the item to use as the grain, a specific hex colour, and which of the four classes it belongs to.
Adding ruby is `register(rubyItem, 0x9B111E, GEM)` and nothing else.

Rock grains have no vanilla item to borrow, so those are ours. Everything with a vanilla equivalent
uses it, which removes a whole parallel set of items and makes the mod interoperate with recipes
that already exist.

## 6. Creative tab

Only **one gravel and one cobblestone**, with a random composition and colour. Registering an entry
per possible composition is item bloat with no upside — a player who wants a specific mix crafts it,
or uses a command. Slightly more effort, much smaller registry.

## 7. What this reworks

| area | status |
|---|---|
| `Composition.smelted()` snapping to the nearest stone | **wrong — remove** |
| Grain backed by an item, registered with item + hex + class | to build |
| 2×2 gravel recipe | to build |
| Naming math, single-grain versus mixed | to build |
| Three smelt outcomes: smooth stone / ore block / alloy block | to build |
| Cobblestone per-stone overlays on the light areas | to build |
| Gravel overlays, any partition | to build |
| Creative tab: one generic each, random composition | to build |

---

## 8. Dyeing — built, per face

**Sneak + right-click with the dye, and the face you clicked takes the colour.** Two mechanisms were
considered and one was chosen.

**Hold a dye, right-click the block.** No new items, discoverable, matches how vanilla dyes sheep.
The objection is accidents — a stray click on a finished wall.

**A dedicated dye tool, loaded with dyes, that applies on right-click.** Deliberate, no accidents,
and it could hold charges so dyeing a wall is not one click per block. Costs an item, a recipe and a
UI, and Timothy is "not the biggest fan".

Sneaking gets the anti-accident property without the extra item: it is already the game's idiom for
"I meant this", dye is not placeable so nothing competes for the input, and a stray click on a wall
does nothing.

### Per face, one dye per face

Dye is stored the way overlays are — six slots, one per `Direction` — because a wall whose south side
is red and whose top is grey is a thing people build, and it costs nothing to allow once the renderer
is already deciding quads one at a time. A whole block is six clicks and six dyes. That is the same
bargain moss and scraping already strike, and the price of being able to paint one side of a wall.

The render route is worth knowing, because it is not obvious. Vanilla's tint API is
`getColor(state, level, pos, tintIndex)` — **there is no face in it**, and no way to add one. So a
colour handler can never be asked "what colour is the north side". What it *can* be asked about is a
tint index, and `OverlayBakedModel` is already rebuilding quads and choosing their indices; so a
matrix quad on a dyed face is re-issued at tint index `30 + face`, and the handler answers those six
from the six slots. `BakedQuad.getDirection()` is the **world** direction — the quads were collected
after the blockstate rotated them — so stairs and walls came along free, exactly as overlays did.

Two alternatives were rejected. *Six tint indices baked into the models* fixes them pre-rotation, so
a rotated stair's dye rotates with it. *The colour written into the quads' vertex data* works, but
means getting a packed int's byte order right at a vertex offset for no gain over rewriting an index.

If dyeing in bulk turns out tedious, a brush that holds charges, or a crafting-grid recipe that dyes
a block's item form the way vanilla dyes a shulker, can be added later *on top* of this without
changing what dyeing means.

### Clearing a face: hold a brush against it

**Hold a brush on a coated face and it comes clean after twenty ticks.** One face per go, one point of
brush wear, no sneak — the same asymmetry growing it has, and the reason a mossed-over furnace is
something you deal with rather than something you undo.

**Why the brush and not a sword.** A blade did it first, on the strength of the stripping-a-log
gesture, and it was wrong twice over: a sword is the one tool a player holds for a reason that has
nothing to do with housekeeping, so cleaning a wall meant putting your weapon away afterwards — and it
quietly made every sword a cleaning implement, a claim about swords this mod had no business making.
A brush already means "take the covering off and leave what is underneath", which is the verb exactly.

**Why the gesture and not just the item.** An instant click with a brush was worse than an instant
click with a sword, for a reason worth keeping: a sword *is* an instant-swing tool, so an instant
result reads correctly, whereas everyone who has met vanilla's brush has met it as something you
*hold*. The right tool doing the wrong motion is uncannier than the wrong tool.

Almost all of it is vanilla's. `BrushItem.onUseTick` already spawns dust for **any** block and plays
`BRUSH_GENERIC` for anything that is not suspicious sand, on a ten-tick beat, and already releases the
item when the player stops looking at a block. Starting the use buys all of that; only the ending had
to be written. Twenty ticks lands just after the second stroke rather than cutting one short.

Taking the click in `CompositeShapes.interact` rather than letting it fall through to `BrushItem` is
what makes any of it possible: a block's own `useWithoutItem` runs *before* the item's `useOn`, so a
brush aimed at a mossy furnace would otherwise open the furnace and never brush anything. A brush on a
*clean* furnace still opens it, which is the behaviour worth keeping.

**This is the safe version of a mechanic that failed once.** Wearing moss off by *mining* was tried and
reverted: it fought the client's block-break prediction, which destroys the client's block entity
before the server has any say, so a cancelled break left the block drawing as plain cobblestone. A
**use** has no such prediction — nothing is destroyed, nothing is predicted, and the only thing that
changes is a coating the server syncs the ordinary way.

### The constraint that matters more than the mechanism

**Dye only ever touches the matrix, never the nine stones.** That is already how it is built, and it
should stay that way, because design §3 is pointed on the subject:

> There's no dye step and no palette to unlock — just arithmetic on what you dug.

The colour system exists so that dye is *not* how you get a shade. Mine across a boundary, smelt in
batches, and the gradient falls out of the material. A general "every block is dyeable" would compete
directly with that loop — why quarry for colour if a dye does it?

Confining dye to the matrix keeps both: the block still shows exactly what it is made of in its nine
stones, and the builder gets control of the mortar between them. It is an escape hatch for the case
the arithmetic genuinely cannot serve — nine different grains average to something hueless, and no
saturation can rescue a colour that has no hue — rather than a replacement for the system.

---

## 9. Stoneware — vanilla blocks rebuilt on grains

Six of them so far: **lever, furnace, dispenser, dropper, observer, piston**, plus the **stonecutter**.
Each is the vanilla block, extended, `implements CompositeStone`, holding a `CompositeBlockEntity`.
The step-by-step is in `StonewareRecipe`, `CompositeShapes` and `tools/gen_shape_models.py`; what
belongs here is the rule that decides how one *looks*.

**The whole block takes the averaged tint; its dimpling shows the individual grains.** That is
Timothy's spec and the reason these are worth building: a furnace made of eight grains has eight
nameable dimples, "almost a default cobble texture but with a sharp corner edging". `N` is **what the
recipe costs**, not nine — piston 4, observer 6, dropper 7, dispenser 7, furnace 8.

**Not every material on a stoneware block is stone.** A piston is three: stone body, timber plate,
metal fittings, at tint indices 0, `WOOD_TINT` and `METAL_TINT`. A stonecutter is four — two *different*
stones, timber and metal. Tinting a piston wholly from its composition made a spruce one look like
slate. When a face is not stone, ask what the *recipe* put there and colour it from that; the rule
generalises to "one slot of the recipe, one source of colour".

### The stonecutter is the odd one out, deliberately

It carries **zero grain layers** and **four materials**, one per slot of its recipe:

```
 B      the bar         -> METAL_TINT    the saw blade
#L#     the left rock   -> tintindex 0   the bench below the wooden rail, and its underside
        the log         -> WOOD_TINT     the frame: legs, rail, corner brackets
        the right rock  -> UPPER_BASE    the strip above the rail, and the working surface on top
```

No dimples, because it is built from **smooth stone** and smooth is precisely the finish in which a
block has stopped showing its grains separately. Drawing nine stones on a bench made of stone that no
longer has visible stones in it would contradict its own recipe.

**Two stones is the point, and vanilla's own art asked for it.** `stonecutter_side` is a wooden frame
with a full-width rail across rows 9–10, stone above it and stone below — the block is already drawn
as two stone sections with timber between them. So a player builds it from two rocks and sees both.
Nothing had to be invented for this: `UPPER_BASE` (tints 10–19) is the index a double slab's upper
half already uses, and *one block, two compositions* is a concept this codebase had before the
stonecutter existed.

Geometry is **not** split to achieve it. All three copies are the same `16×9×16` box, each drawing
through an alpha mask that keeps only its own pixels — the same layering `stoneware_layer` does for
dimples. Splitting would not have worked anyway: the wooden legs sit at the same height as the stone
beside them, so no cut plane separates them.

Five consequences worth knowing before touching it:

- **It is priced in blocks, not chunks** — two of them, so 18 grains. There is no such thing as a
  smooth *chunk*, because a finish lives on a block. That is why `StonewareRecipe` grew a second path:
  chunks first, and `Composition.pooled` over whole blocks when there are none. The two stay separate,
  because slot **order** decides which dimple shows which stone and pooling reshuffles it.
- **Its hammer yield is exact, uniquely.** Every other shape is a *fraction* of a block, so what comes
  back is drawn by chance. A stonecutter holds two whole compositions, one per half, so it hands back
  precisely the two stones that went in — nine and nine, no rounding, no draw.
- **Requiring a finish needs a recipe type, not an ingredient.** A finish is data on the item, so
  `granularity:cobblestone` in JSON matches cobbled, smooth and all eleven styles alike — an
  ingredient cannot say "smooth" at all. `WorkedStonewareRecipe` adds a required `finish` field, checks
  every composite in the grid, and records the first and second by **position**. That last part
  overwrites what the parent pooled, deliberately: pooling is the safe answer for a block that cannot
  show where its material came from, and averages away the whole distinction for one that can.
- **Vanilla's `StonecutterMenu` names `Blocks.STONECUTTER` outright** in `stillValid`, so ours would
  close the instant it opened. `StonecutterMenuMixin` widens it — the same shape of gap as
  `PistonHeadBlockMixin`. Worth checking for any block with a menu: an anvil and a beacon do the same.
- **"Two halves" and "two stones" are different things**, and the stonecutter is what separated them.
  A double slab is two objects sharing a block space, so moss on the bottom leaves the top clean and
  each half keeps its own dye. A stonecutter is *one* block that happens to be drawn from two rocks, so
  moss covers all of it. `Moss.hasTwoHalves` is the distinction; without it a mossy stonecutter had a
  clean top, and nothing would have said so.

The saw points at `minecraft:block/stonecutter_saw` rather than a copy. It is a 16×48 three-frame
animation with its own `.mcmeta`, and both extraction scripts assume 16×16 — but it is already pure
greyscale on every pixel, which *is* what a tintable sprite is, so there is nothing to extract.

### A jammed blade

Moss packed against the saw stops the stonecutter, and — uniquely in this family — you can **see** that
it has stopped. `Fouling` already said a machine with moss over its working face has stopped working;
a furnace that refuses to smelt looks exactly like one that will, so the stonecutter is the first block
that can tell the player why without a word.

Three things had to agree, and one rule decides all three:

- **It jams from either side.** Every other working face here is one-sided — a door, a muzzle, a slot,
  a plate — and moss on the back of a furnace is no obstruction. A saw is a disc standing out of the
  bench, so `Fouling.bladeFouled` takes the whole facing axis. Moss on the bench's other sides is
  merely untidy.
- **It refuses to open**, at `getMenuProvider` rather than at `useWithoutItem`. `Player.openMenu`
  accepts null, so that one override refuses anything reaching for the menu — a mod, a command, a
  future hopper — instead of only the click that happened to be checked. `StonecutterMenuMixin` closes
  one already open, since moss can arrive while the screen is up.
- **The blade stops turning.** You cannot pause an animated sprite: it is one atlas slot rewritten
  every tick. What you can do is draw the quad from a *different* sprite, so
  `extract_stoneware.py` takes frame 0 of vanilla's strip as `stonecutter_saw_still` and
  `StoppedBladeModel` swaps to it. That wrapper matches on the **sprite**, not on `METAL_TINT` or on
  geometry, which is what lets it sit outside `OverlayBakedModel` and ignore the moss quads — they are
  drawn with a moss sprite — and what stops it breaking the day a second metal part appears.

Brush the moss off and it works again. Nothing here is permanent — but you have to be able to *reach*
the blade to do it, which took one more change.

**The blade is part of the outline now.** Vanilla's stonecutter outline is the bench alone, because
vanilla's blade is decoration; ours is a working part that moss can jam, so a player who can see moss
on the blade should be able to put a brush to the blade rather than hunting for the right patch of
bench. The model draws the saw as a zero-thickness plane, which is fine to look at and impossible to
point at, so the shape gives it one pixel either side.

Only the **outline** changed. `getCollisionShape` and `getOcclusionShape` are still vanilla's bench, so
you walk over a stonecutter exactly as before and it casts the same shadow — a taller outline must not
become a taller obstacle. Ray tracing clips against the outline, which is why extending it is the whole
fix: `getInteractionShape` would only have changed which *face* was reported, not whether anything was
hit.

Two boxes cover all four facings, because the blade is centred and a 180° turn maps it onto itself —
so the facing's **axis** decides, not its direction, and the rotation's handedness never matters.

### Compared with the furnace, deliberately

A fouled **furnace goes on smelting**: "moss takes away your reach, not the heat", so only
`openContainer` is barred and the ticker runs untouched. A fouled **stonecutter genuinely stops** —
it refuses to open, an open menu closes on the next tick through `stillValid`, and the blade visibly
halts. The difference is not an inconsistency: a furnace's fire is behind its door, whereas moss packed
against a saw is *on the moving part itself*.

### Adjective order

A colour goes **after** a quality and immediately before the material: "Smooth Red Basalt", never
"Red Smooth Basalt". Timothy caught the latter on a dyed smooth basalt.

The fix needed no new grammar, because the wording already had a slot in the right place. A named
block's key is `Smooth %s` and that blank is exactly where the material goes — so the dye attaches to
the **stone** rather than to the finished name, and `Red Basalt` dropped into `Smooth %s` comes out
ordered. It works for every style at once, including the awkward compounds: `Fine %s Brick Slab` gives
"Fine Red Slate Brick Slab" — quality, colour, material, form, all in place.

A block of **mixed** stone has no such slot: "Smooth Stone" names no material for a colour to sit
beside. So it takes a third wording, `.dyed`, which is the plain one with a blank cut into it —
`Smooth %s Stone` → "Smooth Red Stone". `gen_style_lang.py` emits all three, and `FinishNamingTest`
fails the build if one is missing.

One name changed as a consequence, and for the better: a style whose name is *itself* a noun takes the
material in front of it, so flowstone went from "Flowstone Slate" to **"Slate Flowstone"** — the same
shape as "Slate Bricks", and it now takes a colour correctly as "Red Slate Flowstone" rather than
"Flowstone Red Slate".

---

## 10. Vertical slabs, for no new blocks at all

The reason vanilla has never shipped these is arithmetic. Vanilla has **sixty** slab blocks and
orientation would multiply every one of them. **We have one** — because what a slab is made of is a
composition component and how it is worked is a finish component, and neither is a block. Orientation
is the one thing that genuinely *is* geometry, so it goes in the blockstate, and the whole cost is a
property.

`AXIS` says which way the slab is cut; vanilla's `TYPE` is reinterpreted as **which half along that
axis** — `BOTTOM` the negative side, `TOP` the positive, `DOUBLE` both. On `AXIS=Y` that is precisely
what those words already meant, so **every slab in every existing world keeps working**: a saved block
with no axis loads the default.

Nine models, eighteen states, one block, one item.

### Two things came free

- **Two-stone vertical doubles.** The second composition, its own overlays, dye and finish were built
  for horizontal doubles and reused for the stonecutter's two stones. "Upper" simply becomes "the far
  half along the axis" — no new idea, no new storage.
- **Finish textures orient correctly.** `FinishBakedModel` picks its sprite from a quad's *world*
  direction, so a vertical Pebbled slab shows the top texture on the face that is actually facing up,
  matching its neighbours rather than its own history.

### Placement, in three rules

1. **No sneak, no vertical.** An ordinary click places an ordinary slab, so nothing a player already
   knows how to do changes.
2. **Aiming at a slab inherits its axis *and its half*.** This is what makes a run buildable: point at
   the last one and keep clicking. Inheriting only the axis would offset every second slab by half a
   block.
3. **Otherwise a sneak on a side face goes vertical**, cut along the face pointed at, so the slab hugs
   the block it was placed against.

Sneaking on a **top or bottom** face deliberately stays horizontal. That is not an omission: sneak-place
is already vanilla's "place this instead of opening what I am pointing at", which is how a slab gets
onto a chest, a furnace or a stonecutter — and you reach those by clicking their tops. Taking the whole
gesture would have made every interactive block unbuildable-on.

**Merging and orientation-inheritance never collide**, so neither needs a modifier: vanilla only merges
when you click the *empty* half, which leaves every other click free to inherit. The one addition is
that **two slabs only merge if they lie the same way** — a vertical slab and a horizontal one sharing a
block space is not a double of anything, and without the check a careless click would silently
reorient one of them.

The player never handles a vertical slab. There is one item, it carries no axis, and every drop is an
ordinary slab again; orientation exists only while the block is placed.
