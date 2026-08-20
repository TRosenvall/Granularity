package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stored half of design §6's split rule.
 *
 * <p>Most of what this class has to get right is about <i>not</i> storing: a block at equilibrium
 * must leave no trace, and a deviation must eventually stop existing. Both are invisible in play and
 * both show up as a save file that grows forever.
 */
class WaterDeviationsTest {

    @Test
    @DisplayName("a block at its baseline stores nothing at all")
    void equilibriumIsFree() {
        WaterDeviations deviations = new WaterDeviations();
        deviations.setWaterAt(42L, 3, 3);
        assertEquals(0, deviations.size(), "storing the baseline back must not create an entry");
        assertTrue(deviations.isEmpty());
        assertEquals(3, deviations.waterAt(42L, 3, 9), "and the block still reads as its baseline");
    }

    @Test
    @DisplayName("writing back to the baseline removes an existing entry")
    void returningToBaselineForgets() {
        // The case that matters more than the one above: a block that was disturbed and has since
        // come back. If this left a zero behind, every block anyone ever dug near would be stored
        // forever, which is the failure this whole representation exists to avoid.
        WaterDeviations deviations = new WaterDeviations();
        deviations.setWaterAt(7L, 2, 6);
        assertEquals(1, deviations.size());
        deviations.setWaterAt(7L, 2, 2);
        assertEquals(0, deviations.size(), "a block back at equilibrium must stop being stored");
    }

    @Test
    @DisplayName("water is clamped to the pores that can hold it")
    void clampedToCapacity() {
        WaterDeviations deviations = new WaterDeviations();
        deviations.setWaterAt(1L, 0, 20);
        assertEquals(4, deviations.waterAt(1L, 0, 4), "a block cannot hold more than it has pores");
        deviations.setWaterAt(2L, 5, -3);
        assertEquals(0, deviations.waterAt(2L, 5, 9), "and cannot hold less than nothing");
    }

    @Test
    @DisplayName("deviations decay to nothing from either side")
    void decayExpiresEntries() {
        WaterDeviations deviations = new WaterDeviations();
        deviations.setWaterAt(10L, 0, 3);    // +3: water arrived
        deviations.setWaterAt(11L, 6, 2);    // -4: water left
        assertEquals(2, deviations.size());

        for (int step = 0; step < 3; step++) {
            deviations.decay();
        }
        assertEquals(0, deviations.deviation(10L), "a positive deviation should have reached zero");
        assertEquals(1, deviations.size(), "and the negative one should still be on its way");

        deviations.decay();
        assertTrue(deviations.isEmpty(), "every deviation must expire eventually");
    }

    @Test
    @DisplayName("what is saved is what comes back")
    void roundTrips() {
        WaterDeviations deviations = new WaterDeviations();
        deviations.setWaterAt(100L, 1, 5);
        deviations.setWaterAt(200L, 8, 2);

        WaterDeviations loaded = new WaterDeviations();
        loaded.load(new HashMap<>(deviations.entries()));
        assertEquals(deviations.entries(), loaded.entries());
        assertEquals(5, loaded.waterAt(100L, 1, 9));
        assertEquals(2, loaded.waterAt(200L, 8, 9));
    }

    @Test
    @DisplayName("a zero in saved data is dropped rather than kept")
    void loadingIgnoresZeroes() {
        // Older saves, hand-edited data, a bug upstream. A stored zero is indistinguishable from no
        // entry in meaning and different from it in cost, so it does not survive the trip in.
        Map<Long, Integer> stored = new HashMap<>();
        stored.put(1L, 0);
        stored.put(2L, 3);
        WaterDeviations deviations = new WaterDeviations();
        deviations.load(stored);
        assertEquals(1, deviations.size());
        assertEquals(3, deviations.deviation(2L));
    }
}
