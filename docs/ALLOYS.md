# Alloys and material properties

**Status: design, not built.** This records the shape of the feature and the reasoning behind it, so
that the arguments are not made twice. Where a decision is still open it says so.

## The idea

Nine slots already say what a block is made of. Alloys make that *mean* something: every block gets a
set of **properties** derived from its grains, and mixing grains mixes properties.

Six copper and three tin is not "copper with tin in it" — it is bronze, and it is harder than either.
That is the whole feature in one sentence, and it needs nothing new to store: a composition is
already nine grains, and 16 rocks in 9 slots is 735,471 combinations of one registered block. Alloy
ratios are a non-problem for the same reason stone mixtures were. Six metals is 3,003 combinations,
all free.

## Properties

Each is an integer from **0 to 16**. Integers all the way down, as everywhere else.

Proposed set — hardness, conductivity, corrosion resistance, magnetism, and more as they earn their
place. A block's value for a property is derived from its nine grains, never stored.

Worked example, from the numbers already discussed:

| Material | Hardness |
|---|---|
| tin | 1–2 |
| copper | 3 |
| **8 copper + 1 tin → bronze** | **~4.5** |
| iron | 5 |

Note what that example is doing: bronze is *harder than both its ingredients*, so the derivation
cannot be a plain average.

### A property is a field, and alloys are its peaks

Each property is a scalar **field over composition space**, and the value of a block is that field
sampled at its composition. Bronze is not an entry in a table of recipes — it is a **local maximum**
on the hardness field, at around eight copper to one tin. The field is smooth, so one grain either
side of the peak is nearly as good, and walking toward it is something a player can feel.

This is chosen over a named table of pairs deliberately, and the reasons compound:

- **Discovery is real.** Nobody is told the ratio. You notice hardness climbing and follow the
  gradient, which is a thing to *do* rather than a recipe to look up.
- **Third-party grains alloy without us knowing about them.** A named table can only contain pairs we
  wrote down; a field defined by interaction terms gives any two grains a defined result. That is the
  same argument that made the grain roster open in the first place — see `docs/MATERIAL_ROSTER.md`.
- **Nothing new is stored.** The field is derived from the composition, like everything else here.

The shape of the field is where the design work is: a weighted mixture as the baseline, plus
interaction terms that raise a bump at particular ratios. Naming a peak "bronze" is then a *label on
a region of the field*, not a thing the mechanic depends on.

### Why this needs a step back

Rock already has hardness in the vanilla sense, and cobblestone and smooth stone will need property
values of their own so that stone and metal live on one scale. Expect to revisit the existing roster
rather than bolt metals on beside it.

## Reading a block at a glance

Each property gets a set of **overlays**, and a block wears the overlay for the value it has. A
player should be able to look at a block and know what it is and what it can do, without a tooltip.

This is why the overlay system was built per-face and as registered data rather than blockstate: any
number of overlays combine for free, and other mods can add their own. See
`docs/MATERIAL_ROSTER.md` and `Coating`.

**Four bands per property**, not sixteen. You should read "hard-ish", not "hardness 11" — and sixteen
levels times several properties is an art budget nobody would finish.

**They are always on.** Which is the constraint that decides the art: an overlay a player sees on
every block, all the time, has to read as a *material quality* rather than as a badge. Sheen is the
right register — the way a hard metal catches light differently from a soft one — not icons or
markings.

That is harder than it sounds, and for a reason already written down. `cutout_mipped` alpha is
**binary**: `if (color.a < 0.5) discard`. There is no half-transparent overlay, so a subtle sheen has
to come from *coverage* — dithering — or from tinting the block's own colour. See
`docs/RENDERING.md`, and `tools/gen_cracks.py` for a texture that had to be rebuilt around exactly
this constraint.

Open question: several properties at once means several always-on overlays per face. Legibility is
the risk — four sheens stacked may read as mud. Worth prototyping two properties before authoring
all of them.

## Why transmogrification comes first

Properties and appearance pull apart immediately. A player will assemble a block for its *hardness*
and get a colour they did not choose; the whole point of tuning a composition is that you are
choosing it for what it does. [Transmogrification](TRANSMOGRIFICATION.md) is what lets them keep the
material and pick the look — and its **colorant** slot exists precisely for this case, so that a
block engineered for its properties can be made to read as plain cobblestone.

That is the argument for finishing transmog before starting alloys, and it is why the costume is
stored where it cannot touch a single property.

## The furnace

Three quantities to regulate. Names pending.

| Quantity | What it is |
|---|---|
| **Energy** | the fuel budget. Coal provides 8 — it smelts 8 iron ore, which is already vanilla's number. |
| **Heat** | how hot the fire is. Some ores will not melt below a threshold. |
| **Progress** | the timer on the current item. |

**Heat is visible in the flame.** Its colour runs red → orange → yellow → white → blue with
temperature, so a player can see whether a fire is hot enough for what they are trying to melt. Wood
burns red; coal is orange to yellow. This is the same principle as the property overlays — the state
of the machine is legible from the outside.

**Energy scales with quantity, not with item count.** An iron ingot costs 1 energy; an iron *ore
block* smelts into an iron block for 9, because it is nine ingots' worth. Nine times the material,
nine times the fuel, nine times the wait. That falls out of the nine-grain model rather than being a
special case, which is the sign it is right.

**The furnace's own composition does not affect its heat**, for now. Heat comes from the fuel. This
may be revisited — a conductive furnace holding heat steadier is an appealing idea — but it is not
where the interesting decisions are.

Open questions:

- Is heat a rate that climbs and falls, or a flat property of the current fuel? A rate gives
  preheating and banking a fire; a flat value is far simpler.
- Does exceeding the required heat smelt faster, or only unlock the recipe?

## Recipes ask for properties, not for materials

This is where the whole system closes.

A recipe states the **properties** it needs, not the metal it wants. A stonecutter's blade needs to
be hard enough to cut stone — copper is not, and iron, steel or anything else that clears the bar all
work. The recipe never names a material, so a material invented later, or by another mod, qualifies
on its own merits.

And then: **if you want a copper stonecutter, you transmogrify one.** The look and the function come
apart cleanly, each ends up governed by the thing that should govern it, and neither has to
compromise. That is the argument for [transmogrification](TRANSMOGRIFICATION.md) in one line, and it
is why it was worth finishing first.
