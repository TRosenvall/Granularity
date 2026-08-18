package com.tarosie.granularity.content;

import com.tarosie.granularity.Granularity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * The overlay registry, and the one overlay the mod ships with.
 *
 * <p>Synced, so client and server agree on ids; every overlay a block carries has to be sent to the
 * client, because the client is what draws it.
 */
public final class GranularityOverlays {

    public static final ResourceKey<Registry<Overlay>> REGISTRY_KEY = ResourceKey.createRegistryKey(
            ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "overlay"));

    public static final DeferredRegister<Overlay> OVERLAYS =
            DeferredRegister.create(REGISTRY_KEY, Granularity.MODID);

    public static final Registry<Overlay> REGISTRY = OVERLAYS.makeRegistry(builder -> builder.sync(true));

    /**
     * Moss. The sprite was cut from vanilla's own mossy cobblestone by diffing it against plain
     * cobblestone, so it lines up with stone the way players expect.
     */
    public static final DeferredHolder<Overlay, Overlay> MOSS = OVERLAYS.register("moss",
            () -> new Overlay(ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "block/cobblestone_moss")));

    /**
     * Slime. The second overlay, and the one that proves the claim: adding it cost one registration,
     * one sprite and one line in the atlas — no model, no blockstate variant, no naming scheme, and
     * nothing in the renderer.
     *
     * <p>It was blood first, as a placeholder applied with redstone. Blood is a better idea attached
     * to something that bleeds — taking damage on a block, rather than rubbing dust on it — so it is
     * out until there is a source worth having. The splatter sprite it used is still in the repo, and
     * this one is that shape wearing vanilla slime's palette; see {@code tools/recolour_overlay.py}.
     */
    public static final DeferredHolder<Overlay, Overlay> SLIME = OVERLAYS.register("slime",
            () -> new Overlay(ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "block/cobblestone_slime")));

    /**
     * Cracks, struck into the surface with a hammer.
     *
     * <p>The first overlay that is <b>damage</b> rather than growth, and the first generated rather
     * than cut from vanilla art. That was the point: vanilla's five {@code cracked_*} textures each
     * trace the mortar lines of the bricks they were drawn for, so lifting one onto a Mottled or
     * Banded face draws ghost bricks — mortar in the shape of cracks, on a surface that has none. A
     * network generated from a cell field aligns with nothing and so is at home on all twenty styles.
     * See {@code tools/gen_cracks.py}.
     *
     * <p>Generated <b>once</b>, into one sprite, rather than per block. A pattern computed from
     * position would cost a field evaluation per block and have to agree between client and server —
     * a whole class of desync in exchange for variety nobody asked for.
     *
     * <p>Untinted, like every overlay, which is exactly right here: a crack is a shadow in a fissure,
     * and a shadow is dark on slate and dark on marble alike.
     */
    public static final DeferredHolder<Overlay, Overlay> CRACKED = OVERLAYS.register("cracked",
            () -> Overlay.tinted(ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "block/cobblestone_cracked")));

    private GranularityOverlays() {
    }

    public static void register(IEventBus modEventBus) {
        OVERLAYS.register(modEventBus);
    }

    /** The registry id of an overlay, used for both saving and naming. */
    public static ResourceLocation idOf(Overlay overlay) {
        return REGISTRY.getKey(overlay);
    }

    private static final String ID_KEY = "Id";

    private static final String FACES_KEY = "Faces";

    /**
     * Reads a saved coating.
     *
     * <p>Stored as ids rather than raw registry numbers so a world survives another mod being added
     * or removed — an id that no longer resolves is dropped rather than silently becoming a
     * different overlay.
     *
     * <p>An older save holds a plain list of ids, from back when an overlay covered a whole block and
     * there was nothing to say about faces. That reads as all six, which is what it meant.
     */
    public static Coating load(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return Coating.NONE;
        }
        Map<Overlay, Integer> faces = new LinkedHashMap<>();
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Overlay overlay = byId(entry.getString(ID_KEY));
            int mask = entry.getInt(FACES_KEY) & Coating.ALL_FACES;
            if (overlay != null && mask != 0) {
                faces.put(overlay, mask);
            }
        }
        if (faces.isEmpty()) {
            ListTag flat = tag.getList(key, Tag.TAG_STRING);
            for (int i = 0; i < flat.size(); i++) {
                Overlay overlay = byId(flat.getString(i));
                if (overlay != null) {
                    faces.put(overlay, Coating.ALL_FACES);
                }
            }
        }
        return new Coating(faces);
    }

    public static void save(CompoundTag tag, String key, Coating coating) {
        if (coating.isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (Map.Entry<Overlay, Integer> entry : coating.faces().entrySet()) {
            ResourceLocation id = idOf(entry.getKey());
            if (id == null || entry.getValue() == 0) {
                continue;
            }
            CompoundTag saved = new CompoundTag();
            saved.putString(ID_KEY, id.toString());
            saved.putInt(FACES_KEY, entry.getValue());
            list.add(saved);
        }
        if (!list.isEmpty()) {
            tag.put(key, list);
        }
    }

    @Nullable
    private static Overlay byId(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        return id == null ? null : REGISTRY.get(id);
    }

    /** The same thing keyed by id, for the item component. */
    public static Map<ResourceLocation, Integer> ids(Coating coating) {
        Map<ResourceLocation, Integer> ids = new LinkedHashMap<>();
        for (Map.Entry<Overlay, Integer> entry : coating.faces().entrySet()) {
            ResourceLocation id = idOf(entry.getKey());
            if (id != null && entry.getValue() != 0) {
                ids.put(id, entry.getValue());
            }
        }
        return ids;
    }

    public static Coating fromIds(Map<ResourceLocation, Integer> ids) {
        Map<Overlay, Integer> faces = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Integer> entry : ids.entrySet()) {
            Overlay overlay = REGISTRY.get(entry.getKey());
            int mask = entry.getValue() & Coating.ALL_FACES;
            if (overlay != null && mask != 0) {
                faces.put(overlay, mask);
            }
        }
        return new Coating(faces);
    }
}
