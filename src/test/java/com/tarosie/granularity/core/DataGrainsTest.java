package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Grains that arrive from data rather than from code.
 *
 * <p>The roster is shared static state and these tests mutate it, so each one hands back an empty
 * batch afterwards. That is not merely tidying: {@link Grains#applyDataGrains} taking the whole set
 * rather than a delta is what makes it possible, and the fact that clearing genuinely undoes a batch
 * is itself part of what is being tested.
 *
 * <p>The property underneath all of it is that <b>an id is permanent</b>. A {@link Composition} in a
 * chest or in a loaded chunk holds ids, not names, so an id that came to mean a different material
 * would rewrite the stone inside blocks that already exist, with nothing to show it had happened.
 */
class DataGrainsTest {

    private static final String RUBY = "testmod:ruby";
    private static final String JET = "testmod:jet";

    private static GrainSpec ruby() {
        return new GrainSpec(RUBY, GrainClass.GEM, 0x9B111E, "minecraft:redstone",
                BedrockType.METAMORPHIC);
    }

    private static GrainSpec jet() {
        return new GrainSpec(JET, GrainClass.GEM, 0x1B1B1B, "minecraft:black_dye",
                BedrockType.SEDIMENTARY);
    }

    /**
     * Not {@code applyDataGrains(List.of())} — that retires the grains but keeps their ids allocated
     * forever, which is exactly right in a running game and would make this suite order-dependent:
     * the roster would stay longer than it started and whichever test counted grains next would fail.
     */
    @AfterEach
    void clearDataGrains() {
        Grains.forgetDataGrains();
    }

    @Test
    @DisplayName("a data grain joins the roster and is offered where its family admits it")
    void aDataGrainIsAdmitted() {
        assertTrue(Grains.applyDataGrains(List.of(ruby())).isEmpty(), "nothing to complain about");

        Grain grain = Grains.find(RUBY);
        assertNotNull(grain);
        assertEquals(GrainClass.GEM, grain.clazz());
        assertEquals(0x9B111E, grain.tint());
        assertTrue(Grains.admitted(BedrockType.METAMORPHIC, GrainClass.GEM).contains(grain),
                "declaring the family is the whole contract; worldgen reads this list");
        assertFalse(Grains.admitted(BedrockType.IGNEOUS, GrainClass.GEM).contains(grain));
        assertEquals(grain, Grains.byItem("minecraft:redstone"));
    }

    @Test
    @DisplayName("withdrawing a definition retires the grain but never reissues its id")
    void aRetiredGrainKeepsItsId() {
        Grains.applyDataGrains(List.of(ruby()));
        int rubyId = Grains.byName(RUBY).id();

        // The pack is removed and a different one is loaded in its place.
        Grains.applyDataGrains(List.of(jet()));

        assertNull(Grains.find(RUBY), "a retired grain is no longer offered by name");
        assertFalse(Grains.all().contains(Grains.byId(rubyId)));
        assertFalse(Grains.admitted(BedrockType.METAMORPHIC, GrainClass.GEM)
                .contains(Grains.byId(rubyId)), "and never generates again");
        assertNull(Grains.byItem("minecraft:redstone"), "it lets go of its item");

        // The point of all of it: a block already made of ruby still knows what it is made of.
        assertEquals(RUBY, Grains.byId(rubyId).name());
        assertTrue(Grains.byName(JET).id() != rubyId, "the id it vacated was not handed on");
    }

    @Test
    @DisplayName("a grain that comes back comes back as the same id")
    void revivalIsStable() {
        Grains.applyDataGrains(List.of(ruby()));
        int before = Grains.byName(RUBY).id();
        Grains.applyDataGrains(List.of());
        Grains.applyDataGrains(List.of(ruby()));

        assertEquals(before, Grains.byName(RUBY).id(),
                "toggling a pack off and on must not renumber what is already placed");
    }

    @Test
    @DisplayName("ids follow the sorted names, so two installs of the same packs agree")
    void orderIsDeterministic() {
        // Names this class uses nowhere else, because sorting only decides the order of grains being
        // seen for the first time — a name that already has an id keeps it, which is the more
        // important guarantee and the one revivalIsStable covers.
        GrainSpec later = new GrainSpec("testmod:zircon", GrainClass.GEM, 0xC8D0D8,
                "minecraft:amethyst_shard", BedrockType.IGNEOUS);
        GrainSpec earlier = new GrainSpec("testmod:agate", GrainClass.GEM, 0xC08A5A,
                "minecraft:brick", BedrockType.SEDIMENTARY);

        Grains.applyDataGrains(List.of(later, earlier));

        assertTrue(Grains.byName("testmod:agate").id() < Grains.byName("testmod:zircon").id(),
                "agate sorts before zircon, whatever order the loader happened to find them in");
    }

    @Test
    @DisplayName("applying the same set twice rebuilds nothing")
    void anIdenticalBatchIsANoOp() {
        // Both sides mirror the synced registry — the integrated server at world load and the client
        // at login — so in single player this happens every time. The second pass must not rebuild
        // the tables that chunk meshing and worldgen are reading; identity of the returned list is
        // the observable form of "nothing was rebuilt".
        Grains.applyDataGrains(List.of(ruby()));
        List<Grain> gems = Grains.admitted(BedrockType.METAMORPHIC, GrainClass.GEM);

        assertTrue(Grains.applyDataGrains(List.of(ruby())).isEmpty());

        assertSame(gems, Grains.admitted(BedrockType.METAMORPHIC, GrainClass.GEM),
                "an unchanged batch should leave the derived tables exactly where they were");
    }

    @Test
    @DisplayName("code grains keep their ids no matter what data does")
    void codeGrainsAreUndisturbed() {
        int granite = Grains.GRANITE.id();
        int calcite = Grains.CALCITE.id();
        Grains.applyDataGrains(List.of(ruby(), jet()));

        assertEquals(granite, Grains.GRANITE.id());
        assertEquals(calcite, Grains.CALCITE.id());
        assertTrue(Grains.byName(RUBY).id() > calcite, "data grains land after every code grain");
    }

    @Test
    @DisplayName("data may not redefine a grain the code registered")
    void codeGrainsCannotBeOverridden() {
        List<String> problems = Grains.applyDataGrains(List.of(
                new GrainSpec("granularity:granite", GrainClass.ROCK, 0x000000,
                        "granularity:granite_chunk", BedrockType.IGNEOUS)));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("granularity:granite"), problems.get(0));
        assertEquals(0x9C6B5A, Grains.GRANITE.tint(), "granite is untouched");
    }

    @Test
    @DisplayName("two grains cannot claim the same item, and saying so beats crashing")
    void itemClashesAreReported() {
        List<String> problems = Grains.applyDataGrains(List.of(
                new GrainSpec("testmod:fool_gold", GrainClass.ORE, 0xF2C846, "minecraft:raw_gold",
                        BedrockType.IGNEOUS)));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("granularity:gold"),
                "the message should name who already has it, got: " + problems.get(0));
        assertNull(Grains.find("testmod:fool_gold"));
        assertEquals(Grains.GOLD, Grains.byItem("minecraft:raw_gold"));
    }

    @Test
    @DisplayName("one bad definition is reported and the rest of the pack still loads")
    void oneBadDefinitionDoesNotSinkTheBatch() {
        List<String> problems = Grains.applyDataGrains(List.of(
                ruby(),
                new GrainSpec("no_namespace", GrainClass.GEM, 0x112233, "minecraft:diamond")));

        assertEquals(1, problems.size(), "exactly one refusal: " + problems);
        assertNotNull(Grains.find(RUBY), "a pack must not lose its good grains to one bad one");
    }

    @Test
    @DisplayName("a data grain claims its own share of the world and moves nobody else's stone")
    void addingADataGrainDisturbsOnlyItsOwnShare() {
        // The same guarantee GrainPickStabilityTest pins for code grains, now through the data path —
        // this is what makes a pack safe to add to a world that is already generated.
        List<Grain> before = Grains.admitted(BedrockType.SEDIMENTARY, GrainClass.GEM);
        assertTrue(before.isEmpty(), "sedimentary country holds no gems until something adds one");

        Grains.applyDataGrains(List.of(jet()));
        List<Grain> rocksBefore = Grains.admitted(BedrockType.IGNEOUS, GrainClass.ROCK);

        int moved = 0;
        for (int i = 0; i < 5_000; i++) {
            long region = Rng.mix64(i * 0x9E3779B97F4A7C15L);
            if (Grains.pick(rocksBefore, region) != Grains.pick(rocksBefore, region)) {
                moved++;
            }
        }
        assertEquals(0, moved, "a gem arriving must not touch which rock a region is");
        assertEquals(jet().name(),
                Grains.pick(Grains.admitted(BedrockType.SEDIMENTARY, GrainClass.GEM), 1234L).name(),
                "and the newcomer is the only candidate where it is the only gem");
    }
}
