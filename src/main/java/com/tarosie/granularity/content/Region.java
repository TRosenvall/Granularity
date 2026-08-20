package com.tarosie.granularity.content;

import java.util.List;
import net.minecraft.world.level.block.Block;

/**
 * A part of a block that can be costumed on its own.
 *
 * <h2>The tint index was already the region map</h2>
 * Nothing here is new information. Every composite model is split into parts so its colours can come
 * from different places — a stonecutter's lower stone from one composition, its upper stone from
 * another, its frame from a plank and its blade from an ingot — and that split is written into the
 * models as tint indices. A region is that same division read for a second purpose, so a machine
 * needs no re-authoring to become dressable part by part.
 *
 * <p>Which is also why the parts are uneven, and honestly so. Measured across the models:
 *
 * <table border="1">
 *   <caption>What each machine is actually made of</caption>
 *   <tr><th>Block</th><th>Regions</th></tr>
 *   <tr><td>Stonecutter</td><td>lower stone, upper stone, timber, blade</td></tr>
 *   <tr><td>Piston</td><td>stone, timber, metal</td></tr>
 *   <tr><td>Furnace, observer</td><td>stone, face</td></tr>
 *   <tr><td>Dispenser, dropper, lever</td><td>stone only</td></tr>
 * </table>
 *
 * <p>So only the stonecutter and the piston are genuinely part-shaped. The rest are one stone region
 * with a trim detail, and dressing them by part is the same as dressing them whole — which is the
 * argument for splitting them <i>spatially</i> instead, a furnace into a top and a bottom. That is a
 * later step and it costs nothing here: a spatial region is just another entry in this enum with
 * another tint range behind it, and every other part of the machinery is already indifferent to which
 * kind it is.
 *
 * <h2>Referring to the client's tint constants is safe</h2>
 * They are {@code static final int}, so javac inlines them at the use site and no reference to a
 * client-only class survives into this file's bytecode. One source of truth beats a second copy of
 * the numbers that could drift from it.
 */
public enum Region {

    /** Everything the block shows. What a plain block, slab, stair or wall offers: one slot. */
    ALL("all", 0, com.tarosie.granularity.client.CompositeBlockColour.PLAIN_TINT),

    /** The stone itself, or a double slab's lower half. */
    STONE("stone", 0, com.tarosie.granularity.client.CompositeBlockColour.UPPER_BASE - 1),

    /**
     * The lower half of a block split at the waist. Same tints as {@link #STONE}, a clearer name.
     *
     * <p>A furnace offers a top and a bottom rather than "stone" and "upper stone", because that is
     * what a player sees. The tint ranges are shared deliberately — a block offers one naming or the
     * other, never both, so nothing has to disambiguate them.
     */
    BOTTOM("bottom", 0, com.tarosie.granularity.client.CompositeBlockColour.UPPER_BASE - 1),

    /** The upper half of a block split at the waist. */
    TOP("top", com.tarosie.granularity.client.CompositeBlockColour.UPPER_BASE,
            com.tarosie.granularity.client.CompositeBlockColour.WOOD_TINT - 1),

    /** The second stone: a stonecutter's upper block, a double slab's top half. */
    UPPER_STONE("upper_stone", com.tarosie.granularity.client.CompositeBlockColour.UPPER_BASE,
            com.tarosie.granularity.client.CompositeBlockColour.WOOD_TINT - 1),

    /** The timber: a piston's plate, a stonecutter's frame. */
    TIMBER("timber", com.tarosie.granularity.client.CompositeBlockColour.WOOD_TINT,
            com.tarosie.granularity.client.CompositeBlockColour.WOOD_TINT),

    /** The metal: a piston's brackets, a stonecutter's blade. */
    METAL("metal", com.tarosie.granularity.client.CompositeBlockColour.METAL_TINT,
            com.tarosie.granularity.client.CompositeBlockColour.METAL_TINT, Wears.GRAIN),

    /**
     * A face that takes no colour from the block: a furnace door, an observer's eye.
     *
     * <p>Defined but not currently offered by anything — see {@link #of}. Kept because the tint range
     * has to be named for the region check to be exhaustive, and because a block may yet have a face
     * worth dressing.
     */
    FACE("face", com.tarosie.granularity.client.CompositeBlockColour.PLAIN_TINT,
            com.tarosie.granularity.client.CompositeBlockColour.PLAIN_TINT);

    /**
     * Where a costumed region's synthesised quads put their tint indices.
     *
     * <p>A region dressed in one of our blocks is drawn as stone, and stone is a base plus a layer per
     * grain — so the quads need somewhere to say both <i>which region</i> they belong to and
     * <i>which layer</i> they are, or two regions wearing two different stones would have no way to be
     * told apart at colouring time. Ten indices per region, well clear of everything else.
     */
    public static final int COSTUME_BASE = 100;

    private static final int PER_REGION = 10;

