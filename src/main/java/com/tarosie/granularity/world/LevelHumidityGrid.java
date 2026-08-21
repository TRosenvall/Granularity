package com.tarosie.granularity.world;

import com.tarosie.granularity.core.HumidityGrid;
import com.tarosie.granularity.core.HumidityTransport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * The world's sky, as a grid of chunk columns the transport rule can run over.
 *
 * <p>One cell per chunk. Everything the rule asks for that is not stored — capacity, baseline — is
 * derived here from the things the level already knows: how warm it is, how high the ground stands,
 * how wet the climate is.
 *
 * <h2>Capacity is where the weather comes from</h2>
 * §11 asks for rain "when humidity exceeds a temperature-dependent capacity", and notes that
 * orographic rain and rain shadows follow. They do, and nothing else has to be written: air carrying
 * moisture inland meets ground that rises, capacity falls with the cold, the excess rains out on the
 * windward slope, and what crosses the ridge is dry. The desert behind a mountain range is not a
 * biome placement rule here — it is arithmetic about how much water cold air can hold.
 *
 * <p>So the two terms are temperature and <b>height of the ground</b>, which is why this class needs
 * a level at all. A pure function could not ask how tall the mountain is; a chunk's heightmap answers
 * it for free, already computed and already cached.
 */
public final class LevelHumidityGrid implements HumidityGrid {

    /**
     * Drops a warm sea-level column can hold before it rains.
     *
     * <p>Sets the scale of everything else. Large enough that ordinary weather sits under it and
     * only genuinely moist air rains, small enough that a mountain can push air over the line.
     */
    private static final int BASE_CAPACITY = 420;

    /**
     * How many drops of capacity are lost per block of ground elevation above sea level.
     *
     * <p>The orographic term. Air lifted over high ground cools, and cold air holds less — this is
     * that, linearised. At this rate a summit two hundred blocks up holds almost nothing, which is
     * why mountains are wet on the windward side and snowbound on top.
     */
    private static final int CAPACITY_PER_BLOCK = 3;

    /** Never zero, or high ground would rain out every drop that ever reached it and never stop. */
    private static final int MINIMUM_CAPACITY = 30;

    /** Sea level, the datum elevation is measured from. */
    private static final int SEA_LEVEL = 63;

    private final ServerLevel level;
    private final HumidityTransport.Bounds bounds;
    private int rained;

    /**
     * Capacity and baseline, worked out once per column per step.
     *
     * <p>Both cost a biome lookup and a heightmap read, and both are asked for repeatedly: condense
     * wants the capacity, baseline wants the capacity, and every column's four neighbours may want a
     * baseline. That is several times the work for an answer that cannot change within a step —
     * ground does not rise and climate does not shift between advection and condensation.
     *
     * <p>Not static, unlike the composition cache: terrain <i>can</i> change between steps, and a
     * grid lives for exactly one.
     */
    private final int[] capacities;
    private final int[] baselines;
    private final int width;

    public LevelHumidityGrid(ServerLevel level, HumidityTransport.Bounds bounds) {
        this.level = level;
        this.bounds = bounds;
        this.width = bounds.maxX() - bounds.minX() + 1;
        int cells = width * (bounds.maxZ() - bounds.minZ() + 1);
        this.capacities = new int[cells];
        this.baselines = new int[cells];
        java.util.Arrays.fill(this.capacities, Integer.MIN_VALUE);
        java.util.Arrays.fill(this.baselines, Integer.MIN_VALUE);
    }

    /** Index into the per-step caches, or -1 for a column outside the grid. */
    private int cell(int columnX, int columnZ) {
        if (columnX < bounds.minX() || columnX > bounds.maxX()
                || columnZ < bounds.minZ() || columnZ > bounds.maxZ()) {
            return -1;
        }
        return (columnZ - bounds.minZ()) * width + (columnX - bounds.minX());
    }

    @Override
    public boolean contains(int columnX, int columnZ) {
        if (columnX < bounds.minX() || columnX > bounds.maxX()
                || columnZ < bounds.minZ() || columnZ > bounds.maxZ()) {
            return false;
        }
        // Loaded only. Asking for an unloaded chunk would generate terrain from inside a tick, and
        // weather has no business dragging the world into existence ahead of the player.
        return level.getChunkSource().getChunkNow(columnX, columnZ) != null;
    }

    @Override
    public int humidity(int columnX, int columnZ) {
        LevelChunk chunk = chunk(columnX, columnZ);
        if (chunk == null) {
            return baseline(columnX, columnZ);
        }
        ChunkWeather weather = chunk.getData(GranularityWeather.WEATHER);
        return weather.known() ? weather.humidity() : baseline(columnX, columnZ);
    }

    @Override
    public void setHumidity(int columnX, int columnZ, int drops) {
        LevelChunk chunk = chunk(columnX, columnZ);
        if (chunk == null) {
            return;
        }
        chunk.setData(GranularityWeather.WEATHER,
                chunk.getData(GranularityWeather.WEATHER).withHumidity(drops));
        chunk.setUnsaved(true);
    }

    @Override
    public int capacity(int columnX, int columnZ) {
        int cell = cell(columnX, columnZ);
        if (cell >= 0 && capacities[cell] != Integer.MIN_VALUE) {
            return capacities[cell];
        }
        int computed = computeCapacity(columnX, columnZ);
        if (cell >= 0) {
            capacities[cell] = computed;
        }
        return computed;
    }

