package com.tarosie.granularity.client;

import com.tarosie.granularity.core.CompositionLayers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Renders natural stone from its derived composition — design §5's custom baked model.
 *
 * <h2>How position gets here</h2>
 * A {@link BakedModel} is handed a state, a random source and model data, but never its position —
 * which is a problem when the whole design is that appearance is a function of position, and §5
 * forbids the usual answer (a block entity renderer) at world-stone scale.
 *
 * <p>The way through is NeoForge's {@link #getModelData(BlockAndTintGetter, BlockPos, BlockState,
 * ModelData)}. {@code SectionCompiler} calls it once per block while building a chunk section,
 * <i>with</i> the position, and passes what it returns into {@code getQuads}. So the composition is
 * derived once per block per chunk rebuild and nothing happens per frame — exactly the budget §5
 * asks for, reached without a block entity anywhere.
 *
 * <h2>Why the quads are cached rather than precomputed</h2>
 * A block resolves to a combination of three overlay counts. Precomputing every one would be
 * 10×10×10 layer sets across seven sides — 7,000 lists, nearly all for combinations that cannot
 * occur, since the three counts share nine slots between them. So lists are built on first use and
 * kept. A world touches a few dozen, and after the first chunk rebuild meshing allocates nothing.
 */
public class CompositionBakedModel implements BakedModel {

    /** Carries the derived layers from {@code getModelData} to {@code getQuads}. */
    public static final ModelProperty<CompositionLayers> LAYERS = new ModelProperty<>();

    /**
     * Which band of the stone-to-deepslate ramp this block sits in — 0 at the top of the world.
     *
     * <p>Only set for blocks whose model was built with a ramp, which is <b>world-generated stone
     * alone</b>. A smelted block keeps the stone texture wherever it is put; the ramp describes where
     * rock was formed, and a block you made is not where it was formed.
     */
    public static final ModelProperty<Integer> DEPTH = new ModelProperty<>();

    private static final ChunkRenderTypeSet RENDER_TYPES =
            ChunkRenderTypeSet.of(RenderType.cutoutMipped());

    private static final Direction[] SIDES = Direction.values();
    private static final int NULL_SIDE = SIDES.length;

    /** Reused per meshing thread: the occlusion check runs for every block in a section. */
    private static final ThreadLocal<BlockPos.MutableBlockPos> NEIGHBOUR =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private final BakedModel base;

    /**
     * The depth ramp, top of the world first, or empty for a model that does not ramp.
     *
     * <p>Blended into the sprites rather than drawn as a translucent second layer, so a band costs
     * nothing at render time — it selects a different prebaked model, which is what this class
     * already does for composition. A crossfade done with alpha would have meant a second quad on
     * every stone block and a sorted pass to draw it.
     */
    private final List<BakedModel> depthBases;
    /** Six lists: ore a/b, precious a/b, gem a/b — in draw order, smaller of each pair on top. */
    private final List<List<BakedModel>> overlays;

    private final Map<Integer, List<BakedQuad>> quadCache = new ConcurrentHashMap<>();

    public CompositionBakedModel(BakedModel base, List<List<BakedModel>> overlays) {
        this(base, overlays, List.of());
    }

    public CompositionBakedModel(BakedModel base, List<List<BakedModel>> overlays,
                                 List<BakedModel> depthBases) {
        this.base = base;
        this.overlays = List.copyOf(overlays);
        this.depthBases = List.copyOf(depthBases);
    }

    /** Which ramp band a height falls in; 0 when this model does not ramp. */
    private int bandAt(BlockAndTintGetter level, BlockPos pos) {
        if (depthBases.isEmpty()) {
            return 0;
        }
        int min = level.getMinBuildHeight();
        int span = Math.max(1, level.getMaxBuildHeight() - min);
        // Measured down from the top, so band 0 is sky and the last band is bedrock. Taken from the
        // level's own height rather than a constant, so a dimension of a different depth ramps across
        // its whole range without being told about it.
        double t = (level.getMaxBuildHeight() - pos.getY()) / (double) span;
        int band = (int) (t * depthBases.size());
        return Math.max(0, Math.min(depthBases.size() - 1, band));
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        if (isFullyOccluded(level, pos, state)) {
            if (MeshingProfiler.ENABLED) {
                MeshingProfiler.recordSkipped();
            }
            return modelData;
        }

        long started = MeshingProfiler.ENABLED ? System.nanoTime() : 0L;
        CompositionLayers layers = ClientCompositions.layersAt(pos);
        if (MeshingProfiler.ENABLED) {
            MeshingProfiler.recordDerived(System.nanoTime() - started);
        }

        var derived = modelData.derive();
        if (layers != null) {
            derived.with(LAYERS, layers);
        }
        if (!depthBases.isEmpty()) {
            derived.with(DEPTH, bandAt(level, pos));
        }
        return derived.build();
    }

