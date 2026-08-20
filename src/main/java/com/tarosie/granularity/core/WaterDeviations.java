package com.tarosie.granularity.core;

import java.util.HashMap;
import java.util.Map;

/**
 * How much a block's water differs from what the field says it should hold — the stored half of
 * design §6's split rule.
 *
 * <blockquote>dynamic quantity = noise-derived equilibrium baseline + sparsely stored deviation
 * </blockquote>
 *
 * <p>{@link WaterTable} supplies the baseline; this is the deviation. Water that has actually moved —
 * rained in, drained out, released by a pickaxe — is the difference between where the field says
 * groundwater stands and where it stands now.
 *
 * <h2>Why a deviation and not the water itself</h2>
 * Because almost all of the world is at equilibrium, and a deviation of zero needs no entry. Storing
 * absolute water would mean storing nine numbers per chunk section that a pure function already
 * knows. §4's derive-don't-store is not a preference here: at world-stone scale it is the difference
 * between a save file and a database.
 *
 * <p>It also gives entries a way to expire. A deviation decays toward zero, and when it reaches zero
 * the entry is dropped and the block is back to being derived — which is the mechanism that keeps
 * this sparse over time rather than merely sparse on the first day.
 *
 * <h2>Clamping is the caller's job</h2>
 * This stores a number; it does not know how many pores a block has. {@link #waterAt} takes the
 * baseline and the capacity and does the arithmetic, because both come from the composition and only
 * the caller has it.
 */
public final class WaterDeviations {

    /**
     * Deviations by packed position. A block at equilibrium has no entry at all, which is the
     * common case by an enormous margin.
     *
     * <p>Boxed keys and values, deliberately: this is the sparse, budgeted tier, and the map is
     * consulted only for blocks a step is actually visiting. If that stops being true — if something
     * starts walking every block of a chunk through here — this wants to become a primitive map
     * before it wants anything else.
     */
    private final Map<Long, Integer> deltas = new HashMap<>();

    /** How many entries are stored. Zero for the overwhelming majority of chunks. */
    public int size() {
        return deltas.size();
    }

    public boolean isEmpty() {
        return deltas.isEmpty();
    }

    /** The deviation at a position, or zero where the block is at its derived baseline. */
    public int deviation(long packed) {
        return deltas.getOrDefault(packed, 0);
    }

    /**
     * The water a block actually holds: its baseline, moved by whatever deviation is stored, and
     * never outside what its pores can hold.
     *
     * @param baseline what {@link CompositionFunction} derived for this position
     * @param pores    how many slots could hold water at all
     */
    public int waterAt(long packed, int baseline, int pores) {
        return Math.max(0, Math.min(pores, baseline + deviation(packed)));
    }

    /**
     * Record that a block now holds this much water, given what it would hold left alone.
     *
     * <p>Storing back a value equal to the baseline removes the entry rather than writing a zero.
     * A map that accumulates zeros is a map that never gets smaller, and this one has to survive
     * being saved with a chunk.
     */
    public void setWaterAt(long packed, int baseline, int drops) {
        int delta = drops - baseline;
        if (delta == 0) {
            deltas.remove(packed);
        } else {
            deltas.put(packed, delta);
        }
    }

    /**
     * Move every deviation one step toward zero, dropping the entries that reach it.
     *
     * <p>This is what §6 means by decaying back toward baseline, and it is doing two jobs at once.
     * Physically, disturbed groundwater does return to its equilibrium level once whatever disturbed
     * it stops. Practically, it is the only thing that bounds the size of this map — without it, a
     * long-played world accumulates a permanent entry for every block anyone ever dug near.
     *
     * @return how many entries expired
     */
    public int decay() {
        int expired = 0;
        var iterator = deltas.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Integer> entry = iterator.next();
            int delta = entry.getValue();
            int next = delta > 0 ? delta - 1 : delta + 1;
            if (next == 0) {
                iterator.remove();
                expired++;
            } else {
                entry.setValue(next);
            }
        }
        return expired;
    }

    /**
     * Forget a position outright, whatever it held.
     *
     * <p>Not the same as writing the baseline back. That says "this block is at equilibrium"; this
     * says "there is no such block any more" — the caller broke it.
     *
     * @return whether anything was stored there
     */
    public boolean remove(long packed) {
        return deltas.remove(packed) != null;
    }

    /** The stored entries, for saving. Positions are packed by the caller's own scheme. */
    public Map<Long, Integer> entries() {
        return deltas;
    }

    /** Replace the contents wholesale, for loading. */
    public void load(Map<Long, Integer> stored) {
        deltas.clear();
        stored.forEach((packed, delta) -> {
            if (delta != 0) {
                deltas.put(packed, delta);
            }
        });
    }
}
