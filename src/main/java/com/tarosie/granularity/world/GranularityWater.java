package com.tarosie.granularity.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionFunction;
import com.tarosie.granularity.core.WaterDeviations;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Where a chunk's moved water is kept.
 *
 * <p>Design §6 names the mechanism: "per-chunk sparse maps (data attachments; position → delta) that
 * decay back toward baseline so entries expire. Most of the world stores nothing." This is that,
 * attached to the chunk so it saves and loads with the terrain it describes.
 *
 * <h2>Why a chunk attachment and not a block entity</h2>
 * A block entity per wet block is exactly what §4 forbids at world-stone scale — it is per-block
 * storage for something a function already knows almost everywhere. The attachment holds only the
 * exceptions, so an untouched chunk carries an empty map and costs a single tag on disk.
 *
 * <p>This is also the one place attachments <i>work</i> for this mod. Composite blocks cannot use
 * them (see {@code docs/CRAFTED_BLOCKS.md} on why composition rides in a block entity instead), but
 * a deviation map is per-chunk rather than per-block, is never read by the client, and never has to
 * survive a block being pushed or falling.
 */
public final class GranularityWater {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Granularity.MODID);

    /** One stored deviation: a packed block position and how far it is from its baseline. */
    private record Entry(long position, int delta) {

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("pos").forGetter(Entry::position),
                Codec.INT.fieldOf("delta").forGetter(Entry::delta)
        ).apply(instance, Entry::new));
    }

    /**
     * A list of entries rather than a map keyed by position, because NBT map keys are strings and a
     * packed long round-tripping through decimal text is a cost paid on every chunk save for nothing.
     */
    private static final Codec<WaterDeviations> DEVIATIONS_CODEC = Entry.CODEC.listOf().xmap(
            entries -> {
                WaterDeviations deviations = new WaterDeviations();
                Map<Long, Integer> stored = new HashMap<>();
                entries.forEach(entry -> stored.put(entry.position(), entry.delta()));
                deviations.load(stored);
                return deviations;
            },
            deviations -> deviations.entries().entrySet().stream()
                    .map(entry -> new Entry(entry.getKey(), entry.getValue()))
                    .toList());

    /** The chunk's deviation map. Empty for any chunk nothing has disturbed. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<WaterDeviations>> DEVIATIONS =
            ATTACHMENTS.register("water_deviations", () -> AttachmentType
                    .builder(WaterDeviations::new)
                    .serialize(DEVIATIONS_CODEC)
                    .build());

    private GranularityWater() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    /**
     * What a block actually holds right now: its derived baseline, moved by whatever has been stored
     * against it.
     *
     * <p>Anything asking "how much water is in this rock" must come through here rather than reading
     * the composition directly. The composition is the equilibrium answer, and for a block that
     * something has happened to it is the <i>wrong</i> answer — water that migrated in would be
     * invisible, and breaking the block would release the amount it would have held if nobody had
     * touched it.
     */
    public static int waterAt(ServerLevel level, BlockPos pos, long salt) {
        Composition composition = CompositionFunction.stone(
                pos.getX(), pos.getY(), pos.getZ(), salt);
        LevelChunk chunk = level.getChunkAt(pos);
        if (!chunk.hasData(DEVIATIONS)) {
            return composition.water();
        }
        return chunk.getData(DEVIATIONS).waterAt(
                pos.asLong(), composition.water(), composition.porosity());
    }

    /**
     * Forget any deviation stored against a position, because the block it described is gone.
     *
     * <p>Left behind, the entry would describe water in a block that no longer has pores to hold it,
     * and would sit in the chunk's map until it decayed for no reason at all.
     */
    public static void forget(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        if (!chunk.hasData(DEVIATIONS)) {
            return;
        }
        WaterDeviations deviations = chunk.getData(DEVIATIONS);
        if (deviations.remove(pos.asLong())) {
            chunk.setUnsaved(true);
        }
    }
}
