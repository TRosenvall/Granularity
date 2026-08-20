package com.tarosie.granularity.world;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.WaterMigration;
import com.tarosie.granularity.core.WaterMigration.WaterBounds;
import com.tarosie.granularity.core.WorldSalt;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import com.tarosie.granularity.core.WaterDeviations;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Runs the migration rule where something has actually happened.
 *
 * <p>Design §8's block tier: "the drop-migration automaton runs only near disturbances (excavations,
 * rain events, released water), on a random-tick-style budget, reconciling with the field tier." The
 * whole world is wet; almost none of it is <i>moving</i>, and a rule that stepped every loaded chunk
 * would spend its entire budget confirming that groundwater is where the field already said it is.
 *
 * <h2>Patches, and how one stops being active</h2>
 * A disturbance marks a small patch of blocks around itself, snapped to a grid so that mining a
 * tunnel queues a handful of patches rather than one per block broken. A marked patch is stepped
 * until it goes quiet — a tick that moves nothing and exchanges nothing — for {@link #QUIET_TICKS} in
 * a row, and is then forgotten. That is what keeps a world that has been mined for a month from
 * carrying a growing list of places to check.
 *
 * <h2>What is deliberately not here</h2>
 * <ul>
 *   <li><b>Rain.</b> §6's other disturbance. It needs the humidity field of §11, which is not built.</li>
 *   <li><b>Cross-section flow beyond the marked neighbours.</b> Water reaching the edge of the active
 *       area stops there rather than dragging the simulation across the world. It resumes if
 *       something disturbs that ground too.</li>
 *   <li><b>Reconciliation with a field tier.</b> §8's middle tier does not exist yet; the decay in
 *       {@code WaterDeviations} stands in for it, pulling disturbed water back toward the baseline.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Granularity.MODID)
public final class WaterTicker {

    /** How many disturbed patches may be stepped in one level tick. The budget §8 asks for. */
    private static final int PATCHES_PER_TICK = 4;

    /**
     * How far around a disturbance the rule runs, in blocks.
     *
     * <p>A patch rather than a whole chunk section, and the difference is the whole cost of this
     * class. A section is 4,096 blocks, each of which needs its composition derived — nine slots of
     * noise apiece — and the scan runs three times a step. At four sections a tick that is some
     * sixteen thousand derivations per tick, spent precisely while somebody is mining, which is the
     * worst possible moment to spend anything.
     *
     * <p>Nine blocks across is 729, and it is where the water actually is: the disturbance is the
     * broken block, and water more than a few blocks away has not heard about it yet. Water reaching
     * the edge of a patch stops there until something disturbs that ground too.
     */
    private static final int PATCH_RADIUS = 4;

    /**
     * Disturbances are snapped to this grid before being queued, so that a player mining a tunnel
     * produces a handful of patches rather than one per block broken.
     */
    private static final int PATCH_GRID = 4;

    /**
     * Consecutive quiet ticks before a patch is dropped from the active list.
     *
     * <p>Two seconds. It was three ticks — a sixth of a second — which was wrong twice over. Water
     * working its way down through rock has quiet moments in it, so a patch would be dropped while
     * the drop that woke it was still falling; and the active count was gone long before anyone could
     * type a command to look at it, which made the tier impossible to observe from inside the game.
     */
    private static final int QUIET_TICKS = 40;

    /**
     * The longest a patch may stay active however busy it is.
     *
     * <p>A safety valve, not a physical quantity. An infinite vanilla source feeding porous rock that
     * seeps out somewhere lower is a genuine loop — the rock fills, gives water back, makes room, and
     * fills again — and every turn of it counts as work, so the quiet counter never runs down. The
     * water in such a place is doing what water does; what must not happen is that one bucket pins a
     * patch to the tick loop for the rest of the session. After this many ticks the patch is dropped
     * and only a fresh disturbance brings it back.
     */
    private static final int MAX_PATCH_TICKS = 400;

    /** How often the stored deviations are pulled one step back toward the derived baseline. */
    private static final int DECAY_INTERVAL = 600;

    /**
     * Active patch centres per level, in insertion order so the budget is a fair queue rather than
     * whichever entry a hash happened to put first.
     */
    private static final Map<ResourceKey<Level>, Map<Long, Patch>> ACTIVE = new HashMap<>();

    /**
     * How long a patch has left, both ways round: {@code quiet} counts down while nothing happens,
     * {@code age} counts up regardless.
     */
    private record Patch(int quiet, int age) {

        Patch worked() {
            return new Patch(QUIET_TICKS, age + 1);
        }

        Patch idled() {
            return new Patch(quiet - 1, age + 1);
        }

        boolean expired() {
            return quiet <= 0 || age >= MAX_PATCH_TICKS;
        }
    }

    /** Chunks known to hold deviations, so the decay pass visits those and nothing else. */
    private static final Map<ResourceKey<Level>, Set<Long>> DEVIATED = new HashMap<>();

    private WaterTicker() {
    }

    /**
     * Mark the ground around a position as worth simulating.
     *
     * <p>Called wherever the world does something to water: a block broken, a bucket emptied, a
     * pocket breached. Cheap enough to call speculatively — marking a patch that turns out to be
     * quiet costs {@link #QUIET_TICKS} steps and then stops.
     */
    public static void disturb(ServerLevel level, BlockPos pos) {
        Map<Long, Patch> active = ACTIVE.computeIfAbsent(level.dimension(),
                key -> new LinkedHashMap<>());
        // A fresh disturbance resets the quiet counter and the age both: this is new water, not the
        // same event still running, so the safety valve should not hold it against the patch.
        active.put(snap(pos), new Patch(QUIET_TICKS, 0));
    }

