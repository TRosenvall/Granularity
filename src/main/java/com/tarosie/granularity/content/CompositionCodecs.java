package com.tarosie.granularity.content;

import com.mojang.serialization.Codec;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.Grains;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Serialisation for a stored composition.
 *
 * <p>Design §4 is emphatic that natural blocks store nothing — their composition is derived. This is
 * for the other kind: <b>crafted</b> blocks, which §2 says are "the *only* blocks carrying real
 * data". A cobblestone made from nine particular chunks has to remember which nine, because nothing
 * about its position implies them.
 *
 * <p>Nine names, in NBT and on the wire alike. That is affordable precisely because crafted blocks
 * are sparse against world stone — the thing that would not have been affordable is storing this for
 * every block in a mountain.
 */
public final class CompositionCodecs {

    /**
     * The nine slots as <b>names</b>, which is what goes to disk.
     *
     * <p>A grain's numeric id is its position in the roster, and a position is only meaningful next
     * to the roster that produced it. Install a mod that adds a stone, and every id after it means a
     * different material; remove one, and a saved block reads as its neighbour. Vanilla has the same
     * problem and the same answer — a chunk palette stores {@code minecraft:stone}, never an index —
     * so this stores {@code granularity:slate} and resolves it on load.
     *
     * <p>Names go over the network too, for the reason {@link #STREAM_CODEC} gives.
     *
     * <p>A name that no longer resolves — the mod that defined it is gone — becomes the default
     * stone rather than failing the whole block. Losing one slot of nine is a much smaller harm than
     * a crafted block refusing to load at all.
     */
    public static final Codec<Composition> CODEC =
            Codec.either(Codec.STRING.listOf(), Codec.INT.listOf()).comapFlatMap(
                    either -> either.map(CompositionCodecs::fromNames, CompositionCodecs::fromIds),
                    composition -> com.mojang.datafixers.util.Either.left(names(composition)));

    private static com.mojang.serialization.DataResult<Composition> fromNames(List<String> names) {
        if (names.size() != Composition.SLOTS) {
            return com.mojang.serialization.DataResult.error(
                    () -> "Composition needs " + Composition.SLOTS + " slots, got " + names.size());
        }
        int[] array = new int[Composition.SLOTS];
        for (int i = 0; i < Composition.SLOTS; i++) {
            com.tarosie.granularity.core.Grain grain = Grains.find(names.get(i));
            array[i] = grain == null ? Grains.ANDESITE.id() : grain.id();
        }
        return com.mojang.serialization.DataResult.success(Composition.of(array));
    }

    /** The shape this used to be written in: nine positions into the roster. */
    private static com.mojang.serialization.DataResult<Composition> fromIds(List<Integer> ids) {
        if (ids.size() != Composition.SLOTS) {
            return com.mojang.serialization.DataResult.error(
                    () -> "Composition needs " + Composition.SLOTS + " slots, got " + ids.size());
        }
        int[] array = new int[Composition.SLOTS];
        for (int i = 0; i < Composition.SLOTS; i++) {
            int id = ids.get(i);
            array[i] = id < 0 || id >= Grains.count() ? Grains.ANDESITE.id() : id;
        }
        return com.mojang.serialization.DataResult.success(Composition.of(array));
    }

    /** The nine slots as namespaced names, in slot order. */
    public static List<String> names(Composition composition) {
        List<String> out = new ArrayList<>(Composition.SLOTS);
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            out.add(composition.grainAt(slot).name());
        }
        return out;
    }

    /** The inverse, tolerant of names the current roster does not know. */
    public static Composition byNames(List<String> names) {
        return fromNames(names).result().orElse(Composition.uniform(Grains.ANDESITE.id()));
    }

    /** NBT: nine strings under {@code key}, or the old int array, which is read and never written. */
    public static void save(net.minecraft.nbt.CompoundTag tag, String key, Composition composition) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (String name : names(composition)) {
            list.add(net.minecraft.nbt.StringTag.valueOf(name));
        }
        tag.put(key, list);
    }

    /** Reads either shape, or null when the key is absent or unusable. */
    @org.jetbrains.annotations.Nullable
    public static Composition load(net.minecraft.nbt.CompoundTag tag, String key) {
        if (tag.contains(key, net.minecraft.nbt.Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag list = tag.getList(key, net.minecraft.nbt.Tag.TAG_STRING);
            if (list.size() != Composition.SLOTS) {
                return null;
            }
            List<String> names = new ArrayList<>(Composition.SLOTS);
            for (int i = 0; i < list.size(); i++) {
                names.add(list.getString(i));
            }
            return byNames(names);
        }
        if (tag.contains(key, net.minecraft.nbt.Tag.TAG_INT_ARRAY)) {
            int[] ids = tag.getIntArray(key);
            return ids.length == Composition.SLOTS ? Composition.of(ids) : null;
        }
        return null;
    }

    /**
     * Names on the wire as well, deliberately.
     *
     * <p>This used to send ids, on the reasoning that client and server run the same roster within a
     * session, so an index is unambiguous and costs a byte where a name costs twenty. That reasoning
     * holds only while <b>the roster is fixed at compile time</b>. Once grains can come from a
     * datapack, the server's roster is a property of the world being played and the client's is a
     * property of the client — a client that joins with a different set of data grains, or joins
     * before receiving them, disagrees about what id 34 means. Nothing detects that. Every crafted
     * block in the inventory and in the hand simply renders as the wrong material, and a player who
     * then places one has stored the wrong thing.
     *
     * <p>The alternative was to keep ids and sync the roster on login. That works, but it is a
     * protocol to get right and a failure mode to think about at every future change; names are
     * <b>unconditionally</b> correct, and cost about 180 bytes on an item stack that only crosses the
     * wire when a slot changes. Buying the entire class of desync bug out of existence for that is a
     * trade worth making, and it is the same reason the disk format is names.
     *
     * <p>A dedupe-and-index scheme would recover about half those bytes — a composition rarely holds
     * nine distinct grains — and it is deliberately not here. Halving a cost this small is not worth
     * a second encoding to keep correct.
     *
     * <p>An unknown name decodes to the default stone rather than dropping the packet, matching
     * {@link #CODEC}: a client missing one grain should render that slot wrong, not disconnect.
     */
    public static final StreamCodec<ByteBuf, Composition> STREAM_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(Composition.SLOTS)
                    .apply(ByteBufCodecs.STRING_UTF8)
                    .map(CompositionCodecs::byNames, CompositionCodecs::names);

    private CompositionCodecs() {
    }
}
