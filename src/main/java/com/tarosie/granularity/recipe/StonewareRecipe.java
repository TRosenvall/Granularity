package com.tarosie.granularity.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.core.Composition;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;

/**
 * Anything built out of grains that keeps a memory of which grains.
 *
 * <p>A shaped recipe in every respect but one: the pattern, the key and the result all come from
 * JSON as usual, and then the grains actually placed in the grid are recorded onto the result. A
 * lever built from slate is a slate lever. One class covers the whole family of vanilla blocks being
 * rebuilt on grains, because they differ only in shape and yield.
 *
 * <p>Vanilla's own matching does the work, so these appear in the recipe book, auto-fill correctly
 * and need no {@code isSpecial}. Only {@link #assemble} is ours.
 */
public class StonewareRecipe extends ShapedRecipe {

    public StonewareRecipe(String group, CraftingBookCategory category,
                           ShapedRecipePattern pattern, ItemStack result) {
        super(group, category, pattern, result);
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = super.assemble(input, registries);
        Composition composition = compositionOf(input);
        if (composition != null) {
            result.set(GranularityComponents.COMPOSITION.get(), composition);
        }
        net.minecraft.resources.ResourceLocation wood = woodOf(input);
        if (wood != null) {
            result.set(GranularityComponents.WOOD.get(), wood);
        }
        net.minecraft.resources.ResourceLocation metal = firstIn(
                input, net.neoforged.neoforge.common.Tags.Items.INGOTS);
        if (metal != null) {
            result.set(GranularityComponents.METAL.get(), metal);
        }
        return result;
    }

    /**
     * The timber that went into the grid, for the one recipe here that takes any.
     *
     * <p>A piston's plate is wood, and which wood should show — the same argument that makes a slate
     * cobblestone dark. Written generically rather than as a piston special case because the test
     * costs one tag lookup and every other recipe in the family simply finds no planks.
     *
     * <p>The <b>first</b> plank placed wins where they differ. Three planks could in principle be
     * three woods, but there is one plate to colour and no honest way to average oak with crimson;
     * picking the first is at least predictable, and matching planks is what anyone actually does.
     *
     * <p>Logs count too, and are asked second. A piston's plate is sawn timber and takes planks; a
     * stonecutter's frame is a piece of the tree, and its recipe says so. Planks first so that a
     * recipe taking both is answered by the worked timber, which is the one a plate would be made of.
     */
    private static net.minecraft.resources.ResourceLocation woodOf(CraftingInput input) {
        net.minecraft.resources.ResourceLocation planks =
                firstIn(input, net.minecraft.tags.ItemTags.PLANKS);
        return planks != null ? planks : firstIn(input, net.minecraft.tags.ItemTags.LOGS);
    }

    /**
     * The id of the first item in the grid belonging to a tag, or null if none does.
     *
     * <p>Shared by the timber and the metal, which ask the same question of different tags. Reading
     * the *placed* item rather than the recipe's ingredient is the point: an ingredient says what
     * was allowed, and what a block is made of is what was actually put in.
     */
    private static net.minecraft.resources.ResourceLocation firstIn(
            CraftingInput input, net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag) {
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.is(tag)) {
                return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            }
        }
        return null;
    }

    /**
     * The nine slots built from however many grains the recipe asked for.
     *
     * <p>A lever takes one grain and a dispenser seven, but a block is always nine slots — so the
     * grains repeat in order to fill it, the same rule {@link GravelRecipe} uses for its four. That
     * keeps the composition well formed without inventing material that was never supplied, and a
     * thing made of one grain comes out uniformly that grain.
     *
     * <p>Chunks are looked for <b>first</b> and answer alone when any are found. A recipe built from
     * chunks knows exactly which grain sits in which slot, and slot order is not decoration — it is
     * what decides which dimple on the finished block shows which stone. Pooling would preserve the
     * mix and shuffle the order, so the two paths stay separate rather than merged into one that is
     * subtly wrong for the six blocks that already work.
     */
    private static Composition compositionOf(CraftingInput input) {
        List<Integer> grains = new ArrayList<>();
        for (int slot = 0; slot < input.size(); slot++) {
            int grain = CombineRecipe.grainOf(input.getItem(slot));
            if (grain >= 0) {
                grains.add(grain);
            }
        }
        if (grains.isEmpty()) {
            return pooledBlocks(input);
        }
        int[] ids = new int[Composition.SLOTS];
        for (int slot = 0; slot < ids.length; slot++) {
            ids[slot] = grains.get(slot % grains.size());
        }
        return Composition.of(ids);
    }

    /**
     * The nine slots of a thing built out of whole blocks rather than out of chunks.
     *
     * <p>The stonecutter is the first of these and the reason this exists: it is built from
     * <i>smooth</i> stone, and smooth is a finish, which lives on a block — there is no such thing as
     * a smooth chunk. So its stone arrives as three compositions of nine rather than as three grains.
     *
     * <p>{@link Composition#pooled} rather than "take the first block's" for the reason recorded
     * there: taking the first is a laundering hole, letting one marble block beside two granite ones
     * hand back a wholly marble result. Pooling makes what comes out the mix that actually went in.
     */
    private static Composition pooledBlocks(CraftingInput input) {
        List<Composition> blocks = new ArrayList<>();
        for (int slot = 0; slot < input.size(); slot++) {
            Composition composition =
                    input.getItem(slot).get(GranularityComponents.COMPOSITION.get());
            if (composition != null) {
                blocks.add(composition);
            }
        }
        return blocks.isEmpty() ? null : Composition.pooled(blocks);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GranularityRecipes.STONEWARE_SERIALIZER.get();
    }

    public static class Serializer implements RecipeSerializer<StonewareRecipe> {

        private static final MapCodec<StonewareRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        com.mojang.serialization.Codec.STRING.optionalFieldOf("group", "")
                                .forGetter(StonewareRecipe::getGroup),
                        CraftingBookCategory.CODEC.fieldOf("category")
                                .orElse(CraftingBookCategory.MISC).forGetter(StonewareRecipe::category),
                        ShapedRecipePattern.MAP_CODEC.forGetter(recipe -> recipe.pattern),
                        ItemStack.STRICT_CODEC.fieldOf("result")
                                .forGetter(recipe -> recipe.getResultItem(null)))
                        .apply(instance, StonewareRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, StonewareRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8, StonewareRecipe::getGroup,
                        CraftingBookCategory.STREAM_CODEC, StonewareRecipe::category,
                        ShapedRecipePattern.STREAM_CODEC, recipe -> recipe.pattern,
                        ItemStack.STREAM_CODEC, recipe -> recipe.getResultItem(null),
                        StonewareRecipe::new);

        @Override
        public MapCodec<StonewareRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, StonewareRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
