package com.tarosie.granularity.client;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.content.CompositionHolder;
import com.tarosie.granularity.content.GranularityBlocks;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionFunction;
import com.tarosie.granularity.core.Grain;
import com.tarosie.granularity.core.WaterTable;
import com.tarosie.granularity.core.WorldSalt;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/**
 * What you are looking at, on the debug screen.
 *
 * <p>The same reading {@code /granularity composition} gives, without having to stop and type it.
 * Composition is the mod's whole subject and almost none of it is legible from outside a block's
 * appearance, so the difference between "ask deliberately" and "always there while F3 is open" is the
 * difference between checking a hypothesis and noticing something.
 *
 * <h2>Why the client can answer this at all</h2>
 * Because composition is a pure function of position and salt, and the salt is synced at login for
 * exactly this reason — it is what lets the client derive the same world the server does. Nothing here
 * is asked of the server; it is the same arithmetic, run again.
 *
 * <h2>The half that cannot be derived</h2>
 * Composition says what a block <i>should</i> hold. What it actually holds after a spring drained it
 * or a bucket soaked into it is a deviation, and deviations are server state. This showed the baseline
 * alone at first, which is worse than showing nothing — a number that is quietly the wrong number
 * invites conclusions drawn from it.
 *
 * <p>So {@link NearbyWater} carries the deviations for the chunks a player can reach, along with what
 * the tiers are doing, and the two are printed side by side exactly as the command prints them. Where
 * nothing has been disturbed they agree and only one is shown, which is the common case and should
 * look like the common case.
 */
@EventBusSubscriber(modid = Granularity.MODID, value = Dist.CLIENT)
public final class CompositionDebug {

    private CompositionDebug() {
    }

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null
                || !(client.hitResult instanceof BlockHitResult hit)
                || client.hitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos pos = hit.getBlockPos();
        Composition composition = compositionAt(client, pos);
        if (composition == null) {
            return;
        }

        event.getRight().add("");
        event.getRight().add("[Granularity] " + tally(composition));

        // Baseline and actual side by side, exactly as the command prints them, because the whole
        // point of syncing deviations was that showing one as though it were the other is worse than
        // showing nothing. Where nothing has been disturbed the two agree and only one is printed.
        int holding = NearbyWater.known()
                ? NearbyWater.waterAt(pos.asLong(), composition)
                : composition.water();
        String water = holding == composition.water()
                ? "water " + holding
                : "water " + holding + " (baseline " + composition.water() + ")";
        event.getRight().add(String.format(Locale.ROOT,
                "porosity %d/%d  %s  free %d",
                composition.porosity(), Composition.SLOTS, water, composition.freeSlots()));

        if (WorldSalt.ClientView.isPresent()) {
            long salt = WorldSalt.ClientView.get().value();
            event.getRight().add(String.format(Locale.ROOT,
                    "table y=%.1f  saturation %.2f",
                    WaterTable.elevation(pos.getX(), pos.getZ(), salt),
                    WaterTable.saturation(pos.getX(), pos.getY(), pos.getZ(), salt)));
        }

        if (NearbyWater.known()) {
            event.getRight().add(String.format(Locale.ROOT,
                    "sky %d vapour, %d fallen  |  patches %d, springs %d",
                    NearbyWater.humidity(), NearbyWater.recentRain(),
                    NearbyWater.patches(), NearbyWater.springs()));
            event.getRight().add(String.format(Locale.ROOT,
                    "weeps %d  |  %d blocks disturbed nearby",
                    NearbyWater.weepsEmitted(), NearbyWater.disturbedCount()));
        }
    }

    /**
     * The composition of the block being looked at, however it happens to be known.
     *
     * <p>A crafted composite carries its own and the block entity is synced, so the client can ask it.
     * Natural stone stores nothing and is derived — which is the whole design, and is why this works
     * on the client at all.
     */
    private static Composition compositionAt(Minecraft client, BlockPos pos) {
        if (client.level.getBlockEntity(pos) instanceof CompositionHolder held) {
            return held.composition();
        }
        if (!client.level.getBlockState(pos).is(GranularityBlocks.NATURAL_STONE.get())
                || !WorldSalt.ClientView.isPresent()) {
            return null;
        }
        return CompositionFunction.stone(
                pos.getX(), pos.getY(), pos.getZ(), WorldSalt.ClientView.get().value());
    }

    /** Grains and how many slots each holds, shortest names, on one line. */
    private static String tally(Composition composition) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            Grain grain = composition.grainAt(slot);
            String name = grain == null ? "?" : grain.name();
            // The namespace is the same for every grain the base mod ships and costs a third of the
            // line; a datapack grain from elsewhere keeps its prefix, where it is worth reading.
            if (name.startsWith(Granularity.MODID + ":")) {
                name = name.substring(Granularity.MODID.length() + 1);
            }
            counts.merge(name, 1, Integer::sum);
        }
        StringJoiner joiner = new StringJoiner(", ");
        counts.forEach((name, count) -> joiner.add(count + "x " + name));
        return joiner.toString();
    }
}
