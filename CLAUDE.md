# Granularity

Every natural block is a composition of nine grains. Two rules run through everything: **derive,
don't store**, and **integers all the way down**.

## Read the findings before starting

**Before working on a new problem, read the documentation for the area you are about to touch.** Not
after a bug appears — before writing code. The design notes in `docs/` and
`toy_geology_model/` record decisions *and the reasoning behind them*, and several of them were
written specifically because something was rediscovered the hard way.

This is not a formality. It has already cost real time:

- `docs/RENDERING.md` describes the block/item render-path split. `OverlayBakedModel` had solved a
  bug and documented it in its own override; a new wrapper was later written without reading it, hit
  the identical bug, and took three wrong fixes to find. A grep for `applyTransform` would have
  answered it in a minute.
- `Composition.majorityClass()` and `porosity()` sat unused with javadoc saying design §7 makes them
  what decides block identity — the feature they were waiting for was designed again from scratch
  later.

Where to look, by area:

| Working on | Read first |
|---|---|
| Anything that draws | `docs/RENDERING.md` |
| Grains, the roster, datapack materials | `docs/MATERIAL_ROSTER.md` |
| Blocks, forms, finishes | `docs/CRAFTED_BLOCKS.md`, `docs/MATERIAL_MODEL.md` |
| Stonework styles, or adding a texture | `docs/STONEWORK_STYLES.md` — measure with `tools/style_survey.py` **before** authoring a sprite |
| Worldgen coverage and vanilla suppression | `docs/BLOCK_COVERAGE.md` |
| Porosity, the water table, anything wet | `docs/HYDROLOGY.md` |
| Inventory and logistics | `docs/HAULING.md` |
| Costumes, regions, the transmog screen | `docs/TRANSMOGRIFICATION.md` |
| Material properties, alloying, the furnace | `docs/ALLOYS.md` |
| The design itself | `toy_geology_model/GEOLOGY_MOD_DESIGN.md`, `PROTOTYPE_FINDINGS.md` |

Also read the javadoc on the class you are about to change. It carries the argument, not just the
description — most of the traps in this codebase are written down at the point where they bite.

## When something looks wrong but nothing errors

Rendering, naming and model wiring fail **silently**: they compile, they pass the suite, they log
nothing, and they look wrong on screen. Only a person can see them.

- Instrument before changing code. Reasoning about how an API *ought* to behave has produced several
  real-but-irrelevant fixes.
- Log a count of what a wiring pass actually wired, and check the number. A wrong count is visible;
  a wrong texture is not.
- Verify a guard catches the bug it guards against by breaking it once on purpose.

## Verification

`./gradlew build` runs the suite. Tests are green at all times; a failing `CompositionCostTest` is
usually machine load rather than a regression — it is a wall-clock benchmark, so re-measure with the
game closed before believing it.

The client and a dedicated server can both be run for end-to-end checks
(`./gradlew runClient`, `./gradlew runServer`, and `-PquickPlay=127.0.0.1:25565` to join one from the
other). Restore `run/server/server.properties` and delete any throwaway world afterwards.
