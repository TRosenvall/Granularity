package com.tarosie.granularity.core;

/**
 * Noise layers for the derived composition, built on {@link Rng} rather than on Minecraft's
 * generators.
 *
 * <p>That choice is not incidental. Design §4 syncs a derived salt so client and server hash the
 * same positions; if the noise came from {@code NormalNoise} seeded from a {@code RandomSource},
 * agreement would depend on both sides constructing the generator identically, and the failure mode
 * is silent cosmetic desync. Deriving every octave from the same mixer that Phase 0 pinned against
 * golden vectors makes agreement structural.
 *
 * <p>The prototype has no counterpart to this file — its terrain came from {@code state.py}, which
 * the porting spec lists as test fixtures, free to rewrite. So these routines are <i>new</i>
 * contract surface rather than a port, and they are pinned by
 * {@code CompositionGoldenTest} instead of by {@code golden_vectors.json}.
 *
 * <h2>Axis conventions</h2>
 * Arguments are world-natural {@code (x, y, z)} with y as height. The transposition into
 * {@link Rng#positionHash}'s {@code (y, x)} order happens here, once, at the boundary.
 *
 * <p>{@link Rng#uniform}'s {@code tick} axis carries a spatial or structural index in this file —
 * the lattice y for 3D noise, the feature-point component for cellular noise. It is simply another
 * mixing input; nothing here advances in time.
 */
public final class Noise {

    private Noise() {
    }

    /** Standard quintic fade — first and second derivatives vanish at both ends. */
    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Decorrelates octaves without spending stream ids on them.
     *
     * <p>Octave index folds into the salt rather than into the stream because stream ids are a
     * registry shared with the transport tiers (PORTING_SPEC §2.3) and an fBm with eight octaves
     * would otherwise consume eight of them per layer.
     */
    private static long octaveSalt(long salt, int octave) {
        return salt ^ (0x9E3779B97F4A7C15L * (octave + 1L));
    }

    private static double lattice2(long ix, long iz, long salt, int stream) {
        return Rng.uniform(Rng.positionHash(iz, ix, salt), 0L, stream);
    }

    private static double lattice3(long ix, long iy, long iz, long salt, int stream) {
        return Rng.uniform(Rng.positionHash(iz, ix, salt), iy, stream);
    }

    /** Bilinear value noise in [0, 1). */
    public static double value2(double x, double z, long salt, int stream) {
        long ix = (long) Math.floor(x);
        long iz = (long) Math.floor(z);
        double tx = fade(x - ix);
        double tz = fade(z - iz);

        double v00 = lattice2(ix, iz, salt, stream);
        double v10 = lattice2(ix + 1, iz, salt, stream);
        double v01 = lattice2(ix, iz + 1, salt, stream);
        double v11 = lattice2(ix + 1, iz + 1, salt, stream);

        return lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), tz);
    }

    /** Trilinear value noise in [0, 1). */
    public static double value3(double x, double y, double z, long salt, int stream) {
        long ix = (long) Math.floor(x);
        long iy = (long) Math.floor(y);
        long iz = (long) Math.floor(z);
        double tx = fade(x - ix);
        double ty = fade(y - iy);
        double tz = fade(z - iz);

        double v000 = lattice3(ix, iy, iz, salt, stream);
        double v100 = lattice3(ix + 1, iy, iz, salt, stream);
        double v010 = lattice3(ix, iy + 1, iz, salt, stream);
        double v110 = lattice3(ix + 1, iy + 1, iz, salt, stream);
        double v001 = lattice3(ix, iy, iz + 1, salt, stream);
        double v101 = lattice3(ix + 1, iy, iz + 1, salt, stream);
        double v011 = lattice3(ix, iy + 1, iz + 1, salt, stream);
        double v111 = lattice3(ix + 1, iy + 1, iz + 1, salt, stream);

        double x00 = lerp(v000, v100, tx);
        double x10 = lerp(v010, v110, tx);
        double x01 = lerp(v001, v101, tx);
        double x11 = lerp(v011, v111, tx);

        return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
    }

    /**
     * Fractal Brownian motion over {@link #value2}, normalised to [0, 1).
     *
     * @param frequency cycles per unit at the first octave — i.e. 1/wavelength in blocks
     */
    public static double fbm2(double x, double z, double frequency, long salt, int stream, int octaves) {
        double sum = 0.0;
        double amplitude = 1.0;
        double total = 0.0;
        double f = frequency;
        for (int octave = 0; octave < octaves; octave++) {
            sum += amplitude * value2(x * f, z * f, octaveSalt(salt, octave), stream);
            total += amplitude;
            amplitude *= 0.5;
            f *= 2.0;
        }
        return sum / total;
    }

    /** Fractal Brownian motion over {@link #value3}, normalised to [0, 1). */
    public static double fbm3(double x, double y, double z, double frequency, long salt, int stream, int octaves) {
        double sum = 0.0;
        double amplitude = 1.0;
        double total = 0.0;
        double f = frequency;
        for (int octave = 0; octave < octaves; octave++) {
            sum += amplitude * value3(x * f, y * f, z * f, octaveSalt(salt, octave), stream);
            total += amplitude;
            amplitude *= 0.5;
            f *= 2.0;
        }
        return sum / total;
    }

    /**
     * Cellular (Worley) noise: returns the hash identifying the nearest feature point's cell.
     *
     * <p>Design §4 asks for domain-warped cellular noise because sixteen discrete colours want
     * <i>regions</i> with organic borders, not smears — a value-noise field thresholded into
     * sixteen bands would give stripes. The caller supplies the warp; this returns cell identity.
     *
     * <p>Feature points are jittered within their own cell, so the 3×3 neighbourhood searched here
     * is sufficient — no feature point outside it can ever be nearest.
     *
     * @return the winning cell's position hash, for the caller to reduce to whatever it needs
     */
    public static long cell2(double x, double z, long salt, int jitterStream) {
        long ix = (long) Math.floor(x);
        long iz = (long) Math.floor(z);

        long winner = 0L;
        double best = Double.MAX_VALUE;

        for (long dz = -1; dz <= 1; dz++) {
            for (long dx = -1; dx <= 1; dx++) {
                long cx = ix + dx;
                long cz = iz + dz;
                long h = Rng.positionHash(cz, cx, salt);

                double fx = cx + Rng.uniform(h, 0L, jitterStream);
                double fz = cz + Rng.uniform(h, 1L, jitterStream);

                double ex = fx - x;
                double ez = fz - z;
                double d2 = ex * ex + ez * ez;

                if (d2 < best) {
                    best = d2;
                    winner = h;
                }
            }
        }
        return winner;
    }
}
