package com.tarosie.granularity.core;

import java.util.Locale;

/**
 * What has been <b>done</b> to a block, as against what it is made of.
 *
 * <h2>Worked state, not derived state</h2>
 * This is the distinction worth keeping sharp. A block already carries two kinds of fact:
 *
 * <ul>
 *   <li><b>Derived</b> — everything that follows from the {@link Composition}. Whether it is loose or
 *       solid follows from {@link Composition#porosity()}; what it counts as follows from
 *       {@link Composition#majorityClass()}, which design §7 makes the thing that decides block
 *       identity. Nobody stores "this is gravel"; four earth grains among nine <i>are</i> loose
 *       material, and a rule can say so.</li>
 *   <li><b>Worked</b> — what this is. Smelting a cobblestone changes nothing about its grains: the
 *       same nine go in and come out. What changed is its history, and history cannot be recomputed
 *       from the material. So it has to be stored.</li>
 * </ul>
 *
 * <p>Collapsing the two into one enum is the mistake to avoid. They answer different questions, they
 * change at different times, and only one of them survives being recalculated from scratch.
 *
 * <h2>Why this is data and not a block</h2>
 * Smooth stone was a separate registered block, which meant every finish multiplied against every
 * form — a smooth slab, a smooth stair, a smooth wall, each its own registration, model and recipe
 * set. As data it multiplies against nothing: adding a finish costs one constant here, and a slab of
 * one finish can even sit in the same block space as a slab of another, because they are the same
 * block and vanilla will merge them.
 */
public enum Finish {

    /** Broken rock, showing its nine grains as distinct stones. The state everything starts in. */
    COBBLED(),

    /**
     * Fired in a furnace until it is one stone.
     *
     * <p>Design §3: smelting "averages the colours into a single colour". The grains are untouched —
     * a smooth block still knows it was five chalk and four shale — but it stops showing them
     * separately and renders as the mean. That is why this is a finish and not a composition change.
     */
    SMOOTH("stone_base"),

    // ---- Stonework styles -----------------------------------------------------------------------
    // Cut from smooth stone at a stonecutter. These wear the art of blocks this mod suppressed —
    // tuff, calcite, deepslate, dripstone — recovered as *cuts* rather than as materials, because a
    // pattern is a way of working stone and not a rock in its own right. The sprites are greyscale,
    // so the block's own grains still supply the colour: a mottled block of slate is slate-coloured,
    // and calling it "Tuff" would be a claim the rest of the mod never makes.
    //
    // Each is one sprite, or two or three where vanilla's own block draws its ends differently — see
    // endTexture below. FinishBakedModel derives the geometry from whatever shape it is handed, so a
    // style works on blocks, slabs, stairs and walls the day it is added.

    /** Volcanic ash, from tuff. */
    MOTTLED("mottled_base"),
    POLISHED_MOTTLED("mottled_polished_base"),
    MOTTLED_BRICKS("mottled_bricks_base"),
    CHISELED_MOTTLED("mottled_chiseled_base", "mottled_chiseled_top"),

    /** Crystalline banding, from calcite. */
    BANDED("banded_base"),

    /** Close, dark grain, from deepslate. */
    FINE("fine_base", "fine_top"),
    POLISHED_FINE("fine_polished_base"),
    FINE_BRICKS("fine_bricks_base"),
    FINE_TILES("fine_tiled_base"),
    CHISELED_FINE("fine_chiseled_base"),

    /** Dripstone's flowed, layered surface. */
    FLOWSTONE("flowstone_base"),

    // ---- Added 2026-08-17 -------------------------------------------------------------------------
    // Named for the work, like the rest. Two of these names came from looking at the *greyscale*
    // rather than at vanilla's coloured sprite, which is the only honest way to name something whose
    // colour this mod replaces: `sandstone` stripped of its yellow does not read as beds at all, it
    // reads as a field of small angular stones — hence Pebbled, which also sets up a useful contrast
    // with Cobbled's nine big ones. And Squared rather than "Cut", because every style is a cut.
    //
    // Every one was measured against the shipped styles before being added; see
    // docs/STONEWORK_STYLES.md and tools/style_survey.py. That is what kept the red variants out: they
    // are the same pattern in a redder rock, and the rock's colour is the grains' business.

    /** A field of small angular stones, from sandstone. */
    PEBBLED("pebbled_base", "pebbled_top", "pebbled_bottom"),
    CHISELED_PEBBLED("pebbled_chiseled_base", "pebbled_top"),

    /** Dressed flat and squared off, from cut sandstone. */
    SQUARED("squared_base", "pebbled_top"),

