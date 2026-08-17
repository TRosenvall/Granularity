package com.tarosie.granularity.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tarosie.granularity.content.CompositeBlockItem;
import com.tarosie.granularity.content.Finishes;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.Finish;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.Level;

/**
 * Working a stonework style onto stone in the <b>crafting grid</b> rather than at a stonecutter.
 *
 * <p>Bricks are the case that wanted this: four smooth blocks in a square become four brick blocks,
 * which is vanilla's own stone-brick recipe and the gesture everybody already knows. A stonecutter can
 * reach the same style, and should — this is a second door, not a replacement, exactly as vanilla lets
 * you cut stairs or craft them.
 *
 * <h2>Why it is not a stonecutter cut with a different pattern</h2>
 * A stonecutter takes <b>one</b> item, so a cut can only ever carry one block's composition through
 * unchanged. A crafting grid takes several, and four blocks of different stone are a real question the
 * stonecutter never has to answer. {@link Composition#pooled} answers it the way every other
 * multi-block recipe here does: the result carries the mix that actually went in, so three marble and
 * one granite give back blocks that are mostly marble rather than four of whichever happened to sit in
 * the first slot. That was a laundering hole once already; see the note on {@code pooled}.
 *
 * <h2>Conservation</h2>
 * Four blocks in, four out, same form — break-even by construction, and the count is checked against
 * the pattern by {@code ConservationTest} rather than trusted. A recipe of this type that yielded five
 * would be a press.
 *
 * <h2>The finish is stated on both sides</h2>
 * {@code from} and {@code to} are both required, and every composite in the grid must be wearing
 * {@code from}. This is {@link CompositeIngredient}'s doctrine again: a finish is data on the item, so
 * a JSON ingredient naming {@code granularity:cobblestone} matches cobbled, smooth and every style
 * alike, and a recipe that failed to say which it meant would quietly accept rubble and hand back
 * brickwork.
 */
public class ApplyStyleRecipe extends ShapedRecipe {

    private final Finish from;
    private final Finish to;

    public ApplyStyleRecipe(String group, CraftingBookCategory category,
                            ShapedRecipePattern pattern, ItemStack result, Finish from, Finish to) {
        super(group, category, pattern, result);
        this.from = from;
        this.to = to;
        // The book draws getResultItem(), and a finish lives on the stack, so the sample has to be
        // worked by hand or every one of these previews as plain cobblestone.
        Finishes.apply(getResultItem(null), to);
    }

    public Finish from() {
        return from;
    }

    public Finish to() {
        return to;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (!super.matches(input, level)) {
            return false;
        }
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.getItem() instanceof CompositeBlockItem && Finishes.of(stack) != from) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = super.assemble(input, registries);
        List<Composition> stones = new ArrayList<>();
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            Composition composition = stack.get(GranularityComponents.COMPOSITION.get());
            if (stack.getItem() instanceof CompositeBlockItem && composition != null) {
                stones.add(composition);
            }
        }
        if (!stones.isEmpty()) {
            result.set(GranularityComponents.COMPOSITION.get(), Composition.pooled(stones));
        }
        Finishes.apply(result, to);
        // Moss and dye are deliberately not carried: working the surface removes the face that was
        // grown on or painted, the same reasoning the furnace and the stonecutter use.
        return result;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GranularityRecipes.APPLY_STYLE_SERIALIZER.get();
    }

    public static class Serializer implements RecipeSerializer<ApplyStyleRecipe> {

        private static final MapCodec<ApplyStyleRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        com.mojang.serialization.Codec.STRING.optionalFieldOf("group", "")
                                .forGetter(ApplyStyleRecipe::getGroup),
                        CraftingBookCategory.CODEC.fieldOf("category")
                                .orElse(CraftingBookCategory.BUILDING)
                                .forGetter(ApplyStyleRecipe::category),
                        ShapedRecipePattern.MAP_CODEC.forGetter(recipe -> recipe.pattern),
                        ItemStack.STRICT_CODEC.fieldOf("result")
                                .forGetter(recipe -> recipe.getResultItem(null)),
                        // Strict on both: a mistyped style would read as COBBLED, and a recipe that
                        // accepted rubble and returned rubble is worse than one that fails to load.
                        Finishes.STRICT_CODEC.fieldOf("from").forGetter(ApplyStyleRecipe::from),
                        Finishes.STRICT_CODEC.fieldOf("to").forGetter(ApplyStyleRecipe::to))
                        .apply(instance, ApplyStyleRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, ApplyStyleRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, ApplyStyleRecipe::getGroup,
                        CraftingBookCategory.STREAM_CODEC, ApplyStyleRecipe::category,
                        ShapedRecipePattern.STREAM_CODEC, recipe -> recipe.pattern,
                        ItemStack.STREAM_CODEC, recipe -> recipe.getResultItem(null),
                        Finishes.STREAM_CODEC.cast(), ApplyStyleRecipe::from,
                        Finishes.STREAM_CODEC.cast(), ApplyStyleRecipe::to,
                        ApplyStyleRecipe::new);

        @Override
        public MapCodec<ApplyStyleRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, ApplyStyleRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
