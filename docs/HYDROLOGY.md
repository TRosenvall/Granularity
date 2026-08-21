# Hydrology

Water is slots. A pore in the rock holds air or it holds water, and the number of water slots is the
same number vanilla calls a fluid level. Nothing about water is stored.

This file covers porosity, the water table, water moving through rock, and what happens when you
break wet rock. Read it before touching `WaterTable`, `WaterLevels`, `WaterRelease`, `WaterMigration`,
`WaterDeviations`, `WaterExchange`, `WaterTicker`, or the pore branch of `CompositionFunction.stone`.

## The end goal

Stated by Timothy, and the destination the pieces below are steps toward:

> When water is actively flowing through a porous rock, its air grain should be read as a water
> grain. The water grain shouldn't drop either, but if there is water flowing through a porous rock
> missing one grain, then only water level 1 should be able to flow through it, and when the rock is
> broken, it should appear as water level 1 on the ground itself.

Three consequences, and each is already load-bearing:

1. **A pore is one slot with two possible contents.** Air when dry, water when wet. Not a second
   quantity beside the composition.
2. **Free slots are the throughput.** Rock missing one grain conducts level-1 water and no more.
   Hydraulic conductivity is the composition, counted — `WaterLevels.conductivity`.
3. **Breaking wet rock puts that level on the ground.** Never an item; water yields none.

## What is built

**Porosity** (design §6, findings §6.1). A saturating band of 3D noise decides how much of a block is
pore rather than rock, scaled by how open the rock's family is — sedimentary 1.0, metamorphic 0.3,
igneous 0.05. See `porosity-is-free-slots` in the design notes and the javadoc on
`CompositionFunction`. Pores come in **sheets**, because §6.3's spring needs an aquiclude to perch on.

**The water table** (`WaterTable`). An **elevation**, not a depth below cover. A water table is a
surface, roughly level across a region — that is what lets a hillside be dry while the valley beside
it at the same height is soaked. Depth-below-cover would drape water around every hill and put an
aquifer under a mountain summit. It is also the only formulation available: `CompositionFunction` is
a pure function of position and cannot ask how tall the terrain overhead is.

- Datum y=62, one below sea level, so an ocean floor does not sit exactly on the boundary.
- ±20 blocks of regional swell at a 1024-block wavelength — drainage-basin scale, several times wider
  than a `ColourField` stone province, so one rock type spans wet ground and dry.
- Saturated everywhere below it. Not decaying with depth: below the table the rock is *under* the
  water, and what varies down there is how many pores exist.
- A four-block capillary fringe above it, partially wet, so mining down reads damp → damper → wet.

**Order matters.** Porosity decides *whether a slot is a pore*; the water table decides *what fills
it*. Water is drawn inside the pore branch, downstream of the air draw and on its own stream. An
impermeable bed is therefore dry because it has nowhere to put water, not because a rule says so.

**Breaking wet rock** (`NaturalStoneBlock.onDestroyedByPlayer` → `WaterRelease`). Design §6's
"breaking a moist block releases a partial water level that sits or sinks", almost word for word.
Three drops in the pores come out as level-3 water, which spreads, sinks and is gone.

**One to nine grains, never zero.** A natural block always keeps at least one grain, so at most eight
of its slots are pores. This is the rule the rest of the mod is entitled to lean on, and two separate
things need it: a block with no grains hands a player nothing when mined, and a block of nine drops is
a *source*, which is water the world invented rather than water it moved.

Three attempts, recorded because the third is easy to mistake for the first:

1. **Clamp porosity to four.** Gave up the field's whole upper range to dodge one case at the far end
   of it — and the aquifers and aquicludes §6.3 needs live in exactly that range. Rejected.
2. **Let the field say nine and carve those blocks out at worldgen** (`VugFeature`, since deleted).
   Built, measured at ~25 blocks per chunk, removed: it made a cavity something the *terrain* had to
   contain — a scan of every block in every chunk, a feature, a placement, and a policy for what to
   leave in the hole — to express something the composition can simply decline to say.
3. **Rescue one slot** (`CompositionFunction.rescueStone`). Where all nine would be pores, the pore
   whose air draw came closest to failing becomes host rock instead. Touches about one block in 2,600
   and changes nothing about how porous rock is anywhere else.

The rescued slot is host rock rather than an ore, because a mineral is a property of itself and
conjuring one in would put ore where the ore fields never said any was.

## Decisions worth not re-deriving

