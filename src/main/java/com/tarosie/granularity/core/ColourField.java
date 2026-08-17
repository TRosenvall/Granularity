package com.tarosie.granularity.core;

import java.util.List;

/**
 * The 2D field over (x, z) that assigns every column its bedrock family and its stone.
 *
 * <p>This is the world's material map, and it exists as its own class because design §4 makes one
 * structural demand about it:
 *
 * <blockquote>bedrock and stone are two consumers of <b>one shared field</b> — worldgen must never
 * read bedrock <i>blocks</i> to decide stone.</blockquote>
 *
 * <p>Reading blocks during generation introduces ordering hazards that are painful to unpick later.
 * Both consumers call into this class: bedrock renders it at full certainty at the bottom of the
 * world, and the column above samples the same function with per-slot jitter. Nothing here knows
 * what a block is, which is the point — it cannot be misused that way.
 *
 * <p>Domain-warped cellular noise rather than banded value noise, per §4: sixteen discrete colours
 * want regions with organic borders. Thresholding a smooth field into sixteen bands would produce
 * stripes, and stripes would make the bedrock map useless as a prospecting instrument.
 *
 * <h2>Warp and cell are separate calls, deliberately</h2>
 * A block samples this field nine times, once per slot, and the warp is by far the expensive half —
 * two three-octave fBm evaluations against the cell lookup's single Worley search. Evaluating it
 * nine times was the dominant cost in the whole mod: 5.9 µs of a block's 8.7 µs.
 *
 * <p>Splitting it is not only cheaper but more faithful. The warp is a <i>large-scale</i> distortion
 * of the region map — 512-block wavelength — while per-slot jitter is a ±24-block probe whose whole
 * job is to straddle a cell boundary. The warp belongs to the column; the jitter belongs to the
 * slot. Sampling the warp once per block and the cell once per slot says exactly that, and over a
 * ±24-block jitter a 512-block field barely moves anyway.
 */
public final class ColourField {


    /**
     * Rough width of a colour region, in blocks.
     *
     * <p>Sized so that crossing regions is travel rather than a stroll — design §4 wants the
     * bedrock map to make deep travel into reconnaissance, which only pays if a region is big
     * enough that knowing yours tells you something durable.
     */
    public static final double REGION_SIZE = 384.0;

    /** Warp wavelength, in blocks. Longer than a region, so borders wander rather than crinkle. */
    private static final double WARP_FREQUENCY = 1.0 / 512.0;

    /** Warp displacement at full swing, in blocks — about a third of a region. */
    private static final double WARP_AMPLITUDE = 0.35 * REGION_SIZE;

    private static final int WARP_OCTAVES = 3;

    private ColourField() {
    }

    /**
     * The domain warp along x at a column, in blocks. Shared by all nine of a block's slots.
     */
    public static double warpX(double x, double z, long salt) {
        return warp(x, z, salt, Rng.STREAM_COLOUR_WARP_X);
    }

    /** The domain warp along z at a column, in blocks. */
    public static double warpZ(double x, double z, long salt) {
        return warp(x, z, salt, Rng.STREAM_COLOUR_WARP_Z);
    }

    private static double warp(double x, double z, long salt, int stream) {
        return (Noise.fbm2(x, z, WARP_FREQUENCY, salt, stream, WARP_OCTAVES) - 0.5) * 2.0 * WARP_AMPLITUDE;
    }

    /**
     * The lattice colour at an already-warped position: 0–15.
     *
     * <p>Two draws from the winning cell, not one. The region picks a {@link BedrockType} by areal
     * weight first, then a colour from that family's palette — so colours are not uniform over the
     * lattice but follow the rock families, and a colour identifies its family unambiguously.
     * Drawing the colour directly would give every colour equal area and destroy that.
     *
     * @param warpedX the sample position with the column's warp already added
     */
    public static Grain rockAt(double warpedX, double warpedZ, long salt) {
        // No intermediate object: this runs nine times per block during meshing.
        long winner = Noise.cell2(warpedX / REGION_SIZE, warpedZ / REGION_SIZE, salt, Rng.STREAM_COLOUR_CELL);
        BedrockType family = BedrockType.pick(Rng.uniform(winner, 0L, Rng.STREAM_BEDROCK_TYPE));
        return Grains.pick(Grains.admitted(family, GrainClass.ROCK), winner);
    }

    /**
     * The {@link BedrockType} of the region at an already-warped position.
     *
     * <p>Derived from the stone rather than drawn separately: a stone belongs to exactly one family,
     * so the rock and the family are the same fact. That is the property that lets a player read the
     * family off the rock, and through it which minerals the ground can hold.
     */
    public static BedrockType familyAt(double warpedX, double warpedZ, long salt) {
        return rockAt(warpedX, warpedZ, salt).family();
    }

    /**
     * The lattice colour at a horizontal position, warp included.
     *
     * <p>The single-shot form, for callers with no jitter to apply — bedrock, and tests. A column
     * deriving nine slots should hoist {@link #warpX}/{@link #warpZ} out of its loop instead.
     */
    public static Grain sample(double x, double z, long salt) {
        return rockAt(x + warpX(x, z, salt), z + warpZ(x, z, salt), salt);
    }
}
