package com.tarosie.granularity.core;

/**
 * Where the groundwater stands — design §7's hydrology map, read as an elevation.
 *
 * <p>Porosity gave the rock somewhere to put water (findings §6.1: the free slots <i>are</i> the
 * porosity). This says which of those free slots are actually wet. Below the table a pore holds
 * water; above it a pore holds air. Nothing else is needed to make an aquifer: a porous bed under
 * the table is one, and a tight bed is not, because it had no pores to fill.
 *
 * <h2>An elevation, not a depth</h2>
 * The obvious formulation is "saturated below <i>n</i> blocks of cover", and it is wrong in the way
 * that matters: a water table is a <b>surface</b>, roughly level across a region, which is why one
 * hillside can be dry at y=80 while the valley beside it is wet at the same height. Depth-below-cover
 * would instead drape the water around every hill and put an aquifer under the summit of a mountain.
 *
 * <p>Height is also the one thing this can honestly measure. {@link CompositionFunction} is a pure
 * function of position, so it cannot ask how tall the terrain overhead is — the same limit its own
 * javadoc records for {@code purity}. An elevation field needs no such answer.
 *
 * <h2>What §6 asks for and this does not do</h2>
 * §6's baseline is {@code f(biome humidity, depth below surface, sea-level proximity, porosity)}.
 * Porosity is here, through the pores themselves. The other three are not: biome and sea proximity
 * are level lookups a pure function cannot make, and there is no case yet that needs them — the
 * regional swell below stands in for the drainage-scale variation they would supply. They belong to
 * whatever tier gets to consult the level, alongside the sparse deviations §6 pairs with this.
 *
 * <p>This is the <b>equilibrium baseline</b> half of §6's split rule. The stored-deviation half —
 * rain, drainage, a player breaching a pocket — decays back toward what this returns.
 */
public final class WaterTable {

    /**
     * The height the table stands at with no regional variation, which is one below vanilla's sea
     * level of 63. Not at it: an ocean's floor would otherwise sit exactly on the boundary, and a
     * boundary is the one place a field's value is worth nothing.
     */
    private static final double DATUM = 62.0;

    /**
     * How far the table swells above and below the datum, in blocks.
     *
     * <p>Modest on purpose. Real water tables are subdued replicas of the terrain — they rise under
     * uplands, but by far less than the ground does, because water flows sideways to level itself and
     * rock does not. Twenty blocks of relief across a continent is the scale that reads as "the
     * ground is wetter over here", which is what this is for. It is not trying to reproduce the
     * terrain overhead, which it cannot see.
     */
    private static final double SWELL = 20.0;

    /**
     * Wavelength of that swell, in blocks. Long — this is drainage-basin scale, several times the
     * width of the {@link ColourField} regions the rock comes in, so a single stone province spans
     * wet ground and dry.
     */
    private static final double SWELL_FREQUENCY = 1.0 / 1024.0;

    private static final int SWELL_OCTAVES = 2;

    /**
     * The capillary fringe: how far above the table pores are still partly wet, in blocks.
     *
     * <p>Real, not a softening. Water climbs into fine pores against gravity, so the wet rock does
     * not stop at a line. Four blocks makes the transition legible when you mine down into it —
     * damp, damper, wet — where a hard edge would read as a mistake.
     */
    private static final double FRINGE = 4.0;

    private WaterTable() {
    }

    /**
     * The height the water table stands at above this column, in blocks.
     *
     * <p>A function of x and z only. Groundwater under a column is at one level by definition; if
     * this varied with y it would not be a table.
     */
    public static double elevation(int x, int z, long salt) {
        double swell = Noise.fbm2(x, z, SWELL_FREQUENCY, salt, Rng.STREAM_WATER_TABLE, SWELL_OCTAVES);
        return DATUM + (swell - 0.5) * 2.0 * SWELL;
    }

    /**
     * How wet this position's pores are, from 0 (dry) to 1 (every pore full).
     *
     * <p>Saturated everywhere below the table rather than decaying with depth, and that is the
     * physical answer rather than a simplification: below the table the rock is <i>under</i> the
     * water, and pressure only rises with depth. What varies down there is how many pores exist,
     * which is the porosity field's business and already varies plenty.
     */
    public static double saturation(int x, int y, int z, long salt) {
        double table = elevation(x, z, salt);
        if (y <= table) {
            return 1.0;
        }
        if (y >= table + FRINGE) {
            return 0.0;
        }
        return 1.0 - (y - table) / FRINGE;
    }
}