    /**
     * True when every face of this block is hidden by a neighbour, so it emits no quads at all.
     *
     * <p>{@code SectionCompiler} asks every block with a model for its model data, including ones
     * buried in solid rock whose faces are all culled moments later. Deriving a composition for
     * those is pure waste, and underground it is most of the section — measured at 8.7 µs a block,
     * a solid section costs 35.7 ms before this check and almost nothing after it.
     *
     * <p>Uses {@link Block#shouldRenderFace} rather than a hand-rolled occlusion test, because it
     * is the same predicate {@code ModelBlockRenderer} gates each face on. Any cheaper test risks
     * disagreeing with the renderer in some corner — a neighbour that occludes for one but not the
     * other — and the failure would be an invisible block face rather than a crash.
     */
    private static boolean isFullyOccluded(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        BlockPos.MutableBlockPos neighbour = NEIGHBOUR.get();
        for (Direction direction : SIDES) {
            neighbour.setWithOffset(pos, direction);
            if (Block.shouldRenderFace(state, level, pos, direction, neighbour)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData data, @Nullable RenderType renderType) {
        CompositionLayers layers = data.get(LAYERS);
        Integer depth = data.get(DEPTH);
        int band = depth == null ? 0 : depth;
        if (layers == null) {
            // No salt yet, or an inventory/particle context with no position. The base alone is a
            // correct-looking untinted stone rather than a missing model.
            return baseFor(band).getQuads(state, side, rand, data, renderType);
        }
        int sideIndex = side == null ? NULL_SIDE : side.ordinal();
        return quadCache.computeIfAbsent(key(layers, sideIndex, band),
                ignored -> assemble(layers, side, band));
    }

    /** Counts of the six overlays, in draw order. */
    private static int[] counts(CompositionLayers layers) {
        return new int[] {
                layers.ore().primary().count(), layers.ore().secondary().count(),
                layers.precious().primary().count(), layers.precious().secondary().count(),
                layers.gem().primary().count(), layers.gem().secondary().count(),
        };
    }

    private BakedModel baseFor(int band) {
        return depthBases.isEmpty() ? base : depthBases.get(Math.min(band, depthBases.size() - 1));
    }

    private int key(CompositionLayers layers, int sideIndex, int band) {
        int combination = 0;
        for (int count : counts(layers)) {
            combination = combination * 10 + count;
        }
        // The band joins the key rather than multiplying the work: a band is a different prebaked
        // base, so an entry per (combination, side, band) is assembled once and reused for every
        // block that matches. Without the ramp depthBases is empty and band is always 0, so nothing
        // that does not ramp pays for this.
        int bands = Math.max(1, depthBases.size());
        return (combination * (NULL_SIDE + 1) + sideIndex) * bands + band;
    }

    private List<BakedQuad> assemble(CompositionLayers layers, @Nullable Direction side, int band) {
        RandomSource random = RandomSource.create(0L);
        List<BakedQuad> quads = new ArrayList<>(baseFor(band).getQuads(null, side, random));
        int[] counts = counts(layers);
        for (int i = 0; i < counts.length; i++) {
            append(quads, overlays.get(i), counts[i], side, random);
        }
        return List.copyOf(quads);
    }

    private static void append(List<BakedQuad> quads, List<BakedModel> overlays, int count,
                               @Nullable Direction side, RandomSource random) {
        if (count > 0) {
            quads.addAll(overlays.get(count - 1).getQuads(null, side, random));
        }
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return getQuads(state, side, rand, ModelData.EMPTY, null);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return RENDER_TYPES;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return base.getParticleIcon();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ItemTransforms getTransforms() {
        // Delegate, so the item form gets the standard block display transforms. Returning
        // NO_TRANSFORMS renders the block at raw model scale -- vast in the GUI and in hand.
        return base.getTransforms();
    }
}