    /** The patch centre a position belongs to. Snapping is what collapses a tunnel into a few patches. */
    private static long snap(BlockPos pos) {
        return BlockPos.asLong(
                Math.floorDiv(pos.getX(), PATCH_GRID) * PATCH_GRID,
                Math.floorDiv(pos.getY(), PATCH_GRID) * PATCH_GRID,
                Math.floorDiv(pos.getZ(), PATCH_GRID) * PATCH_GRID);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Map<Long, Patch> active = ACTIVE.get(level.dimension());
        if (active == null || active.isEmpty()) {
            return;
        }
        if (!WorldSalt.ServerView.isPresent()) {
            return;
        }
        long salt = WorldSalt.ServerView.get().value();
        long tick = level.getGameTime();

        List<Long> due = new ArrayList<>(PATCHES_PER_TICK);
        for (Long patch : active.keySet()) {
            due.add(patch);
            if (due.size() >= PATCHES_PER_TICK) {
                break;
            }
        }

        for (Long packed : due) {
            // Re-inserted at the back whether it stayed active or not, so a long queue cycles
            // instead of starving everything behind its first few entries.
            Patch patch = active.remove(packed);
            if (patch == null) {
                continue;
            }
            int worked = stepPatch(level, BlockPos.of(packed), tick, salt);
            Patch next = worked > 0 ? patch.worked() : patch.idled();
            if (!next.expired()) {
                active.put(packed, next);
            }
        }

        if (tick % DECAY_INTERVAL == 0) {
            decayTrackedChunks(level);
        }
    }

    /** One tick of exchange and migration over one patch. Returns the drops that moved or crossed. */
    private static int stepPatch(ServerLevel level, BlockPos centre, long tick, long salt) {
        if (!level.hasChunk(centre.getX() >> 4, centre.getZ() >> 4)) {
            return 0;
        }
        LevelWaterVolume volume = new LevelWaterVolume(level, salt);
        WaterBounds bounds = new WaterBounds(
                centre.getX() - PATCH_RADIUS,
                Math.max(level.getMinBuildHeight(), centre.getY() - PATCH_RADIUS),
                centre.getZ() - PATCH_RADIUS,
                centre.getX() + PATCH_RADIUS,
                Math.min(level.getMaxBuildHeight() - 1, centre.getY() + PATCH_RADIUS),
                centre.getZ() + PATCH_RADIUS);

        // Infiltration first: water standing on the rock is this tick's input, and it should get to
        // move within the same tick rather than sit on the surface for one.
        int crossed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    if (volume.room(x, y, z) <= 0) {
                        continue;
                    }
                    cursor.set(x, y, z);
                    crossed += WaterExchange.infiltrate(level, volume, cursor);
                }
            }
        }

        int moved = WaterMigration.step(volume, bounds, tick, salt);

        // Seepage last, so water that arrived this tick has already had its chance to keep going
        // down through the rock before any of it is handed back to the world.
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    if (volume.water(x, y, z) <= 0) {
                        continue;
                    }
                    cursor.set(x, y, z);
                    crossed += WaterExchange.seep(level, volume, cursor);
                }
            }
        }
        return moved + crossed;
    }

    /**
     * Pull the deviations that exist one step back toward the derived baseline.
     *
     * <p>Only the chunks known to hold any. Walking every loaded chunk was the first version and is
     * the wrong shape twice over: it is work proportional to the view distance rather than to what
     * has actually been disturbed, and {@code ChunkMap.getChunks()} is protected, which is the engine
     * saying the same thing.
     */
    private static void decayTrackedChunks(ServerLevel level) {
        Set<Long> tracked = DEVIATED.get(level.dimension());
        if (tracked == null || tracked.isEmpty()) {
            return;
        }
        Iterator<Long> iterator = tracked.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.next();
            LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(packed), ChunkPos.getZ(packed));
            if (chunk == null) {
                // Unloaded. Its deviations went to disk with it and come back on load, so forgetting
                // it here loses nothing but the bookkeeping.
                iterator.remove();
                continue;
            }
            if (!chunk.hasData(GranularityWater.DEVIATIONS)) {
                iterator.remove();
                continue;
            }
            WaterDeviations deviations = chunk.getData(GranularityWater.DEVIATIONS);
            if (deviations.decay() > 0) {
                chunk.setUnsaved(true);
            }
            if (deviations.isEmpty()) {
                iterator.remove();
            }
        }
    }

    /** Note that a chunk now holds deviations, so the decay pass knows to visit it. */
    public static void trackDeviations(ServerLevel level, int blockX, int blockZ) {
        DEVIATED.computeIfAbsent(level.dimension(), key -> new LinkedHashSet<>())
                .add(ChunkPos.asLong(blockX >> 4, blockZ >> 4));
    }

    /**
     * Pick up the deviations a chunk brings back from disk.
     *
     * <p>Without this, water that moved before a restart would sit at its displaced level forever:
     * the map survives the save, but nothing would know to decay it back.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (chunk.hasData(GranularityWater.DEVIATIONS)
                && !chunk.getData(GranularityWater.DEVIATIONS).isEmpty()) {
            DEVIATED.computeIfAbsent(level.dimension(), key -> new LinkedHashSet<>())
                    .add(chunk.getPos().toLong());
        }
    }

    /** Forget a level's active list when it unloads, so the map does not outlive the world. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ACTIVE.remove(level.dimension());
            DEVIATED.remove(level.dimension());
        }
    }

    /** Drop everything, for a server shutting down or a test starting clean. */
    public static void clear() {
        ACTIVE.clear();
        DEVIATED.clear();
    }

    /** How many patches are queued in a level, for the diagnostic command. */
    public static int activePatches(ServerLevel level) {
        Map<Long, Patch> active = ACTIVE.get(level.dimension());
        return active == null ? 0 : active.size();
    }
}
