package com.tarosie.granularity.content;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tarosie.granularity.core.Finish;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every finishable block must be nameable in every finish.
 *
 * <p>A finish selects the translation key rather than prefixing the name — smelted slate is "Smooth
 * Slate", not "Smooth Slate Cobblestone" — which means each form needs its own pair of keys. Miss one
 * and nothing breaks, nothing logs, and the player is simply shown
 * {@code block.granularity.cobblestone_wall.smooth} where a name should be. That is exactly what
 * happened when slabs, stairs and walls first took a finish: only the plain block had keys.
 */
class FinishNamingTest {

    /**
     * The blocks that carry a finish today.
     *
     * <p>Add to this when a form becomes finishable — the same edit that wires its model. It is a
     * list rather than a registry read because the registry needs a running game and this does not.
     */
    private static final List<String> FINISHABLE = List.of(
            "cobblestone", "cobblestone_slab", "cobblestone_stairs", "cobblestone_wall");

    private static JsonObject lang() {
        try (InputStream in = FinishNamingTest.class.getClassLoader()
                .getResourceAsStream("assets/granularity/lang/en_us.json")) {
            if (in == null) {
                return fail("en_us.json is not on the classpath");
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception failed) {
            return fail(failed);
        }
    }

    @Test
    @DisplayName("every finishable block is named in every finish, plain and with a sole grain")
    void everyFinishHasNames() {
        JsonObject lang = lang();
        for (String block : FINISHABLE) {
            for (Finish finish : Finish.values()) {
                String base = "block.granularity." + block
                        + (finish == Finish.COBBLED ? "" : "." + finish.id());
                assertTrue(lang.has(base), "missing name key " + base);
                assertTrue(lang.has(base + ".named"),
                        "missing name key " + base + ".named — a block of one stone names itself "
                                + "after that stone, and without this the player sees the raw key");
                assertTrue(lang.has(base + ".dyed"),
                        "missing name key " + base + ".dyed — a dyed block of *mixed* stone has no "
                                + "material in its name for the colour to sit beside, so it takes a "
                                + "third wording with a blank cut into it. English orders its "
                                + "adjectives: 'Smooth Red Stone', never 'Red Smooth Stone'.");
            }
        }
    }

    @Test
    @DisplayName("a sole-grain name has somewhere to put the stone")
    void namedKeysTakeTheStone() {
        JsonObject lang = lang();
        for (String key : lang.keySet()) {
            if (key.startsWith("block.granularity.")
                    && (key.endsWith(".named") || key.endsWith(".dyed"))) {
                assertTrue(lang.get(key).getAsString().contains("%s"),
                        key + " must have a %s for the stone's own name");
            }
        }
    }
}
