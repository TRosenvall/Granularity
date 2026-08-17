package com.tarosie.granularity.client;

import com.tarosie.granularity.core.Grains;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.world.item.ItemStack;

/**
 * Tints a dropped object by the grain it carries.
 *
 * <p>The same economy as blocks (design §5): one greyscale sprite per class, coloured at draw time,
 * so the atlas never grows with the roster. Seven sprites cover every grain of every class, and a
 * mod adding its own ore needs no new sprite at all.
 */
public class GrainItemColour implements ItemColor {

    /** Opaque white: multiplying by this leaves the greyscale sprite exactly as it is. */
    private static final int NO_TINT = 0xFFFFFFFF;

    /**
     * Item tints are ARGB and the alpha byte is honoured — {@code ItemRenderer} reads
     * {@code FastColor.ARGB32.alpha(i)}. A bare 0xRRGGBB has alpha 0, which renders the item
     * completely invisible rather than untinted. Block tints go through a path that ignores alpha,
     * which is why the same colours looked correct on blocks and vanished in the inventory.
     */
    private static final int OPAQUE = 0xFF000000;


    @Override
    public int getColor(ItemStack stack, int tintIndex) {
        if (tintIndex != 0) {
            return NO_TINT;
        }
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) {
            return NO_TINT;
        }
        com.tarosie.granularity.core.Grain grain = Grains.byItem(key.toString());
        if (grain == null) {
            return NO_TINT;
        }
        // The grain knows whether it renders muted or saturated -- rock is pulled toward grey,
        // minerals keep their colour so they read out of the rock, in the hand as in the wall.
        return OPAQUE | grain.renderTint();
    }
}
