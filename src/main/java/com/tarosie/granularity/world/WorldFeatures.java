package com.tarosie.granularity.world;

import com.tarosie.granularity.Granularity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Vanilla behaviour Granularity switches off while it builds its own replacements.
 *
 * <p>Both of these are temporary, and they are here rather than quietly left alone because a
 * half-built world is easier to reason about when the parts that do not belong in it yet are simply
 * absent.
 *
 * <p>Strongholds are removed by a datapack override of {@code worldgen/structure_set/strongholds}
 * with an empty structure list, not by code — a one-field data file rather than an event hook.
 */
@EventBusSubscriber(modid = Granularity.MODID)
public final class WorldFeatures {

    private WorldFeatures() {
    }

    /**
     * Nether portals do not light.
     *
     * <p>Cancelling the spawn rather than removing the blocks: a frame can still be built, it just
     * never ignites, so nothing a player has already placed gets destroyed. Timothy's portal rework
     * — redstone-dust ignition, any full-cube frame material, hardness-proportional decay —
     * replaces this later.
     */
    @SubscribeEvent
    public static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        event.setCanceled(true);
    }
}
