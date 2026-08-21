package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionFunction;
import com.tarosie.granularity.core.GrainClass;
import com.tarosie.granularity.core.WaterLevels;
import com.tarosie.granularity.core.WorldSalt;
import com.tarosie.granularity.world.GranularityWater;
import com.tarosie.granularity.world.WaterExchange;
import com.tarosie.granularity.world.WaterTicker;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Natural stone: the first block whose drops are derived rather than declared.
 *
 * <p>Two design pillars show up as structure here rather than as code.
 *
 * <p><b>No loot table.</b> {@link #getDrops} is overridden outright, because the drops are a
 * function of where the block is — design §4's derive-don't-store. A loot table cannot express
 * "nine objects determined by position and salt", and a block entity holding the composition is
 * precisely what §4 forbids at world-stone scale.
 *
 * <p><b>No item form.</b> This block has no {@code BlockItem}, so it cannot be carried or placed.
 * That is design §2's player-placed exemption arriving for free: natural blocks are never harvested
 * directly, so the only placeable stone is the crafted kind, and "placed blocks are exempt from
 * world evolution" needs no flag to check — the two are simply different registered blocks. Use
 * {@code /setblock} to place one for testing.
 */
public class NaturalStoneBlock extends Block {

    public NaturalStoneBlock(Properties properties) {
        super(properties);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        Vec3 origin = params.getOptionalParameter(LootContextParams.ORIGIN);
        if (origin == null) {
            // Some loot contexts carry no position (recipe previews, misuse). Deriving from a
            // guessed position would invent drops, so yield nothing and let it be visible.
            return List.of();
        }
        if (!WorldSalt.ServerView.isPresent()) {
            return List.of();
        }

        BlockPos pos = BlockPos.containing(origin);
        Composition composition = CompositionFunction.stone(
                pos.getX(), pos.getY(), pos.getZ(), WorldSalt.ServerView.get().value());
        return CompositionDrops.toStacks(composition);
    }

    /**
     * Wet rock weeps where it meets the air.
     *
     * <p>What makes a cave below the water table look wet with nobody nearby to cause it. The rock is
     * saturated and open to the air, which is a seep by definition and should not wait for a pickaxe.
     *
     * <h2>Random ticks only, and no self-scheduling</h2>
     * An earlier version treated a random tick as <i>discovery</i>: once a face was found wet and
     * open it scheduled its own visits, faster the wetter it was, so that wetness could drive a
     * frequency the way vanilla's uniform picking cannot. It was a good idea and it does not survive
     * contact with the world.
     *
     * <p>A block only stops rescheduling if it stops emitting, and recharge keeps it wet — so it never
     * stops. Every exposed wet block in the loaded world migrates onto a half-second timer and stays
     * there. Measured: some 465 emissions a tick and a server 329 ticks behind. The mechanism had no
     * fixed point, which is a different kind of bug from being too fast, and no rate would have fixed
     * it.
     *
     * <p>So the rate is vanilla's random tick and nothing else — bounded by a budget the game already
     * tunes, already limits to chunks near players, and cannot run away. A given block is visited
     * about once a minute; a wall of exposed wet rock has enough blocks that something is dripping
     * somewhere most of the time, which is what damp rock looks like.
     */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!WorldSalt.ServerView.isPresent()) {
            return;
        }
        // Nothing is derived here. WaterExchange.weep checks for an open face first — six block-state
        // lookups — and only asks what the rock holds if there is somewhere for water to go. Natural
        // stone is what the world is made of, so this runs on a great many blocks a tick.
        WaterExchange.weep(level, pos, WorldSalt.ServerView.get().value(), random);
    }

    /**
     * Water arriving next to rock is a disturbance, and the rock says so.
     *
     * <p>Without this the migration tier never runs for anything but mining. Breaking a block marks a
     * patch, and that was the only trigger there was — so emptying a bucket onto porous stone did
     * nothing at all, no matter how long you watched it. The rock has to notice water turning up
     * beside it, and a neighbour change is precisely the game telling it so.
     *
     * <p>Filtered to water rather than marking on every update, because this fires for redstone, for
     * a torch placed next door, for anything at all. Marking is cheap but it is not free, and a patch
     * that has nothing to do still costs its quiet ticks before it is dropped.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        if (level instanceof ServerLevel server
                && server.getFluidState(neighborPos).is(Fluids.WATER)) {
            WaterTicker.disturb(server, pos);
        }
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
    }

    /**
     * Breaking wet rock leaves its water behind, at the level the rock was holding.
     *
     * <p>Design §6, stated almost word for word: "breaking a moist block releases a partial water
     * level that sits or sinks". The rock's water is slots, the released water is a vanilla fluid
     * level, and {@link WaterLevels} is where those two readings of the same number meet — three
     * drops in the pores come out as level-3 water and not as some separately chosen amount. What
     * state that becomes is {@link WaterRelease}'s decision, which is where the conservation argument
     * and the finite-fluid compat seam both live.
     *
     * <p>It drains away on its own, and that is correct rather than a shortcoming. What comes out is
     * flowing water with no source feeding it, so it spreads, sinks and is gone — which is what a few
     * drops squeezed out of a rock face should do. Water that stayed would be water the block never
     * had.
     *
     * <p>This hook rather than {@code getDrops}, because water is not a drop: {@link GrainClass#WATER}
     * yields no item by design, so there is nothing for a loot list to carry. It is also not
     * {@code onRemove}, which fires for every state change and would have to work out which ones were
     * breakages. This one is asked precisely what replaces the block.
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (level instanceof ServerLevel server && WorldSalt.ServerView.isPresent()) {
            long salt = WorldSalt.ServerView.get().value();
            // What the block is *actually* holding, not what the field says it would hold if nobody
            // had been here: water may have migrated in, and releasing the baseline instead would
            // quietly destroy the difference.
            int drops = GranularityWater.waterAt(server, pos, salt);
            GranularityWater.forget(server, pos);
            // A new hole in the rock is a disturbance whether or not this block was wet — water in
            // the rock around it now has somewhere to go.
            WaterTicker.disturb(server, pos);
            if (WaterRelease.isDrip(drops)) {
                // Barely damp rock. It gives up what it had, and what it had is a drip — so the
                // block breaks normally and you see the water leave rather than find a puddle.
                WaterRelease.drip(server, pos);
            } else if (drops > 0) {
                return level.setBlock(pos, WaterRelease.stateFor(drops), Block.UPDATE_ALL);
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }
}
