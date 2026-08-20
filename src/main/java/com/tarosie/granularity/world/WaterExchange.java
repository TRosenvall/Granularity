package com.tarosie.granularity.world;

import com.tarosie.granularity.content.WaterRelease;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.WaterLevels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
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
 *   <li><b>Seepage</b> — rock carrying more water than it should gives it back at an open face.</li>
 * </ul>
 *
 * <h2>Only water above baseline seeps</h2>
 * The rule that keeps this from draining the world. Every porous block below the water table holds
 * water at equilibrium, and a cave cuts through thousands of them; if any wet block with an open face
 * discharged, the entire saturated zone would empty into the nearest cave and go on doing it forever,
 * because the baseline would keep topping it back up.
 *
 * <p>An aquifer at equilibrium is not discharging — that is what equilibrium means. So seepage is
 * limited to the <i>deviation</i>: water that arrived from somewhere and has somewhere to go. Which
 * also makes a spring what §6.3 says it is, a response to recharge, rather than a permanent hole in
 * the rock.
 *
 * <h2>Lakes do not drain</h2>
 * A vanilla source block is infinite and stays that way. Reducing it would be more honest about
 * conservation and would also empty every pond over a sandstone bed, which is not a trade worth
 * making for a number nobody sees. Drops taken from a source are booked as injected, so the ledger
 * says plainly that they came from outside — see {@link LevelWaterVolume#injected()}. Flowing water
 * is finite and is genuinely taken from.
 */
public final class WaterExchange {

    private WaterExchange() {
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
            // Finite water, genuinely handed over: what is left stays above.
            int left = available - moved;
            BlockState remaining = left <= 0
                    ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                    : WaterRelease.stateFor(left);
            level.setBlock(above, remaining, Block.UPDATE_ALL);
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
     * Rock holding more water than its baseline gives the excess back at an open face.
     *
     * <p>Downward first and sideways second, which is not a preference but gravity: water leaves by
     * the lowest opening available to it, and a seep that chose a side face over a floor would run
     * uphill out of a wall.
     */
    public static int seep(ServerLevel level, LevelWaterVolume volume, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (!volume.contains(x, y, z)) {
            return 0;
        }
        int excess = volume.water(x, y, z) - volume.baselineWater(x, y, z);
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

        level.setBlock(outlet, WaterRelease.stateFor(already + released), Block.UPDATE_ALL);
        volume.setWater(x, y, z, volume.water(x, y, z) - released);
        volume.drain(released);
        // The same argument as soaking, from the other side: a seep places a thin fluid block that
        // vanilla will erase on its next tick, so without a cue the whole event can happen between
        // two frames and leave nothing behind.
        level.sendParticles(ParticleTypes.DRIPPING_WATER,
                outlet.getX() + 0.5, outlet.getY() + 0.5, outlet.getZ() + 0.5,
                Math.min(released, 3), 0.2, 0.2, 0.2, 0.0);
        return released;
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
