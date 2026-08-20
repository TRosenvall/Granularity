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
 * <p>Two more layers sit on top of those four, and they run in this order for a reason: porosity
 * decides which slots are pore rather than rock, and only then does the water table decide which of
 * those pores are wet. Water can therefore only exist where the rock had room for it, so an
 * impermeable bed is dry because it has nowhere to put water rather than because a rule forbids it.
 *
 * <h2>Not yet here</h2>
 * <ul>
 *   <li><b>Rain.</b> The water table here is the <i>equilibrium baseline</i> half of §6's split
 *       rule — where groundwater stands at rest. The moving half is built ({@link WaterMigration},
 *       with its deviations stored per chunk), but nothing recharges it: without rain a spring has
 *       no supply, so water moves when disturbed and then settles back. §11's humidity field is what
 *       is missing.</li>
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

    // Porosity. Design §6 asks for "an invisible air drop object / a dedicated porosity noise
    // layer"; findings §6.1 collapsed the two — in a nine-slot voxel the free slots *are* the
    // porosity, so this places AIR grains and nothing else has to store anything.
    //
    // A layer, not a per-slot lottery, because the whole point is strata: §6 wants water perching on
    // an impermeable bed and seeping out where that bed outcrops on a hillside, and that only happens
    // if porous and tight rock come in *sheets*. A lower frequency than ore, and compressed harder in
    // y, so a bed stays a bed for a long horizontal way and changes quickly with depth.
    private static final double POROSITY_FREQUENCY = 0.006;
    private static final int POROSITY_OCTAVES = 2;
    private static final double POROSITY_VERTICAL_SQUEEZE = 4.0;
    //
    // A band, not a threshold-and-ramp, and this is the load-bearing choice. The ore curve climbs
    // gently from its threshold, which for ore is right — seams should have edges you can follow. A
    // porosity that climbs gently produces a *gradient*: measured at 0.30/0.85 the world had almost
    // no rock at exactly zero porosity, so water would seep everywhere at a trickle and perch
    // nowhere, and §6.3's spring cannot happen without something to perch on.
    //
    // So porosity saturates instead. Below LOW the rock is tight, above HIGH it is as open as the
    // rock allows, and the band between is narrow enough to read as a boundary rather than a slope.
    // Aquicludes and aquifers are then both common and both thick.
    private static final double POROSITY_LOW = 0.46;
    private static final double POROSITY_HIGH = 0.60;
    private static final double POROSITY_PEAK = 0.58;

    /*
     * A natural block holds between one and nine grains, never zero. Two attempts at this are
     * recorded because both were wrong in instructive ways and the third is easy to mistake for
     * either.
     *
     * The first clamped porosity to four so that no block could be entirely pore. That gave up the
     * field's whole upper range to dodge one case at the far end of it, and the aquifers and
     * aquicludes design §6.3 needs live in exactly that range.
     *
     * The second let the field say nine and had a worldgen feature carve those blocks out. It was
     * built, measured at some twenty-five blocks a chunk, and removed: it made a cavity something the
     * terrain had to contain — a scan of every block in every chunk, a feature, a placement, and a
     * policy for what to leave behind — to express something the composition can simply not say.
     *
     * What is here now rescues one slot in the block that would otherwise be all pore; see
     * rescueStone. It touches one block in roughly two and a half thousand and leaves porosity
     * everywhere else exactly as the field draws it.
     */

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

        // How porous this position wants to be, before the rock gets a say. Enrichment is left out:
        // rock does not become more porous with depth, it becomes less, and that is already carried
        // by which rock is there.
        double porosityP = porosity(Noise.fbm3(
                x, y * VERTICAL_COMPRESSION * POROSITY_VERTICAL_SQUEEZE, z,
                POROSITY_FREQUENCY, salt, Rng.STREAM_POROSITY, POROSITY_OCTAVES));

        // And how full those pores stand at rest. One lookup for the column, like the warp above it:
        // the water table is a surface, so every slot in this block is under the same head of water.
        double saturation = WaterTable.saturation(x, y, z, salt);

        int[] slots = new int[Composition.SLOTS];
        int pores = 0;
        double widestAir = -1.0;
        int widestSlot = 0;
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            double jx = (Rng.uniform(posHash, slot * 2L, Rng.STREAM_SLOT_JITTER) - 0.5) * 2.0 * jitter;
            double jz = (Rng.uniform(posHash, slot * 2L + 1L, Rng.STREAM_SLOT_JITTER) - 0.5) * 2.0 * jitter;
            double sx = x + jx + warpX;
            double sz = z + jz + warpZ;

            // The stone is resolved first now, because whether this slot can be a void at all is a
            // property of the rock: sandstone is porous, granite is not (§6).
            Grain stone = ColourField.rockAt(sx, sz, salt);

            // Air is drawn before the minerals and short-circuits them, on a stream of its own. Both
            // parts matter. Drawing first means porous rock holds proportionally less of everything
            // else, which is what "the free slots are the porosity" says; drawing on its own stream
            // means every other draw is untouched, so adding porosity did not reshuffle the ore in
            // the entire world.
            double air = Rng.uniform(posHash, slot, Rng.STREAM_SLOT_AIR);
            if (air < porosityP * openness(stone)) {
                // A pore, and now the second question: is anything in it. Below the water table it
                // holds water, above it air, and the same slot is one grain or the other. Drawn
                // after the pore rather than instead of it, so water can only ever be somewhere the
                // rock had room for it — an impermeable bed stays dry by having nowhere to put it,
                // not by a rule that says so.
                double wet = Rng.uniform(posHash, slot, Rng.STREAM_SLOT_WATER);
                slots[slot] = wet < saturation ? Grains.WATER.id() : Grains.AIR.id();
                pores++;
                // Remember which pore came closest to being solid, in case this block turns out to
                // be nothing but pores. See the rescue below.
                if (air > widestAir) {
                    widestAir = air;
                    widestSlot = slot;
                }
                continue;
            }

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

        if (pores == Composition.SLOTS) {
            slots[widestSlot] = rescueStone(widestSlot, posHash, jitter, warpX, warpZ, x, z, salt);
        }
        return Composition.of(slots);
    }

    /**
     * The host rock, forced into the one slot that came closest to being solid.
     *
     * <p>A natural block holds between one and nine grains. Never zero — that is the rule the rest of
     * the mod is entitled to rely on, and several places already do: a block with no grains drops
     * nothing at all, and a block of nine drops would be a source, which is water the world created
     * rather than water it moved. Design §7's conservation and design §4's "mining is never fully
     * empty-handed" are the same statement seen from two sides, and both need this floor.
     *
     * <p>The alternative was to let the field say nine pores and have worldgen carve those blocks out
     * of the world. That was built and then removed. It made a cavity a thing the terrain had to
     * contain — a scan of every block in every chunk, a feature, a placement, and a policy for what
     * to leave in the hole — to express something the composition can simply not say. Measured, it
     * was about twenty-five blocks in a chunk.
     *
     * <p>This is not the clamp that was rejected earlier. That one capped porosity at four to dodge
     * the empty-drop case, which cost the field its whole upper range and did make it lie about
     * itself; the aquifers and aquicludes §6.3 needs live in exactly that range. This touches one
     * block in some two and a half thousand, changes nothing about how porous rock is anywhere else,
     * and states a boundary the design already had: rock with no rock in it is not rock.
     *
     * <p>The rescued slot is the pore whose air draw came closest to failing — the slot that most
     * nearly stayed solid — so which one it is follows from the same numbers everything else does
     * rather than from a fixed index. It becomes plain host rock rather than an ore: a mineral is a
     * property of itself, and conjuring one into a rescue slot would put ore where the ore fields
     * never said any was.
     */
    private static int rescueStone(int slot, long posHash, double jitter, double warpX, double warpZ,
                                   int x, int z, long salt) {
        double jx = (Rng.uniform(posHash, slot * 2L, Rng.STREAM_SLOT_JITTER) - 0.5) * 2.0 * jitter;
        double jz = (Rng.uniform(posHash, slot * 2L + 1L, Rng.STREAM_SLOT_JITTER) - 0.5) * 2.0 * jitter;
        return ColourField.rockAt(x + jx + warpX, z + jz + warpZ, salt).id();
    }

    /**
     * The porosity layer read as a bed: tight, then a short boundary, then open.
     *
     * <p>Deliberately not {@link #classProbability}. That curve is built for seams, which should have
     * soft edges you can prospect toward; a bed wants a hard edge, because what makes it a bed is that
     * the rock above it and the rock below it are different things.
     */
    private static double porosity(double layer) {
        if (layer <= POROSITY_LOW) {
            return 0.0;
        }
        double across = Math.min(1.0, (layer - POROSITY_LOW) / (POROSITY_HIGH - POROSITY_LOW));
        return across * POROSITY_PEAK;
    }

    /**
     * How much of a rock's own nature is open space, from 0 (tight) to 1 (as porous as the layer
     * allows).
     *
     * <p>Read off the bedrock family rather than kept as a per-rock table, and that is deliberate:
     * the roster is open, so a grain from another mod has to get a defensible answer without us
     * having heard of it. A family is the one thing every registered rock already declares, and it
     * happens to be the right axis — sedimentary rock is laid down in grains with space between them,
     * igneous rock cools solid. §6's own example is sandstone against granite, which is exactly this
     * distinction.
     *
     * <p>A rock in no family at all is treated as tight. That is the safe direction to be wrong in:
     * an unexpectedly impermeable block is a block that behaves like ordinary stone.
     */
    private static double openness(Grain stone) {
        double most = 0.0;
        for (BedrockType family : stone.families()) {
            most = Math.max(most, switch (family) {
                case SEDIMENTARY -> 1.0;
                case METAMORPHIC -> 0.3;
                case IGNEOUS -> 0.05;
            });
        }
        return most;
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
     * <p>Kept as a named method here because every call in this file reads better for it, but the
     * implementation moved to {@link Rng#positionHash(long, long, long, long)} when the migration
     * rule needed the same hash. One copy, pinned by the composition golden.
     */
    private static long positionHash(int x, int y, int z, long salt) {
        return Rng.positionHash(x, y, z, salt);
    }
}
