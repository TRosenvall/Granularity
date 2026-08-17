package com.tarosie.granularity.core;

import java.util.List;

/**
 * The derived composition — design §4's "pure function of (position, world seed)".
 *
 * <p>Nothing here is stored. A natural block has no block entity and no exploded blockstate; its
 * nine slots are recomputed at block break, for drops, and at chunk mesh build, for texture. That
 * is the same pattern vanilla uses for bedrock dithering, and it is the discipline that makes a
 * simulated world affordable.
 *
 * <p>Four layers feed it, all from §4:
 * <ul>
 *   <li>{@link ColourField} — which region, and therefore which ore family.</li>
 *   <li>Ore / precious / gem probability layers — higher-frequency 3D noise. Seams are where a
 *       layer spikes; <i>residual</i> ore is the same layer's baseline floor, which is what keeps
 *       mining from ever being fully empty-handed.</li>
 *   <li>A vertical purity gradient — richer and less muddled toward bedrock, so depth is a
 *       progression axis and a prospecting one.</li>
 *   <li>Per-slot positional jitter — the dither mechanism. Borders need no blending logic because
 *       each slot samples the colour field slightly offset from its neighbours, so a boundary block
 *       drops five red chunks and four blue on its own.</li>
 * </ul>
 *
 * <p>Purity ties the last two together: jitter shrinks to zero at bedrock, so the same parameter
 * that enriches ore with depth is the one that makes the bedrock map read at full certainty.
 *
 * <h2>Not yet here</h2>
 * <ul>
 *   <li><b>Porosity.</b> Stone comes out with nine material slots and no {@link GrainClass#EMPTY}
 *       ones, so a break yields nine objects flat. Findings §6.1 established that free slots
 *       <i>are</i> the porosity, so this is where an impermeable/porous distinction enters — in
 *       Phase 7, alongside the perched water tables that need it.</li>
 *   <li><b>Soil.</b> Design §6's sand/silt/clay triangle uses this same machinery with a different
 *       class mix; Phase 7.</li>
 *   <li><b>Real surface height.</b> {@link #purity} measures depth against a fixed datum rather
 *       than against terrain, because a pure function of position cannot ask how tall the mountain
 *       above it is. Phase 5 owns worldgen and can supply a cached column height.</li>
 * </ul>
 */
public final class CompositionFunction {

    /** At or above this height, compositions are at their most mixed. */
    private static final double PURITY_TOP = 64.0;

    /** The bedrock datum: purity 1, zero jitter, the colour field at full certainty. */
    private static final double PURITY_BOTTOM = -64.0;

    /** Colour-field sampling jitter at the surface, in blocks. Scales to zero at bedrock. */
    private static final double JITTER_MAX_BLOCKS = 24.0;

    // Ore layers. Wavelengths are in blocks; y is compressed so seams lie flatter than they are
    // wide, which is what makes them read as strata rather than as blobs.
    private static final double ORE_FREQUENCY = 1.0 / 40.0;
    private static final double PRECIOUS_FREQUENCY = 1.0 / 60.0;
    private static final double GEM_FREQUENCY = 1.0 / 24.0;
    private static final double VERTICAL_COMPRESSION = 0.6;
    private static final int ORE_OCTAVES = 3;
    private static final int GEM_OCTAVES = 2;

    // The residual floor: the probability a slot is this class even where the layer is quiet.
    // Under 1% ore over nine slots still means roughly one block in eight carries some at baseline,
    // which is what §4 means by never being fully empty-handed.
    //
    // These are deliberately low relative to the seam peaks below, and the balance between the two
    // is the load-bearing part rather than either number alone. Residual ore is an independent
    // per-slot draw, so it is *unclustered by construction*; seam ore inherits the noise layer's
    // spatial structure. A residual that dominates the total makes ore a lottery with no
    // prospecting in it — measured at 2.8x neighbour enrichment before this was rebalanced, which
    // is barely distinguishable from scatter. The suite asserts the ratio, not the rate.
    private static final double RESIDUAL_ORE = 0.008;
    private static final double RESIDUAL_PRECIOUS = 0.001;
    private static final double RESIDUAL_GEM = 0.0003;

    // Seams: where a layer exceeds its threshold, probability climbs toward the peak. The
    // thresholds sit closer to the mean than they look, because fBm of value noise concentrates
    // near 0.5 rather than spanning [0,1] — a threshold of 0.8 is a three-sigma event and produces
    // a seam term that is effectively never reached.
    private static final double ORE_SEAM_THRESHOLD = 0.55;
    private static final double ORE_SEAM_PEAK = 0.55;
    private static final double PRECIOUS_SEAM_THRESHOLD = 0.68;
    private static final double PRECIOUS_SEAM_PEAK = 0.20;
    private static final double GEM_SEAM_THRESHOLD = 0.74;
    private static final double GEM_SEAM_PEAK = 0.08;
    private static final double SEAM_SHARPNESS = 1.5;

    // Depth enrichment multiplier, surface to bedrock.
    private static final double ENRICHMENT_SURFACE = 0.5;
    private static final double ENRICHMENT_BEDROCK = 2.0;

    private CompositionFunction() {
    }

