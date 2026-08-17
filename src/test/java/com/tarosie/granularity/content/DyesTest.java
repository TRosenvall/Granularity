package com.tarosie.granularity.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dye is a mortar colouring, and mortar has faces.
 *
 * <p>The interesting cases are all about a face being its own thing: painting one leaves the others
 * alone, a block that is one colour all over can still say so, and a block dyed before faces existed
 * has to come back as the colour it was rather than as nothing.
 */
class DyesTest {

    private static final int RED = 0xB02E26;

    private static final int BLUE = 0x3C44AA;

    @Test
    @DisplayName("painting one face leaves the rest showing the average")
    void oneFaceAtATime() {
        Dyes dyes = Dyes.NONE.with(RED, Direction.NORTH);
        assertEquals(RED, dyes.on(Direction.NORTH));
        assertNull(dyes.on(Direction.SOUTH), "the other five faces are still undyed");
        assertNull(dyes.on(Direction.UP));
    }

    @Test
    @DisplayName("repainting a face the colour it already is reports that nothing happened")
    void repaintingTheSameColourChangesNothing() {
        Dyes dyes = Dyes.NONE.with(RED, Direction.NORTH);
        assertNull(dyes.with(RED, Direction.NORTH),
                "null is how a caller knows not to consume the dye");
        assertEquals(BLUE, dyes.with(BLUE, Direction.NORTH).on(Direction.NORTH),
                "a different colour does replace it");
    }

    @Test
    @DisplayName("a block is only one colour when every face agrees")
    void uniformNeedsAllSix() {
        assertNull(Dyes.NONE.uniform(), "an undyed block has no colour of its own");
        Dyes partial = Dyes.NONE.with(RED, Direction.NORTH).with(RED, Direction.SOUTH);
        assertNull(partial.uniform(), "two red faces do not make a red block");

        assertEquals(RED, Dyes.everywhere(RED).uniform());
        assertNull(Dyes.everywhere(RED).with(BLUE, Direction.UP).uniform(),
                "five red and one blue is not a colour");
    }

    @Test
    @DisplayName("the six slots survive a round trip through the saved form")
    void arrayRoundTrip() {
        Dyes dyes = Dyes.NONE.with(RED, Direction.UP).with(BLUE, Direction.WEST);
        assertEquals(dyes, Dyes.of(dyes.toArray()));
        assertEquals(dyes, Dyes.fromList(dyes.toList()));
        assertEquals(Dyes.NONE, Dyes.of(Dyes.NONE.toArray()), "undyed stays undyed");
    }

    @Test
    @DisplayName("a block dyed before faces existed comes back that colour on every face")
    void migratesTheWholeBlockColour() {
        CompoundTag old = new CompoundTag();
        old.putInt("MatrixTint", RED);

        Dyes migrated = Dyes.load(old, "Dyes", "MatrixTint");
        assertEquals(RED, migrated.uniform(), "one colour for the block meant one colour everywhere");
        for (Direction face : Direction.values()) {
            assertEquals(RED, migrated.on(face));
        }
    }

    @Test
    @DisplayName("a per-face save is preferred over the colour it was migrated from")
    void perFaceWinsOverLegacy() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("MatrixTint", RED);
        Dyes.NONE.with(BLUE, Direction.EAST).save(tag, "Dyes");

        Dyes loaded = Dyes.load(tag, "Dyes", "MatrixTint");
        assertEquals(BLUE, loaded.on(Direction.EAST));
        assertNull(loaded.on(Direction.WEST),
                "the stale whole-block colour must not leak back onto the other faces");
    }

    @Test
    @DisplayName("an item dyed before faces existed decodes rather than losing its colour")
    void theItemComponentReadsBothShapes() {
        Tag legacy = IntTag.valueOf(RED);
        java.util.List<Integer> migrated = Dyes.CODEC
                .parse(NbtOps.INSTANCE, legacy)
                .getOrThrow(problem -> new AssertionError("old stacks must still decode: " + problem));
        assertEquals(RED, Dyes.fromList(migrated).uniform(),
                "the single colour covered the whole block, so it covers every face");

        Dyes dyes = Dyes.NONE.with(BLUE, Direction.DOWN);
        Tag written = Dyes.CODEC
                .encodeStart(NbtOps.INSTANCE, dyes.toList())
                .getOrThrow(problem -> new AssertionError(problem));
        java.util.List<Integer> read = Dyes.CODEC
                .parse(NbtOps.INSTANCE, written)
                .getOrThrow(problem -> new AssertionError(problem));
        assertEquals(dyes, Dyes.fromList(read), "and a per-face stack round-trips unchanged");
    }

    @Test
    @DisplayName("an undyed block writes nothing at all")
    void undyedWritesNothing() {
        CompoundTag tag = new CompoundTag();
        Dyes.NONE.save(tag, "Dyes");
        assertFalse(tag.contains("Dyes"), "byte-identical to a block from before dye existed");
        assertTrue(Dyes.load(tag, "Dyes", "MatrixTint").isEmpty());
    }
}
