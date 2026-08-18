# Granularity

**Break a stone block and you don't get stone. You get nine things.**

A Minecraft mod for NeoForge 1.21.1. Every natural block is a **composition**: nine slots holding rock
chunks, ore, precious ore, gems, clay, silt, sand and water. What comes out of the ground depends on
where you dug it, and the same place always gives the same answer — because composition is *computed
from position* rather than stored. Nothing is rolled when you swing. The mountain already knew.

Two rules run through everything here:

> **Derive, don't store.** &nbsp;&nbsp; **Integers all the way down.**

The first is why a mod that gives every block in the world its own material composition is affordable
at all: natural stone stores nothing. The second is why water can't leak and recipes can't quietly
mint material — a grain is indivisible, and conservation is checked by tests rather than hoped for.

The full pitch, including the parts that aren't built yet, is in the mod description
(`src/main/resources/META-INF/neoforge.mods.toml`).

---

## What's built

**Materials.** Sixteen colours, each with its own rock, ore, precious ore and gem, laid out in regions
with organic borders — so blocks near a boundary come out genuinely mixed, and gradients fall out of
the geometry rather than being painted on. The roster is **open**: other mods can register grains, and
datapacks can add them with a JSON file. Grains named in `c:` tags are adopted automatically.

**Building materials.** Nine chunks make a cobblestone that *shows* its constituent rocks. Smelting
averages them into one colour — the whole building-material system, with no dye step and no palette to
unlock, just arithmetic on what you dug.

**Stonework.** Twenty surface styles cut at a stonecutter — mottled, banded, fine, flowstone, pebbled,
bricks and the rest — each one greyscale art coloured by the block's own grains, so a mottled block of
slate *is* slate. Slabs, stairs, walls and **vertical slabs**, all one registered block apiece because
material and finish are components rather than blocks.

**Wear and damage.** Moss spreads block to block and is scrubbed off per face with a brush. Dye colours
the mortar between the stones without ever touching the stones. Cracks are struck in with a hammer and
closed by re-smelting. All of it is per *face*, so a wall can be mossy on one side.

**Machines rebuilt on grains.** Furnace, dispenser, dropper, observer, piston, lever and a stonecutter
of our own, each showing the stone it was built from. Moss over a working face stops the machine.

## Building and running

```sh
./gradlew build        # compiles and runs the test suite
./gradlew runClient    # a client
./gradlew runServer    # a dedicated server
```

A failing `CompositionCostTest` is usually machine load rather than a regression — it is a wall-clock
benchmark, so re-measure with the game closed before believing it.

## Where the reasoning lives

The design notes in `docs/` record decisions **and the arguments behind them**, and several exist
because something was rediscovered the hard way. Read the one for the area before writing code, not
after a bug appears — `CLAUDE.md` has the area-to-file table and explains why.

| | |
|---|---|
| `docs/RENDERING.md` | the block/item render split, and its silent failures |
| `docs/MATERIAL_ROSTER.md` | grains, the open roster, datapack materials |
| `docs/CRAFTED_BLOCKS.md` | blocks, forms, finishes, overlays |
| `docs/STONEWORK_STYLES.md` | every vanilla stone texture, measured and catalogued |
| `docs/BLOCK_COVERAGE.md` | worldgen coverage and vanilla suppression |
| `docs/HAULING.md` | inventory and logistics |
| `toy_geology_model/` | the design itself, and the prototype's findings |

Textures are generated from vanilla's own art by the scripts in `tools/`, never by hand and never at
runtime — one greyscale sprite serves every colour a material can take, because colour comes from the
composition at draw time.

## Licence

All rights reserved. Built on the [NeoForge MDK](https://github.com/NeoForged/MDK).
