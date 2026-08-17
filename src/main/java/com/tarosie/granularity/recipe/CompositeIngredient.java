package com.tarosie.granularity.recipe;

import com.tarosie.granularity.content.Finishes;
import com.tarosie.granularity.core.Finish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * Whether a stack is the composite a recipe is asking for — <b>finish included, always</b>.
 *
 * <p>This exists to make one particular bug impossible rather than merely unlikely. A finish is data
 * on the item now, not a separate item, so cobbled and smooth stone share a registry entry and
 * {@code stack.is(COBBLESTONE)} is true of both. Every recipe that means one of them and not the
 * other has to say so, and the danger is not that saying so is hard — it is that <i>forgetting</i> to
 * say so produces a recipe that quietly accepts the wrong stone. Cutting smooth slabs from a smooth
 * block is fine; cutting them and getting cobbled ones back is a laundering hole of exactly the kind
 * {@code Composition.pooled} had to close once already.
 *
 * <p>So the finish is a <b>required argument</b>. There is no overload without it. A recipe that has
 * not thought about finishes will not compile, and one that genuinely accepts any finish says
 * {@link #any} — which is a word a reader can see and a reviewer can grep for, rather than an absence
 * nobody notices.
 *
 * <p>The failure mode is pointed the right way round: forget, and nothing matches, which is loud and
 * immediately obvious. The alternative arrangement fails by matching everything, which is silent and
 * exploitable.
 */
public final class CompositeIngredient {

    private CompositeIngredient() {
    }

    /** True when the stack is that block, worked exactly that much. */
    public static boolean matches(ItemStack stack, ItemLike block, Finish finish) {
        return stack.is(block.asItem()) && Finishes.of(stack) == finish;
    }

    /**
     * True when the stack is that block, however it has been worked.
     *
     * <p>Spelled out rather than implied. Reach for it only where the finish genuinely does not
     * matter to the outcome — and note that if the finish does not matter to the <i>match</i>, the
     * result almost certainly has to carry it forward, or the recipe launders one finish into
     * another for free.
     */
    public static boolean any(ItemStack stack, ItemLike block) {
        return stack.is(block.asItem());
    }
}
