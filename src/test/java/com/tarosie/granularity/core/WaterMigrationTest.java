package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tarosie.granularity.core.WaterMigration.WaterBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The migration rule's invariants, ported from the prototype's {@code test_m5_voxel.py}.
 *
 * <p>These are the tests findings §6.2 credits with catching a two-drop-per-tick leak that would
 * have been invisible in a running game — water appearing slowly looks exactly like water behaving.
 * They are cheap because the rule runs over {@link WaterVolume} rather than over a level, so a
 * scenario is an array of integers and a tick is a method call.
 *
 * <p>Conservation is asserted after <i>every</i> tick rather than at the end of a run. A leak that
 * gains two drops and loses two drops nets to zero over a run and is a real bug.
 */
class WaterMigrationTest {

    private static final long SALT = WorldSalt.derive(0xB0A7L).value();

    @Test
    @DisplayName("water is conserved exactly, tick by tick")
    void conservation() {
        Grid grid = new Grid(9, 12, 9);
        grid.fillGrains(0);
        grid.floor(0, 9);
        grid.setWater(4, 8, 4, 9);
        grid.setWater(3, 8, 4, 7);
        grid.setWater(5, 9, 5, 4);

        int before = grid.totalWater();
        for (int tick = 0; tick < 60; tick++) {
            WaterMigration.step(grid, grid.bounds(), tick, SALT);
            assertEquals(before, grid.totalWater(), "water was created or destroyed at tick " + tick);
            grid.assertWellFormed();
        }
    }

    @Test
    @DisplayName("four sources onto one block does not create water")
    void fourIntoOneIsExact() {
        // The trap findings §6.2 records, set up on purpose. A block with room for one drop, ringed
        // by four blocks that all want to give it some. A single scatter would have to hand the
        // excess back, and refunding it to every contributor over-refunds — the two-drop leak. One
        // direction per pass means this block has at most one source per pass and the capacity check
        // is exact.
        //
        // Run over many ticks and many salts, because which neighbour aims at the middle is a draw:
        // a single tick might not even produce the collision.
        //
        // This is the only test in the file that catches the trap — verified by deleting the live
        // capacity re-check in the apply phase, which fails here with "8 grains and 4 drops" and
        // leaves the other seven passing. Note also what *cannot* be caught by injection: making the
        // apply phase add the gathered amount while subtracting the capped one looks like a leak but
        // is masked by the `give <= 0` guard above it, so it passes everything. A guard that catches
        // one shape of a bug is not a guard against the bug.
        for (long trial = 0; trial < 40; trial++) {
            Grid grid = new Grid(3, 3, 3);
            grid.fillGrains(9);
            // One height, five blocks: a cross. The middle has eight grains, so exactly one free slot.
            for (int[] cell : new int[][]{{0, 1}, {2, 1}, {1, 0}, {1, 2}}) {
                grid.setGrains(cell[0], 1, cell[1], 0);
                grid.setWater(cell[0], 1, cell[1], 9);
            }
            grid.setGrains(1, 1, 1, 8);

            int before = grid.totalWater();
            for (int tick = 0; tick < 12; tick++) {
                WaterMigration.spread(grid, grid.bounds(), tick, SALT + trial * 7919L,
                        WaterMigration.CREEP_RATE);
                assertEquals(before, grid.totalWater(),
                        "trial " + trial + " tick " + tick + " changed the total");
                grid.assertWellFormed();
            }
            assertTrue(grid.water(1, 1, 1) <= 1,
                    "the middle block holds " + grid.water(1, 1, 1) + " drops in one free slot");
        }
    }

