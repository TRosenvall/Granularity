package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;

/**
 * The <b>shape</b> a composite has been cut into — the other axis from its {@link
 * com.tarosie.granularity.core.Finish}.
 *
 * <h2>Why this is an enum and not data</h2>
 * A finish is data on the item because it multiplies against nothing: thirteen finishes cost one item.
 * A form is the opposite — a slab has to extend {@code SlabBlock} to inherit its shape, placement and
 * merging, a stair {@code StairBlock}, a wall {@code WallBlock} — so each is a registered block and
 * always will be. Timothy settled that: <i>forms stay separate blocks, finishes are data</i>.
 *
 * <p>What this adds is a <b>name for the axis</b>. Before it, "cut a mottled block into mottled slabs"
 * could only be said by writing a recipe per pair; now a cut states a form and a finish on each side
 * and one class covers both kinds of change.
 *
 * <h2>The conservation rule lives here</h2>
 * {@link #grains()} is what one of these hands back under the hammer, and it is the whole reason a form
 * needs to be a first-class thing rather than a block id in some JSON. A stonecutter consumes exactly
 * one input, so a cut that yields more grains than it consumed is a press — and that is not
 * hypothetical:
 *
 * <ul>
 *   <li>block → 2 slabs is 9 grains in, 8 out. Fine.</li>
 *   <li>block → 1 wall is 9 in, 9 out. Break-even, and deliberately watched.</li>
 *   <li>block → 1 stair is 9 in, 9 out — <b>now</b>. It used to be 13 out and therefore a press,
 *       because vanilla's staircase yields four from six and we had inherited that loss. Our staircase
 *       yields six, so a stair costs one whole block and the cut is legal on its own arithmetic
 *       instead of being withheld by exception. {@code ConservationTest} walks every shipped cut
 *       rather than trusting this comment.</li>
 * </ul>
 */
public enum Form {

    /** The whole block: nine grains, and the only form a cut may start from to change shape. */
    BLOCK(GranularityBlocks.COBBLESTONE, Composition.SLOTS),

    SLAB(GranularityBlocks.COBBLESTONE_SLAB, CompositeShapes.SLAB_GRAINS),

    /**
     * A whole block's worth, the same as a wall — both are full height.
     *
     * <p>It was 13 while our staircase copied vanilla's lossy four-from-six, which made it the one
     * shape worth more than the stone it came from and made any 1:1 cut into it pay. Six-from-six
     * fixed that at the source; see {@link CompositeShapes#STAIRS_GRAINS}.
     */
    STAIRS(GranularityBlocks.COBBLESTONE_STAIRS, CompositeShapes.STAIRS_GRAINS),

    WALL(GranularityBlocks.COBBLESTONE_WALL, CompositeShapes.WALL_GRAINS);

    private final Supplier<? extends Block> block;
    private final int grains;

    Form(Supplier<? extends Block> block, int grains) {
        this.block = block;
        this.grains = grains;
    }

    /** The registered block for this shape. */
    public Block block() {
        return block.get();
    }

    /** What one of these hands back under the hammer, out of a block's nine. */
    public int grains() {
        return grains;
    }

    /** Lowercase, for recipe JSON. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The form of that name, or null.
     *
     * <p>Null rather than a default, for the reason {@link com.tarosie.granularity.core.Finish#find}
     * is: this is only ever read from a recipe a person wrote, and a misspelled form silently becoming
     * a block would be a cut that reshapes nothing while claiming to.
     */
    public static Form find(String id) {
        for (Form form : values()) {
            if (form.id().equals(id)) {
                return form;
            }
        }
        return null;
    }
}
