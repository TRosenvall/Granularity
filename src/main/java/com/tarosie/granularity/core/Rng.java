package com.tarosie.granularity.core;

/**
 * Deterministic position-hashed randomness — the port of the prototype's {@code rng.py}.
 *
 * <p><b>THIS IS A BIT-EXACT CONTRACT, NOT AN IMPLEMENTATION DETAIL.</b> See
 * {@code toy_geology_model/porting/PORTING_SPEC.md} §2. Composition, textures and drops are all
 * derived by hashing a position against a salt the client is told at login. If this diverges from
 * the reference by one bit, the client renders a different world than the server simulates — and it
 * fails silently, as cosmetic desync, not as a crash. Every routine here is asserted against
 * {@code porting/golden_vectors.json}; treat any diff as a deliberate world-breaking change.
 *
 * <p>Three ways to break it, all of which compile:
 * <ul>
 *   <li>Using {@code >>} instead of {@code >>>}. Java's {@code long} is signed, so an arithmetic
 *       shift corrupts every negative intermediate. This is the most likely cause of a failing
 *       vector.</li>
 *   <li>Substituting {@link java.util.Random}, {@code SplittableRandom} or {@code Math.random()}.
 *       The whole point is that a cell's draw depends only on its coordinates, so it is reproducible
 *       without reference to what ran before it — that is what makes the two-phase update
 *       order-independent.</li>
 *   <li>Adding overflow checks. The wraparound <i>is</i> the algorithm.</li>
 * </ul>
 *
 * <p><b>Axis mapping.</b> The argument order {@code (y, x)} is inherited from the prototype, where
 * the grid is indexed {@code [row][col]}. The prototype's {@code y} is a horizontal axis — it maps
 * to world <b>z</b>, not world height. Keeping the order rather than "fixing" it to {@code (x, z)}
 * is deliberate: a silent transpose here would be invisible until it produced a mirrored world.
 */
public final class Rng {

    // splitmix64 finalizer constants.
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long MIX_A = 0xBF58476D1CE4E5B9L;
    private static final long MIX_B = 0x94D049BB133111EBL;

    // Odd multipliers decorrelating each input axis before it enters the mixer.
    private static final long K_X = 0xD6E8FEB86659FD93L;
    private static final long K_Y = 0xA0761D6478BD642FL;
    private static final long K_TICK = 0xE7037ED1A0B428DBL;
    private static final long K_STREAM = 0x8EBC6AF09C88C6E3L;

    /**
     * Stream ids in use. These are part of the world — a run that changes them generates different
     * terrain from the same seed. The full allocation is in PORTING_SPEC §2.3; the ones below are
     * what the deterministic core itself names, with the rest declared by the tiers that own them.
     */
    public static final int STREAM_DIRECTION = 1;
    public static final int STREAM_MAGNITUDE = 2;
    public static final int STREAM_CREEP = 3;

    /**
     * The voxel migration rule's three draws: which neighbour receives, how much goes, and the creep
     * exception that lets still water settle. Ids 11–13 are the prototype's own — this rule is a
     * port rather than a new layer, so it inherits the stream ids the reference ran on and the
     * golden vectors that pin them.
     */
    public static final int STREAM_VOXEL_DIRECTION = 11;
    public static final int STREAM_VOXEL_MAGNITUDE = 12;
    public static final int STREAM_VOXEL_CREEP = 13;
    public static final int STREAM_PICKUP = 4;
    public static final int STREAM_DROP = 5;
    public static final int STREAM_RAINFALL = 6;
    public static final int STREAM_SEDIMENT_ADVECTION = 7;

    // Composition derivation (design §4). Block 40-51, allocated here because the prototype never
    // needed it — its terrain came from test fixtures. Added to PORTING_SPEC §2.3 so the registry
    // stays in one place; ids 8-39 are the transport tiers' and are not reused here.
    public static final int STREAM_COLOUR_WARP_X = 40;
    public static final int STREAM_COLOUR_WARP_Z = 41;
    public static final int STREAM_COLOUR_CELL = 42;
    public static final int STREAM_ORE = 43;
    public static final int STREAM_PRECIOUS_ORE = 44;
    public static final int STREAM_GEM = 45;
    public static final int STREAM_SLOT_JITTER = 46;
    public static final int STREAM_SLOT_CLASS = 47;
    public static final int STREAM_BEDROCK_TYPE = 48;
    public static final int STREAM_ORE_PROVINCE = 49;
    public static final int STREAM_PRECIOUS_PROVINCE = 50;
    public static final int STREAM_GEM_PROVINCE = 51;

    /**
     * The porosity layer, and the per-slot draw against it. Streams of their own, so adding porosity
     * left every other draw in the composition function producing exactly what it produced before —
     * only the slots air actually wins are different.
     *
     * <p>These are 52 and 53 because 47 and 48 were taken. An earlier version of this block declared
     * them as 47 and 48 anyway, directly above the fields that already held those ids: Java takes the
     * last assignment silently, so the porosity layer shared a stream with the slot-class draw and the
     * air draw shared one with the bedrock type. Nothing failed — the world simply had porosity
     * correlated with the ore it was supposed to be independent of. Adding an id means reading to the
     * end of this list first; the compiler will not do it for you.
     */
    public static final int STREAM_POROSITY = 52;
    public static final int STREAM_SLOT_AIR = 53;