    /** The plain running bond, from stone bricks — the brickwork everyone means by "bricks". */
    BRICKS("bricks_base"),
    CHISELED_BRICKS("bricks_chiseled_base"),

    /** The tight little courses of a nether fortress. */
    SMALL_BRICKS("small_bricks_base"),
    CHISELED_SMALL_BRICKS("small_bricks_chiseled_base"),

    /**
     * The large flat polish, from polished diorite.
     *
     * <p>Diorite of the three vanilla "polished" rocks, and only diorite. Polished granite and andesite
     * measure 0.169 from {@link #POLISHED_MOTTLED}, which already ships — three near-identical greys
     * would be three menu entries nobody can tell apart in a wall. Diorite is the outlier at 0.25–0.32
     * and so the one that adds something.
     */
    POLISHED("polished_base");

    /**
     * The sprite this finish's sides wear, or null for {@link #COBBLED}.
     *
     * <p>Null means "whatever the model already had" — cobbled stone is the unworked state and the
     * authored models are already drawn as it, so there is nothing to swap.
     */
    private final String texture;

    /**
     * The sprite for the top and bottom, or null when the ends look like the sides.
     *
     * <p><b>Most styles are still one sprite</b>, and that remains the cheap case: vanilla's own model
     * for them is {@code cube_all}, and a Fine Tile really is the same tile on all six faces. But some
     * are {@code cube_column} or {@code cube_bottom_top} — sandstone, cut sandstone, chiseled
     * sandstone, deepslate, chiseled tuff — and drawing those with the side sprite on top reads as a
     * slab of bedding seen end-on. Wrong in exactly the way only a person notices, which is how it was
     * found: Timothy spotted it on Pebbled, and the same fault had been shipping unremarked on
     * {@link #FINE} and {@link #CHISELED_MOTTLED} since those were added.
     *
     * <p>So a style is <b>one sprite, or two, or three</b> — never more, because a block has only
     * three kinds of face once you accept that the four sides match. Which sprite a quad gets is
     * decided from its <i>world</i> direction; see {@code FinishBakedModel}.
     */
    private final String endTexture;

    /** The underside, for the one style whose vanilla block distinguishes it: sandstone. */
    private final String bottomTexture;

    Finish() {
        this(null);
    }

    Finish(String texture) {
        this(texture, null, null);
    }

    Finish(String texture, String endTexture) {
        this(texture, endTexture, null);
    }

    Finish(String texture, String endTexture, String bottomTexture) {
        this.texture = texture;
        this.endTexture = endTexture;
        this.bottomTexture = bottomTexture;
    }

    /** The block texture for the sides, without a namespace or path, or null when nothing is swapped. */
    public String texture() {
        return texture;
    }

    /**
     * The sprite for a top or bottom face, falling back to the sides.
     *
     * <p>Deliberately <b>not</b> {@code textureFor(Direction)}: this class is kept free of Minecraft
     * types so it stays testable without a running game, the same rule that puts the codecs in
     * {@code Finishes}. The caller has the direction and the switch is two lines — see
     * {@code FinishBakedModel.spriteFor}.
     *
     * @param down true for the underside, which only sandstone-derived styles distinguish
     */
    public String endTexture(boolean down) {
        if (down && bottomTexture != null) {
            return bottomTexture;
        }
        return endTexture != null ? endTexture : texture;
    }

    /** Whether this style draws its ends differently from its sides at all. */
    public boolean hasDistinctEnds() {
        return endTexture != null || bottomTexture != null;
    }

    /** Whether this finish shows the nine grains separately, as unworked stone does. */
    public boolean showsGrains() {
        return this == COBBLED;
    }

    /** Lowercase, for datapacks and for the component. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The finish of that name, or {@link #COBBLED} for anything unrecognised.
     *
     * <p>Unrecognised means a block saved by a later version, or by a mod that has since gone. Cobbled
     * is the honest fallback: it is the unworked state, so a block whose history cannot be read is
     * treated as having none rather than as having some particular one.
     *
     * <p>That leniency is right for <b>saved data</b> and wrong for <b>authored data</b>. A recipe
     * that says {@code "finish": "smoth"} does not mean cobbled; it means the author made a mistake,
     * and silently reading it as cobbled turns a typo into a recipe that accepts rubble where it meant
     * to demand worked stone — the exact failure {@code CompositeIngredient} exists to prevent. Use
     * {@link #find} there, which says it does not know.
     */
    public static Finish byId(String id) {
        Finish found = find(id);
        return found == null ? COBBLED : found;
    }

    /** The finish of that name, or null. For anything a human typed rather than the game saved. */
    public static Finish find(String id) {
        for (Finish finish : values()) {
            if (finish.id().equals(id)) {
                return finish;
            }
        }
        return null;
    }
}
