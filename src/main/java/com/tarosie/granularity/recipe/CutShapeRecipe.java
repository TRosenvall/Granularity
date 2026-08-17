package com.tarosie.granularity.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tarosie.granularity.content.GranularityBlocks;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.core.Composition;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Stairs and walls cut from cobblestone, at vanilla's ratios and in vanilla's shapes.
 *
 * <p>One class for both because they differ only in pattern and yield; the composition rule is the
 * same one {@link SlabRecipe} documents — every output carries the <b>whole</b> composition of the
 * stone it was cut from, because a stack of N shares one component set and a grain does not divide.
 *
 * <p>Where the inputs disagree, the first block's composition wins, which keeps the result a real
 * composition rather than an average of grain ids.
 */
public class CutShapeRecipe extends net.minecraft.world.item.crafting.ShapedRecipe {

    /** Which cut this is: the pattern to match, and how many it yields. */
    public enum Shape implements StringRepresentable {
        /**
         * Vanilla's staircase, either handedness — a shaped recipe would mirror, so we do too.
         *
         * <p><b>Six, where vanilla yields four.</b> A deliberate divergence, and the only one in this
         * class. Vanilla's staircase is lossy, which here meant a stair cost a block and a half and so
         * was <i>worth</i> thirteen grains — more than the nine a block holds. That anomaly was
         * load-bearing in the wrong direction: it made the stair the one shape worth more than the
         * stone it came from, and it made {@code block -> 1 stair} at a stonecutter a grain press,
         * which is why that cut had to be withheld while every other shape had one.
         *
         * <p>Raising the yield to six makes a stair cost exactly one block, which is the natural
         * price for a full-height shape and the same one a wall pays. Everything falls into line: the
         * recipe is break-even at 54 grains, {@code STAIRS_GRAINS} drops to nine, and the stonecutter
         * cut becomes legal on its own arithmetic rather than by exception.
         */
        STAIRS("stairs", 3, 3, 6),
        /** Two full rows of three. */
        WALL("wall", 3, 2, 6);

        /**
         * The shape as the recipe book should draw it.
         *
         * <p>One handedness only, because a picture can only show one — {@link #wants} still accepts
         * both, so the book teaching the left-facing staircase costs the player nothing.
         */
        List<String> display() {
            return this == STAIRS ? List.of("#  ", "## ", "###") : List.of("###", "###");
        }

        /**
         * Mossy stone cuts through the same two recipes.
         *
         * <p>There used to be a {@code mossy_stairs} and a {@code mossy_wall} shape here, back when
         * mossy cobblestone was a separate block. It is not: moss is a flag the block carries, so
         * cutting mossy stone is the same cut and the flag rides along to the result.
         */
        Block input() {
            return GranularityBlocks.COBBLESTONE.get();
        }

        private final String name;
        private final int width;
        private final int height;
        private final int yield;

        Shape(String name, int width, int height, int yield) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.yield = yield;
        }

        Block result() {
            return switch (this) {
                case STAIRS -> GranularityBlocks.COBBLESTONE_STAIRS.get();
                case WALL -> GranularityBlocks.COBBLESTONE_WALL.get();
            };
        }

        /** How many the cut yields, and how many blocks it takes — read by ConservationTest. */
        int yield() {
            return yield;
        }

