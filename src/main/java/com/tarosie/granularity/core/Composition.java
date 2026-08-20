package com.tarosie.granularity.core;

import java.util.Arrays;

/**
 * The nine slots of one block.
 *
 * <p>For natural blocks this is never stored — it is derived on demand from position and salt
 * (design §4), at block break for drops and at chunk mesh build for texture. Only crafted blocks
 * carry a composition as real data, and they are sparse relative to natural stone.
 *
 * <p>Immutable. The nine-ness is the invariant: {@link #SLOTS} is not a tuning parameter but the
 * reason the whole system works — it is what lets any block be reconstructed in a 3×3 crafting
 * grid, and it is the fixed-point denominator that makes conservation exact (design §12).
 */
public final class Composition {

    /** Nine. See the class note before changing it. */
    public static final int SLOTS = 9;

    /**
     * The largest id a slot can hold, and so the size of the roster.
     *
     * <p>These were bytes, which was ample for a roster fixed at compile time and became a hazard the
     * moment datapacks could add to it: past 256 grains the cast wrapped, and a slot came back as a
     * completely different material with nothing to indicate it had happened. Widening costs nine
     * bytes per crafted block — and crafted blocks are sparse against world stone, which is the whole
     * reason storing anything here is affordable — so it is the cheapest possible way to be rid of
     * the question. {@link Grains} refuses to allocate past this rather than wrapping.
     */
    public static final int MAX_GRAIN_ID = Short.MAX_VALUE;

    private final short[] slots;

    private Composition(short[] slots) {
        this.slots = slots;
    }

    /** Wraps nine grain ids. The array is copied, so the caller may reuse its buffer. */
    public static Composition of(int[] grainIds) {
        if (grainIds.length != SLOTS) {
            throw new IllegalArgumentException("Expected " + SLOTS + " slots, got " + grainIds.length);
        }
        short[] slots = new short[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            int id = grainIds[i];
            if (id < 0 || id >= Grains.count()) {
                throw new IllegalArgumentException("Unassigned grain id in slot " + i + ": " + id);
            }
            slots[i] = (short) id;
        }
        return new Composition(slots);
    }

    /** A block of nine identical slots — the common case for tests and for pure strata. */
    /**
     * The nine slots that best represent several blocks' worth of grains pooled together.
     *
     * <p>Cutting takes more than one block: three cobblestones make six slabs. Taking the first
     * block's composition and discarding the rest was a laundering hole — one marble cobblestone
     * beside two granite ones produced six marble slabs, and hammering them turned granite into
     * marble for free. Pooling makes the result carry the mix that actually went in, so a slab drawn
     * from it returns granite twice as often as marble, which is what was put in the grid.
     *
     * <p>Nine slots cannot express every ratio — twenty-seven grains reduced to nine is a division by
     * three, and a single gold grain among twenty-six of rock rounds away. The largest-remainder
     * method is used because it is the one that keeps the total at exactly nine while giving the
     * spare slots to whatever was most under-represented by flooring. A grain too rare to earn a
     * ninth of the block is a grain the block genuinely cannot show.
     */
    public static Composition pooled(java.util.List<Composition> inputs) {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("Nothing to pool");
        }
        if (inputs.size() == 1) {
            return inputs.get(0);
        }
        java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
        for (Composition input : inputs) {
            for (int slot = 0; slot < SLOTS; slot++) {
                counts.merge(input.grainId(slot), 1, Integer::sum);
            }
        }
        int total = inputs.size() * SLOTS;

        // Floor each share, then hand the slots that flooring lost to the largest remainders.
        java.util.Map<Integer, Integer> share = new java.util.LinkedHashMap<>();
        java.util.List<java.util.Map.Entry<Integer, Integer>> byRemainder =
                new java.util.ArrayList<>(counts.entrySet());
        int assigned = 0;
        for (java.util.Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            int floor = entry.getValue() * SLOTS / total;
            share.put(entry.getKey(), floor);
            assigned += floor;
        }
        byRemainder.sort(java.util.Comparator
                .comparingInt((java.util.Map.Entry<Integer, Integer> e) ->
                        (e.getValue() * SLOTS) % total)
                .reversed());
        for (int i = 0; assigned < SLOTS; i = (i + 1) % byRemainder.size()) {
            share.merge(byRemainder.get(i).getKey(), 1, Integer::sum);
            assigned++;
        }

