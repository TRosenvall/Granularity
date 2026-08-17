package com.tarosie.granularity.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tarosie.granularity.content.Finishes;
import com.tarosie.granularity.content.Form;
import com.tarosie.granularity.content.Forms;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.Finish;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;

/**
 * One cut at a stonecutter: a move along <b>one</b> of the two axes a composite has.
 *
 * <p>A composite is a {@link Form} — block, slab, stairs, wall — and a {@link Finish} — cobbled,
 * smooth, and the stonework styles. This recipe changes the finish, or the form, and it is the whole
 * cutting interface.
 *
 * <h2>Why one axis at a time</h2>
 * The alternative was every combination: ten styles times four shapes is forty buttons in a menu that
 * has room for twelve, growing by four with every style added. Offering one axis per cut makes the
 * list <b>additive</b> — the styles this input can become, plus the shapes it can become — so ten
 * styles and four shapes is fourteen entries rather than forty, and the player chains two cuts to move
 * along both. A mottled wall is block → mottled → wall, or block → wall → mottled; both work, because
 * a style cut is defined on every form rather than only on blocks.
 *
 * <p>That is a UI budget rather than a technical one, and it is the reason to prefer it: the stonecutter
 * is vanilla's, unchanged, with no custom menu, screen or synced selection to maintain. See
 * {@code docs/STONEWORK_STYLES.md} §5.
 *
 * <h2>Composition survives; grains are watched</h2>
 * The output is the input's composition in a new shape or wearing a new surface — cutting is not a
 * furnace and not a hammer. A <b>style</b> cut is 1:1 and cannot affect conservation at all. A
 * <b>shape</b> cut can, because a stonecutter consumes exactly one input however many it yields, and
 * {@link Form#grains()} is where that arithmetic lives: two slabs from a block is 8 grains out of 9,
 * a wall is 9 out of 9, and a <b>stair is 13 out of 9 and therefore not cuttable at all</b>.
 * {@code ConservationTest} walks every shipped cut rather than trusting this paragraph.
 */
public class StoneCutRecipe extends StonecutterRecipe {

    private final Form fromForm;
    private final Finish from;
    private final Form toForm;
    private final Finish to;
    private final int count;

    public StoneCutRecipe(String group, Form fromForm, Finish from, Form toForm, Finish to, int count) {
        super(group, Ingredient.of(fromForm.block()),
                RecipeDisplay.sample(toForm.block(), count));
        this.fromForm = fromForm;
        this.from = from;
        this.toForm = toForm;
        this.to = to;
        this.count = count;
        // The book needs to show what it makes, and a finish lives on the stack rather than in the
        // block, so the sample has to be finished by hand.
        Finishes.apply(getResultItem(null), to);
    }

    public Form fromForm() {
        return fromForm;
    }

    public Finish from() {
        return from;
    }

    public Form toForm() {
        return toForm;
    }

    public Finish to() {
        return to;
    }

    public int count() {
        return count;
    }

    /** True when this cut reshapes rather than resurfaces — the only kind that can affect grains. */
    public boolean reshapes() {
        return fromForm != toForm;
    }

    @Override
    public boolean matches(SingleRecipeInput input, Level level) {
        // The finish is required, not incidental: see CompositeIngredient. A cut that accepted any
        // finish would let a player launder one style into another for nothing.
        return CompositeIngredient.matches(input.item(), fromForm.block(), from);
    }

    @Override
    public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider registries) {
        ItemStack result = new ItemStack(toForm.block(), count);
        Composition composition = input.item().get(GranularityComponents.COMPOSITION.get());
        if (composition != null) {
            // The grains cross untouched. Cutting changes the surface or the shape, not the stone.
            result.set(GranularityComponents.COMPOSITION.get(), composition);
        }
        Finishes.apply(result, to);
        // Moss and dye are deliberately not carried: cutting removes the face that was grown on or
        // painted, the same reasoning the furnace uses. See SmeltAverageRecipe.
        return result;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GranularityRecipes.STONE_CUT_SERIALIZER.get();
    }

    public static class Serializer implements RecipeSerializer<StoneCutRecipe> {

        private static final MapCodec<StoneCutRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        com.mojang.serialization.Codec.STRING.optionalFieldOf("group", "")
                                .forGetter(StoneCutRecipe::getGroup),
                        // Both forms default to the whole block, so the eleven style cuts that
                        // predate forms read unchanged — and a resurfacing cut, which is most of
                        // them, never has to name a form twice.
                        Forms.CODEC.optionalFieldOf("from_form", Form.BLOCK)
                                .forGetter(StoneCutRecipe::fromForm),
                        // Strict, not lenient: a mistyped style here would read as COBBLED and give a
                        // cut that turns rubble into rubble. See Finishes.STRICT_CODEC.
                        Finishes.STRICT_CODEC.fieldOf("from").forGetter(StoneCutRecipe::from),
                        Forms.CODEC.optionalFieldOf("to_form", Form.BLOCK)
                                .forGetter(StoneCutRecipe::toForm),
                        Finishes.STRICT_CODEC.fieldOf("to").forGetter(StoneCutRecipe::to),
                        com.mojang.serialization.Codec.INT.optionalFieldOf("count", 1)
                                .forGetter(StoneCutRecipe::count))
                        .apply(instance, StoneCutRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, StoneCutRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, StoneCutRecipe::getGroup,
                        Forms.STREAM_CODEC.cast(), StoneCutRecipe::fromForm,
                        Finishes.STREAM_CODEC.cast(), StoneCutRecipe::from,
                        Forms.STREAM_CODEC.cast(), StoneCutRecipe::toForm,
                        Finishes.STREAM_CODEC.cast(), StoneCutRecipe::to,
                        ByteBufCodecs.VAR_INT, StoneCutRecipe::count,
                        StoneCutRecipe::new);

        @Override
        public MapCodec<StoneCutRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, StoneCutRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
