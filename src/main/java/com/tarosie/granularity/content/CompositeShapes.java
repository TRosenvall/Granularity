package com.tarosie.granularity.content;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.Nullable;

/**
 * The composition bookkeeping every cut shape shares.
 *
 * <p>Stairs, walls and slabs differ only in geometry; what they do with a composition is identical.
 * Like a slab, a cut shape carries the <b>full nine-slot composition</b> of the block it came from
 * rather than a share of its grains — design §12 makes a grain indivisible, and four cobblestones do
 * not divide into six walls any more evenly than into slabs. The shape is <i>a fraction of a block of
 * composition X</i>, and conservation holds at the composition level.
 */
public final class CompositeShapes {

    private CompositeShapes() {
    }

    /**
     * What one shape gives back under the hammer, in grains out of nine.
     *
     * <p>A shape returns <b>what it cost to make</b>, rounded down, and our own recipes are the
     * statement of that cost. Three cobblestones make six slabs, so a slab cost half a block — four
     * grains of nine. Six make six walls and six make six stairs, so each of those cost a whole block:
     * nine.
     *
     * <p>The stair used to be <b>thirteen</b>, because vanilla's staircase yields four from six and we
     * inherited the loss. That made it the one shape worth more than the stone it came from, and made
     * {@code block -> 1 stair} at a stonecutter a grain press — so the cut had to be withheld while
     * every other shape had one. Raising our yield to six (see {@code CutShapeRecipe.Shape.STAIRS})
     * prices a stair at one block, which is what a full-height shape should cost and what a wall
     * already cost. The exception disappeared rather than being managed.
     *
     * <p>The <b>count</b> is a loss or a wash and never a gain: six slabs return 24 grains of the 27
     * that went in; six stairs and six walls return exactly what they cost. <b>Which</b> materials
     * come back is drawn by chance, so a single hammering can hand back more gold than the block held
     * — that is deliberate, and it averages out. See
     * {@link CompositionDrops#toStacks(com.tarosie.granularity.core.Composition, int,
     * net.minecraft.util.RandomSource)}.
     */
    public static final int SLAB_GRAINS = 4;

    /** A full-height shape costs a whole block, the same as a wall. See the note above for the 13. */
    public static final int STAIRS_GRAINS = 9;

    public static final int WALL_GRAINS = 9;

    /**
     * What a stonecutter gives back: the two whole blocks it was built from.
     *
     * <p>The one member of the stoneware family whose recipe is priced in blocks rather than in
     * chunks, because it is built from <i>smooth</i> stone and smooth is a finish, which lives on a
     * block. Two nines, exactly break-even — the same footing the furnace's eight chunks are on, and
     * pinned by {@code ConservationTest} against the recipe's own pattern.
     *
     * <p>Better than break-even, in fact: it is <b>exact</b>, and uniquely so. Every other hammer
     * yield is a draw by chance, because a shape is a fraction of a block and a grain does not divide.
     * A stonecutter is two whole blocks and keeps them whole and separate — one in each half — so
     * hammering it hands back precisely the two stones that went in, with nothing left to rounding.
     *
     * <p>It lives here rather than on {@link CompositeStonecutterBlock} so that the test can read it
     * without loading a Block subclass, which a test with no running game cannot do.
     */
    public static final int STONECUTTER_GRAINS = 2 * com.tarosie.granularity.core.Composition.SLOTS;

