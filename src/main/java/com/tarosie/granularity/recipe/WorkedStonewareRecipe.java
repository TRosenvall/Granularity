package com.tarosie.granularity.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tarosie.granularity.content.CompositeBlockItem;
import com.tarosie.granularity.content.Finishes;
import com.tarosie.granularity.core.Finish;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.Level;

/**
 * Stoneware built from stone that has been <b>worked</b> to a particular finish.
 *
 * <p>A {@link StonewareRecipe} in every respect but the extra condition, and it exists because of the
 * problem {@link CompositeIngredient} was written to make impossible. A finish is data on the item
 * now, so cobbled stone, smooth stone and every stonework style share one registry entry: a JSON
 * ingredient naming {@code granularity:cobblestone} matches all thirteen of them. A recipe that means
 * smooth stone and only smooth stone cannot say so in the ingredient at all.
 *
 * <p>So it says so here. {@code finish} is a required field of the type — there is no default and no
 * "any" — and every composite placed in the grid has to carry it. The stonecutter is the first recipe
 * to need this: it is a bench of <i>worked</i> stone, and building one out of rubble would say the
 * opposite of what the block looks like.
 *
 * <p>The check runs over the grid rather than over the ingredients on purpose, and over
 * <i>composites</i> rather than over a named block: what a thing is made of is what was actually put
 * in, and a future recipe mixing two composite forms should not have to restate the rule per slot.
 * Non-composites — the metal bar — are ignored, having no finish to have.
 */
public class WorkedStonewareRecipe extends StonewareRecipe {

    private final Finish finish;

    public WorkedStonewareRecipe(String group, CraftingBookCategory category,
                                 ShapedRecipePattern pattern, ItemStack result, Finish finish) {
        super(group, category, pattern, result);
        this.finish = finish;
    }

    public Finish finish() {
        return finish;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (!super.matches(input, level)) {
            return false;
        }
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.getItem() instanceof CompositeBlockItem && Finishes.of(stack) != finish) {
                return false;
            }
        }
        return true;
    }

    /**
     * As {@link StonewareRecipe}, and then the <b>second</b> stone if the grid holds one.
     *
     * <p>A stonecutter is built from two blocks of worked stone and shows both: the bench is divided
     * by a wooden rail, and the recipe places one stone either side of the log that becomes that
     * rail. So which block went where has to survive into the result, and pooling — which is what the
     * parent does with several blocks — would average exactly the distinction the block exists to
     * show.
     *
     * <p>Position decides it, in reading order: the <b>first</b> composite in the grid is the block's
     * stone and the <b>second</b> is its second stone. For the stonecutter's {@code "#L#"} row that is
     * left and right, which is also how the finished block reads — left is the body below the rail,
     * right is the strip above it and the working surface on top. A recipe with only one composite in
     * it records no second stone and is untouched by any of this.
     *
     * <p>Note that this <b>overwrites</b> the composition the parent worked out, and has to. Given
     * several blocks {@link StonewareRecipe} pools them, which is right when the result has one stone
     * and averages away the whole distinction when it has two. Pooling stays the default because it
     * is the safe answer for a block that cannot show where its material came from; keeping both is
     * strictly more information, and only a block drawn in two parts can use it.
     */
    @Override
    public ItemStack assemble(CraftingInput input, net.minecraft.core.HolderLookup.Provider registries) {
        ItemStack result = super.assemble(input, registries);
        java.util.List<com.tarosie.granularity.core.Composition> stones = compositesIn(input);
        if (stones.size() >= 2) {
            result.set(com.tarosie.granularity.content.GranularityComponents.COMPOSITION.get(),
                    stones.get(0));
            result.set(com.tarosie.granularity.content.GranularityComponents.UPPER_COMPOSITION.get(),
                    stones.get(1));
        }
        return result;
    }

    /** Every composite block in the grid, in reading order. */
    private static java.util.List<com.tarosie.granularity.core.Composition> compositesIn(
            CraftingInput input) {
        java.util.List<com.tarosie.granularity.core.Composition> stones = new java.util.ArrayList<>(2);
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            com.tarosie.granularity.core.Composition composition = stack
                    .get(com.tarosie.granularity.content.GranularityComponents.COMPOSITION.get());
            // Guarded on the item, not merely on the component, so a stray ingredient that happens to
            // carry a composition cannot be read as one of the block's stones.
            if (stack.getItem() instanceof CompositeBlockItem && composition != null) {
                stones.add(composition);
            }
        }
        return stones;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GranularityRecipes.WORKED_STONEWARE_SERIALIZER.get();
    }

    public static class Serializer implements RecipeSerializer<WorkedStonewareRecipe> {

        private static final MapCodec<WorkedStonewareRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        com.mojang.serialization.Codec.STRING.optionalFieldOf("group", "")
                                .forGetter(WorkedStonewareRecipe::getGroup),
                        CraftingBookCategory.CODEC.fieldOf("category")
                                .orElse(CraftingBookCategory.MISC)
                                .forGetter(WorkedStonewareRecipe::category),
                        ShapedRecipePattern.MAP_CODEC.forGetter(recipe -> recipe.pattern),
                        ItemStack.STRICT_CODEC.fieldOf("result")
                                .forGetter(recipe -> recipe.getResultItem(null)),
                        // No orElse: a recipe of this type that has not said which finish it means is
                        // a recipe that has not thought about finishes, and should fail to load rather
                        // than quietly accept rubble.
                        Finishes.STRICT_CODEC.fieldOf("finish")
                                .forGetter(WorkedStonewareRecipe::finish))
                        .apply(instance, WorkedStonewareRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, WorkedStonewareRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, WorkedStonewareRecipe::getGroup,
                        CraftingBookCategory.STREAM_CODEC, WorkedStonewareRecipe::category,
                        ShapedRecipePattern.STREAM_CODEC, recipe -> recipe.pattern,
                        ItemStack.STREAM_CODEC, recipe -> recipe.getResultItem(null),
                        Finishes.STREAM_CODEC.cast(), WorkedStonewareRecipe::finish,
                        WorkedStonewareRecipe::new);

        @Override
        public MapCodec<WorkedStonewareRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, WorkedStonewareRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