        int[] ids = new int[SLOTS];
        int slot = 0;
        for (java.util.Map.Entry<Integer, Integer> entry : share.entrySet()) {
            for (int n = 0; n < entry.getValue() && slot < SLOTS; n++) {
                ids[slot++] = entry.getKey();
            }
        }
        return of(ids);
    }

    public static Composition uniform(int materialId) {
        int[] ids = new int[SLOTS];
        Arrays.fill(ids, materialId);
        return of(ids);
    }

    /** The grain id in one slot. */
    public int grainId(int slot) {
        return slots[slot] & 0xFFFF;
    }

    /** The grain in one slot. */
    public Grain grainAt(int slot) {
        return Grains.byId(grainId(slot));
    }

    public GrainClass classAt(int slot) {
        return grainAt(slot).clazz();
    }

    /** How many slots hold the given class. */
    public int count(GrainClass materialClass) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (classAt(i) == materialClass) {
                n++;
            }
        }
        return n;
    }

    /** How many slots hold the given exact grain. */
    public int countGrain(Grain grain) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (grainId(i) == grain.id()) {
                n++;
            }
        }
        return n;
    }

    /**
     * The distinct grains present and how many slots each holds, written into caller-supplied
     * buffers of length {@link #SLOTS}.
     *
     * <p>The natural way to ask this is {@link #grainCounts}, and it is the wrong one for anything
     * on the meshing path: it allocates and then scans an array as long as the <b>roster</b>, when a
     * block has at most nine distinct grains in it no matter how many exist. That cost was invisible
     * while the roster was a fixed three dozen entries and grows without limit once datapacks can
     * add to it. Everything here is bounded by nine.
     *
     * <p>Entries come out in first-appearance order, so callers that care about ties must break them
     * on the id explicitly rather than relying on the order.
     *
     * @return how many entries were written
     */
    public int tally(int[] ids, int[] counts) {
        int distinct = 0;
        next:
        for (int slot = 0; slot < SLOTS; slot++) {
            int id = grainId(slot);
            for (int i = 0; i < distinct; i++) {
                if (ids[i] == id) {
                    counts[i]++;
                    continue next;
                }
            }
            ids[distinct] = id;
            counts[distinct] = 1;
            distinct++;
        }
        return distinct;
    }

    /**
     * Counts per grain id, length {@link Grains#count()}.
     *
     * <p>Convenient, and roster-sized — use {@link #tally} anywhere that runs per block.
     */
    public int[] grainCounts() {
        int[] counts = new int[Grains.count()];
        for (int i = 0; i < SLOTS; i++) {
            counts[grainId(i)]++;
        }
        return counts;
    }

    /**
     * How much of the block is pore rather than solid — its porosity (findings §6.1).
     *
     * <p>Counts water as well as air, because a pore that has filled with water is still a pore. The
     * distinction that matters to a mason is how much rock is there, and that does not change when
     * it rains. {@link #freeSlots} is the other question — how much room is <i>left</i> — and the two
     * are only the same number in dry rock.
     */
    public int porosity() {
        return count(GrainClass.AIR) + count(GrainClass.WATER);
    }

    /**
     * How many pores are still empty, which is also how much water this block can take.
     *
     * <p>Findings §6.2 spends a paragraph on this: budgeting free space against only rock and water,
     * and forgetting a third occupant, drove the prototype's {@code free()} negative. So this is
     * defined as "not occupied" rather than as a subtraction from nine, and a new grain class costs
     * nothing here.
     */
    public int freeSlots() {
        return count(GrainClass.AIR);
    }

    /**
     * How many slots hold water — the block's water content in drops.
     *
     * <p>This number is also the block's <i>level</i>: design §7 maps nine drops onto a source block
     * and fewer onto vanilla's flow levels, so the slot count and the fluid state are one quantity
     * seen from two sides. Converting between them is {@link WaterLevels}'s job and belongs nowhere
     * else.
     */
    public int water() {
        return count(GrainClass.WATER);
    }

    /**
     * The class holding the most slots, which by design §7 is what determines block identity and
     * collision — walkable versus swimmable. Ties break toward the earlier enum constant, so the
     * result is deterministic rather than dependent on iteration order.
     */
    public GrainClass majorityClass() {
        int[] counts = new int[GrainClass.values().length];
        for (int i = 0; i < SLOTS; i++) {
            counts[classAt(i).ordinal()]++;
        }
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        return GrainClass.values()[best];
    }

    /** How many distinct grains appear. One means a pure block; more means a border. */
    public int distinctGrains() {
        return tally(new int[SLOTS], new int[SLOTS]);
    }

    /**
     * How many distinct grains appear among slots of one class.
     *
     * <p>The bedrock invariant is stated over {@link GrainClass#ROCK} alone: at the datum the rock
     * is a single stone, but minerals come from their own province fields and are under no
     * obligation to match. Asking about all classes at once would conflate the two.
     */
    public int distinctGrains(GrainClass grainClass) {
        int[] ids = new int[SLOTS];
        int[] counts = new int[SLOTS];
        int distinct = tally(ids, counts);
        int n = 0;
        for (int i = 0; i < distinct; i++) {
            if (Grains.byId(ids[i]).clazz() == grainClass) {
                n++;
            }
        }
        return n;
    }

    /**
     * The slot-weighted mean colour of one class, or -1 if this composition holds none of it.
     *
     * <p>Design §3's smelt arithmetic. Smelting does <b>not</b> rewrite the grains — an earlier
     * version snapped the mean to the nearest named stone, which turned five chalk and four shale
     * into "nine diorite". Nearness is not identity: a mixture stays a mixture, keeps its grains,
     * and simply renders and names itself as a mixture. Only the block type changes.
     */
    public int averageTint(GrainClass grainClass) {
        int[] ids = new int[SLOTS];
        int[] counts = new int[SLOTS];
        int distinct = tally(ids, counts);
        long r = 0;
        long g = 0;
        long b = 0;
        int total = 0;
        for (int i = 0; i < distinct; i++) {
            int n = counts[i];
            Grain grain = Grains.byId(ids[i]);
            if (grainClass != null ? grain.clazz() != grainClass : !grain.clazz().isObtainable()) {
                continue;
            }
            r += (long) ((grain.tint() >> 16) & 0xFF) * n;
            g += (long) ((grain.tint() >> 8) & 0xFF) * n;
            b += (long) (grain.tint() & 0xFF) * n;
            total += n;
        }
        if (total == 0) {
            return -1;
        }
        int half = total / 2;
        return (int) ((((r + half) / total) << 16) | (((g + half) / total) << 8) | ((b + half) / total));
    }

    /**
     * The single grain every slot holds, or null if this block is a mixture.
     *
     * <p>The naming rule turns on exactly this: a block of one grain earns that grain's name
     * ("Granite Cobblestone"), and anything mixed is plain "Cobblestone".
     */
    public Grain soleGrain() {
        int first = grainId(0);
        for (int i = 1; i < SLOTS; i++) {
            if (grainId(i) != first) {
                return null;
            }
        }
        return Grains.byId(first);
    }

    /** True when every slot is a mineral class — no rock, no soil. Decides the smelt outcome. */
    public boolean isAllMineral() {
        for (int i = 0; i < SLOTS; i++) {
            GrainClass clazz = classAt(i);
            if (clazz == GrainClass.ROCK || !clazz.isMineral()) {
                return false;
            }
        }
        return true;
    }

    /** True when at least one slot is ore, precious ore or gem. */
    public boolean hasMineralInclusion() {
        for (int i = 0; i < SLOTS; i++) {
            GrainClass clazz = classAt(i);
            if (clazz != GrainClass.ROCK && clazz.isMineral()) {
                return true;
            }
        }
        return false;
    }

    /** The grain ids, as a fresh array. */
    public int[] toArray() {
        int[] out = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            out[i] = grainId(i);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Composition other && Arrays.equals(slots, other.slots);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(slots);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Composition[");
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(grainAt(i).name());
        }
        return sb.append(']').toString();
    }
}
