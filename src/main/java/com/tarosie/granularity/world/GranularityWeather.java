package com.tarosie.granularity.world;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.HumidityTransport;
import com.tarosie.granularity.core.WorldSalt;
import java.util.ArrayList;
import java.util.List;
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
import net.neoforged.neoforge.registries.NeoForgeRegistries;

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
        }
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
