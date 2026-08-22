package com.tarosie.granularity.world;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.HumidityTransport;
import com.tarosie.granularity.core.WorldSalt;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import com.tarosie.granularity.network.HumiditySyncPayload;
import com.tarosie.granularity.network.NearbyWaterPayload;
import net.minecraft.core.BlockPos;

/**
 * The field tier, running — design §8's second tier and §11's water cycle.
 *
 * <p>Rain enters here and nowhere else, and that is a requirement rather than a preference. Findings
 * §5.1 measured a storm activating <b>18–40% of the loaded world</b>: "rain is not near anything — it
 * is everywhere", so the block tier that {@code WaterTicker} runs cannot be the thing that carries it.
 * The field tier is coarse — one cell per chunk — and slow, and those two properties are what make
 * weather affordable at all.
 *
 * <h2>The cycle, closed</h2>
 * Evaporation off open water raises humidity; wind carries it; capacity falls where the ground rises
 * or the air is cold; the excess rains; rain feeds {@link Recharge}, which refills the aquifer that
 * {@code WaterExchange} discharges at a spring, which runs to the sea. Every step of that is in drops,
 * so the whole loop is one currency.
 */
@EventBusSubscriber(modid = Granularity.MODID)
public final class GranularityWeather {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Granularity.MODID);

    /** The sky over a chunk: humidity, and what has lately fallen. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ChunkWeather>> WEATHER =
            ATTACHMENTS.register("weather", () -> AttachmentType
                    .builder(() -> ChunkWeather.UNKNOWN)
                    .serialize(ChunkWeather.CODEC)
                    .build());

    /**
     * How often the field tier advances, in ticks.
     *
     * <p>§8 says "at ~1 Hz or slower". Weather has no business moving twenty times a second, and the
     * whole reason this tier can afford to cover the loaded world is that it does not try to.
     */
    private static final int STEP_INTERVAL = 20;

    /**
     * How far around a player the sky is simulated, in chunks.
     *
     * <p>Beyond this the grid reports climate baselines and accepts what blows into it, which is what
     * {@link com.tarosie.granularity.core.HumidityGrid} means by the edge not being a wall. Eight
     * chunks is well over a hundred blocks — wide enough for a front to have somewhere to come from —
     * and costs 289 columns a second where twelve cost 625.
     */
    private static final int RADIUS = 8;

    /**
     * How many field steps between snapshots to the client, so once every two seconds.
     *
     * <p>Weather moves slowly and the client blends between snapshots, so sending one every step
     * would be bandwidth spent on a picture that has barely changed.
     */
    private static final int SYNC_STEPS = 2;

    private GranularityWeather() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long tick = level.getGameTime();
        if (tick % STEP_INTERVAL != 0 || !WorldSalt.ServerView.isPresent()) {
            return;
        }
        long salt = WorldSalt.ServerView.get().value();
        long step = tick / STEP_INTERVAL;

        // One box per player, skipping any that a box already stepped this tick covers. Stepping the
        // same sky twice in a step would not break conservation — each step is conservative — but it
        // would run weather at double speed for whoever was standing in the overlap.
        List<ChunkPos> stepped = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            ChunkPos centre = player.chunkPosition();
            if (alreadyCovered(stepped, centre)) {
                continue;
            }
            stepped.add(centre);

            HumidityTransport.Bounds bounds = new HumidityTransport.Bounds(
                    centre.x - RADIUS, centre.z - RADIUS, centre.x + RADIUS, centre.z + RADIUS);
            LevelHumidityGrid grid = new LevelHumidityGrid(level, bounds);

            // Evaporation first: vapour that rises this step should get to move in the same step
            // rather than sit over the sea for one.
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    if (grid.contains(x, z)) {
                        grid.evaporate(x, z);
                    }
                }
            }
            HumidityTransport.step(grid, bounds, step, salt);
            fadeRain(level, bounds);
            if (step % SYNC_STEPS == 0) {
                sendSky(level, grid, bounds);
                sendNearbyWater(level);
            }
        }
    }

    /**
     * Send the shape of the sky to everyone standing under it.
     *
     * <p>Saturation rather than humidity, because that is what the client can use without computing a
     * capacity of its own — which would need the biome and the heightmap and would have to agree with
     * the server exactly, or the sky would contradict the weather.
     */
    private static void sendSky(ServerLevel level, LevelHumidityGrid grid,
                                HumidityTransport.Bounds bounds) {
        int width = bounds.maxX() - bounds.minX() + 1;
        byte[] saturation = new byte[width * (bounds.maxZ() - bounds.minZ() + 1)];
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                int index = (z - bounds.minZ()) * width + (x - bounds.minX());
                if (!grid.contains(x, z)) {
                    continue;
                }
                int capacity = Math.max(1, grid.capacity(x, z));
                int ratio = Math.min(255, grid.humidity(x, z) * 255 / capacity);
                saturation[index] = (byte) ratio;
            }
        }

        HumiditySyncPayload payload = new HumiditySyncPayload(
                bounds.minX(), bounds.minZ(), width, saturation);
        for (ServerPlayer player : level.players()) {
            ChunkPos at = player.chunkPosition();
            if (at.x >= bounds.minX() && at.x <= bounds.maxX()
                    && at.z >= bounds.minZ() && at.z <= bounds.maxZ()) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /**
     * Send each player the water that has moved around them, and what the tiers are doing.
     *
     * <p>The nine chunks they could reach into, which is more than reach needs and costs nothing when
     * nothing has happened — deviations are sparse by construction, so the usual payload is empty.
     */
    private static void sendNearbyWater(ServerLevel level) {
        long[] weeps = WaterExchange.weepTally();
        for (ServerPlayer player : level.players()) {
            ChunkPos centre = player.chunkPosition();
            BlockPos at = player.blockPosition();
            List<NearbyWaterPayload.Deviation> deviations = new ArrayList<>();
            for (int dx = -1; dx <= 1 && deviations.size() < NearbyWaterPayload.maxDeviations(); dx++) {
                for (int dz = -1; dz <= 1 && deviations.size() < NearbyWaterPayload.maxDeviations(); dz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(centre.x + dx, centre.z + dz);
                    if (chunk == null || !chunk.hasData(GranularityWater.DEVIATIONS)) {
                        continue;
                    }
                    for (Map.Entry<Long, Integer> entry
                            : chunk.getData(GranularityWater.DEVIATIONS).entries().entrySet()) {
                        if (deviations.size() >= NearbyWaterPayload.maxDeviations()) {
                            // Truncated rather than sent, because the codec enforces its limit by
                            // throwing and that disconnects the player. Losing a readout for a block
                            // beyond arm's reach costs nothing; losing the session costs everything.
                            break;
                        }
                        // Only what the readout can use. A player can look at a block within reach,
                        // so anything further is bandwidth spent on numbers nobody will see — and
                        // sending all nine chunks is what overran the limit in the first place.
                        if (!withinReadout(at, entry.getKey())) {
                            continue;
                        }
                        deviations.add(new NearbyWaterPayload.Deviation(
                                entry.getKey(), entry.getValue()));
                    }
                }
            }
            ChunkWeather sky = at(level, at.getX(), at.getZ());
            PacketDistributor.sendToPlayer(player, new NearbyWaterPayload(
                    deviations,
                    WaterTicker.activePatches(level),
                    WaterTicker.activeSprings(level),
                    sky.known() ? sky.humidity() : 0,
                    sky.recentRain(),
                    weeps[3]));
        }
    }

    /**
     * How far from a player a deviation is worth sending, in blocks.
     *
     * <p>Comfortably past reach, so the block under the crosshair is always covered, and nowhere near
     * far enough to matter for bandwidth.
     */
    private static final int READOUT_RANGE = 24;

    private static boolean withinReadout(BlockPos player, long packed) {
        BlockPos pos = BlockPos.of(packed);
        return Math.abs(pos.getX() - player.getX()) <= READOUT_RANGE
                && Math.abs(pos.getY() - player.getY()) <= READOUT_RANGE
                && Math.abs(pos.getZ() - player.getZ()) <= READOUT_RANGE;
    }

    private static boolean alreadyCovered(List<ChunkPos> stepped, ChunkPos centre) {
        for (ChunkPos other : stepped) {
            if (Math.abs(other.x - centre.x) <= RADIUS && Math.abs(other.z - centre.z) <= RADIUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Let recent rainfall fade everywhere the grid covers.
     *
     * <p>Without it, one storm would keep an aquifer topped up for the rest of the world's life and
     * every spring would be perennial after the first rain — the distinction between a perennial
     * spring and a seasonal one is precisely that this number falls when it stops raining.
     */
    private static void fadeRain(ServerLevel level, HumidityTransport.Bounds bounds) {
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    continue;
                }
                ChunkWeather weather = chunk.getData(WEATHER);
                if (weather.recentRain() > 0) {
                    chunk.setData(WEATHER, weather.fade());
                }
            }
        }
    }

    /** How much rain has lately fallen on the chunk containing a block position. */
    public static int recentRain(ServerLevel level, int blockX, int blockZ) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4);
        return chunk == null ? 0 : chunk.getData(WEATHER).recentRain();
    }

    /** The sky over a block position, for the diagnostic command. */
    public static ChunkWeather at(ServerLevel level, int blockX, int blockZ) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4);
        return chunk == null ? ChunkWeather.UNKNOWN : chunk.getData(WEATHER);
    }
}
