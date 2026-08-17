# The Material Roster

**Design: Timothy Rosenvall, 2026-08-11. Companion to [MATERIAL_MODEL.md](MATERIAL_MODEL.md), which
covers the slot model and the bedrock axis. This one is about *what the materials are*.**

---

## 1. The lattice stops being sixteen dye colours

Design §3 says "for each of Minecraft's 16 standard colors there is a rock type, an ore type, a
precious-ore type, a gem type" — 64 materials on a colour grid. That was the right scaffold to build
against and it is no longer the right shape.

**Not every colour needs filling, and real materials do not sit on a dye grid.** Rocks are named
stones with the colours real stones have; ores are the ores that matter for play. A material now
carries its own RGB rather than an index into sixteen dyes, so granite can be granite-pink rather
than the nearest dye.

What survives unchanged: one greyscale sprite per class, colour by tint, colours averaging across a
block's slots. The arithmetic does not care whether a tint came from a lattice or a named stone.

## 2. Minerals

Kept deliberately small and real. These are the ores that carry the game.

| class | material | colour | occurs in |
|---|---|---|---|
| Ore | **Iron** | tan-brown | igneous, metamorphic, sedimentary |
| Ore | **Copper** | malachite green | igneous, sedimentary |
| Ore | **Zinc** | pale sphalerite yellow-brown | metamorphic, sedimentary |
| Precious | **Gold** | gold | igneous, metamorphic |
| Precious | **Silver** | pale grey-white | igneous, metamorphic |
| Gem | **Lapis** | deep blue | metamorphic |
| Gem | **Diamond** | pale cyan | igneous |
| Gem | **Emerald** | green | igneous, metamorphic |

The occurrence column is real geology, not flavour: banded iron is sedimentary and magmatic iron is
igneous, so iron is everywhere; porphyry copper is igneous and sediment-hosted copper is
sedimentary; Mississippi-Valley-type zinc is sedimentary; orogenic gold and epithermal silver sit in
igneous and metamorphic country; lazurite is contact-metamorphosed limestone; diamond arrives in
kimberlite, which is igneous; beryl is schist- and pegmatite-hosted.

**This is what resolves §4's prospecting promise.** Bedrock tells you the rock family; the family
tells you which minerals are possible. Sedimentary country has no gold, silver or gems at all —
which makes finding igneous or metamorphic ground worth the travel, and makes the bedrock map an
instrument again.

## 3. Rocks

Real stones, real colours, mostly in Minecraft's existing range with some deviation per family.
Granite, diorite and andesite deliberately match vanilla's, because **once these generate we can
switch vanilla's generation of them off entirely.**

| family | stones |
|---|---|
| Igneous | Granite, Diorite, Andesite, Basalt, Gabbro |
| Metamorphic | Marble, Slate, Schist, Gneiss, Quartzite |
| Sedimentary | Sandstone, Limestone, Shale, Mudstone, Chalk, Conglomerate |

One colour per stone, no duplicates — finding a colour still identifies the stone, and the stone
identifies the family.

## 4. Extensibility

Other mods should be able to add materials. That rules out a hardcoded enum as the long-term
answer: the roster wants to be **data**, so a mod or a datapack can contribute an ore with its own
colour, class and family constraints, and everything downstream — drops, tints, overlays, the
mediation table — picks it up without code changes.

The registry is built for that shape now, and there are two ways in.

**From code**, for a mod that has one:

```java
Grains.register("mymod:ruby", GrainClass.GEM, "mymod:ruby", BedrockType.METAMORPHIC);
```

Called during mod construction. Declaring *what it is and where it belongs* is the entire contract —
the composition function places it wherever metamorphic country admits a gem, and it is tinted,
drawn, dropped, hammered and craftable with nothing to configure in worldgen. Pass an explicit
`0xRRGGBB` in the overload when the item's own texture is not a fair average of the material.

**From a datapack**, which needs no code at all, at
`data/<namespace>/granularity/grain/<name>.json` — so a pack can add grains for a mod that has never
heard of Granularity:

```json
{
  "class": "gem",
  "item": "mymod:ruby",
  "families": ["metamorphic"],
  "tint": "#9B111E"
}
```

The file's path is the grain's name (`mymod:ruby` above), which is how the namespacing that keeps two
mods' "slate" apart stops being something an author can forget. `class` and `item` are required.
Omitting `families` means **every** family, which is right for the soils and wrong for nearly
anything else — a gem that occurs everywhere is a gem worth nobody's journey. Omitting `tint`
averages the item's own sprite.

**From nothing at all.** A mod that tags its ruby `c:gems/ruby` — which mods do anyway, so their ruby
works in other mods' recipes — has already said everything needed. Those tags are walked and any
material nothing has claimed becomes a grain, coloured from its own item sprite.

The unit is the **tag, not the item**: two mods' rubies are one material, so the grain is named
`c:ruby` and the roster does not gain a second ruby when a second mod is installed. Because
`Grains.pick` hashes the name, that also means the grain owns the same regions whether one ruby mod
is present or three. The item is only what it drops, and the lowest id wins so two installs agree.