**Never place a vanilla source, and rock can never hold one anyway.** A source is infinite: it refills
its neighbours forever, so releasing one from a broken block would create water and break the
conservation §7 exists to protect. But this is now arithmetic rather than a rule to remember — a
natural block keeps ≥1 grain, so ≤8 pores, so ≤8 drops, and vanilla's deepest flow is 8. The clamp in
`WaterLevels.amount` is unreachable from real rock and no drop is ever lost in the conversion. Nine
drops remains a meaningful composition; it is simply what open water is made of, not what rock is.

**Flowing Fluids and its kind are a soft-compat seam, never a hard dependency.** Design §7 rules out
depending on external water-physics mods — performance-fraught and abandonment-prone — and leaves soft
compatibility open. With finite water present, a source stops being infinite and the rule above loses
its reason; nine drops could be placed as nine. `WaterRelease` is the single place that decision
lives, so a compat layer changes one method rather than threading through call sites.

**Porosity counts water; free slots do not.** `Composition.porosity()` is air + water, because a pore
full of water is still a pore and the rock has not become less porous by raining. `freeSlots()` is the
room that is left. They agree only in dry rock. Findings §6.2 records the prototype driving `free()`
negative by budgeting against the wrong set of occupants, so "not occupied" is defined by exclusion
and a new grain class costs nothing.

**A known wrinkle: saturated rock conducts nothing by the free-slot rule.** Below the water table
every pore is already full, so `conductivity` is zero there. That is a real limit rather than a bug —
flow in a saturated aquifer is *displacement*, not filling — and it belongs to the migration tier,
which moves drops between blocks rather than counting room. Do not "fix" it by making saturation
partial; that was tried in the porosity band and produces seepage everywhere and perching nowhere.

## Water in motion

Ported from the prototype's `voxel.py` rather than written fresh, because the traps in it were paid
for once already.

**The rule** (`WaterMigration`). A tick is **fall, spread, fall**. Fall sweeps bottom-up so a column
drains in one tick rather than one level per tick; spread is the heightfield rule within one height —
one stochastic receiver, transfer capped at half the head difference, plus a 1% creep rate so a pool
one drop out of level eventually settles instead of jittering forever.

**The conservation trap, and why the shape of the code is not negotiable.** Up to four blocks can
target one block in a tick. When the target has room for only some of it, the excess must go back to
*specific* sources — refunding it to every contributor over-refunds and **creates water**, measured as
a two-drop leak in the prototype. The fix is structural: **apply one direction per pass**, so each
target has exactly one possible source and the capacity check is exact with no refund at all. Do not
collapse the four passes into one scatter.

**Determinism narrows, and that is fine.** Order-independent *across* columns, deterministic *within*
them by a fixed bottom-up order. Chunk-parallel is column-parallel, so that is what the mod needs.

**Where the moved water lives** (`WaterDeviations`, `GranularityWater`). A per-chunk sparse map of
position → deviation from the derived baseline, saved as a chunk attachment. A block at equilibrium
stores nothing; a deviation decays one step toward zero on an interval and its entry vanishes when it
gets there. That decay is doing two jobs: disturbed groundwater really does return to its level, and
it is the only thing bounding the map's size in a world that has been mined for a month.

**The domain is our own stone** (`LevelWaterVolume`). Air, caves, vanilla water and player blocks all
report nine grains, so the rule treats them as walls. Vanilla water ticks itself; if the rule also
moved it, two systems would be moving the same drops on two schedules and the failure would look like
lag. So the rule owns every drop inside rock and vanilla owns every drop outside it.

**The two crossings** (`WaterExchange`). Infiltration downward only — sideways entry would be a
pressure story and there is no pressure. Seepage from an open face, **limited to water above the
baseline**: an aquifer at equilibrium is not discharging, and without that rule the entire saturated
zone would empty into the nearest cave forever, because the baseline would keep topping it up. Lakes
do not drain — a vanilla source stays infinite and its drops are booked as injected, so the ledger
says plainly they came from outside.

**Where it runs** (`WaterTicker`). §8's budgeted block tier: patches around disturbances, four per
tick, dropped after three quiet ticks. Patches rather than chunk sections because a section is 4,096
blocks each needing its composition derived — some sixteen thousand derivations a tick, spent while
somebody is mining.

## Verified in game, and how

Checked in a running client on 2026-08-20, which matters because most of this fails silently.

**Confirmed working:** porosity and water counts read out per block; wet rock is visibly distinct from
dry; breaking wet rock releases water proportional to what it held and never a source; tight rock
takes no water; sedimentary rock is far more porous than igneous; every block yields at least one
item; no performance drop; the stonework, hammer and stonecutter are unaffected.

