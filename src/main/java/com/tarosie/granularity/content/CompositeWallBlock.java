package com.tarosie.granularity.content;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

/**
 * A wall that remembers the stone it was built from. See {@link CompositeShapes} for the rule.
 *
 * <p>This is the block that proves the model rework. A wall's blockstate is <b>multipart</b> — nine
 * conditional entries assembled into one {@code MultiPartBakedModel} — and the old approach of
 * composing layers after baking could not participate in that at all. With the layers inside each
 * model, post and sides are ordinary models and multipart works untouched.
 */
public class CompositeWallBlock extends WallBlock implements EntityBlock, CompositeStone {

    public CompositeWallBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return CompositeShapes.entity(pos, state);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return CompositeShapes.drops(this, state, params, 1, CompositeShapes.WALL_GRAINS);
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
