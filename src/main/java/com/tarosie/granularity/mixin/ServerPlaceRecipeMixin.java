package com.tarosie.granularity.mixin;

import com.tarosie.granularity.content.CompositeSlots;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets the recipe book fill the grid with blocks that remember what they are made of.
 *
 * <p>The mod's only mixin, and it is here because vanilla leaves no other way in.
 * {@code ServerPlaceRecipe#moveItemToGrid} looks the wanted item up with a stack rebuilt from a
 * {@code StackedContents} index, which carries no components, and
 * {@link Inventory#findSlotMatchingUnusedItem} then demands component equality. Every Granularity
 * cobblestone carries a composition, so the lookup always fails and the button does nothing —
 * silently, because the earlier craftability check keys on the item id alone and passes, which also
 * suppresses the ghost-recipe packet vanilla would otherwise send. NeoForge patches neither method
 * and offers no event on this path.
 *
 * <p>The redirect only adds a fallback: vanilla's answer is taken whenever it finds one, and
 * {@link CompositeSlots#findAnyComposition} is consulted only for our own composite items. Nothing
 * about vanilla stacking or any other mod's items is affected.
 */
@Mixin(ServerPlaceRecipe.class)
public class ServerPlaceRecipeMixin {

    @Redirect(
            method = "moveItemToGrid(Lnet/minecraft/world/inventory/Slot;Lnet/minecraft/world/item/ItemStack;I)I",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;"
                            + "findSlotMatchingUnusedItem(Lnet/minecraft/world/item/ItemStack;)I"))
    private int granularity$findComposite(Inventory inventory, ItemStack sought) {
        int slot = inventory.findSlotMatchingUnusedItem(sought);
        return slot != -1 ? slot : CompositeSlots.findAnyComposition(inventory, sought);
    }
}
