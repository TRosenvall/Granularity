package com.tarosie.granularity.client;

import com.tarosie.granularity.core.CompositionLayers;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Turns the derived composition into the tints the grayscale sprites are multiplied by.
 *
 * <p>This is what makes §5's atlas economy work: seven tint indices over shared grayscale sprites
 * cover all 16 colours in every combination, so the atlas never multiplies by the lattice.
 *
 * <ul>
 *   <li>0 — the <i>averaged</i> rock colour, muted. Averaging is what turns a region boundary into
 *       a gradient instead of a seam, and what makes a mixed block read as muddier than a pure one
 *       (design §3, §4).</li>
 *   <li>1–6 — ore, precious ore and gem, each with two colour slots, near full saturation so they
 *       read out of the rock. Two slots per class is what lets a block show iron <i>and</i> copper
 *       rather than averaging them into one wrong colour.</li>
 * </ul>
 *
 * <p>Tints are resolved while a chunk section is being built and baked into the vertex colours, so
 * like the quads themselves this costs nothing per frame.
 */
public class CompositionBlockColour implements BlockColor {

    /** White: multiplying by this leaves the grayscale sprite exactly as it is. */
    private static final int NO_TINT = 0xFFFFFF;

    @Override
    public int getColor(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos, int tintIndex) {
        if (level == null || pos == null) {
            return NO_TINT;
        }
        // A crafted block stores its composition; natural stone derives it. Ore blocks are the
        // crafted kind but want the natural rendering, so both routes land here.
        CompositionLayers layers;
        if (level.getBlockEntity(pos) instanceof com.tarosie.granularity.content.CompositeBlockEntity composite) {
            layers = composite.layers();
        } else {
            layers = ClientCompositions.layersAt(pos);
        }
        if (layers == null) {
            return NO_TINT;
        }
        // The tints are already resolved in the layers: an average is not a lattice entry, so
        // there is nothing left to look up here.
        return switch (tintIndex) {
            case 0 -> layers.baseTint();
            case 1 -> layers.ore().primary().tint();
            case 2 -> layers.ore().secondary().tint();
            case 3 -> layers.precious().primary().tint();
            case 4 -> layers.precious().secondary().tint();
            case 5 -> layers.gem().primary().tint();
            case 6 -> layers.gem().secondary().tint();
            default -> NO_TINT;
        };
    }
}