    /**
     * What a part will accept, which is what the part was built out of.
     *
     * <p>A costume asks the same of you as the recipe did. A stonecutter's blade was an iron ingot, so
     * dressing it takes an ingot and not an iron block — asking for a block there would be asking for
     * something the recipe never wanted.
     *
     * <p><b>Timber is not an exception</b>, though it looks like it should be. A piston's plate was
     * made from planks and planks are full blocks, so the slot goes on taking blocks — which also keeps
     * a plate dressable in <i>stone</i>, and a costume that cannot cover the whole of a block is not
     * much of a costume. Only the ingot is genuinely not a block.
     *
     * <p>Which is why the two are applied differently. A {@link #BLOCK} part borrows the donor's
     * sprites; an {@link #INGOT} part has no sprites to lend and borrows its <i>colour</i>, through the
     * same {@code metalTint} that already decides what a gold-braced piston looks like. Nothing new
     * had to be invented for either.
     */
    public enum Wears {
        /** A full opaque cube, whose six faces are lent to ours. Paired with a colorant slot. */
        BLOCK,
        /** A grain item — chunk, ore, gem, ingot. It has no faces, so it lends only its colour. */
        GRAIN
    }

    private final String id;
    private final int firstTint;
    private final int lastTint;
    private final Wears wears;

    Region(String id, int firstTint, int lastTint) {
        this(id, firstTint, lastTint, Wears.BLOCK);
    }

    Region(String id, int firstTint, int lastTint, Wears wears) {
        this.id = id;
        this.firstTint = firstTint;
        this.lastTint = lastTint;
        this.wears = wears;
    }

    /** What this part will accept. */
    public Wears wears() {
        return wears;
    }

    /**
     * The outline drawn in this part's empty slot.
     *
     * <p>A slot that wants an ingot should not show a cube. There is no other label on these squares —
     * a row of four identical outlines over a stonecutter says nothing about which is the blade — so
     * the shape is carrying the meaning and it has to be the right shape.
     */
    public String emptyIcon() {
        return wears == Wears.GRAIN ? "item/empty_transmog_slot_grain" : "item/empty_transmog_slot";
    }

    /** Whether this part has a colorant slot beneath it. A grain part is already only a colour. */
    public boolean takesColorant() {
        return wears == Wears.BLOCK;
    }

    public String id() {
        return id;
    }

    /** Whether a quad carrying this tint index belongs to this region. */
    public boolean covers(int tintIndex) {
        return tintIndex >= firstTint && tintIndex <= lastTint;
    }

    /** Where this region's synthesised stone puts one layer's quads. */
    public int costumeTint(int layer) {
        return COSTUME_BASE + ordinal() * PER_REGION + layer;
    }

    /**
     * The region a synthesised tint index came from, or null if it is not one.
     *
     * <p>The lower bound is tested before the division, not after it. Java truncates toward zero, so
     * {@code (91 - 100) / 10} is 0 rather than -1 and every index from 91 to 99 decoded as the first
     * region — a block whose model used one of those would have been coloured from a costume nobody
     * put on it.
     */
    public static Region ofCostumeTint(int tintIndex) {
        if (tintIndex < COSTUME_BASE) {
            return null;
        }
        int offset = (tintIndex - COSTUME_BASE) / PER_REGION;
        return offset < values().length ? values()[offset] : null;
    }

    /** Which layer a synthesised tint index is — 0 for the base, 1–9 for a grain. */
    public static int costumeLayer(int tintIndex) {
        return (tintIndex - COSTUME_BASE) % PER_REGION;
    }

    public static Region byId(String id) {
        for (Region region : values()) {
            if (region.id.equals(id)) {
                return region;
            }
        }
        return ALL;
    }

    /**
     * The parts a block offers, in the order they are shown.
     *
     * <p>Declared in one place rather than overridden per block, deliberately. The alternative was a
     * method on each of a dozen block classes, and this codebase has already paid twice for that shape
     * — the piston head losing its dye, and a costume being added to one drop path out of three. A
     * block missing from this list falls back to a single slot, which is the harmless answer.
     */
    public static List<Region> of(net.minecraft.world.level.block.state.BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CompositeSlabBlock
                && state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                        == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE) {
            // A double slab is two slabs, so it dresses as two — and it can be, because the only way
            // to take one apart is to break the whole thing, which hands both costumes back at once.
            // A single slab is one surface and offers one slot.
            return List.of(TOP, BOTTOM);
        }
        if (block instanceof CompositeStonecutterBlock) {
            return List.of(STONE, UPPER_STONE, TIMBER, METAL);
        }
        if (block instanceof CompositePistonBlock) {
            return List.of(STONE, TIMBER, METAL);
        }
        if (block instanceof CompositeFurnaceBlock || block instanceof CompositeObserverBlock) {
            // The face is deliberately not offered. A furnace door and an observer's eye are how you
            // read the block — which way it points, whether it is lit — and a costume over them covers
            // that up entirely rather than decorating it.
            return block instanceof CompositeFurnaceBlock
                    // Split at the waist by tools/split_model_halves.py, so each half wears its own
                    // rock. Parts bought a furnace nothing — it is one region of stone and a door —
                    // and a top and a bottom is the division it visibly already has.
                    ? List.of(TOP, BOTTOM)
                    : List.of(STONE);
        }
        return List.of(ALL);
    }

    /** For callers with no state in hand, such as the startup check over baked models. */
    public static List<Region> of(Block block) {
        return of(block.defaultBlockState());
    }

    /**
     * Parts a block draws but deliberately does not let you dress.
     *
     * <p>Named rather than merely left out, so the startup check can tell a part withheld on purpose
     * from a part nobody remembered. A guard that warns about the design is a guard people learn to
     * ignore, and then it is not guarding anything.
     */
    public static List<Region> withheld(Block block) {
        return block instanceof CompositeFurnaceBlock || block instanceof CompositeObserverBlock
                ? List.of(FACE)
                : List.of();
    }
}
