package com.tarosie.granularity.mixin;

import com.tarosie.granularity.content.CompositePistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a piston head sit on a Granularity piston.
 *
 * <p>{@code PistonHeadBlock.isFittingBase} asks whether the block behind a head is the piston that
 * pushed it, and answers by <b>name</b>: {@code Blocks.PISTON} or {@code Blocks.STICKY_PISTON} and
 * nothing else. That predicate is what {@code canSurvive} is built on, so a head extended by our
 * piston finds no base it recognises and pops off the instant it is placed — the piston would extend
 * and immediately drop its own head as an item.
 *
 * <p>Vanilla has no reason to allow for another piston, so this is a gap rather than a decision, and
 * widening the name check is the whole fix. Everything else about extension already works on ours:
 * {@code moveBlocks} builds the head from {@code this.isSticky} rather than from the block's
 * identity, so a composite piston gets a correct, ordinary vanilla head.
 *
 * <p>That head is drawn in vanilla's own grey — it is a vanilla block, with no block entity and so no
 * way to be handed a composition. An extended Granularity piston therefore shows a tinted body and an
 * untinted arm. Closing that means a head block of our own, which means redirecting the three places
 * {@code moveBlocks} names {@code Blocks.PISTON_HEAD}; it is deliberately not attempted here.
 */
@Mixin(PistonHeadBlock.class)
public class PistonHeadBlockMixin {

    /**
     * Note the argument order. Vanilla declares this {@code isFittingBase(baseState, extendedState)},
     * but the first argument is the <b>head's</b> state — it is the one whose TYPE and FACING are
     * read — and the second is the piston behind it. The names here are the accurate ones.
     */
    @Inject(method = "isFittingBase", at = @At("HEAD"), cancellable = true)
    private void granularity$compositePistonsFitToo(BlockState headState, BlockState baseState,
                                                    CallbackInfoReturnable<Boolean> callback) {
        if (!(baseState.getBlock() instanceof CompositePistonBlock)) {
            return;
        }
        // A Granularity piston is never sticky, so only a plain head can belong to one. Facing has to
        // match for the same reason vanilla checks it: two pistons at right angles are not a pair.
        boolean fits = headState.getValue(PistonHeadBlock.TYPE) == PistonType.DEFAULT
                && baseState.getValue(PistonBaseBlock.EXTENDED)
                && baseState.getValue(PistonBaseBlock.FACING)
                        == headState.getValue(PistonHeadBlock.FACING);
        if (fits) {
            callback.setReturnValue(true);
        }
    }
}
