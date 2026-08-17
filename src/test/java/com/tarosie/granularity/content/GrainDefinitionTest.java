package com.tarosie.granularity.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.tarosie.granularity.core.BedrockType;
import com.tarosie.granularity.core.GrainClass;
import com.tarosie.granularity.core.GrainSpec;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The datapack format for a grain.
 *
 * <p>Tested apart from the loader that reads these off disk, which is why
 * {@link GrainDefinition} does not extend or import anything to do with resource packs — a format is
 * worth being able to check without starting a game.
 */
class GrainDefinitionTest {

    private static DataResult<GrainDefinition> parse(String json) {
        return GrainDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static GrainDefinition ok(String json) {
        DataResult<GrainDefinition> result = parse(json);
        return result.result().orElseThrow(() ->
                new AssertionError("expected to parse, got: " + result.error().orElseThrow().message()));
    }

    @Test
    @DisplayName("a full definition becomes a spec the roster can take")
    void readsAFullDefinition() {
        GrainSpec spec = ok("""
                {
                  "class": "gem",
                  "item": "mymod:ruby",
                  "families": ["metamorphic"],
                  "tint": "#9B111E"
                }
                """).toSpec("mymod:ruby");

        assertEquals("mymod:ruby", spec.name());
        assertEquals(GrainClass.GEM, spec.clazz());
        assertEquals("mymod:ruby", spec.itemId());
        assertEquals(0x9B111E, spec.tint());
        assertEquals(Set.of(BedrockType.METAMORPHIC), spec.families());
    }

    @Test
    @DisplayName("classes are written the way an author would write them")
    void multiWordClassesAreLowercase() {
        assertEquals(GrainClass.PRECIOUS_ORE,
                ok("{\"class\": \"precious_ore\", \"item\": \"minecraft:raw_gold\"}").clazz());
    }

    @Test
    @DisplayName("an omitted tint is resolved from the item's own texture, then and there")
    void tintIsResolvedOnDecode() {
        int tint = ok("{\"class\": \"rock\", \"item\": \"granularity:granite_chunk\"}").tint();

        assertTrue((tint >> 16 & 0xFF) > 20, "a real colour, not black: " + Integer.toHexString(tint));
        // Resolved here rather than left for each side to work out, because this codec is also the
        // network codec — see GrainDefinition. Encoding therefore round-trips a concrete colour even
        // when the file that produced it named none.
        JsonElement written = GrainDefinition.CODEC
                .encodeStart(JsonOps.INSTANCE, ok("{\"class\": \"rock\", \"item\": \"granularity:granite_chunk\"}"))
                .result().orElseThrow();
        assertEquals(tint, written.getAsJsonObject().get("tint").getAsInt(),
                "the resolved colour is what travels, so both sides cannot disagree about it");
    }

    @Test
    @DisplayName("an item with no readable texture and no tint is skipped, not guessed at")
    void unreadableTextureWithNoTintIsSkipped() {
        GrainDefinition read = ok("{\"class\": \"rock\", \"item\": \"nosuchmod:nothing\"}");
        assertFalse(read.isUsable(),
                "a colour invented here would differ between a client that has the item and a "
                        + "server that does not");
        assertTrue(read.problem().orElseThrow().contains("explicit tint"),
                "the message should tell the author what to do instead");
    }

    @Test
    @DisplayName("a bare hex number is accepted as readily as a plain integer")
    void tintMayBeWrittenEitherWay() {
        assertEquals(0x9B111E,
                ok("{\"class\": \"gem\", \"item\": \"m:r\", \"tint\": \"9B111E\"}").tint());
        assertEquals(0x9B111E,
                ok("{\"class\": \"gem\", \"item\": \"m:r\", \"tint\": 10162462}").tint());
    }

    @Test
    @DisplayName("omitting families means every family, which is right for soil and wrong for gems")
    void familiesDefaultToAll() {
        GrainSpec spec = ok("{\"class\": \"clay\", \"item\": \"minecraft:clay_ball\"}")
                .toSpec("mymod:bentonite");
        assertTrue(spec.families().isEmpty(), "empty is how Grain spells 'anywhere'");
    }

    @Test
    @DisplayName("a misspelled class is skipped rather than refused, and the message lists the real ones")
    void unknownClassIsSkippedNotFatal() {
        // Decoding cannot fail: a datapack registry aborts the whole world load on a parse error, and
        // that cure is worse than the disease here, because natural rock stores nothing — a grain
        // fixed tomorrow appears in chunks generated today. See GrainDefinition.CODEC.
        GrainDefinition read = ok("{\"class\": \"mineral\", \"item\": \"mymod:ruby\"}");

        assertFalse(read.isUsable(), "silently becoming a real grain would put it in the wrong places");
        assertTrue(read.problem().orElseThrow().contains("precious_ore"),
                "should list what is available, got: " + read.problem().orElseThrow());
    }

    @Test
    @DisplayName("a colour that is not a colour says so rather than becoming black")
    void malformedTintIsSkipped() {
        GrainDefinition read = ok("{\"class\": \"gem\", \"item\": \"m:r\", \"tint\": \"ruby red\"}");
        assertFalse(read.isUsable());
        assertTrue(read.problem().orElseThrow().contains("9B111E"),
                "the message should show the author what one looks like");
    }

    @Test
    @DisplayName("the two required fields are required")
    void itemAndClassAreMandatory() {
        assertFalse(ok("{\"item\": \"mymod:ruby\"}").isUsable(), "class is required");
        assertFalse(ok("{\"class\": \"gem\"}").isUsable(), "item is required");
        assertFalse(ok("\"not even an object\"").isUsable(), "and the file must be a definition");
    }

    @Test
    @DisplayName("a definition survives the round trip that syncing it to the client is")
    void roundTripsAcrossTheWire() {
        // The same codec writes the network form, so what the client decodes must equal what the
        // server holds — including a tint the file never named.
        GrainDefinition original = ok("""
                {"class": "gem", "item": "mymod:ruby", "families": ["metamorphic", "igneous"],
                 "tint": "#9B111E"}
                """);
        JsonElement sent = GrainDefinition.CODEC.encodeStart(JsonOps.INSTANCE, original)
                .result().orElseThrow();

        assertEquals(original, GrainDefinition.CODEC.parse(JsonOps.INSTANCE, sent).result().orElseThrow());
    }

    @Test
    @DisplayName("an unreadable definition carries its reason to the client rather than arriving as air")
    void skippedDefinitionsTravelWithTheirReason() {
        // It is shaped as air so that nothing reading past isUsable() could do harm — but air that
        // arrived without its reason would look like a legitimate grain the client should register.
        GrainDefinition broken = ok("{\"class\": \"mineral\", \"item\": \"mymod:ruby\"}");
        JsonElement sent = GrainDefinition.CODEC.encodeStart(JsonOps.INSTANCE, broken)
                .result().orElseThrow();
        GrainDefinition received = GrainDefinition.CODEC.parse(JsonOps.INSTANCE, sent)
                .result().orElseThrow();

        assertFalse(received.isUsable(), "the client must skip exactly what the server skipped");
        assertEquals(broken.problem(), received.problem(), "and be able to say why");
    }
}
