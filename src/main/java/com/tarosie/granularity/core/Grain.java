package com.tarosie.granularity.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One named material: what it is, what colour it is, and where it occurs.
 *
 * <p>This replaces the (class, colour-index) pair the lattice used. Design §3's sixteen-dye grid was
 * the right scaffold to build against and is not the right shape for real materials — granite should
 * be granite-pink, not the nearest dye, and not every colour needs filling. A material therefore
 * carries its own RGB.
 *
 * <p>Everything downstream is unaffected: sprites are still greyscale, colour still arrives by tint,
 * and a block's rock slots still average. The arithmetic never cared where a tint came from.
 *
 * <p>The id is its index in {@link Grains} and is what a {@link Composition} slot actually stores —
 * O(1) to look up, one byte to keep, and stable as long as the roster only appends.
 *
 * <h2>A grain is an item that already exists</h2>
 * The grain for iron ore is vanilla's raw iron; the grain for a diamond is the diamond. Only the
 * rocks need items of our own, because vanilla has no granite chunk. Binding to existing items
 * removes a parallel set of near-duplicates and lets the mod interoperate with recipes that are
 * already out there.
 *
 * <p>Registration therefore takes an item, a hex colour and a class — {@code register(rubyItem,
 * 0x9B111E, GEM)} is the whole of adding ruby.
 *
 * @param id        index in the registry; part of the world
 * @param name      registry name, lowercase, e.g. {@code granite}
 * @param clazz     which slot class this occupies
 * @param tint      the material's own colour, 0xRRGGBB, before any muting
 * @param itemId    the item this grain is, as a namespaced id — vanilla's where one fits
 * @param families  the bedrock families it occurs in; empty means "any", used by the colourless
 *                  classes
 */
public record Grain(int id, String name, GrainClass clazz, int tint, String itemId, Set<BedrockType> families) {

    public Grain {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Material needs a name");
        }
        // Namespaced, in Minecraft's own style, and for Minecraft's own reason: two mods will each
        // want to call their rock "slate", and only the namespace keeps them apart. Held as a String
        // rather than a ResourceLocation because this package is deliberately free of Minecraft types
        // -- itemId above is a namespaced String for the same reason -- so the composition function
        // and everything under it stays testable without a running game.
        int colon = name.indexOf(':');
        if (colon <= 0 || colon == name.length() - 1 || name.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException(
                    "Grain name must be namespaced, e.g. granularity:granite — got: " + name);
        }
        if (tint < 0 || tint > 0xFFFFFF) {
            throw new IllegalArgumentException(name + " tint out of range: " + Integer.toHexString(tint));
        }
        families = families.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.allOf(BedrockType.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(families));
    }

    static Grain of(int id, String name, GrainClass clazz, int tint, String itemId, BedrockType... families) {
        return new Grain(id, name, clazz, tint, itemId,
                families.length == 0 ? EnumSet.noneOf(BedrockType.class) : EnumSet.of(families[0], families));
    }

    public boolean occursIn(BedrockType family) {
        return families.contains(family);
    }

    /**
     * The single family this grain belongs to.
     *
     * <p>Meaningful for stone, which is partitioned one family per grain. A mineral occurring in
     * several returns the first, which is not useful — callers wanting a mineral's range should ask
     * {@link #occursIn}.
     */
    public BedrockType family() {
        return families.iterator().next();
    }

    /** The muted tint stone is rendered with. */
    public int rockTint() {
        return LatticeColour.rockTint(tint);
    }

    /** The saturated tint a mineral is rendered with, so it reads out of the rock around it. */
    public int mineralTint() {
        return LatticeColour.oreTint(tint);
    }

    /** The tint appropriate to this material's class. */
    public int renderTint() {
        return clazz.isMineral() && clazz != GrainClass.ROCK ? mineralTint() : rockTint();
    }

    /** The mod this grain came from, e.g. {@code granularity}. */
    public String namespace() {
        return name.substring(0, name.indexOf(':'));
    }

    /** The bare name within that mod, e.g. {@code granite}. */
    public String path() {
        return name.substring(name.indexOf(':') + 1);
    }

    /**
     * Translation key, e.g. {@code material.granularity.granite}.
     *
     * <p>Namespace in the middle, the way vanilla writes {@code block.minecraft.stone} and the way
     * this mod already writes {@code overlay.granularity.moss} — so another mod's grain names itself
     * from its own language file without collision.
     */
    public String translationKey() {
        return "material." + namespace() + "." + path();
    }

    /**
     * A stable 64-bit hash of the name, for choosing which grain wins a region.
     *
     * <p>FNV-1a over the UTF-8 of the full namespaced name: it depends on nothing but the name, so
     * two installs with different mods — or a different mod load order — agree on it exactly. That
     * is the property {@code Grains.pick} is built on, and the reason the id is not used here; an id
     * is a position in a list and moves when the list does.
     */
    public long nameHash() {
        long hash = 0xCBF29CE484222325L;
        for (int i = 0; i < name.length(); i++) {
            hash ^= name.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }
}
