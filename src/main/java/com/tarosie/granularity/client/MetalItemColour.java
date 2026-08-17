package com.tarosie.granularity.client;

import net.minecraft.client.color.item.ItemColor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Tints a bar by which metal it is.
 *
 * <p>One greyscale ingot sprite serves every metal, exactly as one chunk sprite serves every rock —
 * see {@link GrainItemColour}, which is the same idea for the other half of the roster. Adding a
 * metal costs a registry entry and no texture at all.
 *
 * <p>The colour comes from {@link CompositeBlockColour#metalTint}, which is also what paints a
 * piston's fittings. That is deliberate: a piston built with silver should have nubbins the colour
 * of the bar that went into it, and one lookup guarantees they cannot drift apart.
 */
public class MetalItemColour implements ItemColor {

    /** See {@link GrainItemColour}: item tints are ARGB and alpha 0 renders nothing at all. */
    private static final int OPAQUE = 0xFF000000;

    @Override
    public int getColor(ItemStack stack, int tintIndex) {
        if (tintIndex != 0) {
            return 0xFFFFFFFF;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return OPAQUE | CompositeBlockColour.metalTint(id);
    }
}
