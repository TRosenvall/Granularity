# Transmogrification

A block wearing another block's look.

Granularity's founding rule is that a block shows what it is made of. This suspends that, on purpose
and in exactly one place. The reason is not decoration: once
[alloys](ALLOYS.md) give blocks real properties, a wall of the right *material* will very often be
the wrong *colour*, and a builder should not have to choose between a block that performs and a
block that looks right. Transmog is what keeps those two axes independent.

## What keeps it honest

The disguise lives on the block entity and nowhere else.

- It never reaches the item. A block picked up is just a block.
- It changes what is drawn and not one thing more — no hardness, no tool, no drop, no recipe.
- Breaking the block hands every donor back intact, and reveals what was underneath all along.

Storing it apart from the composition is what makes that structural rather than a promise. Nothing
in the costume path can reach a blockstate, a composition, or a drop.

## The screen

Shift + right-click a composite with a **brush**. Not in adventure mode: the check is `mayBuild`
rather than the mode's name, because that is the question vanilla itself asks before letting anyone
alter a block — so spectators are covered too, and any future mode that withholds building. It is
tested when the screen opens *and* while it is open, or a mode change would leave someone holding a
working screen whose slots write straight through to the block. One row of slots per part, the player's inventory
below.

Each part offers up to two slots:

| Slot | Takes | Supplies |
|---|---|---|
| **Costume** | a full opaque block | texture *and* colour |
| **Colorant** | any grain item — chunk, ore, gem, ingot | colour only, overriding the costume's |

A colorant on its own recolours the block's own texture. A costume on its own brings its own colour
with it. Together, the costume supplies the texture and the colorant overrides the colour: a
multicoloured cobble plus a slate chunk reads as *slate cobblestone*.

Some parts take a grain item and nothing else — a stonecutter's blade was an iron ingot, and an
ingot has no faces to lend. Those parts have one slot and adopt the item's colour.

## Which parts a block offers

A part is a **region**, and a region is a range of tint indices. This is not new information: every
composite model is already split by tint so its colours can come from different places — a
stonecutter's lower stone from one composition, its upper from another, its frame from a plank, its
blade from an ingot. A region reads that same division for a second purpose, so a machine needs no
re-authoring to become dressable part by part.

Two kinds of division, and the difference is worth naming:

- **By material.** What the block is made of. A piston is stone, timber and metal; a stonecutter is
  two stones, timber and a blade. Only these two machines are genuinely part-shaped.
- **Spatially.** A cut through the geometry. A furnace is one region of stone and a door, so parts
  buy it nothing — a top and a bottom is the division it visibly already has, and its top and side
  textures are near enough aligned at the waist that cutting there costs nothing. See
  `tools/split_model_halves.py`.

Both kinds are the same thing to every other part of the machinery. A spatial region is another
entry in the enum with another tint range behind it.

### What each slot asks for

The rule is that **a costume asks the same of you as the recipe did**, with one deliberate
exception.

| | Asks for |
|---|---|
| Every standard composite — block, slab, stairs, wall | a full block, **always** |
| A spatially split part — a furnace's top or bottom | a full block |
| A part built from a block — a piston's plate, a stonecutter's frame | a full block |
| A part built from an item — a stonecutter's blade | a grain item |

The exception matters. Most things here are crafted from loose grains, so read strictly the rule
would make almost every slot a chunk slot — and a chunk can only ever say *"look like this rock"*,
never *"look like sandstone"*. That would remove the ability to disguise anything as a
non-Granularity block, which is the whole point. Standard composites therefore always take blocks.

Every test is asked of tags, of the grain roster, or of the block's own state rather than kept as a
list of items, so a plank, an ingot or a rock from another mod is eligible the moment it qualifies.

### Parts withheld on purpose

A furnace door and an observer's eye are how you *read* the block — which way it points, whether it
is lit. A costume over them covers that up rather than decorating it, so they are never touched at
all: not their texture and not their colour.

`Region.withheld` names them explicitly rather than leaving them out, so the startup check can tell a
part withheld on purpose from a part nobody remembered. A guard that flags the design is a guard
people learn to ignore.

## How it is drawn

Two donors, two mechanisms, because the two kinds of block keep their appearance in different places.

**A foreign block keeps its appearance in a texture.** So it lends its sprites, face by face, and the
colour handler answers no tint at all — it must be drawn in its own colours or a sandstone costume
comes out slate-coloured.

**One of ours keeps its appearance in nine grains and a finish.** Its sprites are untinted greyscale
layers that mean nothing alone, so lending them lends nothing: the first version of this borrowed a
granularity cobblestone's base layer, dropped the grain layers, turned the tint off with them, and
produced the same flat white-grey whatever you inserted. One of ours is therefore worn as a
**composition** — the block is drawn *from* it, through the ordinary layered path, with the donor's
stones, tints and finish.

