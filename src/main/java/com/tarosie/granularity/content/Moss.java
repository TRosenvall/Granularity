package com.tarosie.granularity.content;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * Overlays as they are carried, per half, by blocks and by items.
 *
 * <p>This used to be moss and only moss, held in a blockstate property. It is now the general case,
 * and the property is gone — {@link Overlay} explains why at length, but the short of it is that
 * properties cannot be registered by other mods and multiply by 2<sup>N</sup>.
 *
 * <p><b>Per half is the thing to keep.</b> A double slab is two slabs, and each may be mossy or not
 * independently, exactly as each may be a different stone. That was the bug that killed the
 * separate-block design and it stays fixed here: {@link CompositeBlockEntity} holds one overlay set
 * for the block (or a slab's lower half) and a second for a slab's upper half, mirroring how it
 * holds two compositions.
 */
public final class Moss {

    private Moss() {
    }

    /** The coating an item form carries. Absent means bare. */
    public static Coating of(ItemStack stack) {
        Map<net.minecraft.resources.ResourceLocation, Integer> ids =
                stack.get(GranularityComponents.OVERLAYS.get());
        return ids == null || ids.isEmpty() ? Coating.NONE : GranularityOverlays.fromIds(ids);
    }

    /** Writes a coating onto an item form, leaving a bare block clean of the component. */
    public static void apply(ItemStack stack, Coating coating) {
        if (!coating.isEmpty()) {
            stack.set(GranularityComponents.OVERLAYS.get(), GranularityOverlays.ids(coating));
        }
    }

    /**
     * Whether a click at this point means the upper half.
     *
     * <p>Only a double slab has two halves to choose between; everything else has one, and a single
     * slab stores into the lower set whichever way up it sits — the same convention
     * {@link CompositeBlockEntity#setSlabHalf} uses for composition, so the two never disagree.
     */
    public static boolean upperHalfAt(BlockState state, BlockPos pos, Vec3 hit) {
        if (!hasTwoHalves(state) || hit == null) {
            return false;
        }
        // "Upper" is really "the far half along the slab's axis" now that a slab can stand on end. On
        // a horizontal double this is the y test it always was; on a vertical one it asks about x or
        // z instead, so mossing the east side of an east–west double reaches the east half.
        net.minecraft.core.Direction.Axis axis =
                state.hasProperty(CompositeSlabBlock.AXIS)
                        ? state.getValue(CompositeSlabBlock.AXIS)
                        : net.minecraft.core.Direction.Axis.Y;
        return axis.choose(hit.x - pos.getX(), hit.y - pos.getY(), hit.z - pos.getZ()) >= 0.5;
    }

    /**
     * Whether this block's two halves are coated <b>separately</b> — which only a double slab's are.
     *
     * <p>The distinction matters because "two halves" and "two stones" turned out to be different
     * things. A double slab is two objects sharing a block space, so moss on the bottom one leaves
     * the top one clean and each half keeps its own dye. A stonecutter also draws from two
     * compositions — its bench is divided by a wooden rail, with a different rock either side — but it
     * is <i>one</i> block, and moss growing over it should cover all of it.
     *
     * <p>Both use tint indices 10–19 for their second stone, because that is the machinery for "one
     * block, two compositions" and reusing it is right. This is the one place where they part company:
     * a block that is not two halves has its upper surface coated exactly as its lower one is. Without
     * this, a mossy stonecutter grew moss on its body and none on its top, which nothing would have
     * reported.
     */
    public static boolean hasTwoHalves(BlockState state) {
        return state.hasProperty(SlabBlock.TYPE) && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE;
    }

}
