package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.WaterLevels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
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

    /**
     * The fewest drops that make a block of water rather than a drip.
     *
     * <p>One drop is a <b>drip</b>: a sound and a few particles, water you can see leaving the rock
     * and not water you can swim in. Two or more is a block.
     *
     * <p>Physically obvious once stated — a single drop does not make a puddle — and it settles three
     * separate problems that were each being handled ad hoc.
     *
     * <ul>
     *   <li><b>Level-1 water does not work.</b> It renders as a nearly invisible film and vanilla
     *       erases it about five ticks later, so it is the one fluid state that reliably looks like
     *       nothing happened. Never placing it means every block we do place is one somebody can
     *       see.</li>
     *   <li><b>Ambient weeping needed an exception.</b> It was made particles-only by hand, for
     *       performance, which left the rule "weeps are special" rather than "small amounts are
     *       small". Now it falls out: a weep is one drop, so a weep is a drip.</li>
     *   <li><b>Block placement was the cost.</b> Measured at some 465 setBlock calls a tick, and the
     *       single-drop case is by far the most common. Making it free is most of the saving without
     *       a budget or a rate limit anywhere.</li>
     * </ul>
     */
    public static final int MINIMUM_BLOCK = 2;

    private WaterRelease() {
    }

    /** Whether this much water is a drip rather than something to place. */
    public static boolean isDrip(int drops) {
        return drops > 0 && drops < MINIMUM_BLOCK;
    }

    /**
     * Put water into the world, as a block or as a drip according to how much there is.
     *
     * <p>The one call sites should use, so the threshold lives in a single place rather than being
     * remembered at each of them.
     *
     * @param existing drops already standing at the outlet, which count toward the block
     * @return true if a block was placed, false if it was a drip
     */
    public static boolean releaseInto(ServerLevel level, BlockPos outlet, int drops, int existing) {
        int total = drops + existing;
        if (total >= MINIMUM_BLOCK) {
            level.setBlock(outlet, stateFor(total), Block.UPDATE_ALL);
            return true;
        }
        drip(level, outlet);
        return false;
    }

    /** A drip: what a drop of water looks like when there is only one of it. */
    public static void drip(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.DRIPPING_WATER,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                1, 0.2, 0.2, 0.2, 0.0);
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
