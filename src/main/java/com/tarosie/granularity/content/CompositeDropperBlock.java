package com.tarosie.granularity.content;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A dropper built from seven grains, which shows all seven.
 *
 * <p>The same seven-way split of {@code furnace_side} the dispenser takes, since the two cost the
 * same; only the muzzle differs, and that face is machinery and stays untinted.
 *
 * <p>Not a subclass of {@link CompositeDispenserBlock}, because vanilla's {@code DropperBlock} is
 * where dropping rather than firing lives and Java gives us one superclass. The composition
 * bookkeeping is a handful of one-line hand-offs to {@link CompositeShapes}, which is the cheaper
 * duplication of the two.
 */
public class CompositeDropperBlock extends DropperBlock implements CompositeStone {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DispenseItemBehavior DISPENSE_BEHAVIOUR = new DefaultDispenseItemBehavior();

    // Declared as the parent's type rather than our own: DropperBlock narrows codec() to
    // MapCodec<DropperBlock>, and generics are invariant, so MapCodec<CompositeDropperBlock> would
    // not be a legal override. Inferring B = DropperBlock still builds our block.
    public static final MapCodec<DropperBlock> CODEC = simpleCodec(CompositeDropperBlock::new);

    /** What the recipe costs, and so what the hammer returns and how many dimples are drawn. */
    public static final int GRAINS = 7;

    public CompositeDropperBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<DropperBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeDropperBlockEntity(pos, state);
    }

    /**
     * Vanilla's dropping, resolved against our own block entity type.
     *
     * <p>Same reason as {@link CompositeDispenserBlock#dispenseFrom}: vanilla's typed lookup asks for
     * {@code BlockEntityType.DROPPER} and would never find ours. The hopper hand-off below — dropping
     * into a container rather than onto the floor when one is in front — is vanilla's, including the
     * NeoForge insert hook that lets modded inventories take the item.
     */
    @Override
    protected void dispenseFrom(ServerLevel level, BlockState state, BlockPos pos) {
        if (Fouling.fouled(level, pos, state)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof CompositeDropperBlockEntity dropper)) {
            LOGGER.warn("Ignoring dispensing attempt for Dropper without matching block entity at {}", pos);
            return;
        }
        BlockSource source = new BlockSource(level, pos, state, dropper);
        int slot = dropper.getRandomSlot(level.random);
        if (slot < 0) {
            level.levelEvent(1001, pos, 0);
            return;
        }
        ItemStack stack = dropper.getItem(slot);
        if (stack.isEmpty()
                || !net.neoforged.neoforge.items.VanillaInventoryCodeHooks.dropperInsertHook(
                        level, pos, dropper, slot, stack)) {
            return;
        }
        Direction facing = level.getBlockState(pos).getValue(FACING);
        Container target = HopperBlockEntity.getContainerAt(level, pos.relative(facing));
        ItemStack remainder;
        if (target == null) {
            remainder = DISPENSE_BEHAVIOUR.dispense(source, stack);
        } else if (HopperBlockEntity.addItem(dropper, target, stack.copyWithCount(1), facing.getOpposite())
                .isEmpty()) {
            remainder = stack.copy();
            remainder.shrink(1);
        } else {
            remainder = stack.copy();
        }
        dropper.setItem(slot, remainder);
    }

    /** Drops the dropper whole, or its seven grains under the hammer. */
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
        // Sneak + moss grows on it; an ordinary click still opens the dropper.
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
