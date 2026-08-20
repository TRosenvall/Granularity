package com.tarosie.granularity.content;

import com.tarosie.granularity.Granularity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The screens this mod can open. */
public final class GranularityMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Granularity.MODID);

    /**
     * Dressing a block in another block's face.
     *
     * <p>Created through {@code IMenuTypeExtension} because this one has something to say at open
     * time: which block is being dressed. The position rides along in the opening packet, so the
     * client's copy of the menu can read the same block entity the server's does.
     */
    public static final DeferredHolder<MenuType<?>, MenuType<TransmogMenu>> TRANSMOG =
            MENUS.register("transmog", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension
                    .create(TransmogMenu::new));

    private GranularityMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
