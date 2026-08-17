package com.tarosie.granularity.content;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

/**
 * A stonecutter built from worked stone and a metal bar, and coloured by both.
 *
 * <h2>Four materials, which is the point of it</h2>
 * One per slot of the recipe — {@code " B "} over {@code "#L#"}. The bar becomes the saw blade
 * ({@code METAL_TINT}), the log becomes the frame ({@code WOOD_TINT}), and the <b>two</b> stones are
 * drawn separately: the left one is the bench below the wooden rail, the right one the strip above it
 * and the working surface on top. So a slate bench with a marble top and a copper blade is a thing you
 * can build and then see. See {@link com.tarosie.granularity.client.CompositeBlockColour}, and
 * {@code docs/CRAFTED_BLOCKS.md} §9 for why the second stone reuses the double slab's tint range.
 *
 * <h2>Why it shows no grains</h2>
 * Every other block in this family is built from chunks and shows one dimple per chunk. This one is
 * built from <i>smooth</i> stone, and {@link com.tarosie.granularity.core.Finish#SMOOTH} is precisely
 * the state in which a block has stopped showing its grains separately. Drawing nine stones on the
 * side of a bench made of stone that no longer has visible stones in it would contradict the recipe.
 * So its model carries zero grain layers, and the two compositions it stores are averaged rather than
 * shown stone by stone.
 *
 * <h2>Why the recipe is not the reason it exists</h2>
 * It replaces vanilla's stonecutter, which had become uncraftable: vanilla's recipe asks for
 * {@code minecraft:stone}, and no such block is obtainable here any more. The stonecutter is where
 * {@link com.tarosie.granularity.recipe.StoneCutRecipe} lives, so the block the whole stonework-style
 * feature depends on had no way into a player's hands. See {@code docs/CRAFTED_BLOCKS.md}.
 *
 * <p>Note that vanilla's own stonecutter block is untouched and still works if one is obtained by
 * other means — {@link com.tarosie.granularity.mixin.StonecutterMenuMixin} is what lets ours open the
 * same menu, because {@code StonecutterMenu.stillValid} names {@code Blocks.STONECUTTER} outright.
 */
public class CompositeStonecutterBlock extends StonecutterBlock implements EntityBlock, CompositeStone {

    // As with the observer: StonecutterBlock declares codec() as MapCodec<StonecutterBlock> and
    // generics are invariant, so the field cannot be narrowed to our own type.
    public static final MapCodec<StonecutterBlock> CODEC = simpleCodec(CompositeStonecutterBlock::new);

    /**
     * What the recipe costs in grains, and so what the hammer gives back: two whole blocks.
     *
     * <p>Two <i>blocks</i> rather than a handful of chunks because smooth is a finish, and a finish
     * lives on a block — there is no such thing as a smooth chunk. Eighteen is therefore exactly
     * break-even, the same footing the furnace is on, and {@code ConservationTest} pins it against the
     * recipe's own pattern.
     */
    public static final int GRAINS = CompositeShapes.STONECUTTER_GRAINS;

    /** Vanilla's own bench: nine deep, and the only part you stand on or bump into. */
    private static final net.minecraft.world.phys.shapes.VoxelShape BENCH =
            box(0.0, 0.0, 0.0, 16.0, 9.0, 16.0);

    /*
     * The saw, given enough thickness to be clicked.
     *
     * The model draws it as a zero-thickness plane at z=8 — fine to look at, impossible to point at,
     * because a ray has nothing to hit. One pixel either side is enough to catch a cursor and still
     * matches what you see.
     *
     * Two boxes rather than four: rotating the plane 180° about y maps it onto itself, so north and
     * south share one and east and west share the other. Which applies is decided by the facing's
     * *axis* for exactly that reason.
     */
    private static final net.minecraft.world.phys.shapes.VoxelShape ALONG_X =
            net.minecraft.world.phys.shapes.Shapes.or(BENCH, box(1.0, 9.0, 7.5, 15.0, 16.0, 8.5));

    private static final net.minecraft.world.phys.shapes.VoxelShape ALONG_Z =
            net.minecraft.world.phys.shapes.Shapes.or(BENCH, box(7.5, 9.0, 1.0, 8.5, 16.0, 15.0));

    public CompositeStonecutterBlock(Properties properties) {
        super(properties);
    }

