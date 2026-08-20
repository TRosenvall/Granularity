package com.tarosie.granularity.content;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

/**
 * The head a Granularity piston pushes out, cut from the same stone as the piston.
 *
 * <p>Vanilla's head is a block in its own right, and it has to be: it stands one position in front of
 * the piston and is placed and removed independently. Ours has to be a block of its own for the same
 * reason, and it needs a block entity for the reason every composite does — a tint has to come from
 * somewhere, and {@code ModelData} comes only from block entities.
 *
 * <p>It does not own that composition, though. A head is part of the piston that extended it, so it
 * <b>copies</b> the piston's stone when it lands and never stores anything a player put there. That
 * is what makes a slate piston extend a slate arm rather than a grey one.
 *
 * <p>It is a {@link CompositeStone} for one reason: that marker is what
 * {@code GranularityModels.wrapForOverlays} keys on, and without it a mossy piston would push out a
 * clean arm. The marker's other consequences are all inert here — the hammer becomes a correct tool
 * for it, which matters not at all when {@link #getDrops} is empty and breaking the head destroys the
 * piston anyway, and the pushability mixin never gets a look in because a head's push reaction is
 * {@code BLOCK}. Nothing wires {@code CompositeShapes.interact} to it, so moss cannot be grown on an
 * arm directly; it only ever inherits what the piston is wearing.
 */
public class CompositePistonHeadBlock extends PistonHeadBlock implements EntityBlock, CompositeStone {

    // Declared at the parent's type, since PistonHeadBlock narrows codec() and generics are
    // invariant — the same constraint the observer and the piston base run into.
    public static final MapCodec<PistonHeadBlock> CODEC = simpleCodec(CompositePistonHeadBlock::new);

    public CompositePistonHeadBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<PistonHeadBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeBlockEntity(pos, state);
    }

    /**
     * A head is not a thing you get: it is the piston, extended.
     *
     * <p>Vanilla's head drops nothing either — {@code onRemove} destroys the base and lets <i>that</i>
     * drop the piston — and returning an empty list keeps that true rather than handing out a second
     * piston's worth of grains for free.
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of();
    }

    /** Middle-clicking the arm should hand you a piston, and one that remembers its stone. */
    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = new ItemStack(GranularityItems.PISTON.get());
        BlockPos base = pos.relative(state.getValue(FACING).getOpposite());
        if (level.getBlockEntity(base) instanceof CompositionHolder piston) {
            stack.set(GranularityComponents.COMPOSITION.get(), piston.composition());
            Dyes.apply(stack, piston.dyes());
            if (piston.wood() != null) {
                stack.set(GranularityComponents.WOOD.get(), piston.wood());
            }
            if (piston.metal() != null) {
                stack.set(GranularityComponents.METAL.get(), piston.metal());
            }
            Moss.apply(stack, piston.overlays());
        }
        return stack;
    }

    /**
     * Takes the piston's stone the moment the head lands.
     *
     * <p>{@code onPlace} is the first instant this block exists, and the piston behind it is already
     * standing and extended by then — {@code moveBlocks} puts the head in motion before the base is
     * switched, and the head only arrives two ticks later. So the base is always there to be read.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (level.isClientSide) {
            return;
        }
        BlockPos base = pos.relative(state.getValue(FACING).getOpposite());
        if (level.getBlockEntity(base) instanceof CompositionHolder piston
                && level.getBlockEntity(pos) instanceof CompositionHolder head) {
            head.setComposition(piston.composition());
            head.setDyes(false, piston.dyes());
            head.setOverlays(false, piston.overlays());
            // The plate on the head is the same timber as the plate on the piston.
            head.setWood(piston.wood());
            head.setMetal(piston.metal());
            // And it wears what the piston wears. Everything else about a head is inherited here, so
            // a costume left out would mean an extended piston shedding its disguise halfway along
            // its own arm — and only while extended, which is the hardest kind of wrong to catch.
            head.setCostumes(piston.costumes());
        }
    }
}
