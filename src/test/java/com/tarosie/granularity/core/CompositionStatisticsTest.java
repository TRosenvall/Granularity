package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Measures what the composition function actually produces, rather than asserting that it runs.
 *
 * <p>The bounds here are deliberately wide — they are guards against a constant being off by an
 * order of magnitude, not a pin on the world. Exact outputs are pinned by
 * {@link CompositionGoldenTest}. Each test prints its numbers so a change in feel is visible in the
 * build log rather than only in-game.
 */
class CompositionStatisticsTest {

    private static final long SALT = WorldSalt.derive(24301L).value();

    @Test
    @DisplayName("ore fractions across depth, and the residual floor")
    void oreFractionsAreReasonableAndIncreaseWithDepth() {
        int[] depths = {60, 32, 0, -32, -60};
        double[] oreFraction = new double[depths.length];

        System.out.println("  depth   rock     ore  precious    gem   (fraction of slots)");
        for (int d = 0; d < depths.length; d++) {
            int y = depths[d];
            long[] counts = new long[GrainClass.values().length];
            long total = 0;
            for (int x = 0; x < 96; x++) {
                for (int z = 0; z < 96; z++) {
                    Composition c = CompositionFunction.stone(x * 7, y, z * 7, SALT);
                    for (int slot = 0; slot < Composition.SLOTS; slot++) {
                        counts[c.classAt(slot).ordinal()]++;
                        total++;
                    }
                }
            }
            oreFraction[d] = counts[GrainClass.ORE.ordinal()] / (double) total;
            System.out.printf(Locale.ROOT, "  %5d  %.4f  %.4f   %.5f  %.5f%n", y,
                    counts[GrainClass.ROCK.ordinal()] / (double) total,
                    oreFraction[d],
                    counts[GrainClass.PRECIOUS_ORE.ordinal()] / (double) total,
                    counts[GrainClass.GEM.ordinal()] / (double) total);

            assertTrue(counts[GrainClass.ROCK.ordinal()] / (double) total > 0.7,
                    "stone should still be mostly rock at y=" + y);
            assertTrue(oreFraction[d] > 0.001, "residual ore floor should never vanish at y=" + y);
        }

        assertTrue(oreFraction[oreFraction.length - 1] > oreFraction[0] * 1.5,
                "depth should enrich: bedrock " + oreFraction[oreFraction.length - 1]
                        + " vs surface " + oreFraction[0]);
    }

    @Test
    @DisplayName("purity: colours mix near the surface and are pure at bedrock")
    void purityGradientMixesAtTheSurfaceAndNotAtBedrock() {
        int[] depths = {64, 32, 0, -32, -64};
        System.out.println("  depth   blocks with mixed colours");
        for (int y : depths) {
            int mixed = 0;
            int total = 0;
            for (int x = 0; x < 200; x++) {
                for (int z = 0; z < 200; z++) {
                    // Sample a band that crosses region borders rather than one region's interior.
                    Composition c = CompositionFunction.stone(x * 5, y, z * 5, SALT);
                    if (c.distinctGrains(GrainClass.ROCK) > 1) {
                        mixed++;
                    }
                    total++;
                }
            }
            System.out.printf(Locale.ROOT, "  %5d   %.4f%n", y, mixed / (double) total);

            if (y == -64) {
                assertTrue(mixed == 0, "bedrock depth must be a single rock colour: jitter is zero there");
            }
            if (y == 64) {
                assertTrue(mixed > 0, "the surface must dither region borders");
            }
        }
    }

