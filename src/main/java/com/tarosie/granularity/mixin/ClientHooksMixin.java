package com.tarosie.granularity.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tarosie.granularity.client.MovingComposites;
import com.tarosie.granularity.content.CompositeStone;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a composite's colours on it while a piston is moving it.
 *
 * <p>{@code ClientHooks.renderPistonMovedBlocks} draws a block in motion with
 * {@code ModelData.EMPTY}, twice over — once to choose render types and once to build the quads.
 * It has no choice: a moving block has no block entity, so there is no data to pass. The visible
 * result on a Granularity block is a two-tick flash of bare grey at each end of every piston stroke,
 * on the block being pushed and on a piston retracting itself.
 *
 * <p>{@link MovingComposites} keeps that data for exactly as long as it is needed, and {@code pos} —
 * the position the renderer uses to light the block — is the position the block left, so it is also
 * the key. This runs the same five lines the hook would have run, with the data filled in.
 *
 * <p>Scoped to {@link CompositeStone}, and it stands aside when nothing was remembered, so every
 * other block in the game still takes vanilla's path.
 */
@Mixin(value = ClientHooks.class, remap = false)
public class ClientHooksMixin {

    @Inject(method = "renderPistonMovedBlocks", at = @At("HEAD"), cancellable = true)
    private static void granularity$keepCompositeTintsInMotion(
            BlockPos pos, BlockState state, PoseStack poseStack, MultiBufferSource bufferSource,
            Level level, boolean checkSides, int packedOverlay, BlockRenderDispatcher blockRenderer,
            CallbackInfo callback) {
        if (!(state.getBlock() instanceof CompositeStone)) {
            return;
        }
        ModelData data = MovingComposites.modelDataAt(pos);
        if (data == ModelData.EMPTY) {
            return;
        }
        MovingComposites.draw(pos, state, poseStack, bufferSource, level, checkSides,
                packedOverlay, blockRenderer, data);
        callback.cancel();
    }
}