    @Test
    @DisplayName("impermeable rock admits no water, and water rests on top of it")
    void aquicludeHolds() {
        // §6.3's aquiclude. Nine grains is nine grains: there is no slot for a drop, and the rule
        // never needs to be told that rock is impermeable — it reads the room and finds none.
        Grid grid = new Grid(7, 10, 7);
        grid.fillGrains(0);
        for (int y = 0; y <= 4; y++) {
            grid.layerGrains(y, 9);
        }
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                grid.setWater(x, 9, z, 6);
            }
        }

        int before = grid.totalWater();
        for (int tick = 0; tick < 80; tick++) {
            WaterMigration.step(grid, grid.bounds(), tick, SALT);
        }
        assertEquals(before, grid.totalWater());
        int inside = 0;
        for (int y = 0; y <= 4; y++) {
            inside += grid.layerWater(y);
        }
        assertEquals(0, inside, "water entered impermeable rock");
        assertTrue(grid.layerWater(5) > 0, "water should be resting on top of the aquiclude");
    }

    @Test
    @DisplayName("porous rock takes water into its pores, tight rock beside it does not")
    void porousRockSoaks() {
        // The behaviour the whole feature is for: water filtering *through* rock rather than around
        // it. Half the column is rock with three free slots per block, half is solid; the same water
        // is poured on both.
        Grid porous = columnWithFloor(6);
        Grid tight = columnWithFloor(9);
        for (int tick = 0; tick < 40; tick++) {
            WaterMigration.step(porous, porous.bounds(), tick, SALT);
            WaterMigration.step(tight, tight.bounds(), tick, SALT);
        }

        int soaked = 0;
        for (int y = 1; y <= 5; y++) {
            soaked += porous.layerWater(y);
        }
        assertTrue(soaked > 0, "water never entered the porous rock at all");
        int inTight = 0;
        for (int y = 1; y <= 5; y++) {
            inTight += tight.layerWater(y);
        }
        assertEquals(0, inTight, "water entered rock with no pores");
    }

    @Test
    @DisplayName("a block conducts only what it has room for")
    void throughputIsTheFreeSlots() {
        // Timothy's rule, as behaviour rather than as arithmetic: rock missing one grain passes
        // level-1 water and no more. The block below the neck can never hold more than the neck
        // could have delivered in a tick.
        Grid grid = new Grid(1, 6, 1);
        grid.fillGrains(0);
        grid.setGrains(0, 0, 0, 0);
        grid.setGrains(0, 1, 0, 8);   // the neck: one free slot
        grid.setGrains(0, 2, 0, 0);
        grid.setWater(0, 2, 0, 9);

        assertEquals(1, WaterLevels.conductivity(compositionOf(grid, 0, 1, 0)),
                "a block with one free slot conducts one drop");

        for (int tick = 0; tick < 3; tick++) {
            WaterMigration.step(grid, grid.bounds(), tick, SALT);
            assertTrue(grid.water(0, 1, 0) <= 1,
                    "the neck held " + grid.water(0, 1, 0) + " drops in one free slot");
        }
        assertTrue(grid.water(0, 0, 0) > 0, "water should have made it through the neck");
    }

    @Test
    @DisplayName("still water settles instead of churning forever")
    void stillWaterSettles() {
        // Without the creep exception a pool one drop out of level jitters indefinitely, because the
        // transfer cap floors a difference of one to zero. With it, the pool reaches a state where a
        // tick moves nothing at all -- which is also what makes it affordable to stop ticking.
        Grid grid = new Grid(5, 4, 5);
        grid.fillGrains(0);
        grid.layerGrains(0, 9);
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                grid.setWater(x, 1, z, 4);
            }
        }
        int quietFrom = -1;
        for (int tick = 0; tick < 200; tick++) {
            if (WaterMigration.step(grid, grid.bounds(), tick, SALT) == 0 && quietFrom < 0) {
                quietFrom = tick;
            }
        }
        assertTrue(quietFrom >= 0, "a level pool never reached a tick that moved nothing");
    }

    @Test
    @DisplayName("the same salt reproduces the run; a different one does not")
    void deterministic() {
        assertEquals(runToString(0xABCL), runToString(0xABCL));
        assertNotEquals(runToString(1L), runToString(2L));
    }

    @Test
    @DisplayName("water cannot rise above its entry level — there is no pressure")
    void uTubeDoesNotEqualize() {
        // Findings §6.4, asserted rather than merely documented, so that if someone adds a pressure
        // solve this fails and the docs get corrected instead of quietly going stale. The cost is
        // karst hydrology: water running under a ridge and rising on the far side will not happen.
        Grid grid = new Grid(5, 8, 1);
        grid.fillGrains(9);
        // Two shafts joined by a tunnel at the bottom: |_| with rock either side.
        for (int y = 1; y <= 6; y++) {
            grid.setGrains(0, y, 0, 0);
            grid.setGrains(4, y, 0, 0);
        }
        for (int x = 0; x <= 4; x++) {
            grid.setGrains(x, 1, 0, 0);
        }
        for (int y = 2; y <= 6; y++) {
            grid.setWater(0, y, 0, 9);
        }

        int before = grid.totalWater();
        for (int tick = 0; tick < 200; tick++) {
            WaterMigration.step(grid, grid.bounds(), tick, SALT);
        }
        assertEquals(before, grid.totalWater());

        int farShaftAboveTunnel = 0;
        for (int y = 2; y <= 6; y++) {
            farShaftAboveTunnel += grid.water(4, y, 0);
        }
        assertEquals(0, farShaftAboveTunnel,
                "water climbed the far shaft, so something has added pressure — see findings §6.4");
    }

    private static String runToString(long seed) {
        long salt = WorldSalt.derive(seed).value();
        Grid grid = new Grid(6, 8, 6);
        grid.fillGrains(0);
        grid.layerGrains(0, 9);
        grid.setWater(3, 7, 3, 9);
        grid.setWater(2, 7, 2, 9);
        for (int tick = 0; tick < 40; tick++) {
            WaterMigration.step(grid, grid.bounds(), tick, salt);
        }
        return grid.describe();
    }

    /** A column of rock over a floor, with water poured on top. */
    private static Grid columnWithFloor(int grainsPerBlock) {
        Grid grid = new Grid(1, 8, 1);
        grid.fillGrains(0);
        grid.setGrains(0, 0, 0, 9);
        for (int y = 1; y <= 5; y++) {
            grid.setGrains(0, y, 0, grainsPerBlock);
        }
        grid.setWater(0, 7, 0, 9);
        grid.setWater(0, 6, 0, 9);
        return grid;
    }

    private static Composition compositionOf(Grid grid, int x, int y, int z) {
        int[] slots = new int[Composition.SLOTS];
        int grains = grid.grains(x, y, z);
        int water = grid.water(x, y, z);
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            if (slot < grains) {
                slots[slot] = Grains.GRANITE.id();
            } else if (slot < grains + water) {
                slots[slot] = Grains.WATER.id();
            } else {
                slots[slot] = Grains.AIR.id();
            }
        }
        return Composition.of(slots);
    }

    /** An array-backed volume. Outside it is rock, which is what {@link WaterVolume} promises. */
    private static final class Grid implements WaterVolume {

        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int[] grains;
        private final int[] water;

        Grid(int sizeX, int sizeY, int sizeZ) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.grains = new int[sizeX * sizeY * sizeZ];
            this.water = new int[sizeX * sizeY * sizeZ];
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
        }

        @Override
        public int grains(int x, int y, int z) {
            return contains(x, y, z) ? grains[index(x, y, z)] : Composition.SLOTS;
        }

        @Override
        public int water(int x, int y, int z) {
            return contains(x, y, z) ? water[index(x, y, z)] : 0;
        }

        @Override
        public void setWater(int x, int y, int z, int drops) {
            water[index(x, y, z)] = drops;
        }

        void setGrains(int x, int y, int z, int count) {
            grains[index(x, y, z)] = count;
        }

        void fillGrains(int count) {
            java.util.Arrays.fill(grains, count);
        }

        void layerGrains(int y, int count) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    setGrains(x, y, z, count);
                }
            }
        }

        void floor(int y, int count) {
            layerGrains(y, count);
        }

        int layerWater(int y) {
            int total = 0;
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    total += water(x, y, z);
                }
            }
            return total;
        }

        int totalWater() {
            int total = 0;
            for (int drops : water) {
                total += drops;
            }
            return total;
        }

        WaterBounds bounds() {
            return new WaterBounds(0, 0, 0, sizeX - 1, sizeY - 1, sizeZ - 1);
        }

        void assertWellFormed() {
            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    for (int z = 0; z < sizeZ; z++) {
                        int held = water(x, y, z);
                        assertTrue(held >= 0, "negative water at " + x + "," + y + "," + z);
                        assertTrue(grains(x, y, z) + held <= Composition.SLOTS,
                                "over capacity at " + x + "," + y + "," + z + ": "
                                        + grains(x, y, z) + " grains and " + held + " drops");
                    }
                }
            }
        }

        String describe() {
            StringBuilder out = new StringBuilder();
            for (int drops : water) {
                out.append(drops).append(',');
            }
            return out.toString();
        }

        private int index(int x, int y, int z) {
            return (y * sizeZ + z) * sizeX + x;
        }
    }
}
