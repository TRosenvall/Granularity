package com.tarosie.granularity.content;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

/**
 * A piston built from four grains, which shows all four.
 *
 * <p>Four is what vanilla's recipe costs — the planks, the iron and the redstone are not stone — so
 * the side and back are split four ways. The plate on its face and the plane revealed when it
 * extends are machinery: drawn once from a pre-darkened sprite, never tinted, because they are the
 * one part of a piston that should look the same whatever it was cut from.
 *
 * <p>Two things here are not simply bookkeeping.
 *
 * <p>{@link #getPistonPushReaction} restores a rule vanilla only ever applies to itself. Vanilla's
 * {@code isPushable} special-cases {@code Blocks.PISTON} and {@code Blocks.STICKY_PISTON} to refuse
 * an <b>extended</b> piston by name; every other block falls through to the push-reaction check. Ours
 * is not one of those two, so without this an extended piston could be shoved out from under its own
 * head and leave it stranded.
 *
 * <p>The block entity is likewise not free. Vanilla refuses to push anything carrying one, which is
 * lifted for composites by {@link com.tarosie.granularity.mixin.PistonBaseBlockMixin} and made safe
 * by {@link PistonMoves}; extending itself is unaffected, because the base never moves and
 * {@code setBlock} to the same block keeps the entity where it is.
 */
public class CompositePistonBlock extends PistonBaseBlock implements EntityBlock, CompositeStone {

    // B is inferred as PistonBaseBlock: that class declares codec() as MapCodec<PistonBaseBlock> and
    // generics are invariant. Vanilla's own codec carries a `sticky` field; ours does not need one,
    // because a Granularity piston is never sticky and the flag is fixed at construction.
    public static final MapCodec<PistonBaseBlock> CODEC = simpleCodec(CompositePistonBlock::new);

    /** What the recipe costs, and so what the hammer returns and how many dimples are drawn. */
    public static final int GRAINS = 4;

    public CompositePistonBlock(Properties properties) {
        super(false, properties);
    }

    @Override
    public MapCodec<PistonBaseBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeBlockEntity(pos, state);
    }

    /** An extended piston cannot be moved — see the class note on why this has to be said aloud. */
    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return state.getValue(EXTENDED) ? PushReaction.BLOCK : PushReaction.NORMAL;
    }

    /** Drops the piston whole, or its four grains under the hammer. */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return CompositeShapes.drops(this, state, params, 1, GRAINS);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        CompositeShapes.placed(level, pos, stack);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return CompositeShapes.cloned(super.getCloneItemStack(level, pos, state), level, pos, state);
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        // Sneak + moss grows on it; a piston has no ordinary click of its own to preserve.
        net.minecraft.world.ItemInteractionResult result =
                CompositeShapes.interact(stack, state, level, pos, player, hit);
        if (result != net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return result;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /**
     * A piston with moss over its plate will not extend. See {@link Fouling}.
     *
     * <p>Extension only — id 0 is the push, 1 and 2 are the pull. Blocking retraction as well would
     * be a trap: an extended piston's plate is behind its own head, so a player could not reach the
     * moss to scrape it off and the piston would be stuck out for good. Jammed shut is recoverable;
     * jammed open is not.
     */
    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        if (id == 0 && Fouling.fouled(level, pos, state)) {
            return false;
        }
        return super.triggerEvent(state, level, pos, id, param);
    }

    /** A piston push rebuilds the block at the far end; this is where its memory catches up. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        PistonMoves.land(level, pos, isMoving);
    }
}
