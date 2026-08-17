package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The nine-slot container, now holding grain ids. */
class CompositionTest {

    private static Composition of(Grain... grains) {
        int[] ids = new int[Composition.SLOTS];
        for (int i = 0; i < Composition.SLOTS; i++) {
            ids[i] = grains[Math.min(i, grains.length - 1)].id();
        }
        return Composition.of(ids);
    }

    @Test
    @DisplayName("air is grain id zero, so a default-initialised slot array is air")
    void airIsZero() {
        assertEquals(0, Grains.AIR.id());
        assertEquals(GrainClass.AIR, Grains.byId(0).clazz());
        assertEquals(GrainClass.AIR, Composition.of(new int[Composition.SLOTS]).majorityClass());
        assertEquals(Composition.SLOTS, Composition.of(new int[Composition.SLOTS]).porosity());
    }

    @Test
    @DisplayName("a composition is exactly nine slots of registered grains")
    void nineSlotsOfRegisteredGrains() {
        assertEquals(9, Composition.SLOTS);
        assertThrows(IllegalArgumentException.class, () -> Composition.of(new int[8]));
        assertThrows(IllegalArgumentException.class, () -> Composition.of(new int[10]));
        int[] bad = new int[Composition.SLOTS];
        bad[8] = Grains.count();
        assertThrows(IllegalArgumentException.class, () -> Composition.of(bad));
    }

    @Test
    @DisplayName("class counts always sum to nine")
    void countsAreExhaustive() {
        int[] ids = new int[Composition.SLOTS];
        ids[0] = Grains.GRANITE.id();
        ids[1] = Grains.GRANITE.id();
        ids[2] = Grains.IRON.id();
        ids[3] = Grains.GOLD.id();
        ids[4] = Grains.DIAMOND.id();
        ids[5] = Grains.QUARTZ_SAND.id();
        ids[6] = Grains.LOESS.id();
        ids[7] = Grains.KAOLIN.id();
        ids[8] = Grains.WATER.id();
        Composition c = Composition.of(ids);

        int total = 0;
        for (GrainClass clazz : GrainClass.values()) {
            total += c.count(clazz);
        }
        assertEquals(Composition.SLOTS, total);
        assertEquals(2, c.count(GrainClass.ROCK));
        assertEquals(1, c.count(GrainClass.WATER));
        assertEquals(2, c.countGrain(Grains.GRANITE));
        assertEquals(GrainClass.ROCK, c.majorityClass());
        assertEquals(8, c.distinctGrains());
        assertEquals(1, c.distinctGrains(GrainClass.ROCK));
    }

    @Test
    @DisplayName("porosity is the count of air slots")
    void porosityIsAirSlots() {
        int[] ids = new int[Composition.SLOTS];
        for (int i = 0; i < 6; i++) {
            ids[i] = Grains.ANDESITE.id();
        }
        // Six solid, three air -- findings §6.1's porous rock, holding up to three drops.
        assertEquals(3, Composition.of(ids).porosity());
    }

    @Test
    @DisplayName("grain counts index by id and sum to nine")
    void grainCountsAreComplete() {
        Composition c = of(Grains.SLATE, Grains.SLATE, Grains.LAPIS);
        int[] counts = c.grainCounts();
        assertEquals(Grains.count(), counts.length);
        int total = 0;
        for (int n : counts) {
            total += n;
        }
        assertEquals(Composition.SLOTS, total);
        assertEquals(2, counts[Grains.SLATE.id()]);
        assertEquals(7, counts[Grains.LAPIS.id()]);
    }

    @Test
    @DisplayName("equality is by contents, and the backing array is not shared")
    void valueSemantics() {
        int[] ids = new int[Composition.SLOTS];
        Composition a = Composition.of(ids);
        ids[0] = Grains.EMERALD.id();
        assertEquals(a, Composition.of(new int[Composition.SLOTS]));
        assertNotEquals(a, Composition.of(ids));

        int[] exported = a.toArray();
        exported[0] = 42;
        assertEquals(Grains.AIR.id(), a.grainId(0), "toArray must not alias the internals");
    }

    @Test
    @DisplayName("majority ties break deterministically rather than by iteration order")
    void majorityTiesAreDeterministic() {
        int[] ids = new int[Composition.SLOTS];
        for (int i = 0; i < 4; i++) {
            ids[i] = Grains.QUARTZ_SAND.id();
        }
        for (int i = 4; i < 8; i++) {
            ids[i] = Grains.WATER.id();
        }
        ids[8] = Grains.KAOLIN.id();
        Composition c = Composition.of(ids);
        for (int i = 0; i < 50; i++) {
            assertEquals(GrainClass.SAND, c.majorityClass());
        }
    }

    @Test
    @DisplayName("class predicates match the design's groupings")
    void classPredicates() {
        assertTrue(GrainClass.ROCK.isMineral());
        assertTrue(GrainClass.SILT.isSoil());
        assertTrue(GrainClass.SAND.isColoured());
        // Air and water occupy slots but never become items -- why a break can yield under nine.
        for (GrainClass c : GrainClass.values()) {
            assertEquals(c != GrainClass.AIR && c != GrainClass.WATER, c.isObtainable(),
                    "obtainability should follow from air/water alone: " + c);
        }
    }
}
