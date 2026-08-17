package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Invariants of the derived composition. Statistics live in {@link CompositionStatisticsTest}. */
class CompositionFunctionTest {

    private static final long SALT = WorldSalt.derive(0xC0FFEEL).value();

    @Test
    @DisplayName("purity is a clamped 0-1 depth axis")
    void purityIsClampedAndMonotone() {
        assertEquals(0.0, CompositionFunction.purity(64));
        assertEquals(0.0, CompositionFunction.purity(320), "above the datum stays at zero");
        assertEquals(1.0, CompositionFunction.purity(-64));
        assertEquals(1.0, CompositionFunction.purity(-1000), "below bedrock stays at one");
        assertEquals(0.5, CompositionFunction.purity(0));

        double previous = -1.0;
        for (int y = 128; y >= -128; y--) {
            double purity = CompositionFunction.purity(y);
            assertTrue(purity >= previous, "purity must not decrease with depth at y=" + y);
            previous = purity;
        }
    }

    @Test
    @DisplayName("stone is always nine slots of mineral classes")
    void stoneIsNineMineralSlots() {
        for (int i = 0; i < 400; i++) {
            int x = i * 37 - 5000;
            int y = (i % 200) - 64;
            int z = i * -53 + 900;
            Composition c = CompositionFunction.stone(x, y, z, SALT);

            assertEquals(0, c.porosity(), "Phase 1 stone has no free slots; porosity is Phase 7");
            for (int slot = 0; slot < Composition.SLOTS; slot++) {
                GrainClass materialClass = c.classAt(slot);
                assertTrue(materialClass == GrainClass.ROCK
                                || materialClass == GrainClass.ORE
                                || materialClass == GrainClass.PRECIOUS_ORE
                                || materialClass == GrainClass.GEM,
                        "unexpected class in stone: " + materialClass);
                assertTrue(c.grainAt(slot).clazz().isMineral(), "stone slots must hold minerals");
            }
        }
    }

    @Test
    @DisplayName("the same position gives the same composition, always")
    void derivationIsDeterministic() {
        for (int i = 0; i < 100; i++) {
            int x = i * 613 - 20_000;
            int y = 40 - i;
            int z = i * -911 + 7000;
            Composition first = CompositionFunction.stone(x, y, z, SALT);
            for (int repeat = 0; repeat < 3; repeat++) {
                assertEquals(first, CompositionFunction.stone(x, y, z, SALT),
                        "composition must be a pure function of (position, salt)");
            }
        }
    }

    @Test
    @DisplayName("negative and extreme coordinates behave like any others")
    void extremeCoordinatesAreWellFormed() {
        int[][] positions = {
                {0, 0, 0},
                {-1, -1, -1},
                {-30_000_000, -64, -30_000_000},
                {30_000_000, 319, 30_000_000},
                {Integer.MIN_VALUE / 2, -64, Integer.MAX_VALUE / 2},
        };
        for (int[] pos : positions) {
            Composition c = CompositionFunction.stone(pos[0], pos[1], pos[2], SALT);
            assertEquals(Composition.SLOTS, c.toArray().length);
            assertEquals(c, CompositionFunction.stone(pos[0], pos[1], pos[2], SALT));
        }
    }

    @Test
    @DisplayName("bedrock is the map: at the datum the rock is the bedrock stone")
    void bedrockRendersTheFieldAtFullCertainty() {
        // Design §4's payoff -- standing on bedrock tells you the whole column's ore family --
        // only holds if the column agrees with the floor. Jitter reaching exactly zero at the
        // datum is what makes that true, so it is asserted rather than assumed.
        for (int i = 0; i < 300; i++) {
            int x = i * 149 - 8000;
            int z = i * -211 + 3000;
            Grain expected = CompositionFunction.bedrockStone(x, z, SALT);
            Composition c = CompositionFunction.stone(x, -64, z, SALT);
            // Rock only. Mineral slots draw from their own province fields, so an ore slot at
            // bedrock is under no obligation to match the rock around it -- that independence is
            // the point, and asserting over every slot would forbid it.
            assertEquals(1, c.distinctGrains(GrainClass.ROCK),
                    "bedrock depth must be a single stone at " + x + "," + z);
            for (int slot = 0; slot < Composition.SLOTS; slot++) {
                if (c.classAt(slot) == GrainClass.ROCK) {
                    assertEquals(expected, c.grainAt(slot),
                            "the column's rock must agree with the bedrock map at " + x + "," + z);
                }
            }
        }
    }

    @Test
    @DisplayName("bedrock colour is the shared field, not a second implementation")
    void bedrockAndStoneShareOneField() {
        // §4 forbids worldgen reading bedrock *blocks* to decide stone. The structural guard is
        // that both consumers call ColourField, so there is nothing to read.
        for (int i = 0; i < 100; i++) {
            int x = i * 71;
            int z = i * -37;
            assertEquals(ColourField.sample(x, z, SALT), CompositionFunction.bedrockStone(x, z, SALT));
        }
    }

    @Test
    @DisplayName("region borders dither without any blending logic")
    void bordersDitherThemselves() {
        // Walk a long transect at the surface and find a block whose nine slots disagree on colour.
        // That is §4's whole border mechanism: per-slot jitter, no blend step anywhere.
        Composition mixed = null;
        for (int x = 0; x < 20_000 && mixed == null; x += 3) {
            Composition c = CompositionFunction.stone(x, 62, 0, SALT);
            if (c.distinctGrains(GrainClass.ROCK) > 1) {
                mixed = c;
            }
        }
        assertTrue(mixed != null, "a 20k-block transect should cross at least one region border");

        int total = 0;
        for (int count : mixed.grainCounts()) {
            total += count;
        }
        assertEquals(Composition.SLOTS, total, "a mixed block still holds exactly nine slots");
    }

    @Test
    @DisplayName("the colour field is stable under repeated sampling and spans the lattice")
    void colourFieldIsStable() {
        for (int i = 0; i < 200; i++) {
            double x = i * 13.5 - 900.0;
            double z = i * -27.25 + 400.0;
            Grain stone = ColourField.sample(x, z, SALT);
            assertEquals(GrainClass.ROCK, stone.clazz());
            assertEquals(stone, ColourField.sample(x, z, SALT));
        }
    }

    @Test
    @DisplayName("noise stays inside [0, 1)")
    void noiseIsBounded() {
        long salt = SALT;
        for (int i = 0; i < 2000; i++) {
            double x = i * 0.37 - 300.0;
            double y = i * -0.11 + 50.0;
            double z = i * 0.91 - 700.0;

            double v2 = Noise.value2(x, z, salt, Rng.STREAM_ORE);
            double v3 = Noise.value3(x, y, z, salt, Rng.STREAM_GEM);
            double f2 = Noise.fbm2(x, z, 0.01, salt, Rng.STREAM_COLOUR_WARP_X, 4);
            double f3 = Noise.fbm3(x, y, z, 0.02, salt, Rng.STREAM_PRECIOUS_ORE, 3);

            for (double v : new double[] {v2, v3, f2, f3}) {
                assertTrue(v >= 0.0 && v < 1.0, "noise escaped [0,1): " + v);
            }
        }
    }
}