**Confirmed on screen:** seepage. Water placed above a one-block porous ceiling soaks in and comes
out of the underside — visible, and the thing §6.3 is ultimately about. The `/granularity spring` rig
is what made it observable; by hand the test almost never worked, for the two reasons that rig exists
to remove.

**Confirmed by instrument only:** infiltration. A bucket on porous rock moved it from `free 1` to
`water 1`, read back through `/granularity composition`. Nothing about it was visible on screen — see
below.

**Not yet observed:** migration over distance, persistence across a reload, decay back to baseline,
and whether the patch lifetime cap actually stops a source-fed loop after 400 ticks.

### Two cues, two meanings

The block tint had three attempts before it read correctly, and the third changed the model rather
than the numbers. **Darkness means rock is missing** — every pore counts the same, wet or dry.
**Blue means water is in it.** Previously water both darkened and tinted, so the two cues stacked on
one channel and saturated rock at depth went nearly black without saying anything extra.

Both curves are square-rooted, and that is the fix for a cue nobody could see: linearly, one wet pore
in nine darkens a block by five percent, and one pore is the common case. The root lifts the bottom of
the range where the reading actually happens.

### Infiltration is invisible, and no tint can fix it

Moved water lives in a server-side chunk attachment. The client draws a block from the derived
composition and has never heard of it, so **migrated water changes nothing on screen**. Syncing the
map would buy very little: a slot going from air to water is a few percent of tint either way. What
reads as absorption is motion at the surface, so infiltration and seepage emit particles instead.

### The debug commands

- `/granularity composition` — the block you are looking at: porosity, water, free slots, the water
  table height, `holding N (baseline M)`, and the active patch count.
- `/granularity porous [n]` — nearest natural stone with at least n free slots. Reports how many stone
  blocks it examined and how many were tight or saturated, so "found nothing" says why.
- `/granularity spring` — builds the smallest rig that makes a spring: real porous rock, the block
  below it opened out, a source placed on top. Level 2.

To restore a block you broke, `/setblock ~ ~-1 ~ granularity:natural_stone`. Composition is a pure
function of position, so it comes back identical — grains, porosity and baseline water alike. Natural
stone deliberately has no item form (design §2's player-placed exemption depends on natural and
crafted being different blocks), so this is the intended route.

## Springs

A spring is a **condition, not a place**: saturated permeable rock meeting open air. Nothing marks
one, so cutting deeper into a hillside makes the new face the spring — it satisfies the same
condition the old one did. There is no object to place and none to destroy, which is what happens
when you cut into a permeable streambed.

**Discharge** is the rock's pore space. Free slots is the right measure for water *filling*
unsaturated rock and is zero for rock already full, yet full rock is exactly what transmits water —
that is what an aquifer is. A bed of porosity four gives water back four times as fast as porosity
one, and that is visible in play.

**Recharge** (`Recharge`) is keyed to regional rainfall, sampled across ±96 blocks rather than at the
block. Sampling locally would be wrong in a way worth naming: **an oasis is a desert spring**. Real
desert springs exist because recharge happened somewhere else, so a local reading would delete them.
The floor is deliberately non-zero for the same reason. Rate runs 0.25–1.75 drops per second per
block and is rounded stochastically (§12), so rainfall varies springs continuously rather than
quantising them into two or three speeds.

**Perennial versus intermittent falls out of those two rates in opposition** — recharge keeps up and
the spring runs indefinitely; it does not and the bed drains locally, stops, and returns once it
recovers. Neither case is coded for.

**Ambient weeping.** Wet rock exposed to air weeps on its own, because discharge is not conditional
on a disturbance. Random ticks *discover* a wet open face; from then on the block schedules its own
next visit at an interval scaled by how much water it holds — 4 seconds when barely damp, half a
second when nearly all water. Vanilla picks random-tick blocks uniformly, so the rate cannot be
biased toward wet rock; scheduled ticks are how that bias is expressed, the same mechanism vanilla
uses for fire and crops. A block only reschedules if it emitted, so drying out or being sealed in
ends the chain.

A weep **changes nothing** — no storage drawn down, no deviation written, no patch seeded. The drop
is passing through, which is what throughflow at equilibrium means. That is not an optimisation; it
avoids two real bugs. A weep that placed water with neighbour updates would trigger
`neighborChanged`, which disturbs, which seeds a patch, which seeps, which places water, which
disturbs again — every drip bootstrapping a self-sustaining patch until the queue swamped the
player's own. And a weep that drew its block down would drain to zero with nothing to refill it,
because recharge only runs inside patches.

