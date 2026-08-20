package com.tarosie.granularity.content;

import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * Something that grows, spatters or settles on stone without being stone.
 *
 * <p>Moss is the first. Blood, soot, frost, lichen are the same kind of thing, and so is whatever
 * another mod wants to add — which is the whole reason this is a registered object rather than a
 * blockstate flag. A flag cannot be registered: block properties are fixed when the block is
 * constructed, so there is no moment at which another mod could add one. An overlay can be added to
 * {@link GranularityOverlays#REGISTRY} at any point during registration, by anyone.
 *
 * <p>The other reason is arithmetic. Overlays combine freely — a block can be mossy <i>and</i> blood
 * spattered — so as blockstate properties, N of them would multiply every blockstate variant and
 * every model by 2<sup>N</sup>. Stairs alone went from 40 variants to 80 for moss; eight overlays
 * would be ten thousand variants and three thousand models. As data on the block entity, N overlays
 * cost one entry per block and not a single extra model.
 *
 * <p><b>Geometry is free, and that is the trick.</b> An overlay is drawn on exactly the boxes the
 * stone occupies, so its quads are the block's own quads wearing a different sprite — see
 * {@link com.tarosie.granularity.client.OverlayBakedModel}. Nothing here needs a model, and because
 * the quads are copied <i>after</i> the blockstate has rotated them, stairs, walls and multipart all
 * work with no per-rotation anything.
 *
 * <p><b>Which faces</b> an overlay covers is not its business — that belongs to the block, and lives
 * in {@link Coating}. What lives here is which sprite to draw on a given face, because some things
 * genuinely look different from above: grass is green on top and a fringe down the sides, and
 * mycelium the same. An overlay with one texture uses it everywhere, which is moss and blood.
 *
 * <p><b>A family</b> makes overlays exclusive. Grass is meant to arrive rooted, then partial, then
 * full — three overlays that are three stages of one thing, and a face showing two stages at once is
 * nonsense. Overlays sharing a family replace one another on a face; overlays with none stack freely,
 * which is moss and slime and the general case. Exclusivity is a property of the overlays themselves
 * rather than a rule written where they are applied, so it holds however they arrive: by hand, by
 * bonemeal, or by whatever grows them later.
 *
 * @param texture the sprite to draw, which must be stitched into the block atlas — declare it in an
 *                {@code atlases/blocks.json} if no model references it
 * @param perFace sprites that override {@code texture} on particular faces; usually empty
 * @param family  overlays that cannot share a face, or null where the overlay stacks with anything
 */
public record Overlay(ResourceLocation texture, Map<Direction, ResourceLocation> perFace,
                      @org.jetbrains.annotations.Nullable ResourceLocation family, boolean tinted,
                      boolean brushable) {

    public Overlay {
        perFace = Map.copyOf(perFace);
    }

    /** An overlay that looks the same from every direction and stacks with anything — moss, slime. */
    public Overlay(ResourceLocation texture) {
        this(texture, Map.of(), null, false, true);
    }

    /** One stage of something: exclusive with every other overlay in the same family. */
    public Overlay(ResourceLocation texture, ResourceLocation family) {
        this(texture, Map.of(), family, false, true);
    }

    /**
     * Damage: drawn in the block's <b>own</b> colour, and not something a brush can take off.
     *
     * <p>Two properties, one idea, which is why they are one factory rather than two flags to
     * remember to set together.
     *
     * <p>Overlays are untinted by default and that is usually right: moss is green on slate and green
     * on marble, because moss is a different material sitting on the stone. <b>Damage is not.</b> A
     * crack is the same rock, in shadow — so it has to be darker than whatever it is cracking, and no
     * single fixed colour can be darker than both chalk and deepslate.
     *
     * <p>Keeping the tint index solves it exactly, because a tint multiplies: a mid-grey sprite over a
     * block tinted slate comes out as slate at half brightness, and over marble as marble at half
     * brightness. It is the same trick the whole mod runs on — greyscale art, colour from the
     * composition — applied to an overlay for the first time.
     *
     * <p>And it is <b>not brushable</b>, for the same reason it is tinted: growth sits <i>on</i> a
     * surface and can be swept off it, but a crack is a hole <i>in</i> the surface and no amount of
     * brushing fills it in. Firing the block closes it; nothing else does. Without this a brush
     * scrubbed cracks away, which read as a bug because it is one.
     */
    public static Overlay damage(ResourceLocation texture) {
        return new Overlay(texture, Map.of(), null, true, false);
    }

    /**
     * An overlay with a lid: one sprite on top, another around the sides.
     *
     * <p>The shape grass and mycelium take. The bottom is not named because a block's underside is
     * not a face grass grows on — and that is decided by the {@link Coating}, not here.
     */
    public static Overlay topAndSides(ResourceLocation top, ResourceLocation sides,
                                      @org.jetbrains.annotations.Nullable ResourceLocation family) {
        return new Overlay(sides, Map.of(Direction.UP, top), family, false, true);
    }

    /** The sprite for one face, falling back to the overlay's own. */
    public ResourceLocation textureFor(Direction face) {
        return perFace.getOrDefault(face, texture);
    }
}
