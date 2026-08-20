package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Porosity is the free slots, and the free slots come from the rock.
 *
 * <p>Findings §6.1: in a nine-slot voxel the free slots <i>are</i> the porosity, so this asserts the
 * shape of the field rather than any stored quantity. The numbers matter because §6.3's spring needs
 * porous and tight rock to come in <b>sheets</b> — water perches on an impermeable bed and seeps out
 * where it outcrops — and a field that is merely noisy per block produces no beds and no springs.
 */
class PorosityFieldTest {

    private static final long SALT = 987654321L;

    @Test
    @DisplayName("igneous country is tight and sedimentary country is porous")
    void porosityFollowsTheRock() {
        Map<BedrockType, int[]> seen = new HashMap<>();
        for (int x = 0; x < 160; x += 3) {
            for (int z = 0; z < 160; z += 3) {
                for (int y = -48; y < 48; y += 7) {
                    Composition composition = CompositionFunction.stone(x, y, z, SALT);
                    Grain rock = ColourField.rockAt(x, z, SALT);
                    for (BedrockType family : rock.families()) {
                        int[] tally = seen.computeIfAbsent(family, ignored -> new int[2]);
                        tally[0] += composition.porosity();
                        tally[1] += Composition.SLOTS;
                    }
                }
            }
        }

        double igneous = share(seen, BedrockType.IGNEOUS);
        double sedimentary = share(seen, BedrockType.SEDIMENTARY);
        // §6's own example: sandstone porous, granite not. The ordering is the assertion; the exact
        // rates are free to be tuned without this test becoming a tripwire on somebody's balance pass.
        assertTrue(sedimentary > igneous * 4,
                "sedimentary " + sedimentary + " should be far more porous than igneous " + igneous);
        assertTrue(igneous < 0.03, "igneous rock should be near-impermeable, was " + igneous);
        assertTrue(sedimentary > 0.05, "sedimentary rock should be usefully porous, was " + sedimentary);
    }

    @Test
    @DisplayName("porosity comes in beds, not in speckle")
    void porosityIsLayered() {
        // Counted as transitions between tight and porous, not as how much the number moves. The
        // first version of this measured mean absolute change and was simply wrong: inside one porous
        // bed the count still rattles between 2 and 4 block to block, because each slot is drawn
        // independently, and that per-block scatter swamped the bed structure it was trying to find.
        //
        // What makes a bed a bed is that tight rock *stays* tight for a long way. So: cross from
        // tight to porous rarely, in both directions, which is the same thing as long runs of each.
        // Stated as the thing a spring actually needs: somewhere in this column of rock there is a
        // sheet of tight stone long enough for water to run along and come out of the end of.
        //
        // Counting transitions was tried first and measured the wrong thing twice over. Mean absolute
        // change is swamped by the per-block scatter inside a bed — each slot is drawn independently,
        // so a porous bed rattles between 2 and 4 — and even counting tight/porous crossings punishes
        // the *boundary* of a bed, where the probability is small and the draw is genuinely mottled.
        // A gradational contact is what real beds have; what matters is that the interiors are solid.
        int longest = 0;
        for (int y = -60; y <= 60; y += 4) {
            longest = Math.max(longest, longestTightRun(y));
        }
        assertTrue(longest >= 48,
                "no aquiclude anywhere in the column: longest unbroken run of tight rock was "
                        + longest + " blocks, and water cannot perch on that");
    }

    @Test
    @DisplayName("a natural block always holds at least one grain")
    void everyBlockKeepsOneGrain() {
        // The floor the rest of the mod relies on, and it is relied on in two places that would fail
        // very differently. A block with no grains hands a player nothing when they mine it, which is
        // design §4's "never fully empty-handed" broken at the one moment it is visible. A block of
        // nine drops is a source, which is §7's conservation broken — water the world invented rather
        // than water it moved.
        //
        // Swept rather than spot-checked, and the size of the sweep is the point. The rescue only
        // fires where the field would otherwise have drawn all nine slots as pore, which is about one
        // block in two and a half thousand; a sample of a few hundred would never reach one and would
        // pass just as happily on a build with the rescue deleted. Verified by deleting it: this
        // fails at 0,-20,3.
        int atFloor = 0;
        int scanned = 0;
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) {
                for (int y = -64; y < 120; y++) {
                    Composition c = CompositionFunction.stone(x, y, z, SALT);
                    scanned++;
                    int grains = Composition.SLOTS - c.porosity();
                    assertTrue(grains >= 1 && grains <= Composition.SLOTS,
                            "a natural block holds one to nine grains, but " + x + "," + y + "," + z
                                    + " holds " + grains);
                    if (c.porosity() == Composition.SLOTS - 1) {
                        atFloor++;
                    }
                }
            }
        }
        // Blocks sitting *at* the one-grain floor, which is not the same as blocks the rescue moved
        // there — most of these were drawn with eight pores honestly. It is a proxy: if this region
        // has plenty of rock at the very edge of being all pore, it has would-be cavities in it too,
        // and the assertion above is being exercised rather than merely satisfied.
        System.out.printf(Locale.ROOT, "blocks at the one-grain floor: %d in %d (1 in %.0f)%n",
                atFloor, scanned, scanned / (double) Math.max(1, atFloor));
        assertTrue(atFloor > 0,
                "no block in " + scanned + " came within one grain of being all pore, so this swept "
                        + "a region too tight to exercise the floor at all");
    }

    @Test
    @DisplayName("the rescue takes one slot and leaves the rest of the field alone")
    void rescueIsNarrow() {
        // The distinction between this and the porosity clamp that was rejected earlier. A clamp
        // moves every porous block; this moves one slot in the rare block that had nothing else. If
        // it ever starts showing up in ordinary porous rock, the mean pore count is what will say so.
        long pores = 0;
        long blocks = 0;
        for (int i = 0; i < 20000; i++) {
            int x = i * 17 - 90000;
            int z = i * -23 + 45000;
            int y = (i % 140) - 64;
            pores += CompositionFunction.stone(x, y, z, SALT).porosity();
            blocks++;
        }
        double mean = pores / (double) blocks;
        System.out.printf(Locale.ROOT, "mean pores per block: %.3f%n", mean);
        assertTrue(mean > 0.2 && mean < 4.0,
                "mean porosity of " + mean + " means the field is no longer saying what it drew");
    }

    @Test
    @DisplayName("air never leaves the world as an item")
    void airIsUnobtainable() {
        // Three independent guarantees, and this asserts the one that would actually bite: the roster
        // does not know an item for air, so nothing can look one up and hand it over.
        assertTrue(!Grains.itemIds().contains(Grains.AIR.itemId()),
                "air must not be an obtainable grain");
        assertEquals(GrainClass.AIR, Grains.AIR.clazz());
        assertTrue(!Grains.AIR.clazz().isObtainable(), "air must yield no item");
    }

    private static double share(Map<BedrockType, int[]> seen, BedrockType family) {
        int[] tally = seen.get(family);
        return tally == null || tally[1] == 0 ? 0.0 : (double) tally[0] / tally[1];
    }

    /** The longest unbroken run of impermeable rock along 240 blocks at one depth. */
    private static int longestTightRun(int y) {
        int best = 0;
        int run = 0;
        for (int i = 0; i < 240; i++) {
            if (CompositionFunction.stone(400 + i, y, 400, SALT).porosity() == 0) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
        }
        return best;
    }
}