## Cost, measured

Numbers, because two performance bugs here came from reasoning instead of counting.

| | |
|---|---|
| one composition | ~4.6 µs |
| a 62,000-block command scan | ~280 ms |
| the same scan, second pass with the cache | ~15 ms |
| one patch step (729 blocks) | ~4.4 ms |

Three things follow. Compositions are cached **across ticks and volumes** — `stone()` is pure, so a
cached answer cannot go stale while the world is the same world; there is no invalidation problem,
only a size one. Searches prune by the water table, because each command wants one side of it and was
deriving the half it would certainly reject. And the patch budget is two per tick, not four: four was
a third of a 50 ms tick for a background feature.

The same discipline governs ambient weeping. Enabling random ticks on natural stone makes nearly every
section in the world randomly ticking, since natural stone is what the world is made of — so `weep`
checks for an open face first, six block-state lookups, and throws out every buried block before
anything is derived.

## The atmosphere

Design §11, and the half of the cycle that happens in the sky. Findings §5.1 decided the architecture
before any of it was written: rain activates **18–40% of the loaded world**, "rain is not near
anything — it is everywhere", so the block tier cannot carry it. **Rain enters at the field tier.**

**`Wind`** — curl noise plus a prevailing bias, drifting slowly. Curl specifically, because a wind
made of two independent noise fields has *divergence*: some cells take in more than they give out,
advection piles humidity there permanently, and you get stationary fog banks no weather explains.
`∇·(∇×ψ) = 0` makes that impossible by construction rather than by tuning.

**`HumidityTransport`** — advect, then condense, at 1 Hz over one cell per chunk. Integer drops
throughout, so a drop that evaporates off a lake, crosses the sky, falls as rain, soaks into rock and
seeps out at a spring is *one drop the whole way*. The findings §6.2 convergence trap reappears
unchanged in a new medium, so the fix is the same: one direction per pass.

**`LevelHumidityGrid`** — capacity is where weather comes from, and it is two terms: warmth, and
**height of the ground**. Air lifted over a range cools, capacity falls below what it already carries,
the excess rains on the windward slope, and what crosses is dry. The desert behind a mountain is not
a placement rule — it is arithmetic about how much water cold air holds.

The baseline is measured against the capacity a column would have at **sea level**, not its own. That
correction is why the world rains at all: scaling by local capacity double-counts elevation, so high
air arrived pre-shrunk and could never cross its own line. Every reading sat near a fifth of capacity
and nothing condensed. Measured after the fix:

| | baseline | capacity | rains |
|---|---|---|---|
| warm wet lowland, y=70 | 343 | 385 | no |
| the same air at y=110 | 343 | 265 | **yes** |
| temperate hills, y=130 | 208 | 164 | **yes** |
| desert, y=70 | 242 | 672 | no |

**`WeatherDisplay`** — vanilla's weather cycle is switched off, because it is a coin flip on a timer
that rains on deserts. Rain level is set from the field at the player's column and eased in. Vanilla's
rain is one number per *level*, not per chunk, so this is right where the player is and approximate
elsewhere; per-chunk visuals need the field synced to the client.

## Drips, and what a single drop is

**One drop is a drip; two or more is a block.** Timothy's rule, and it settles three things that were
each being handled ad hoc.

Level-1 water is the one fluid state that reliably looks like nothing happened — a nearly invisible
film that vanilla erases about five ticks later — so never placing it means every block placed is one
somebody can see. Ambient weeping needed a hand-written particles-only exception for cost; now it
falls out, because a weep is one drop. And single-drop releases were most of the block placements.

**Sustained flow is not a bigger release; it is a release that happens again before the last one has
gone.** This is the whole difference between a drip and a spring, and it was learned the hard way
twice. Vanilla erases unfed water in about five ticks, so a face topped up *every tick* accumulates —
the level climbs, the block fills, it flows downhill — while the same total delivered once every
twenty minutes never amounts to anything. It is why breaking a rock made saturated faces gush: that
started a patch, and patches run every tick.

So random ticks **discover** springs and a bounded set of them runs every tick. The bound is what makes
this safe where self-scheduling was not: 24 per level, and a face needs 4+ pores to qualify. A cap has
a fixed point by construction — which is exactly what self-scheduling lacked, since blocks only
stopped weeping when they dried out and recharge kept them wet. That ran to 465 emissions a tick and a
server 329 ticks behind.

