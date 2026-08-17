package com.tarosie.granularity.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A stonecutter is four materials, and every one of them has to reach the renderer.
 *
 * <p>It is the only block in the mod drawn from four different sources of colour at once — two
 * stones, timber and metal — and each is nothing but a tint index on a face in a generated model.
 * Lose one and the game does not complain: the face simply renders in whatever the neighbouring
 * index resolves to, or in bare greyscale, and looks merely like a wrong texture. That is the exact
 * failure mode {@code CLAUDE.md} is written about, so it is pinned by number here.
 *
 * <p>The indices are read from {@code CompositeBlockColour}, which is a client class — but they are
 * {@code static final int} compile-time constants, so javac inlines them and nothing is loaded at
 * runtime. Referencing them rather than restating them is what keeps this test and the colour handler
 * from drifting apart.
 */
class StonecutterModelTest {

    private static final int MATRIX = 0;
    private static final int SECOND_STONE = com.tarosie.granularity.client.CompositeBlockColour.UPPER_BASE;
    private static final int TIMBER = com.tarosie.granularity.client.CompositeBlockColour.WOOD_TINT;
    private static final int METAL = com.tarosie.granularity.client.CompositeBlockColour.METAL_TINT;

    private static JsonObject model() {
        try (InputStream in = StonecutterModelTest.class.getClassLoader().getResourceAsStream(
                "assets/granularity/models/block/stonecutter.json")) {
            if (in == null) {
                return fail("the stonecutter model is not on the classpath — run gen_shape_models.py");
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception failed) {
            return fail(failed);
        }
    }

    /** Every face in the model, as tint index -> the texture keys drawn at it. */
    private static Map<Integer, Set<String>> facesByTint() {
        Map<Integer, Set<String>> byTint = new LinkedHashMap<>();
        for (JsonElement element : model().getAsJsonArray("elements")) {
            JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
            for (String direction : faces.keySet()) {
                JsonObject face = faces.getAsJsonObject(direction);
                int tint = face.has("tintindex") ? face.get("tintindex").getAsInt() : -1;
                byTint.computeIfAbsent(tint, key -> new TreeSet<>())
                        .add(face.get("texture").getAsString());
            }
        }
        return byTint;
    }

    @Test
    @DisplayName("all four materials are drawn, and nothing else is")
    void fourMaterialsAndNoOthers() {
        Map<Integer, Set<String>> byTint = facesByTint();

        assertEquals(Set.of(MATRIX, SECOND_STONE, TIMBER, METAL), byTint.keySet(),
                "a stonecutter is drawn from exactly four tints: the stone below the rail, the stone "
                        + "above it, the timber frame and the saw. An untinted face (-1) means a "
                        + "sprite lost its index and will render in bare greyscale.");
    }

    @Test
    @DisplayName("each material draws its own sprite, so no mask is wired to the wrong tint")
    void eachMaterialHasItsOwnSprites() {
        Map<Integer, Set<String>> byTint = facesByTint();
        Set<String> seen = new TreeSet<>();
        for (Map.Entry<Integer, Set<String>> entry : byTint.entrySet()) {
            for (String texture : entry.getValue()) {
                assertTrue(seen.add(texture),
                        texture + " is drawn at two different tints — the alpha masks are a "
                                + "partition of one sprite, so sharing one means a section is "
                                + "painted twice and another not at all");
            }
        }
        // The bench's sides are where the two stones actually meet, so all three have to be there.
        assertTrue(byTint.get(MATRIX).contains("#stonecutter_side_lower"), "no lower stone on the sides");
        assertTrue(byTint.get(SECOND_STONE).contains("#stonecutter_side_upper"), "no upper stone on the sides");
        assertTrue(byTint.get(TIMBER).contains("#stonecutter_side_wood"), "no frame on the sides");
    }

    @Test
    @DisplayName("the three stacked copies are coincident, or the sections would tear apart")
    void theCopiesShareOneBox() {
        JsonArray elements = model().getAsJsonArray("elements");
        JsonObject first = elements.get(0).getAsJsonObject();
        for (int i = 1; i < 3; i++) {
            JsonObject copy = elements.get(i).getAsJsonObject();
            assertEquals(first.get("from"), copy.get("from"), "copy " + i + " starts elsewhere");
            assertEquals(first.get("to"), copy.get("to"), "copy " + i + " ends elsewhere");
        }
        // Geometry is deliberately *not* split at the rail: the wooden legs sit at the same height as
        // the stone beside them, so no cut plane separates them and the masks have to do it.
        assertEquals(9, first.getAsJsonArray("to").get(1).getAsInt(), "the bench is nine tall");
    }
}
