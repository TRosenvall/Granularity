package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The three rock families, and the geology that decides what each can hold. */
class BedrockTypeTest {

    private static final long SALT = WorldSalt.derive(24301L).value();

    @Test
    @DisplayName("every stone belongs to exactly one family, so a stone names its country")
    void stonesPartitionByFamily() {
        Set<Grain> seen = new HashSet<>();
        for (Grain stone : Grains.ofClass(GrainClass.ROCK)) {
            int families = 0;
            for (BedrockType family : BedrockType.values()) {
                if (stone.occursIn(family)) {
                    families++;
                }
            }
            assertEquals(1, families, stone.name() + " should belong to exactly one family");
            assertTrue(seen.add(stone));
        }
        for (BedrockType family : BedrockType.values()) {
            assertFalse(Grains.admitted(family, GrainClass.ROCK).isEmpty(),
                    family + " has no stone to be made of");
        }
    }

    @Test
    @DisplayName("areal distribution follows the weights, not how many stones a family has")
    void arealDistributionFollowsWeights() {
        // Drawing a stone uniformly would give each family area in proportion to its stone count.
        // The family is picked by weight first, then a stone within it.
        Map<BedrockType, Integer> counts = new EnumMap<>(BedrockType.class);
        for (BedrockType type : BedrockType.values()) {
            counts.put(type, 0);
        }
        int samples = 0;
        for (int i = 0; i < 260; i++) {
            for (int j = 0; j < 260; j++) {
                BedrockType family = ColourField.sample(i * 160.0 - 20_000.0, j * 160.0 - 20_000.0, SALT)
                        .family();
                counts.merge(family, 1, Integer::sum);
                samples++;
            }
        }

        int totalWeight = 0;
        for (BedrockType type : BedrockType.values()) {
            totalWeight += type.weight();
        }
        System.out.println("  areal share of each rock family:");
        for (BedrockType type : BedrockType.values()) {
            double actual = counts.get(type) / (double) samples;
            double expected = type.weight() / (double) totalWeight;
            System.out.printf(Locale.ROOT, "    %-12s %.3f (target %.3f)%n", type, actual, expected);
            assertTrue(Math.abs(actual - expected) < 0.06,
                    type + " share " + actual + " strayed from target " + expected);
        }
    }

    @Test
    @DisplayName("every stone occurs somewhere, despite unequal family weights")
    void everyStoneIsReachable() {
        Set<Grain> seen = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            for (int j = 0; j < 400; j++) {
                seen.add(ColourField.sample(i * 200.0 - 40_000.0, j * 200.0 - 40_000.0, SALT));
            }
        }
        for (Grain stone : Grains.ofClass(GrainClass.ROCK)) {
            assertTrue(seen.contains(stone), stone.name() + " never occurred in 160k samples");
        }
    }

    @Test
    @DisplayName("a region is one stone throughout, so boundaries are the cell borders")
    void regionsAreCoherent() {
        int agreements = 0;
        int comparisons = 0;
        for (int i = 0; i < 300; i++) {
            for (int j = 0; j < 300; j++) {
                double x = i * 16.0 - 2400.0;
                double z = j * 16.0 - 2400.0;
                Grain here = ColourField.sample(x, z, SALT);
                if (ColourField.sample(x + 16.0, z, SALT) == here) {
                    agreements++;
                }
                comparisons++;
            }
        }
        double coherence = agreements / (double) comparisons;
        System.out.printf(Locale.ROOT, "  stone agreement at 16 blocks: %.4f%n", coherence);
        assertTrue(coherence > 0.90, "stone must occupy regions, not scatter: " + coherence);
    }
}