    /**
     * Drops the shape itself, carrying its composition — or its grains, under the hammer.
     *
     * <p>The hammer is how you say "no, I want the pieces", and a cut shape answers it the same way
     * a whole block does. It cannot answer exactly: a shape is a fraction of a block and a grain does
     * not divide, so the fraction is taken proportionally and rounded down. See
     * {@link CompositionDrops#toStacks(com.tarosie.granularity.core.Composition, int)}.
     */
    public static List<ItemStack> drops(Block block, net.minecraft.world.level.block.state.BlockState state,
                                        LootParams.Builder params, int count, int grains) {
        BlockEntity entity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        ItemStack tool = params.getOptionalParameter(LootContextParams.TOOL);
        if (tool != null && tool.is(GranularityItems.HAMMER.get())
                && entity instanceof CompositionHolder composite) {
            return CompositionDrops.toStacks(composite.composition(), grains,
                    params.getLevel().getRandom());
        }
        ItemStack stack = new ItemStack(block, count);
        if (entity instanceof CompositionHolder composite) {
            stack.set(GranularityComponents.COMPOSITION.get(), composite.composition());
            Dyes.apply(stack, composite.dyes());
            if (composite.wood() != null) {
                stack.set(GranularityComponents.WOOD.get(), composite.wood());
            }
            if (composite.metal() != null) {
                stack.set(GranularityComponents.METAL.get(), composite.metal());
            }
            Moss.apply(stack, composite.overlays());
        }
        if (entity instanceof CompositeBlockEntity composite) {
            // The hammer path above returns before this: grains have no finish, because smelting a
            // block did not smelt the rocks inside it.
            Finishes.apply(stack, composite.finish());
            carrySecondStone(stack, composite);
        }
        return List.of(stack);
    }

    /** Middle-click has to hand back a block that remembers what it was made of. */
    public static ItemStack cloned(ItemStack stack, net.minecraft.world.level.LevelReader level, BlockPos pos,
                                   net.minecraft.world.level.block.state.BlockState state) {
        if (level.getBlockEntity(pos) instanceof CompositionHolder composite) {
            stack.set(GranularityComponents.COMPOSITION.get(), composite.composition());
            Dyes.apply(stack, composite.dyes());
            if (composite.wood() != null) {
                stack.set(GranularityComponents.WOOD.get(), composite.wood());
            }
            if (composite.metal() != null) {
                stack.set(GranularityComponents.METAL.get(), composite.metal());
            }
            Moss.apply(stack, composite.overlays());
        }
        if (level.getBlockEntity(pos) instanceof CompositeBlockEntity composite) {
            Finishes.apply(stack, composite.finish());
            carrySecondStone(stack, composite);
        }
        return stack;
    }

    /**
     * Puts a two-stone block's second composition onto its stack, so picking it up keeps both.
     *
     * <p>Only the stonecutter has one today. A double slab does <b>not</b> come through here — it has
     * its own {@code getDrops}, and drops as two separate slabs rather than as one item carrying two
     * stones, which is why its upper half never needed a component before now.
     *
     * <p>Written unconditionally when present and never cleared: a stack that has no second stone
     * should carry no component at all, so that an ordinary composite stays byte-identical to one
     * made before this existed and goes on stacking with it.
     */
    private static void carrySecondStone(ItemStack stack, CompositeBlockEntity composite) {
        if (composite.upper() != null) {
            stack.set(GranularityComponents.UPPER_COMPOSITION.get(), composite.upper());
        }
    }