A costume covers the whole of a block or it is not a costume, so parts that are not stone are
redrawn as stone: the timber quad is replaced by the full base-plus-grain-layer stack at the same
geometry. That works because the layer models all share the base's exact `from`/`to` — coplanar
duplication is already how this codebase draws grains.

### A colorant beats a foreign texture, and it has to

A block tint **multiplies** against the sprite. Every part sprite in this mod is greyscale for exactly
that reason: multiplying grey by a colour gives that colour, so a tint reads as the colour you chose.

A real texture cannot be recoloured that way. Dark oak log times grey is not grey — it is darker dark
oak, which is what a log framing an iron-coloured stonecutter actually looked like. So asking for a
colour is asking for a surface that can take one, and only the block's own greyscale sprite can. When
a part has a colorant, that sprite is used and the donor supplies nothing; the donor decides the
texture whenever no colour is asked for.

The case that matters most is unaffected, because our own blocks *are* greyscale layers: a
multicoloured cobble plus a slate chunk keeps the cobble pattern exactly and only its colour changes.

### Some parts are masks, not surfaces

A stonecutter's lower stone, upper stone and timber are **three coplanar boxes at the identical
`from`/`to`**, told apart only by the transparency in their sprites — the frame is 74 opaque pixels
out of 256. That is the alpha-mask trick that let one block show two stones without splitting its
geometry, and it means those quads are not surfaces that can be retextured: putting any opaque
texture on one fills the whole face and buries the stone underneath it.

So a masked part keeps its own sprite and takes only a **colour**. The test is geometric rather than
by pixel — a part quad occupying the same plane as a stone quad is a mask over it. A piston's plate is
a separate box in front of its body and may be retextured freely; a stonecutter's frame is the same
box as its stone and may not.

Where a block is worn on a masked part, its own `MapColor` supplies the colour — the one thing every
block in the game has an opinion about, ours and anyone else's.

### Traps found the hard way

- **The sprite pool must come from the whole model, not the side being drawn.** Minecraft asks for
  one side at a time, and a retracted piston's top face is *entirely* wooden plate — no stone in that
  call to copy a treatment from. Reading only the current slice fell back to the particle icon, which
  is a side texture, and the plate wore the body's side on its top.
- **A restoned part borrows its own donor's finish.** Asking the model with the block's own finish
  gave the piston's cobbled sprites tinted white, which is white cobble and not marble.
- **Stone tints are never synthesised.** Rewriting a double slab's 10–19 into a costume band asked
  for a costume filed under `UPPER_STONE` while a slab files its one costume under `ALL`, so the top
  half found nothing and drew bare grey.
- **A colorant must not decide the texture.** Folding the costume and the colorant into one
  "composition" meant tinting a *foreign* costume made the block look like one of ours again and the
  donor's texture vanished — the opposite of "texture from the block, colour from the chunk".
- **An ingot is not the colour of its ore.** Resolving an ingot to its material gave iron ingots raw
  iron's ore-pink. An item is asked for its own colour: the roster's tint if it is a grain's own item,
  otherwise the colour of the block it would be cast into.
- **A finish removes grain layers, not everything.** `FinishBakedModel.worked` kept only tints 0 and
  10, which was invisible until a costume could hand a *machine* a worked finish — then a fine-tiled
  piston lost its plate, its brackets and its grain layers at once and rendered as one flat quad.

## The things that carry a costume

Everything that carries a composition has to carry the costume with it, and each one was a separate
bug until it was found:

- **Drops.** Three paths — a whole block, a slab, and everything shaped or mechanical — wrapped by
  one `CompositeShapes.withCostume` so a fourth cannot quietly forget. Outside the hammer check:
  smashing a block for its grains is no reason to destroy the item somebody put on it.
- **Four block entities.** A furnace, a dispenser and a dropper had to extend vanilla's container
  block entities, so they are not `CompositeBlockEntity`. Testing for the concrete class meant the
  menu opened on a furnace and silently did nothing.
- **A piston's arm.** `onPlace` copies everything else the head inherits; a costume left out meant an
  extended piston shedding its disguise halfway along its own arm.
- **A pushed block.** Destroyed and rebuilt at the far end; the costume rides the same seam the
  composition does.

## Known limits

- A stone-dressed piston **arm** falls back to the piston's side sprite, because the head's model
  contains no stone to copy a treatment from. Visible only while extended.
- A double slab's **grain-layer count** comes from one composition for both halves, since that is a
  single model property. Colours and finishes are per half.
- `Region.of` is hand-declared. The startup check catches a part that is drawn but undressable; it
  cannot catch a region mapped to the wrong tints.
