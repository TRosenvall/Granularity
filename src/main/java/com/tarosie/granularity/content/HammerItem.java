package com.tarosie.granularity.content;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Takes a crafted block apart into the grains it was made from.
 *
 * <p>The counterpart to combining. A pickaxe picks a crafted block up whole — mining a wall you
 * built should give the wall back — and the hammer is how you say "no, I want the pieces".
 *
 * <p>It has to declare itself a correct tool explicitly. Every composite block sets
 * {@code requiresCorrectToolForDrops}, and a plain item is never correct for anything, so without
 * this the hammer would break blocks that yield absolutely nothing — the same trap the pickaxe fell
 * into when its mineable tag was missing.
 *
 * <p>The test is {@link CompositeStone}, not any one class. It used to be {@code CompositeBlock},
 * which silently excluded every cut shape: a slab extends {@code SlabBlock} and a stair
 * {@code StairBlock}, so neither is a {@code CompositeBlock} however composite it is. The hammer was
 * therefore not a correct tool for them, and swinging it at a slab destroyed the slab and dropped
 * nothing at all.
 *
 * <p>Deliberately correct only for <i>crafted</i> blocks. A hammer is not a general mining tool, and
 * it should not let a player skip a pickaxe to quarry natural stone.
 */
public class HammerItem extends Item {

    public HammerItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
        return state.getBlock() instanceof CompositeStone;
    }

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        return state.getBlock() instanceof CompositeStone ? 6.0F : 1.0F;
    }
}