    /**
     * Sneak + right-click: dye the matrix, or grow moss on it.
     *
     * <p>Sneaking is what distinguishes both from an ordinary click, so a stray click on a finished
     * wall does nothing — the anti-accident property CRAFTED_BLOCKS §8 wanted without a dedicated
     * tool. <b>Dye only ever touches the matrix</b>, never the nine stones, because the colour system
     * exists so that dye is not how you get a shade: the mortar is the escape hatch, the stones stay
     * what you dug.
     */
    public static net.minecraft.world.ItemInteractionResult interact(
            ItemStack stack, net.minecraft.world.level.block.state.BlockState state, Level level,
            BlockPos pos, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        // Scraping comes before the sneak gate, because stripping a log with an axe needs no sneak
        // and this is the same gesture. It passes when the face is already bare, so a sword in hand
        // still opens a clean furnace and only scrapes a dirty one.
        if (stack.is(net.minecraft.tags.ItemTags.SWORDS)) {
            net.minecraft.world.ItemInteractionResult scraped =
                    scrape(stack, state, level, pos, player, hit);
            if (scraped != net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                return scraped;
            }
        }
        if (!player.isShiftKeyDown()) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.DyeItem dye) {
            if (!(level.getBlockEntity(pos) instanceof CompositionHolder composite)) {
                return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            // One face per click, and one dye per face — the same bargain moss and scraping strike.
            // A whole block is six clicks, which is the honest price of being able to paint one side
            // of a wall and leave the rest as it was dug.
            int colour = dye.getDyeColor().getTextureDiffuseColor() & 0xFFFFFF;
            boolean upper = Moss.upperHalfAt(state, pos, hit.getLocation());
            Dyes painted = (upper ? composite.upperDyes() : composite.dyes())
                    .with(colour, hit.getDirection());
            if (painted == null) {
                return net.minecraft.world.ItemInteractionResult.CONSUME;
            }
            if (!level.isClientSide) {
                composite.setDyes(upper, painted);
                stack.consume(1, player);
            }
            return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        // Slime is applied by hand; moss is not, and used to be. Moss now arrives from bonemeal
        // reaching nearby stone — see MossSpread — because a thing that grows should look like it
        // grew rather than like it was placed, and because that is the mechanic worth building on.
        if (stack.is(net.minecraft.world.item.Items.SLIME_BALL)) {
            return grow(stack, state, level, pos, player, hit, GranularityOverlays.SLIME.get());
        }
        return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * Grows an overlay on the face the player clicked, on the half they clicked.
     *
     * <p>One face per click, deliberately. An overlay that covers a whole block the moment you touch
     * it is paint; moss on the face you reached for is something that could as well have arrived on
     * its own, and the same call is what a spreading mechanic would make later.
     *
     * <p>Nothing changes but the block entity, so the composition stays put, a wall keeps its
     * connections, and a double slab takes the overlay on <b>one</b> half — which a shared blockstate
     * flag got wrong twice.
     */
    private static net.minecraft.world.ItemInteractionResult grow(
            ItemStack stack, net.minecraft.world.level.block.state.BlockState state, Level level,
            BlockPos pos, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit, Overlay overlay) {
        if (!(level.getBlockEntity(pos) instanceof CompositionHolder composite)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        boolean upper = Moss.upperHalfAt(state, pos, hit.getLocation());
        Coating grown = (upper ? composite.upperOverlays() : composite.overlays())
                .with(overlay, hit.getDirection());
        if (grown == null) {
            return net.minecraft.world.ItemInteractionResult.CONSUME;
        }
        if (!level.isClientSide) {
            composite.setOverlays(upper, grown);
            stack.consume(1, player);
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Scrapes one face clean with the edge of a blade.
     *
     * <p>Modelled on stripping a log: no sneak, one swing, a little wear on the tool. The face is the
     * one clicked, so clearing a whole block is six swings — the same asymmetry growing it has, and
     * the reason a mossed-over furnace is a thing you have to deal with rather than a thing you undo.
     *
     * <p>Keyed on the {@code swords} tag rather than {@code SwordItem}, so another mod's blade works.
     */
    private static net.minecraft.world.ItemInteractionResult scrape(
            ItemStack stack, net.minecraft.world.level.block.state.BlockState state, Level level,
            BlockPos pos, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CompositionHolder composite)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        boolean upper = Moss.upperHalfAt(state, pos, hit.getLocation());
        Coating bare = (upper ? composite.upperOverlays() : composite.overlays())
                .without(hit.getDirection());
        if (bare == null) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            composite.setOverlays(upper, bare);
            stack.hurtAndBreak(1, player, player.getMainHandItem() == stack
                    ? net.minecraft.world.entity.EquipmentSlot.MAINHAND
                    : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
        }
        level.playSound(player, pos, net.minecraft.sounds.SoundEvents.MOSS_BREAK,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 0.9F + level.random.nextFloat() * 0.2F);
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    public static void placed(Level level, BlockPos pos, ItemStack stack) {
        CompositeBlock.applyTo(level, pos, stack);
    }

    @Nullable
    public static BlockEntity entity(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return new CompositeBlockEntity(pos, state);
    }
}
