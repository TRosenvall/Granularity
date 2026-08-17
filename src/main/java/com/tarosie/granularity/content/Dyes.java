package com.tarosie.granularity.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * What colour a block's mortar has been dyed, face by face.
 *
 * <p><b>Dye only ever touches the matrix.</b> The nine stones stay exactly the colour of what was dug
 * — that is the whole point of a composite, and a dye that could recolour them would turn every block
 * into a paint chip. The mortar between them is the part that has no material identity of its own, so
 * it is the part a builder is allowed to choose.
 *
 * <p>Per face for the same reason overlays are: a wall whose south side is red and whose top is grey
 * is a thing people build, and there is no cost to allowing it once the renderer is already deciding
 * quads one at a time. See {@code OverlayBakedModel} for how a face's colour actually reaches the
 * screen — vanilla's tint API cannot be asked about a face, so the wrapper rewrites the tint index
 * instead.
 *
 * <p>Immutable and null-on-no-change, the same contract {@link Coating} has: {@link #with} hands back
 * null when the face already carries that colour, which is how a caller knows not to eat the dye.
 *
 * @param faces the faces that carry a colour, as 0xRRGGBB; an absent face is undyed and shows the
 *              average of the grains instead
 */
public record Dyes(Map<Direction, Integer> faces) {

    /** An undyed block. Byte-identical on disk to one from before dye existed. */
    public static final Dyes NONE = new Dyes(Map.of());

    /** How an undyed face is written in the fixed-length array form. Colours are 24-bit, so -1 is free. */
    private static final int UNDYED = -1;

    private static final int FACE_COUNT = 6;

    public Dyes {
        // An EnumMap so iteration order is face order rather than hash order, and so lookups are an
        // array index. Wrapped unmodifiable because this is shared with the render thread.
        EnumMap<Direction, Integer> copy = new EnumMap<>(Direction.class);
        copy.putAll(faces);
        faces = Collections.unmodifiableMap(copy);
    }

    /** One colour over the whole block — what a hand-dyed block used to be, and what one migrates to. */
    public static Dyes everywhere(int colour) {
        EnumMap<Direction, Integer> all = new EnumMap<>(Direction.class);
        for (Direction face : Direction.values()) {
            all.put(face, colour);
        }
        return new Dyes(all);
    }

    public boolean isEmpty() {
        return faces.isEmpty();
    }

    /** The colour on this face, or null if it is undyed and should show the average. */
    @Nullable
    public Integer on(Direction face) {
        return faces.get(face);
    }

    /**
     * The one colour this block is, or null if it is not one colour.
     *
     * <p>For the places that can only show a single tint: a flat item sprite has no faces to speak
     * of, so a block dyed the same colour all over can still be drawn honestly, and a part-dyed one
     * falls back to the average rather than picking a face arbitrarily.
     */
    @Nullable
    public Integer uniform() {
        if (faces.size() != FACE_COUNT) {
            return null;
        }
        Integer first = null;
        for (Integer colour : faces.values()) {
            if (first == null) {
                first = colour;
            } else if (!first.equals(colour)) {
                return null;
            }
        }
        return first;
    }

    /** This, with one more face painted — or null if that face was already that colour. */
    @Nullable
    public Dyes with(int colour, Direction face) {
        Integer before = faces.get(face);
        if (before != null && before == colour) {
            return null;
        }
        EnumMap<Direction, Integer> painted = new EnumMap<>(Direction.class);
        painted.putAll(faces);
        painted.put(face, colour);
        return new Dyes(painted);
    }

    /** The six faces as colours, undyed ones as {@value #UNDYED}. The form that goes to disk. */
    public int[] toArray() {
        int[] packed = new int[FACE_COUNT];
        for (int i = 0; i < FACE_COUNT; i++) {
            Integer colour = faces.get(Direction.from3DDataValue(i));
            packed[i] = colour == null ? UNDYED : colour;
        }
        return packed;
    }

    /** The inverse of {@link #toArray}, tolerant of a short or long array so a bad save cannot crash. */
    public static Dyes of(int[] packed) {
        EnumMap<Direction, Integer> faces = new EnumMap<>(Direction.class);
        for (int i = 0; i < Math.min(FACE_COUNT, packed.length); i++) {
            if (packed[i] != UNDYED) {
                faces.put(Direction.from3DDataValue(i), packed[i]);
            }
        }
        return faces.isEmpty() ? NONE : new Dyes(faces);
    }

    /**
     * The item component's codec, reading either shape.
     *
     * <p>Six per-face colours, or the single colour that came before faces — which meant the whole
     * block, so it reads as all six. Without that second branch every dyed stack already in an
     * inventory would fail to decode and quietly come back grey.
     *
     * <p>It lives here rather than beside the component it serves so that it can be tested: touching
     * {@link GranularityComponents} class-loads its deferred registrations, which need a bootstrapped
     * game. This class is a plain record with no registry statics, so a test can reach it.
     */
    public static final com.mojang.serialization.Codec<List<Integer>> CODEC =
            com.mojang.serialization.Codec.either(
                            com.mojang.serialization.Codec.INT.listOf(),
                            com.mojang.serialization.Codec.INT)
                    .xmap(either -> either.map(java.util.function.Function.identity(),
                                    single -> Collections.nCopies(FACE_COUNT, single)),
                            com.mojang.datafixers.util.Either::left);

    /** The item component's form: the same six slots, as a list, because a codec for one already exists. */
    public List<Integer> toList() {
        List<Integer> packed = new ArrayList<>(FACE_COUNT);
        for (int colour : toArray()) {
            packed.add(colour);
        }
        return packed;
    }

    public static Dyes fromList(List<Integer> packed) {
        int[] array = new int[FACE_COUNT];
        java.util.Arrays.fill(array, UNDYED);
        for (int i = 0; i < Math.min(FACE_COUNT, packed.size()); i++) {
            Integer colour = packed.get(i);
            array[i] = colour == null ? UNDYED : colour;
        }
        return of(array);
    }

    /**
     * Reads a block entity's dye, migrating a block dyed before faces existed.
     *
     * <p>{@code MatrixTint} was one integer for the whole block, so it reads as all six faces —
     * exactly what it meant. Checked second, so a block saved since is never mistaken for an old one.
     */
    public static Dyes load(CompoundTag tag, String key, String legacyKey) {
        if (tag.contains(key, Tag.TAG_INT_ARRAY)) {
            return of(tag.getIntArray(key));
        }
        if (tag.contains(legacyKey, Tag.TAG_INT)) {
            return everywhere(tag.getInt(legacyKey));
        }
        return NONE;
    }

    /** Reads a half that never had a legacy form of its own — a double slab's upper. */
    public static Dyes load(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_INT_ARRAY) ? of(tag.getIntArray(key)) : NONE;
    }

    public void save(CompoundTag tag, String key) {
        if (!isEmpty()) {
            tag.putIntArray(key, toArray());
        }
    }

    /** The dye an item form carries. Absent means undyed. */
    public static Dyes of(ItemStack stack) {
        List<Integer> packed = stack.get(GranularityComponents.DYES.get());
        return packed == null ? NONE : fromList(packed);
    }

    /** Writes dye onto an item form, leaving an undyed block clean of the component. */
    public static void apply(ItemStack stack, Dyes dyes) {
        if (!dyes.isEmpty()) {
            stack.set(GranularityComponents.DYES.get(), dyes.toList());
        }
    }
}
