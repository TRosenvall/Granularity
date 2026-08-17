package com.tarosie.granularity.core;

/**
 * The RGB values behind the 16-colour lattice.
 *
 * <p>Values are vanilla's {@code DyeColor} texture-diffuse colours, in dye order, so lattice index
 * <i>n</i> is the same colour players already know as dye <i>n</i>. They are duplicated here rather
 * than read from {@code DyeColor} to keep this package free of Minecraft — the colours are needed
 * by tests, and by the tint provider, and only one of those can load a Minecraft class.
 *
 * <p>Design §5's economy depends on this file: sprites are grayscale and colour arrives by tint, so
 * the atlas holds ~57 sprites rather than 57×16.
 *
 * <h2>Muting is art direction, not physics</h2>
 * Full-saturation dye on stone reads as painted plastic — a mountain of pure yellow. {@link #rock}
 * pulls colours toward grey and {@link #ore} keeps them closer to full. Both are knobs, not
 * findings; the prototype has nothing to say about them and they should be set by eye once the
 * material lattice exists.
 */
public final class LatticeColour {

    /** Vanilla dye texture-diffuse colours, indexed by dye order. */
    private static final int[] RGB = {
            16383998, // white
            16351261, // orange
            13061821, // magenta
            3847130,  // light blue
            16701501, // yellow
            8439583,  // lime
            15961002, // pink
            4673362,  // gray
            10329495, // light gray
            1481884,  // cyan
            8991416,  // purple
            3949738,  // blue
            8606770,  // brown
            6192150,  // green
            11546150, // red
            1908001,  // black
    };

    /**
     * How far rock is pulled toward grey.
     *
     * <p>Calibrated against Timothy's own bedrock examples, which span mean chroma 0 (metamorphic,
     * pure greyscale), 14 (igneous) and 29 (sedimentary). At 0.72 the lattice produces mean chroma
     * 25 across a stone face, with the loudest dyes reaching ~52 — matching the examples in the
     * middle and leaving the "somewhat exaggerated" headroom he asked for at the ends. Stone is
     * supposed to read as stone; 0.55 made it read as painted.
     */
    private static final double ROCK_DESATURATION = 0.72;

    /** Ore keeps most of its saturation — it is the thing the player is looking for. */
    private static final double ORE_DESATURATION = 0.12;

    /**
     * How far a crafted block's matrix is pulled toward grey — much less than natural stone.
     *
     * <p>Averaging many different grains lands near grey by arithmetic; muting that a further 72%
     * on top left a mixed cobblestone reading as bare grey with coloured confetti in it. Natural
     * stone wants the heavy mute, because a whole mountain of saturated rock looks painted. A
     * crafted block is one block a player made on purpose and should show it.
     */
    private static final double MATRIX_DESATURATION = 0.30;

    private LatticeColour() {
    }

    /** The pure lattice colour, 0xRRGGBB. */
    public static int rgb(int colour) {
        if (colour < 0 || colour >= RGB.length) {
            throw new IllegalArgumentException("Colour out of lattice: " + colour);
        }
        return RGB[colour];
    }

    /** The tint for rock of this colour: muted, so stone reads as stone. */
    public static int rock(int colour) {
        return rockTint(rgb(colour));
    }

    /** The tint for ore of this colour: close to full saturation, so it stands out of the rock. */
    public static int ore(int colour) {
        return oreTint(rgb(colour));
    }

    /** As {@link #rock(int)}, but for an arbitrary colour — an average, rather than a lattice entry. */
    public static int rockTint(int rgb) {
        return desaturate(rgb, ROCK_DESATURATION);
    }

    /** As {@link #ore(int)}, but for an arbitrary colour. */
    public static int oreTint(int rgb) {
        return desaturate(rgb, ORE_DESATURATION);
    }

    /** The tint for a crafted block's matrix: present, not painted. See {@link #MATRIX_DESATURATION}. */
    public static int matrixTint(int rgb) {
        return desaturate(rgb, MATRIX_DESATURATION);
    }

    /**
     * The mean of several lattice colours, weighted by how many slots hold each.
     *
     * <p>Design §3: <i>"smelting averages the colours into a single colour"</i>, described there as
     * arithmetic on the composition. The same arithmetic colours natural stone, which is what makes
     * a region boundary a smooth gradient rather than a hard seam — and what makes §4's promise
     * that compositions are "muddier near the surface, purer with depth" literally true of the
     * colour, since a mixed block averages toward grey and a pure one does not.
     *
     * <p>Straight integer arithmetic in sRGB, not a gamma-correct blend. A gamma-correct average
     * would look marginally better in isolation, but §3's smelt recipe does this arithmetic on the
     * stored composition, and a smelted block that did not match the stone it came from would be a
     * worse failure than a slightly muddy mid-tone.
     *
     * @param countsByColour one entry per lattice colour
     * @return the averaged colour, or -1 if no slots were counted
     */
    public static int average(int[] countsByColour) {
        long r = 0;
        long g = 0;
        long b = 0;
        int total = 0;
        for (int colour = 0; colour < countsByColour.length; colour++) {
            int count = countsByColour[colour];
            if (count == 0) {
                continue;
            }
            int rgb = rgb(colour);
            r += (long) channel(rgb, 16) * count;
            g += (long) channel(rgb, 8) * count;
            b += (long) channel(rgb, 0) * count;
            total += count;
        }
        if (total == 0) {
            return -1;
        }
        int half = total / 2;
        return (int) ((((r + half) / total) << 16) | (((g + half) / total) << 8) | ((b + half) / total));
    }

    /**
     * Pulls a colour toward grey <i>at its own luminance</i>, rather than toward a fixed mid-grey.
     *
     * <p>The distinction matters because tint is a multiply against a bright greyscale base. Fixing
     * the grey would flatten the palette's light-to-dark ordering — white and black dye would both
     * end up mid-brightness, so white stone and black stone would be hard to tell apart. Blending
     * toward each colour's own luminance removes chroma and leaves lightness alone, which is what
     * keeps sixteen tinted stones distinguishable from one another.
     */
    public static int desaturate(int rgb, double amount) {
        int r = channel(rgb, 16), g = channel(rgb, 8), b = channel(rgb, 0);
        int grey = (int) Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b);
        return blend(rgb, (grey << 16) | (grey << 8) | grey, amount);
    }

    /** Linear blend from {@code from} toward {@code to}, per channel. */
    public static int blend(int from, int to, double amount) {
        double t = Math.max(0.0, Math.min(1.0, amount));
        int r = channel(from, 16), g = channel(from, 8), b = channel(from, 0);
        int r2 = channel(to, 16), g2 = channel(to, 8), b2 = channel(to, 0);
        int mr = (int) Math.round(r + (r2 - r) * t);
        int mg = (int) Math.round(g + (g2 - g) * t);
        int mb = (int) Math.round(b + (b2 - b) * t);
        return (mr << 16) | (mg << 8) | mb;
    }

    private static int channel(int rgb, int shift) {
        return (rgb >> shift) & 0xFF;
    }
}
