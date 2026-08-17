package com.tarosie.granularity.network;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.WorldSalt;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Derives the salt when a world loads, hands it to each player at login, and clears it after.
 *
 * <p>The clearing matters as much as the setting. A salt left behind from the previous world would
 * be used to derive compositions for the next one, and because both are plausible-looking worlds
 * the result is not a crash but a client rendering ore that the server does not have.
 */
@EventBusSubscriber(modid = Granularity.MODID)
public final class SaltLifecycle {

    private SaltLifecycle() {
    }

    /** Called from the mod constructor: payload registration is a mod-bus event, not a game one. */
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(
                SaltSyncPayload.TYPE, SaltSyncPayload.STREAM_CODEC, SaltSyncPayload::handle);
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // The overworld's seed is the world's seed; every dimension shares it. Derived once here
        // rather than per-lookup so the cost of the digest is paid once per world load.
        long seed = event.getServer().getWorldData().worldGenOptions().seed();
        WorldSalt salt = WorldSalt.derive(seed);
        WorldSalt.ServerView.accept(salt);
        Granularity.LOGGER.info("World salt derived: {}", Long.toHexString(salt.value()));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WorldSalt.ServerView.clear();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && WorldSalt.ServerView.isPresent()) {
            PacketDistributor.sendToPlayer(player, new SaltSyncPayload(WorldSalt.ServerView.get().value()));
        }
    }
}
