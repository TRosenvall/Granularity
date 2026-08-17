package com.tarosie.granularity.content;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

/**
 * A furnace built from eight grains, which shows all eight.
 *
 * <p>Its dimpling is split into eight regions rather than cobblestone's nine, because eight is what
 * the recipe costs — see {@code tools/extract_stoneware.py} for how those regions were found. The
 * block as a whole takes the averaged tint; each dimple takes one grain's exact colour.
 *
 * <p>Smelting, fuel and the menu are inherited from {@link AbstractFurnaceBlock} untouched. All this
 * adds is the memory of what it was made of, and — by being a {@link CompositeStone} — the ability to
 * grow moss and to be shoved by a piston.
 */
public class CompositeFurnaceBlock extends AbstractFurnaceBlock implements CompositeStone {

    public static final MapCodec<CompositeFurnaceBlock> CODEC = simpleCodec(CompositeFurnaceBlock::new);

    public CompositeFurnaceBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends AbstractFurnaceBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeFurnaceBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // Moss over the door does *not* stop the smelt. A furnace that is burning is burning, and
        // the fire is behind the door rather than through it — what moss takes away is your reach,
        // not the heat. So the ticker is left alone and only openContainer is barred.
        return createFurnaceTicker(level, type, GranularityBlocks.COMPOSITE_FURNACE_ENTITY.get());
    }

    /** A door with moss over it does not open. See {@link Fouling}. */
    @Override
    protected void openContainer(Level level, BlockPos pos, Player player) {
        if (Fouling.fouled(level, pos, level.getBlockState(pos))) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof CompositeFurnaceBlockEntity furnace) {
            player.openMenu(furnace);
            player.awardStat(net.minecraft.stats.Stats.INTERACT_WITH_FURNACE);
        }
    }

    /**
     * Drops the furnace whole, or its eight grains under the hammer.
     *
     * <p>Eight rather than nine: a furnace cost eight grains, so eight is what it gives back. The
     * ninth composition slot is a repeat the recipe made to fill the block out, and returning it
     * would be inventing material.
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return CompositeShapes.drops(this, state, params, 1, GRAINS);
    }

    /** What the recipe costs, and so what the hammer returns and how many dimples are drawn. */
    public static final int GRAINS = 8;

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
            ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        // Sneak + moss grows on it; an ordinary click still opens the furnace.
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
