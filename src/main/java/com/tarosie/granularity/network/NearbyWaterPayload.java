package com.tarosie.granularity.network;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.client.NearbyWater;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The water that has actually moved near a player, and what the tiers are doing.
 *
 * <p>Everything a block's composition says is derivable on the client — that is what the synced salt
 * is for. Everything that has <i>happened</i> is not. Water that migrated into a block, drained out
 * of it, or was released by a pickaxe lives as a deviation in a server-side chunk attachment, and
 * without it the client reports the baseline the field says a block should hold rather than what it
 * holds. A number that is quietly the wrong number is worse than no number.
 *
 * <h2>Why the whole neighbourhood rather than one block</h2>
 * Because it is cheap and it is durable. Deviations are sparse by construction — a block at
 * equilibrium stores nothing, which is the whole point of the representation — so the nine chunks
 * around a player usually carry a handful of entries and often none at all. Sending them costs less
 * than a request-and-reply for a single block would, needs no round trip, and leaves the client
 * holding real data rather than an answer to one question.
 *
 * <p>That matters beyond the debug screen. The block tint is computed from the derived composition and
 * therefore cannot show migrated water at all; with this on the client, it could.
 */
public record NearbyWaterPayload(List<Deviation> deviations, int patches, int springs,
                                 int humidity, int recentRain, long weepsEmitted)
        implements CustomPacketPayload {

    /** One block that is not where the field says it should be. */
    public record Deviation(long position, int delta) {

        public static final StreamCodec<ByteBuf, Deviation> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Deviation::position,
                ByteBufCodecs.VAR_INT, Deviation::delta,
                Deviation::new);
    }

    public static final CustomPacketPayload.Type<NearbyWaterPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "nearby_water"));

    /**
     * The most deviations one packet may carry.
     *
     * <p>This was 2,048, with a note claiming nine chunks never legitimately carry that many. They
     * carry 2,060 — measured, in a world with springs running — and the failure was not a truncated
     * readout. A codec limit is enforced by <b>throwing</b> at encode time, which kills the connection
     * and drops the player onto the server list mid-session.
     *
     * <p>Two things follow, and the second is the one that matters. The sender now filters to what the
     * readout can actually use rather than everything in nine chunks; and it <b>truncates</b> rather
     * than letting the limit be reached, so this number can never disconnect anybody again. A cap that
     * throws is a cap that is one wrong estimate away from being a crash — and the estimate is always
     * wrong eventually.
     */
    private static final int MAX_DEVIATIONS = 2048;

    public static final StreamCodec<ByteBuf, NearbyWaterPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Deviation.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_DEVIATIONS)),
                    NearbyWaterPayload::deviations,
                    ByteBufCodecs.VAR_INT, NearbyWaterPayload::patches,
                    ByteBufCodecs.VAR_INT, NearbyWaterPayload::springs,
                    ByteBufCodecs.VAR_INT, NearbyWaterPayload::humidity,
                    ByteBufCodecs.VAR_INT, NearbyWaterPayload::recentRain,
                    ByteBufCodecs.VAR_LONG, NearbyWaterPayload::weepsEmitted,
                    NearbyWaterPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** The limit callers must respect, so the codec's own limit is never the thing that stops them. */
    public static int maxDeviations() {
        return MAX_DEVIATIONS;
    }

    public static void handle(NearbyWaterPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> NearbyWater.accept(payload));
    }
}
