package net.worldeater.granularity.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.worldeater.granularity.Granularity;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Granularity.MOD_ID);


    public static final DeferredItem<Item> DIRTCLOD = ITEMS.register(
        "dirtclod",
        () -> new Item(new Item.Properties().useItemDescriptionPrefix().setId(
                ResourceKey.create(Registries.ITEM,
                ResourceLocation.parse(String.format("%s:dirtclod", Granularity.MOD_ID))
        )))
    );

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
