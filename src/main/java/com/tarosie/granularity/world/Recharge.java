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
 * <h2>Weather, and what is left standing in</h2>
 * Design §11's humidity field <b>now exists</b> ({@link GranularityWeather}), so the primary term is
 * real: rain that has actually fallen on this chunk. The regional-climate reading it replaced is kept
 * for the case where nothing has fallen lately, standing for the deep slow supply — water that fell
 * somewhere else long ago and is still working through. That is what keeps a desert spring alive
 * between storms years apart, and it is why the floor below is not zero.
 *
 * <p>One factor this still does not have:
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

    /**
     * How much a drop of recent rainfall adds to the recharge rate.
     *
     * <p>Small, because rain is counted per chunk and a storm deposits a great many drops over one.
     * What matters is the shape: ground that has just been rained on recharges hard, and the rate
     * falls away as the rainfall fades.
     */
    private static final double PER_DROP = 0.04;

    /**
     * What the climate alone is worth when no rain has fallen lately.
     *
     * <p>Under one, so a wet climate with no recent storm still recharges more slowly than the same
     * place mid-downpour. Without this, weather would make no difference to a spring in a rainforest
     * — the baseline would already be at the ceiling.
     */
    private static final double BETWEEN_STORMS = 0.5;

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
        // What has actually fallen here lately, first. This is the seam the class note describes,
        // now carrying real weather instead of a stand-in: rain reaches the ground through the field
        // tier, and the aquifer is refilled by the water that landed on it rather than by the climate
        // the biome table says the place has.
        int fallen = GranularityWeather.recentRain(level, pos.getX(), pos.getZ());
        if (fallen > 0) {
            return Math.min(WETTEST * 2.0, DRIEST + fallen * PER_DROP);
        }
        // No recent rain: the deep, slow supply. Regional climate stands in for water that fell
        // somewhere else long ago and is still working its way through, which is what keeps a desert
        // spring alive between storms that may be years apart.
        double rainfall = regionalRainfall(level, pos);
        return DRIEST + (WETTEST - DRIEST) * rainfall * BETWEEN_STORMS;
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
