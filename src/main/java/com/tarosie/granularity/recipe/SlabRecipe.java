package com.tarosie.granularity.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tarosie.granularity.content.GranularityBlocks;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.core.Composition;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Three cobblestones in a row become six slabs of the same stone.
 *
 * <p>Vanilla's ratio, unchanged — and it already conserves: six halves are three blocks. What it
 * cannot do is divide twenty-seven grains among six slabs, so the slabs do not carry a share of the
 * grains. Each carries the whole composition, and two of them recombine into one block of it.
 *
 * <p>All six come out identical, which is not a compromise but a requirement: an {@link ItemStack}
 * of six shares one set of components, so six differently-composed slabs could not exist in one
 * stack however the arithmetic were arranged.
 *
 * <p>Where the three inputs disagree, their compositions merge — the first block's slots win for
 * each position, which keeps the result a real composition rather than an average of ids.
 */
public class SlabRecipe extends net.minecraft.world.item.crafting.ShapedRecipe {

    /** Package-private rather than private so ConservationTest can check the ratio it implies. */
    static final int INPUTS = 3;
    static final int OUTPUT = 6;

    public SlabRecipe(CraftingBookCategory category) {
        // The pattern is declared for the recipe book's sake -- see RecipeDisplay. Matching is done
        // by `matches` below, which also has to check the composition component the pattern cannot.
        super("", category,
                net.minecraft.world.item.crafting.ShapedRecipePattern.of(
                        java.util.Map.of('#', RecipeDisplay.cobblestone()), "###"),
                RecipeDisplay.sample(GranularityBlocks.COBBLESTONE_SLAB.get(), OUTPUT));
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return firstOf(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack first = firstOf(input);
        if (first == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = new ItemStack(GranularityBlocks.COBBLESTONE_SLAB.get(), OUTPUT);
        // Pooled across all three, not taken from the first — see Composition.pooled for the
        // laundering hole that closes. Twenty-seven grains in, six slabs of four grains out.
        java.util.List<com.tarosie.granularity.core.Composition> pool = new java.util.ArrayList<>();
        for (int slot = 0; slot < INPUTS; slot++) {
            com.tarosie.granularity.core.Composition composition =
                    input.getItem(slot).get(GranularityComponents.COMPOSITION.get());
            if (composition != null) {
                pool.add(composition);
            }
        }
        result.set(GranularityComponents.COMPOSITION.get(),
                com.tarosie.granularity.core.Composition.pooled(pool));
        // Mossy stone cuts into mossy slabs. Moss is a flag on the same block, so this is one
        // recipe rather than the second one a separate mossy block would have needed.
        com.tarosie.granularity.content.Moss.apply(result,
                com.tarosie.granularity.content.Moss.of(first));
        // Cutting is not a furnace: smooth stone cuts into smooth slabs and cobbled into cobbled.
        com.tarosie.granularity.content.Finishes.apply(result,
                com.tarosie.granularity.content.Finishes.of(first));
        return result;
    }

    /** The first of three cobblestones in a row, or null if that is not what is there. */
    private static ItemStack firstOf(CraftingInput input) {
        if (input.width() != INPUTS || input.height() != 1) {
            return null;
        }
        ItemStack first = null;
        for (int slot = 0; slot < INPUTS; slot++) {
            ItemStack stack = input.getItem(slot);
            // Any finish cuts, but all three must agree and the finish rides through to the slabs —
            // see CompositeIngredient: a match that ignores the finish has to carry it forward, or
            // cutting is a free way to turn smooth stone into cobbled slabs and back again.
            if (!CompositeIngredient.any(stack, GranularityBlocks.COBBLESTONE.get())) {
                return null;
            }
            if (first != null && com.tarosie.granularity.content.Finishes.of(stack)
                    != com.tarosie.granularity.content.Finishes.of(first)) {
                return null;
            }
            Composition composition = stack.get(GranularityComponents.COMPOSITION.get());
            if (composition == null) {
                return null;
            }
            if (first == null) {
                first = stack;
            }
        }
        return first;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GranularityRecipes.SLAB_SERIALIZER.get();
    }

    public static class Serializer implements RecipeSerializer<SlabRecipe> {
        private static final MapCodec<SlabRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(CraftingBookCategory.CODEC.fieldOf("category")
                        .orElse(CraftingBookCategory.BUILDING).forGetter(SlabRecipe::category))
                        .apply(instance, SlabRecipe::new));

        private static final StreamCodec<ByteBuf, SlabRecipe> STREAM_CODEC =
                CraftingBookCategory.STREAM_CODEC.map(SlabRecipe::new, SlabRecipe::category);

        @Override
        public MapCodec<SlabRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, SlabRecipe> streamCodec() {
            return STREAM_CODEC.cast();
        }
    }
}
