package com.tarosie.granularity.core;

/**
 * Which way the air is moving — design §11's wind field, derived rather than simulated.
 *
 * <p>Humidity is a scalar per column and something has to carry it. §11 offers "curl noise or
 * seasonal prevailing winds"; this is the first, with the second layered on top as a slow bias.
 *
 * <h2>Why curl noise, and not two noise fields</h2>
 * The obvious construction — sample one noise field for the x component and another for the z — has
 * a defect that shows up as a bug much later: such a field has <b>divergence</b>. Some cells have
 * more flow arriving than leaving, and under advection humidity piles up there permanently. The
 * result is stationary blobs of fog that no weather explains, and the cause is three steps removed
 * from the symptom.
 *
 * <p>The curl of a scalar potential cannot do that. For a potential ψ, the field
 * {@code (∂ψ/∂z, -∂ψ/∂x)} satisfies {@code ∇·(∇×ψ) = 0} identically — divergence-free <i>by
 * construction</i>, not by tuning. Air neither accumulates nor vanishes because the wind said so, and
 * whatever conservation the transport rule has is not quietly undone by the field it runs on. §8
 * already names this trick for animating rivers; it is the same property being used for the same
 * reason.
 *
 * <h2>Time</h2>
 * The potential drifts, so weather is not a fixed pattern bolted to the map. Slowly: a front should
 * take a long while to cross a region, and a wind that changed quickly would smear humidity evenly
 * instead of carrying it somewhere.
 */
public final class Wind {

    /**
     * Wavelength of the wind pattern, in blocks — the size of a weather system.
     *
     * <p>Large. Cells of circulation a few hundred blocks across would read as turbulence rather than
     * weather, and would carry moisture nowhere in particular; a rain shadow needs air that travels
     * consistently in one direction for the width of a mountain range.
     */
    private static final double FREQUENCY = 1.0 / 2048.0;

    private static final int OCTAVES = 2;

    /** How far apart the potential is sampled to take its curl, in blocks. */
    private static final double GRADIENT_STEP = 16.0;

    /**
     * How fast the pattern drifts, in blocks of potential per tick.
     *
     * <p>Tuned so a weather system takes tens of minutes to pass rather than seconds. Wind that
     * changes faster than humidity can travel mixes the field instead of moving it, and every
     * emergent thing §11 wants — orographic rain, rain shadows — depends on air going one way for
     * long enough to cross a mountain.
     */
    private static final double DRIFT = 0.02;

    /**
     * A steady bias added to the curl, in the same units — §11's "seasonal prevailing winds".
     *
     * <p>Without it the curl field is all eddies and a given place has no prevailing direction over
     * time, so no coast is reliably the wet one. Real rain shadows exist because the wind mostly
     * comes from the same quarter for years. This is small enough that local circulation still shows
     * through.
     */
    private static final double PREVAILING_X = 0.35;
    private static final double PREVAILING_Z = 0.15;

    private Wind() {
    }

    /** East–west component of the wind at a column, positive toward +x. */
    public static double x(int blockX, int blockZ, long tick, long salt) {
        double here = potential(blockX, blockZ + GRADIENT_STEP, tick, salt);
        double there = potential(blockX, blockZ - GRADIENT_STEP, tick, salt);
        return (here - there) / 2.0 + PREVAILING_X;
    }

    /** North–south component of the wind at a column, positive toward +z. */
    public static double z(int blockX, int blockZ, long tick, long salt) {
        double here = potential(blockX + GRADIENT_STEP, blockZ, tick, salt);
        double there = potential(blockX - GRADIENT_STEP, blockZ, tick, salt);
        return -(here - there) / 2.0 + PREVAILING_Z;
    }

    /**
     * The scalar potential the wind is the curl of.
     *
     * <p>Scaled up so its gradient lands in a useful range: the difference of two samples a few
     * blocks apart is otherwise a very small number, and the wind would never move anything.
     */
    private static double potential(double x, double z, long tick, long salt) {
        double drift = tick * DRIFT;
        return Noise.fbm2(x + drift, z - drift * 0.5, FREQUENCY, salt, Rng.STREAM_WIND, OCTAVES)
                * 8.0;
    }
}
