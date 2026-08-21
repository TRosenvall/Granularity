package com.tarosie.granularity.world;

import com.tarosie.granularity.content.GranularityBlocks;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionFunction;
import com.tarosie.granularity.core.WaterDeviations;
import com.tarosie.granularity.core.WaterVolume;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The world, seen as blocks water can move through.
 *
 * <h2>The domain is our own stone, and only that</h2>
 * Every other block — air, a cave, vanilla water, a player's cobblestone — reports nine grains, so
 * the migration rule treats it as a wall and water never crosses out. That is a deliberate boundary
 * rather than a limitation of the rule.
 *
 * <p>Vanilla water blocks tick themselves. If the rule also moved them, two systems would be moving
 * the same drops on two schedules, and the failure mode is water that doubles or vanishes depending
 * on which ran first — a bug that looks like lag. Keeping the domain to rock means the rule owns
 * every drop inside it and vanilla owns every drop outside it, with the exchange between them made
 * explicit and booked ({@link WaterExchange}) rather than emergent.
 *
 * <h2>Where the numbers come from</h2>
 * Grains are derived and never stored: {@link CompositionFunction} already knows how much rock is in
 * a block. Water is the derived baseline plus the chunk's stored deviation, which is design §6's
 * split rule spelled out in two method calls.
 *
 * <h2>The composition cache</h2>
 * A step asks for a block's grain count many times over — its own fall, its neighbours' spread, the
 * second fall — and each answer is nine slots of noise. Caching for the life of one step turns that
 * into once per block. The instance is meant to be short-lived and single-threaded for exactly this
 * reason; hold one across ticks and it will happily serve stale rock after somebody mines.
 */
public final class LevelWaterVolume implements WaterVolume {

    /**
     * Derived compositions, kept across ticks and across volumes.
     *
     * <p>Measured at 6.1 microseconds a derivation, and a patch step touches 729 blocks — so four
     * patches a tick is some eighteen milliseconds of a fifty millisecond budget, spent re-deriving
     * answers that cannot have changed. A composition is a pure function of position and salt, so a
     * cached one is valid for as long as the world is: there is no invalidation problem here, only a
     * size one. Blocks that stop being natural stone are excluded by {@link #contains} looking at the
     * world, not by anything in here.
     *
     * <p>Bounded and least-recently-used, because a player walking a tunnel would otherwise cache the
     * tunnel. Sixty-four thousand entries is a few megabytes and far more than any set of active
     * patches needs.
     */
    private static final int CACHE_LIMIT = 65_536;

    private static final Map<Long, Composition> COMPOSITIONS =
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Composition> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    /** Drops the cache when the world changes underneath it — a different salt is a different world. */
    private static long cachedSalt = Long.MIN_VALUE;

    private final ServerLevelAccessor level;
    private final long salt;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /** Drops that entered or left the rock domain this step, for the ledger. */
    private int injected;
    private int drained;

    public LevelWaterVolume(ServerLevelAccessor level, long salt) {
        this.level = level;
        this.salt = salt;
        if (cachedSalt != salt) {
            // A different world. Compositions from the last one are not merely stale, they are
            // answers to a different question.
            COMPOSITIONS.clear();
            cachedSalt = salt;
        }
    }

    @Override
    public boolean contains(int x, int y, int z) {
        cursor.set(x, y, z);
        if (level.getLevel().isOutsideBuildHeight(cursor)) {
            return false;
        }
        // Only a loaded chunk. Asking an unloaded one would generate terrain from inside a tick, and
        // water at the edge of the loaded world should stop there rather than drag the world outward.
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return false;
        }
        return level.getBlockState(cursor).is(GranularityBlocks.NATURAL_STONE.get());
    }

    @Override
    public int grains(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return Composition.SLOTS;
        }
        return Composition.SLOTS - composition(x, y, z).porosity();
    }

    @Override
    public int water(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return 0;
        }
        Composition composition = composition(x, y, z);
        return deviations(x, z).waterAt(BlockPos.asLong(x, y, z),
                composition.water(), composition.porosity());
    }

    @Override
    public void setWater(int x, int y, int z, int drops) {
        Composition composition = composition(x, y, z);
        deviations(x, z).setWaterAt(BlockPos.asLong(x, y, z), composition.water(), drops);
        // The chunk now differs from what the field alone would say, so it has to be written out —
        // and the decay pass has to know it exists, or this water would never find its way back to
        // the baseline.
        chunk(x, z).setUnsaved(true);
        if (level.getLevel() instanceof net.minecraft.server.level.ServerLevel server) {
            WaterTicker.trackDeviations(server, x, z);
        }
    }

    /**
     * What the field alone says this block would hold — its water before anything moved.
     *
     * <p>Offered because the alternative is calling {@link CompositionFunction} again from outside,
     * which lands outside this volume's cache. Seepage asks it of every wet block in a patch, and
     * below the water table that is most of them, so the difference is a derivation per block per
     * tick against none at all.
     */
    public int baselineWater(int x, int y, int z) {
        return composition(x, y, z).water();
    }

    /**
     * The block's pore space — every slot that is not rock, wet or dry.
     *
     * <p>What carries flow through <i>saturated</i> rock. {@link WaterVolume#room} is the free slots,
     * which is the right measure for rock filling up and is zero for rock already full; full rock
     * plainly still transmits water, because that is what an aquifer does. The two are the same only
     * in dry rock.
     */
    public int grainsPoreSpace(int x, int y, int z) {
        return composition(x, y, z).porosity();
    }

    /**
     * Put water back toward what the field says should be here, and say how much was added.
     *
     * <p>This is <b>recharge</b>: the catchment behind the hillside, refilling rock that has given
     * water up. It is not simulated — the water comes from the derived baseline, which stands for an
     * aquifer far larger than anything loaded — so it is booked as injected rather than pretended to
     * be conserved. When rain exists it replaces this rate and nothing else here changes.
     *
     * <p>Never past the baseline. Recharge fills a bed back to where the water table says it stands
     * and stops; going further would be inventing water rather than returning it.
     */
    public int recharge(int x, int y, int z, int drops) {
        int held = water(x, y, z);
        int baseline = baselineWater(x, y, z);
        if (held >= baseline) {
            return 0;
        }
        int added = Math.min(drops, baseline - held);
        setWater(x, y, z, held + added);
        injected += added;
        return added;
    }

    /** Drops handed to this volume from outside it — from a lake, from rain, from a broken block. */
    public void inject(int drops) {
        injected += drops;
    }

    /** Drops that left for the world outside — seeped out of a rock face. */
    public void drain(int drops) {
        drained += drops;
    }

    public int injected() {
        return injected;
    }

    public int drained() {
        return drained;
    }

    /**
     * Every drop this volume is tracking, for conservation checks.
     *
     * <p>Deliberately walks the region rather than summing the deviation map: the map holds
     * <i>differences</i>, and a sum of differences is not a sum of water.
     */
    public int totalWater(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int total = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    total += water(x, y, z);
                }
            }
        }
        return total;
    }

    private Composition composition(int x, int y, int z) {
        return COMPOSITIONS.computeIfAbsent(BlockPos.asLong(x, y, z),
                packed -> CompositionFunction.stone(x, y, z, salt));
    }

    /** How many derivations are being held. For the diagnostic command. */
    public static int cached() {
        return COMPOSITIONS.size();
    }

    private WaterDeviations deviations(int x, int z) {
        return chunk(x, z).getData(GranularityWater.DEVIATIONS);
    }

    private LevelChunk chunk(int x, int z) {
        return level.getLevel().getChunk(x >> 4, z >> 4);
    }
}
