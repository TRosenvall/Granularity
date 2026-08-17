package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.Nullable;

/**
 * A slab that remembers the stone it was cut from.
 *
 * <p>A slab carries the <b>full nine-slot composition</b> of its parent block rather than a share of
 * its grains. Three cobblestones make six slabs, which is exactly three blocks of material — vanilla's
 * ratio already conserves — but twenty-seven grains do not divide into six, and design §12's whole
 * premise is that a grain is indivisible. So a slab is <i>half a block of composition X</i>, not a bag
 * of four and a half grains. Two slabs go back to one block of X, and conservation holds at the level
 * the model actually works at.
 *
 * <h2>Vertical slabs, for no new blocks at all</h2>
 * The reason vanilla has never shipped these is arithmetic: it has <b>sixty</b> slab blocks, and
 * orientation would multiply every one of them. We have <b>one</b>, because what a slab is made of is a
 * composition component and how it is worked is a finish component — neither is a block. Orientation is
 * the one thing that genuinely is geometry, so it goes in the blockstate, and the cost is a property.
 *
 * <p>{@link #AXIS} says which way the slab is cut, and vanilla's {@code TYPE} is reinterpreted as
 * <i>which half along that axis</i>: {@code BOTTOM} is the half on the negative side, {@code TOP} the
 * positive one, {@code DOUBLE} both. On {@code AXIS=Y} that is precisely what those words already
 * meant, so every slab in every existing world keeps working — a saved block with no axis loads the
 * default.
 *
 * <p>Two things then come free, and they are the reason this was a small change rather than a large
 * one. The <b>two-halves machinery</b> — a second composition, its own overlays, dye and finish — was
 * built for doubles and reused for the stonecutter's two stones; "upper" simply becomes "the far half
 * along the axis", so a vertical double of two different rocks works without a new idea. And
 * <b>per-face finish sprites</b> are chosen from a quad's <i>world</i> direction, so a vertical Pebbled
 * slab shows the top texture on the face that is actually facing up, matching its neighbours.
 *
 * <p>The player never handles a vertical slab: there is one item, it carries no axis, and every drop
 * is an ordinary slab again. Orientation exists only while the block is placed.
 */
public class CompositeSlabBlock extends SlabBlock implements EntityBlock, CompositeStone {

    /**
     * Which way this slab is cut. {@code TYPE} then says which half along it.
     *
     * <p>Vanilla's own {@code AXIS} property, so it costs no new registration and reads the way a
     * player would expect from a pillar.
     */
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<net.minecraft.core.Direction.Axis>
            AXIS = net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS;

    /** The six halves, indexed by axis then by which side of it. */
    private static final net.minecraft.world.phys.shapes.VoxelShape[] HALVES = {
        box(0.0, 0.0, 0.0, 8.0, 16.0, 16.0),   // X, negative — the west half
        box(8.0, 0.0, 0.0, 16.0, 16.0, 16.0),  // X, positive — the east half
        box(0.0, 0.0, 0.0, 16.0, 8.0, 16.0),   // Y, negative — vanilla's BOTTOM_AABB
        box(0.0, 8.0, 0.0, 16.0, 16.0, 16.0),  // Y, positive — vanilla's TOP_AABB
        box(0.0, 0.0, 0.0, 16.0, 16.0, 8.0),   // Z, negative — the north half
        box(0.0, 0.0, 8.0, 16.0, 16.0, 16.0),  // Z, positive — the south half
    };

