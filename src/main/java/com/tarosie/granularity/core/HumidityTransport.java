package com.tarosie.granularity.core;

/**
 * Moving humidity around the sky, and dropping it as rain — design §8's field tier, §11's water cycle.
 *
 * <p>A step is <b>advect, then condense</b>. Advection carries vapour downwind; condensation turns
 * whatever exceeds a column's capacity into rain. Both are integer and both are exactly conservative:
 * every drop is either still in the sky, has fallen as rain, or has been booked as leaving the
 * simulated area.
 *
 * <h2>The same conservation trap, and the same fix</h2>
 * Findings §6.2 recorded it for water in rock and it is waiting here unchanged: several cells can
 * advect into one, and if the receiver is treated as having limited room, handing the excess back to
 * every contributor over-refunds and creates vapour. The structural answer is the same — <b>apply one
 * direction per pass</b>, so within a pass each target has exactly one possible source. It is not a
 * coincidence that the shape repeats; it is what conservative transport on a grid looks like.
 *
 * <p>Air makes it easier in one respect. Rock has capacity and refuses water; sky does not refuse
 * vapour, it rains it out. So there is no capacity check during advection at all — an overfull column
 * simply condenses on the same step.
 *
 * <h2>Gather from a frozen state, then apply</h2>
 * As with water. Deciding where a column's vapour goes from a half-updated grid makes the answer
 * depend on iteration order, and two-phase is what makes it not.
 *
 * <h2>Why the wind cannot be allowed to have divergence</h2>
 * See {@link Wind}: the field is a curl, so it is divergence-free by construction. If it were not,
 * this rule would faithfully carry humidity into cells the wind converges on and pile it there
 * forever — permanent fog banks that no weather explains. The conservation here would still hold; it
 * is the <i>distribution</i> that would be wrong, which is much harder to notice.
 */
public final class HumidityTransport {

    /**
     * The four directions vapour can move, in the order the passes run.
     *
     * <p>The order is part of the world, exactly as it is for water: it decides which of two
     * competing sources reaches a column first.
     */
    private static final int[][] LATERAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /**
     * How much of a column's vapour a full-strength wind carries away in one step.
     *
     * <p>Not all of it. A step that emptied a column would make humidity a travelling packet rather
     * than a field, and packets do not produce fronts, gradients or rain shadows. A third per step is
     * enough to move weather across a region in a sensible time while leaving a trail behind it.
     */
    private static final double CARRY = 0.34;

    /**
     * How much of the difference between neighbours evens out per step, on top of advection.
     *
     * <p>Diffusion is what turns a moving blob into a front with an edge. Small, because a field that
     * mixes faster than it travels arrives everywhere at once and it never rains anywhere in
     * particular.
     */
    private static final double MIXING = 0.08;

    private HumidityTransport() {
    }

    /**
     * One step of the field tier over a box of columns.
     *
     * <p>Runs at about 1 Hz rather than every tick — §8 says the field tier advances "at ~1 Hz or
     * slower", and weather has no business moving at twenty steps a second.
     *
     * @return drops that fell as rain
     */
    public static int step(HumidityGrid grid, Bounds bounds, long tick, long salt) {
        advect(grid, bounds, tick, salt);
        return condense(grid, bounds, tick, salt);
    }

