package com.tarosie.granularity.core;

/**
 * A coarse grid of air columns that humidity moves through — design §8's <b>field tier</b>.
 *
 * <p>One value per column, not per block. §11 is explicit that the atmosphere is column-integrated
 * fields rather than per-block air, "how real weather models work", and findings §5.1 makes it a
 * requirement rather than a preference: during rain the active set is <b>18–40% of the loaded
 * world</b>, so the block tier cannot be the thing that carries a storm. Rain enters here.
 *
 * <p>Deliberately the same shape as {@link WaterVolume}: an interface the rule runs over, so the
 * conservation invariants can be asserted on a grid of integers with no level, no chunks and no
 * ticking. That is what caught the two-drop leak in the water rule, and humidity has the identical
 * trap waiting — several cells advecting into one.
 *
 * <h2>Units</h2>
 * Integers, in the same drops the rock counts. A drop that evaporates off a lake, crosses two hundred
 * blocks of sky and falls as rain into a porous bed is one drop the whole way, which is what lets
 * §11's "conservation throughout" be a checkable claim rather than a hope.
 *
 * <h2>Outside is not a wall</h2>
 * Unlike rock, air genuinely continues past whatever is loaded. A cell the grid does not
 * {@link #contains} reports its <i>baseline</i> humidity — what the field says the climate there
 * should be — and accepts what it is given without storing it. Humidity blowing off the edge of the
 * simulated area is booked as leaving, and humidity blowing in arrives at the ambient value. Treating
 * the edge as a wall would pile weather against the render distance, which is both wrong and
 * extremely visible.
 */
public interface HumidityGrid {

    /** Whether this column is one the grid can read and write. */
    boolean contains(int columnX, int columnZ);

    /** Drops of water vapour standing over this column. */
    int humidity(int columnX, int columnZ);

    /** Set the humidity of a column the grid {@link #contains}. */
    void setHumidity(int columnX, int columnZ, int drops);

    /**
     * The most vapour this column can hold before it rains — §11's temperature-dependent capacity.
     *
     * <p>The single most important number in the model, because everything §11 wants to emerge comes
     * out of it varying across the map. Cold air holds less, so air forced up a mountain rains on the
     * windward side; having rained, it crosses the ridge dry, and the far side is a desert. Neither
     * of those is written anywhere — they are what a capacity that falls with height does to a wind
     * that keeps blowing.
     */
    int capacity(int columnX, int columnZ);

    /**
     * What the climate says this column holds when nothing is happening.
     *
     * <p>The derived half of §6's split rule, applied to air. Columns outside the grid report this,
     * so weather blowing in from unloaded country arrives at a sensible value rather than at zero.
     */
    int baseline(int columnX, int columnZ);

    /** Take {@code drops} out of the sky here as rain. Called when capacity is exceeded. */
    void rain(int columnX, int columnZ, int drops);

    /** Book vapour that left the simulated area, so conservation can still be checked. */
    default void escape(int drops) {
    }
}
