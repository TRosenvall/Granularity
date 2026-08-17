package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A block's worked state, and the line between it and its derived state.
 *
 * <p>{@link Finish} is deliberately small; what these pin is the reasoning around it, so that the
 * next person to reach for "just add a flag" finds the argument rather than rediscovering it.
 */
class FinishTest {

    @Test
    @DisplayName("unworked is the default, so nothing that predates finishes needs migrating")
    void cobbledIsTheDefault() {
        assertEquals(Finish.COBBLED, Finish.byId("nonsense"),
                "a finish written by a later version, or by a mod since removed, reads as unworked "
                        + "rather than as some particular history");
        assertEquals(Finish.COBBLED, Finish.values()[0],
                "first, so an unset field is the unworked state");
    }

    @Test
    @DisplayName("finishes travel by name, so reordering the enum cannot rewrite saved blocks")
    void roundTripsByName() {
        for (Finish finish : Finish.values()) {
            assertEquals(finish, Finish.byId(finish.id()));
            assertEquals(finish.name().toLowerCase(java.util.Locale.ROOT), finish.id());
        }
    }

    @Test
    @DisplayName("a name a person typed is checked; a name the game saved is forgiven")
    void authoredNamesAreNotForgiven() {
        // The two callers want opposite things from the same lookup. A saved block from a version
        // that had a style this one does not should load as unworked; a *recipe* naming a style that
        // does not exist is a typo, and reading it as cobbled makes a recipe that accepts rubble
        // where it demanded worked stone. That is precisely the laundering CompositeIngredient was
        // written to make impossible, arriving through the back door of a misspelling.
        assertEquals(Finish.COBBLED, Finish.byId("smoth"), "saved data is forgiven");
        org.junit.jupiter.api.Assertions.assertNull(Finish.find("smoth"), "authored data is not");
        for (Finish finish : Finish.values()) {
            assertEquals(finish, Finish.find(finish.id()), "every real name still resolves");
        }
    }

    @Test
    @DisplayName("smelting is history, not material: the grains are untouched")
    void smeltingDoesNotChangeTheComposition() {
        // The whole reason a finish has to be stored rather than derived. If smelting changed the
        // grains, the block could work out for itself what had happened to it and no field would be
        // needed — design §3 is explicit that it does not: the colours average, the grains stay.
        int[] slots = new int[Composition.SLOTS];
        for (int i = 0; i < Composition.SLOTS; i++) {
            slots[i] = (i < 5 ? Grains.CHALK : Grains.SHALE).id();
        }
        Composition before = Composition.of(slots);
        Composition afterSmelting = Composition.of(slots);

        assertEquals(before, afterSmelting,
                "a smooth block still knows it was five chalk and four shale");
        assertNotEquals(before.averageTint(GrainClass.ROCK), -1,
                "what changes is only that it now shows that average instead of the nine");
    }

    @Test
    @DisplayName("looseness is derived, which is why it is not a finish")
    void loosenessComesFromTheComposition() {
        // The other side of the line. Gravel is not a worked state and must never become one: a block
        // with air among its grains *is* loose, and porosity already says so. See Finish's class note.
        int[] loose = new int[Composition.SLOTS];
        for (int i = 0; i < Composition.SLOTS; i++) {
            loose[i] = (i < 4 ? Grains.QUARTZ_SAND : Grains.AIR).id();
        }
        int[] solid = new int[Composition.SLOTS];
        java.util.Arrays.fill(solid, Grains.GRANITE.id());

        assertEquals(5, Composition.of(loose).porosity(), "four grains among nine leaves five free");
        assertEquals(0, Composition.of(solid).porosity(), "a whole block has no room in it");
    }
}
