package com.tarosie.granularity.world;

import com.tarosie.granularity.content.GranularityBlocks;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionFunction;
import com.tarosie.granularity.core.WaterDeviations;
import com.tarosie.granularity.core.WaterVolume;
import java.util.HashMap;
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

    private final ServerLevelAccessor level;
    private final long salt;
    private final Map<Long, Composition> compositions = new HashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /** Drops that entered or left the rock domain this step, for the ledger. */
    private int injected;
    private int drained;

    public LevelWaterVolume(ServerLevelAccessor level, long salt) {
        this.level = level;
        this.salt = salt;
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
        return compositions.computeIfAbsent(BlockPos.asLong(x, y, z),
                packed -> CompositionFunction.stone(x, y, z, salt));
    }

    private WaterDeviations deviations(int x, int z) {
        return chunk(x, z).getData(GranularityWater.DEVIATIONS);
    }

    private LevelChunk chunk(int x, int z) {
        return level.getLevel().getChunk(x >> 4, z >> 4);
    }
}