    public CompositeSlabBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(AXIS, net.minecraft.core.Direction.Axis.Y));
    }

    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<
                    net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(AXIS);
    }

    /** The half this state occupies, or the whole block for a double. */
    public static net.minecraft.world.phys.shapes.VoxelShape shapeOf(BlockState state) {
        SlabType type = state.getValue(TYPE);
        if (type == SlabType.DOUBLE) {
            return net.minecraft.world.phys.shapes.Shapes.block();
        }
        return HALVES[state.getValue(AXIS).ordinal() * 2 + (type == SlabType.TOP ? 1 : 0)];
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(
            BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return shapeOf(state);
    }

    /**
     * Where along the axis the click landed, as a fraction of the block.
     *
     * <p>The one piece of arithmetic every axis-aware decision below needs, and the only thing that
     * differs from vanilla — which asks the same question of {@code y} and nothing else.
     */
    private static double along(net.minecraft.world.phys.Vec3 hit, BlockPos pos,
                                net.minecraft.core.Direction.Axis axis) {
        return axis.choose(hit.x - pos.getX(), hit.y - pos.getY(), hit.z - pos.getZ());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompositeBlockEntity(pos, state);
    }

    /**
     * Which way a slab placed by this click should lie.
     *
     * <p>Three rules, in this order, and the order is the design:
     *
     * <ol>
     *   <li><b>No sneak, no vertical.</b> An ordinary click places an ordinary slab, so nothing a
     *       player already knows how to do changes.</li>
     *   <li><b>Aiming at a slab inherits its axis.</b> Timothy's rule, and it is what makes a run of
     *       vertical slabs buildable — you point at the last one and keep clicking rather than hunting
     *       for a face at the right angle each time.</li>
     *   <li><b>Otherwise a sneak on a side face goes vertical</b>, cut along the face you are pointing
     *       at, so the slab hugs the block you clicked.</li>
     * </ol>
     *
     * <p>Sneaking on a <b>top or bottom</b> face deliberately stays horizontal, and that is not an
     * omission. Sneak-place is already vanilla's "place this instead of opening what I am pointing at",
     * which is how a slab gets onto a chest, a furnace or a stonecutter — and you reach those by
     * clicking their tops. Taking the whole gesture would have made those blocks unbuildable-on.
     */
    private net.minecraft.core.Direction.Axis placementAxis(
            net.minecraft.world.item.context.BlockPlaceContext context) {
        net.minecraft.world.entity.player.Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return net.minecraft.core.Direction.Axis.Y;
        }
        BlockState aimed = aimedAt(context);
        if (aimed.is(this)) {
            return aimed.getValue(AXIS);
        }
        net.minecraft.core.Direction face = context.getClickedFace();
        return face.getAxis().isHorizontal() ? face.getAxis() : net.minecraft.core.Direction.Axis.Y;
    }

    /**
     * The block the player is pointing at, as against the space the slab will go into.
     *
     * <p>The two are the same only when the clicked block is being replaced — merging into a double,
     * or building into grass. {@code replacingClickedOnBlock} is what tells them apart, and getting it
     * wrong would read the axis off whatever happened to be behind the target.
     */
    private BlockState aimedAt(net.minecraft.world.item.context.BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        if (!context.replacingClickedOnBlock()) {
            pos = pos.relative(context.getClickedFace().getOpposite());
        }
        return context.getLevel().getBlockState(pos);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        BlockState existing = context.getLevel().getBlockState(pos);
        if (existing.is(this)) {
            // Merging into a double. The axis is already whatever the slab being filled lies on, and
            // canBeReplaced has already refused a click that would have merged across two axes.
            return existing.setValue(TYPE, SlabType.DOUBLE).setValue(WATERLOGGED, false);
        }

        net.minecraft.core.Direction.Axis axis = placementAxis(context);
        BlockState placed = defaultBlockState()
                .setValue(AXIS, axis)
                .setValue(WATERLOGGED,
                        context.getLevel().getFluidState(pos).getType() == net.minecraft.world.level.material.Fluids.WATER);

        BlockState aimed = aimedAt(context);
        if (axis != net.minecraft.core.Direction.Axis.Y && aimed.is(this)
                && aimed.getValue(TYPE) != SlabType.DOUBLE) {
            // Following a run: sit in the same plane as the slab being pointed at, not merely on the
            // same axis. Otherwise every second slab in a wall would be offset by half a block.
            return placed.setValue(TYPE, aimed.getValue(TYPE));
        }

        net.minecraft.core.Direction face = context.getClickedFace();
        if (face.getAxis() == axis) {
            // Clicked square onto the axis: the slab hugs the face it was placed against. Pointing at
            // a block's east face puts the slab in the space east of it, against its west side.
            return placed.setValue(TYPE,
                    face.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE
                            ? SlabType.BOTTOM
                            : SlabType.TOP);
        }
        // Clicked across the axis: the half is whichever side of it the cursor fell on. This is
        // vanilla's rule for a slab placed against a wall, asked about the axis rather than about y.
        return placed.setValue(TYPE,
                along(context.getClickLocation(), pos, axis) > 0.5 ? SlabType.TOP : SlabType.BOTTOM);
    }

    /**
     * Whether this click fills the empty half of an existing slab.
     *
     * <p>Vanilla's own rule with {@code y} replaced by the axis, plus one addition: <b>two slabs only
     * merge if they lie the same way</b>. A vertical slab and a horizontal one in one block space is
     * not a double of anything, and without this a careless click would silently reorient one of them.
     */
    @Override
    protected boolean canBeReplaced(BlockState state,
                                    net.minecraft.world.item.context.BlockPlaceContext useContext) {
        SlabType type = state.getValue(TYPE);
        if (type == SlabType.DOUBLE || !useContext.getItemInHand().is(asItem())) {
            return false;
        }
        if (!useContext.replacingClickedOnBlock()) {
            return true;
        }
        net.minecraft.core.Direction.Axis axis = state.getValue(AXIS);
        if (placementAxis(useContext) != axis) {
            return false;
        }
        net.minecraft.core.Direction face = useContext.getClickedFace();
        boolean far = along(useContext.getClickLocation(), useContext.getClickedPos(), axis) > 0.5;
        net.minecraft.core.Direction positive =
                net.minecraft.core.Direction.get(net.minecraft.core.Direction.AxisDirection.POSITIVE, axis);
        return type == SlabType.BOTTOM
                ? face == positive || (far && face.getAxis() != axis)
                : face == positive.getOpposite() || (!far && face.getAxis() != axis);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity entity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        boolean isDouble = state.getValue(TYPE) == SlabType.DOUBLE;

        if (!(entity instanceof CompositeBlockEntity composite)) {
            return List.of(new ItemStack(this, isDouble ? 2 : 1));
        }
        // The hammer takes each half apart on its own terms, so a double made of two stones gives
        // back both. Moss is growth rather than material and simply burns off in the process.
        ItemStack tool = params.getOptionalParameter(LootContextParams.TOOL);
        if (tool != null && tool.is(GranularityItems.HAMMER.get())) {
            // Each half draws separately, so a mixed double is two independent draws rather than one
            // doubled — the same reason its two halves drop as two stacks.
            net.minecraft.util.RandomSource random = params.getLevel().getRandom();
            List<ItemStack> grains = new java.util.ArrayList<>(CompositionDrops.toStacks(
                    composite.composition(), CompositeShapes.SLAB_GRAINS, random));
            if (isDouble) {
                Composition top = composite.upper();
                grains.addAll(CompositionDrops.toStacks(top != null ? top : composite.composition(),
                        CompositeShapes.SLAB_GRAINS, random));
            }
            return grains;
        }
        if (!isDouble) {
            return List.of(half(composite.composition(), composite.overlays(), composite.dyes(),
                    composite.finish(), 1));
        }
        // Two halves, two stacks: a stack of two shares one set of components, so a slate half and
        // a gabbro half cannot travel together however they are counted. Moss counts the same way —
        // a mossy half and a clean one are as different as two stones are.
        Composition upper = composite.upper();
        Coating lowerOverlays = composite.overlays();
        Coating upperOverlays = composite.upperOverlays();
        Dyes lowerDyes = composite.dyes();
        Dyes upperDyes = composite.upperDyes();
        // Dye joins composition and moss in deciding whether the two halves can travel as one stack:
        // a stack of two shares one set of components, so a red half and a grey one cannot.
        // A finish joins composition, moss and dye in deciding whether the halves can travel as one
        // stack: smelting one half of a double leaves two genuinely different slabs.
        if (upper == null && lowerOverlays.equals(upperOverlays) && lowerDyes.equals(upperDyes)
                && composite.finish() == composite.upperFinish()) {
            return List.of(half(composite.composition(), lowerOverlays, lowerDyes,
                    composite.finish(), 2));
        }
        return List.of(
                half(composite.composition(), lowerOverlays, lowerDyes, composite.finish(), 1),
                half(upper != null ? upper : composite.composition(), upperOverlays, upperDyes,
                        composite.upperFinish(), 1));
    }

    private ItemStack half(Composition composition, Coating overlays, Dyes dyes,
                           com.tarosie.granularity.core.Finish finish, int count) {
        ItemStack stack = new ItemStack(this, count);
        Finishes.apply(stack, finish);
        stack.set(GranularityComponents.COMPOSITION.get(), composition);
        Dyes.apply(stack, dyes);
        Moss.apply(stack, overlays);
        return stack;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        Composition incoming = CompositeBlock.compositionOf(stack);
        if (incoming == null || !(level.getBlockEntity(pos) instanceof CompositeBlockEntity composite)) {
            return;
        }
        var type = state.getValue(TYPE);
        // Dye goes in through setSlabHalf with the composition and the moss, not afterwards: stacking
        // a red slab onto a grey one has to fill the free half, and a separate call would have painted
        // both. The same bug the composition already had a fix for.
        composite.setSlabHalf(type == SlabType.DOUBLE, type == SlabType.TOP, incoming,
                Moss.of(stack), Dyes.of(stack), Finishes.of(stack));
    }

    @Override
    public ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        // A double picks up as one slab, so it hands back the lower half's overlays and dye.
        if (level.getBlockEntity(pos) instanceof CompositeBlockEntity composite) {
            stack.set(GranularityComponents.COMPOSITION.get(), composite.composition());
            Dyes.apply(stack, composite.dyes());
            Moss.apply(stack, composite.overlays());
            Finishes.apply(stack, composite.finish());
        }
        return stack;
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

    /** A piston push rebuilds the block at the far end; this is where its memory catches up. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        PistonMoves.land(level, pos, isMoving);
    }

}
