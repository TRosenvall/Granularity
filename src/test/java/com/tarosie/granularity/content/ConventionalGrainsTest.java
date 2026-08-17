package com.tarosie.granularity.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tarosie.granularity.core.BedrockType;
import com.tarosie.granularity.core.GrainClass;
import com.tarosie.granularity.core.GrainSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Minting grains from conventional tags, which is the only tier that scales to a modpack.
 *
 * <p>Walking the tag registry needs a running game; deciding what a tag <i>means</i> does not, and
 * that is where every rule worth pinning lives. {@code granularity:gem} and {@code granularity:ingot}
 * stand in for a mod's items here — they are real sprites on the classpath that no grain claims, so
 * the colour is genuinely averaged rather than stubbed.
 */
class ConventionalGrainsTest {

    private static final String MOD_RUBY = "granularity:gem";
    private static final String OTHER_RUBY = "granularity:ingot";

    @Test
    @DisplayName("a modded material becomes a grain, coloured by its own item")
    void adoptsAModdedMaterial() {
        GrainSpec spec = ConventionalGrains.choose(
                "c:ruby", GrainClass.GEM, List.of(MOD_RUBY), Set.of());

        assertNotNull(spec);
        assertEquals("c:ruby", spec.name());
        assertEquals(MOD_RUBY, spec.itemId());
        assertTrue(spec.tint() > 0, "the colour comes from the item's own sprite");
    }

    @Test
    @DisplayName("two mods' rubies are one material, and the lower item id decides")
    void oneGrainPerMaterialNotPerItem() {
        GrainSpec spec = ConventionalGrains.choose(
                "c:ruby", GrainClass.GEM, List.of(MOD_RUBY, OTHER_RUBY), Set.of());
        GrainSpec reversed = ConventionalGrains.choose(
                "c:ruby", GrainClass.GEM, List.of(OTHER_RUBY, MOD_RUBY), Set.of());

        // Named for the tag, so installing a second ruby mod does not add a second ruby to the
        // roster — and because Grains.pick hashes the name, it does not move anyone's stone either.
        assertEquals(spec, reversed, "the order the tag happened to be in must not decide anything");
        assertEquals(MOD_RUBY, spec.itemId(), "granularity:gem sorts before granularity:ingot");
    }

    @Test
    @DisplayName("a material we already model is left alone")
    void claimedItemsAreNotAdoptedTwice() {
        assertNull(ConventionalGrains.choose("c:iron", GrainClass.ORE,
                List.of("minecraft:raw_iron", MOD_RUBY), Set.of("minecraft:raw_iron")),
                "iron is granularity:iron however many mods tag their own raw iron beside vanilla's");
    }

    @Test
    @DisplayName("a purely vanilla material is left for us to decide about deliberately")
    void vanillaOnlyMaterialsAreSkipped() {
        // c:gems/amethyst is the real case. Skipping it is a design choice — the geode is going to be
        // rebuilt out of grains on purpose — and it also removes the one way the two sides could
        // have disagreed, since a dedicated server cannot read vanilla textures to average them.
        assertNull(ConventionalGrains.choose("c:amethyst", GrainClass.GEM,
                List.of("minecraft:amethyst_shard"), Set.of()));
    }

    @Test
    @DisplayName("an inferred gem is not admitted everywhere, or prospecting would mean nothing")
    void gemsInheritTheRangeOurOwnGemsHave() {
        GrainSpec gem = ConventionalGrains.choose("c:ruby", GrainClass.GEM, List.of(MOD_RUBY), Set.of());
        assertEquals(Set.of(BedrockType.IGNEOUS, BedrockType.METAMORPHIC), gem.families(),
                "sedimentary country must stay honestly barren of gems");

        GrainSpec ore = ConventionalGrains.choose("c:tin", GrainClass.ORE, List.of(MOD_RUBY), Set.of());
        assertTrue(ore.families().isEmpty(), "an ore occurs in all three, as iron does");
    }

    @Test
    @DisplayName("an item whose texture cannot be read is skipped rather than given a colour")
    void unreadableItemsAreSkipped() {
        assertNull(ConventionalGrains.choose("c:unobtainium", GrainClass.GEM,
                List.of("nosuchmod:nothing"), Set.of()),
                "a colour invented on one side and read on the other is two different worlds");
    }
}
