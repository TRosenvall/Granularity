package com.tarosie.granularity.mixin;

import com.tarosie.granularity.content.CompositePistonBlock;
import com.tarosie.granularity.content.GranularityBlocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes a Granularity piston extend a Granularity head.
 *
 * <p>{@code moveBlocks} names {@code Blocks.PISTON_HEAD} three times, and all three are the same
 * question — "which block is this piston's arm?" — asked with the answer hard-coded, because vanilla
 * has only ever had one. It builds the head from that name when extending, clears the head by that
 * name when retracting, and notifies neighbours under it afterwards. Redirecting the field read
 * answers all three at once, and correctly: a piston must clear the same block it placed.
 *
 * <p>Kept apart from {@link PistonBaseBlockMixin} because the two say different things. That one is
 * about which blocks may be <i>pushed</i> and applies to every piston in the game; this one is about
 * what <i>our</i> piston is made of, and leaves vanilla's alone.
 *
 * <p>The composition itself is not carried here. The head reads it from the base when it lands —
 * see {@link com.tarosie.granularity.content.CompositePistonHeadBlock} — which is simpler than
 * threading it through the two ticks of animation in between.
 */
@Mixin(PistonBaseBlock.class)
public class PistonBaseBlockHeadMixin {

    @Redirect(
            method = "moveBlocks",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/block/Blocks;PISTON_HEAD:"
                            + "Lnet/minecraft/world/level/block/Block;"))
    private Block granularity$ourPistonUsesOurHead() {
        return (Object) this instanceof CompositePistonBlock
                ? GranularityBlocks.PISTON_HEAD.get()
                : Blocks.PISTON_HEAD;
    }
}
