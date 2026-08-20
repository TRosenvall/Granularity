package com.tarosie.granularity.core;

/**
 * A region of nine-slot blocks that water can move through.
 *
 * <p>The migration rule is written against this rather than against the world, and the split is not
 * ceremony. Findings §6.2 records a conservation bug in the prototype that a unit test caught
 * immediately and that would have been invisible in a running game — two drops appearing per tick
 * looks exactly like water behaving normally. Keeping the rule over an interface means the same
 * invariants can be asserted here, on a grid of integers, with no level, no chunks and no ticking.
 *
 * <h2>What the two numbers are</h2>
 * <ul>
 *   <li>{@link #grains} — how many of the nine slots are rock, ore or gem. Fixed: water moving
 *       through does not dissolve the rock. This is the derived composition, and it is where
 *       porosity comes from.</li>
 *   <li>{@link #water} — how many are water right now. This is the part that moves.</li>
 * </ul>
 * The rest is empty pore, and {@link #room} is the only capacity the rule ever consults. Findings
 * §6.2: the prototype drove its free-slot count negative by budgeting against rock and water while a
 * third occupant existed, so capacity is defined by subtracting <i>everything</i> that occupies a
 * slot rather than by naming the things that do.
 *
 * <h2>Outside is rock</h2>
 * A position the volume does not {@link #contains} answers as nine grains of rock: no water, no room.
 * Water therefore never leaves through an edge, which is what keeps conservation exact when the rule
 * runs over one chunk of a world that continues past it. The alternative — letting water flow into
 * blocks nothing is tracking — loses drops silently, and a fluid layer that leaks slowly is a fluid
 * layer nobody can debug.
 */
public interface WaterVolume {

    /** Whether this position is one the volume can read and write. */
    boolean contains(int x, int y, int z);

    /** Slots holding rock, ore or gem. Nine outside the volume. */
    int grains(int x, int y, int z);

    /** Slots holding water. Zero outside the volume. */
    int water(int x, int y, int z);

    /** Set the water content of a position the volume {@link #contains}. */
    void setWater(int x, int y, int z, int drops);

    /**
     * Slots that could take another drop.
     *
     * <p>Nine minus everything already in the block, never named occupant by occupant. Add a grain
     * class one day and this stays correct.
     */
    default int room(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return 0;
        }
        return Math.max(0, Composition.SLOTS - grains(x, y, z) - water(x, y, z));
    }
}
