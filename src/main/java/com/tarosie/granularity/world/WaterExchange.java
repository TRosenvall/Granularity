package com.tarosie.granularity.world;

import com.tarosie.granularity.content.WaterRelease;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.WaterLevels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * The boundary between water the rock is carrying and water the world can see.
 *
 * <p>{@link LevelWaterVolume} keeps the migration rule inside our own stone so that it and vanilla
 * never move the same drop. That leaves two crossings, and they are here rather than inside the rule
 * because they are the only places drops enter or leave its accounting — which is precisely what a
 * conservation argument needs to be able to point at.
 *
 * <ul>
 *   <li><b>Infiltration</b> — water standing on porous rock soaks into it.</li>
 *   <li><b>Seepage</b> — saturated rock gives water back at an open face, driven by a disturbance.</li>
 *   <li><b>Weeping</b> — the same thing without a disturbance, on vanilla's random tick, which is what
 *       makes a cave below the water table wet with nobody there to cause it.</li>
 * </ul>
 *
 * <h2>Discharge is not conditional on disturbance</h2>
 * Seepage once released only water <i>above</i> baseline, reasoning that an aquifer at equilibrium is
 * not discharging. That is exactly backwards. A real aquifer at equilibrium discharges <b>constantly</b>
 * and is at equilibrium because recharge matches it — dynamic equilibrium, not stillness. Under the old
 * rule, cutting into a saturated bed gave one trickle and stopped, where cutting into a permeable
 * streambed should give water that keeps coming.
 *
 * <p>What stops the world draining is therefore recharge, not a refusal to flow. See
 * {@code WaterTicker}'s recharge, keyed to regional rainfall, and {@link #weep} for the ambient case
 * and the two problems that shaped it.
 *
 * <h2>Lakes do not drain</h2>
 * A vanilla source block is infinite and stays that way. Reducing it would be more honest about
 * conservation and would also empty every pond over a sandstone bed, which is not a trade worth
 * making for a number nobody sees. Drops taken from a source are booked as injected, so the ledger
 * says plainly that they came from outside — see {@link LevelWaterVolume#injected()}. Flowing water
 * is finite and is genuinely taken from.
 */
public final class WaterExchange {

    /**
     * A tally of what the ambient weep actually did, for the diagnostic command.
     *
     * <p>Here because "no water is coming out of the rock" has two causes that look identical from
     * inside the game and need opposite fixes: the weep is not running, or it is running and a single
     * drop is too small to see. Counting each stage apart tells them apart. Design note in CLAUDE.md:
     * log a count of what a pass actually did, and check the number.
     */
    /**
     * A weep happens on a random tick with odds of {@code pores in WEEP_ODDS}.
     *
     * <p>The rate limit lives here rather than in the amount. Nine-pore rock weeps on half its visits,
     * one-pore rock on a eighteenth of them, and since most exposed rock is tight the total across the
     * loaded world stays small while genuine aquifer faces run visibly.
     */
    private static final int WEEP_ODDS = 18;

    private static long ticksSeen;
    private static long withOpenFace;
    private static long withWater;
    private static long emitted;
    private static long blocksPlaced;

    private WaterExchange() {
    }

    /** Ambient weep counters: ticks seen, open-faced, watered, emitting, and placing a block. */
    public static long[] weepTally() {
        return new long[]{ticksSeen, withOpenFace, withWater, emitted, blocksPlaced};
    }

    /**
     * Water sitting on rock soaks into it. Returns the drops that crossed in.
     *
     * <p>Downward only. Water spreading sideways into a rock face is a pressure story, and there is
     * no pressure here (findings §6.4) — a sideways crossing would let water enter rock it could
     * never have climbed to.
     */
    public static int infiltrate(ServerLevel level, LevelWaterVolume volume, BlockPos pos) {
        int room = volume.room(pos.getX(), pos.getY(), pos.getZ());
        if (room <= 0) {
            return 0;
        }
        BlockPos above = pos.above();
        FluidState fluid = level.getFluidState(above);
        if (fluid.isEmpty() || !fluid.is(Fluids.WATER)) {
            return 0;
        }

        int available = WaterLevels.dropsFor(fluid.getAmount(), fluid.isSource());
        int moved = Math.min(room, available);
        if (moved <= 0) {
            return 0;
        }

        if (!fluid.isSource()) {
            // Finite water, genuinely handed over: what is left stays above — unless what is left is
            // a single drop, which is a drip rather than a puddle and does not get a block either.
            int left = available - moved;
            BlockState remaining = WaterRelease.isDrip(left) || left <= 0
                    ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                    : WaterRelease.stateFor(left);
            level.setBlock(above, remaining, Block.UPDATE_ALL);
            if (WaterRelease.isDrip(left)) {
                WaterRelease.drip(level, above);
            }
        }
        volume.setWater(pos.getX(), pos.getY(), pos.getZ(),
                volume.water(pos.getX(), pos.getY(), pos.getZ()) + moved);
        volume.inject(moved);
        soaking(level, pos, moved);
        return moved;
    }

    /**
     * A few drips at the face water is soaking through.
     *
     * <p>Infiltration is otherwise <b>completely invisible</b>, and that is not a matter of the cue
     * being too subtle. Moved water lives in a server-side chunk attachment; the client draws a block
     * from the derived composition and has never heard of it. On top of that, the water doing the
     * soaking is usually a source, which by design does not deplete — so nothing above the rock
     * changes either. A player watching a bucket sit on porous stone sees precisely nothing happen,
     * and the only instrument that disagrees is a command.
     *
     * <p>Particles rather than syncing the deviation map, because the map would buy very little for
     * what it costs: a slot moving from air to water shifts the block's tint by a few percent, which
     * is not what anyone would notice anyway. What reads as absorption is <i>motion at the surface</i>,
     * and that is what this draws.
     */
    private static void soaking(ServerLevel level, BlockPos pos, int drops) {
        level.sendParticles(ParticleTypes.FALLING_WATER,
                pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5,
                Math.min(drops, 3), 0.3, 0.0, 0.3, 0.0);
    }

    /**
     * Wet rock gives water back at an open face — a spring, wherever one happens to be.
     *
     * <p>Downward first and sideways second, which is not a preference but gravity: water leaves by
     * the lowest opening available to it, and a seep that chose a side face over a floor would run
     * uphill out of a wall.
     *
     * <h2>A spring is a condition, not a place</h2>
     * This used to release only water <i>above</i> baseline, on the grounds that an aquifer at
     * equilibrium is not discharging. That was wrong, and wrong in an instructive way: a real aquifer
     * at equilibrium is discharging <b>constantly</b>, and is at equilibrium because recharge matches
     * it. Static equilibrium is not the same thing as dynamic equilibrium, and only the second one
     * describes water.
     *
     * <p>The consequence of the old rule was that digging into a saturated bed gave a one-off trickle
     * and then nothing, when what should happen is what happens when you cut into a permeable
     * streambed: water keeps coming. So baseline water discharges too, and what stops the world
     * draining is {@code WaterTicker}'s recharge rather than a refusal to flow.
     *
     * <p>Nothing marks a spring, and nothing needs to. A spring is saturated permeable rock meeting
     * open air; cut deeper into the hillside and the new face is the spring, because it satisfies the
     * same condition the old one did. There is no object to place and none to destroy.
     *
     * <h2>Rate</h2>
     * Limited by the rock's <b>pore space</b>, not by its free slots. Free slots is the right measure
     * for water filling unsaturated rock; for rock that is already full it is zero, and saturated rock
     * plainly does transmit water — that is what an aquifer is. What carries flow through full rock is
     * the pore space itself, so a bed of porosity four gives water back four times as fast as a bed of
     * porosity one.
     */
    public static int seep(ServerLevel level, LevelWaterVolume volume, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (!volume.contains(x, y, z)) {
            return 0;
        }
        int held = volume.water(x, y, z);
        if (held <= 0) {
            return 0;
        }
        // Pore space sets the rate; a block never gives up everything it holds in one tick, or a
        // spring would be a burst followed by nothing while it waited to be recharged.
        int rate = Math.max(1, volume.grainsPoreSpace(x, y, z) / 2);
        int excess = Math.min(held, rate);
        if (excess <= 0) {
            return 0;
        }

        BlockPos outlet = openFace(level, pos);
        if (outlet == null) {
            return 0;
        }

        // What is already in the outlet counts: a seep into a half-full block tops it up rather than
        // replacing it, or the world quietly loses whatever was there.
        FluidState standing = level.getFluidState(outlet);
        int already = standing.is(Fluids.WATER)
                ? WaterLevels.dropsFor(standing.getAmount(), standing.isSource())
                : 0;
        int released = Math.min(excess, Composition.SLOTS - 1 - already);
        if (released <= 0) {
            return 0;
        }

        // A block or a drip, by the one rule in WaterRelease: a single drop is not a puddle. Either
        // way the rock gives it up and the ledger records it — what changes is whether the world gets
        // water it can hold or water it can only see.
        WaterRelease.releaseInto(level, outlet, released, already);
        volume.setWater(x, y, z, volume.water(x, y, z) - released);
        volume.drain(released);
        return released;
    }

    /**
     * A wet rock face weeping of its own accord — the aquifer in throughflow.
     *
     * <p>Design §8's "random-tick-style budget", and it is what makes a wet cave wet without anybody
     * touching it. Saturated rock meeting open air <i>is</i> discharging; that is what a weep is, and
     * it should not need a pickaxe nearby to start.
     *
     * <h2>It changes nothing, and that is the point</h2>
     * No storage is drawn down, no deviation is written, no patch is seeded. The drop is passing
     * <i>through</i>: at equilibrium the aquifer discharges and is recharged at the same rate, so its
     * storage is unchanged by definition. Booked as injected, because the water comes from a catchment
     * this mod does not simulate.
     *
     * <p>Two problems fall away as a result, and both were real.
     *
     * <p>The <b>cascade</b>: a weep that placed water with neighbour updates would trigger
     * {@code NaturalStoneBlock.neighborChanged}, which disturbs, which seeds a patch, which seeps,
     * which places water, which disturbs again. Every ambient drip would bootstrap a self-sustaining
     * patch and a cave system would accumulate hundreds — the tick budget would hold, but the queue
     * would grow without bound and a player's own disturbance would wait behind all of it. So this
     * places water with {@link Block#UPDATE_CLIENTS} and schedules the fluid tick by hand: vanilla
     * still animates it, and nothing listening is told.
     *
     * <p>The <b>drain</b>: recharge only runs inside patches, so a weep that <i>did</i> draw its block
     * down would empty it and stop, with nothing to refill it. Two mechanisms modelling one steady
     * state, and they would have fought.
     *
     * @return drops emitted, which is 0 or 1
     */
    public static int weep(ServerLevel level, BlockPos pos, long salt, RandomSource random) {
        ticksSeen++;
        // The open face is asked FIRST, and the order is the whole cost of this feature.
        //
        // Turning random ticks on for natural stone makes almost every section in the world randomly
        // ticking, because natural stone is what the world is made of — vanilla only ticks a section
        // that contains a ticking block, and underground that used to be none. That is tens of
        // thousands of picks a tick across the simulation distance. Deriving a composition in this
        // method costs about six microseconds and would turn the lot into tens of milliseconds a
        // tick; six block-state lookups cost tens of nanoseconds and throw out every enclosed block,
        // which is nearly all of them. Only rock that actually faces open air is worth a derivation.
        BlockPos outlet = openFace(level, pos);
        if (outlet == null) {
            return 0;
        }
        withOpenFace++;

        // How often, rather than how much. Discharge scales with pore space and so does the chance of
        // weeping at all, so a bed that gives a lot gives it often and tight rock seldom does anything
        // — and the cost across the loaded world stays bounded, because most exposed rock is tight.
        int pores = GranularityWater.poreSpaceAt(level, pos, salt);
        if (pores <= 0 || random.nextInt(WEEP_ODDS) >= pores) {
            return 0;
        }
        FluidState standing = level.getFluidState(outlet);
        int already = standing.is(Fluids.WATER)
                ? WaterLevels.dropsFor(standing.getAmount(), standing.isSource())
                : 0;
        if (already >= Composition.SLOTS - 1) {
            return 0;
        }

        // Only now, with an opening confirmed and room in it, is the rock worth asking about.
        // Deviations included: rock drained by a spring next door should stop weeping until it has
        // recovered, rather than going on weeping out of a baseline it no longer holds.
        if (GranularityWater.waterAt(level, pos, salt) <= 0) {
            return 0;
        }
        withWater++;

        // A drip, always — a random tick is one *sample* of throughflow, not a flow.
        //
        // Emitting the rock's full discharge here was tried and looks wrong for a reason worth
        // writing down: our drops map onto vanilla's fluid amount, so a two-drop release renders as
        // amount 2, which is a thin film. A film placed once every twenty minutes and erased five
        // ticks later reads as worse than a drip, not better. Sustained flow is not a bigger single
        // release; it is a release that happens again before the last one has gone.
        //
        // So a face that could really sustain one is offered to WaterTicker as a spring, and the
        // spring tier discharges it every tick — which is exactly why breaking a rock nearby made
        // these same blocks gush. That was a patch running, and now they can have one without the
        // pickaxe.
        if (pores >= WaterTicker.SPRING_PORES) {
            WaterTicker.offerSpring(level, pos);
        }
        WaterRelease.drip(level, outlet);
        emitted++;
        return 1;
    }

    /** The face water would leave by: the floor if it is open, otherwise a side. Never upward. */
    private static BlockPos openFace(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        if (canAccept(level, below)) {
            return below;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos beside = pos.relative(direction);
            if (canAccept(level, beside)) {
                return beside;
            }
        }
        return null;
    }

    /** Air, or water with room left in it. Anything solid is not an opening. */
    private static boolean canAccept(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        FluidState fluid = state.getFluidState();
        return fluid.is(Fluids.WATER) && !fluid.isSource();
    }
}
