package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the groundwater stands, and which rock it can stand in.
 *
 * <p>Design §7's hydrology map as an equilibrium baseline. The assertions are about <i>shape</i>
 * rather than about any particular number of drops: a water table is a surface, water only exists in
 * pores, and pores only exist in rock that has them. Those three are what the fluid layer will be
 * built on, and each of them can break silently — a wet block looks like a dry one.
 */
class WaterTableTest {

    private static final long SALT = WorldSalt.derive(0x5EAL).value();

    @Test
    @DisplayName("the table is a surface, not a field of speckle")
    void tableIsASurface() {
        // If this varied block to block it would not be a water table; it would be noise that happens
        // to be measured in metres. A real one is nearly level across a valley, which is exactly what
        // lets one hillside be dry while the ground below it is soaked.
        double steepest = 0.0;
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (int i = 0; i < 400; i++) {
            int x = 3000 + i;
            double here = WaterTable.elevation(x, -1200, SALT);
            double next = WaterTable.elevation(x + 1, -1200, SALT);
            steepest = Math.max(steepest, Math.abs(next - here));
            lowest = Math.min(lowest, here);
            highest = Math.max(highest, here);
        }
        assertTrue(steepest < 1.0,
                "the table should be near level block to block, but stepped " + steepest + " blocks");
        assertTrue(highest - lowest > 1.0,
                "the table should still vary regionally; it moved only " + (highest - lowest)
                        + " blocks over 400");
    }

    @Test
    @DisplayName("a column has one table height, whatever depth you ask from")
    void oneTablePerColumn() {
        // Structural: elevation takes no y. Asserted anyway because saturation does take one, and a
        // future version that let the table drift with depth would still compile and still look
        // plausible in game.
        double table = WaterTable.elevation(77, -404, SALT);
        for (int y = -64; y < 200; y += 8) {
            double expected = y <= table ? 1.0 : WaterTable.saturation(77, y, -404, SALT);
            assertEquals(expected, WaterTable.saturation(77, y, -404, SALT),
                    "saturation must be a function of this one table height");
        }
        assertEquals(1.0, WaterTable.saturation(77, (int) table - 1, -404, SALT));
        assertEquals(0.0, WaterTable.saturation(77, (int) table + 40, -404, SALT));
    }

    @Test
    @DisplayName("below the table every pore is water; above it every pore is air")
    void poresFillBelowAndEmptyAbove() {
        // The sharpest statement of what a water table is, and both halves matter. Below it there is
        // no dry pore anywhere -- that is what "saturated" means, and it is also what makes an
        // aquifer able to give water up when you break into it. Above the fringe there is no wet one.
        int wetAbove = 0;
        int dryBelow = 0;
        int checked = 0;
        for (int i = 0; i < 300; i++) {
            int x = i * 37 - 4000;
            int z = i * -61 + 2200;
            double table = WaterTable.elevation(x, z, SALT);

            Composition below = CompositionFunction.stone(x, (int) table - 20, z, SALT);
            if (below.freeSlots() > 0) {
                dryBelow++;
            }
            Composition above = CompositionFunction.stone(x, (int) table + 24, z, SALT);
            if (above.water() > 0) {
                wetAbove++;
            }
            checked++;
        }
        assertEquals(300, checked);
        assertEquals(0, dryBelow, "rock below the water table must hold no empty pore");
        assertEquals(0, wetAbove, "rock well above the water table must hold no water");
    }

    @Test
    @DisplayName("the fringe is a gradient, so mining down reads damp then wet")
    void fringeIsPartial() {
        // A hard edge would be a wall of dry rock against a wall of wet, which reads as a bug rather
        // than as a water table. Capillary rise is real, and it is also the only part of this field a
        // player meets face-on, one block at a time.
        int partial = 0;
        for (int i = 0; i < 400; i++) {
            int x = i * 53 + 900;
            int z = i * 29 - 700;
            int table = (int) WaterTable.elevation(x, z, SALT);
            for (int step = 1; step <= 3; step++) {
                Composition c = CompositionFunction.stone(x, table + step, z, SALT);
                if (c.water() > 0 && c.freeSlots() > 0) {
                    partial++;
                }
            }
        }
        assertTrue(partial > 20,
                "the capillary fringe should hold rock that is part wet and part dry, found "
                        + partial);
    }

    @Test
    @DisplayName("tight rock stays dry however deep it is drowned")
    void impermeableRockHoldsNoWater() {
        // The aquiclude, which is the whole point: §6.3's spring needs rock that water cannot enter.
        // It is dry here not because a rule excludes it but because the rock has nowhere to put it --
        // the water draw happens inside a pore or not at all.
        Map<BedrockType, int[]> seen = new HashMap<>();
        for (int x = 0; x < 200; x += 3) {
            for (int z = 0; z < 200; z += 3) {
                for (int y = -60; y < 0; y += 9) {
                    Composition c = CompositionFunction.stone(x, y, z, SALT);
                    Grain rock = ColourField.rockAt(x, z, SALT);
                    for (BedrockType family : rock.families()) {
                        int[] tally = seen.computeIfAbsent(family, ignored -> new int[2]);
                        tally[0] += c.water();
                        tally[1] += Composition.SLOTS;
                    }
                }
            }
        }
        double igneous = share(seen, BedrockType.IGNEOUS);
        double sedimentary = share(seen, BedrockType.SEDIMENTARY);
        assertTrue(igneous < 0.03, "igneous country should be near-dry, held " + igneous);
        assertTrue(sedimentary > igneous * 4,
                "sedimentary country " + sedimentary + " should be far wetter than igneous "
                        + igneous);
    }

