package com.tarosie.granularity.content;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.BedrockType;
import com.tarosie.granularity.core.GrainClass;
import com.tarosie.granularity.core.GrainSpec;
import com.tarosie.granularity.core.Grains;
import com.tarosie.granularity.core.TextureTint;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Grains minted from NeoForge's conventional item tags, so a modpack gets them for nothing.
 *
 * <p>Tier 3, and the one that scales. Tier 1 needs the other mod to call us; tier 2 needs somebody to
 * write a JSON file per material. This needs neither: a mod that already tags its ruby as
 * {@code c:gems/ruby} — which mods do anyway, so their ruby works in other mods' recipes — has said
 * everything we need to hear.
 *
 * <h2>One grain per material, not per item</h2>
 * The unit is the <b>tag</b>, not the item in it. Two mods may each add a ruby, and they are one
 * material; naming the grain after the tag ({@code c:ruby}) rather than after whichever item was
 * found first means the roster does not gain a second ruby when a second mod is installed. It also
 * keeps worldgen stable, because {@code Grains.pick} hashes the name — the grain owns the same
 * regions whether one ruby mod is present or three. The item is only what the grain <i>drops</i>, and
 * the lowest id wins so that two installs of the same pack agree.
 *
 * <h2>Vanilla materials are skipped, deliberately</h2>
 * Not an oversight and not only caution. Amethyst and prismarine are the two vanilla materials these
 * tags would otherwise hand us, and both are things this mod should decide about on purpose — the
 * geode is already on the roadmap to be rebuilt out of grains. A tag says what a thing <i>is</i>; it
 * cannot say what part it should play in a world we are designing.
 *
 * <p>It also happens to close the one way this could have gone wrong. Tints are averaged from the
 * item's texture on each side independently, and a dedicated server has no vanilla assets — so a
 * vanilla-backed grain would have been adopted by the client and skipped by the server, and the two
 * would have derived different rock. Mod jars carry their assets to both sides, so for everything
 * that is left, both sides read the same bytes and reach the same answer.
 */
public final class ConventionalGrains {

    private static final String CONVENTIONAL = "c";
    private static final String GEMS = "gems/";
    private static final String RAW = "raw_materials/";

    /**
     * Where a material is admitted when nothing has said.
     *
     * <p>A tag is silent about geology, and "everywhere" is the tempting default and the wrong one:
     * design §4's promise is that the country rock tells you what is possible, and a gem that occurs
     * in every family is a gem nobody need travel for. Gems therefore inherit the range our own gems
     * have — igneous and metamorphic, never sedimentary — which keeps sedimentary country honestly
     * barren. Ores occur in all three, as iron does. A pack that knows better says so in a definition
     * of its own, which takes precedence.
     */
    private static final Set<BedrockType> GEM_FAMILIES =
            EnumSet.of(BedrockType.IGNEOUS, BedrockType.METAMORPHIC);

    private ConventionalGrains() {
    }

    /**
     * Every material worth adopting that nothing has claimed yet.
     *
     * <p>{@code claimedItems} and {@code claimedNames} are what the code roster and the datapacks have
     * already taken, so an explicit definition always wins over an inferred one — which is the escape
     * hatch for a pack that dislikes what this would do.
     */
    public static List<GrainSpec> adopt(Set<String> claimedItems, Set<String> claimedNames) {
        List<GrainSpec> adopted = new ArrayList<>();
        for (TagKey<Item> tag : BuiltInRegistries.ITEM.getTagNames().toList()) {
            if (!tag.location().getNamespace().equals(CONVENTIONAL)) {
                continue;
            }
            String path = tag.location().getPath();
            GrainClass clazz = path.startsWith(GEMS) ? GrainClass.GEM
                    : path.startsWith(RAW) ? GrainClass.ORE : null;
            if (clazz == null) {
                continue;
            }
            String material = path.substring(path.indexOf('/') + 1);
            // `c:gems` itself, and any deeper nesting, is not one material.
            if (material.isEmpty() || material.indexOf('/') >= 0) {
                continue;
            }

            String name = CONVENTIONAL + ":" + material;
            if (claimedNames.contains(name)) {
                continue;
            }
            GrainSpec spec = fromTag(tag, name, clazz, claimedItems);
            if (spec != null) {
                adopted.add(spec);
                claimedItems.add(spec.itemId());
                claimedNames.add(name);
            }
        }
        return adopted;
    }

    private static GrainSpec fromTag(TagKey<Item> tag, String name, GrainClass clazz,
                                     Set<String> claimedItems) {
        List<String> itemIds = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(holder.value());
            if (id != null) {
                itemIds.add(id.toString());
            }
        }
        return choose(name, clazz, itemIds, claimedItems);
    }

    /**
     * The whole of the decision, given what is in a tag. Package-private so it can be tested without
     * a game running, which is where the rules that matter actually live.
     */
    static GrainSpec choose(String name, GrainClass clazz, List<String> itemIds,
                            Set<String> claimedItems) {
        String chosen = null;
        for (String itemId : itemIds) {
            if (claimedItems.contains(itemId)) {
                // We already model this material under a name of our own — iron is granularity:iron,
                // however many mods tag their own raw iron alongside vanilla's.
                return null;
            }
            if (itemId.startsWith("minecraft:")) {
                continue;   // see the class note
            }
            if (chosen == null || itemId.compareTo(chosen) < 0) {
                chosen = itemId;   // lowest id, so two installs of the same pack agree
            }
        }
        if (chosen == null) {
            return null;
        }

        int tint;
        try {
            tint = TextureTint.averageOf(chosen);
        } catch (RuntimeException unreadable) {
            // Skipped rather than guessed, for the reason the class note gives: a colour invented on
            // one side and read on the other is two different worlds.
            Granularity.LOGGER.debug("Not adopting {}: {}", name, unreadable.getMessage());
            return null;
        }
        return new GrainSpec(name, clazz, tint, chosen,
                clazz == GrainClass.GEM ? GEM_FAMILIES : Set.of());
    }

    /** The items and names already spoken for, which adoption must not tread on. */
    public static Set<String> claimedItems() {
        return new java.util.HashSet<>(Grains.itemIds());
    }

    public static Set<String> claimedNames() {
        Set<String> names = new java.util.HashSet<>();
        for (com.tarosie.granularity.core.Grain grain : Grains.all()) {
            names.add(grain.name());
        }
        return names;
    }
}
