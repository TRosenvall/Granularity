package com.tarosie.granularity.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * How fast the ground gives water back — the catchment behind a spring.
 *
 * <p>Discharge is a property of the block: pore space, and nothing else. Recharge is a property of
 * <i>everything above and behind it</i>, which is why it is the harder half and why this is a
 * separate file rather than a constant.
 *
 * <p>The two rates together decide what a spring is, with nothing written down either way. Recharge
 * keeps up with discharge and the spring is <b>perennial</b>; it does not and the bed drains locally,
 * the flow stops, and it returns once the rock recovers — <b>intermittent</b>. Neither case is coded
 * for; both fall out.
 *
 * <h2>Regional rainfall, not local</h2>
 * The obvious implementation reads the biome under the player's feet, and it is wrong in a way worth
 * stating: <b>an oasis is a desert spring</b>. Real desert springs exist because the recharge happened
 * somewhere else — uplands tens of kilometres away, feeding a regional aquifer, sometimes water that
 * fell thousands of years ago. Recharge belongs to the catchment, and the catchment is usually not the
 * biome you are standing in.
 *
 * <p>So rainfall is sampled across a neighbourhood and averaged. A dry basin ringed by wet hills still
 * gets water; a desert in the middle of a desert gets very little, but never nothing.
 *
 * <h2>What this is standing in for</h2>
 * Design §11's humidity field: one scalar per column, advected by wind, sourced by evaporation and
 * sinking as rainfall. That is the real driver, and it is the same <i>shape</i> as this — spread
 * across biomes rather than clamped to them — so when it exists it replaces the sampling here and
 * nothing else changes.
 *
 * <p>Two factors this does not yet have, in the order they matter:
 * <ul>
 *   <li><b>Catchment area.</b> Why a spring near a valley floor gushes and one near a summit
 *       trickles, in identical rock and rain. §9's flow accumulation is exactly this machinery,
 *       already designed for rivers.</li>
 *   <li><b>Permeability of the column above.</b> Rain that cannot get in runs off instead. Tight
 *       caprock over a permeable bed makes a weak spring in a wet climate.</li>
 * </ul>
 */
public final class Recharge {

    /** How often recharge is applied, in ticks. The rate below is per application. */
    public static final int INTERVAL = 20;

    /**
     * How far out rainfall is sampled, in blocks.
     *
     * <p>Far enough to reach past a biome boundary, since the whole point is that the catchment is
     * usually somewhere else. Not so far that a spring is fed by weather on the other side of the
     * world.
     */
    private static final int CATCHMENT = 96;

    /**
     * Drops per application in ground with no rain reaching it at all.
     *
     * <p>Never zero. Deep aquifers hold water that fell long ago and travelled far, which is what
     * keeps a genuine desert spring alive; a floor of zero would make arid country not merely dry but
     * hydrologically inert, and would erase the oasis.
     */
    private static final double DRIEST = 0.25;

    /** Drops per application where rainfall is at its heaviest. */
    private static final double WETTEST = 1.75;

    private Recharge() {
    }

    /**
     * Drops per application at this position, as a fraction — the caller rounds it stochastically.
     *
     * <p>Fractional on purpose. A rate of a third of a drop is a real rate, and design §12's
     * stochastic rounding turns it into one drop on one application in three: exact in expectation
     * and exactly conservative in every realisation. Rounding it to an integer here would quantise
     * every spring in the world into the same few speeds.
     */
    public static double dropsPerApplication(ServerLevel level, BlockPos pos) {
        double rainfall = regionalRainfall(level, pos);
        return DRIEST + (WETTEST - DRIEST) * rainfall;
    }

    /**
     * Mean rainfall around a position, 0 to 1.
     *
     * <p>Five samples — the centre and four at arm's length — rather than one. Enough to notice that
     * the hills next door are wet, cheap enough to do once per patch per tick.
     */
    private static double regionalRainfall(ServerLevel level, BlockPos pos) {
        double total = downfallAt(level, pos);
        total += downfallAt(level, pos.offset(CATCHMENT, 0, 0));
        total += downfallAt(level, pos.offset(-CATCHMENT, 0, 0));
        total += downfallAt(level, pos.offset(0, 0, CATCHMENT));
        total += downfallAt(level, pos.offset(0, 0, -CATCHMENT));
        return Math.max(0.0, Math.min(1.0, total / 5.0));
    }

    private static double downfallAt(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).value().getModifiedClimateSettings().downfall();
    }
}
