# Block Coverage

**What the generator has to be able to express. Design: Timothy Rosenvall, 2026-08-11.**

Every naturally-occurring non-plant block in vanilla, and the composition that should produce it.
Extracted from `Blocks.java` (1060 declarations) filtered to natural, non-plant, non-crafted — 74
blocks. Grass is excluded as topsoil; plants are out of scope.

The point of the exercise: **the generator should cover essentially everything**, not just stone. If
sand and gravel cannot be expressed as compositions then they have to be special-cased, and the
whole premise — one uniform matter model — leaks.

---

## 1. The consolidation rule

Timothy's rule, and it turns out to be the load-bearing one:

> **Falling blocks have a majority of air or water where other blocks have grains.**

A block is *unconsolidated* — it falls, it is loose — when air or water holds five or more of its
nine slots. Which grains fill the rest decides what it is.

| grains | consolidated (air ≤ 4) | unconsolidated (air ≥ 5) |
|---|---|---|
| sand | **sandstone** | **sand** |
| rock / ore | **stone** | **gravel** |
| clay | hardened clay | **clay** |
| ice | **packed ice** | **snow** |

Sandstone and sand are *the same grains at different porosity*. So are stone and gravel. Nothing
needs a special case: one threshold on the air count produces the entire loose/solid distinction,
and it is the same air count that already governs how fast a block breaks and how much water it can
hold.

This also settles the open question in MATERIAL_MODEL.md §6.3 — "what is five air plus four sand?"
It is sand. The grains say what; the air says whether it holds together.

## 2. Coverage

### Stone (consolidated mineral grains, air 0–4)

| block | composition | notes |
|---|---|---|
| stone | 9 rock, family-appropriate | the default |
| granite, diorite, andesite | 9 of that named rock | **igneous**; replaces vanilla's generation |
| deepslate | 9 rock, deep variant | metamorphic in character |
| tuff | rock + moderate air | volcanic ash, porous |
| calcite, dripstone_block | 9 carbonate rock | sedimentary |
| basalt, blackstone | 9 rock | igneous |
| obsidian, crying_obsidian | 9 rock, **air 0** | zero porosity is why it is hard |
| netherrack | rock + high air | soft and porous — the air explains both |
| end_stone | 9 rock | own dimension |
| bedrock | the map at full certainty | design §4 |

### Ores (rock grains + mineral grains)

| block | composition |
|---|---|
| iron_ore, copper_ore, gold_ore, redstone_ore, lapis_ore, diamond_ore, emerald_ore, coal_ore | rock + 1–4 of that mineral |
| deepslate_*_ore (8 of them) | same, with deepslate as the rock |
| nether_gold_ore, nether_quartz_ore, ancient_debris | nether rock + mineral |

A vanilla ore block is just "mostly rock, some mineral" — which is exactly what the composition
function already produces. **A grain may still drop the vanilla item**: an iron grain can drop
`minecraft:raw_iron`. Being a grain is about what it is made of, not what it gives you.

### Soil (sand / silt / clay, per the USDA triangle)

| block | composition |
|---|---|
| dirt | sand + silt + clay, mixed |
| coarse_dirt | dirt grains + rock grains + some air |
| rooted_dirt, podzol, mycelium, grass_block | dirt + an organic surface layer |
| farmland, dirt_path | dirt at altered compaction |
| mud | dirt + **water ≥ 4** |
| clay | 9 clay grains, unconsolidated |
| soul_sand, soul_soil | nether soil |
| muddy_mangrove_roots | mud + organic |

### Unconsolidated (air or water majority)

| block | composition |
|---|---|
| sand, red_sand | sand grains + air ≥ 5 — colour from which sand |
| gravel | rock grains + air ≥ 5 |
| suspicious_sand, suspicious_gravel | as above, with a treasure grain in a slot |
| snow, snow_block, powder_snow | ice grains + air, increasing with looseness |

Red sand and sand differ only in which sand grain — `iron_sand` against `quartz_sand`. That is the
lattice doing its job.

### Water phases

| block | composition |
|---|---|
| ice, packed_ice, blue_ice | 9 water grains, frozen; air distinguishes them |
| water | 9 water grains (design §7) |

Freezing is a *state* of the water grain rather than a separate grain, which needs a temperature
concept. Deferred with the atmosphere.

### Superstructures and specials

| block | approach |
|---|---|
| amethyst_block, budding_amethyst | gem grains; **generated as a superstructure** — geodes are worth discussing separately |
| glowstone, sea_lantern, prismarine, dark_prismarine | other dimensions and oceans; low priority |
| magma_block | rock + lava |
| sculk, sculk_vein, catalyst, shrieker, sensor | biological, not compositional — supplant, do not model |
| moss_block | plant-adjacent, out of scope |

## 3. Grains this needs that do not exist yet

