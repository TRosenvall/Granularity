package com.tarosie.granularity.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tarosie.granularity.client.MovingComposites;
import com.tarosie.granularity.content.CompositePistonBlock;
import com.tarosie.granularity.content.CompositePistonHeadBlock;
import com.tarosie.granularity.content.GranularityBlocks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Animates a Granularity piston's stroke in the piston's own stone.
 *
 * <p>{@code PistonHeadRenderer.render} decides what to draw by naming {@code Blocks.PISTON_HEAD}
 * twice — once to recognise a head in flight and shorten its arm, once to build the head that trails
 * a retracting piston. Neither matches ours, so without this an extending Granularity piston draws
 * its head through the fall-through branch: no arm shortening, and offset to the wrong position.
 *
 * <p>Rather than surgery on those two expressions, this takes the whole method for our two blocks
 * and leaves it untouched for every other. It is the same branching, with the head block swapped and
 * the model data supplied.
 *
 * <p>Where that data comes from differs per branch, which is the reason it is worth writing out. An
 * <b>extending</b> head is lit from the piston that is pushing it, and that piston is still standing
 * there to be asked. A <b>retracting</b> piston is drawing itself, and by then it has been replaced
 * by {@code MovingPistonBlock} — so the answer comes from {@link MovingComposites}, which kept it.
 */
@Mixin(PistonHeadRenderer.class)
public class PistonHeadRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void granularity$renderCompositeStroke(
            PistonMovingBlockEntity moving, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay,
            CallbackInfo callback) {
        Level level = moving.getLevel();
        BlockState movedState = moving.getMovedState();
        if (level == null || movedState.isAir()) {
            return;
        }
        boolean isHead = movedState.getBlock() instanceof CompositePistonHeadBlock;
        boolean isPiston = movedState.getBlock() instanceof CompositePistonBlock;
        if (!isHead && !isPiston) {
            return;
        }

        BlockRenderDispatcher renderer =
                net.minecraft.client.Minecraft.getInstance().getBlockRenderer();
        BlockPos from = moving.getBlockPos().relative(moving.getMovementDirection().getOpposite());

        ModelBlockRenderer.enableCaching();
        poseStack.pushPose();
        poseStack.translate(moving.getXOff(partialTick), moving.getYOff(partialTick),
                moving.getZOff(partialTick));

        if (isHead && moving.getProgress(partialTick) <= 4.0F) {
            // `from` is the piston that is pushing this head out, and it has not moved.
            BlockState head = movedState.setValue(
                    PistonHeadBlock.SHORT, moving.getProgress(partialTick) <= 0.5F);
            MovingComposites.draw(from, head, poseStack, bufferSource, level, false, packedOverlay,
                    renderer, MovingComposites.appearanceAt(level, from));
        } else if (moving.isSourcePiston() && !moving.isExtending()) {
            // The piston is pulling itself back in: it draws its own body one step along, and a head
            // in front of it that is about to be removed. Both wear the body's stone.
            BlockPos body = from.relative(moving.getMovementDirection());
            ModelData data = MovingComposites.appearanceAt(level, body);
            // The head is drawn in the empty space ahead of the piston, and tints are resolved by
            // position — so without this it would be coloured by whatever that space last held, or
            // by nothing at all. A head's timber is the piston's timber; say so.
            MovingComposites.Remembered piston = MovingComposites.at(body);
            if (piston != null) {
                MovingComposites.rememberAs(from, piston);
            }
            BlockState head = GranularityBlocks.PISTON_HEAD.get().defaultBlockState()
                    .setValue(PistonHeadBlock.TYPE, PistonType.DEFAULT)
                    .setValue(PistonHeadBlock.FACING, movedState.getValue(PistonBaseBlock.FACING))
                    .setValue(PistonHeadBlock.SHORT, moving.getProgress(partialTick) >= 0.5F);
            MovingComposites.draw(from, head, poseStack, bufferSource, level, false, packedOverlay,
                    renderer, data);
            poseStack.popPose();
            poseStack.pushPose();
            MovingComposites.draw(body, movedState.setValue(PistonBaseBlock.EXTENDED, true),
                    poseStack, bufferSource, level, true, packedOverlay, renderer, data);
        } else {
            // A piston being shoved along as cargo by some other piston.
            MovingComposites.draw(from, movedState, poseStack, bufferSource, level, false,
                    packedOverlay, renderer, MovingComposites.appearanceAt(level, from));
        }

        poseStack.popPose();
        ModelBlockRenderer.clearCache();
        callback.cancel();
    }
}
