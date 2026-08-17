package com.tarosie.granularity.client;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.content.GrainRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Takes the grain registry the server sent and puts it into the client's roster.
 *
 * <p>The client derives natural stone for itself — that is what makes the mod affordable, since a
 * mountain stores nothing — so it needs the same grains the server generated with. The registry has
 * already arrived by the time a player logs in: datapack registries are sent during the configuration
 * phase, which completes before the play phase begins and so before any chunk.
 *
 * <p>Nothing is undone on logging out, deliberately. Every join mirrors the <i>whole</i> registry, so
 * joining a server with no grain definitions retires the ones the last world had. Clearing on the way
 * out would only add a second path doing the same work, on the side of a shutdown where the
 * integrated server may still be finishing with the roster.
 */
@EventBusSubscriber(modid = Granularity.MODID, value = Dist.CLIENT)
public final class GrainRegistryClient {

    private GrainRegistryClient() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        GrainRegistry.mirror(event.getPlayer().registryAccess(), "login");
    }
}
