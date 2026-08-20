package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionLayers;
import org.jetbrains.annotations.Nullable;

/**
 * A block entity that knows what its block is made of.
 *
 * <p>An interface because the family cannot share a superclass. {@link CompositeBlockEntity} extends
 * {@code BlockEntity} and that is all it needs, but a furnace has to extend
 * {@code AbstractFurnaceBlockEntity} to be a furnace at all — and one position holds exactly one
 * block entity, so it cannot be both. The composition is a fact about the block rather than an
 * implementation, and this is how the renderer, the drops and the dye all ask for it without caring
 * which kind of block entity answered.
 *
 * <p>The same shape as {@link CompositeStone} on the block side, and for the same reason.
 */
public interface CompositionHolder {

    Composition composition();

    /** A double slab's upper half, or null wherever there is only one composition. */
    @Nullable
    Composition upper();

    /** The dyed faces of the block's matrix, or of a slab's lower half. Empty means the average. */
    Dyes dyes();

    /** The dyed faces of a double slab's upper half; empty for everything else. */
    Dyes upperDyes();

    /** What is growing on the block, or on a slab's lower half. */
    Coating overlays();

    /** What is growing on a double slab's upper half; empty for everything else. */
    Coating upperOverlays();

    /** The reduction the renderer wants, cached until the composition changes. */
    CompositionLayers layers();

    /**
     * The timber this block was built from, or null where it is built from stone alone.
     *
     * <p>Defaulted rather than abstract because only the piston has any: a furnace and a dropper are
     * rock all the way through, and there is nothing for them to answer. See
     * {@link GranularityComponents#WOOD}.
     */
    @Nullable
    default net.minecraft.resources.ResourceLocation wood() {
        return null;
    }

    default void setWood(@Nullable net.minecraft.resources.ResourceLocation wood) {
    }

    /** The metal this block's fittings are made of, or null where it has none. */
    @Nullable
    default net.minecraft.resources.ResourceLocation metal() {
        return null;
    }

    default void setMetal(@Nullable net.minecraft.resources.ResourceLocation metal) {
    }

    void setComposition(Composition composition);

    /** Sets one half's dye. {@code upper} is only ever true for a double slab. */
    void setDyes(boolean upper, Dyes dyes);

    void setOverlays(boolean upper, Coating overlays);

    /**
     * What this block is wearing, part by part. Empty for a block showing what it is made of.
     *
     * <p>On this interface rather than on {@link CompositeBlockEntity} because a furnace, a dispenser
     * and a dropper are not one — they had to extend vanilla's container block entities instead — and
     * a costume that only half the family can carry is a menu that silently does nothing when opened
     * on the other half. That is exactly how it failed the first time.
     */
    default Costumes costumes() {
        return Costumes.NONE;
    }

    default void setCostumes(Costumes costumes) {
    }
}
