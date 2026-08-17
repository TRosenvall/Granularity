package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Adding a grain must claim its own share of the world and disturb nothing else.
 *
 * <p>This is the property that makes the roster safe to open to other mods. The obvious
 * implementation — {@code candidates.get(hash % candidates.size())} — does not have it: every
 * remainder shifts when the count changes, so adding one rock to a family of five moved 83% of that
 * family's regions, and adding two rocks in an afternoon moved 72% of the world. Installing a mod
 * would have rewritten the geology of a save that mod had never touched.
 *
 * <p>{@link Grains#pick} uses rendezvous hashing instead, and these tests pin the two consequences:
 * a newcomer wins only about {@code 1/n} of regions, and <b>every region it does not win is
 * completely unchanged</b>. The second is the one that matters; the first is only the price.
 */
class GrainPickStabilityTest {

    private static final int SAMPLES = 20_000;

    /** Real grains, so their ids index the hash table {@link Grains#pick} reads. */
    private static List<Grain> igneousRocks() {
        return Grains.admitted(BedrockType.IGNEOUS, GrainClass.ROCK);
    }

    private static long region(int i) {
        // Stands in for a Worley cell id; any well-spread longs will do.
        return Rng.mix64(i * 0x9E3779B97F4A7C15L);
    }

    @Test
    @DisplayName("a newcomer takes only its own share and leaves every other region untouched")
    void addingAGrainDisturbsOnlyItsOwnShare() {
        List<Grain> all = igneousRocks();
        assertTrue(all.size() >= 3, "need a few rocks to say anything");
        List<Grain> before = new ArrayList<>(all.subList(0, all.size() - 1));
        Grain newcomer = all.get(all.size() - 1);

        int changed = 0;
        for (int i = 0; i < SAMPLES; i++) {
            long region = region(i);
            Grain was = Grains.pick(before, region);
            Grain now = Grains.pick(all, region);
            if (was == now) {
                continue;
            }
            changed++;
            // The only permitted change is the newcomer winning. Anything else means an existing
            // grain moved because the list got longer, which is exactly the bug.
            assertEquals(newcomer, now,
                    "a region changed to something other than the grain that was added");
        }

        double share = changed / (double) SAMPLES;
        double expected = 1.0 / all.size();
        assertTrue(Math.abs(share - expected) < 0.05,
                "the newcomer should win about 1/" + all.size() + " of regions, took " + share);
    }

    @Test
    @DisplayName("removing a grain hands its regions back and moves nobody else")
    void removingAGrainIsTheExactInverse() {
        List<Grain> all = igneousRocks();
        List<Grain> without = new ArrayList<>(all.subList(0, all.size() - 1));
        Grain removed = all.get(all.size() - 1);

        for (int i = 0; i < SAMPLES; i++) {
            long region = region(i);
            Grain full = Grains.pick(all, region);
            if (full != removed) {
                assertEquals(full, Grains.pick(without, region),
                        "a region not held by the removed grain must not move when it goes");
            }
        }
    }

    @Test
    @DisplayName("the answer depends on the name, not on the order the list happens to be in")
    void orderDoesNotMatter() {
        List<Grain> all = igneousRocks();
        List<Grain> shuffled = new ArrayList<>(all);
        java.util.Collections.reverse(shuffled);
        for (int i = 0; i < SAMPLES; i++) {
            long region = region(i);
            assertEquals(Grains.pick(all, region), Grains.pick(shuffled, region),
                    "load order must not decide which stone a region is");
        }
    }

    @Test
    @DisplayName("for comparison: a modulo pick would have moved most of the world")
    void moduloWouldHaveMovedMostRegions() {
        List<Grain> all = igneousRocks();
        List<Grain> before = all.subList(0, all.size() - 1);
        int changed = 0;
        for (int i = 0; i < SAMPLES; i++) {
            long h = Rng.mix64(region(i));
            Grain was = before.get((int) Math.floorMod(h, (long) before.size()));
            Grain now = all.get((int) Math.floorMod(h, (long) all.size()));
            if (was != now) {
                changed++;
            }
        }
        // Not a requirement — a record of why the algorithm changed, so the reason survives in the
        // suite rather than only in a commit message.
        assertTrue(changed / (double) SAMPLES > 0.5,
                "modulo should be shown moving most regions; it moved " + changed);
    }
}
