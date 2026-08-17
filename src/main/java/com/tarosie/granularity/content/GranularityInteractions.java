package com.tarosie.granularity.content;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Sneak + right-click, routed through NeoForge rather than {@code Block#useItemOn}.
 *
 * <p>This exists because the obvious implementation cannot work. {@code ServerPlayerGameMode.useItemOn}
 * skips a block's {@code useItemOn} whenever the player is sneaking <b>and</b> holding an item — that
 * is how vanilla lets you place a block against a chest instead of opening it. Since sneak-plus-item
 * is exactly the gesture dyeing and mossing use, the block-side hook is never reached.
 *
 * <p>{@link PlayerInteractEvent.RightClickBlock} fires ahead of that check, so intercepting here and
 * cancelling is what makes the gesture available at all.
 */
public final class GranularityInteractions {

    private GranularityInteractions() {
    }

    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(GranularityInteractions::onRightClickBlock);
        modEventBus.addListener(GranularityInteractions::registerCauldron);
    }

    /**
     * Washing dye out in a water cauldron, the way vanilla cleans a shulker box or leather boots.
     *
     * <p>Dye is otherwise a one-way door: a face can be repainted but never returned to showing the
     * average of its grains. This is the undo, and the cauldron is where vanilla already keeps that
     * verb, so it needs no new item and no new gesture to learn.
     *
     * <p>It washes the <b>whole block</b> — every face at once — because you are dunking the thing,
     * not wiping one side of it. Per-face removal is the sponge's job when a sponge exists, and the
     * two read as different actions precisely because one is a dunk and the other is a wipe.
     *
     * <p>Registered by walking the item registry rather than naming fourteen items, so a composite
     * added later is washable without touching this file. Done in {@code FMLCommonSetup} because the
     * registries must be frozen first, and inside {@code enqueueWork} because the interaction map is
     * plain and mod setup runs in parallel.
     */
    private static void registerCauldron(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            int washable = 0;
            for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                if (item instanceof net.minecraft.world.item.BlockItem block
                        && block.getBlock() instanceof CompositeStone) {
                    net.minecraft.core.cauldron.CauldronInteraction.WATER.map()
                            .put(item, GranularityInteractions::washDye);
                    washable++;
                }
            }
            com.tarosie.granularity.Granularity.LOGGER.info(
                    "Cauldron washing registered for {} composite items.", washable);
        });
    }

    /**
     * Strips the dye and takes a level of water with it.
     *
     * <p>Passing when the stack is not dyed matters: without it, a cauldron would swallow a level of
     * water for cleaning a block that was never dirty, and an undyed composite could not be put into
     * a cauldron for any other reason ever again.
     */
    private static ItemInteractionResult washDye(BlockState state, Level level,
                                                 net.minecraft.core.BlockPos pos, Player player,
                                                 net.minecraft.world.InteractionHand hand,
                                                 net.minecraft.world.item.ItemStack stack) {
        if (!stack.has(GranularityComponents.DYES.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            stack.remove(GranularityComponents.DYES.get());
            net.minecraft.world.level.block.LayeredCauldronBlock.lowerFillLevel(state, level, pos);
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.BUCKET_EMPTY,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown()) {
            return;
        }
        Level level = event.getLevel();
        BlockState state = level.getBlockState(event.getPos());
        // The hit tells us two things: which face the player reached for, and — on a double slab —
        // which half of it they meant.
        ItemInteractionResult result = CompositeShapes.interact(event.getItemStack(), state, level,
                event.getPos(), player, event.getHitVec());
        if (result == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return;
        }
        // Cancelling is what stops the moss block being placed against the cobble instead.
        event.setCanceled(true);
        event.setCancellationResult(result == ItemInteractionResult.CONSUME
                ? InteractionResult.CONSUME
                : InteractionResult.sidedSuccess(level.isClientSide));
    }
}
