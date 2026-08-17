package com.tarosie.granularity.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tarosie.granularity.content.GranularityBlocks;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.Grain;
import com.tarosie.granularity.core.Grains;
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
 * Nine grains in a 3×3 become a cobblestone that remembers all nine.
 *
 * <p>Design §2: "Combining 9 rock chunks crafts a cobblestone block." §3 adds the part that makes it
 * interesting — a cobblestone of mixed chunks "shows the constituent colours as distinct rocks".
 *
 * <p>It has to be a coded recipe rather than a JSON one because <b>the output depends on the
 * inputs</b>. There is no fixed result to declare: nine granite chunks and nine marble chunks make
 * different cobblestones, and five-and-four makes a third thing again. A shaped JSON recipe can only
 * name a constant.
 *
 * <p>The grid is read in order, so the composition records exactly what was placed. That is worth
 * more than it sounds: a player who wants a particular mix builds it slot by slot, and the block
 * remembers the arrangement rather than a summary of it.
 */
public class CombineRecipe extends net.minecraft.world.item.crafting.ShapedRecipe {

    public CombineRecipe(CraftingBookCategory category) {
        // A declared pattern is what puts this in the recipe book: see RecipeDisplay. The pattern is
        // never consulted for matching -- `matches` below reads the grid itself, because what makes
        // a grain valid is the grain roster, not a symbol table.
        super("", category,
                net.minecraft.world.item.crafting.ShapedRecipePattern.of(
                        java.util.Map.of('#', RecipeDisplay.anyGrain()), "###", "###", "###"),
                RecipeDisplay.sample(GranularityBlocks.COBBLESTONE.get(), 1));
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (input.width() != 3 || input.height() != 3) {
            return false;
        }
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            if (grainOf(input.getItem(slot)) < 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        int[] ids = new int[Composition.SLOTS];
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            int id = grainOf(input.getItem(slot));
            if (id < 0) {
                return ItemStack.EMPTY;
            }
            ids[slot] = id;
        }
        ItemStack result = new ItemStack(GranularityBlocks.COBBLESTONE.get());
        result.set(GranularityComponents.COMPOSITION.get(), Composition.of(ids));
        return result;
    }

    /** The grain a stack carries, or -1 if it is not a grain at all. */
    static int grainOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }
        // Look the grain up by the item itself. A stack of raw iron is the iron grain; there is no
        // component to check and nothing that can fall out of sync.
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) {
            return -1;
        }
        Grain grain = Grains.byItem(key.toString());
        return grain == null ? -1 : grain.id();
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GranularityRecipes.COMBINE_SERIALIZER.get();
    }

    public static class Serializer implements RecipeSerializer<CombineRecipe> {
        private static final MapCodec<CombineRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        CraftingBookCategory.CODEC.fieldOf("category")
                                .orElse(CraftingBookCategory.BUILDING)
                                .forGetter(CombineRecipe::category))
                        .apply(instance, CombineRecipe::new));

        private static final StreamCodec<ByteBuf, CombineRecipe> STREAM_CODEC =
                CraftingBookCategory.STREAM_CODEC.map(CombineRecipe::new, CombineRecipe::category);

        @Override
        public MapCodec<CombineRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CombineRecipe> streamCodec() {
            return STREAM_CODEC.cast();
        }
    }
}
