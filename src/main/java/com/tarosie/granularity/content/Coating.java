package com.tarosie.granularity.content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * What is on a block, and on which of its faces.
 *
 * <p>This used to be a plain {@code Set<Overlay>}, which said moss was on a block but not where —
 * so a mossy stone was mossy underneath as well, which is not how moss works. Real moss takes the
 * top and the shaded sides and leaves the rest; so does grass, so does snow, and soot settles on
 * horizontal faces only. Per face is the difference between an overlay you paint on and an overlay
 * that could plausibly spread on its own.
 *
 * <p>Stored as a six-bit mask per overlay rather than a set of directions per overlay, because that
 * is one integer instead of a collection and every question anyone asks of it — "is this face
 * covered", "add this face" — is one bitwise operation. {@link Direction#get3DDataValue()} numbers
 * the faces 0 to 5 and is stable, so the mask is safe to write to disk.
 *
 * <p>Immutable, like the composition it sits beside: {@link #with} returns a new coating rather than
 * changing this one, and returns null when there was nothing to add, which is how callers know a
 * click did nothing.
 *
 * @param faces overlay to the mask of faces it covers; an overlay present with a mask of zero is
 *              treated as absent
 */
public record Coating(Map<Overlay, Integer> faces) {

    /** Every face: the mask an overlay covering a whole block carries. */
    public static final int ALL_FACES = 0b111111;

    /** Nothing on the block at all. */
    public static final Coating NONE = new Coating(Map.of());

    public Coating {
        // Insertion order is kept rather than copied into an unordered immutable map: it decides the
        // order overlays are drawn in, and two overlays on one face should stack the same way every
        // time rather than flickering between orderings on a reload.
        faces = Collections.unmodifiableMap(new LinkedHashMap<>(faces));
    }

    /** The bit standing for one face. */
    public static int bit(Direction face) {
        return 1 << face.get3DDataValue();
    }

    public static boolean covers(int mask, Direction face) {
        return (mask & bit(face)) != 0;
    }

    /** An overlay over the whole block — what a hand-placed or a migrated one starts as. */
    public static Coating everywhere(Overlay overlay) {
        return new Coating(Map.of(overlay, ALL_FACES));
    }

    public boolean isEmpty() {
        return faces.isEmpty();
    }

    /** The faces this overlay covers, or zero if it is not on the block. */
    public int facesOf(Overlay overlay) {
        return faces.getOrDefault(overlay, 0);
    }

    /**
     * This coating with one face scraped clean of everything, or null if it already was.
     *
     * <p>Every overlay at once rather than a named one: scraping is scraping, and a face you can see
     * is bare should <i>be</i> bare. An overlay left with no faces drops out entirely, so a scraped
     * block is byte-identical to one that was never coated.
     */
    @Nullable
    public Coating without(Direction face) {
        Map<Overlay, Integer> left = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<Overlay, Integer> entry : faces.entrySet()) {
            int after = entry.getValue() & ~bit(face);
            changed |= after != entry.getValue();
            if (after != 0) {
                left.put(entry.getKey(), after);
            }
        }
        return changed ? new Coating(left) : null;
    }

    /**
     * This coating with one more face covered by an overlay, or null if it already was.
     *
     * <p>Null rather than an equal coating so a caller can tell "nothing happened" from "something
     * happened" without comparing — which is what stops a click consuming an item for no effect.
     */
    @Nullable
    public Coating with(Overlay overlay, Direction face) {
        int before = facesOf(overlay);
        int after = before | bit(face);
        if (after == before) {
            return null;
        }
        Map<Overlay, Integer> grown = new LinkedHashMap<>(faces);
        // Anything in the same family leaves this face as this one arrives. Grass cannot be both
        // partial and full in one place; see Overlay for why exclusivity lives on the overlay.
        if (overlay.family() != null) {
            grown.entrySet().removeIf(entry -> {
                if (entry.getKey().equals(overlay)
                        || !overlay.family().equals(entry.getKey().family())) {
                    return false;
                }
                int left = entry.getValue() & ~bit(face);
                entry.setValue(left);
                return left == 0;
            });
        }
        grown.put(overlay, after);
        return new Coating(grown);
    }
}
