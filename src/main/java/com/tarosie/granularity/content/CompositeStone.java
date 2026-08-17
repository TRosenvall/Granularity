package com.tarosie.granularity.content;

/**
 * A block whose material is a composition of grains.
 *
 * <p>A marker, because the family cannot share a superclass: cobblestone extends {@link
 * net.minecraft.world.level.block.Block}, but a slab has to extend {@code SlabBlock}, a stair
 * {@code StairBlock} and a wall {@code WallBlock} to inherit their shapes, placement and connection
 * logic. What they genuinely have in common is not an implementation but a fact — each holds a
 * {@link CompositeBlockEntity} and is made of grains — and an interface is how you say that.
 *
 * <p>It exists because {@link HammerItem} needs to recognise the whole family. Testing for
 * {@code CompositeBlock} instead silently excluded every cut shape, and since all of them set
 * {@code requiresCorrectToolForDrops}, a hammer swung at a slab dropped nothing whatsoever. Anything
 * added later that is made of grains should implement this and be hammerable for free.
 */
public interface CompositeStone {
}
