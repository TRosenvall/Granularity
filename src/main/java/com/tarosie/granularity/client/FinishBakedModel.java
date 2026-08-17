package com.tarosie.granularity.client;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.Finish;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Draws a block in whatever finish it carries — and a double slab in whichever two.
 *
 * <h2>A finish is one sprite</h2>
 * Every shape here is <i>N boxes drawn ten times over</i>, once per tint: the base layer (tint 0, or
 * {@code UPPER_BASE} for a double's upper half) is the full solid box, and tints 1–9 are the nine
 * grains shown as separate stones. Working the stone is exactly what stops showing them. So any
 * finish but {@link Finish#COBBLED} is <b>the base layer wearing that finish's texture</b>, and
 * nothing else.
 *
 * <p>That is what makes a new style cost a single greyscale sprite. There is no authored model per
 * finish per shape — which could not exist anyway for a wall, whose blockstate is multipart and whose
 * baked model is assembled per state, with no file to write a counterpart to. Deriving from whatever
 * the cobbled model produced works for every shape however it was assembled, and works the day the
 * sprite is added.
 *
 * <p>The sprite stays greyscale so the composition still colours it: a mottled block of slate is
 * slate-coloured, which is why calling it "tuff" would be a claim this mod never makes. It is also
 * why {@link OverlayBakedModel#retexture} had to learn to keep a tint index — an overlay drops it to
 * -1 because moss is moss on any rock, and a finish is precisely the opposite.
 *
 * <h2>Order, and the two halves</h2>
 * This nests <i>inside</i> {@link OverlayBakedModel}, which passes its {@link ModelData} down, so the
 * finish is chosen first and moss and dye applied to whatever it chose. Moss grows on worked stone as
 * readily as on cobbled.
 *
 * <p>A double slab is two slabs and they need not have been worked alike. The tint index already says
 * which half a quad belongs to — 0–9 lower, 10–19 upper, the same split that dyes one half and not
 * the other — so each half is finished on its own with no geometry rebuilt.
 *
 * <h2>Surviving item rendering</h2>
 * See {@code docs/RENDERING.md}. A wrapper that must survive the item path has to answer <b>every</b>
 * method returning a model — {@link #applyTransform} and {@link #getRenderPasses} both — or the
 * renderer hands back the model this one exists to replace, and blocks look right while items do not.
 */
public class FinishBakedModel extends net.neoforged.neoforge.client.model.BakedModelWrapper<BakedModel> {

    /** What has been done to a block's two halves; the upper equals the lower for everything else. */
    public record State(Finish lower, Finish upper) {
        public static final State COBBLED = new State(Finish.COBBLED, Finish.COBBLED);

        public State(Finish both) {
            this(both, both);
        }

        /** True when neither half has been worked, so the model is drawn exactly as authored. */
        public boolean isBare() {
            return lower.showsGrains() && upper.showsGrains();
        }
    }

    /** The finishes a block entity is carrying, delivered through {@link ModelData}. */
    public static final ModelProperty<State> FINISH = new ModelProperty<>();

    /**
     * Set for an item form, whose finish was read off the stack because there is no model data.
     *
     * <p>{@link OverlayItemModel} has already decided by the time it hands back one of these.
     */
    @Nullable
    private final State forced;

    /** Derived quads, keyed by everything they depend on. Meshing asks once per section rebuild. */
    private final Map<Key, List<BakedQuad>> cache = new ConcurrentHashMap<>();

    private record Key(@Nullable BlockState state, @Nullable Direction side,
                       @Nullable RenderType renderType, State finishes) {
    }

    public FinishBakedModel(BakedModel cobbled) {
        this(cobbled, null);
    }

    /** A model fixed to one finish, for an item form. */
    public static FinishBakedModel of(BakedModel cobbled, Finish finish) {
        return new FinishBakedModel(cobbled, new State(finish));
    }

    private FinishBakedModel(BakedModel cobbled, @Nullable State forced) {
        super(cobbled);
        this.forced = forced;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                    RandomSource rand, ModelData data, @Nullable RenderType renderType) {
        State finishes = forced != null ? forced : data.get(FINISH);
        if (finishes == null || finishes.isBare()) {
            return originalModel.getQuads(state, side, rand, data, renderType);
        }
        return cache.computeIfAbsent(new Key(state, side, renderType, finishes),
                key -> worked(originalModel.getQuads(state, side, rand, data, renderType),
                        key.finishes()));
    }

    /** The data-less overload, which is the one item rendering uses. */
    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                    RandomSource rand) {
        return forced == null
                ? originalModel.getQuads(state, side, rand)
                : getQuads(state, side, rand, ModelData.EMPTY, null);
    }

    /**
     * Each half's base layer wearing its finish, with a worked half's grain layers dropped — showing
     * the nine stones separately is exactly what working the block stops doing.
     */
    private static List<BakedQuad> worked(List<BakedQuad> source, State finishes) {
        List<BakedQuad> out = new ArrayList<>(source.size());
        for (BakedQuad quad : source) {
            int tint = quad.getTintIndex();
            boolean lowerHalf = tint < CompositeBlockColour.UPPER_BASE;
            Finish finish = lowerHalf ? finishes.lower() : finishes.upper();
            if (finish.showsGrains()) {
                out.add(quad);
            } else if (tint == 0 || tint == CompositeBlockColour.UPPER_BASE) {
                out.add(OverlayBakedModel.retexture(quad, spriteFor(finish, quad.getDirection()), tint));
            }
        }
        return out;
    }

    /**
     * The sprite for one quad, chosen by which way the face points.
     *
     * <p>Most styles answer the same sprite whatever is asked, and that is still the cheap case. Some
     * do not: sandstone, cut sandstone, chiseled sandstone, deepslate and chiseled tuff are
     * {@code cube_column} or {@code cube_bottom_top} in vanilla, and drawing their side sprite on top
     * reads as a slab of bedding seen end-on — wrong in the way only a person notices, and it had been
     * shipping on {@link Finish#FINE} and {@link Finish#CHISELED_MOTTLED} unremarked until Timothy
     * caught it on Pebbled.
     *
     * <p>{@link BakedQuad#getDirection()} is the <b>world</b> direction, because these quads were
     * collected after the blockstate rotated them. That is the same property that made overlays and
     * dye work on stairs and walls for free, and it means a rotated stair's top face is still its top
     * face here — no per-shape knowledge required.
     */
    private static TextureAtlasSprite spriteFor(Finish finish, @Nullable Direction face) {
        boolean vertical = face != null && face.getAxis().isVertical();
        String texture = vertical
                ? finish.endTexture(face == Direction.DOWN)
                : finish.texture();
        return OverlayBakedModel.sprite(ResourceLocation.fromNamespaceAndPath(
                Granularity.MODID, "block/" + texture));
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context,
                                     com.mojang.blaze3d.vertex.PoseStack poseStack, boolean leftHand) {
        originalModel.applyTransform(context, poseStack, leftHand);
        return this;
    }

    @Override
    public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        return List.of(this);
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        State finishes = forced != null ? forced : data.get(FINISH);
        // The sides, not the ends: breaking particles should look like the face you were hitting, and
        // the sides are four faces out of six.
        return finishes == null || finishes.lower().showsGrains()
                ? super.getParticleIcon(data)
                : spriteFor(finishes.lower(), null);
    }
}
