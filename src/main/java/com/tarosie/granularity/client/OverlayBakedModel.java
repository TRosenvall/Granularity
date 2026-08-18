package com.tarosie.granularity.client;

import com.tarosie.granularity.content.Coating;
import com.tarosie.granularity.content.Dyes;
import com.tarosie.granularity.content.Overlay;
import com.tarosie.granularity.core.Composition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Draws whatever is growing on a block, without the block owning a model for it.
 *
 * <p>An overlay covers exactly the stone it sits on, so its geometry is the block's own geometry.
 * That is the whole idea here: rather than baking a model per overlay per shape per rotation — which
 * is 2<sup>N</sup> models and cannot admit another mod's overlay at all — this takes the quads the
 * block already produced and re-issues them wearing the overlay's sprite.
 *
 * <p>Because those quads are collected <b>after</b> the blockstate has done its work, everything the
 * blockstate does comes along free: a stair's rotation is already baked into the quads being copied,
 * a wall's multipart selection has already happened, and a slab's half is already the right half.
 * There is no per-rotation, per-shape or per-variant anything.
 *
 * <p>Which quads to copy is decided by tint index rather than by geometry. Each composite model
 * draws ten coincident layers — a matrix at tint 0 and nine stones at 1–9 — so tint 0 is exactly one
 * copy of the block's surface. A double slab's upper half repeats that at
 * {@link CompositeBlockColour#UPPER_BASE}, which conveniently makes "the upper half's surface" just
 * as easy to name.
 */
public class OverlayBakedModel extends BakedModelWrapper<BakedModel> {

    /** The overlays a block entity is carrying, delivered through {@link ModelData}. */
    public static final ModelProperty<State> OVERLAYS = new ModelProperty<>();

    /**
     * A block's overlays and its dye, per half.
     *
     * <p>Dye rides in the same property rather than a second one because it is answered in the same
     * pass and by the same information — a face — and because two properties would mean two chances
     * for a block entity to hand over one and forget the other.
     *
     * @param lower the block's overlays, or a slab's lower half
     * @param upper a double slab's upper half; empty for everything else
     * @param lowerDyes the block's dyed faces, or a slab's lower half
     * @param upperDyes a double slab's upper half; empty for everything else
     * @param whole whether a dyed face tints the grains too, not only the matrix between them —
     *              see {@link com.tarosie.granularity.content.GranularityTags#DYED_WHOLE}
     */
    public record State(Coating lower, Coating upper, Dyes lowerDyes, Dyes upperDyes, boolean whole) {

        public static final State NONE =
                new State(Coating.NONE, Coating.NONE, Dyes.NONE, Dyes.NONE, false);

        /** For the blocks that have mortar to dye, which is all of them but gravel. */
        public State(Coating lower, Coating upper, Dyes lowerDyes, Dyes upperDyes) {
            this(lower, upper, lowerDyes, upperDyes, false);
        }

        public boolean isEmpty() {
            return lower.isEmpty() && upper.isEmpty() && lowerDyes.isEmpty() && upperDyes.isEmpty();
        }
    }

    public OverlayBakedModel(BakedModel wrapped) {
        super(wrapped);
    }

    /**
     * Keeps the wrapper alive through the item pipeline.
     *
     * <p>{@link BakedModelWrapper} implements both of these as {@code return originalModel.…}, which
     * hands back the <b>unwrapped</b> model. {@code ItemRenderer} reassigns from the first and
     * iterates the second, so an overlay model resolved for a stack was discarded before a single
     * quad was asked of it: the overrides ran, chose the right model, and it was then thrown away.
     * Applying the transform for its side effect on the pose stack and returning {@code this} is what
     * makes the wrapper survive to be drawn.
     *
     * <p>Blocks were never affected — chunk rendering calls neither.
     */
    @Override
    public BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext context,
                                     com.mojang.blaze3d.vertex.PoseStack poseStack, boolean leftHand) {
        originalModel.applyTransform(context, poseStack, leftHand);
        return this;
    }

    @Override
    public List<BakedModel> getRenderPasses(net.minecraft.world.item.ItemStack stack, boolean fabulous) {
        return List.of(this);
    }

    /**
     * Routes the plain three-argument call through the one that can actually answer.
     *
     * <p>Not redundant. {@code ItemRenderer} asks for quads with the three-argument form, and
     * {@link BakedModelWrapper} overrides it to delegate straight to the wrapped model — so without
     * this, everything below is simply skipped whenever an item is drawn, and overlays appear on
     * placed blocks but never in the inventory. Chunk rendering uses the five-argument form and was
     * always fine, which is exactly why the gap was invisible from the block side.
     */
    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return getQuads(state, side, rand, ModelData.EMPTY, null);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                    RandomSource rand, ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> base = originalModel.getQuads(state, side, rand, data, renderType);
        State overlays = data.get(OVERLAYS);
        if (overlays == null || overlays.isEmpty() || base.isEmpty()) {
            return base;
        }
        List<BakedQuad> out = new ArrayList<>(base.size() + 8);
        for (BakedQuad quad : base) {
            out.add(dyed(quad, overlays));
        }
        // Against `base`, not `out`: the dyed copies no longer carry the tint index that says "this is
        // the block's surface", which is exactly what an overlay looks for.
        addOverlays(out, base, overlays.lower(), 0);
        addOverlays(out, base, overlays.upper(), CompositeBlockColour.UPPER_BASE);
        return out;
    }

    /**
     * The same quad, addressed to the colour of the face it is on.
     *
     * <p>This is the whole of per-face dye. Vanilla's tint API is
     * {@code getColor(state, level, pos, tintIndex)} — there is no face in it, and no way to add one
     * — so a colour handler cannot be asked "what colour is the north side". What it <i>can</i> be
     * asked is about a tint index, and this wrapper is already deciding what index each quad carries.
     * So a matrix quad on a dyed face is re-issued at {@code DYE_BASE + face}, and the handler answers
     * those six indices from the six faces. The block model knows nothing about any of it.
     *
     * <p>{@link BakedQuad#getDirection()} is the <b>world</b> direction, because these quads were
     * collected after the blockstate rotated them — so a rotated stair's north side is north, and
     * stairs and walls come along free exactly as they did for overlays. Baking six tint indices into
     * the models instead would have rotated the dye with the block.
     *
     * <p>Only the matrix is dyed, so the nine stones keep the colour of the stone they are — unless
     * the block is one whose grains leave no matrix visible, in which case all ten layers of the dyed
     * face take the colour. Gravel is the only such block; see
     * {@link com.tarosie.granularity.content.GranularityTags#DYED_WHOLE}. Either way it is decided
     * <b>per face</b>, so an undyed side of the same block still shows every grain.
     */
    private static BakedQuad dyed(BakedQuad quad, State state) {
        int tint = quad.getTintIndex();
        // Below zero is an overlay we just added; above the upper half's nine is timber, metal or a
        // machined face. None of those is stone or mortar, and none of them is ever dyed.
        if (tint < 0 || tint > CompositeBlockColour.UPPER_BASE + Composition.SLOTS) {
            return quad;
        }
        boolean upper = tint >= CompositeBlockColour.UPPER_BASE;
        int slot = upper ? tint - CompositeBlockColour.UPPER_BASE : tint;
        if (slot != 0 && !state.whole()) {
            return quad;
        }
        Dyes dyes = upper ? state.upperDyes() : state.lowerDyes();
        if (dyes.isEmpty() || dyes.on(quad.getDirection()) == null) {
            return quad;
        }
        int base = upper ? CompositeBlockColour.UPPER_DYE_BASE : CompositeBlockColour.DYE_BASE;
        return retint(quad, base + quad.getDirection().get3DDataValue());
    }

    /**
     * The same quad under a different tint index.
     *
     * <p>The vertex array is copied rather than shared. Sharing would work today — nothing downstream
     * of here writes to it — but a quad handed out twice with one array between them is the kind of
     * thing that fails much later and somewhere else, and this only runs for faces that are dyed.
     */
    private static BakedQuad retint(BakedQuad quad, int tintIndex) {
        return new BakedQuad(quad.getVertices().clone(), tintIndex, quad.getDirection(),
                quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    /**
     * Re-issues the surface at {@code tint} once per overlay, each wearing its own sprite.
     *
     * <p>The face test is what makes an overlay something that could plausibly grow rather than
     * something painted on: moss takes the top and the shaded sides and leaves the underside alone.
     * {@link BakedQuad#getDirection()} answers it, and answers it in <b>world</b> terms — these quads
     * were collected after the blockstate rotated them, so a stair's top is the top. That is the same
     * property that made stairs and walls free when overlays were built.
     *
     * <p>The sprite is resolved per face too, so one overlay can be green on top and a fringe down
     * the sides. Resolved once per direction rather than per quad: a chunk holds a great many quads
     * and an atlas lookup is a hash map.
     *
     * <p>{@link CompositeBlockColour#WOOD_TINT} and {@code METAL_TINT} count as part of the block's
     * surface for the base layer, even though neither is stone. Moss does not care what it is growing
     * on, and a piston whose plate stayed conspicuously clean while the rest went green would look
     * like a bug rather than a decision.
     */
    private static void addOverlays(List<BakedQuad> out, List<BakedQuad> base,
                                    Coating coating, int tint) {
        if (coating.isEmpty()) {
            return;
        }
        for (Map.Entry<Overlay, Integer> entry : coating.faces().entrySet()) {
            Overlay overlay = entry.getKey();
            int mask = entry.getValue();
            TextureAtlasSprite[] byFace = new TextureAtlasSprite[Direction.values().length];
            for (BakedQuad quad : base) {
                boolean surface = quad.getTintIndex() == tint
                        || (tint == 0 && (quad.getTintIndex() == CompositeBlockColour.WOOD_TINT
                                || quad.getTintIndex() == CompositeBlockColour.METAL_TINT
                                || quad.getTintIndex() == CompositeBlockColour.PLAIN_TINT));
                if (!surface) {
                    continue;
                }
                Direction face = quad.getDirection();
                if (!Coating.covers(mask, face)) {
                    continue;
                }
                int slot = face.ordinal();
                if (byFace[slot] == null) {
                    byFace[slot] = sprite(overlay.textureFor(face));
                }
                if (byFace[slot] != null) {
                    // A tinted overlay keeps the surface's tint index, so its greyscale sprite is
                    // multiplied by the block's own colour — see Overlay.tinted. Everything else drops
                    // to -1 and draws exactly as its sprite was painted, because moss is green
                    // whatever it grew on.
                    out.add(overlay.tinted()
                            ? retexture(quad, byFace[slot], quad.getTintIndex())
                            : retexture(quad, byFace[slot]));
                }
            }
        }
    }

    @Nullable
    static TextureAtlasSprite sprite(net.minecraft.resources.ResourceLocation texture) {
        // Looked up rather than cached across calls: the atlas is rebuilt on every resource reload,
        // and a sprite held across one is a stale pointer that renders as a torn piece of some other
        // texture. The per-call array above is safe because it lives for one getQuads.
        return Minecraft.getInstance()
                .getModelManager()
                .getAtlas(InventoryMenu.BLOCK_ATLAS)
                .getSprite(texture);
    }

    /**
     * The same quad, wearing a different sprite.
     *
     * <p>A baked quad's UVs are already absolute coordinates into the atlas, so swapping the sprite
     * means mapping each vertex out of the old sprite's rectangle and into the new one's. Doing it
     * proportionally rather than by offset is what lets an overlay be a different resolution from
     * the stone it covers.
     *
     * <p>The tint index is dropped to -1. An overlay is growth, not stone, so its colour must not
     * come from the composition — a mossy gneiss and a mossy gabbro grow the same moss.
     */
    private static BakedQuad retexture(BakedQuad quad, TextureAtlasSprite to) {
        return retexture(quad, to, -1);
    }

    /**
     * The same swap, keeping a tint index.
     *
     * <p>{@link FinishBakedModel} needs this: a smooth block is the cobbled block's base layer wearing
     * a different sprite, and it must keep tint 0 so the composition still colours it. An overlay is
     * the opposite case and passes -1, because moss is growth rather than stone.
     */
    static BakedQuad retexture(BakedQuad quad, TextureAtlasSprite to, int tintIndex) {
        TextureAtlasSprite from = quad.getSprite();
        int[] vertices = quad.getVertices().clone();
        // Derived rather than assumed, so a vertex format change cannot silently corrupt the UVs.
        int stride = vertices.length / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            int u = vertex * stride + 4;
            vertices[u] = Float.floatToRawIntBits(remap(
                    Float.intBitsToFloat(vertices[u]), from.getU0(), from.getU1(), to.getU0(), to.getU1()));
            vertices[u + 1] = Float.floatToRawIntBits(remap(
                    Float.intBitsToFloat(vertices[u + 1]), from.getV0(), from.getV1(), to.getV0(), to.getV1()));
        }
        return new BakedQuad(vertices, tintIndex, quad.getDirection(), to, quad.isShade(),
                quad.hasAmbientOcclusion());
    }

    private static float remap(float value, float fromMin, float fromMax, float toMin, float toMax) {
        float span = fromMax - fromMin;
        if (span == 0.0F) {
            return toMin;
        }
        return toMin + (value - fromMin) / span * (toMax - toMin);
    }
}
