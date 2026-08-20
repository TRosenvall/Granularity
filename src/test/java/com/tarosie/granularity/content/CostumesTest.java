package com.tarosie.granularity.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A block wearing another block's look, part by part.
 *
 * <p>Only the parts that need no item registry, which is a real limit rather than a choice: this suite
 * runs without FML's mod list, so {@code Bootstrap.bootStrap()} throws and even naming
 * {@code ItemStack.EMPTY} fails. Anything touching a stack — that dressing a bare block no longer
 * throws on an empty {@code EnumMap}, that an empty stack is stored as no costume — is verified by
 * running the game and not here, and that is worth knowing when reading this file for coverage.
 *
 * <p>What is left is still where the sharp edges are: which tints a region owns, and how a synthesised
 * tint index is packed and unpacked. The second of those was wrong when this file was written.
 */
class CostumesTest {

    @Test
    @DisplayName("a region covers its own tint indices and nobody else's")
    void regionsDoNotOverlap() {
        assertTrue(Region.STONE.covers(0));
        assertTrue(Region.STONE.covers(9));
        assertTrue(Region.UPPER_STONE.covers(10));
        assertTrue(Region.TIMBER.covers(20));
        assertTrue(Region.METAL.covers(21));
        assertTrue(Region.FACE.covers(22));

        // The stone ranges must not meet, or a double slab's two halves would colour as one.
        assertTrue(!Region.STONE.covers(10));
        assertTrue(!Region.UPPER_STONE.covers(20));
        assertTrue(!Region.TIMBER.covers(21));
    }

    @Test
    @DisplayName("a synthesised tint says both which part it is and which layer")
    void costumeTintsRoundTrip() {
        // A part that is not stone is redrawn as stone, and two such parts may be wearing two
        // different stones. The tint index is the only thing carrying that apart, so it has to survive
        // the round trip exactly or one part is coloured with the other's rock.
        for (Region region : Region.values()) {
            for (int layer = 0; layer <= 9; layer++) {
                int tint = region.costumeTint(layer);
                assertSame(region, Region.ofCostumeTint(tint),
                        "region lost for " + region + " layer " + layer);
                assertEquals(layer, Region.costumeLayer(tint),
                        "layer lost for " + region + " layer " + layer);
            }
        }
    }

    @Test
    @DisplayName("ordinary tint indices are not mistaken for synthesised ones")
    void ordinaryTintsAreNotCostumes() {
        // Everything a model actually bakes sits far below COSTUME_BASE. If these ever collided, a
        // plain block's own stone would be coloured from a costume that was never put on it.
        for (int tint = 0; tint < Region.COSTUME_BASE; tint++) {
            assertNull(Region.ofCostumeTint(tint), "tint " + tint + " read as a costume");
        }
    }

    @Test
    @DisplayName("an unknown region name reads as the whole block rather than failing")
    void unknownRegionFallsBack() {
        // Region ids reach disk. One written by a later version, or by a mod adding its own part,
        // must not stop the block entity from loading.
        assertSame(Region.ALL, Region.byId("no_such_region"));
        for (Region region : Region.values()) {
            assertSame(region, Region.byId(region.id()));
        }
    }
}