Inferred gems are admitted in igneous and metamorphic country only, matching the range our own gems
have, because a tag says what a thing *is* and is silent about geology — and a gem that occurs
everywhere is a gem worth nobody's journey. Inferred ores occur in all three, as iron does. A pack
that knows better writes a definition, which always beats an inferred one.

**Purely vanilla materials are skipped** — amethyst and prismarine are the two this would otherwise
hand us, and both are things this mod should decide about deliberately rather than infer. It also
removes the one way the two sides could disagree: a dedicated server has no vanilla assets to average
a colour from, where mod jars carry theirs to both sides.

Three things worth knowing about any of these routes:

- **The item must already exist.** Item registration closes during mod construction and datapacks
  load long after, so a grain names an item some mod has registered. A new *rock* therefore needs a
  chunk item shipped with it, since vanilla has no granite chunk.
- **Adding a grain does not move anybody else's stone.** Which grain owns a region is decided by
  rendezvous hashing on the name, so a newcomer claims about `1/n` of the regions its family admits
  and leaves every other region byte-identical. Installing a mod does not rewrite the geology of a
  save that mod never touched.
- **The roster is fixed once a world is running.** `granularity:grain` is a datapack *registry*, and
  those are read once per world load — `/reload` does not re-read them. That is the behaviour we
  want rather than a limitation to work around: the world generator reads the roster for every block
  it derives, so changing it mid-world would put different rock in chunks generated from then on.
- **The client is sent the definitions.** Also because it is a datapack registry: contents go out
  during the configuration phase, before the play phase and so before the first chunk. This is not
  decoration. A natural block's composition is *derived* on the client as well as the server, so the
  two must hold the same grains or the client renders rock the server does not have. (Crafted blocks
  were never at risk — compositions travel as names.)

**Nothing a pack can write is fatal.** A misspelled class, a colour that is not a colour, a name the
code already uses, an item another grain already claims — each is logged, naming the file and what
was expected, and skipped; the world loads without that grain.

That is worth stating because it is not what a datapack registry normally does, and not what the
usual instinct about worldgen data would suggest. The instinct says be strict: a grain missing at
generation time bakes a world without it, and no later fix can put the ore back. **That is false
here, for the same reason the mod is affordable at all** — natural blocks store nothing. Worldgen
never consults the roster; it places `natural_stone` and stops. Composition is derived from position
and salt at the moment it is needed, so a grain defined tomorrow is simply *there* tomorrow,
throughout a world generated long before, with no seam and nothing to migrate. Fix the JSON, restart,
and the world reinterprets itself.

The one thing a skipped grain does cost is bounded: ore mined while it was missing is already an
item, and items *do* store their composition, so a stack in a chest keeps the stone it was mined as.

For that reason the colour is resolved once, on the server, and sent. A definition that omits `tint`
has it averaged from the item sprite at load, and the resolved value is what crosses the wire — the
two sides never each work it out and hope they agree. They would not always: a dedicated server has
no vanilla assets, so a grain backed by a vanilla item is refused there and would have been accepted
on a client.

## 5. Superstructures

Amethyst geodes and their kind are not compositions and probably should not be. If they can be made
to fall out of the composition system, good; if not, generate them separately and let them sit on
top of it. Not worth contorting the model for.

## 6. Inventory: mixed debris

**The 9× drop problem's answer.** Design §15 lists it as the decision that shapes play most, and
this is it.

- A stack of a single constituent — sand piles, flint, iron ore — stacks to **256**.
- Drop one constituent onto another in the inventory and they **combine into mixed debris**: one
  stack holding several different things.
- Highlighting mixed debris opens a window with a slot per constituent inside it.
- Any constituent can be pulled back out individually, to reclaim and sort.

So the friction of nine objects per block is absorbed by letting unrelated leftovers share a slot,
without losing the ability to separate them. Mining stops filling the hotbar with singles.

**Naming.** "Block compositor" is the working term and is not good — it reads as a machine that
composes blocks. Better candidates, in order of preference:

1. **Grain** — a block is nine grains. Fits the mod's name, and grain size is literally the
   sand/silt/clay axis. "Iron ore grain" is a slight stretch.
2. **Constituent** — precise, a touch clinical.
3. **Fragment** — reads well for rock, less so for water.

The design document already calls them *objects* (§2, "drops 9 objects"), which is serviceable but
too generic to name a UI around.

## 7. Later, recorded so it is not lost

- **Plants keyed to soil composition.** Specific plants growing only in certain sand/silt/clay
  mixes. The composition already carries everything needed; this is a rule on top.
- **Treasures in blocks.** A slot can hold something that has no business being there — an echo
  shard in ordinary dirt. "How did *that* get there" is a good feeling and costs nothing structural.
- **Redstone.** Its own thing mechanically, but ultimately just another constituent.

## 8. Phase attribution

| item | phase |
|---|---|
| Mineral roster, rock roster, names, mediation table | **3** |
| Extensible registry (populated from code) | **3** |
| Datapack-driven roster | 3 tail, mechanical |
| Proper datagen, own world type rather than overwriting vanilla | 5 |
| Switching off vanilla granite/diorite/andesite generation | 5 |
| Amethyst geodes and superstructures | 5 |
| Mixed debris inventory | after 4 — needs the crafted composites to exist first |
| Plants keyed to soil, treasures in blocks | 7+ |
