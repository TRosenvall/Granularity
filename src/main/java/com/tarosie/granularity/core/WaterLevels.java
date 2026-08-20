package com.tarosie.granularity.core;

/**
 * Drops in slots, read as a vanilla fluid level — design §7's "almost 1:1" mapping, in one place.
 *
 * <p>A block's water content is a slot count, 0 to 9. Vanilla's water is a source plus eight flowing
 * amounts. Those are the same quantity seen from two sides, and the entire fluid layer depends on
 * that being true: water moving out of a rock's pores and into the world must arrive as the level it
 * was, or the conservation §7 insists on is a fiction the moment anything crosses the boundary.
 *
 * <h2>Why this is a class and not two expressions</h2>
 * Because the mapping is not the identity and the discrepancy is easy to write twice, differently.
 * Nine drops onto eight amounts is off by one at exactly one end, and an off-by-one that appears in
 * the placing code but not the reading code is a leak: break a rock, get slightly more water than it
 * held; absorb it back, get slightly less. There is no test that would catch a drop appearing here
 * and vanishing there except one that round-trips, which is what {@link #dropsFor} is for.
 *
 * <p>Kept in {@code core} and free of any Minecraft import so it stays a unit-testable integer fact.
 * Turning an amount into an actual {@code FluidState} is the caller's business.
 */
public final class WaterLevels {

    /** Vanilla's deepest flowing amount, and what a source reports. */
    public static final int MAX_AMOUNT = 8;

    private WaterLevels() {
    }

    /**
     * The vanilla fluid amount that represents this many drops: 0 for dry, 1–8 for flowing, and 8
     * for a full block — which is a source, so callers must ask {@link #isSource} too.
     *
     * <p>The squeeze is at the top. Nine drops is a full block and eight is vanilla's deepest
     * flowing water, so both report 8 and the source flag is what separates them. That is where §7's
     * "almost" lives: a full block and a nearly-full one look alike to the renderer and differ in
     * whether they feed their neighbours.
     */
    public static int amount(int drops) {
        if (drops <= 0) {
            return 0;
        }
        return Math.min(drops, MAX_AMOUNT);
    }

    /** True when this many drops fill the block, which is what makes it a source rather than flow. */
    public static boolean isSource(int drops) {
        return drops >= Composition.SLOTS;
    }

    /**
     * The inverse: how many drops a fluid of this amount is worth.
     *
     * <p>A source is nine because it fills its block. Everything else is its own amount. Carry both
     * arguments and the round trip is exact for all ten counts — the source flag is the bit that
     * separates the two that share an amount.
     *
     * <p>Drop it and the mapping is no longer injective, which is the whole of §7's "almost": eight
     * drops and nine both report amount 8, so an amount alone cannot say whether the block feeds its
     * neighbours. Any caller reading a fluid state must read {@code isSource} with it. A conservation
     * test that compares amounts rather than drops would pass while a drop went missing at exactly
     * that step.
     */
    public static int dropsFor(int amount, boolean source) {
        if (source) {
            return Composition.SLOTS;
        }
        return Math.max(0, Math.min(amount, MAX_AMOUNT));
    }

    /**
     * How much water this block can pass through itself in one step, in drops.
     *
     * <p>The free pores and nothing else. Rock with one empty slot conducts one drop — level-1 water
     * and no more — and rock with none conducts nothing at all, which is what makes an aquiclude an
     * aquiclude rather than a slow aquifer. Conductivity is not a second property to tune: it is the
     * composition, counted.
     */
    public static int conductivity(Composition composition) {
        return composition.freeSlots();
    }
}