    /**
     * The blade is part of what you can point at — which is what lets you scrape it.
     *
     * <p>Vanilla's outline is the bench alone, because vanilla's blade is decoration. Ours is a
     * working part that moss can jam, and a player who can see moss on the blade should be able to put
     * a brush to <i>the blade</i> rather than hunting for the right patch of bench. Only the outline
     * changes: {@link #getCollisionShape} and {@link #getOcclusionShape} stay at the bench, so you
     * still walk over a stonecutter exactly as before and it still casts vanilla's shadow.
     *
     * <p>Ray tracing clips against this shape, so extending it is the whole fix —
     * {@code getInteractionShape} would only have overridden which <i>face</i> was reported, not
     * whether anything was hit at all.
     */
    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(
            BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return state.getValue(FACING).getAxis() == net.minecraft.core.Direction.Axis.Z
                ? ALONG_X
                : ALONG_Z;
    }

    /** Vanilla's, deliberately: a taller outline must not become a taller obstacle. */
    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(
            BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return BENCH;
    }

    /**
     * Vanilla's too. {@code useShapeForLightOcclusion} is true on a stonecutter, so without this the
     * wider outline would quietly change how the block shades itself and its neighbours.
     */
    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getOcclusionShape(
            BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return BENCH;
    }

    @Override
    public MapCodec<StonecutterBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeBlockEntity(pos, state);
    }

    /**
     * Drops the bench whole, or — under the hammer — the two blocks it was built from, exactly.
     *
     * <p>Each half is taken apart on its own terms, the same rule a double slab uses and for the same
     * reason: the two stones are separate materials, not a mixture, and pooling them into nine slots
     * before drawing would round a rare grain out of existence. Here it can be better than
     * proportional — a stonecutter holds two <i>whole</i> compositions, so there is no fraction to
     * draw for and no chance involved at all. Hammer one built from marble and granite and you get
     * back nine marble and nine granite, every time.
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity entity = params.getOptionalParameter(
                net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY);
        ItemStack tool = params.getOptionalParameter(
                net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL);
        if (tool != null && tool.is(GranularityItems.HAMMER.get())
                && entity instanceof CompositeBlockEntity composite) {
            List<ItemStack> grains =
                    new java.util.ArrayList<>(CompositionDrops.toStacks(composite.composition()));
            com.tarosie.granularity.core.Composition second = composite.upper();
            // A stonecutter from before the second stone existed, or one handed out by a command,
            // has one composition doing both jobs — so it gives that one back twice, which is still
            // the two blocks its recipe cost.
            grains.addAll(CompositionDrops.toStacks(
                    second != null ? second : composite.composition()));
            return grains;
        }
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

    /**
     * Sneak-click grows moss, plain click opens the cutter.
     *
     * <p>The hand-off matters here more than on an observer: a stonecutter has a real interaction to
     * preserve, and swallowing it would leave a block that looks like a workbench and does nothing.
     * {@link CompositeShapes#interact} passes when it has nothing to do, and {@code useWithoutItem} —
     * which is where {@link StonecutterBlock} opens its menu — runs after this returns PASS.
     */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        net.minecraft.world.ItemInteractionResult result =
                CompositeShapes.interact(stack, state, level, pos, player, hit);
        if (result != net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return result;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /**
     * A blade packed with moss opens nothing.
     *
     * <p>{@link Fouling} is the rule — a machine with moss over its working part has stopped working —
     * and this is the first block in the family where stopping is <i>visible</i>: the saw stops turning
     * too, via {@code StoppedBladeModel}. A furnace that refuses to smelt looks exactly like one that
     * will, so the stonecutter is the one that can tell the player why without a word.
     *
     * <p>Overridden <b>here</b> rather than in {@code useWithoutItem} because this is the choke point:
     * {@code Player.openMenu} accepts null and does nothing, so anything else that reaches for this
     * block's menu — another mod, a command, a future automation block — is refused by the same rule
     * rather than by a check it never runs. {@code StonecutterMenuMixin} closes one already open.
     *
     * <p>Brush the moss off and it works again; nothing here is permanent.
     */
    @Nullable
    @Override
    protected net.minecraft.world.MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        if (Fouling.bladeFouled(level, pos, state)) {
            return null;
        }
        return super.getMenuProvider(state, level, pos);
    }

    /** A piston push rebuilds the block at the far end; this is where its memory catches up. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        PistonMoves.land(level, pos, isMoving);
    }
}
