package com.tarosie.granularity.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Holding a brush against a mossy face until the moss comes off.
 *
 * <h2>Why the gesture and not just the item</h2>
 * Moss came off a sword in one instant click, and then off a brush in one instant click, and the
 * second was worse than the first for a reason worth writing down: a sword <i>is</i> an instant-swing
 * tool, so an instant result reads correctly, whereas everyone who has met vanilla's brush has met it
 * as something you hold. Using the item without its gesture is an uncanny half-measure — the right
 * tool doing the wrong motion.
 *
 * <p>So this is vanilla's brushing, applied to our coatings: hold the button, dust comes off the
 * block, and after {@value #BRUSH_TICKS} ticks the face is clean.
 *
 * <h2>Almost all of it is vanilla's</h2>
 * {@code BrushItem.onUseTick} already spawns dust particles for <b>any</b> block and plays
 * {@code BRUSH_GENERIC} for anything that is not suspicious sand, on a ten-tick beat. It also releases
 * the item the moment the player stops looking at a block. None of that had to be written; starting
 * the use is what buys it. All this adds is the ending.
 *
 * <p>Two brush strokes then clean, at twenty ticks — the beat falls on ticks 5 and 15, so the timing
 * was chosen to land just after the second one rather than cutting a stroke short. Six faces is six
 * seconds of deliberate work, which is the same answer the sword gave at one stroke per face and the
 * same answer growing the moss gave.
 *
 * <h2>Where the previous attempt went wrong, and why this one is different</h2>
 * Wearing moss off by <i>mining</i> was tried and reverted. That fought the client's block-break
 * prediction: the client destroys its own block entity before the server has a say, so a cancelled
 * break leaves it drawing a block that has forgotten its composition. <b>A use has no such
 * prediction.</b> Nothing is destroyed, nothing is predicted, and the only state that changes is a
 * coating the server syncs the ordinary way.
 */
public final class BrushCleaning {

    /** How long a face takes to brush clean. Two strokes of vanilla's ten-tick beat, plus the finish. */
    public static final int BRUSH_TICKS = 20;

    private BrushCleaning() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(BrushCleaning::onUseTick);
    }

    private static void onUseTick(LivingEntityUseItemEvent.Tick event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack stack = event.getItem();
        if (!stack.is(Items.BRUSH)) {
            return;
        }
        // getDuration is what is *left*, so this counts up from zero as the brush is held.
        int brushed = stack.getUseDuration(player) - event.getDuration();
        if (brushed < BRUSH_TICKS) {
            return;
        }
        Level level = player.level();
        BlockHitResult hit = coatedFaceInSight(player);
        if (hit == null) {
            // Nothing under the brush worth taking off — the player turned away, or another player
            // got there first, or this was a clean block all along. Stop rather than go on brushing
            // thin air for the remaining nine seconds of vanilla's duration.
            //
            // Only ever after the threshold, so a glance away mid-stroke costs nothing: progress is
            // held and the face still comes clean when the brush is back on it.
            player.stopUsingItem();
            return;
        }
        BlockState state = level.getBlockState(hit.getBlockPos());
        if (CompositeShapes.strip(level, hit.getBlockPos(), state, player,
                hit.getDirection(), hit.getLocation()) && !level.isClientSide) {
            stack.hurtAndBreak(1, player, player.getMainHandItem() == stack
                    ? EquipmentSlot.MAINHAND
                    : EquipmentSlot.OFFHAND);
        }
        // Stopped on both sides, or the client would go on brushing a face that is already clean.
        player.stopUsingItem();
    }

    /**
     * The face the player is aiming at, if it is one of ours and has something on it.
     *
     * <p>Raytraced rather than remembered from the click. A player can turn while brushing, and the
     * face that gets cleaned should be the one under the brush when it finishes — which is also how
     * vanilla decides where its own dust comes from, so the particles and the result always agree.
     */
    @Nullable
    private static BlockHitResult coatedFaceInSight(Player player) {
        HitResult look = player.pick(player.blockInteractionRange() + 1.0, 1.0F, false);
        if (!(look instanceof BlockHitResult hit)) {
            return null;
        }
        Level level = player.level();
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CompositeStone)) {
            return null;
        }
        return CompositeShapes.coated(level, pos, state, hit.getDirection(), hit.getLocation())
                ? hit
                : null;
    }
}
