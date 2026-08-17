package com.tarosie.granularity.core;

import java.util.EnumSet;
import java.util.Set;

/**
 * A grain as data describes it, before the roster has given it an id.
 *
 * <p>The difference between this and {@link Grain} is exactly the id: a {@code Grain} is something
 * the roster has admitted and a {@code Composition} slot can hold, and a {@code GrainSpec} is a
 * request to admit one. Keeping them apart means a definition can be parsed, reported on and
 * rejected without ever occupying an id — and an id, once handed out, is permanent (see
 * {@link Grains#applyDataGrains}).
 *
 * <p>No Minecraft types, like everything in this package, so a datapack loader can be tested by
 * handing this straight to {@link Grains} with no game running.
 *
 * <h2>The item must already exist</h2>
 * {@code itemId} names an item some mod has <b>registered</b> — vanilla's raw iron, another mod's
 * ruby, or one of ours. A data grain cannot mint an item of its own: item registration closes during
 * mod construction, and datapacks load long after. That is not really a limitation, because a grain
 * <i>is</i> an item that already exists (see {@link Grain}); it does mean a mod adding a new
 * <b>rock</b> must ship a chunk item, since there is no vanilla granite chunk to point at.
 *
 * @param name     namespaced, e.g. {@code mymod:ruby} — see {@link Grain} for why
 * @param clazz    which slot class this occupies, and so where the world will put it
 * @param tint     0xRRGGBB, or null to average the item's own texture via {@link TextureTint}
 * @param itemId   the already-registered item this grain is
 * @param families the bedrock families it occurs in; empty means all of them
 */
public record GrainSpec(String name, GrainClass clazz, Integer tint, String itemId,
                        Set<BedrockType> families) {

    public GrainSpec {
        families = families == null ? Set.of() : Set.copyOf(families);
    }

    public GrainSpec(String name, GrainClass clazz, Integer tint, String itemId,
                     BedrockType... families) {
        this(name, clazz, tint, itemId,
                families.length == 0 ? Set.of() : EnumSet.of(families[0], families));
    }

    /**
     * Resolves this into a real grain at the given id.
     *
     * @throws IllegalArgumentException if the name is malformed, or if the tint was left to the
     *                                  texture and the texture cannot be read
     */
    Grain toGrain(int id) {
        int resolved = tint != null ? tint : TextureTint.averageOf(itemId);
        return new Grain(id, name, clazz, resolved, itemId,
                families.isEmpty() ? EnumSet.noneOf(BedrockType.class) : EnumSet.copyOf(families));
    }
}