    private int computeCapacity(int columnX, int columnZ) {
        LevelChunk chunk = chunk(columnX, columnZ);
        if (chunk == null) {
            return BASE_CAPACITY;
        }
        BlockPos centre = centre(columnX, columnZ);
        // Warmth first: a tropical sky holds far more than a polar one, which is most of why the
        // tropics are wet and the poles are deserts in everything but appearance.
        double warmth = Math.max(0.0, Math.min(1.2,
                level.getBiome(centre).value().getBaseTemperature()));
        int warm = (int) (BASE_CAPACITY * (0.35 + 0.65 * warmth));

        int ground = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, centre.getX() & 15,
                centre.getZ() & 15);
        int lifted = Math.max(0, ground - SEA_LEVEL) * CAPACITY_PER_BLOCK;
        return Math.max(MINIMUM_CAPACITY, warm - lifted);
    }

    /**
     * What this column could hold if the ground here were at sea level — warmth alone, no lifting.
     *
     * <p>The datum the baseline is measured against, so that elevation is counted once rather than
     * twice. See {@link #computeBaseline}.
     */
    private int seaLevelCapacity(int columnX, int columnZ) {
        double warmth = Math.max(0.0, Math.min(1.2,
                level.getBiome(centre(columnX, columnZ)).value().getBaseTemperature()));
        return (int) (BASE_CAPACITY * (0.35 + 0.65 * warmth));
    }

    @Override
    public int baseline(int columnX, int columnZ) {
        int cell = cell(columnX, columnZ);
        if (cell >= 0 && baselines[cell] != Integer.MIN_VALUE) {
            return baselines[cell];
        }
        int computed = computeBaseline(columnX, columnZ);
        if (cell >= 0) {
            baselines[cell] = computed;
        }
        return computed;
    }

    private int computeBaseline(int columnX, int columnZ) {
        LevelChunk chunk = chunk(columnX, columnZ);
        if (chunk == null) {
            return BASE_CAPACITY / 4;
        }
        // Measured against the capacity a column would have at SEA LEVEL, not against its own.
        //
        // This is the fix for a world that never rained. Scaling the baseline by the *local* capacity
        // double-counts elevation: high ground already has less capacity, so tying its baseline to
        // that too meant the air arrived pre-shrunk and could never be over the line. Every reading
        // sat near a fifth of capacity and nothing ever condensed.
        //
        // Against sea level instead, a wet lowland sky sits close to saturated — which is the point.
        // Rain then needs only what §11 says it should need: air that gets colder or ground that gets
        // higher. Lift that same air three hundred blocks and its capacity falls below what it is
        // already carrying, and it rains, without anything else being tuned.
        double downfall = level.getBiome(centre(columnX, columnZ)).value()
                .getModifiedClimateSettings().downfall();
        int atSeaLevel = seaLevelCapacity(columnX, columnZ);
        return (int) (atSeaLevel * (0.35 + 0.55 * Math.max(0.0, Math.min(1.0, downfall))));
    }

    @Override
    public void rain(int columnX, int columnZ, int drops) {
        LevelChunk chunk = chunk(columnX, columnZ);
        if (chunk == null) {
            return;
        }
        chunk.setData(GranularityWeather.WEATHER,
                chunk.getData(GranularityWeather.WEATHER).withRain(drops));
        chunk.setUnsaved(true);
        rained += drops;
    }

    /**
     * Vapour rising off open water, and the only place new humidity enters the sky.
     *
     * <p>§11's source term, and the step that closes the cycle: a drop that fell as rain, soaked into
     * rock, seeped out at a spring and ran to the sea can evaporate and go round again. Sampled at the
     * chunk's centre rather than surveyed across it — one lookup per chunk per step, and a chunk is
     * either mostly sea or mostly not.
     *
     * @return drops added
     */
    public int evaporate(int columnX, int columnZ) {
        LevelChunk chunk = chunk(columnX, columnZ);
        if (chunk == null) {
            return 0;
        }
        BlockPos centre = centre(columnX, columnZ);
        int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, centre.getX() & 15,
                centre.getZ() & 15);
        BlockPos top = new BlockPos(centre.getX(), surface - 1, centre.getZ());
        if (!level.getFluidState(top).is(Fluids.WATER)) {
            return 0;
        }
        // Warm seas give up more, which is what puts the moisture over the tropics. Generous, because
        // this is the only place vapour enters the sky at all and it has a whole ocean's worth of
        // atmosphere to keep topped up against advection carrying it inland.
        double warmth = Math.max(0.0, Math.min(1.2,
                level.getBiome(top).value().getBaseTemperature()));
        int drops = 4 + (int) (warmth * 12.0);
        setHumidity(columnX, columnZ, humidity(columnX, columnZ) + drops);
        return drops;
    }

    /** Drops that have fallen as rain through this grid since it was made. */
    public int rained() {
        return rained;
    }

    private LevelChunk chunk(int columnX, int columnZ) {
        return level.getChunkSource().getChunkNow(columnX, columnZ);
    }

    private static BlockPos centre(int columnX, int columnZ) {
        return new BlockPos((columnX << 4) + 8, 0, (columnZ << 4) + 8);
    }
}
