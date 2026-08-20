package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drops in slots against vanilla's fluid levels.
 *
 * <p>Small arithmetic, and worth its own test because the fluid layer's conservation rests on it. A
 * drop that appears when water leaves a rock, or vanishes when it enters one, is water created or
 * destroyed — and the mod would look entirely correct while doing it.
 */
class WaterLevelsTest {

    @Test
    @DisplayName("a full block is a source; anything less is a flow level")
    void fullBlockIsASource() {
        assertTrue(WaterLevels.isSource(Composition.SLOTS));
        assertFalse(WaterLevels.isSource(Composition.SLOTS - 1));
        assertEquals(0, WaterLevels.amount(0), "dry rock releases no water at all");
        assertEquals(1, WaterLevels.amount(1),
                "one free slot's worth of water is level 1 and nothing more");
    }

    @Test
    @DisplayName("drops survive the round trip through a fluid level")
    void dropsRoundTrip() {
        // Exact for all ten counts, because the source flag is carried. That is the contract every
        // conservation argument in the fluid layer leans on.
        for (int drops = 0; drops <= Composition.SLOTS; drops++) {
            assertEquals(drops,
                    WaterLevels.dropsFor(WaterLevels.amount(drops), WaterLevels.isSource(drops)),
                    "drop count " + drops + " must survive being read as a fluid level");
        }
    }

    @Test
    @DisplayName("an amount on its own cannot tell a full block from a nearly full one")
    void amountAloneIsAmbiguous() {
        // §7's "almost 1:1", asserted so the ambiguity is a known shape of the mapping rather than a
        // surprise found later. Eight drops and nine report the same amount and differ only in the
        // flag, so a caller that reads one and not the other loses a drop here and nowhere else.
        assertEquals(WaterLevels.amount(Composition.SLOTS), WaterLevels.amount(Composition.SLOTS - 1));
        assertTrue(WaterLevels.isSource(Composition.SLOTS));
        assertFalse(WaterLevels.isSource(Composition.SLOTS - 1));
        assertEquals(Composition.SLOTS - 1, WaterLevels.dropsFor(WaterLevels.MAX_AMOUNT, false));
        assertEquals(Composition.SLOTS, WaterLevels.dropsFor(WaterLevels.MAX_AMOUNT, true));
    }

    @Test
    @DisplayName("what a rock conducts is what it has room for")
    void conductivityIsTheFreeSlots() {
        int[] oneFree = new int[Composition.SLOTS];
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            oneFree[slot] = Grains.GRANITE.id();
        }
        oneFree[4] = Grains.AIR.id();
        Composition porous = Composition.of(oneFree);
        assertEquals(1, WaterLevels.conductivity(porous),
                "rock with one empty slot passes level-1 water and no more");
        assertEquals(1, WaterLevels.amount(WaterLevels.conductivity(porous)));

        int[] solid = new int[Composition.SLOTS];
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            solid[slot] = Grains.GRANITE.id();
        }
        assertEquals(0, WaterLevels.conductivity(Composition.of(solid)),
                "an aquiclude passes nothing; that is what makes it one");

        // Already-wet pores are not room. A saturated rock conducts nothing by this measure, which is
        // a real limit of the rule rather than a bug: below the water table flow is displacement, and
        // that belongs to the migration tier rather than to a capacity count.
        int[] wet = solid.clone();
        wet[4] = Grains.WATER.id();
        assertEquals(0, WaterLevels.conductivity(Composition.of(wet)));
    }
}
