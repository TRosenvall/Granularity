package com.tarosie.granularity.content;

import com.tarosie.granularity.Granularity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Tags the mod asks questions of, as opposed to the vanilla ones it merely fills in. */
public final class GranularityTags {

    /**
     * Composite blocks moss will not spread onto.
     *
     * <p>The alloy block is the case: gravel taking moss is fine, a cast metal block is not. Made a
     * tag rather than a check against that one block so a pack — or another mod adding a composite of
     * its own — can answer the question without touching code, which is the same reason overlays are
     * a registry rather than a blockstate property.
     *
     * <p>It gates <b>spreading</b> only. Moss already on a block stays, and a block can still be
     * scraped, so nothing that exists in a world is invalidated by adding to this tag.
     */
    public static final TagKey<Block> MOSS_WONT_GROW = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "moss_wont_grow"));

    /**
     * Composites whose dye covers the grains as well as the matrix.
     *
     * <p>Gravel is the case, and it is a fact about the <b>texture</b> rather than a preference: its
     * nine grain regions tile all 256 pixels, so the matrix beneath them is never visible and a dye
     * confined to it does nothing at all. Loose pebbles have no mortar between them to colour.
     *
     * <p>This is the one place the rule in {@link Dyes} — dye never touches the stones — is set aside,
     * deliberately and per block. The cost is real and was chosen knowingly: a dyed gravel no longer
     * shows what it is made of. Still <b>per face</b>, so an undyed side of the same block goes on
     * showing its grains.
     */
    public static final TagKey<Block> DYED_WHOLE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "dyed_whole"));

    private GranularityTags() {
    }
}
