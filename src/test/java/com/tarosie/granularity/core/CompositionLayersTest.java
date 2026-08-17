package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The reduction from nine slots to an averaged base plus mineral overlays. */
class CompositionLayersTest {

    private static Composition build(Object... pairs) {
        int[] ids = new int[Composition.SLOTS];
        int slot = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            Grain grain = (Grain) pairs[i];
            int count = (Integer) pairs[i + 1];
            for (int n = 0; n < count; n++) {
                ids[slot++] = grain.id();
            }
        }
        return Composition.of(ids);
    }

    private static int chroma(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static int distance(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    @Test
    @DisplayName("a block of one stone renders exactly that stone's colour")
    void pureBlockKeepsItsColour() {
        for (Grain stone : Grains.ofClass(GrainClass.ROCK)) {
            CompositionLayers layers = CompositionLayers.of(build(stone, 9));
            assertEquals(stone.rockTint(), layers.baseTint(), stone.name() + " should render as itself");
            assertTrue(layers.isPlainRock());
        }
    }

    @Test
    @DisplayName("a border block averages its stones into one shade between them")
    void borderBlockAverages() {
        // Design §3 gives "shows the constituent colours as distinct rocks" to crafted cobblestone
        // and "averages into a single colour" to smelted stone. Natural stone takes the average,
        // which turns a region boundary into a gradient rather than a seam.
        CompositionLayers mixed = CompositionLayers.of(build(Grains.BASALT, 5, Grains.MARBLE, 4));
        int basalt = Grains.BASALT.rockTint();
        int marble = Grains.MARBLE.rockTint();

        assertNotEquals(basalt, mixed.baseTint());
        assertNotEquals(marble, mixed.baseTint());
        assertTrue(distance(mixed.baseTint(), basalt) < distance(basalt, marble),
                "the average must lie between its constituents");
        assertTrue(distance(mixed.baseTint(), basalt) < distance(mixed.baseTint(), marble),
                "five basalt and four marble should lean basalt");
    }

    @Test
    @DisplayName("the average moves monotonically as the mix shifts — a gradient, not a step")
    void averageSweepsSmoothly() {
        int previousDistance = Integer.MAX_VALUE;
        for (int toCount = 0; toCount <= 9; toCount++) {
            int[] ids = new int[Composition.SLOTS];
            for (int i = 0; i < Composition.SLOTS; i++) {
                ids[i] = (i < toCount ? Grains.CHALK : Grains.SHALE).id();
            }
            int tint = CompositionLayers.of(Composition.of(ids)).baseTint();
            int toDistance = distance(tint, Grains.CHALK.rockTint());
            assertTrue(toDistance <= previousDistance,
                    "each slot swapped must move the tint toward the new stone, at " + toCount);
            previousDistance = toDistance;
        }
    }

    @Test
    @DisplayName("two ore grains in one block stay two — iron and copper, not a mid-tone")
    void twoOreGrainsAreKeptSeparate() {
        // The mineral provinces are independent of the rock and of each other, so a province
        // boundary puts two grains of the same class in one block. Averaging them would turn
        // iron-and-copper into a single muddy midpoint that is neither.
        CompositionLayers layers = CompositionLayers.of(
                build(Grains.GRANITE, 5, Grains.IRON, 3, Grains.COPPER, 1));

        assertEquals(3, layers.ore().primary().count());
        assertEquals(1, layers.ore().secondary().count());
        assertEquals(Grains.IRON.mineralTint(), layers.ore().primary().tint());
        assertEquals(Grains.COPPER.mineralTint(), layers.ore().secondary().tint());
        assertEquals(4, layers.ore().total());
    }

    @Test
    @DisplayName("an equal split picks the same primary whichever slot came first")
    void tiesDoNotDependOnSlotOrder() {
        // The reduction works from a nine-entry tally in first-appearance order rather than from a
        // roster-length array scanned in id order, which is what keeps it cheap as the roster grows.
        // The tie-break has to be spelled out for that to be equivalent: with two of each, the answer
        // must be the same block whichever grain the slots happen to mention first, or a chunk would
        // render differently depending on how its own composition was laid out.
        CompositionLayers ironFirst = CompositionLayers.of(
                build(Grains.GRANITE, 5, Grains.IRON, 2, Grains.COPPER, 2));
        CompositionLayers copperFirst = CompositionLayers.of(
                build(Grains.GRANITE, 5, Grains.COPPER, 2, Grains.IRON, 2));

        assertEquals(ironFirst.ore().primary().tint(), copperFirst.ore().primary().tint());
        assertEquals(ironFirst.ore().secondary().tint(), copperFirst.ore().secondary().tint());
        assertEquals(Grains.IRON.mineralTint(), ironFirst.ore().primary().tint(),
                "the lower grain id takes primary, as the id-order scan used to give");
    }

    @Test
    @DisplayName("each mineral class gets its own overlay")
    void mineralClassesAreSeparate() {
        CompositionLayers layers = CompositionLayers.of(
                build(Grains.GNEISS, 6, Grains.ZINC, 1, Grains.GOLD, 1, Grains.LAPIS, 1));
        assertEquals(Grains.ZINC.mineralTint(), layers.ore().primary().tint());
        assertEquals(Grains.GOLD.mineralTint(), layers.precious().primary().tint());
        assertEquals(Grains.LAPIS.mineralTint(), layers.gem().primary().tint());
        assertEquals(Grains.GNEISS.rockTint(), layers.baseTint(), "rock alone sets the base");
        assertFalse(layers.isPlainRock());
    }

    @Test
    @DisplayName("one ore in nine still shows")
    void singleOreSlotIsVisible() {
        CompositionLayers layers = CompositionLayers.of(build(Grains.DIORITE, 8, Grains.DIAMOND, 1));
        assertEquals(1, layers.gem().primary().count(), "one gem slot must reach sprite 1");
        assertEquals(0, layers.gem().secondary().count());
    }

    @Test
    @DisplayName("a block with no rock still has a base to sit on")
    void allOreStillHasABase() {
        CompositionLayers layers = CompositionLayers.of(build(Grains.EMERALD, 9));
        assertEquals(Composition.SLOTS, layers.gem().primary().count());
        assertEquals(LatticeColour.rockTint(Grains.EMERALD.tint()), layers.baseTint());
    }

    @Test
    @DisplayName("out-of-range counts are rejected rather than indexing past the sprites")
    void countsAreValidated() {
        CompositionLayers.Family none = CompositionLayers.Family.NONE;
        CompositionLayers.Overlay zero = new CompositionLayers.Overlay(0xFFFFFF, 0);
        assertThrows(IllegalArgumentException.class, () -> new CompositionLayers(0,
                new CompositionLayers.Family(
                        new CompositionLayers.Overlay(0, CompositionLayers.MAX_ORE + 1), zero), none, none));
        // The smaller of a pair draws on top, so a secondary larger than its primary is a bug.
        assertThrows(IllegalArgumentException.class, () -> new CompositionLayers(0,
                new CompositionLayers.Family(new CompositionLayers.Overlay(0, 1),
                        new CompositionLayers.Overlay(0, 4)), none, none));
    }

    @Test
    @DisplayName("every derived composition maps to a sprite that exists")
    void derivedCompositionsStayInRange() {
        long salt = WorldSalt.derive(24301L).value();
        for (int i = 0; i < 3000; i++) {
            CompositionLayers layers = CompositionLayers.of(
                    CompositionFunction.stone(i * 31 - 4000, (i % 130) - 64, i * -17 + 2500, salt));
            for (CompositionLayers.Family fam : new CompositionLayers.Family[] {
                    layers.ore(), layers.precious(), layers.gem()}) {
                assertTrue(fam.primary().count() >= 0 && fam.primary().count() <= CompositionLayers.MAX_ORE);
                assertTrue(fam.secondary().count() <= fam.primary().count());
                assertTrue(fam.total() <= Composition.SLOTS);
            }
            assertTrue(layers.baseTint() >= 0 && layers.baseTint() <= 0xFFFFFF);
        }
    }

    @Test
    @DisplayName("minerals render more saturated than the stone around them")
    void mineralsReadOutOfTheRock() {
        for (Grain grain : Grains.all()) {
            if (!grain.clazz().isMineral() || grain.clazz() == GrainClass.ROCK) {
                continue;
            }
            assertTrue(chroma(grain.mineralTint()) >= chroma(grain.rockTint()),
                    grain.name() + " should read out of the rock");
        }
    }
}