    /**
     * Carry vapour downwind, and mix a little of it sideways.
     *
     * <p>The wind is sampled per column and split into its two axial components, so a diagonal wind
     * sends vapour along both axes in proportion. Fractional amounts are rounded stochastically
     * (§12): a wind carrying a third of a drop really does move one drop on one step in three, which
     * is exact in expectation and exactly conservative every time.
     */
    public static void advect(HumidityGrid grid, Bounds bounds, long tick, long salt) {
        int width = bounds.maxX() - bounds.minX() + 1;
        int depth = bounds.maxZ() - bounds.minZ() + 1;
        int[] give = new int[width * depth * LATERAL.length];

        // Gather. Nothing is written here, so every column decides from the same frozen sky.
        for (int ix = 0; ix < width; ix++) {
            for (int iz = 0; iz < depth; iz++) {
                int x = bounds.minX() + ix;
                int z = bounds.minZ() + iz;
                if (!grid.contains(x, z)) {
                    continue;
                }
                int held = grid.humidity(x, z);
                if (held <= 0) {
                    continue;
                }

                long hash = Rng.positionHash(z, x, salt);
                double windX = Wind.x(x << COLUMN_SHIFT, z << COLUMN_SHIFT, tick, salt);
                double windZ = Wind.z(x << COLUMN_SHIFT, z << COLUMN_SHIFT, tick, salt);

                int cell = (ix * depth + iz) * LATERAL.length;
                give[cell] = carried(held, -windX, hash, tick, salt, 0);
                give[cell + 1] = carried(held, windX, hash, tick, salt, 1);
                give[cell + 2] = carried(held, -windZ, hash, tick, salt, 2);
                give[cell + 3] = carried(held, windZ, hash, tick, salt, 3);

                // Never promise more than the column has. Trimmed in pass order so the total handed
                // out is exactly what was there, whatever the wind asked for.
                int promised = give[cell] + give[cell + 1] + give[cell + 2] + give[cell + 3];
                for (int d = 0; promised > held && d < LATERAL.length; d++) {
                    int trim = Math.min(give[cell + d], promised - held);
                    give[cell + d] -= trim;
                    promised -= trim;
                }

                // Mixing, on top: a share of the gap to each neighbour, which is what gives a front
                // an edge rather than a step.
                for (int d = 0; d < LATERAL.length; d++) {
                    int nx = x + LATERAL[d][0];
                    int nz = z + LATERAL[d][1];
                    int gap = held - neighbourHumidity(grid, nx, nz);
                    if (gap <= 1) {
                        continue;
                    }
                    long mixHash = Rng.positionHash(nz, nx, hash);
                    int mixed = (int) Rng.stochasticFloor(MIXING * gap / 2.0,
                            Rng.uniform(mixHash, tick, Rng.STREAM_HUMIDITY_MIXING));
                    give[cell + d] += Math.max(0, Math.min(mixed, held - promised));
                    promised += Math.max(0, Math.min(mixed, held - promised));
                }
            }
        }

        // Apply, one direction per pass. See the class note: this is the whole of the conservation
        // argument, and collapsing it into a single scatter breaks it.
        for (int d = 0; d < LATERAL.length; d++) {
            for (int ix = 0; ix < width; ix++) {
                for (int iz = 0; iz < depth; iz++) {
                    int amount = give[(ix * depth + iz) * LATERAL.length + d];
                    if (amount <= 0) {
                        continue;
                    }
                    int x = bounds.minX() + ix;
                    int z = bounds.minZ() + iz;
                    int held = grid.humidity(x, z);
                    int moved = Math.min(amount, held);
                    if (moved <= 0) {
                        continue;
                    }
                    grid.setHumidity(x, z, held - moved);

                    int nx = x + LATERAL[d][0];
                    int nz = z + LATERAL[d][1];
                    if (grid.contains(nx, nz)) {
                        grid.setHumidity(nx, nz, grid.humidity(nx, nz) + moved);
                    } else {
                        // Off the edge of what is loaded. Air really does continue out there, so this
                        // is not a leak — but it is booked, because an unbooked one looks the same.
                        grid.escape(moved);
                    }
                }
            }
        }
    }

    /**
     * Rain out whatever the sky cannot hold.
     *
     * <p>Not all of the excess at once. Rain that dumped a column's whole surplus in one step would
     * be a single violent event followed by clear sky, where what a front actually does is rain
     * steadily for as long as it takes to cross. Half the excess per step gives a decaying tail,
     * which reads as a shower passing.
     *
     * @return drops that fell
     */
    public static int condense(HumidityGrid grid, Bounds bounds, long tick, long salt) {
        int fallen = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (!grid.contains(x, z)) {
                    continue;
                }
                int held = grid.humidity(x, z);
                int excess = held - grid.capacity(x, z);
                if (excess <= 0) {
                    continue;
                }
                long hash = Rng.positionHash(z, x, salt);
                int falls = (int) Rng.stochasticFloor(excess / 2.0,
                        Rng.uniform(hash, tick, Rng.STREAM_RAINFALL));
                falls = Math.max(1, Math.min(falls, held));
                grid.setHumidity(x, z, held - falls);
                grid.rain(x, z, falls);
                fallen += falls;
            }
        }
        return fallen;
    }

    /**
     * How much vapour a wind component carries, as a whole number of drops.
     *
     * <p>Zero against the wind: a component pointing the other way carries nothing in this direction,
     * and the opposite direction's pass will handle it.
     */
    private static int carried(int held, double component, long hash, long tick, long salt,
                               int direction) {
        if (component <= 0.0) {
            return 0;
        }
        double share = Math.min(1.0, component) * CARRY * held;
        return (int) Rng.stochasticFloor(share,
                Rng.uniform(hash, tick, Rng.STREAM_HUMIDITY_ADVECTION + direction));
    }

    /** A neighbour's humidity, or its climate baseline where the grid does not reach. */
    private static int neighbourHumidity(HumidityGrid grid, int x, int z) {
        return grid.contains(x, z) ? grid.humidity(x, z) : grid.baseline(x, z);
    }

    /**
     * How many blocks a column is across, as a power of two.
     *
     * <p>One column per chunk. §8 suggests "per chunk-section, or 4×4×4 cells; ~10k cells at normal
     * view distance", and a chunk is the unit the game already loads, saves and iterates — a coarser
     * grid than the terrain by exactly the factor that makes a field tier affordable.
     */
    public static final int COLUMN_SHIFT = 4;

    /** The box of columns a step runs over, inclusive. */
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {

        public Bounds {
            if (maxX < minX || maxZ < minZ) {
                throw new IllegalArgumentException(
                        "empty bounds: " + minX + ".." + maxX + ", " + minZ + ".." + maxZ);
            }
        }
    }
}
