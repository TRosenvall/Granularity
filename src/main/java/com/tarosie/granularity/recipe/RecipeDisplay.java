package com.tarosie.granularity.recipe;

import com.tarosie.granularity.content.GranularityBlocks;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.Grain;
import com.tarosie.granularity.core.GrainClass;
import com.tarosie.granularity.core.Grains;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;

/**
 * What the recipe book draws for a recipe whose real result depends on its inputs.
 *
 * <p>Granularity's crafting recipes are all coded rather than JSON, because nine granite chunks and
 * nine marble chunks make different cobblestones and a JSON recipe can only name a constant. That
 * used to mean declaring them {@code isSpecial()} and disappearing from the recipe book entirely —
 * but {@code isSpecial()} exists to hide recipes whose <b>ingredients</b> are meaningless to show, as
 * with firework rockets, and ours are perfectly meaningful. What varies is only the result.
 *
 * <p>So the recipes declare a real pattern and real ingredients, and give the book a representative
 * result to draw. The icon is a fixed sample rather than the block you will actually get; that is the
 * whole cost, and it buys discoverability plus working click-to-fill.
 */
public final class RecipeDisplay {

    private static Ingredient anyGrain;
    private static Composition sample;

    private RecipeDisplay() {
    }

    /**
     * Every item that is a grain, as one ingredient.
     *
     * <p>Taken from {@link Grains#itemIds()} so it is precisely what crafting accepts — the book
     * cycles through them, which happens to be the clearest possible statement of "any grain".
     */
    public static Ingredient anyGrain() {
        if (anyGrain == null) {
            List<ItemLike> items = new ArrayList<>();
            for (String id : Grains.itemIds()) {
                ResourceLocation key = ResourceLocation.tryParse(id);
                if (key == null) {
                    continue;
                }
                // A grain may name an item some other mod owns, or one a future version adds.
                // Skipping what is absent keeps a missing item from breaking every recipe.
                Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
                if (item != null) {
                    items.add(item);
                }
            }
            anyGrain = Ingredient.of(items.toArray(new ItemLike[0]));
        }
        return anyGrain;
    }

    /** Crafted cobblestone, the input to every cut. */
    public static Ingredient cobblestone() {
        return Ingredient.of(GranularityBlocks.COBBLESTONE.get());
    }

    /**
     * A representative block for the book to draw.
     *
     * <p>Deliberately a <b>mixed</b> composition. A uniform one would render as a real stone and be
     * named for it — "Granite Cobblestone" in the recipe book, for a recipe that makes nothing of the
     * kind. Mixed keeps the generic name and shows what these blocks are: made of whatever went in.
     */
    public static ItemStack sample(Block block, int count) {
        ItemStack stack = new ItemStack(block, count);
        stack.set(GranularityComponents.COMPOSITION.get(), sampleComposition());
        return stack;
    }

    private static Composition sampleComposition() {
        if (sample == null) {
            List<Grain> rocks = Grains.ofClass(GrainClass.ROCK);
            int[] ids = new int[Composition.SLOTS];
            for (int slot = 0; slot < ids.length; slot++) {
                ids[slot] = rocks.get(slot % rocks.size()).id();
            }
            sample = Composition.of(ids);
        }
        return sample;
    }
}
