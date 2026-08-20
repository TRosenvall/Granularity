package com.tarosie.granularity.core;

/**
 * Water moving between blocks — the block tier of design §8, ported from the prototype's `voxel.py`.
 *
 * <p>Two moves, and a tick is <b>fall, spread, fall</b>. The second fall is not tidiness: water that
 * has just moved sideways over a void should drop in the same tick rather than hang for one, and
 * findings §6.2's measurements were taken with it.
 *
 * <ul>
 *   <li><b>fall</b> — water descends into the room below, swept <i>bottom-up</i> so a column drains
 *       fully in one tick instead of one level per tick.</li>
 *   <li><b>spread</b> — within one height, the heightfield rule verbatim: one stochastic receiver,
 *       transfer capped at half the head difference so a source can never end below its target, and
 *       a low creep rate so a settled pool actually stops.</li>
 * </ul>
 *
 * <h2>The conservation trap</h2>
 * Findings §6.2, and the reason this is a port rather than a fresh implementation. Up to four blocks
 * can target one block in a tick. When the target has room for only some of it, the excess must go
 * back to <i>specific</i> sources; refunding the shortfall to every contributor over-refunds and
 * <b>creates water</b> — measured as a two-drop leak in the prototype before it was rewritten. The
 * fix is structural: <b>apply one direction per pass</b>, so each target has exactly one possible
 * source and the capacity check is exact with no refund at all. Do not collapse the four passes into
 * one scatter, however much it looks like the same thing.
 *
 * <h2>Gather from a frozen state, then apply</h2>
 * Which neighbour receives, and how much, are decided for every block from the state as it was at the
 * start of the pass. Reading a half-updated grid makes the answer depend on iteration order, and the
 * whole point of the two-phase split is that it does not.
 *
 * <h2>What determinism survives</h2>
 * Not the unconditional order-independence the heightfield model had, and the narrowing is worth
 * stating rather than glossing. The fall sweep is sequential in height, so this is
 * <b>order-independent across columns</b> — fall touches one column — and <b>deterministic within
 * them</b>, by a fixed bottom-up order. Chunk-parallel is column-parallel, so that is what the mod
 * needs.
 *
 * <h2>No pressure</h2>
 * Deliberate, and asserted in the tests so it stays a known boundary (findings §6.4). Water cannot
 * rise above its entry level: a U-tube does not equalize and artesian flow does not exist. §7 chose a
 * <i>narrow</i> in-house fluid layer, and a pressure solve is neither narrow nor cheap. It costs
 * nothing §6 asks for — perched tables and hillside seeps are entirely gravity-driven — but it does
 * cost karst hydrology, water running under a ridge and rising on the far side.
 */
public final class WaterMigration {

    /**
     * How often a one-drop difference moves anyway.
     *
     * <p>Without it, water with a head difference of exactly one never moves: the transfer cap is
     * half the difference, which floors to zero. A pool would sit one drop out of level forever and
     * a channel would never quite finish draining. With it, the last drop eventually goes, and the
     * rate is low enough that a settled pool is still settled — the same trade-off the heightfield
     * model made, and the same number.
     */
    public static final double CREEP_RATE = 0.01;

    /**
     * Lateral neighbours, in the order the passes run.
     *
     * <p>Four axial directions and no diagonals: within one height every neighbour is the same
     * distance away, so the diagonal weighting that mattered on the heightfield has nothing to
     * correct here, and four is half the per-tick cost of eight on a grid that is now
     * three-dimensional.
     *
     * <p><b>The order is part of the world.</b> The passes apply in this sequence, so changing it
     * changes which of two competing sources reaches a block first, and a saved world would evolve
     * differently.
     */
    private static final int[][] LATERAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private WaterMigration() {
    }

    /**
     * One tick over a box of blocks: fall, spread, fall.
     *
     * @param bounds which blocks are stepped. Blocks outside it may still receive water if the
     *               volume contains them — they are simply not sources this tick, and will be
     *               stepped when their own region is.
     * @param tick   the time axis of the draw, so the same block decides differently on each step
     * @return how many drops moved, which is zero exactly when the region has settled
     */
    public static int step(WaterVolume volume, WaterBounds bounds, long tick, long salt) {
        int moved = fall(volume, bounds);
        moved += spread(volume, bounds, tick, salt, CREEP_RATE);
        moved += fall(volume, bounds);
        return moved;
    }

