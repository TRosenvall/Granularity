package com.tarosie.granularity.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * A material's colour, read off the item that <i>is</i> that material.
 *
 * <p>A mod adding a ruby should not have to pick a hex code for it — the ruby already has a picture,
 * and the average of that picture is what the stone around it should look like. The method is not a
 * guess: deriving granite, diorite and andesite this way reproduces the values already in the roster
 * to within a few points per channel, which is how tuff and calcite were coloured.
 *
 * <h2>Read from the jar, never from the atlas</h2>
 * The obvious source is the texture atlas, and it is the wrong one, because <b>the atlas is
 * client-only</b>. Grain tint is not merely decoration: {@code SmeltAverageRecipe} reads it, and
 * recipes run on the server. Deriving from the atlas would give a dedicated server no answer at all
 * and produce a block that smelts differently from how it looks.
 *
 * <p>So this reads the PNG out of the classpath — the same bytes on both sides, since a mod ships one
 * jar to both. It also means a resource pack cannot change what an ore smelts into, which is right.
 *
 * <p>No Minecraft types are involved, deliberately: this package stays testable without a running
 * game, and the work is a PNG decode and an average.
 */
public final class TextureTint {

    private TextureTint() {
    }

    /**
     * The average colour of the item's texture, as 0xRRGGBB.
     *
     * <p>Resolved through the item's <i>model</i> rather than by guessing a texture path, because the
     * model is the authority on which sprite an item wears; the conventional path is only the
     * fallback for a model this cannot read.
     *
     * @throws IllegalArgumentException if no texture can be found, which is deliberate — see below
     */
    public static int averageOf(String itemId) {
        int colon = itemId.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Item id must be namespaced: " + itemId);
        }
        String namespace = itemId.substring(0, colon);
        String path = itemId.substring(colon + 1);

        String sprite = spriteFromModel(namespace, path);
        BufferedImage image = sprite == null ? null : read(sprite);
        if (image == null) {
            image = read("assets/" + namespace + "/textures/item/" + path + ".png");
        }
        if (image == null) {
            // Loud, not silent. A tint quietly defaulted here would differ between a client that
            // could find the texture and a server that could not, and the two would then disagree
            // about what the material smelts into. Vanilla assets in particular are absent from a
            // dedicated server, so a grain backed by a vanilla item must state its colour outright.
            throw new IllegalArgumentException(
                    "No readable texture for " + itemId + " — pass an explicit tint to Grains.register "
                            + "instead. (Items from the vanilla jar have no texture on a dedicated "
                            + "server, so they always need one.)");
        }
        return average(image);
    }

    /** {@code layer0} out of {@code assets/ns/models/item/path.json}, as a resource path. */
    private static String spriteFromModel(String namespace, String path) {
        try (InputStream in = open("assets/" + namespace + "/models/item/" + path + ".json")) {
            if (in == null) {
                return null;
            }
            JsonObject model = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!model.has("textures")) {
                return null;
            }
            JsonObject textures = model.getAsJsonObject("textures");
            // layer0 is the flat-item convention; `all` covers a block-shaped item wearing one sprite.
            String layer = textures.has("layer0") ? textures.get("layer0").getAsString()
                    : textures.has("all") ? textures.get("all").getAsString() : null;
            if (layer == null) {
                return null;
            }
            int colon = layer.indexOf(':');
            String ns = colon < 0 ? "minecraft" : layer.substring(0, colon);
            String tex = colon < 0 ? layer : layer.substring(colon + 1);
            return "assets/" + ns + "/textures/" + tex + ".png";
        } catch (Exception ignored) {
            // A model we cannot parse is not an error; the conventional path is tried next.
            return null;
        }
    }

    private static BufferedImage read(String resource) {
        try (InputStream in = open(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static InputStream open(String resource) {
        InputStream in = TextureTint.class.getClassLoader().getResourceAsStream(resource);
        return in != null ? in : TextureTint.class.getResourceAsStream("/" + resource);
    }

    /**
     * The alpha-weighted mean colour of an image.
     *
     * <p>Weighted rather than a flat mean over every pixel, because an item sprite is mostly
     * transparent background and counting those as black drags every material toward soot. A
     * half-transparent edge pixel counts half, which is what antialiasing means.
     */
    public static int average(BufferedImage image) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long weight = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = argb >>> 24;
                if (alpha == 0) {
                    continue;
                }
                red += (long) (argb >> 16 & 0xFF) * alpha;
                green += (long) (argb >> 8 & 0xFF) * alpha;
                blue += (long) (argb & 0xFF) * alpha;
                weight += alpha;
            }
        }
        if (weight == 0) {
            throw new IllegalArgumentException("Texture is entirely transparent");
        }
        return (int) (red / weight) << 16 | (int) (green / weight) << 8 | (int) (blue / weight);
    }
}