    /**
     * The nine slots of natural stone at a block position.
     *
     * <p>Pure: same arguments, same result, forever and on both sides of the network. Costs a
     * handful of hashes and three fBm samples — against vanilla worldgen's dozens of octaves per
     * block, rounding error (design §12).
     */
    public static Composition stone(int x, int y, int z, long salt) {
        long posHash = positionHash(x, y, z, salt);
        double purity = purity(y);
        double jitter = JITTER_MAX_BLOCKS * (1.0 - purity);
        double enrichment = ENRICHMENT_SURFACE + (ENRICHMENT_BEDROCK - ENRICHMENT_SURFACE) * purity;

        double yc = y * VERTICAL_COMPRESSION;
        double oreP = classProbability(
                Noise.fbm3(x, yc, z, ORE_FREQUENCY, salt, Rng.STREAM_ORE, ORE_OCTAVES),
                RESIDUAL_ORE, ORE_SEAM_THRESHOLD, ORE_SEAM_PEAK, enrichment);
        double preciousP = classProbability(
                Noise.fbm3(x, yc, z, PRECIOUS_FREQUENCY, salt, Rng.STREAM_PRECIOUS_ORE, ORE_OCTAVES),
                RESIDUAL_PRECIOUS, PRECIOUS_SEAM_THRESHOLD, PRECIOUS_SEAM_PEAK, enrichment);
        double gemP = classProbability(
                Noise.fbm3(x, yc, z, GEM_FREQUENCY, salt, Rng.STREAM_GEM, GEM_OCTAVES),
                RESIDUAL_GEM, GEM_SEAM_THRESHOLD, GEM_SEAM_PEAK, enrichment);

        // Rarest first, so that where seams overlap the scarcer class is the one that wins the
        // slot. Clamped as a set: the three together can never claim more than the whole slot.
        double gemEdge = gemP;
        double preciousEdge = gemEdge + preciousP;
        double oreEdge = preciousEdge + oreP;
        if (oreEdge > 1.0) {
            double scale = 1.0 / oreEdge;
            gemEdge *= scale;
            preciousEdge *= scale;
            oreEdge = 1.0;
        }

        // The domain warp is a 512-block-wavelength distortion of the region map, so it belongs to
        // the column rather than to each slot; hoisting it out of the loop turns nine warp
        // evaluations into one and was the single largest cost in the mod. See ColourField.
        double warpX = ColourField.warpX(x, z, salt);
        double warpZ = ColourField.warpZ(x, z, salt);

        int[] slots = new int[Composition.SLOTS];
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            double jx = (Rng.uniform(posHash, slot * 2L, Rng.STREAM_SLOT_JITTER) - 0.5) * 2.0 * jitter;
            double jz = (Rng.uniform(posHash, slot * 2L + 1L, Rng.STREAM_SLOT_JITTER) - 0.5) * 2.0 * jitter;
            double sx = x + jx + warpX;
            double sz = z + jz + warpZ;

            double u = Rng.uniform(posHash, slot, Rng.STREAM_SLOT_CLASS);
            GrainClass grainClass;
            if (u < gemEdge) {
                grainClass = GrainClass.GEM;
            } else if (u < preciousEdge) {
                grainClass = GrainClass.PRECIOUS_ORE;
            } else if (u < oreEdge) {
                grainClass = GrainClass.ORE;
            } else {
                grainClass = GrainClass.ROCK;
            }

            // The stone comes from the region; it also *is* the region's bedrock family, because a
            // stone belongs to exactly one. Which minerals the ground can hold follows from that.
            Grain stone = ColourField.rockAt(sx, sz, salt);

            // The class is decided before the grain, because a mineral is a property of itself and
            // not of its host rock. Resolving one colour up front and applying it to whatever the
            // slot turned out to be is what made grey stone contain only grey ore.
            Grain grain;
            if (grainClass == GrainClass.ROCK) {
                grain = stone;
            } else {
                List<Grain> admitted = Grains.admitted(stone.family(), grainClass);
                // Sedimentary country admits no gems and no precious ore. A slot that rolled one
                // becomes rock rather than nothing: the mediation table decides what can exist,
                // and where it cannot, the ground is simply stone.
                grain = admitted.isEmpty()
                        ? stone
                        : MineralField.pick(grainClass, admitted, sx, sz, salt);
            }

            slots[slot] = grain.id();
        }
        return Composition.of(slots);
    }

    /**
     * The colour the bedrock layer shows at a horizontal position.
     *
     * <p>Design §4: bedrock renders the colour field at full certainty, so the floor of the world
     * tells you the ore family of the whole column above. This is the same {@link ColourField}
     * call the column makes, with the jitter that {@link #purity} would have zeroed out anyway.
     */
    public static Grain bedrockStone(int x, int z, long salt) {
        return ColourField.sample(x, z, salt);
    }

    /**
     * Depth as a 0–1 progression axis: 0 at and above {@link #PURITY_TOP}, 1 at and below bedrock.
     */
    public static double purity(int y) {
        double t = (PURITY_TOP - y) / (PURITY_TOP - PURITY_BOTTOM);
        return Math.max(0.0, Math.min(1.0, t));
    }

    /** Residual floor plus a sharpened seam contribution, scaled by depth. */
    private static double classProbability(double layer, double residual, double threshold,
                                           double peak, double enrichment) {
        double seam = 0.0;
        if (layer > threshold) {
            double excess = (layer - threshold) / (1.0 - threshold);
            seam = peak * Math.pow(excess, SEAM_SHARPNESS);
        }
        return Math.min(1.0, (residual + seam) * enrichment);
    }

    /**
     * The per-block hash, three-dimensional.
     *
     * <p>{@link Rng#positionHash} is 2D by contract, so height enters by way of a per-layer salt —
     * built from the same contracted routine rather than from a new ad-hoc mix, so nothing here
     * needs its own golden vectors to be trustworthy.
     */
    private static long positionHash(int x, int y, int z, long salt) {
        long layerSalt = Rng.positionHash(y, 0L, salt);
        // Rng's (y, x) order is the prototype's row/column indexing: its y is world z. The
        // transposition happens here, once, at the boundary between world space and the contract.
        return Rng.positionHash(z, x, layerSalt);
    }
}
