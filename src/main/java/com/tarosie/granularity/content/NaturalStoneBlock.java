package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionFunction;
import com.tarosie.granularity.core.WorldSalt;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
}
