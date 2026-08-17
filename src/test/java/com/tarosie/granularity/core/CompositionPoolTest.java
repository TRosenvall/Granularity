package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pooling several blocks into one composition, which is what closes the laundering hole.
 *
 * <p>Cutting three cobblestones into six slabs used to keep the <b>first</b> block's composition and
 * throw the other two away, so one marble block beside two granite ones made six marble slabs — and
 * hammering those turned granite into marble at no cost. The result has to carry the mix that
 * actually went in.
 */
class CompositionPoolTest {

    private static final int MARBLE = Grains.MARBLE.id();

    private static final int GRANITE = Grains.GRANITE.id();

    private static final int SLATE = Grains.SLATE.id();

    private static int countOf(Composition composition, int grainId) {
        int found = 0;
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            if (composition.grainId(slot) == grainId) {
                found++;
            }
        }
        return found;
    }

    @Test
    @DisplayName("one marble block among three comes out as a third of the result")
    void oneInThreeBecomesThreeNinths() {
        Composition pooled = Composition.pooled(List.of(
                Composition.uniform(MARBLE),
                Composition.uniform(GRANITE),
                Composition.uniform(GRANITE)));

        assertEquals(3, countOf(pooled, MARBLE), "nine of twenty-seven grains were marble");
        assertEquals(6, countOf(pooled, GRANITE), "eighteen of twenty-seven were granite");
    }

    @Test
    @DisplayName("pooling always fills exactly nine slots, whatever the ratio")
    void alwaysExactlyNineSlots() {
        List<List<Composition>> cases = List.of(
                List.of(Composition.uniform(MARBLE), Composition.uniform(GRANITE)),
                List.of(Composition.uniform(MARBLE), Composition.uniform(GRANITE),
                        Composition.uniform(SLATE)),
                List.of(Composition.of(new int[] {MARBLE, GRANITE, SLATE, MARBLE, GRANITE, SLATE,
                        MARBLE, GRANITE, SLATE}), Composition.uniform(SLATE)),
                List.of(Composition.of(new int[] {MARBLE, GRANITE, GRANITE, GRANITE, GRANITE,
                        GRANITE, GRANITE, GRANITE, GRANITE}),
                        Composition.uniform(GRANITE), Composition.uniform(GRANITE)));

        for (List<Composition> inputs : cases) {
            Composition pooled = Composition.pooled(inputs);
            int filled = 0;
            for (int grain = 0; grain < Grains.count(); grain++) {
                filled += countOf(pooled, grain);
            }
            assertEquals(Composition.SLOTS, filled,
                    "a composition is always nine grains, whatever went in");
        }
    }

    @Test
    @DisplayName("a single input is passed through untouched")
    void oneInputIsUnchanged() {
        Composition only = Composition.of(new int[] {MARBLE, GRANITE, SLATE, MARBLE, GRANITE,
                SLATE, MARBLE, GRANITE, SLATE});
        assertEquals(only, Composition.pooled(List.of(only)),
                "cutting one block must not disturb its composition at all");
    }

    @Test
    @DisplayName("the majority stays the majority — laundering cannot invert a mix")
    void majorityIsPreserved() {
        // The hole that started this: one marble among two granite must not yield marble slabs.
        Composition pooled = Composition.pooled(List.of(
                Composition.uniform(MARBLE),
                Composition.uniform(GRANITE),
                Composition.uniform(GRANITE)));
        assertTrue(countOf(pooled, GRANITE) > countOf(pooled, MARBLE),
                "granite went in twice as often and must come out that way");
    }
}
