package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tarosie.granularity.core.HumidityTransport.Bounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The field tier's invariants — design §8's coarse grid, §11's water cycle.
 *
 * <p>Conservation is asserted after <b>every</b> step rather than at the end of a run, because a rule
 * that gains two drops and loses two nets to zero over a run and is still a bug. §11 claims
 * "conservation throughout" for the whole cycle; this is the half of it that happens in the sky.
 *
 * <p>The trap here is the one findings §6.2 recorded for water in rock and it is unchanged by the
 * change of medium: several cells advecting into one. Hence the same structural answer, and hence a
 * test aimed squarely at it.
 */
class HumidityTransportTest {

    private static final long SALT = WorldSalt.derive(0xA12L).value();

    @Test
    @DisplayName("vapour is conserved exactly, step by step")
    void conservation() {
        Sky sky = new Sky(12, 12, 40);
        sky.set(6, 6, 300);
        sky.set(3, 8, 120);

        int before = sky.total();
        for (int step = 0; step < 80; step++) {
            HumidityTransport.step(sky, sky.bounds(), step, SALT);
            assertEquals(before, sky.accountedFor(),
                    "vapour was created or destroyed at step " + step);
            sky.assertWellFormed();
        }
    }

    @Test
    @DisplayName("several columns blowing into one does not create vapour")
    void convergenceIsExact() {
        // The findings §6.2 trap, in air. Four full columns around one empty, run under many salts
        // so the wind points every which way across the trials.
        for (long trial = 0; trial < 30; trial++) {
            Sky sky = new Sky(3, 3, 1000);
            sky.set(0, 1, 400);
            sky.set(2, 1, 400);
            sky.set(1, 0, 400);
            sky.set(1, 2, 400);

            int before = sky.total();
            for (int step = 0; step < 10; step++) {
                HumidityTransport.advect(sky, sky.bounds(), step, SALT + trial * 7919L);
                assertEquals(before, sky.accountedFor(),
                        "trial " + trial + " step " + step + " changed the total");
                sky.assertWellFormed();
            }
        }
    }

