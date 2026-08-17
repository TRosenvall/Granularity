package com.tarosie.granularity.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/**
 * A machine with moss over its working face has stopped working.
 *
 * <p>Every block in the stoneware family has one face that is the point of it: the door of a furnace,
 * the muzzle of a dispenser, the slot an observer watches through, the plate a piston pushes with.
 * Let moss close over that and the block should be as useless as it looks. This is the first thing
 * overlays do that is not decoration, and it is only possible because they are
 * {@linkplain Coating per face} — "the block is mossy" could never have meant this.
 *
 * <p>Reading the face from {@code FACING} rather than from a per-block table is what keeps this one
 * method. Every one of these blocks already stores which way it points, and for every one of them
 * the working face is the way it points.
 *
 * <p><b>Only moss.</b> Slime is sticky, not obstructive, and a slimed furnace should still smelt.
 * When something eventually fouls a machine on purpose it can be added here.
 */
public final class Fouling {

    private Fouling() {
    }

    /** Which way the block works. Null for a block with no facing at all. */
    @Nullable
    public static Direction workingFace(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    /** Whether moss has closed over this block's working face. */
    public static boolean fouled(BlockGetter level, BlockPos pos, BlockState state) {
        Direction face = workingFace(state);
        if (face == null || !(level.getBlockEntity(pos) instanceof CompositionHolder composite)) {
            return false;
        }
        return Coating.covers(composite.overlays().facesOf(GranularityOverlays.MOSS.get()), face);
    }

    /**
     * The same, for a working part with <b>two</b> sides rather than one.
     *
     * <p>Every other machine here has a one-sided working face — a door, a muzzle, a slot, a plate —
     * and moss on the back of a furnace is no obstruction at all. A stonecutter's saw is not like that:
     * it is a disc standing up out of the bench, and moss packed against either side of it jams the
     * blade just as well. So the whole axis counts.
     *
     * <p>The axis is the block's facing, because the blade stands square across it — the model's saw
     * plane sits at {@code z=8} facing north and south, and the blockstate's y rotation carries both
     * faces round together with the block.
     */
    public static boolean bladeFouled(BlockGetter level, BlockPos pos, BlockState state) {
        Direction face = workingFace(state);
        if (face == null || !(level.getBlockEntity(pos) instanceof CompositionHolder composite)) {
            return false;
        }
        return bladeFouled(composite.overlays(), face);
    }

    /**
     * The same question asked of a {@link Coating} directly, for the renderer.
     *
     * <p>Split out because the client has to answer it from {@link net.minecraft.client.renderer.block.model.BakedQuad}
     * time, where there is a blockstate and a coating in hand but no level to look a block entity up
     * in. One rule, two callers, so a jammed blade and a stopped blade can never disagree.
     */
    public static boolean bladeFouled(Coating overlays, Direction facing) {
        return coversEitherSide(overlays.facesOf(GranularityOverlays.MOSS.get()), facing);
    }

    /**
     * Whether a face mask covers either side of the axis a two-sided working part stands across.
     *
     * <p>The geometry on its own, taking a mask rather than a {@link Coating}, so it can be tested
     * without a registry — which the moss lookup above needs and a unit test does not have. It is also
     * the only part with any reasoning in it: a saw is jammed from the front or the back, and the
     * perpendicular faces are the bench, where moss is only untidy.
     */
    public static boolean coversEitherSide(int faces, Direction axis) {
        return Coating.covers(faces, axis) || Coating.covers(faces, axis.getOpposite());
    }
}