The roster in [MATERIAL_ROSTER.md](MATERIAL_ROSTER.md) covers stone, the eight minerals and six soil
grains. Coverage above requires more:

- **Coal** — ore class, sedimentary, organic in origin.
- **Redstone** — its own thing mechanically, but ultimately a grain.
- **Quartz** — nether, and a common vein mineral besides.
- **Ice** — the frozen state of water, if it is a grain rather than a state.
- **Lava** — fluid, alongside water.
- **Humus / organic** — what makes topsoil topsoil rather than sediment, and what grass grows on.
- **Netherite / ancient debris**, **soul**, **end stone** — other-dimension grains.
- **Treasure grains** — echo shard and friends, for suspicious sand and for the pleasure of finding
  one in ordinary dirt.
- **Evaporites** — gypsum, halite. Sedimentary, and they justify desert and lakebed variation.

## 3b. Vanilla stone suppressed but not yet reimplemented

**Marked 2026-08-15, extended the same day.** Seven vanilla stones are now actively removed from
generation and are still owed a granular form. Killing them is not the same as replacing them, and
the difference is easy to lose track of once the world stops showing them. **Everything in this table
is a debt.**

| vanilla block | why it is gone | grain exists? | owed |
|---|---|---|---|
| `granite` | filler for copper ore veins, stripped by `OreVeinifierMixin`; blob features removed by `remove_vanilla_stone_blobs` | **yes** — `Grains.GRANITE`, igneous | a reason for it to appear in the world, since the blob features that placed it are gone |
| `tuff` | filler for iron ore veins, stripped by the same mixin; `ore_tuff` feature removed | **yes** — `Grains.TUFF`, igneous, added 2026-08-15 | paid |
| `diorite` | blob features removed | **yes** — `Grains.DIORITE` | as granite |
| `andesite` | blob features removed | **yes** — `Grains.ANDESITE` | as granite |
| `deepslate` | the `vertical_gradient` rule was **deleted from our own `surface_rule`** | **no** | a grain, and the depth gradient that made it mean something — deep stone should read as deep |
| `calcite` | geode middle layer swapped to `natural_stone` in our `amethyst_geode` override | **yes** — `Grains.CALCITE`, sedimentary, added 2026-08-15 | paid |
| `dripstone_block`, `pointed_dripstone` | `remove_vanilla_dripstone` removes `dripstone_cluster`, `large_dripstone`, `pointed_dripstone` | **no** | both a grain *and* a growth mechanic — dripstone is the only one here that was a behaviour, not just a rock |

Verified against 841 freshly generated chunks: zero occurrences of any of the seven, `natural_stone`
present throughout. Match block ids **exactly** when checking this — a substring search for
`minecraft:deepslate` also matches `deepslate_bricks` and every deepslate ore variant, and will
report a leak that is not there.

### Two things the removals dragged with them

**Amethyst was nearly collateral damage.** Calcite is the geode's *middle* layer, so deleting the
`amethyst_geode` feature outright would have taken budding amethyst and every cluster with it.
Overriding the feature and swapping just that layer keeps geodes generating exactly as before —
confirmed, 8 chunks with amethyst and none with calcite.

**Deepslate iron ore had to follow deepslate.** `OreVeinifier.VeinType.IRON` hard-codes
`DEEPSLATE_IRON_ORE` as its *ore*, not merely its filler, because vanilla only ever runs that vein
between y −60 and −8 where everything is deepslate anyway. With deepslate gone it left
deepslate-textured ore sitting in pale stone, so `OreVeinifierMixin` now maps it to plain `iron_ore`
— same ore, same drops.

### Surface stone: fixed, with a small residue

**Done 2026-08-15.** All **25** placements of `minecraft:stone` in our `surface_rule` now name
`granularity:natural_stone` — not only the stony-shore branch but every windswept and peak variant
alongside it. Vanilla stone fell from 24 of 841 chunks to **3**.

The residue is real and unchased: all three are `lush_caves` chunks, so something in that biome's
features still places a little vanilla stone from outside the surface rule. It is the same *kind* of
leak as the ore-vein filler — a hardcoded block inside a feature — and much smaller than the one just
closed.

### Deepslate, when it returns

Not a block, per Timothy, 2026-08-15: **a depth crossfade on the stone texture itself.** Natural stone
carries the ordinary stone texture and a deepslate texture, and their alphas trade off with depth —
deepslate at alpha 0 at sky level rising to full at the world's floor, stone the reverse. Depth stops
being a boundary you cross and becomes a gradient you descend, which is what deepslate always wanted
to mean. Dripstone likewise returns as a **function rather than a block**.

## 4. What this changes

Nothing structural. Every row above is a composition the existing model can already express — the
work is roster entries and the air-count threshold, not new machinery. That is the strongest
evidence so far that the nine-slot model is the right shape: a catalogue of 74 vanilla blocks maps
onto it without a single special case, except the ones deliberately supplanted (sculk, and
superstructures like geodes).
