package com.tarosie.granularity.mixin;

import com.tarosie.granularity.content.CompositeStone;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets pistons push blocks that remember what they are made of.
 *
 * <p>{@code PistonBaseBlock.isPushable} ends with {@code return !state.hasBlockEntity()}, and that
 * line is not about pistons — it is the last word on <b>every</b> block in the game. Vanilla refuses
 * to move a block entity because it has nowhere to put the data: a pushed block is replaced by
 * {@code MovingPistonBlock} carrying only a {@code BlockState}, and whatever the block remembered is
 * gone. So the rule is really "no block may carry data through a piston", and every Granularity
 * composite fell under it — cobblestone, slabs, stairs and walls could not be pushed at all.
 *
 * <p>This redirect answers "no block entity" for composites only, which makes them pushable, and
 * {@link com.tarosie.granularity.content.PistonMoves} supplies the missing half: it copies each
 * block's data aside before the push and restores it when the block lands. Together they satisfy the
 * condition vanilla was protecting rather than merely evading it.
 *
 * <p>Scoped to {@link CompositeStone}. Another mod's machine still refuses to move, because its data
 * genuinely would be lost.
 */
@Mixin(PistonBaseBlock.class)
public class PistonBaseBlockMixin {

    @Redirect(
            method = "isPushable",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;hasBlockEntity()Z"))
    private static boolean granularity$compositesMayMove(BlockState state) {
        return state.hasBlockEntity() && !(state.getBlock() instanceof CompositeStone);
    }
}
