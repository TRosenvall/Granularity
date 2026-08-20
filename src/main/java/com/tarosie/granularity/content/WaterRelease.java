package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.WaterLevels;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * The one place drops in slots become water in the world.
 *
 * <p>{@link WaterLevels} converts the number; this converts the thing. They are separate because the
 * number is an integer fact that holds everywhere, and the thing depends on what the world's water
 * can do — which is not fixed, and is the point of this class existing at all.
 *
 * <h2>Why never a source</h2>
 * Vanilla water is infinite. A source block refills its neighbours forever, so a pocket of nine drops
 * placed as a source is unlimited water, and the conservation design §7 rests on — drops migrate,
 * never created or destroyed — stops being true the first time a player breaks into wet rock. Every
 * release is therefore a <i>flowing</i> state, which spreads, thins and is gone.
 *
 * <p>That costs the top drop: nine drops leave as vanilla's deepest flow, eight. The mod loses a drop
 * rather than inventing an ocean, which is the right direction for an error this size.
 *
 * <h2>The compat seam</h2>
 * With a finite-water mod present — Flowing Fluids and its kind — a source is no longer infinite and
 * the reason for the rule above evaporates: nine drops could be placed as nine drops, exactly, and
 * the last drop would stop being lost. Design §7 rules out a <b>hard</b> dependency on such mods and
 * leaves soft compatibility open, so this is the seam that would carry it. One method, one decision,
 * no threading through call sites: everything that puts our water into the world comes here first.
 */
public final class WaterRelease {

    private WaterRelease() {
    }

    /** The state to leave where a block holding this many drops used to be. */
    public static BlockState stateFor(int drops) {
        if (drops <= 0) {
            return Blocks.AIR.defaultBlockState();
        }
        FluidState released = Fluids.WATER.getFlowing(WaterLevels.amount(drops), false);
        return released.createLegacyBlock();
    }

    /** Whether a block holding this composition has any water to give up when it is broken. */
    public static boolean holdsWater(Composition composition) {
        return composition.water() > 0;
    }
}
