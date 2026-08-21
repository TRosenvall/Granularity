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
     * The slowest a weeping face is revisited, in ticks — barely damp rock.
     *
     * <p>Four seconds. Below this a block is not really weeping, and the scheduled tick is costing
     * more than the drip is worth.
     */
    private static final int SLOWEST_WEEP = 80;

    /** The fastest, for rock that is nearly all water. Half a second. */
    private static final int FASTEST_WEEP = 10;

    /**
     * Wet rock weeps where it meets the air, and wetter rock weeps more often.
     *
     * <p>What makes a cave below the water table actually wet, with nobody nearby to cause it. The
     * rock is saturated and open to the air; that is a spring by definition and it should not wait for
     * a pickaxe.
     *
     * <h2>Random ticks find it; scheduled ticks keep it</h2>
     * Vanilla picks random-tick blocks uniformly inside a section, so the <i>rate</i> cannot be biased
     * toward wet rock — a soaked block and a dry one are equally likely to be chosen, about once a
     * minute each. That is far too slow to read as dripping, and there is no knob for it.
     *
     * <p>So a random tick is treated as <b>discovery</b>. Once a face is found to be wet and open, it
     * schedules its own next visit, at an interval that scales with how much water it holds — see
     * {@link #weepDelay}. Barely damp rock is revisited every four seconds; rock that is nearly all
     * water, every half second. That is the "more likely the wetter it is" that random ticks cannot
     * express, and it is how vanilla drives fire and crops.
     *
     * <p>It stops on its own. A block only reschedules if it actually emitted, so drying out or being
     * sealed in ends the chain with no bookkeeping. One pending tick per block, saved with the chunk,
     * and no queue that can grow.
     */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        weepAndReschedule(level, pos);
    }

    /** The scheduled visit set by {@link #weepAndReschedule}. Same work, on our own clock. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        weepAndReschedule(level, pos);
    }

    private void weepAndReschedule(ServerLevel level, BlockPos pos) {
        if (!WorldSalt.ServerView.isPresent()) {
            return;
        }
        long salt = WorldSalt.ServerView.get().value();
        // Nothing is derived unless there is somewhere for water to go: weep checks for an open face
        // first, which is six block-state lookups and throws out every buried block. Natural stone is
        // what the world is made of, so this method runs on a great many blocks a tick.
        if (WaterExchange.weep(level, pos, salt) <= 0) {
            return;
        }
        if (level.getBlockTicks().hasScheduledTick(pos, this)) {
            return;
        }
        level.scheduleTick(pos, this, weepDelay(GranularityWater.waterAt(level, pos, salt)));
    }

    /**
     * How long until this face is worth visiting again, from how much water the rock holds.
     *
     * <p>Linear between the two bounds. Not a physical law — the honest quantity would be discharge
     * per unit time, which the seepage rate already expresses — but the visible <i>rhythm</i> of
     * dripping is what tells a player how wet a wall is, and rhythm is a frequency.
     */
    private static int weepDelay(int water) {
        int held = Math.max(1, Math.min(Composition.SLOTS, water));
        int span = SLOWEST_WEEP - FASTEST_WEEP;
        return SLOWEST_WEEP - span * (held - 1) / (Composition.SLOTS - 1);
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
            if (drops > 0) {
                return level.setBlock(pos, WaterRelease.stateFor(drops), Block.UPDATE_ALL);
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }
}