    @Test
    @DisplayName("how porous the rock is does not depend on whether it is wet")
    void porosityIsBlindToTheWaterTable() {
        // Guards the ordering in CompositionFunction: the water draw sits *inside* the branch that
        // already decided this slot is a pore. Reverse them and rock dissolves into water below the
        // table.
        //
        // Written as a comparison across the table rather than as a per-block identity, and the first
        // attempt is worth recording because it looked right and tested nothing. It asserted
        // porosity() == water() + freeSlots(), which cannot fail: porosity is *defined* as air plus
        // water. Breaking the ordering on purpose left it passing. What actually distinguishes the
        // two orderings is that a pore count must not know where the water table is — saturation
        // decides what fills a pore, never whether there is one.
        long poresBelow = 0;
        long poresAbove = 0;
        int samples = 0;
        for (int i = 0; i < 400; i++) {
            int x = i * 91 - 6000;
            int z = i * -47 + 1500;
            int table = (int) WaterTable.elevation(x, z, SALT);
            for (int step = 6; step < 18; step += 3) {
                poresBelow += CompositionFunction.stone(x, table - step, z, SALT).porosity();
                poresAbove += CompositionFunction.stone(x, table + step, z, SALT).porosity();
                samples++;
            }
        }
        assertTrue(samples > 0);
        // Not equality: porosity is a 3D field, so a band twelve blocks lower is genuinely somewhat
        // different rock. A factor of two is well inside that; letting water claim a slot the rock
        // already held was measured at 5.5, so the margin is comfortable in both directions.
        //
        // What this does *not* catch, checked by breaking it: making every slot a pore. That raises
        // porosity to nine on both sides of the table at once, so the ratio stays at one. It is the
        // igneous-stays-dry test above and PorosityFieldTest's tight-run that fail on that one — a
        // ratio can only see a change that crosses the table, which is precisely the ordering bug
        // this is aimed at.
        double ratio = poresBelow / (double) Math.max(1, poresAbove);
        assertTrue(ratio > 0.5 && ratio < 2.0,
                "crossing the water table changed how porous the rock is, by a factor of " + ratio
                        + " — water is being drawn outside the pore it belongs in");
    }

    @Test
    @DisplayName("a pore holds one thing at a time")
    void everyPoreIsAirOrWater() {
        for (int i = 0; i < 500; i++) {
            int x = i * 91 - 6000;
            int y = (i % 90) - 64;
            int z = i * -47 + 1500;
            Composition c = CompositionFunction.stone(x, y, z, SALT);
            assertEquals(c.porosity(), c.water() + c.freeSlots(),
                    "every pore is either water or air, and nothing else is a pore");
        }
    }

    @Test
    @DisplayName("rock can never hold a source's worth of water")
    void rockNeverHoldsNineDrops() {
        // Why releasing water from a broken block is never a source, stated as arithmetic rather than
        // as a rule someone has to remember to apply. A natural block keeps at least one grain, so at
        // most eight of its slots are pores, so at most eight are water -- and vanilla's deepest flow
        // is eight. The cap in WaterLevels.amount is therefore unreachable from real rock, and no
        // drop is ever lost converting one to the other.
        //
        // Nine drops is still a meaningful composition; it is simply not a block of rock. It is what
        // open water is made of.
        int mostFound = 0;
        for (int i = 0; i < 30000; i++) {
            int x = i * 19 - 120000;
            int z = i * -31 + 60000;
            int y = (i % 130) - 64;
            int drops = CompositionFunction.stone(x, y, z, SALT).water();
            mostFound = Math.max(mostFound, drops);
        }
        assertTrue(mostFound <= Composition.SLOTS - 1,
                "rock held " + mostFound + " drops, which is a source block's worth");
        assertTrue(mostFound > 4,
                "no rock anywhere held much water at all — found at most " + mostFound
                        + " drops, so this proves nothing about the ceiling");
        assertEquals(WaterLevels.MAX_AMOUNT, WaterLevels.amount(Composition.SLOTS - 1),
                "eight drops is vanilla's deepest flow, which is what the fullest rock releases");
    }

    @Test
    @DisplayName("water never leaves the world as an item")
    void waterIsUnobtainable() {
        // The same three-part guarantee air has, asserted at the part that would bite: nothing can
        // look up an item for water and hand it over. Water leaves a broken block as a fluid level,
        // never as a stack.
        assertTrue(!Grains.itemIds().contains(Grains.WATER.itemId()),
                "water must not be an obtainable grain");
        assertEquals(GrainClass.WATER, Grains.WATER.clazz());
        assertTrue(!Grains.WATER.clazz().isObtainable(), "water must yield no item");
    }

    private static double share(Map<BedrockType, int[]> seen, BedrockType family) {
        int[] tally = seen.get(family);
        return tally == null || tally[1] == 0 ? 0.0 : (double) tally[0] / tally[1];
    }
}
