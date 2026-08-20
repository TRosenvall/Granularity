package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.Nullable;

/**
 * A crafted block that remembers what it was made from.
 *
 * <p>The other half of design §2's pillar. Natural blocks are derived and exempt from nothing;
 * <b>placed blocks are exempt from everything</b> — world evolution never touches them. That
 * exemption needs no flag to check because a crafted block is a different registered block from a
 * natural one, and this is that block.
 *
 * <p>It is also the only kind that stores its composition. A cobblestone can be assembled from any
 * nine chunks a player happens to have, so nothing about where it sits implies what it is made of.
 */
public class CompositeBlock extends Block implements EntityBlock, CompositeStone {

    public CompositeBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeBlockEntity(pos, state);
    }

    /**
     * Drops itself, composition intact.
     *
     * <p>Breaking a crafted block returns the block, not its nine constituents — mining a wall you
     * built should give the wall back. Taking it apart again is a crafting operation, not a
     * pickaxe one.
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return wholeBlockDrops(this, params);
    }

    /**
     * Shared with {@link CompositeGravelBlock}, which cannot inherit it.
     *
     * <p>Gravel has to extend {@code FallingBlock} to fall at all, so the two cannot share a
     * superclass — the same bind the block entities are in. Keeping the body here rather than
     * duplicating it is what stops the next component added to a drop reaching one and not the other,
     * which is exactly how the piston head lost its dye.
     */
    public static List<ItemStack> wholeBlockDrops(Block block, LootParams.Builder params) {
        // A costume is handed back whole and separately — see CompositeBlockEntity.transmog. The block
        // itself drops looking like what it is actually made of, because the disguise was never on the
        // item to begin with.
        return CompositeShapes.withCostume(bareWholeBlockDrops(block, params), params);
    }

    private static List<ItemStack> bareWholeBlockDrops(Block block, LootParams.Builder params) {
        BlockEntity entity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);

        // A hammer takes the block apart instead of picking it up. Breaking in the world is the
        // natural place for this: a mixed cobblestone comes apart into up to nine *different*
        // stacks, and a crafting recipe can only produce one result. Dropping them as entities
        // sidesteps that entirely and reuses the same routine natural stone already uses.
        ItemStack tool = params.getOptionalParameter(LootContextParams.TOOL);
        if (tool != null && tool.is(GranularityItems.HAMMER.get())
                && entity instanceof CompositionHolder composite) {
            return CompositionDrops.toStacks(composite.composition());
        }

        ItemStack stack = new ItemStack(block);
        if (entity instanceof CompositionHolder composite) {
            stack.set(GranularityComponents.COMPOSITION.get(), composite.composition());
            Dyes.apply(stack, composite.dyes());
            Moss.apply(stack, composite.overlays());
        }
        if (entity instanceof CompositeBlockEntity composite) {
            // Note the hammer path above never reaches here: taking a block apart returns its grains,
            // and grains have no finish — smelting a cobblestone did not smelt the rocks in it.
            Finishes.apply(stack, composite.finish());
        }
        return List.of(stack);
    }

    /**
     * Carries the stack's composition onto the block entity when placed.
     *
     * <p>Without this a crafted cobblestone would forget what it was made of the moment it left the
     * hand — which is the one thing a crafted block is for.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        applyTo(level, pos, stack);
    }

    /** The composition an item form carries, or null if it has none. */
    @Nullable
    public static Composition compositionOf(ItemStack stack) {
        return stack.get(GranularityComponents.COMPOSITION.get());
    }

    /** Applies a stack's stored composition to the block entity it was just placed as. */
    public static void applyTo(Level level, BlockPos pos, ItemStack stack) {
        Composition composition = compositionOf(stack);
        if (composition == null) {
            return;
        }

        if (level.getBlockEntity(pos) instanceof CompositionHolder composite) {
            composite.setComposition(composition);
            composite.setDyes(false, Dyes.of(stack));
            composite.setOverlays(false, Moss.of(stack));
            composite.setWood(stack.get(GranularityComponents.WOOD.get()));
            composite.setMetal(stack.get(GranularityComponents.METAL.get()));
        }
        // Separately, because a finish is not composition data and not every composite carries one.
        if (level.getBlockEntity(pos) instanceof CompositeBlockEntity composite) {
            composite.setFinish(Finishes.of(stack));
            // A second stone, for a block built from two at once — see UPPER_COMPOSITION. Set only
            // when the stack has one, so this cannot clear the upper half of a double slab, which
            // gets its second composition from setSlabHalf rather than from an item.
            Composition second = stack.get(GranularityComponents.UPPER_COMPOSITION.get());
            if (second != null) {
                composite.setUpper(second);
            }
        }
    }

    @Override
    public ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof CompositionHolder composite) {
            stack.set(GranularityComponents.COMPOSITION.get(), composite.composition());
            Dyes.apply(stack, composite.dyes());
            Moss.apply(stack, composite.overlays());
        }
        if (level.getBlockEntity(pos) instanceof CompositeBlockEntity composite) {
            Finishes.apply(stack, composite.finish());
        }
        return stack;
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
