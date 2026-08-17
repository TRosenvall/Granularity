package com.tarosie.granularity;

import com.tarosie.granularity.client.GranularityModels;
import com.tarosie.granularity.core.WorldSalt;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only setup. This class does not load on dedicated servers. */
@Mod(value = Granularity.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Granularity.MODID, value = Dist.CLIENT)
public class GranularityClient {

    public GranularityClient(ModContainer container, IEventBus modEventBus) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        GranularityModels.register(modEventBus);
    }

    /**
     * Drops the synced salt on disconnect.
     *
     * <p>Without this, joining a second world before the sync packet arrives would derive
     * compositions from the previous world's salt — a client rendering ore the server does not
     * have. {@link WorldSalt.ClientView} throws when unset precisely so that the window between
     * clearing and re-syncing is loud instead of plausible.
     */
    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WorldSalt.ClientView.clear();
    }
}
