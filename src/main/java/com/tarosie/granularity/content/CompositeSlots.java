package com.tarosie.granularity.content;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Finding a composite in the inventory when only its <i>item</i> is known.
 *
 * <p>This exists for one caller: the recipe book's click-to-fill. {@code ServerPlaceRecipe} asks the
 * inventory for the item a recipe wants, but the stack it asks with is rebuilt from a
 * {@code StackedContents} index — and that index is the item's registry id alone, so the rebuilt
 * stack has <b>no components</b>. {@link Inventory#findSlotMatchingUnusedItem} then compares with
 * {@code isSameItemSameComponents}, and a cobblestone that remembers what it is made of can never
 * equal a bare one. The result is a recipe the book calls craftable, whose button does nothing.
 *
 * <p>So for our composites only, matching falls back to item identity. That is the right rule here
 * rather than a loosening: every crafted cobblestone carries a composition, any of them is a valid
 * input, and the recipes already say the first block's composition wins where inputs disagree. The
 * player gets whichever came to hand first, exactly as if they had placed it themselves.
 *
 * <p>Scoped to {@link CompositeBlockItem} on purpose — vanilla's stacking rules are untouched, and
 * nothing here can make two differently-composed blocks stack.
 */
public final class CompositeSlots {

    private CompositeSlots() {
    }

    /**
     * A slot holding any composite of the sought item, or -1.
     *
     * @param sought the component-less stack the recipe book asked for
     */
    public static int findAnyComposition(Inventory inventory, ItemStack sought) {
        if (!(sought.getItem() instanceof CompositeBlockItem)) {
            return -1;
        }
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack candidate = inventory.items.get(slot);
            // The same guards vanilla applies: a renamed or enchanted block is one the player did
            // something deliberate to, and consuming it out from under them would be rude.
            if (!candidate.isEmpty()
                    && candidate.is(sought.getItem())
                    && !candidate.isDamaged()
                    && !candidate.isEnchanted()
                    && !candidate.has(DataComponents.CUSTOM_NAME)) {
                return slot;
            }
        }
        return -1;
    }
}
