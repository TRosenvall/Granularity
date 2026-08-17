package com.tarosie.granularity.content;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A dispenser built from seven grains, which shows all seven.
 *
 * <p>Its dimpling is split seven ways rather than the furnace's eight, from the same
 * {@code furnace_side} pixels — the count belongs to the block, not to the texture, because seven is
 * what vanilla's recipe costs. The block as a whole takes the averaged tint; each dimple takes one
 * grain's exact colour, and the muzzle is left untinted because it is machinery rather than stone.
 *
 * <p>Firing, the nine slots and the menu are inherited from {@link DispenserBlock} untouched. All
 * this adds is the memory of what it was made of, and — by being a {@link CompositeStone} — the
 * ability to grow moss and to be shoved by a piston.
 */
public class CompositeDispenserBlock extends DispenserBlock implements CompositeStone {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final MapCodec<CompositeDispenserBlock> CODEC = simpleCodec(CompositeDispenserBlock::new);

    /** What the recipe costs, and so what the hammer returns and how many dimples are drawn. */
    public static final int GRAINS = 7;

    public CompositeDispenserBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends DispenserBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeDispenserBlockEntity(pos, state);
    }

    /**
     * Vanilla's dispensing, resolved against our own block entity type.
     *
     * <p>Overridden for one word. {@code DispenserBlock.dispenseFrom} looks the entity up with
     * {@code getBlockEntity(pos, BlockEntityType.DISPENSER)} — a <b>typed</b> lookup, which returns
     * nothing for our type and would leave the block silently refusing to fire while logging a
     * warning about a missing block entity. Everything else here is vanilla's, line for line.
     */
    @Override
    protected void dispenseFrom(ServerLevel level, BlockState state, BlockPos pos) {
        if (Fouling.fouled(level, pos, state)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof CompositeDispenserBlockEntity dispenser)) {
            LOGGER.warn("Ignoring dispensing attempt for Dispenser without matching block entity at {}", pos);
            return;
        }
        BlockSource source = new BlockSource(level, pos, state, dispenser);
        int slot = dispenser.getRandomSlot(level.random);
        if (slot < 0) {
            level.levelEvent(1001, pos, 0);
            level.gameEvent(GameEvent.BLOCK_ACTIVATE, pos, GameEvent.Context.of(dispenser.getBlockState()));
            return;
        }
        ItemStack stack = dispenser.getItem(slot);
        DispenseItemBehavior behaviour = this.getDispenseMethod(level, stack);
        if (behaviour != DispenseItemBehavior.NOOP) {
            dispenser.setItem(slot, behaviour.dispense(source, stack));
        }
    }

    /**
     * Drops the dispenser whole, or its seven grains under the hammer.
     *
     * <p>Seven rather than nine: the two remaining composition slots are repeats the recipe made to
     * fill the block out, and returning them would be inventing material.
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return CompositeShapes.drops(this, state, params, 1, GRAINS);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        CompositeShapes.placed(level, pos, stack);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return CompositeShapes.cloned(super.getCloneItemStack(level, pos, state), level, pos, state);
    }

    /** A muzzle with moss over it does not open either. See {@link Fouling}. */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (Fouling.fouled(level, pos, state)) {
            return net.minecraft.world.InteractionResult.CONSUME;
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        // Sneak + moss grows on it; an ordinary click still opens the dispenser.
        net.minecraft.world.ItemInteractionResult result =
                CompositeShapes.interact(stack, state, level, pos, player, hit);
        if (result != net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return result;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /** A piston push rebuilds the block at the far end; this is where its memory catches up. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        PistonMoves.land(level, pos, isMoving);
    }
}
