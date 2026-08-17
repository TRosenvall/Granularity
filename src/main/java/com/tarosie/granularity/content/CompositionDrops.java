package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.Grain;
import com.tarosie.granularity.core.GrainClass;
import com.tarosie.granularity.core.Grains;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Turns a derived composition into the objects a break yields (design §2).
 *
 * <p>One slot, one object. Slots holding the same material merge into one stack, so a block of nine
 * red rock chunks is one stack of nine rather than nine stacks of one — but a boundary block's five
 * red and four blue stay two stacks, because that distinction is the whole point of the colour
 * lattice.
 */
public final class CompositionDrops {

    private CompositionDrops() {
    }

    /**
     * The stacks a composition drops.
     *
     * <p>Fewer than nine objects can come out: water slots are invisible and unobtainable, and
     * empty slots are porosity. Both are silent by design rather than by oversight — see
     * {@link GranularityItems}.
     */
    public static List<ItemStack> toStacks(Composition composition) {
        // Ordered so drops come out in a stable, position-independent order. A HashMap here would
        // make the drop order vary between runs, which is the kind of thing that looks like a
        // physics bug when it is really just iteration order.
        Map<Integer, Integer> countsByGrain = new LinkedHashMap<>();
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            countsByGrain.merge(composition.grainId(slot), 1, Integer::sum);
        }

        List<ItemStack> stacks = new ArrayList<>(countsByGrain.size());
        for (Map.Entry<Integer, Integer> entry : countsByGrain.entrySet()) {
            Grain grain = Grains.byId(entry.getKey());
            if (!grain.clazz().isObtainable()) {
                continue;
            }
            // The grain *is* an item: iron drops vanilla raw iron, diamond drops a diamond. No
            // component to carry, and the drops slot straight into recipes that already exist.
            Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.parse(grain.itemId()));
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            stacks.add(new ItemStack(item, entry.getValue()));
        }
        return stacks;
    }

    /**
     * The stacks a <i>fraction</i> of a block gives back — what the hammer yields from a cut shape.
     *
     * <p>A shape is worth fewer than nine grains, so something has to decide <b>which</b> of the nine
     * come back. Each one is drawn at random from the block's own slots, which makes the chance of a
     * given material exactly its share of the block: hammer a slab cut from four gold ore and five
     * slate and each grain is gold four times in nine, slate five times in nine.
     *
     * <p>Chance rather than proportion because proportion cannot be honest at these sizes. Four
     * grains cannot be split 4:5, and any fixed rounding either invents material or destroys it, in
     * the same direction every time — a rule that quietly taxes mixed blocks and never mixes back.
     * Drawing instead is right on average and wrong only in the individual case, which is the correct
     * way round for something a player does hundreds of times.
     *
     * <p>Drawing a <i>slot</i> rather than a material is what makes the weighting automatic, and it
     * also keeps porosity honest: a draw landing on an air or water slot yields nothing, so a porous
     * block gives back less, exactly as it should.
     *
     * @param grains how many of the block's nine this shape is worth
     */
    public static List<ItemStack> toStacks(Composition composition, int grains, RandomSource random) {
        // Ordered for the same reason toStacks(Composition) is: stable drop order.
        Map<Integer, Integer> countsByGrain = new LinkedHashMap<>();
        for (int draw = 0; draw < grains; draw++) {
            countsByGrain.merge(composition.grainId(random.nextInt(Composition.SLOTS)), 1, Integer::sum);
        }

        List<ItemStack> stacks = new ArrayList<>(countsByGrain.size());
        for (Map.Entry<Integer, Integer> entry : countsByGrain.entrySet()) {
            ItemStack stack = stackFor(entry.getKey(), entry.getValue());
            if (stack != null) {
                stacks.add(stack);
            }
        }
        return stacks;
    }

    /** One stack of a grain, or null where the grain is not something a player can hold. */
    private static ItemStack stackFor(int grainId, int count) {
        Grain grain = Grains.byId(grainId);
        if (!grain.clazz().isObtainable()) {
            return null;
        }
        // The grain *is* an item: iron drops vanilla raw iron, diamond drops a diamond. No
        // component to carry, and the drops slot straight into recipes that already exist.
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse(grain.itemId()));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return null;
        }
        return new ItemStack(item, count);
    }

    /** How many of a composition's nine slots yield a held object. */
    public static int obtainableSlots(Composition composition) {
        int n = 0;
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            if (composition.classAt(slot).isObtainable()) {
                n++;
            }
        }
        return n;
    }
}
