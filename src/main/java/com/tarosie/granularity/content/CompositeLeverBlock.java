package com.tarosie.granularity.content;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

/**
 * A lever that remembers the stone it was cut from.
 *
 * <p>The first of the vanilla blocks to be rebuilt on grains. Vanilla's lever already draws its base
 * from {@code block/cobblestone}, so nothing about its shape changes — the base becomes ten
 * coincident layers instead of one, and its colour follows the stone it was made of. A lever cut
 * from slate is a dark lever.
 *
 * <p>Being a {@link CompositeStone} it accepts overlays with no further work: the same moss and blood
 * that grow on a wall grow on this, because {@code OverlayBakedModel} asks the model for its surface
 * rather than asking the block what it is.
 *
 * <p>A block entity is safe here where it would not be on a piston. Levers already have
 * {@link net.minecraft.world.level.material.PushReaction#DESTROY}, so nothing was ever going to push
 * one, and adding data costs no behaviour.
 */
public class CompositeLeverBlock extends LeverBlock implements EntityBlock, CompositeStone {

    public CompositeLeverBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeBlockEntity(pos, state);
    }

    /**
     * One grain in, one grain out.
     *
     * <p>A lever costs a single chunk, so taking it apart returns a single chunk — no hammer needed
     * and no fraction to round, which is the one case in the mod where conservation is exact and
     * obvious.
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return CompositeShapes.drops(this, state, params, 1, 1);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        CompositeShapes.placed(level, pos, stack);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return CompositeShapes.cloned(super.getCloneItemStack(level, pos, state), level, pos, state);
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit) {
        // Sneak + moss grows on it; an ordinary click still flips it, because the sneak gesture is
        // exactly what tells the two apart.
        net.minecraft.world.ItemInteractionResult result =
                CompositeShapes.interact(stack, state, level, pos, player, hit);
        if (result != net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return result;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /** A piston push rebuilds the block at the far end; this is where its memory catches up. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        PistonMoves.land(level, pos, isMoving);
    }

}
