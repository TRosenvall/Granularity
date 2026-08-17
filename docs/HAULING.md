# Hauling

**Status: agreed design, not built. Deferred until after almost everything else.**

## The problem this is the answer to

Every natural block yields nine grains. Mining therefore produces roughly nine times the items
vanilla does, and a player's 36 slots empty out nine times faster. The obvious fix is to inflate
stack sizes — 256 instead of 64 — and that option is deliberately held in reserve rather than taken,
because it solves the symptom by making the numbers less real.

Hauling is the intended answer instead. The nine-ness stays honest, and the game gains a reason for
**logistics** to be a thing players think about: getting the rock home is part of mining, not an
inventory-management chore to be optimised away.

## Wheelbarrow

A small wooden thing, built and handled roughly like a boat — but the player moves it themselves
rather than riding it.

- **It cannot climb a full block.** Stairs or flat ground only. This is the point rather than a
  limitation: it makes terrain matter, gives stairs a job outside decoration, and means a mine worth
  hauling out of is a mine somebody had to grade a path into.
- **Keying.** A player keys into a wheelbarrow. **One wheelbarrow per player**, but **several players
  may key into the same one** — so a group can work a face together and fill a shared barrow.
- **Drop it and keep mining.** A parked wheelbarrow still collects: while its keyed player is in
  range, grains that would have gone to the player's inventory go into the barrow instead. The player
  works the face; the barrow fills behind them.
- **A chime when it fills**, so nobody discovers a full barrow twenty blocks later.
- Then wheel it back to base, unload properly, and go back.

## Trains, later

Minecarts joined into a train, with the same keying idea one level up: a player may be keyed into a
**single train**, and every cart in it stores for them at once — far more room than a barrow.

The reason to want this is not only capacity. It gives minecarts a real job again: rails become
something a mining operation *builds toward*, and a long-running mine turns into infrastructure.
Exploration and rail both get more interesting for it.

## Questions to settle when we build it

Recorded now so they are not rediscovered as bugs:

- **Range**, and whether it is a sphere, a leash, or line-of-sight.
- **What happens when it is full** — fall back to the player's inventory, or stop the pickup? The
  chime implies the player is expected to notice and act, which argues for falling back.
- **What it accepts.** "Grains" is the stated rule and is easy to honour exactly: `Grains.byItem(id)`
  already identifies a grain item, so the routing can be typed rather than a whitelist.
- **Where the interception goes.** Drops originate from `NaturalStoneBlock`, which derives the
  composition and spawns nine items; routing wants to happen before they become entities.
- **Whether keying survives logout**, and what happens to a barrow whose keyed players are all
  offline.
- **Breaking it** — contents drop, presumably, but a barrow full of nine-stacks bursting across a
  cave floor is worth thinking about first.
- **Pushing.** Entity with collision, or something closer to a moved block? The boat comparison
  suggests an entity, and composites already move as entities when pistons push them.
