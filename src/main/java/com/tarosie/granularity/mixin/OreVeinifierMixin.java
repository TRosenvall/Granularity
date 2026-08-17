package com.tarosie.granularity.mixin;

import com.tarosie.granularity.content.GranularityBlocks;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.OreVeinifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops vanilla granite and tuff leaking into a world made of grains.
 *
 * <p>Phase 5 replaced the overworld's default block and removed the vanilla stone blob features, and
 * granite kept appearing anyway — around 0.7% of stone. This is where it came from.
 * {@code OreVeinifier.VeinType.COPPER} carries {@code Blocks.GRANITE} as its filler and {@code IRON}
 * carries {@code Blocks.TUFF}, and ore veins are <b>not placed features</b>: they are generated in
 * the noise stage from the noise router. A {@code remove_features} biome modifier cannot reach them,
 * which is exactly why removing every granite feature left granite behind.
 *
 * <p>The veins themselves are kept. They are a real ore source and there is nothing wrong with them
 * — only with the two stones they pack around the ore. Filtering the filler leaves copper and iron
 * generating exactly as vanilla intends, in stone that answers to the composition function like all
 * the rest of the world's stone.
 *
 * <p>Wrapping the returned filler rather than redirecting the field read inside it: the field is read
 * from a lambda, whose compiled name is not something to depend on, and {@code create} is an ordinary
 * static method. This also states the rule in the terms it was asked in — "no granite, no tuff" —
 * rather than in terms of which enum constant holds what.
 *
 * <p>Both stones are still owed a granular form; see {@code docs/BLOCK_COVERAGE.md}, which tracks
 * what vanilla has and Granularity does not yet express.
 */
@Mixin(OreVeinifier.class)
public class OreVeinifierMixin {

    @Inject(method = "create", at = @At("RETURN"), cancellable = true)
    private static void granularity$noVanillaStoneInVeins(
            net.minecraft.world.level.levelgen.DensityFunction veinToggle,
            net.minecraft.world.level.levelgen.DensityFunction veinRidged,
            net.minecraft.world.level.levelgen.DensityFunction veinGap,
            net.minecraft.world.level.levelgen.PositionalRandomFactory random,
            CallbackInfoReturnable<NoiseChunk.BlockStateFiller> callback) {
        NoiseChunk.BlockStateFiller veins = callback.getReturnValue();
        if (veins == null) {
            return;
        }
        callback.setReturnValue(context -> {
            BlockState placed = veins.calculate(context);
            if (placed == null) {
                return null;
            }
            // Null rather than naming our own block. MaterialRuleList takes the first non-null
            // answer, and when every rule declines the generator falls back to the noise settings'
            // default block -- which is granularity:natural_stone. So declining puts the vein in
            // whatever stone the composition function says belongs there, which is more correct
            // than substituting one block of our choosing and needs no reference to it.
            if (placed.is(Blocks.GRANITE) || placed.is(Blocks.TUFF)) {
                return null;
            }
            // `VeinType.IRON` hard-codes DEEPSLATE_IRON_ORE as its ore, not just its filler, because
            // vanilla only ever generates that vein between y -60 and -8 where everything is
            // deepslate anyway. Deepslate no longer generates here, so the deepslate-textured ore
            // was left sitting in pale stone. The stone variant is the same ore with the same drops.
            return placed.is(Blocks.DEEPSLATE_IRON_ORE)
                    ? Blocks.IRON_ORE.defaultBlockState()
                    : placed;
        });
    }
}
