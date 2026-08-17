package com.tarosie.granularity.network;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.WorldSalt;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Carries the world salt to the client at login (design §4).
 *
 * <p>The salt, never the seed. {@link WorldSalt} explains why the derivation is one-way; this is
 * the wire that makes it matter — the client needs to hash the same positions the server does, and
 * this is the only thing it is told in order to do so.
 */
public record SaltSyncPayload(long salt) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SaltSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "salt_sync"));

    /**
     * A derived salt is uniform over 64 bits, so the varint encoding averages ~9 bytes where a
     * fixed-width one would take 8. Vanilla ships no fixed-width long codec, and one packet per
     * login is not worth writing one for.
     */
    public static final StreamCodec<ByteBuf, SaltSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, SaltSyncPayload::salt, SaltSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SaltSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            WorldSalt.ClientView.accept(WorldSalt.of(payload.salt()));
            Granularity.LOGGER.debug("World salt synced: {}", Long.toHexString(payload.salt()));
        });
    }
}