        int blocksConsumed() {
            int blocks = 0;
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    if (wants(col, row, false)) {
                        blocks++;
                    }
                }
            }
            return blocks;
        }

        /** True where this shape wants a block at (col, row). Stairs take a handedness. */
        boolean wants(int col, int row, boolean mirrored) {
            if (this != STAIRS) {
                return true;
            }
            return mirrored ? col >= (height - 1 - row) : col <= row;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final StringRepresentable.EnumCodec<Shape> SHAPE_CODEC =
            StringRepresentable.fromEnum(Shape::values);

    private final Shape shape;

    public CutShapeRecipe(CraftingBookCategory category, Shape shape) {
        // Declared for the recipe book -- see RecipeDisplay. `matches` is what actually decides,
        // because it also accepts the mirrored staircase and reads the composition component.
        super("", category,
                net.minecraft.world.item.crafting.ShapedRecipePattern.of(
                        java.util.Map.of('#', RecipeDisplay.cobblestone()), shape.display()),
                RecipeDisplay.sample(shape.result(), shape.yield));
        this.shape = shape;
    }

    public Shape shape() {
        return shape;
    }

    /** What the grid holds: the stone the cut is made from, and what is growing on it. */
    private record Cut(Composition composition,
                       com.tarosie.granularity.content.Coating overlays,
                       com.tarosie.granularity.core.Finish finish) {
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return cutOf(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        Cut cut = cutOf(input);
        if (cut == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = new ItemStack(shape.result(), shape.yield);
        result.set(GranularityComponents.COMPOSITION.get(), cut.composition());
        com.tarosie.granularity.content.Moss.apply(result, cut.overlays());
        com.tarosie.granularity.content.Finishes.apply(result, cut.finish());
        return result;
    }

    /** What the pattern carries, or null if the grid does not hold that pattern. */
    private Cut cutOf(CraftingInput input) {
        if (input.width() != shape.width || input.height() != shape.height) {
            return null;
        }
        Cut mirrored = read(input, true);
        return mirrored != null ? mirrored : read(input, false);
    }

    private Cut read(CraftingInput input, boolean mirrored) {
        java.util.List<Composition> pool = new java.util.ArrayList<>();
        com.tarosie.granularity.content.Coating overlays = null;
        com.tarosie.granularity.core.Finish finish = null;
        for (int row = 0; row < shape.height; row++) {
            for (int col = 0; col < shape.width; col++) {
                ItemStack stack = input.getItem(row * shape.width + col);
                boolean wanted = shape.wants(col, row, mirrored);
                if (!wanted) {
                    // A stair's empty corners must actually be empty, or a 3x3 of cobblestone
                    // would craft stairs as readily as it crafts a block.
                    if (!stack.isEmpty()) {
                        return null;
                    }
                    continue;
                }
                // Any finish cuts, but every block in the pattern must agree and the finish rides
                // through to the result — see CompositeIngredient. Cutting is not a furnace.
                if (!CompositeIngredient.any(stack, shape.input())) {
                    return null;
                }
                if (finish == null) {
                    finish = com.tarosie.granularity.content.Finishes.of(stack);
                } else if (finish != com.tarosie.granularity.content.Finishes.of(stack)) {
                    return null;
                }
                Composition composition = stack.get(GranularityComponents.COMPOSITION.get());
                if (composition == null) {
                    return null;
                }
                pool.add(composition);
                if (overlays == null) {
                    // Overlays still take the first block. Unlike the composition there is nothing
                    // to conserve — moss is not a material the hammer gives back — so first-wins
                    // launders nothing.
                    overlays = com.tarosie.granularity.content.Moss.of(stack);
                }
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        // Every block that went in, pooled. See Composition.pooled: taking the first and discarding
        // the rest let a single marble block turn two granite ones into marble.
        return new Cut(Composition.pooled(pool), overlays,
                finish == null ? com.tarosie.granularity.core.Finish.COBBLED : finish);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GranularityRecipes.CUT_SHAPE_SERIALIZER.get();
    }

    public static class Serializer implements RecipeSerializer<CutShapeRecipe> {
        private static final MapCodec<CutShapeRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        CraftingBookCategory.CODEC.fieldOf("category")
                                .orElse(CraftingBookCategory.BUILDING).forGetter(CutShapeRecipe::category),
                        SHAPE_CODEC.fieldOf("shape").forGetter(CutShapeRecipe::shape))
                        .apply(instance, CutShapeRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, CutShapeRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        CraftingBookCategory.STREAM_CODEC, CutShapeRecipe::category,
                        net.minecraft.network.codec.ByteBufCodecs.idMapper(
                                i -> Shape.values()[i], Shape::ordinal), CutShapeRecipe::shape,
                        CutShapeRecipe::new);

        @Override
        public MapCodec<CutShapeRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CutShapeRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
