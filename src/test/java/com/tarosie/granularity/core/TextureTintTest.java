package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mod should not have to pick a hex code for its ruby.
 *
 * <p>The averaging has to agree between client and server, because grain tint reaches
 * {@code SmeltAverageRecipe} and recipes run server-side — a colour read from the client's texture
 * atlas would give a block that smelts differently from how it looks. These tests pin the arithmetic;
 * {@link TextureTint} carries the argument about where the bytes come from.
 */
class TextureTintTest {

    private static BufferedImage image(int width, int height, int argb) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, argb);
            }
        }
        return out;
    }

    @Test
    @DisplayName("a flat sprite averages to its own colour")
    void flatColour() {
        assertEquals(0x9B111E, TextureTint.average(image(16, 16, 0xFF9B111E)));
    }

    @Test
    @DisplayName("transparent background is ignored, not counted as black")
    void transparentPixelsDoNotDarken() {
        BufferedImage sprite = image(16, 16, 0x00000000);
        // Four opaque red pixels adrift in a transparent field.
        for (int i = 0; i < 4; i++) {
            sprite.setRGB(i, 0, 0xFFB02E26);
        }
        assertEquals(0xB02E26, TextureTint.average(sprite),
                "an item is mostly background; counting it would make every material soot");
    }

    @Test
    @DisplayName("partial alpha counts partially, which is what antialiasing means")
    void alphaIsAWeight() {
        BufferedImage sprite = image(2, 1, 0x00000000);
        sprite.setRGB(0, 0, 0xFF000000);          // full weight, black
        sprite.setRGB(1, 0, 0x80FFFFFF);          // half weight, white
        int averaged = TextureTint.average(sprite) & 0xFF;
        assertTrue(averaged > 60 && averaged < 110,
                "a half-transparent white against an opaque black should land near a third, got "
                        + averaged);
    }

    @Test
    @DisplayName("an entirely transparent sprite is an error rather than a silent black")
    void emptySpriteIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TextureTint.average(image(4, 4, 0x00000000)));
    }

    @Test
    @DisplayName("our own item textures resolve through the model and average sensibly")
    void readsOurOwnAssetsOffTheClasspath() {
        // granite_chunk is a greyscale sprite tinted at draw time, so its average is a mid grey —
        // the point here is that the classpath lookup and model resolution work at all.
        int tint = TextureTint.averageOf("granularity:granite_chunk");
        int red = tint >> 16 & 0xFF;
        int green = tint >> 8 & 0xFF;
        int blue = tint & 0xFF;
        assertTrue(red > 20 && red < 250, "expected a real colour, got " + Integer.toHexString(tint));
        assertEquals(red, green, "the chunk sprite is greyscale");
        assertEquals(green, blue, "the chunk sprite is greyscale");
    }

    @Test
    @DisplayName("an item with no readable texture says so instead of guessing")
    void missingTextureIsLoud() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TextureTint.averageOf("somemod:nonexistent_thing"));
        assertTrue(thrown.getMessage().contains("explicit tint"),
                "the message should tell the author what to do instead");
    }
}
