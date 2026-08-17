package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The named roster, and the geology that decides where each material occurs. */
class GrainsTest {

    @Test
    @DisplayName("the roster: what exists, and where")
    void rosterReadsOut() {
        System.out.println("  registered materials: " + Grains.count());
        for (BedrockType family : BedrockType.values()) {
            System.out.println("  " + family + ":");
            for (GrainClass clazz : new GrainClass[] {
                    GrainClass.ROCK, GrainClass.ORE, GrainClass.PRECIOUS_ORE, GrainClass.GEM}) {
                List<Grain> admitted = Grains.admitted(family, clazz);
                StringBuilder names = new StringBuilder();
                for (Grain def : admitted) {
                    names.append(names.length() == 0 ? "" : ", ").append(def.name());
                }
                System.out.printf(Locale.ROOT, "    %-14s %s%n", clazz,
                        names.length() == 0 ? "(none)" : names);
            }
        }
    }

    @Test
    @DisplayName("names are unique and every grain answers to its own id and name")
    void namesAndIds() {
        Set<String> names = new HashSet<>();
        // Grains.all() rather than 0..count(): an id that data has retired stays resolvable by id —
        // blocks made of it may exist — but is deliberately absent from the roster and from byName.
        for (Grain def : Grains.all()) {
            assertTrue(names.add(def.name()), "duplicate name " + def.name());
            assertEquals(def, Grains.byId(def.id()));
            assertEquals(def, Grains.byName(def.name()));
        }
        assertEquals(0, Grains.AIR.id(),
                "air is first so a default-initialised slot array is a block of nothing");
        assertTrue(Grains.GRANITE.id() < Grains.TUFF.id(),
                "code grains keep declaration order; TUFF was appended, never inserted");
        assertThrows(IllegalArgumentException.class, () -> Grains.byId(Grains.count()));
        assertThrows(IllegalArgumentException.class, () -> Grains.byName("unobtainium"));
    }

    @Test
    @DisplayName("every stone belongs to exactly one family, and every family has stone")
    void stonesPartitionByFamily() {
        for (Grain stone : Grains.ofClass(GrainClass.ROCK)) {
            int families = 0;
            for (BedrockType family : BedrockType.values()) {
                if (stone.occursIn(family)) {
                    families++;
                }
            }
            assertEquals(1, families, stone.name() + " should belong to exactly one family");
        }
        for (BedrockType family : BedrockType.values()) {
            assertFalse(Grains.admitted(family, GrainClass.ROCK).isEmpty(),
                    family + " has no stone to be made of");
        }
    }

    @Test
    @DisplayName("stone colours are distinct, so a colour identifies its stone")
    void stoneColoursAreDistinct() {
        Set<Integer> tints = new HashSet<>();
        for (Grain stone : Grains.ofClass(GrainClass.ROCK)) {
            assertTrue(tints.add(stone.tint()), stone.name() + " shares a colour with another stone");
        }
    }

    @Test
    @DisplayName("the mediation table is real geology, not decoration")
    void mineralOccurrenceMatchesGeology() {
        // Iron is the one ore in every country -- banded iron is sedimentary, magmatic iron igneous.
        for (BedrockType family : BedrockType.values()) {
            assertTrue(Grains.admitted(family, GrainClass.ORE).contains(Grains.IRON),
                    "iron should occur in " + family);
        }
        // Kimberlite is igneous; lazurite is contact-metamorphosed limestone.
        assertTrue(Grains.DIAMOND.occursIn(BedrockType.IGNEOUS));
        assertFalse(Grains.DIAMOND.occursIn(BedrockType.SEDIMENTARY));
        assertTrue(Grains.LAPIS.occursIn(BedrockType.METAMORPHIC));
        assertFalse(Grains.LAPIS.occursIn(BedrockType.IGNEOUS));

        // The prospecting payoff: sedimentary country holds no precious ore and no gems at all, so
        // finding igneous or metamorphic ground is worth the travel. This is what restores §4's
        // promise that bedrock tells you the ore family -- indirectly, through the rock family.
        assertTrue(Grains.admitted(BedrockType.SEDIMENTARY, GrainClass.GEM).isEmpty(),
                "sedimentary country should hold no gems");
        assertTrue(Grains.admitted(BedrockType.SEDIMENTARY, GrainClass.PRECIOUS_ORE).isEmpty(),
                "sedimentary country should hold no precious ore");
        assertFalse(Grains.admitted(BedrockType.IGNEOUS, GrainClass.GEM).isEmpty());
        assertFalse(Grains.admitted(BedrockType.METAMORPHIC, GrainClass.GEM).isEmpty());
    }

    @Test
    @DisplayName("minerals render saturated, stone muted")
    void tintsFollowClass() {
        for (Grain def : Grains.all()) {
            int rendered = def.renderTint();
            assertTrue(rendered >= 0 && rendered <= 0xFFFFFF);
            if (def.clazz() == GrainClass.ORE || def.clazz() == GrainClass.PRECIOUS_ORE
                    || def.clazz() == GrainClass.GEM) {
                assertEquals(def.mineralTint(), rendered, def.name() + " should render saturated");
            } else {
                assertEquals(def.rockTint(), rendered, def.name() + " should render muted");
            }
        }
    }
}
