package com.tarosie.granularity.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tarosie.granularity.content.GranularityBlocks;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.core.Composition;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;

/**
 * Smelting a cobblestone averages its colours into one.
 *
 * <p>Design §3, verbatim: <i>"smelting averages the colors into a single color — the natural route
 * to gradients and texture in common building blocks."</i> This is the recipe that makes the whole
 * building-material system work. There is no dye step and no palette to unlock: you mine across a
 * region boundary, smelt in batches, and the shades you get are arithmetic on what you dug.
 *
 * <p>Like {@link CombineRecipe} it must be coded, because the output depends on the input. Unlike it,
 * the averaging is not merely bookkeeping — it is the same arithmetic {@code LatticeColour.average}
 * already applies to natural stone's rock slots, which is why a smelted block and the stone it came
 * from agree on colour.
 *
 * <p>The averaging itself is a rendering property, not a transformation: {@link Composition} keeps
 * all nine grains and {@code averageTint} computes the colour on demand. An earlier version rewrote
 * the grains to the nearest named stone, which turned five chalk and four shale into "nine diorite"
 * — nearness is not identity.
 */
public class SmeltAverageRecipe extends SmeltingRecipe {

    public SmeltAverageRecipe(String group, CookingBookCategory category, float experience, int cookingTime) {
        super(group, category,
                net.minecraft.world.item.crafting.Ingredient.of(GranularityBlocks.COBBLESTONE.get()),
                smoothStone(), experience, cookingTime);
    }

    @Override
    public boolean matches(SingleRecipeInput input, Level level) {
        // Only unworked stone goes in the furnace; smelting an already-smooth block does nothing.
        return CompositeIngredient.matches(input.item(), GranularityBlocks.COBBLESTONE.get(),
                com.tarosie.granularity.core.Finish.COBBLED);
    }

    @Override
    public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider registries) {
        Composition composition = input.item().get(GranularityComponents.COMPOSITION.get());
        if (composition == null) {
            return smoothStone();
        }
        ItemStack result = new ItemStack(outcomeFor(composition));
        // Smooth is a finish now, not a block. Applied only to the stone outcome: an ore block and an
        // alloy block are their own blocks and read no finish, and giving them a component they never
        // consult would split their stacks for nothing.
        if (result.is(GranularityBlocks.COBBLESTONE.get().asItem())) {
            com.tarosie.granularity.content.Finishes.apply(
                    result, com.tarosie.granularity.core.Finish.SMOOTH);
        }
        // The grains are carried across untouched. Smelting changes what the block *is*, not what
        // it is made of -- which is what keeps a smelted block recombinable, and what stops a
        // chalk-and-shale mixture from being renamed to whatever stone its average happens to
        // resemble.
        result.set(GranularityComponents.COMPOSITION.get(), composition);
        // Composition and nothing else, deliberately. Moss is growth rather than material and burns
        // off, the same way it does under the hammer; dye is pigment on the surface and does not
        // survive a furnace either. A mossy red slate cobblestone therefore comes out as plain
        // smooth slate, which is what firing something means.
        //
        // Worth stating because it is easy to mistake for an oversight and "fix" by copying the
        // components across -- the block keeps its grains, so the temptation is to keep everything.
        return result;
    }

    /**
     * Which block a composition smelts into.
     *
     * <ul>
     *   <li>all rock — <b>smooth stone</b>, rendered as one averaged colour</li>
     *   <li>rock with a mineral in it — <b>ore block</b>, which looks like the ore block that was
     *       mined to get the grains in the first place, so the material system round-trips</li>
     *   <li>all mineral, no rock — <b>alloy block</b>, also averaged</li>
     * </ul>
     */
    public static net.minecraft.world.level.block.Block outcomeFor(Composition composition) {
        if (composition.isAllMineral()) {
            return GranularityBlocks.ALLOY_BLOCK.get();
        }
        if (composition.hasMineralInclusion()) {
            return GranularityBlocks.ORE_BLOCK.get();
        }
        // The same block as cobblestone, worked. See Finish for why this is data and not a block.
        return GranularityBlocks.COBBLESTONE.get();
    }

    /** A cobblestone that has been through a furnace — what "smooth stone" now is. */
    private static ItemStack smoothStone() {
        ItemStack stack = new ItemStack(GranularityBlocks.COBBLESTONE.get());
        com.tarosie.granularity.content.Finishes.apply(
                stack, com.tarosie.granularity.core.Finish.SMOOTH);
        return stack;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GranularityRecipes.SMELT_SERIALIZER.get();
    }

    public static class Serializer implements RecipeSerializer<SmeltAverageRecipe> {
        private static final MapCodec<SmeltAverageRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        com.mojang.serialization.Codec.STRING.optionalFieldOf("group", "")
                                .forGetter(SmeltAverageRecipe::getGroup),
                        CookingBookCategory.CODEC.fieldOf("category")
                                .orElse(CookingBookCategory.BLOCKS)
                                .forGetter(SmeltAverageRecipe::category),
                        com.mojang.serialization.Codec.FLOAT.fieldOf("experience").orElse(0.1F)
                                .forGetter(SmeltAverageRecipe::getExperience),
                        com.mojang.serialization.Codec.INT.fieldOf("cookingtime").orElse(200)
                                .forGetter(SmeltAverageRecipe::getCookingTime))
                        .apply(instance, SmeltAverageRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, SmeltAverageRecipe> STREAM_CODEC =
                StreamCodec.of((buf, recipe) -> {
                    buf.writeUtf(recipe.getGroup());
                    buf.writeEnum(recipe.category());
                    buf.writeFloat(recipe.getExperience());
                    buf.writeVarInt(recipe.getCookingTime());
                }, buf -> new SmeltAverageRecipe(buf.readUtf(), buf.readEnum(CookingBookCategory.class),
                        buf.readFloat(), buf.readVarInt()));

        @Override
        public MapCodec<SmeltAverageRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, SmeltAverageRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
