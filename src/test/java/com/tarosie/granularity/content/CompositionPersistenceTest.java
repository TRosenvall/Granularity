package com.tarosie.granularity.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.Grains;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A saved composition must mean the same thing after the roster changes.
 *
 * <p>Slots used to go to disk as positions in {@link Grains} — nine small integers. That is only
 * meaningful beside the roster that wrote it: install a mod that adds a stone and every id after it
 * denotes a different material, so a slate cobblestone in a chest quietly becomes gneiss. Vanilla has
 * the same problem and the same answer, which is why a chunk palette stores {@code minecraft:stone}
 * rather than an index.
 */
class CompositionPersistenceTest {

    private static Composition mixed() {
        int[] ids = new int[Composition.SLOTS];
        ids[0] = Grains.SLATE.id();
        ids[1] = Grains.GRANITE.id();
        ids[2] = Grains.GOLD.id();
        ids[3] = Grains.TUFF.id();
        ids[4] = Grains.CALCITE.id();
        for (int i = 5; i < Composition.SLOTS; i++) {
            ids[i] = Grains.ANDESITE.id();
        }
        return Composition.of(ids);
    }

    @Test
    @DisplayName("a composition round-trips through NBT as names")
    void roundTripsThroughNbt() {
        Composition original = mixed();
        CompoundTag tag = new CompoundTag();
        CompositionCodecs.save(tag, "Composition", original);

        assertEquals(net.minecraft.nbt.Tag.TAG_LIST, tag.get("Composition").getId(),
                "slots go to disk as a list of names, not as an int array");
        assertEquals(original, CompositionCodecs.load(tag, "Composition"));
    }

    @Test
    @DisplayName("the names written are namespaced, so two mods may both have a slate")
    void namesAreNamespaced() {
        for (String name : CompositionCodecs.names(mixed())) {
            assertTrue(name.startsWith("granularity:"), name + " should carry its namespace");
        }
    }

    @Test
    @DisplayName("a slot naming a grain that is no longer installed falls back rather than failing")
    void unknownGrainsDoNotSinkTheBlock() {
        List<String> names = new ArrayList<>(CompositionCodecs.names(mixed()));
        names.set(2, "somemod:unobtainium");

        Composition read = CompositionCodecs.byNames(names);
        assertNotNull(read);
        assertEquals(Grains.SLATE, read.grainAt(0), "the slots around it are untouched");
        assertEquals(Grains.ANDESITE, read.grainAt(2),
                "the missing grain becomes the default stone; losing one slot beats losing the block");
    }

    @Test
    @DisplayName("a block saved as ids still loads, and is not reinterpreted")
    void theOldIntArrayFormStillReads() {
        Composition original = mixed();
        CompoundTag old = new CompoundTag();
        old.putIntArray("Composition", original.toArray());

        assertEquals(original, CompositionCodecs.load(old, "Composition"));
    }

    @Test
    @DisplayName("a composition round-trips over the network as names too")
    void roundTripsOverTheWire() {
        Composition original = mixed();
        io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        CompositionCodecs.STREAM_CODEC.encode(buffer, original);

        assertEquals(original, CompositionCodecs.STREAM_CODEC.decode(buffer));
        assertEquals(0, buffer.readableBytes(), "the decoder must consume exactly what was written");
    }

    @Test
    @DisplayName("a name the client has never heard of renders wrong rather than dropping the packet")
    void unknownGrainsOnTheWireDoNotKillTheConnection() {
        // What a client joining with a different set of datapack grains would actually receive. It
        // used to receive an id instead, which would have silently denoted some other real material —
        // the failure mode this codec exists to remove.
        List<String> names = new ArrayList<>(CompositionCodecs.names(mixed()));
        names.set(4, "somemod:unobtainium");

        io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        net.minecraft.network.codec.ByteBufCodecs
                .<io.netty.buffer.ByteBuf, String>list(Composition.SLOTS)
                .apply(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8)
                .encode(buffer, names);

        Composition read = CompositionCodecs.STREAM_CODEC.decode(buffer);
        assertEquals(Grains.SLATE, read.grainAt(0), "the slots around it arrive intact");
        assertEquals(Grains.ANDESITE, read.grainAt(4));
    }

    @Test
    @DisplayName("an absent or malformed key reads as nothing rather than as a default block")
    void missingKeyIsNull() {
        assertNull(CompositionCodecs.load(new CompoundTag(), "Composition"));

        CompoundTag short_ = new CompoundTag();
        short_.putIntArray("Composition", new int[] {1, 2, 3});
        assertNull(CompositionCodecs.load(short_, "Composition"),
                "a wrong-length payload must not be silently padded");
    }
}
