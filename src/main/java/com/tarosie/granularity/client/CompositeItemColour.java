package com.tarosie.granularity.client;

import com.tarosie.granularity.content.Dyes;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.core.Composition;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.world.item.ItemStack;

/** The same tints as the placed block, read off the stack's stored composition. */
public class CompositeItemColour implements ItemColor {

    /** See {@link GrainItemColour}: item tints are ARGB, and alpha 0 renders nothing at all. */
    private static final int OPAQUE = 0xFF000000;

    /**
     * What a stack with no composition of its own is drawn as.
     *
     * <p>The same stone {@link com.tarosie.granularity.content.CompositeBlockEntity} starts from, so
     * that an item and the block it becomes agree. Returning "no tint" instead — which is what this
     * used to do — left the greyscale base sprite showing raw, and every such stack rendered as a
     * washed-out ghost of itself.
     *
     * <p>The recipe book is the case that made it visible. It draws {@code getResultItem()}, which is
     * the recipe's bare JSON result and so has no composition; there is no grid to read grains from
     * yet, because the point of the entry is to tell you what to put in one. {@code /give} and any
     * other hand-built stack were in the same position.
     */
    private static final Composition DEFAULT =
            Composition.uniform(com.tarosie.granularity.core.Grains.ANDESITE.id());

    @Override
    public int getColor(ItemStack stack, int tintIndex) {
        if (tintIndex == CompositeBlockColour.WOOD_TINT) {
            return OPAQUE | CompositeBlockColour.woodTint(stack.get(GranularityComponents.WOOD.get()));
        }
        if (tintIndex == CompositeBlockColour.METAL_TINT) {
            return OPAQUE | CompositeBlockColour.metalTint(stack.get(GranularityComponents.METAL.get()));
        }
        Composition composition = stack.get(GranularityComponents.COMPOSITION.get());
        Composition resolved = composition == null ? DEFAULT : composition;
        Dyes dyes = Dyes.of(stack);
        if (tintIndex >= CompositeBlockColour.UPPER_BASE
                && tintIndex < CompositeBlockColour.WOOD_TINT) {
            // A block built out of two stones — the stonecutter, whose bench is divided by a wooden
            // rail. The placed block reads this off the block entity's second composition; a stack
            // has to read it off the component, or the item shows one stone where the block shows
            // two. Falling back to the first composition is what every one-stone composite wants,
            // and matches the block handler exactly.
            Composition upper = stack.get(GranularityComponents.UPPER_COMPOSITION.get());
            return OPAQUE | CompositeBlockColour.tintFor(upper != null ? upper : resolved, null,
                    tintIndex - CompositeBlockColour.UPPER_BASE);
        }
        if (tintIndex >= CompositeBlockColour.DYE_BASE) {
            // The stack's model is wrapped by OverlayItemModel, which rewrites a dyed face's matrix
            // quad the same way the block's does — so the item is asked about the same six indices.
            int face = tintIndex - CompositeBlockColour.DYE_BASE;
            if (face < 0 || face > 5) {
                return OPAQUE | CompositeBlockColour.tintFor(resolved, null, 0);
            }
            return OPAQUE | CompositeBlockColour.tintFor(resolved,
                    dyes.on(net.minecraft.core.Direction.from3DDataValue(face)), 0);
        }
        // A flat item sprite is never wrapped — its one layer is tint index 0 — so a block dyed the
        // same colour all over says so here rather than falling back to the average. The lever is the
        // only such item, and a part-dyed one has no single colour to show.
        return OPAQUE | CompositeBlockColour.tintFor(resolved,
                tintIndex == 0 ? dyes.uniform() : null, tintIndex);
    }
}
