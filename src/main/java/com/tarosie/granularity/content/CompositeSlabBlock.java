package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.Nullable;

/**
 * A slab that remembers the stone it was cut from.
 *
 * <p>A slab carries the <b>full nine-slot composition</b> of its parent block rather than a share of
 * its grains. Three cobblestones make six slabs, which is exactly three blocks of material — vanilla's
 * ratio already conserves — but twenty-seven grains do not divide into six, and design §12's whole
 * premise is that a grain is indivisible. So a slab is <i>half a block of composition X</i>, not a bag
 * of four and a half grains. Two slabs go back to one block of X, and conservation holds at the level
 * the model actually works at.
 */
public class CompositeSlabBlock extends SlabBlock implements EntityBlock, CompositeStone {

    public CompositeSlabBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeBlockEntity(pos, state);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity entity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        boolean isDouble = state.getValue(TYPE) == SlabType.DOUBLE;

        if (!(entity instanceof CompositeBlockEntity composite)) {
            return List.of(new ItemStack(this, isDouble ? 2 : 1));
        }
        // The hammer takes each half apart on its own terms, so a double made of two stones gives
        // back both. Moss is growth rather than material and simply burns off in the process.
        ItemStack tool = params.getOptionalParameter(LootContextParams.TOOL);
        if (tool != null && tool.is(GranularityItems.HAMMER.get())) {
            // Each half draws separately, so a mixed double is two independent draws rather than one
            // doubled — the same reason its two halves drop as two stacks.
            net.minecraft.util.RandomSource random = params.getLevel().getRandom();
            List<ItemStack> grains = new java.util.ArrayList<>(CompositionDrops.toStacks(
                    composite.composition(), CompositeShapes.SLAB_GRAINS, random));
            if (isDouble) {
                Composition top = composite.upper();
                grains.addAll(CompositionDrops.toStacks(top != null ? top : composite.composition(),
                        CompositeShapes.SLAB_GRAINS, random));
            }
            return grains;
        }
        if (!isDouble) {
            return List.of(half(composite.composition(), composite.overlays(), composite.dyes(),
                    composite.finish(), 1));
        }
        // Two halves, two stacks: a stack of two shares one set of components, so a slate half and
        // a gabbro half cannot travel together however they are counted. Moss counts the same way —
        // a mossy half and a clean one are as different as two stones are.
        Composition upper = composite.upper();
        Coating lowerOverlays = composite.overlays();
        Coating upperOverlays = composite.upperOverlays();
        Dyes lowerDyes = composite.dyes();
        Dyes upperDyes = composite.upperDyes();
        // Dye joins composition and moss in deciding whether the two halves can travel as one stack:
        // a stack of two shares one set of components, so a red half and a grey one cannot.
        // A finish joins composition, moss and dye in deciding whether the halves can travel as one
        // stack: smelting one half of a double leaves two genuinely different slabs.
        if (upper == null && lowerOverlays.equals(upperOverlays) && lowerDyes.equals(upperDyes)
                && composite.finish() == composite.upperFinish()) {
            return List.of(half(composite.composition(), lowerOverlays, lowerDyes,
                    composite.finish(), 2));
        }
        return List.of(
                half(composite.composition(), lowerOverlays, lowerDyes, composite.finish(), 1),
                half(upper != null ? upper : composite.composition(), upperOverlays, upperDyes,
                        composite.upperFinish(), 1));
    }

    private ItemStack half(Composition composition, Coating overlays, Dyes dyes,
                           com.tarosie.granularity.core.Finish finish, int count) {
        ItemStack stack = new ItemStack(this, count);
        Finishes.apply(stack, finish);
        stack.set(GranularityComponents.COMPOSITION.get(), composition);
        Dyes.apply(stack, dyes);
        Moss.apply(stack, overlays);
        return stack;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        Composition incoming = CompositeBlock.compositionOf(stack);
        if (incoming == null || !(level.getBlockEntity(pos) instanceof CompositeBlockEntity composite)) {
            return;
        }
        var type = state.getValue(TYPE);
        // Dye goes in through setSlabHalf with the composition and the moss, not afterwards: stacking
        // a red slab onto a grey one has to fill the free half, and a separate call would have painted
        // both. The same bug the composition already had a fix for.
        composite.setSlabHalf(type == SlabType.DOUBLE, type == SlabType.TOP, incoming,
                Moss.of(stack), Dyes.of(stack), Finishes.of(stack));
    }

    @Override
    public ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        // A double picks up as one slab, so it hands back the lower half's overlays and dye.
        if (level.getBlockEntity(pos) instanceof CompositeBlockEntity composite) {
            stack.set(GranularityComponents.COMPOSITION.get(), composite.composition());
            Dyes.apply(stack, composite.dyes());
            Moss.apply(stack, composite.overlays());
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
