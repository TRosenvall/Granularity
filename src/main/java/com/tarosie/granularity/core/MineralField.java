package com.tarosie.granularity.core;

/**
 * Where each kind of ore is, independent of what rock it sits in.
 *
 * <p>The bug this fixes was structural: {@link CompositionFunction} resolved one colour per slot
 * from {@link ColourField} and then decided what class the slot was, so an ore slot inherited the
 * surrounding rock's colour. Grey igneous stone could only ever contain grey ore. But copper is
 * green wherever you find it — the mineral's colour is a property of the mineral, not of its host.
 *
 * <p>So ore, precious ore and gem each get their own province field. A region of grey basalt can
 * host a copper province, an iron province, or both across a boundary.
 *
 * <h2>Three fields, deliberately unaligned</h2>
 * Each class has its own stream and its own province size — 256, 320 and 208 blocks against the
 * bedrock map's 384. Different sizes mean their boundaries do not coincide, so the combinations
 * vary as you travel instead of every province changing at once. Ore provinces cross-cutting
 * lithology is also what real ore geology does.
 *
 * <p>The domain warp is shared with {@link ColourField} rather than recomputed. It is the expensive
 * half of a field sample and it has already been paid for by the time these are called; borrowing
 * it costs nothing and only means the provinces wander in sympathy with the bedrock regions, which
 * is harmless — the cell partitions are independent because the streams differ.
 *
 * <h2>Cost</h2>
 * These are sampled only for slots that actually resolve to a mineral class, which is a few percent
 * of slots. The rock field is still sampled per slot; these ride along for well under a tenth of
 * one extra sample per block.
 *
 * <h2>Open</h2>
 * The caller supplies the list this family admits, so the province only ever picks a mineral that
 * can actually occur in that country — see {@link Grains#admitted}. Sedimentary ground admits no
 * gems at all, and the empty list is handled by the caller rather than by a fallback here.
 */
public final class MineralField {

    private static final double ORE_REGION = 256.0;
    private static final double PRECIOUS_REGION = 320.0;
    private static final double GEM_REGION = 208.0;

    private MineralField() {
    }

    /**
     * The province colour for a mineral class at an already-warped position.
     *
     * @param materialClass one of {@link GrainClass#ORE}, {@link GrainClass#PRECIOUS_ORE},
     *                      {@link GrainClass#GEM}
     */
    public static Grain pick(GrainClass materialClass, java.util.List<Grain> admitted,
                             double warpedX, double warpedZ, long salt) {
        double region;
        int stream;
        switch (materialClass) {
            case ORE -> {
                region = ORE_REGION;
                stream = Rng.STREAM_ORE_PROVINCE;
            }
            case PRECIOUS_ORE -> {
                region = PRECIOUS_REGION;
                stream = Rng.STREAM_PRECIOUS_PROVINCE;
            }
            case GEM -> {
                region = GEM_REGION;
                stream = Rng.STREAM_GEM_PROVINCE;
            }
            default -> throw new IllegalArgumentException("Not a mineral class: " + materialClass);
        }
        long winner = Noise.cell2(warpedX / region, warpedZ / region, salt, stream);
        // Rendezvous hashing, so another mod's ore claims its own provinces without moving anyone
        // else's; see Grains.pick.
        return Grains.pick(admitted, winner);
    }
}