    /**
     * The water table's regional swell, and the per-slot draw that decides whether a pore holds
     * water or air. Downstream of the air draw and on streams of their own, so the porous rock is in
     * the same places it was before there was any water in it — only what fills the pores changed.
     */
    public static final int STREAM_WATER_TABLE = 54;
    public static final int STREAM_SLOT_WATER = 55;

    /**
     * The atmosphere (design §11): the wind's potential, the four advection draws, and the mixing
     * that gives a front its edge.
     *
     * <p>Rainfall reuses id 6, the prototype's own rainfall stream, because it is the same quantity
     * arriving by a different route.
     */
    public static final int STREAM_WIND = 56;
    public static final int STREAM_HUMIDITY_ADVECTION = 57;
    public static final int STREAM_HUMIDITY_MIXING = 61;

    private Rng() {
    }

    /**
     * splitmix64 finalizer. Wraps on overflow, which is the algorithm rather than an accident.
     *
     * <p>The shifts must be {@code >>>}. With {@code >>} the vectors fail immediately, which is the
     * good case; the bad case is code that only sometimes sees a negative intermediate.
     */
    public static long mix64(long x) {
        x += GOLDEN;
        x ^= x >>> 30;
        x *= MIX_A;
        x ^= x >>> 27;
        x *= MIX_B;
        x ^= x >>> 31;
        return x;
    }

    /**
     * Per-cell hash of (position, salt), independent of time.
     *
     * <p>Hoist this out of tick loops: positions do not move, so it is computed once and only the
     * tick/stream mix in {@link #uniform} runs per step.
     *
     * <p>Coordinates are two's complement 64-bit. A widening {@code (long)} cast of an {@code int}
     * already does the right thing, so negative world coordinates hash identically to the reference.
     *
     * @param y prototype row axis — world <b>z</b>, see the class note on axis mapping
     * @param x prototype column axis — world x
     */
    public static long positionHash(long y, long x, long salt) {
        long h = mix64(salt ^ (x * K_X));
        return mix64(h ^ (y * K_Y));
    }

    /**
     * The same hash in three dimensions, for the layers that are genuinely volumetric.
     *
     * <p>{@link #positionHash} is two-dimensional by contract — the prototype's grids were rows and
     * columns — so height enters as a <b>per-layer salt</b> built from the same contracted routine
     * rather than from a new ad-hoc mix. Nothing here needs golden vectors of its own to be
     * trustworthy, because it is two applications of a routine that already has them.
     *
     * <p>Shared rather than reimplemented per caller. It was private to {@link CompositionFunction}
     * until the migration rule needed it too, and two copies of a world-defining hash is exactly the
     * kind of thing that drifts silently: the second copy works, the worlds differ, and no test
     * compares them. The composition golden pins this one for both.
     *
     * @param x world x
     * @param y world height
     * @param z world z
     */
    public static long positionHash(long x, long y, long z, long salt) {
        long layerSalt = positionHash(y, 0L, salt);
        // Rng's (y, x) order is the prototype's row/column indexing: its y is world z. The
        // transposition happens here, once, at the boundary between world space and the contract.
        return positionHash(z, x, layerSalt);
    }

    /**
     * Uniform in [0, 1) for one (position, tick, stream) triple.
     *
     * <p>{@code 0x1.0p-53} is exactly 2^-53 — the standard 53-bit-mantissa construction, exact in
     * both languages.
     *
     * @param baseHash the value from {@link #positionHash}
     * @param stream one of the {@code STREAM_*} ids, so that within a tick the direction draw and
     *               the magnitude draw are uncorrelated
     */
    public static double uniform(long baseHash, long tick, long stream) {
        long mixed = (tick * K_TICK) ^ (stream * K_STREAM);
        long h = mix64(baseHash ^ mixed);
        return (h >>> 11) * 0x1.0p-53;
    }

    /**
     * Rounds {@code value} down or up so the result is unbiased: E[out] == value.
     *
     * <p>{@code floor(v + u)} for u ~ U[0,1) is the whole of stochastic rounding (design §12): a
     * flux of ⅓ drop/tick moves one drop on one tick in three — exact in expectation, and exactly
     * conservative in every realization, which is the property that matters.
     */
    public static long stochasticFloor(double value, double u) {
        return (long) Math.floor(value + u);
    }

    /**
     * Splits integer {@code total} across bins with cumulative fractions {@code cumfrac}.
     *
     * <p>Reusing <i>a single</i> uniform across every bin is what makes this work. Because
     * {@code total * cumfrac[i]} is non-decreasing in i, so are the edges, so every output is
     * non-negative; and {@code cumfrac[n-1] == 1} forces the last edge to {@code total}, so the
     * split is exactly conservative — no leftover, no overshoot, no renormalisation. Each bin is
     * still unbiased, since E[floor(x + u)] == x.
     *
     * <p>Unused by the transport rule, which routes to one receiver at a time, but required
     * wherever a load divides across species.
     *
     * @param cumfrac non-decreasing, last element exactly 1.0
     * @return one count per bin, summing exactly to {@code total}
     */
    public static long[] stratifiedSplit(long total, double[] cumfrac, double u) {
        long[] out = new long[cumfrac.length];
        long prev = (long) Math.floor(u);
        for (int i = 0; i < cumfrac.length; i++) {
            long edge = (long) Math.floor(cumfrac[i] * total + u);
            out[i] = edge - prev;
            prev = edge;
        }
        return out;
    }
}
