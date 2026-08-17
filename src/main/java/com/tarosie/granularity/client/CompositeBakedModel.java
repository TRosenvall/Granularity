package com.tarosie.granularity.client;

import com.tarosie.granularity.core.Composition;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * Cobblestone, rendered as nine individual stones.
 *
 * <p>Exactly the treatment the ores get, with a different notion of what a region is. Vanilla's
 * cobblestone has well-defined lighter areas separated by darker mortar lines, and those lighter
 * areas <i>are</i> the stones — so the nine largest become nine overlays, one per slot, each
 * carrying the <b>exact</b> colour of the grain in that slot. Eight granite and a diamond means one
 * of the nine visibly is the diamond.
 *
 * <p>Everything left over — mortar, offcuts, the smaller shapes — stays on the base and takes the
 * <i>average</i>, the same way natural stone does. So a cobblestone reads as a specific set of
 * stones set in a matrix the colour of their mean.
 *
 * <p>The quad list never varies: a cobblestone always has nine stones. All the variation is in the
 * tints, which {@link CompositeBlockColour} resolves per block from the stored composition. So the
 * geometry is assembled once at bake time and handed out unchanged.
 */
public class CompositeBakedModel implements BakedModel {

    private static final ChunkRenderTypeSet RENDER_TYPES =
            ChunkRenderTypeSet.of(RenderType.cutoutMipped());

    private static final Direction[] SIDES = Direction.values();
    private static final int NULL_SIDE = SIDES.length;

    private final BakedModel base;
    private final List<List<BakedQuad>> quadsBySide;

    public CompositeBakedModel(BakedModel base, List<BakedModel> shapes) {
        this.base = base;
        this.quadsBySide = new ArrayList<>(NULL_SIDE + 1);

        RandomSource random = RandomSource.create(0L);
        for (int sideIndex = 0; sideIndex <= NULL_SIDE; sideIndex++) {
            Direction side = sideIndex < SIDES.length ? SIDES[sideIndex] : null;
            List<BakedQuad> quads = new ArrayList<>(base.getQuads(null, side, random));
            for (BakedModel shape : shapes) {
                quads.addAll(shape.getQuads(null, side, random));
            }
            quadsBySide.add(List.copyOf(quads));
        }
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData data, @Nullable RenderType renderType) {
        return quadsBySide.get(side == null ? NULL_SIDE : side.ordinal());
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return getQuads(state, side, rand, ModelData.EMPTY, null);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return RENDER_TYPES;
    }

    /** Slot count and shape count are the same nine, and the renderer assumes it. */
    public static int shapeCount() {
        return Composition.SLOTS;
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
        return base.getTransforms();
    }
}