    @Test
    @DisplayName("a column never promises away more than it holds")
    void neverOverdrawn() {
        // The wind can point along both axes at once, so four directions are each asked for a share
        // of the same column. Without trimming, a strong diagonal wind would hand out more than the
        // column has and the apply pass would silently clamp — losing vapour rather than moving it.
        Sky sky = new Sky(5, 5, 10_000);
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                sky.set(x, z, 9);
            }
        }
        int before = sky.total();
        for (int step = 0; step < 40; step++) {
            HumidityTransport.advect(sky, sky.bounds(), step, SALT);
            assertEquals(before, sky.accountedFor(), "step " + step + " lost or gained vapour");
            sky.assertWellFormed();
        }
    }

    @Test
    @DisplayName("rain falls only where the sky is over capacity")
    void rainNeedsExcess() {
        Sky sky = new Sky(6, 6, 100);
        sky.set(2, 2, 40);
        int fell = HumidityTransport.condense(sky, sky.bounds(), 0, SALT);
        assertEquals(0, fell, "a sky below capacity must not rain");

        sky.set(2, 2, 260);
        fell = HumidityTransport.condense(sky, sky.bounds(), 1, SALT);
        assertTrue(fell > 0, "a sky over capacity must rain");
        assertTrue(sky.humidity(2, 2) < 260, "and must lose what it rained");
        assertEquals(fell, sky.rained(2, 2), "what fell must be what arrived on the ground");
    }

    @Test
    @DisplayName("wind carries vapour away from where it started")
    void windMovesIt() {
        // Not asserting a direction -- that is the wind's business and it varies with position. What
        // must be true is that a blob does not sit still: advection is transport, and a field that
        // only diffused would spread symmetrically and leave its centre of mass alone.
        Sky sky = new Sky(21, 21, 100_000);
        sky.set(10, 10, 5000);
        for (int step = 0; step < 30; step++) {
            HumidityTransport.advect(sky, sky.bounds(), step, SALT);
        }
        double[] centre = sky.centreOfMass();
        double moved = Math.hypot(centre[0] - 10.0, centre[1] - 10.0);
        assertTrue(moved > 0.5,
                "the blob's centre of mass moved only " + moved + " columns; wind is not carrying it");
    }

    @Test
    @DisplayName("low capacity wrings the sky out, and leaves less downwind")
    void rainShadow() {
        // §11's payoff, in miniature: a ridge is a band of columns that cannot hold much. Air crossing
        // it must rain there, and having rained, carry less onward. Neither is coded for -- both are
        // what a capacity that varies does to a wind that keeps blowing.
        //
        // The upwind edge is topped up every step, because a rain shadow is a *steady* state. The
        // first version of this released one puff of moist air and asserted rain on the ridge; the
        // puff diluted across 120 columns to an average of 33 drops, under the ridge's capacity, and
        // nothing fell. One cloud is not a climate.
        Sky ridge = new Sky(24, 5, 400);
        Sky flat = new Sky(24, 5, 400);
        for (int z = 0; z < 5; z++) {
            for (int x = 10; x <= 12; x++) {
                ridge.setCapacity(x, z, 30);
            }
        }

        for (int step = 0; step < 200; step++) {
            for (Sky sky : new Sky[]{ridge, flat}) {
                sky.topUpEdge(600);
                HumidityTransport.step(sky, sky.bounds(), step, SALT);
                assertEquals(0, sky.leak(), "vapour was created or destroyed at step " + step);
            }
        }

        int onRidge = 0;
        int onFlatSameColumns = 0;
        for (int z = 0; z < 5; z++) {
            for (int x = 10; x <= 12; x++) {
                onRidge += ridge.rained(x, z);
                onFlatSameColumns += flat.rained(x, z);
            }
        }
        System.out.println("RAINSHADOW ridge rain " + onRidge + " vs flat " + onFlatSameColumns
                + "; lee humidity " + ridge.humidityBeyond(13) + " vs " + flat.humidityBeyond(13));

        assertTrue(onRidge > onFlatSameColumns,
                "a cold ridge should wring out more rain than flat ground at the same columns: "
                        + onRidge + " against " + onFlatSameColumns);
        assertTrue(ridge.humidityBeyond(13) < flat.humidityBeyond(13),
                "the lee of the ridge held " + ridge.humidityBeyond(13)
                        + " against " + flat.humidityBeyond(13) + " with no ridge — no rain shadow");
    }

    @Test
    @DisplayName("the same salt reproduces the weather; a different one does not")
    void deterministic() {
        assertEquals(run(0x5AL), run(0x5AL));
        assertNotEquals(run(1L), run(2L));
    }

    private static String run(long seed) {
        long salt = WorldSalt.derive(seed).value();
        Sky sky = new Sky(10, 10, 200);
        sky.set(5, 5, 900);
        for (int step = 0; step < 25; step++) {
            HumidityTransport.step(sky, sky.bounds(), step, salt);
        }
        return sky.describe();
    }

    /** An array-backed sky. Outside it is ambient air, not a wall. */
    private static final class Sky implements HumidityGrid {

        private static final int BASELINE = 20;

        private final int sizeX;
        private final int sizeZ;
        private final int[] humidity;
        private final int[] capacity;
        private final int[] fallen;
        private int escaped;
        private int arrived;

        Sky(int sizeX, int sizeZ, int capacity) {
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.humidity = new int[sizeX * sizeZ];
            this.capacity = new int[sizeX * sizeZ];
            this.fallen = new int[sizeX * sizeZ];
            java.util.Arrays.fill(this.capacity, capacity);
        }

        @Override
        public boolean contains(int x, int z) {
            return x >= 0 && x < sizeX && z >= 0 && z < sizeZ;
        }

        @Override
        public int humidity(int x, int z) {
            return contains(x, z) ? humidity[index(x, z)] : BASELINE;
        }

        @Override
        public void setHumidity(int x, int z, int drops) {
            humidity[index(x, z)] = drops;
        }

        @Override
        public int capacity(int x, int z) {
            return contains(x, z) ? capacity[index(x, z)] : Integer.MAX_VALUE;
        }

        @Override
        public int baseline(int x, int z) {
            return BASELINE;
        }

        @Override
        public void rain(int x, int z, int drops) {
            fallen[index(x, z)] += drops;
        }

        @Override
        public void escape(int drops) {
            escaped += drops;
        }

        /** Hold the upwind edge at a fixed humidity, booking whatever had to be added. */
        void topUpEdge(int drops) {
            for (int z = 0; z < sizeZ; z++) {
                int index = index(0, z);
                if (humidity[index] < drops) {
                    arrived += drops - humidity[index];
                    humidity[index] = drops;
                }
            }
        }

        /** Zero when every drop is where it should be. */
        int leak() {
            return accountedFor();
        }

        void set(int x, int z, int drops) {
            humidity[index(x, z)] = drops;
        }

        void setCapacity(int x, int z, int drops) {
            capacity[index(x, z)] = drops;
        }

        int rained(int x, int z) {
            return fallen[index(x, z)];
        }

        int total() {
            int sum = 0;
            for (int drops : humidity) {
                sum += drops;
            }
            return sum;
        }

        /** Everything the sky started with, wherever it has got to since. */
        int accountedFor() {
            int sum = total() + escaped - arrived;
            for (int drops : fallen) {
                sum += drops;
            }
            return sum;
        }

        int humidityBeyond(int fromX) {
            int sum = 0;
            for (int x = fromX; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    sum += humidity[index(x, z)];
                }
            }
            return sum;
        }

        double[] centreOfMass() {
            double totalX = 0;
            double totalZ = 0;
            double mass = 0;
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    int drops = humidity[index(x, z)];
                    totalX += x * (double) drops;
                    totalZ += z * (double) drops;
                    mass += drops;
                }
            }
            return mass == 0 ? new double[]{0, 0} : new double[]{totalX / mass, totalZ / mass};
        }

        Bounds bounds() {
            return new Bounds(0, 0, sizeX - 1, sizeZ - 1);
        }

        void assertWellFormed() {
            for (int drops : humidity) {
                assertTrue(drops >= 0, "negative humidity");
            }
        }

        String describe() {
            StringBuilder out = new StringBuilder();
            for (int drops : humidity) {
                out.append(drops).append(',');
            }
            for (int drops : fallen) {
                out.append(drops).append(';');
            }
            return out.toString();
        }

        private int index(int x, int z) {
            return z * sizeX + x;
        }
    }
}
