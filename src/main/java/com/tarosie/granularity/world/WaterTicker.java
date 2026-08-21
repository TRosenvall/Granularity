package com.tarosie.granularity.world;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.Rng;
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

    /**
     * How many disturbed patches may be stepped in one level tick. The budget §8 asks for.
     *
     * <p>Two, not four, and the number came from a measurement rather than a feeling. A patch step
     * touches 729 blocks and a composition is six microseconds, so four patches was some eighteen
     * milliseconds of a fifty millisecond tick — a third of the server's budget for a feature that is
     * meant to be in the background. With compositions now cached across ticks the repeat cost is far
     * lower, but the first pass over new ground still is not free, and a queue drains at the same rate
     * either way: a patch waiting a tick longer is invisible, a server running behind is not.
     */
    private static final int PATCHES_PER_TICK = 2;

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
     * <p>A backstop, and deliberately generous now. It was twenty seconds when the only thing that
     * could run forever was an infinite vanilla source feeding a loop — a cheat that needed a leash.
     * Since recharge arrived, sustained flow is the <i>intended</i> behaviour: a spring is meant to
     * keep running, and a patch that keeps working is a patch doing its job. Ten minutes is long
     * enough that no real spring is cut off mid-flow, and short enough that a patch nobody is near
     * eventually stops being stepped.
     */
    private static final int MAX_PATCH_TICKS = 12000;

    /**
     * How often rock below its baseline gets a drop back, and how much.
     *
     * <p>The interval is fixed; the <i>amount</i> comes from {@link Recharge}, which reads regional
     * rainfall. That split is the point: discharge is a property of the block, recharge is a property
     * of the catchment, and only the second one varies across the world.
     */
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

    /**
     * Pore space a face needs before it counts as a spring rather than a damp patch.
     *
     * <p>Half the block. Rock this open, saturated and cut through by a cave is a genuine aquifer
     * face, and there are far fewer of them than there are merely damp ones — which is what keeps the
     * set below small without a rule that says so.
     */
    public static final int SPRING_PORES = 4;

    /**
     * The most natural springs a level runs at once.
     *
     * <p>A hard cap, and the thing that makes this safe where self-scheduling was not. Ambient weeping
     * once let every exposed wet block promote itself to a half-second timer, with no fixed point:
     * blocks only stopped if they dried out, and recharge kept them wet. Measured at 465 emissions a
     * tick and a server 329 ticks behind.
     *
     * <p>A cap has a fixed point by construction. It also happens to be true to the world — a cave
     * system has a few springs in it, not a thousand — so the bound is not merely a budget, it is
     * roughly the right number.
     */
    private static final int MAX_SPRINGS = 24;

    /** Positions discharging on their own, discovered by random ticks. Insertion-ordered, capped. */
    private static final Map<ResourceKey<Level>, Set<Long>> SPRINGS = new HashMap<>();

    /** Chunks known to hold deviations, so the decay pass visits those and nothing else. */
    private static final Map<ResourceKey<Level>, Set<Long>> DEVIATED = new HashMap<>();

    private WaterTicker() {
    }

    /**
     * Offer a wet, open, porous face as a natural spring.
     *
     * <p>Called from a random tick, so discovery is spread over the loaded world at a rate the game
     * already budgets. Ignored once the level is at its cap, which is what bounds this: the first
     * faces found keep running until they stop qualifying, and the rest stay damp rock.
     */
    public static void offerSpring(ServerLevel level, BlockPos pos) {
        Set<Long> springs = SPRINGS.computeIfAbsent(level.dimension(), key -> new LinkedHashSet<>());
        if (springs.size() >= MAX_SPRINGS) {
            return;
        }
        springs.add(pos.asLong());
    }

    /** How many natural springs are running, for the diagnostic command. */
    public static int activeSprings(ServerLevel level) {
        Set<Long> springs = SPRINGS.get(level.dimension());
        return springs == null ? 0 : springs.size();
    }

    /**
     * Discharge every natural spring, once per tick.
     *
     * <p>Once per <i>tick</i> is the whole point. Vanilla erases unfed flowing water about five ticks
     * after it is placed, so a spring that tops its outlet up every tick accumulates — the level
     * climbs, the block fills, and it starts to flow downhill like water. A release of the same total
     * size delivered once a minute would be erased between every one and never amount to anything.
     * That difference, not the amount, is why breaking a rock made these faces gush.
     */
    private static void runSprings(ServerLevel level, long salt) {
        Set<Long> springs = SPRINGS.get(level.dimension());
        if (springs == null || springs.isEmpty()) {
            return;
        }
        LevelWaterVolume volume = new LevelWaterVolume(level, salt);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Iterator<Long> iterator = springs.iterator();
        while (iterator.hasNext()) {
            cursor.set(BlockPos.of(iterator.next()));
            if (!level.hasChunk(cursor.getX() >> 4, cursor.getZ() >> 4)) {
                // Unloaded. Forgotten rather than kept, so the cap is not held by ground nobody is
                // near; a random tick will find it again when somebody comes back.
                iterator.remove();
                continue;
            }
            if (!volume.contains(cursor.getX(), cursor.getY(), cursor.getZ())
                    || WaterExchange.seep(level, volume, cursor) <= 0) {
                // Broken, sealed, or dry. It has stopped being a spring, so it stops costing one.
                iterator.remove();
            }
        }
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
        // Before the early-out, not after it. The weep tally reports on ambient weeping, which
        // deliberately seeds no patches — so putting this below the "no active patches" return meant
        // the one mechanism it was built to observe was the one it could never see.
        reportWeeps(level.getGameTime());
        if (WorldSalt.ServerView.isPresent()) {
            runSprings(level, WorldSalt.ServerView.get().value());
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

        // Recharge before migration, so water arriving from the catchment gets to move in the same
        // tick rather than waiting a step at the back of the bed.
        int recharged = 0;
        if (tick % Recharge.INTERVAL == 0) {
            // Sampled once for the patch, not per block: rainfall varies over hundreds of blocks and
            // a patch is nine across, so per-block sampling would buy nothing for five biome lookups
            // a block.
            double rate = Recharge.dropsPerApplication(level, centre);
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                        if (!volume.contains(x, y, z)) {
                            continue;
                        }
                        // Stochastic rounding, design §12: a rate of a third of a drop really does
                        // mean one drop on one application in three, rather than a rate rounded to
                        // nothing. Drawn per block and per tick so neighbours do not recharge in
                        // lockstep.
                        long hash = Rng.positionHash(x, y, z, salt);
                        int drops = (int) Rng.stochasticFloor(
                                rate, Rng.uniform(hash, tick, Rng.STREAM_RAINFALL));
                        if (drops > 0) {
                            recharged += volume.recharge(x, y, z, drops);
                        }
                    }
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
        return moved + crossed + recharged;
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
            SPRINGS.remove(level.dimension());
        }
    }

    /** How often the ambient weep tally is written to the log, in ticks. */
    private static final int WEEP_REPORT_INTERVAL = 200;

    private static long lastWeepReport = -1;

    /**
     * Write what the ambient weep actually did into the log, when it changes.
     *
     * <p>Because the alternative is asking a person to read four numbers off a chat line and type
     * them back, which is a slow way to find out something the server already knows. This is the
     * "log a count of what a pass actually wired" rule from CLAUDE.md, applied to the one mechanism
     * whose failure mode is looking exactly like success: silence.
     *
     * <p>Only when the numbers move, so an idle world logs nothing.
     */
    private static void reportWeeps(long tick) {
        if (tick % WEEP_REPORT_INTERVAL != 0) {
            return;
        }
        long[] tally = WaterExchange.weepTally();
        if (tally[0] == lastWeepReport) {
            return;
        }
        lastWeepReport = tally[0];
        Granularity.LOGGER.info(
                "Weep tally: {} random ticks on wet-capable stone, {} with an open face, {} holding "
                        + "water, {} emitted, {} of those placing a block.",
                tally[0], tally[1], tally[2], tally[3], tally[4]);
    }

    /** Drop everything, for a server shutting down or a test starting clean. */
    public static void clear() {
        ACTIVE.clear();
        DEVIATED.clear();
        SPRINGS.clear();
    }

    /** How many patches are queued in a level, for the diagnostic command. */
    public static int activePatches(ServerLevel level) {
        Map<Long, Patch> active = ACTIVE.get(level.dimension());
        return active == null ? 0 : active.size();
    }
}
