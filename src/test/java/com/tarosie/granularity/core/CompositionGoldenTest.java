package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the world the composition function generates.
 *
 * <p>Phase 0's routines are pinned by the prototype's {@code golden_vectors.json}, but everything in
 * this package above {@link Rng} is new — the prototype's terrain came from test fixtures the
 * porting spec explicitly leaves free to rewrite. So the noise layers, the colour field and the
 * composition function have no upstream reference to check against, and without a pin they would
 * be free to drift: a refactor that changes an octave's salt or a loop's order regenerates every
 * world silently, and nothing would fail.
 *
 * <p>This file plays the same role the prototype's vectors play, with the same rule attached:
 * <b>a diff here is a world-breaking change, not a test to update.</b> Existing saves would
 * generate differently across the change. Regenerate deliberately with
 * {@code ./gradlew test -PregenerateCompositionGolden}, and only when breaking worlds is the
 * intent.
 *
 * <p>The positions are chosen to cover what would otherwise fail quietly: both purity extremes,
 * negative coordinates, a region border, and the bedrock datum where jitter must reach exactly
 * zero.
 */
class CompositionGoldenTest {

    private static final long SALT_SEED = 24301L;

    /** Positions to pin: {x, y, z}. */
    private static final int[][] POSITIONS = {
            {0, 0, 0},
            {0, 64, 0},
            {0, -64, 0},
            {1, 0, 1},
            {-1, 0, -1},
            {127, 32, -415},
            {-2048, -32, 4096},
            {30_000, 12, -30_000},
            {512, -63, 512},
            {-777, 63, 1313},
            {384, 0, 384},
            {385, 0, 384},
            {1152, -16, -1152},
            {64, -64, 64},
            {-64, -64, -64},
            {9001, 100, 9001},
            {-12_345, -48, 6789},
            {256, 20, 256},
            {8, -8, 8},
            {2047, 0, -2047},
    };

    /** Horizontal positions to pin the colour field at: {x, z}. */
    private static final int[][] COLOUR_POSITIONS = {
            {0, 0}, {192, 0}, {384, 0}, {576, 0}, {768, 0},
            {0, 384}, {-384, 0}, {-384, -384}, {1536, 1536}, {-2048, 4096},
            {100_000, -100_000}, {7, 13}, {-7, -13}, {4096, 0}, {0, -4096},
    };

    @Test
    @DisplayName("compositions and colour field match the pinned world")
    void worldMatchesTheGoldenFile() throws IOException {
        JsonObject actual = generate();

        if (Boolean.getBoolean("granularity.regenerateCompositionGolden")) {
            Path path = goldenPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path,
                    new GsonBuilder().setPrettyPrinting().create().toJson(actual) + "\n",
                    StandardCharsets.UTF_8);
            fail("Regenerated " + path + ". Re-run without -PregenerateCompositionGolden to verify, "
                    + "and review the diff as a world-breaking change.");
        }

        JsonObject expected = readGolden();

        assertEquals(expected.get("salt_seed"), actual.get("salt_seed"));
        assertEquals(expected.get("region_size"), actual.get("region_size"),
                "ColourField.REGION_SIZE changed — every region boundary in every world moves");
        assertEquals(expected.get("grain_count"), actual.get("grain_count"),
                "the grain roster changed size — ids shift and every composition moves");

        JsonArray expectedColours = expected.getAsJsonArray("stone_field");
        JsonArray actualColours = actual.getAsJsonArray("stone_field");
        assertEquals(expectedColours.size(), actualColours.size());
        for (int i = 0; i < expectedColours.size(); i++) {
            assertEquals(expectedColours.get(i).toString(), actualColours.get(i).toString(),
                    "stone field drifted at entry " + i);
        }

        JsonArray expectedCompositions = expected.getAsJsonArray("compositions");
        JsonArray actualCompositions = actual.getAsJsonArray("compositions");
        assertEquals(expectedCompositions.size(), actualCompositions.size());
        for (int i = 0; i < expectedCompositions.size(); i++) {
            assertEquals(expectedCompositions.get(i).toString(), actualCompositions.get(i).toString(),
                    "composition drifted at entry " + i);
        }
    }

    private static JsonObject generate() {
        long salt = WorldSalt.derive(SALT_SEED).value();

        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Pinned output of the composition function. A diff is a "
                + "world-breaking change, not a test to update. Regenerate with "
                + "./gradlew test -PregenerateCompositionGolden.");
        root.addProperty("salt_seed", SALT_SEED);
        root.addProperty("salt", Long.toHexString(salt));
        root.addProperty("region_size", ColourField.REGION_SIZE);
        root.addProperty("grain_count", Grains.count());

        JsonArray colours = new JsonArray();
        for (int[] pos : COLOUR_POSITIONS) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", pos[0]);
            entry.addProperty("z", pos[1]);
            Grain stone = ColourField.sample(pos[0], pos[1], salt);
            entry.addProperty("stone", stone.name());
            entry.addProperty("family", stone.family().name());
            colours.add(entry);
        }
        root.add("stone_field", colours);

        JsonArray compositions = new JsonArray();
        for (int[] pos : POSITIONS) {
            Composition c = CompositionFunction.stone(pos[0], pos[1], pos[2], salt);
            JsonObject entry = new JsonObject();
            entry.addProperty("x", pos[0]);
            entry.addProperty("y", pos[1]);
            entry.addProperty("z", pos[2]);
            JsonArray slots = new JsonArray();
            for (int id : c.toArray()) {
                slots.add(id);
            }
            entry.add("slots", slots);
            entry.addProperty("describe", c.toString());
            compositions.add(entry);
        }
        root.add("compositions", compositions);

        return root;
    }

    private static Path goldenPath() {
        String configured = System.getProperty("granularity.compositionGolden");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("src", "test", "resources", "granularity", "composition_golden.json");
    }

    private static JsonObject readGolden() throws IOException {
        Path path = goldenPath();
        if (!Files.exists(path)) {
            fail("No golden file at " + path + ". Generate it deliberately with "
                    + "./gradlew test -PregenerateCompositionGolden — a missing file must not "
                    + "silently re-pin the world.");
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.getAsJsonObject();
        }
    }
}
