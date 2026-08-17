package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every sprite a finish names must exist.
 *
 * <p>This is a spelling test, and it earns its place because the failure is invisible. A finish names
 * its sprite as a string; if that string is wrong the atlas lookup misses and the face renders as the
 * missing-texture chequer or, worse, as whatever the lookup falls back to. Nothing throws, nothing
 * logs, the suite passes, and only a person standing in front of the block can tell.
 *
 * <p>It matters more now that a style can name <b>three</b> sprites rather than one. Sides, ends and
 * underside are three chances to mistype, and two of them are on faces you have to walk around the
 * block to see — which is exactly how {@link Finish#FINE} and {@link Finish#CHISELED_MOTTLED} shipped
 * with their side sprite on top and nobody noticed for a fortnight.
 *
 * <p>Sprites are found on the classpath rather than by reading the generator, so this checks what is
 * actually shipped: a style whose {@code extract_textures.py} entry was never run, or was run against
 * a jar missing the source texture, fails here.
 */
class FinishSpriteTest {

    private static final String TEXTURES = "assets/granularity/textures/block/";

    private static boolean exists(String sprite) {
        return FinishSpriteTest.class.getClassLoader()
                .getResource(TEXTURES + sprite + ".png") != null;
    }

    @Test
    @DisplayName("every finish's side sprite is on the classpath")
    void sideSpritesExist() {
        List<String> missing = new ArrayList<>();
        for (Finish finish : Finish.values()) {
            if (finish.showsGrains()) {
                // Cobbled swaps nothing: the authored models are already drawn as it.
                assertNotNull(finish, "unreachable");
                continue;
            }
            assertNotNull(finish.texture(), finish + " has no side sprite");
            if (!exists(finish.texture())) {
                missing.add(finish + " -> " + finish.texture() + ".png");
            }
        }
        assertTrue(missing.isEmpty(), "sprites named but not shipped: " + missing
                + " — run tools/extract_textures.py; these render as the missing-texture chequer "
                + "and nothing logs it");
    }

    @Test
    @DisplayName("every finish's end and underside sprites are on the classpath")
    void endSpritesExist() {
        List<String> missing = new ArrayList<>();
        int withEnds = 0;
        for (Finish finish : Finish.values()) {
            if (finish.showsGrains()) {
                continue;
            }
            if (finish.hasDistinctEnds()) {
                withEnds++;
            }
            for (boolean down : new boolean[] {false, true}) {
                String sprite = finish.endTexture(down);
                assertNotNull(sprite, finish + " has no sprite for its " + (down ? "underside" : "top"));
                if (!exists(sprite)) {
                    missing.add(finish + (down ? " (down)" : " (up)") + " -> " + sprite + ".png");
                }
            }
        }
        assertTrue(missing.isEmpty(), "end sprites named but not shipped: " + missing);
        // Pinned so that deleting an end sprite from a style is a failure rather than a silent
        // reversion to drawing its sides on its top.
        assertEquals(5, withEnds, "five styles draw their ends differently: Pebbled, Chiseled "
                + "Pebbled, Squared, Fine and Chiseled Mottled. See STONEWORK_STYLES.md §3.");
    }

    @Test
    @DisplayName("a style with no ends of its own answers its sides for every face")
    void endsFallBackToTheSides() {
        for (Finish finish : Finish.values()) {
            if (finish.showsGrains() || finish.hasDistinctEnds()) {
                continue;
            }
            assertEquals(finish.texture(), finish.endTexture(false), finish + " top");
            assertEquals(finish.texture(), finish.endTexture(true), finish + " underside");
        }
    }

    @Test
    @DisplayName("only sandstone-derived Pebbled distinguishes its underside")
    void onlyPebbledHasItsOwnUnderside() {
        // Vanilla's cube_column shares one sprite between top and bottom, and only cube_bottom_top —
        // which among our sources is sandstone alone — draws a third. A second style acquiring its own
        // underside is fine, but it should be a decision rather than a copy-paste.
        List<Finish> distinct = new ArrayList<>();
        for (Finish finish : Finish.values()) {
            if (!finish.showsGrains() && !finish.endTexture(true).equals(finish.endTexture(false))) {
                distinct.add(finish);
            }
        }
        assertEquals(List.of(Finish.PEBBLED), distinct,
                "only Pebbled comes from a cube_bottom_top block");
    }
}
