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
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

/**
 * An observer built from six grains, which shows all six.
 *
 * <p>Six because that is what the recipe costs. Only four of its faces are stone — the slot it
 * watches through and the light on its back are machinery, drawn once from a pre-darkened sprite so
 * that tinting can never colour the indicator. The rest takes the averaged tint over its six grains
 * with each dimple in one grain's exact colour, like every other block in the family.
 *
 * <p>Watching, the pulse and the scheduled tick are inherited from {@link ObserverBlock} untouched.
 * The block entity is new — an observer has none in vanilla — and is what carries the composition.
 * That would be a problem on a block vanilla refuses to push, but
 * {@link com.tarosie.granularity.mixin.PistonBaseBlockMixin} lifts that refusal for composites and
 * {@link PistonMoves} carries the data across, so a pushed observer keeps its stone.
 */
public class CompositeObserverBlock extends ObserverBlock implements EntityBlock, CompositeStone {

    // B is inferred as ObserverBlock, not our block: ObserverBlock declares codec() as
    // MapCodec<ObserverBlock> and generics are invariant, so a narrower type cannot override it.
    public static final MapCodec<ObserverBlock> CODEC = simpleCodec(CompositeObserverBlock::new);

    /** What the recipe costs, and so what the hammer returns and how many dimples are drawn. */
    public static final int GRAINS = 6;

    public CompositeObserverBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<ObserverBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeBlockEntity(pos, state);
    }

    /** Drops the observer whole, or its six grains under the hammer. */
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
        // Sneak + moss grows on it. An observer has no ordinary click to preserve, but the hand-off
        // stays so that a future one is not silently swallowed.
        net.minecraft.world.ItemInteractionResult result =
                CompositeShapes.interact(stack, state, level, pos, player, hit);
        if (result != net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return result;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /**
     * An observer with moss over its slot sees nothing. See {@link Fouling}.
     *
     * <p>{@code updateShape} is where vanilla notices the block it watches has changed, so refusing
     * to pass it on is refusing to see. Only the *start* of a pulse is blocked — a pulse already
     * scheduled is left to finish, because a half-fired observer would sit powered for ever.
     */
    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction facing,
                                     BlockState facingState, net.minecraft.world.level.LevelAccessor level,
                                     BlockPos currentPos, BlockPos facingPos) {
        if (Fouling.fouled(level, currentPos, state)) {
            return state;
        }
        return super.updateShape(state, facing, facingState, level, currentPos, facingPos);
    }

    /** A piston push rebuilds the block at the far end; this is where its memory catches up. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        PistonMoves.land(level, pos, isMoving);
    }
}
