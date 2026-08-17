package com.tarosie.granularity.client;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.content.Fouling;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * Stops a stonecutter's saw when moss has packed against the blade.
 *
 * <p>{@link Fouling} already says a machine with moss over its working face has stopped working, and
 * a stonecutter is the first one whose stopping is <i>visible</i>. A furnace that will not smelt looks
 * the same as one that will; a saw that will not turn should not go on turning. A blade spinning
 * merrily under a coat of moss reads as a bug rather than as a mechanic, which is the whole reason
 * this class exists.
 *
 * <h2>How you stop an animation</h2>
 * You do not. An animated sprite is one slot in the atlas whose pixels are rewritten every tick, and
 * nothing in the model can ask it to hold still. What you can do is draw the quad from a
 * <b>different</b> sprite — a still copy that no ticker touches — which is what
 * {@code extract_stoneware.py}'s {@code write_still} produces from frame 0 of vanilla's strip.
 *
 * <h2>Matching on the sprite, not the tint or the geometry</h2>
 * The swap replaces every quad currently drawn with the animated saw, whatever its tint index or
 * direction. That is deliberate and it is what makes this wrapper order-independent: it can sit
 * outside {@link OverlayBakedModel} and see the moss copies as well as the base quads, and it will not
 * touch them, because a moss quad is drawn with a moss sprite. Matching on {@code METAL_TINT} instead
 * would have coupled this to the model's tint layout and broken the day a second metal part appeared.
 *
 * <h2>One rule, asked twice</h2>
 * Whether the blade is jammed is decided by {@link Fouling#bladeFouled(com.tarosie.granularity.content.Coating,
 * Direction)}, the same call the block uses to refuse to open. A blade that looked stopped but still
 * opened its menu — or the reverse — would be worse than either behaviour on its own.
 */
public class StoppedBladeModel extends BakedModelWrapper<BakedModel> {

    /** Vanilla's animated strip: the sprite this looks for. */
    private static final ResourceLocation TURNING =
            ResourceLocation.withDefaultNamespace("block/stonecutter_saw");

    /** Our copy of its first frame, which nothing animates. */
    private static final ResourceLocation STILL =
            ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "block/stonecutter_saw_still");

    public StoppedBladeModel(BakedModel wrapped) {
        super(wrapped);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                    RandomSource rand, ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> quads = originalModel.getQuads(state, side, rand, data, renderType);
        if (quads.isEmpty() || !jammed(state, data)) {
            return quads;
        }
        TextureAtlasSprite still = OverlayBakedModel.sprite(STILL);
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            out.add(quad.getSprite().contents().name().equals(TURNING)
                    ? OverlayBakedModel.retexture(quad, still, quad.getTintIndex())
                    : quad);
        }
        return out;
    }

    /**
     * Whether this stonecutter's blade is packed with moss.
     *
     * <p>Answered from the blockstate and the model data alone — no level and no block entity, because
     * a baked model has neither. Both halves are already to hand: the facing is on the state, and the
     * coating is the same one {@link OverlayBakedModel} is about to draw moss from.
     */
    private static boolean jammed(@Nullable BlockState state, ModelData data) {
        if (state == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return false;
        }
        OverlayBakedModel.State overlays = data.get(OverlayBakedModel.OVERLAYS);
        if (overlays == null) {
            return false;
        }
        return Fouling.bladeFouled(overlays.lower(),
                state.getValue(BlockStateProperties.HORIZONTAL_FACING));
    }

    /** See {@link OverlayBakedModel#applyTransform}: a wrapper that returns the wrapped model is lost. */
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
}