    @Test
    @DisplayName("stone field: many stones appear, and regions are coherent")
    void stoneFieldCoversTheRosterInCoherentRegions() {
        boolean[] seen = new boolean[Grains.count()];
        int distinctSeen = 0;
        int agreements = 0;
        int comparisons = 0;

        for (int x = 0; x < 300; x++) {
            for (int z = 0; z < 300; z++) {
                int wx = x * 16 - 2400;
                int wz = z * 16 - 2400;
                Grain stone = ColourField.sample(wx, wz, SALT);
                if (!seen[stone.id()]) {
                    seen[stone.id()] = true;
                    distinctSeen++;
                }
                // Neighbouring samples 16 blocks apart should usually agree: regions are ~384
                // blocks across, so disagreement means the field is noise, not regions.
                if (ColourField.sample(wx + 16, wz, SALT) == stone) {
                    agreements++;
                }
                comparisons++;
            }
        }

        double coherence = agreements / (double) comparisons;
        System.out.printf(Locale.ROOT, "  stones seen: %d, neighbour agreement at 16 blocks: %.4f%n",
                distinctSeen, coherence);

        assertTrue(distinctSeen >= 8,
                "a 4800-block span should cross many stone regions, saw " + distinctSeen);
        assertTrue(coherence > 0.9,
                "regions must be coherent, not per-sample noise; agreement was " + coherence);
    }

    @Test
    @DisplayName("seams exist: ore is clustered, not uniformly sprinkled")
    void oreIsClusteredIntoSeams() {
        // If ore were an independent per-slot coin flip, the distribution of ore-slots-per-block
        // would be binomial. Seams show up as overdispersion: blocks with many ore slots occur far
        // more often than independence allows.
        int y = -32;
        int[] histogram = new int[Composition.SLOTS + 1];
        int blocks = 0;
        for (int x = 0; x < 160; x++) {
            for (int z = 0; z < 160; z++) {
                Composition c = CompositionFunction.stone(x * 3, y, z * 3, SALT);
                histogram[c.count(GrainClass.ORE)]++;
                blocks++;
            }
        }

        System.out.print("  ore slots per block:");
        for (int i = 0; i <= Composition.SLOTS; i++) {
            System.out.printf(Locale.ROOT, " %d:%.4f", i, histogram[i] / (double) blocks);
        }
        System.out.println();

        int rich = 0;
        for (int i = 4; i <= Composition.SLOTS; i++) {
            rich += histogram[i];
        }
        assertTrue(rich > 0, "there should be blocks with 4+ ore slots — that is what a seam is");
        assertTrue(histogram[0] > blocks / 2, "most blocks should still be plain rock");

        // Rate is only half the question. A seam has to be *followable*: finding an ore-rich block
        // should tell you something about the block next to it, or the ore is just a lottery with
        // no prospecting in it. Measured against the unconditional rate as the null hypothesis.
        int enriched = 0;
        int enrichedWithEnrichedNeighbour = 0;
        int sampled = 0;
        for (int x = 0; x < 160; x++) {
            for (int z = 0; z < 160; z++) {
                boolean here = CompositionFunction.stone(x * 3, y, z * 3, SALT)
                        .count(GrainClass.ORE) >= 2;
                boolean next = CompositionFunction.stone(x * 3 + 3, y, z * 3, SALT)
                        .count(GrainClass.ORE) >= 2;
                if (here) {
                    enriched++;
                    if (next) {
                        enrichedWithEnrichedNeighbour++;
                    }
                }
                sampled++;
            }
        }
        double unconditional = enriched / (double) sampled;
        double conditional = enrichedWithEnrichedNeighbour / (double) enriched;
        System.out.printf(Locale.ROOT,
                "  P(neighbour enriched) = %.4f unconditional, %.4f given this block is: %.1fx%n",
                unconditional, conditional, conditional / unconditional);

        assertTrue(conditional > unconditional * 3.0,
                "ore must cluster into seams a player can follow, not scatter independently");
    }

    @Test
    @DisplayName("a different salt is a different world")
    void saltChangesTheWorld() {
        long other = WorldSalt.derive(24302L).value();
        int differing = 0;
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                if (!CompositionFunction.stone(x, 0, z, SALT)
                        .equals(CompositionFunction.stone(x, 0, z, other))) {
                    differing++;
                }
            }
        }
        System.out.printf(Locale.ROOT, "  blocks differing between salts: %d/4096%n", differing);
        assertTrue(differing > 4000, "changing the salt should change essentially every block");
    }
}