## Clouds, and why they cannot be blocks

Asked for and rejected with numbers, so it is not proposed again. Cloud blocks attenuating one light
level each is a real mechanic — `getLightBlock` is overridable and cached per state — but the sky is
17×17 chunks, or **73,984 columns**: one block each is 74,000 blocks and fifteen layers is 1.1
million. Worse, each placement triggers a skylight recalculation down the whole column, and clouds
have to move with the wind, so it is that cost continuously rather than once. Ambient weeping at 465
blocks a tick — with no lighting propagation at all — already put the server 329 ticks behind.

Clouds, sky darkening and per-chunk rain all want the same enabling piece instead: **the humidity
field synced to the client**, and rendering from it. No blocks, no lighting, nothing saved.

## Not built yet

- **Springs at outcrops.** §6.3's payoff. The beds exist and seepage exists; what is missing is
  recharge that keeps a spring running — which is rain.
- **Rain, and why it is structural rather than additive.** §6's other disturbance, needing §11's
  humidity field. It is tempting to read rain as one more input to a system that already works. It is
  not — it is the thing that makes the model's equilibrium the *right kind* of equilibrium.

  Real aquifers sit in **dynamic** equilibrium: recharge in at the top, discharge out at the spring,
  water table stable because the two balance. The store is large and drains slowly — residence times
  of months to years for a shallow hillside aquifer — so a perennial spring runs for centuries,
  varying seasonally but never stopping. It runs forever *because* it is at equilibrium, not despite
  it.

  Ours is a **static** equilibrium: at baseline, nothing moves. Seepage only releases water above
  baseline, so a spring stops as soon as the rock fills. That is why `WaterTicker`'s 400-tick patch
  cap has to exist at all — with no recharge, the only way to keep a spring running is an infinite
  vanilla source, which creates water from nothing, so the cheat needs a ceiling. The cap is a symptom
  of the missing tier, not a design choice worth keeping.

  With recharge, continuous springs fall out of the existing rules for free and the cap can go.

  Also worth knowing for when this is designed: a hillside spring emerges where a **geological
  contact** daylights — permeable bed on impermeable bed — not at whatever elevation is lowest. Water
  perches on the aquiclude, runs along it, and comes out where the boundary meets the surface;
  below that point the impermeable unit has nothing to give and the water above it has already left.
  Where geology stacks several such couplets you get a **spring line**, a row of springs at one
  height along a hill. Our porosity beds are exactly this structure, so spring lines are something
  the model should produce on its own once water flows through it.
- **A field tier.** §8's middle tier does not exist; `WaterDeviations.decay` stands in for it.
- **Released water does not fall; it vanishes where it was put.** Observed in play, and it is vanilla
  rather than us: `FlowingFluid.tick` calls `getNewLiquid` first, which returns empty for water with
  no source feeding it, and sets the block to air *before* `spread` is ever reached. Non-source vanilla
  water cannot fall. Fixing it properly means water in air being slots too — §7's unification — or a
  finite-water mod. Do not chase it in `WaterRelease`; the placement is not the problem.
- **Deviations are server-side.** Chunk attachments are not synced, so the block tint shows the
  *baseline* wetness, not migrated water. Only `/granularity composition` sees the real number. The
  tint is a placeholder anyway, but a real overlay would have to solve this.
- **No pressure**, and this is deliberate (findings §6.4): water cannot rise above its entry level, so
  there is no artesian flow and no karst hydrology. §7 chose a *narrow* fluid layer and a pressure
  solve is neither narrow nor cheap.
- **Biome humidity and sea proximity.** §6's baseline lists them; they are level lookups a pure
  function cannot make, and the regional swell stands in for now.

## Measured, not assumed

`PorosityFieldTest` and `WaterTableTest` carry the numbers. The census prints its rates rather than
only asserting them, because a rate that moves by a factor of ten still looks like "occasional
pockets" to someone walking through a cave.

Two guards in this area were verified by breaking them on purpose: deleting the rescue fails the
one-grain sweep at 0,-20,3, and reversing the pore ordering fails the cross-table porosity ratio at
5.5x. Do that before trusting a new one.

One test in this area was written, looked correct, and asserted nothing: `porosity() == water() +
freeSlots()` cannot fail, since porosity is *defined* as air plus water. It passed with the pore
ordering deliberately broken. What catches that bug is the ratio across the water table — a pore count
must not know where the table is. Break a guard on purpose before trusting it.
