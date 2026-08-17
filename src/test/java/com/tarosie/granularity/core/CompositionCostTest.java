package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What deriving a composition actually costs.
 *
 * <p>Design §12 budgets composition derivation as "a hash plus a few noise samples", and calls it
 * rounding error against vanilla worldgen. That estimate assumes one colour-field sample per block.
 * The implementation takes <b>nine</b> — one per slot, because §4's border dithering works by giving
 * each slot its own positional jitter — and each of those samples is a domain-warped cellular
 * lookup: two three-octave fBm evaluations plus a nine-cell Worley search.
 *
 * <p>So the real cost is roughly an order of magnitude above the budgeted one, and the number
 * matters because it is paid per block per chunk-section rebuild. This measures it rather than
 * arguing about it. The assertion is a loose ceiling meant to catch a regression of the kind that
 * would make chunk loading stutter, not to pin a performance target.
 */
class CompositionCostTest {

    private static final long SALT = WorldSalt.derive(24301L).value();

    /** Blocks in a chunk section — the unit of work a rebuild actually does. */
    private static final int SECTION_BLOCKS = 16 * 16 * 16;

    @Test
    @DisplayName("cost per block, and what that implies for a chunk section rebuild")
    void derivationCostIsAffordable() {
        // Warm up the JIT; the first thousands of calls are interpreted and would dominate.
        long sink = 0;
        for (int i = 0; i < 60_000; i++) {
            sink += CompositionFunction.stone(i, i % 128 - 64, -i, SALT).grainId(0);
        }

        int samples = 200_000;
        long start = System.nanoTime();
        for (int i = 0; i < samples; i++) {
            sink += CompositionFunction.stone(i * 3, (i % 130) - 64, i * -7, SALT).grainId(0);
        }
        long elapsed = System.nanoTime() - start;
        assertTrue(sink != Long.MIN_VALUE, "keep the loop from being optimised away");

        double nsPerBlock = elapsed / (double) samples;
        double msPerSection = nsPerBlock * SECTION_BLOCKS / 1_000_000.0;

        System.out.printf(Locale.ROOT, "  composition: %.0f ns/block%n", nsPerBlock);
        System.out.printf(Locale.ROOT, "  a full section (%d blocks): %.1f ms%n", SECTION_BLOCKS, msPerSection);
        System.out.printf(Locale.ROOT, "  a 24-section column, worst case: %.0f ms%n", msPerSection * 24);

        // Only the colour field is measured separately below; this bound is what keeps a section
        // rebuild inside a frame or two rather than a visible hitch.
        assertTrue(msPerSection < 60.0,
                "a section rebuild would stutter at " + String.format(Locale.ROOT, "%.1f ms", msPerSection));
    }

    @Test
    @DisplayName("where the time goes: the colour field dominates")
    void colourFieldDominates() {
        long sink = 0;
        for (int i = 0; i < 60_000; i++) {
            sink += ColourField.sample(i, -i, SALT).id();
        }

        int samples = 200_000;
        long start = System.nanoTime();
        for (int i = 0; i < samples; i++) {
            sink += ColourField.sample(i * 3.0, i * -7.0, SALT).id();
        }
        double nsPerSample = (System.nanoTime() - start) / (double) samples;
        assertTrue(sink != Long.MIN_VALUE);

        start = System.nanoTime();
        for (int i = 0; i < samples; i++) {
            sink += (long) (Noise.fbm3(i * 3, i % 130 - 64, i * -7, 1.0 / 40.0, SALT, Rng.STREAM_ORE, 3) * 1000);
        }
        double nsPerFbm3 = (System.nanoTime() - start) / (double) samples;
        assertTrue(sink != Long.MIN_VALUE);

        System.out.printf(Locale.ROOT, "  ColourField.sample: %.0f ns  (x9 per block = %.0f ns)%n",
                nsPerSample, nsPerSample * 9);
        System.out.printf(Locale.ROOT, "  fbm3 (3 octaves):   %.0f ns  (x3 per block = %.0f ns)%n",
                nsPerFbm3, nsPerFbm3 * 3);
    }

    @Test
    @DisplayName("at bedrock every slot samples the same point — a free early-out exists")
    void purityCollapsesTheNineSamples() {
        // Jitter scales to zero at the bedrock datum, so all nine slots sample the identical
        // position and eight of the nine colour-field lookups are redundant. Recording this
        // because it is the cheapest available optimisation if the cost above ever bites.
        Composition deep = CompositionFunction.stone(1234, -64, -5678, SALT);
        assertTrue(deep.distinctGrains(GrainClass.ROCK) == 1,
                "bedrock depth is a single rock colour by construction");

        double purity = CompositionFunction.purity(-64);
        assertTrue(purity == 1.0, "purity must reach exactly 1 for the collapse to be exact");
    }
}
