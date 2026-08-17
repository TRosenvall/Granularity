package com.tarosie.granularity.content;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A saw is jammed from the front or the back; a furnace door only from the front.
 *
 * <p>{@link Fouling} started as one rule — moss over the face a machine points at stops it — and the
 * stonecutter is the first block that does not fit it. Its working part is a disc standing up out of
 * the bench, and moss packed against <i>either</i> side jams it just as well. Getting that wrong is
 * not a crash: it is a blade you can still use from behind, or a bench you cannot use because moss
 * grew on an unrelated side, and both are the sort of thing that survives a long time unnoticed.
 *
 * <p>Tested through {@link Fouling#coversEitherSide}, the geometry on its own, because the full check
 * looks moss up in a registry and a unit test has no game to register one in.
 */
class FoulingTest {

    private static final Overlay MOSS =
            new Overlay(ResourceLocation.fromNamespaceAndPath("granularity", "block/moss"));

    private static int mossedOn(Direction... faces) {
        Coating coating = Coating.NONE;
        for (Direction face : faces) {
            coating = coating.with(MOSS, face);
        }
        return coating.facesOf(MOSS);
    }

    @Test
    @DisplayName("moss on the face the blade points at jams it")
    void theFrontJams() {
        assertTrue(Fouling.coversEitherSide(mossedOn(Direction.NORTH), Direction.NORTH));
    }

    @Test
    @DisplayName("moss on the back jams it too — that is the whole difference from a door")
    void theBackJamsAsWell() {
        assertTrue(Fouling.coversEitherSide(mossedOn(Direction.SOUTH), Direction.NORTH),
                "a saw is a disc, not a door: moss behind the blade is still against the blade");
    }

    @Test
    @DisplayName("moss on the bench is untidy, not jamming")
    void theSidesDoNot() {
        for (Direction face : new Direction[] {
                Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN}) {
            assertFalse(Fouling.coversEitherSide(mossedOn(face), Direction.NORTH),
                    face + " is the bench, not the blade — a mossy tabletop still cuts");
        }
    }

    @Test
    @DisplayName("a clean blade is not jammed, however mossy the rest of the block is")
    void everythingElseMossyStillCuts() {
        int allButTheBlade = mossedOn(Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN);
        assertFalse(Fouling.coversEitherSide(allButTheBlade, Direction.NORTH));
        assertFalse(Fouling.coversEitherSide(0, Direction.NORTH), "no moss at all");
    }

    @Test
    @DisplayName("the rule turns with the block, so every facing behaves alike")
    void everyFacingIsTheSame() {
        // The blade stands square across the block's facing and the blockstate's y rotation carries
        // both its faces round together, so nothing here may depend on which way it was placed.
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            assertTrue(Fouling.coversEitherSide(mossedOn(facing), facing), facing + " front");
            assertTrue(Fouling.coversEitherSide(mossedOn(facing.getOpposite()), facing),
                    facing + " back");
            assertFalse(Fouling.coversEitherSide(mossedOn(facing.getClockWise()), facing),
                    facing + " bench side");
        }
    }
}
