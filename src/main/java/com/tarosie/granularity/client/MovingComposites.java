package com.tarosie.granularity.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tarosie.granularity.content.CompositionHolder;
import com.tarosie.granularity.content.Dyes;
import com.tarosie.granularity.core.Composition;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * What a composite looked like, kept just long enough for it to be pushed.
 *
 * <p>A block in motion is not a block. Vanilla replaces it with {@code MovingPistonBlock} carrying
 * only a {@code BlockState} and animates that for two ticks — so for those two ticks there is no
 * block entity anywhere to ask, and NeoForge's {@code renderPistonMovedBlocks} draws the model with
 * {@code ModelData.EMPTY}. For an ordinary block that is exactly right. For ours it means the tints
 * vanish: a slate wall flashes bare grey the moment a piston touches it, and snaps back on landing.
 *
 * <p>{@link com.tarosie.granularity.content.PistonMoves} solves the same problem on the server by
 * setting the data aside before the push. This is its client-side twin, and it hangs the data on the
 * same hook: the block entity's own removal, which is the last instant the appearance exists.
 *
 * <p>Keyed by the position the block <b>left</b>, which is precisely what the renderer hands back.
 * For a pushed block the moving entity sits at the destination and offsets back by the movement to
 * light the block, landing on the source; for a retracting piston — which animates in place — the
 * same arithmetic lands on the piston itself. Both are where the entity was removed from.
 */
public final class MovingComposites {

    /**
     * Bounded, and deliberately not cleaned up on a schedule.
     *
     * <p>An entry is worth two ticks and is replaced the next time anything is removed from that
     * position, so a stale one can only be read while a block is animating at the very spot it was
     * left — where it is the right answer anyway. The bound is what keeps a long session from
     * accumulating one entry per block ever broken.
     */
    private static final int KEPT = 512;

    private static final Map<BlockPos, Remembered> RECENT =
            new LinkedHashMap<>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<BlockPos, Remembered> eldest) {
                    return size() > KEPT;
                }
            };

    /**
     * Everything about how a composite looked, which is two separate things.
     *
     * <p>{@link ModelData} decides <i>which quads are drawn</i> — the overlays growing on it. The
     * composition decides <i>what colour they are</i>, and that does not travel through model data at
     * all: {@link CompositeBlockColour} is handed a level and a position and looks the block entity
     * up itself. So a moving block needs both remembered, and for a while only the first was, which
     * kept moss on a pushed wall and still let its stone go grey.
     */
    public record Remembered(Composition composition, @Nullable Composition upper,
                             Dyes dyes, Dyes upperDyes, @Nullable ResourceLocation wood,
                             @Nullable ResourceLocation metal, ModelData modelData) {
    }

    private MovingComposites() {
    }

    /**
     * Called from every composite block entity's {@code setRemoved}, client side only.
     *
     * <p>Unconditional rather than "only when a piston is involved", because at removal time there
     * is no way to tell the two apart — and a block that was simply broken costs one map entry that
     * nothing will ever read.
     */
    public static void remember(BlockEntity entity) {
        if (entity.getLevel() == null || !entity.getLevel().isClientSide
                || !(entity instanceof CompositionHolder holder)) {
            return;
        }
        Remembered kept = new Remembered(holder.composition(), holder.upper(), holder.dyes(),
                holder.upperDyes(), holder.wood(), holder.metal(), entity.getModelData());
        synchronized (RECENT) {
            RECENT.put(entity.getBlockPos().immutable(), kept);
        }
    }

    /**
     * Files an appearance under a position it never actually occupied.
     *
     * <p>For a block drawn somewhere its own data does not live. A retracting piston draws its head
     * in the empty space ahead of it, and that head's colours are not that space's business — they
     * are the piston's, by definition, because a head <i>is</i> the piston extended. Saying so
     * outright beats hoping the head's own entry is still cached at the spot it was removed from.
     */
    public static void rememberAs(BlockPos pos, Remembered kept) {
        synchronized (RECENT) {
            RECENT.put(pos.immutable(), kept);
        }
    }

    /** What was last seen at this position, or null if nothing was kept for it. */
    @Nullable
    public static Remembered at(BlockPos pos) {
        synchronized (RECENT) {
            return RECENT.get(pos);
        }
    }

    /** The overlays last seen at this position, or {@link ModelData#EMPTY} if none were kept. */
    public static ModelData modelDataAt(BlockPos pos) {
        Remembered kept = at(pos);
        return kept == null ? ModelData.EMPTY : kept.modelData();
    }

    /**
     * The appearance of whatever is — or was just now — at this position.
     *
     * <p>Prefers a block entity still standing there, because that is the live answer: a piston
     * pushing its head out has not moved, so its own composition can simply be read. Falls back to
     * what was remembered, for the blocks that are mid-flight and no longer anywhere.
     */
    public static ModelData appearanceAt(BlockGetter level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof CompositionHolder) {
            return entity.getModelData();
        }
        return modelDataAt(pos);
    }

    /**
     * Draws a block in motion with the model data it should have had.
     *
     * <p>The body of NeoForge's {@code renderPistonMovedBlocks}, with the two {@code ModelData.EMPTY}
     * arguments filled in. Shared by the two places that need it: the general case of any composite
     * being pushed, and a piston drawing its own arm.
     */
    public static void draw(BlockPos pos, BlockState state, PoseStack poseStack,
                            MultiBufferSource bufferSource, Level level, boolean checkSides,
                            int packedOverlay, BlockRenderDispatcher blockRenderer, ModelData data) {
        BakedModel model = blockRenderer.getBlockModel(state);
        for (RenderType renderType : model.getRenderTypes(
                state, RandomSource.create(state.getSeed(pos)), data)) {
            VertexConsumer consumer =
                    bufferSource.getBuffer(RenderTypeHelper.getMovingBlockRenderType(renderType));
            blockRenderer.getModelRenderer().tesselateBlock(level, model, state, pos, poseStack,
                    consumer, checkSides, RandomSource.create(), state.getSeed(pos), packedOverlay,
                    data, renderType);
        }
    }
}
