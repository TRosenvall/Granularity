package com.tarosie.granularity.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Overlays in one family are stages of one thing, and a face shows one stage.
 *
 * <p>Grass is the case: rooted, then partial, then full. Two stages on one face is nonsense, and the
 * exclusivity has to live on the overlay rather than in whatever applies it, so that it holds however
 * the overlay arrives — by hand, by bonemeal, or by a spreader written later.
 */
class CoatingFamilyTest {

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("granularity", path);
    }

    private static final ResourceLocation GRASS = id("grass");

    private static final Overlay ROOTED = new Overlay(id("block/rooted"), GRASS);

    private static final Overlay PARTIAL = new Overlay(id("block/partial"), GRASS);

    private static final Overlay FULL = new Overlay(id("block/full"), GRASS);

    private static final Overlay MOSS = new Overlay(id("block/moss"));

    private static final Overlay SLIME = new Overlay(id("block/slime"));

    @Test
    @DisplayName("a later stage replaces an earlier one on the same face")
    void oneStagePerFace() {
        Coating grown = Coating.NONE.with(ROOTED, Direction.UP).with(PARTIAL, Direction.UP);
        assertEquals(0, grown.facesOf(ROOTED), "rooted gave way to partial");
        assertTrue(Coating.covers(grown.facesOf(PARTIAL), Direction.UP), "partial took the face");

        Coating finished = grown.with(FULL, Direction.UP);
        assertEquals(0, finished.facesOf(PARTIAL), "partial gave way to full");
        assertTrue(Coating.covers(finished.facesOf(FULL), Direction.UP), "full took the face");
    }

    @Test
    @DisplayName("stages on different faces do not disturb each other")
    void stagesAreEvictedPerFaceNotPerBlock() {
        Coating coating = Coating.NONE
                .with(PARTIAL, Direction.NORTH)
                .with(FULL, Direction.UP);
        assertTrue(Coating.covers(coating.facesOf(PARTIAL), Direction.NORTH),
                "the north face is still part-grown; only the top advanced");
        assertTrue(Coating.covers(coating.facesOf(FULL), Direction.UP));
    }

    @Test
    @DisplayName("an overlay with no family stacks with anything")
    void familylessOverlaysStack() {
        Coating coating = Coating.NONE
                .with(MOSS, Direction.UP)
                .with(SLIME, Direction.UP)
                .with(FULL, Direction.UP);
        assertTrue(Coating.covers(coating.facesOf(MOSS), Direction.UP), "moss is not a grass stage");
        assertTrue(Coating.covers(coating.facesOf(SLIME), Direction.UP), "nor is slime");
        assertTrue(Coating.covers(coating.facesOf(FULL), Direction.UP));
    }

    @Test
    @DisplayName("applying the stage already there reports that nothing happened")
    void reapplyingTheSameStageChangesNothing() {
        Coating coating = Coating.NONE.with(FULL, Direction.UP);
        assertNull(coating.with(FULL, Direction.UP),
                "null is how a caller knows not to consume the item");
    }

    @Test
    @DisplayName("an evicted stage still held elsewhere is kept, not dropped")
    void evictionKeepsTheOverlayWhereItSurvives() {
        Coating coating = Coating.NONE
                .with(PARTIAL, Direction.UP)
                .with(PARTIAL, Direction.NORTH)
                .with(FULL, Direction.UP);
        assertEquals(Coating.bit(Direction.NORTH), coating.facesOf(PARTIAL),
                "partial lost the top and kept the north face");
    }
}
