package com.tarosie.granularity.content;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.GrainSpec;
import com.tarosie.granularity.core.Grains;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/**
 * Grains as a synced datapack registry, and the bridge from it into the roster.
 *
 * <h2>Why a registry rather than a reload listener</h2>
 * A natural block's composition is <b>derived, not stored</b> (design §4) — and derived on the client
 * as well as the server, from position, salt and the roster. So the two must hold the same grains, or
 * a client renders rock the server does not have. Crafted blocks were already safe, because
 * {@link CompositionCodecs} sends compositions as names; natural stone is the one that cannot be,
 * because nothing is sent at all.
 *
 * <p>This is the problem vanilla already solved for biomes and dimension types, so the answer is to
 * use its solution. Declaring a datapack registry with a network codec buys three things that would
 * otherwise each have to be built and each fail quietly when wrong:
 *
 * <ul>
 *   <li><b>The client gets the definitions</b>, sent during the configuration phase — before the play
 *       phase and before the first chunk packet, rather than racing them the way a login payload
 *       would.</li>
 *   <li><b>{@code /reload} cannot change the roster.</b> Datapack registries are read once per world
 *       load. That was previously a flag this class had to maintain by hand, and it is the rule that
 *       keeps a world from generating different rock halfway through.</li>
 *   <li><b>Nothing leaks between servers.</b> The client's registry access belongs to the connection.
 *       </li>
 * </ul>
 *
 * <p>{@link Grains} itself learns none of this: it takes {@link GrainSpec}s and stays free of
 * Minecraft types, so the composition function underneath it remains testable without a game.
 *
 * <h2>Nothing here is fatal</h2>
 * A datapack registry normally aborts the world load over one unreadable entry, and
 * {@link GrainDefinition#CODEC} deliberately steps around that: a definition that cannot be read
 * decodes into an inert one carrying the reason, which is logged here and skipped.
 *
 * <p>The argument for strictness is that terrain data is different — that a grain missing at
 * generation time bakes a world without it, and no later fix can put the ore back. That argument is
 * false <i>here</i>, and false for the mod's central reason: natural blocks store nothing. Worldgen
 * never consults the roster; it places {@code natural_stone} and stops. Composition is derived from
 * position and salt at the moment it is needed, so a grain defined tomorrow is simply there
 * tomorrow, throughout a world generated long before. Refusing to open the world would buy nothing
 * and cost the player their evening.
 */
@EventBusSubscriber(modid = Granularity.MODID)
public final class GrainRegistry {

    /**
     * {@code granularity:grain}, which is what puts definitions at
     * {@code data/<namespace>/granularity/grain/<name>.json} — a datapack registry's folder is its
     * own namespace and path.
     */
    public static final ResourceKey<Registry<GrainDefinition>> KEY = ResourceKey.createRegistryKey(
            ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "grain"));

    private GrainRegistry() {
    }

    /** Called from the mod constructor: declaring a registry is a mod-bus event, not a game one. */
    public static void register(DataPackRegistryEvent.NewRegistry event) {
        // The same codec for disk and for the wire. It resolves an absent tint on decode, so the
        // colour is averaged once on the server and sent, rather than derived twice and hoped over —
        // see GrainDefinition.
        event.dataPackRegistry(KEY, GrainDefinition.CODEC, GrainDefinition.CODEC);
    }

    /**
     * The moment both halves of the roster are knowable.
     *
     * <p>Tags are what {@link ConventionalGrains} reads, and they bind later than the registry does —
     * so this, and not server start, is the earliest point at which the full set can be worked out.
     * It fires on both sides: on the server when datapacks finish loading, on the client when the
     * server's tags arrive. Still before any chunk, which is the requirement.
     */
    @SubscribeEvent
    public static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        mirror(event.getRegistryAccess(), "tags loaded");
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Belt and braces. If tags have already bound, this is a no-op; if the event above somehow
        // has not fired, the world still gets its written grains before it generates anything.
        mirror(event.getServer().registryAccess(), "world load");
    }

    /**
     * Copies the registry, plus whatever the conventional tags imply, into the roster.
     *
     * <p>Called from several points on purpose — tags loading, server start, client login — because
     * each is the earliest moment on some path and none is the earliest on all of them. Calling it
     * more than once is free: {@link Grains#applyDataGrains} recognises an unchanged batch and leaves
     * the roster exactly as it is, rather than rebuilding tables that chunk meshing is reading.
     */
    public static void mirror(RegistryAccess access, String occasion) {
        Registry<GrainDefinition> registry = access.registry(KEY).orElse(null);
        if (registry == null) {
            // Not an error worth shouting about: a connection that predates the registry simply has
            // no data grains, and the roster the code registered is a complete, working roster.
            Granularity.LOGGER.debug("No grain registry present at {}.", occasion);
            return;
        }

        List<GrainSpec> specs = new ArrayList<>(registry.size());
        int unreadable = 0;
        for (Map.Entry<ResourceKey<GrainDefinition>, GrainDefinition> entry : registry.entrySet()) {
            GrainDefinition definition = entry.getValue();
            if (!definition.isUsable()) {
                unreadable++;
                Granularity.LOGGER.error("Grain {} could not be read and was skipped: {}",
                        entry.getKey().location(), definition.problem().orElseThrow());
                continue;
            }
            // The entry's own key is the grain's name, so a pack cannot forget to namespace it.
            specs.add(definition.toSpec(entry.getKey().location().toString()));
        }
        if (unreadable > 0) {
            Granularity.LOGGER.error("{} grain definition(s) were skipped. Fix them and restart; "
                    + "natural rock is derived rather than stored, so grains added later appear in "
                    + "chunks that already exist.", unreadable);
        }

        // Inferred grains join the same batch rather than making a second call, because
        // applyDataGrains replaces the whole data set — two calls would have each wiping the other's
        // work. Adoption runs last and is told what everything else has already claimed, so an
        // explicit definition always beats an inferred one, which is a pack's way of overruling it.
        Set<String> claimedItems = new HashSet<>();
        Set<String> claimedNames = new HashSet<>();
        for (com.tarosie.granularity.core.Grain grain : Grains.codeGrains()) {
            claimedItems.add(grain.itemId());
            claimedNames.add(grain.name());
        }
        for (GrainSpec spec : specs) {
            claimedItems.add(spec.itemId());
            claimedNames.add(spec.name());
        }
        int written = specs.size();
        specs.addAll(ConventionalGrains.adopt(claimedItems, claimedNames));

        List<String> problems = Grains.applyDataGrains(specs);
        problems.forEach(Granularity.LOGGER::warn);

        int accepted = specs.size() - problems.size();
        if (accepted > 0) {
            Granularity.LOGGER.info(
                    "{} grain(s) at {} ({} written, {} adopted from c: tags); the roster holds {}.",
                    accepted, occasion, written, specs.size() - written, Grains.all().size());
        } else {
            Granularity.LOGGER.debug("No grain definitions at {}.", occasion);
        }
    }
}
