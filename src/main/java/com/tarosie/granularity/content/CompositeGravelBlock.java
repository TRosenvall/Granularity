package com.tarosie.granularity.content;

import com.mojang.serialization.MapCodec;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.LatticeColour;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

/**
 * Gravel, which falls — and remembers what it is made of on the way down.
 *
 * <p>It was a plain {@link CompositeBlock} until now, so it hung in the air like stone. That is a
 * silent divergence from the one thing everybody knows about gravel, and the sort of difference a
 * player reads as a bug rather than a design.
 *
 * <p>Falling is the hard part for a block that carries data. Vanilla turns the block into a
 * {@link FallingBlockEntity}, which carries a {@code BlockState} and nothing else by default — so a
 * composite would land as the andesite a fresh block entity starts from, and a stack of gravel could
 * be laundered into whatever it liked by dropping it. {@code FallingBlockEntity.blockData} is the
 * seam: vanilla merges that tag onto the block entity it rebuilds on landing, and saves it under
 * {@code TileEntityData}, so the composition survives even if the world is closed mid-fall.
 */
public class CompositeGravelBlock extends FallingBlock implements EntityBlock, CompositeStone {

    public static final MapCodec<CompositeGravelBlock> CODEC = simpleCodec(CompositeGravelBlock::new);

    /** Dust colours carry their own alpha; see {@link #getDustColor}. */
    private static final int OPAQUE = 0xFF000000;

    public CompositeGravelBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends FallingBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeBlockEntity(pos, state);
    }

    /**
     * Takes the composition with it as it goes.
     *
     * <p>Vanilla's own {@code tick} is reproduced rather than called, because the block entity has to
     * be read <b>before</b> {@code FallingBlockEntity.fall} replaces the block — and by the time
     * {@code falling(entity)} runs, the block entity has already been destroyed by that replacement.
     * There is no hook between the two.
     */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!isFree(level.getBlockState(pos.below())) || pos.getY() < level.getMinBuildHeight()) {
            return;
        }
        BlockEntity entity = level.getBlockEntity(pos);
        CompoundTag data = entity instanceof CompositionHolder
                ? entity.saveWithoutMetadata(level.registryAccess())
                : null;
        FallingBlockEntity falling = FallingBlockEntity.fall(level, pos, state);
        falling.blockData = data;
        this.falling(falling);
    }

    /**
     * Puts the composition back the instant it lands, rather than leaving it to vanilla.
     *
     * <p>Vanilla does apply {@code blockData} a few lines later, but only calls {@code setChanged} —
     * which marks the chunk unsaved without telling a single client. The block would sit showing the
     * default stone until something else happened to dirty the section, which is the same one-step
     * lag dyeing had. Applying it here and broadcasting is what makes the landing look instant; the
     * apply vanilla then does is the same tag over the same block entity, so it changes nothing.
     */
    @Override
    public void onLand(Level level, BlockPos pos, BlockState state, BlockState replaced,
                       FallingBlockEntity entity) {
        super.onLand(level, pos, state, replaced, entity);
        if (level.isClientSide || entity.blockData == null) {
            return;
        }
        BlockEntity landed = level.getBlockEntity(pos);
        if (landed instanceof CompositionHolder) {
            landed.loadWithComponents(entity.blockData, level.registryAccess());
            landed.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    /**
     * The dust it throws is the colour of what it is made of.
     *
     * <p>Not decoration: {@link FallingBlock}'s default is flat black, so a composite that did not
     * answer this would trail soot. The average is the same one the matrix is drawn from, so falling
     * gravel and the block it lands as agree.
     *
     * <p><b>ARGB, not RGB.</b> Vanilla's dust colours are opaque-alpha ints — its own default is
     * {@code 0xFF000000} — and a tint handed back with alpha zero is a fully transparent particle.
     */
    @Override
    public int getDustColor(BlockState state, BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof CompositionHolder composite) {
            Composition composition = composite.composition();
            int average = composition.averageTint(null);
            if (average >= 0) {
                return OPAQUE | LatticeColour.matrixTint(average);
            }
        }
        return super.getDustColor(state, level, pos);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return CompositeBlock.wholeBlockDrops(this, params);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        CompositeBlock.applyTo(level, pos, stack);
    }

    @Override
    public ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos,
                                       BlockState state) {
        return CompositeShapes.cloned(super.getCloneItemStack(level, pos, state), level, pos, state);
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit) {
        net.minecraft.world.ItemInteractionResult result =
                CompositeShapes.interact(stack, state, level, pos, player, hit);
        if (result != net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return result;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /**
     * Both parents want this one: {@link FallingBlock} schedules the tick that decides whether to
     * fall, and a piston push needs its memory restored. Dropping either breaks a different thing.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        PistonMoves.land(level, pos, isMoving);
    }
}
