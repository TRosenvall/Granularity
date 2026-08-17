package com.tarosie.granularity.mixin;

import com.tarosie.granularity.content.GranularityBlocks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.StonecutterMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a Granularity stonecutter keep its own menu open.
 *
 * <p>{@code StonecutterMenu.stillValid} asks by <b>name</b> whether the block you opened is still
 * there: {@code stillValid(access, player, Blocks.STONECUTTER)}, which reads the block at the
 * position and tests {@code is(Blocks.STONECUTTER)}. Ours is a different block, so the answer is no
 * from the very first tick and the screen closes the instant it opens.
 *
 * <p>This is the same shape of gap as {@link PistonHeadBlockMixin}: vanilla had no reason to allow
 * for a second stonecutter, and every other part of the menu already works on ours. The recipe list is
 * built from {@code RecipeType.STONECUTTING}, which is exactly what
 * {@link com.tarosie.granularity.recipe.StoneCutRecipe} registers into, and the block itself extends
 * {@code StonecutterBlock} so the menu provider, the shape and the statistic are all inherited.
 *
 * <p>Injecting at the head rather than redirecting the {@code Blocks.STONECUTTER} field read, because
 * the field is read once and used inside a lambda that also needs the position — the reach check has
 * to be repeated here rather than reused. Failing to cancel leaves vanilla's own check to run, so a
 * real vanilla stonecutter still behaves exactly as it did.
 */
@Mixin(StonecutterMenu.class)
public class StonecutterMenuMixin {

    @Shadow
    @Final
    private ContainerLevelAccess access;

    /** Vanilla's own reach distance for a container, so ours is neither longer nor shorter. */
    private static final double REACH = 4.0;

    /**
     * Also the place a jammed blade closes a menu that is already open.
     *
     * <p>{@code CompositeStonecutterBlock.getMenuProvider} refuses to open one, but moss can arrive
     * while the screen is up — another player, or a brush put away mid-use — and a menu that went on
     * working would contradict the blade that had visibly stopped. Answering "no longer valid" is
     * exactly the vanilla mechanism for a container whose block is gone, used here for a container
     * whose block has jammed.
     */
    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void granularity$oursIsAStonecutterToo(Player player, CallbackInfoReturnable<Boolean> callback) {
        boolean ours = access.evaluate(
                (level, pos) -> {
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                    return state.is(GranularityBlocks.STONECUTTER.get())
                            && !com.tarosie.granularity.content.Fouling.bladeFouled(level, pos, state)
                            && player.canInteractWithBlock(pos, REACH);
                },
                false);
        if (ours) {
            callback.setReturnValue(true);
        }
    }
}