    /**
     * Gravity: water descends into the room below it.
     *
     * <p>Swept upward from the bottom so that a block's destination has already emptied itself this
     * tick — that is what lets a column drain in one step rather than one level per step. Columns
     * never interact, so this is order-independent across them.
     */
    public static int fall(WaterVolume volume, WaterBounds bounds) {
        int moved = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    int here = volume.water(x, y, z);
                    if (here <= 0) {
                        continue;
                    }
                    int below = volume.room(x, y - 1, z);
                    if (below <= 0) {
                        continue;
                    }
                    int move = Math.min(here, below);
                    volume.setWater(x, y, z, here - move);
                    volume.setWater(x, y - 1, z, volume.water(x, y - 1, z) + move);
                    moved += move;
                }
            }
        }
        return moved;
    }

    /**
     * Lateral equalization within each height.
     *
     * <p>Gathered from a frozen snapshot of the layer, then applied one direction at a time. See the
     * class note for why both halves of that sentence are load-bearing.
     */
    public static int spread(WaterVolume volume, WaterBounds bounds, long tick, long salt,
                             double creepRate) {
        int width = bounds.maxX() - bounds.minX() + 1;
        int depth = bounds.maxZ() - bounds.minZ() + 1;
        int[] direction = new int[width * depth];
        int[] amount = new int[width * depth];
        // Hoisted: this is per block of every layer of every stepped region, every tick.
        double[] drop = new double[LATERAL.length];

        int moved = 0;
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            // Gather. Nothing is written in this loop, so every decision below sees the layer as it
            // was when the pass began.
            boolean any = false;
            for (int ix = 0; ix < width; ix++) {
                for (int iz = 0; iz < depth; iz++) {
                    int cell = ix * depth + iz;
                    direction[cell] = -1;
                    amount[cell] = 0;

                    int x = bounds.minX() + ix;
                    int z = bounds.minZ() + iz;
                    int here = volume.water(x, y, z);
                    if (here <= 0 || !volume.contains(x, y, z)) {
                        continue;
                    }

                    // Head difference toward each neighbour, limited by what that neighbour can
                    // actually take. Doubled, because the transfer is half the difference: a
                    // neighbour with one free slot can usefully be offered a difference of two.
                    double total = 0.0;
                    for (int d = 0; d < LATERAL.length; d++) {
                        int nx = x + LATERAL[d][0];
                        int nz = z + LATERAL[d][1];
                        int head = here - volume.water(nx, y, nz);
                        int fits = volume.room(nx, y, nz) * 2;
                        drop[d] = Math.max(0, Math.min(head, fits));
                        total += drop[d];
                    }
                    if (total <= 0.0) {
                        continue;
                    }

                    long hash = Rng.positionHash(x, y, z, salt);
                    double pick = Rng.uniform(hash, tick, Rng.STREAM_VOXEL_DIRECTION) * total;
                    int chosen = LATERAL.length - 1;
                    double cumulative = 0.0;
                    for (int d = 0; d < LATERAL.length; d++) {
                        cumulative += drop[d];
                        if (cumulative > pick) {
                            chosen = d;
                            break;
                        }
                    }

                    double selected = drop[chosen];
                    double magnitude = Rng.uniform(hash, tick, Rng.STREAM_VOXEL_MAGNITUDE);
                    long give = Rng.stochasticFloor(0.5 * selected, magnitude);
                    // The non-crossing cap, exact: never move so much that the source ends below the
                    // target it was levelling with.
                    give = Math.min(give, (long) (selected / 2));
                    if (creepRate > 0.0 && selected == 1.0
                            && Rng.uniform(hash, tick, Rng.STREAM_VOXEL_CREEP) < creepRate) {
                        give = 1;
                    }
                    give = Math.max(0, Math.min(give, here));
                    if (give <= 0) {
                        continue;
                    }
                    direction[cell] = chosen;
                    amount[cell] = (int) give;
                    any = true;
                }
            }
            if (!any) {
                continue;
            }

            // Apply, one direction per pass. Within a pass no block is the target of two sources, so
            // the capacity check below is exact and nothing has to be refunded. Capacity and stock
            // are re-read live, because an earlier pass may have filled the target or drained the
            // source.
            for (int d = 0; d < LATERAL.length; d++) {
                for (int ix = 0; ix < width; ix++) {
                    for (int iz = 0; iz < depth; iz++) {
                        int cell = ix * depth + iz;
                        if (direction[cell] != d || amount[cell] <= 0) {
                            continue;
                        }
                        int x = bounds.minX() + ix;
                        int z = bounds.minZ() + iz;
                        int nx = x + LATERAL[d][0];
                        int nz = z + LATERAL[d][1];

                        int give = Math.min(amount[cell], volume.water(x, y, z));
                        give = Math.min(give, volume.room(nx, y, nz));
                        if (give <= 0) {
                            continue;
                        }
                        volume.setWater(x, y, z, volume.water(x, y, z) - give);
                        volume.setWater(nx, y, nz, volume.water(nx, y, nz) + give);
                        moved += give;
                    }
                }
            }
        }
        return moved;
    }

    /** The box a step runs over, inclusive on every axis. */
    public record WaterBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public WaterBounds {
            if (maxX < minX || maxY < minY || maxZ < minZ) {
                throw new IllegalArgumentException("empty bounds: " + minX + ".." + maxX + ", "
                        + minY + ".." + maxY + ", " + minZ + ".." + maxZ);
            }
        }
    }
}
