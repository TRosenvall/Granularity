package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of crafted blocks: naming, averaging, and which smelt outcome a composition takes.
 *
 * <p>All pure, so none of it needs a furnace or a crafting table to test.
 */
class SmeltAverageTest {

    private static Composition of(Object... pairs) {
        int[] ids = new int[Composition.SLOTS];
        int slot = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            for (int n = 0; n < (Integer) pairs[i + 1]; n++) {
                ids[slot++] = ((Grain) pairs[i]).id();
            }
        }
        return Composition.of(ids);
    }

    @Test
    @DisplayName("a single-grain block names itself; a mixture does not")
    void namingFollowsComposition() {
        assertEquals(Grains.GRANITE, of(Grains.GRANITE, 9).soleGrain());
        assertEquals(Grains.IRON, of(Grains.IRON, 9).soleGrain());
        assertNull(of(Grains.CHALK, 5, Grains.SHALE, 4).soleGrain(), "a mixture earns no name");
        assertNull(of(Grains.GRANITE, 8, Grains.DIAMOND, 1).soleGrain(),
                "one diamond in eight granite is still a mixture");
    }

    @Test
    @DisplayName("nearness is not identity: a chalk/shale mix keeps its grains, whatever it resembles")
    void averagingDoesNotRenameTheBlock() {
        // Five chalk and four shale average to almost exactly diorite. An earlier version snapped
        // to the nearest named stone and produced "nine diorite" -- a block claiming to be
        // something it was not made of. The grains must survive untouched.
        Composition mixed = of(Grains.CHALK, 5, Grains.SHALE, 4);

        assertEquals(5, mixed.countGrain(Grains.CHALK));
        assertEquals(4, mixed.countGrain(Grains.SHALE));
        assertEquals(0, mixed.countGrain(Grains.DIORITE), "smelting must not invent diorite");
        assertNull(mixed.soleGrain(), "and it stays generically named");

        int mean = mixed.averageTint(GrainClass.ROCK);
        assertTrue(mean >= 0);
        // The average lies between its constituents even though no grain has become the other.
        for (int shift : new int[] {16, 8, 0}) {
            int chalk = (Grains.CHALK.tint() >> shift) & 0xFF;
            int shale = (Grains.SHALE.tint() >> shift) & 0xFF;
            int got = (mean >> shift) & 0xFF;
            assertTrue(got <= Math.max(chalk, shale) && got >= Math.min(chalk, shale));
        }
    }

    @Test
    @DisplayName("a pure block averages to exactly its own colour")
    void pureAveragesToItself() {
        for (Grain stone : Grains.ofClass(GrainClass.ROCK)) {
            assertEquals(stone.tint(), of(stone, 9).averageTint(GrainClass.ROCK));
        }
    }

    @Test
    @DisplayName("the three smelt outcomes follow from what the block holds")
    void smeltOutcomeFollowsComposition() {
        // All rock -> smooth stone.
        Composition rockOnly = of(Grains.GRANITE, 5, Grains.BASALT, 4);
        assertFalse(rockOnly.isAllMineral());
        assertFalse(rockOnly.hasMineralInclusion());

        // Rock with a mineral in it -> the ore block you originally mined. This is the round trip:
        // mine an ore block, get eight rock and one iron, recombine, smelt, and it is back.
        Composition ore = of(Grains.GRANITE, 8, Grains.IRON, 1);
        assertFalse(ore.isAllMineral());
        assertTrue(ore.hasMineralInclusion());

        // All mineral, no rock -> alloy.
        Composition alloy = of(Grains.IRON, 5, Grains.COPPER, 4);
        assertTrue(alloy.isAllMineral());
        assertTrue(alloy.hasMineralInclusion());
    }

    @Test
    @DisplayName("gems count as an inclusion, not as rock")
    void gemsAreInclusions() {
        Composition withGem = of(Grains.MARBLE, 8, Grains.DIAMOND, 1);
        assertTrue(withGem.hasMineralInclusion());
        assertFalse(withGem.isAllMineral());
    }

    @Test
    @DisplayName("averaging one class ignores the others")
    void averagingIsPerClass() {
        Composition mixed = of(Grains.SLATE, 7, Grains.GOLD, 2);
        assertEquals(Grains.SLATE.tint(), mixed.averageTint(GrainClass.ROCK), "rock alone");
        assertEquals(Grains.GOLD.tint(), mixed.averageTint(GrainClass.PRECIOUS_ORE), "precious alone");
        assertEquals(-1, mixed.averageTint(GrainClass.GEM), "no gems to average");
    }

    @Test
    @DisplayName("every grain is backed by an item, so a mod adds one by naming its item")
    void everyGrainHasAnItem() {
        for (Grain grain : Grains.all()) {
            assertNotNull(grain.itemId(), grain.name() + " needs an item");
            assertTrue(grain.itemId().contains(":"), grain.name() + " item must be namespaced");
        }
        // The ones vanilla already has are vanilla's, not near-duplicates of our own.
        assertEquals("minecraft:raw_iron", Grains.IRON.itemId());
        assertEquals("minecraft:diamond", Grains.DIAMOND.itemId());
        assertEquals("minecraft:coal", Grains.COAL.itemId());
        // Rocks have no vanilla chunk item, so those are ours.
        assertTrue(Grains.GRANITE.itemId().startsWith("granularity:"));
    }
}
