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
 * Four grains in any 2×2 square become gravel.
 *
 * <p>Shapeless <i>within a square</i>: it does not matter which grain goes where, only that four of
 * them fill one. Because it is 2×2 it works in the player's own inventory grid, so gravel is
 * makeable without a crafting table — which matters, since gravel is the cheap thing you make from
 * whatever is left over.
 *
 * <p>The square is found rather than fixed, so the recipe fires wherever in a 3×3 the player puts
 * it. Nine slots hold four possible 2×2 positions.
 */
public class GravelRecipe extends net.minecraft.world.item.crafting.ShapedRecipe {

    /** Gravel is four grains where cobblestone is nine. */
    public static final int SIZE = 4;

    public GravelRecipe(CraftingBookCategory category) {
        // Declaring the 2x2 pattern is what makes the recipe book place it correctly: vanilla centres
        // a shaped recipe smaller than the grid, and reads the size off the pattern. Matching stays
        // with `matches`, which finds the square anywhere rather than only at the top-left.
        super("", category,
                net.minecraft.world.item.crafting.ShapedRecipePattern.of(
                        java.util.Map.of('#', RecipeDisplay.anyGrain()), "##", "##"),
                RecipeDisplay.sample(GranularityBlocks.GRAVEL.get(), 1));
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return findSquare(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        int[] square = findSquare(input);
        if (square == null) {
            return ItemStack.EMPTY;
        }
        // Four grains fill nine slots by repeating in order: a gravel block is still nine slots,
        // it is just made from four kinds. The repetition keeps the composition well formed
        // without inventing grains that were not supplied.
        int[] ids = new int[Composition.SLOTS];
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            ids[slot] = square[slot % SIZE];
        }
        ItemStack result = new ItemStack(GranularityBlocks.GRAVEL.get());
        result.set(GranularityComponents.COMPOSITION.get(), Composition.of(ids));
        return result;
    }

    /** The four grain ids of a filled 2×2, or null if the grid does not hold exactly one. */
    private static int[] findSquare(CraftingInput input) {
        int width = input.width();
        int height = input.height();
        if (width < 2 || height < 2) {
            return null;
        }
        int[] found = null;
        for (int row = 0; row + 1 < height; row++) {
            for (int col = 0; col + 1 < width; col++) {
                int[] square = squareAt(input, row, col, width);
                if (square == null) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = square;
            }
        }
        if (found == null) {
            return null;
        }
        // Nothing may sit outside the square, or a 3x3 of grains would also match as a gravel.
        int filled = 0;
        for (int slot = 0; slot < width * height; slot++) {
            if (!input.getItem(slot).isEmpty()) {
                filled++;
            }
        }
        return filled == SIZE ? found : null;
    }

    private static int[] squareAt(CraftingInput input, int row, int col, int width) {
        int[] ids = new int[SIZE];
        int index = 0;
        for (int dr = 0; dr < 2; dr++) {
            for (int dc = 0; dc < 2; dc++) {
                int id = CombineRecipe.grainOf(input.getItem((row + dr) * width + col + dc));
                if (id < 0) {
                    return null;
                }
                ids[index++] = id;
            }
        }
        return ids;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GranularityRecipes.GRAVEL_SERIALIZER.get();
    }

    public static class Serializer implements RecipeSerializer<GravelRecipe> {
        private static final MapCodec<GravelRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        CraftingBookCategory.CODEC.fieldOf("category")
                                .orElse(CraftingBookCategory.BUILDING)
                                .forGetter(GravelRecipe::category))
                        .apply(instance, GravelRecipe::new));

        private static final StreamCodec<ByteBuf, GravelRecipe> STREAM_CODEC =
                CraftingBookCategory.STREAM_CODEC.map(GravelRecipe::new, GravelRecipe::category);

        @Override
        public MapCodec<GravelRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, GravelRecipe> streamCodec() {
            return STREAM_CODEC.cast();
        }
    }
}
