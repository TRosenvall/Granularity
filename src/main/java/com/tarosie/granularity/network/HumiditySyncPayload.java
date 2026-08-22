package com.tarosie.granularity.network;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.client.SkyMoisture;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Hands the client the shape of the sky around it.
 *
 * <p>Everything the atmosphere does is invisible without this. The humidity field lives in a
 * server-side chunk attachment, the client derives blocks from position and has never heard of it,
 * and vanilla's only weather lever is a single rain number for the whole level. Clouds, gloom under
 * heavy air, and rain falling in one place and not the next all need the client to know where the
 * moisture is — this is the one wire that tells it.
 *
 * <h2>Saturation, not drops</h2>
 * What crosses the wire is <b>how close each column is to raining</b>, 0 to 255, rather than the
 * humidity and capacity it was computed from. Two reasons. It is what every consumer actually wants —
 * cloud density, fog, gloom are all functions of the ratio, and none of them care how many drops
 * either side of it. And it means the client never has to compute a capacity, which would require the
 * biome and the heightmap and would have to agree with the server exactly or the sky would
 * disagree with the weather.
 *
 * <p>One byte per chunk. A grid seventeen chunks across is 289 bytes, sent every couple of seconds —
 * a few hundred bytes a second, which buys a sky that matches the ground.
 */
public record HumiditySyncPayload(int originX, int originZ, int width, byte[] saturation)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HumiditySyncPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "humidity_sync"));

    /** Enough for a grid far larger than the field tier simulates, and a hard stop on a bad packet. */
    private static final int MAX_CELLS = 4096;

    public static final StreamCodec<ByteBuf, HumiditySyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, HumiditySyncPayload::originX,
                    ByteBufCodecs.VAR_INT, HumiditySyncPayload::originZ,
                    ByteBufCodecs.VAR_INT, HumiditySyncPayload::width,
                    ByteBufCodecs.byteArray(MAX_CELLS), HumiditySyncPayload::saturation,
                    HumiditySyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(HumiditySyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SkyMoisture.accept(payload));
    }
}
