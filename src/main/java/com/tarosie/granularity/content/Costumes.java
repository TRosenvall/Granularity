package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.Finish;
import com.tarosie.granularity.core.Grain;
import com.tarosie.granularity.core.Grains;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * What a block is wearing, part by part.
 *
 * <p>A <b>costume</b>, and the word carries the whole design. This mod's rule is that a block shows
 * what it is made of; transmogrification suspends that on purpose, so a wall built for its properties
 * can be made to read as plain cobblestone. What keeps it honest is where the disguise is kept: here,
 * on the block entity, never on the item. A block picked up is just a block, and breaking one hands
 * every donor back intact.
 *
 * <p>Nothing outside the renderer and the drops may read this. It changes what is drawn and not one
 * thing more — no hardness, no tool, no drop, no recipe. Keeping it apart from the composition is
 * what makes that structural rather than a promise, and it is what lets {@code docs/ALLOYS.md} treat
 * material and appearance as genuinely independent axes.
 *
 * <h2>Kept out of the block entities</h2>
 * Four different classes need this — {@link CompositeBlockEntity} and the three that had to extend
 * vanilla's container block entities instead — and none of them can share a superclass. That is the
 * same bind {@link Dyes} and {@link Coating} are in, so this follows their idiom exactly: the state
 * and its save and load live here, and each block entity holds one field and two one-line calls.
 * Writing the NBT out four times is how a fifth block ends up saving a costume it never loads.
 *
 * @param worn what each dressed region is wearing; a region absent is a region undressed
 */
public record Costumes(Map<Region, Dressed> worn) {

    /**
     * One part's disguise: what it looks like, and what colour it is.
     *
     * <p>Two slots rather than one because the two questions are genuinely separate. A donor block
     * brings its own colour with it, which is usually what you want — but a block assembled for its
     * <i>properties</i> lands on a colour nobody chose, and being able to say "this texture, that
     * rock's colour" is the difference between transmog as decoration and transmog as the thing that
     * makes tuning a composition liveable. See {@code docs/ALLOYS.md}.
     *
     * <p>So: the costume supplies the texture, the colorant overrides the colour. A multicoloured
     * cobble plus a slate chunk reads as slate cobblestone.
     *
     * @param costume  a full block whose faces are lent to ours, or empty
     * @param colorant a grain item — chunk, ore, gem, ingot — whose colour is taken, or empty
     */
    public record Dressed(ItemStack costume, ItemStack colorant) {

        public static final Dressed NONE = new Dressed(ItemStack.EMPTY, ItemStack.EMPTY);

        public Dressed {
            costume = costume.isEmpty() ? ItemStack.EMPTY : costume.copyWithCount(1);
            colorant = colorant.isEmpty() ? ItemStack.EMPTY : colorant.copyWithCount(1);
        }

        public boolean isEmpty() {
            return costume.isEmpty() && colorant.isEmpty();
        }

        /**
         * The composition this part is drawn from, or null when it is drawn from a texture instead.
         *
         * <p>A colorant wins over the costume's own stones, and it wins by being a <i>uniform</i>
         * composition of its grain — which is what "slate cobblestone" means. Uniform rather than a
         * tint laid over the top, because uniform is a real composition and everything downstream
         * already knows how to draw one: the layer count collapses to one, the matrix takes slate's
         * muted colour, and no new path is needed anywhere.
         */
        @Nullable
        public Composition composition() {
            Composition lent = textureComposition();
            if (lent != null) {
                return lent;
            }
            Grain grain = grainOf(colorant);
            return grain == null ? null : Composition.uniform(grain.id());
        }

        /**
         * The composition the <b>texture</b> comes from, which is the costume's and only the
         * costume's.
         *
         * <p>Separate from {@link #composition()} because a colorant must not decide whether a block
         * is drawn from stones or from a borrowed sprite. Folding the two together meant that adding
         * a colorant to a <i>foreign</i> costume made the block look like one of ours again — the
         * donor's texture simply vanished the moment you tinted it, which is the opposite of "texture
         * from the block, colour from the chunk".
         */
        @Nullable
        public Composition textureComposition() {
            return costume.get(GranularityComponents.COMPOSITION.get());
        }

        /**
         * Whether the costume is a log, which this mod draws with a greyscale sprite of its own.
         *
         * <p>A vanilla log cannot be recoloured — a block tint multiplies, and dark oak times grey is
         * darker dark oak, never grey. So a log donor lends the *idea* of a log and Granularity's own
         * `log_side` and `log_top` supply the surface, which takes any colour asked of it. See
         * {@code tools/gen_wood.py}; wood will need this on its own account shortly after alloys.
         */
        public boolean isLog() {
            return costume.is(net.minecraft.tags.ItemTags.LOGS);
        }

        /** The finish this part wears. Only a costume has one; a colorant is a colour and nothing else. */
        public Finish finish() {
            return Finishes.of(costume);
        }

        List<ItemStack> items() {
            List<ItemStack> both = new ArrayList<>(2);
            if (!costume.isEmpty()) {
                both.add(costume.copy());
            }
            if (!colorant.isEmpty()) {
                both.add(colorant.copy());
            }
            return both;
        }
    }

    /** Nothing worn at all. */
    public static final Costumes NONE = new Costumes(Map.of());

    private static final String COSTUME_KEY = "Block";
    private static final String COLORANT_KEY = "Colour";

    public Costumes {
        Map<Region, Dressed> copy = new EnumMap<>(Region.class);
        worn.forEach((region, dressed) -> {
            if (!dressed.isEmpty()) {
                copy.put(region, dressed);
            }
        });
        worn = Collections.unmodifiableMap(copy);
    }

    /**
     * The grain an item is, or null if it is not one.
     *
     * <p>Asked of the roster rather than of a list of our own, so "any ingot, rock chunk or ore" is
     * one lookup and a third-party mod's ruby is a valid colorant the day it is registered. The same
     * rule the rest of the mod follows — see {@code docs/MATERIAL_ROSTER.md}.
     */
    @Nullable
    public static Grain grainOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        Grain held = Grains.byItem(id.toString());
        if (held != null) {
            return held;
        }
        // An ingot is the same material as its raw form, and the roster keys on the raw: iron is
        // `minecraft:raw_iron`, so an iron *ingot* matched nothing and every colorant slot and the
        // stonecutter's blade rejected every ingot in the game.
        //
        // Substituting by name rather than keeping a table, because `x_ingot` names material `x` in
        // essentially every mod — the same convention metalTint already leans on. Asked in the
        // ingot's own namespace first so another mod's metal resolves against that mod's grain.
        String path = id.getPath();
        for (String suffix : new String[] {"_ingot", "_nugget", "_block"}) {
            if (!path.endsWith(suffix)) {
                continue;
            }
            String bare = path.substring(0, path.length() - suffix.length());
            Grain named = Grains.find(id.getNamespace() + ":" + bare);
            return named != null ? named : Grains.find(Grains.NAMESPACE + ":" + bare);
        }
        return null;
    }

    public boolean isEmpty() {
        return worn.isEmpty();
    }

    /** What this region is wearing. */
    public Dressed on(Region region) {
        return worn.getOrDefault(region, Dressed.NONE);
    }

    /**
     * What the part carrying this tint index is wearing.
     *
     * <p>Asked by tint rather than by region because that is what a quad and a colour lookup have in
     * hand. Regions offered by one block never overlap — a block offers {@link Region#ALL} or a set of
     * specific parts, never both — so the first match is the only match.
     */
    public Dressed covering(int tintIndex) {
        for (Map.Entry<Region, Dressed> entry : worn.entrySet()) {
            if (entry.getKey().covers(tintIndex)) {
                return entry.getValue();
            }
        }
        return Dressed.NONE;
    }

    public Costumes withCostume(Region region, ItemStack stack) {
        return with(region, new Dressed(stack, on(region).colorant()));
    }

    public Costumes withColorant(Region region, ItemStack stack) {
        return with(region, new Dressed(on(region).costume(), stack));
    }

    private Costumes with(Region region, Dressed dressed) {
        // Built from the key type and filled, not copied with `new EnumMap<>(worn)`: that constructor
        // infers the key type from the first entry and throws outright on an empty map, and `worn` is
        // wrapped for immutability so it is never itself an EnumMap to copy from. Dressing the first
        // region of any block therefore threw, every time.
        Map<Region, Dressed> next = new EnumMap<>(Region.class);
        next.putAll(worn);
        if (dressed.isEmpty()) {
            next.remove(region);
        } else {
            next.put(region, dressed);
        }
        return new Costumes(next);
    }

    /**
     * The composition the block's primary stone is wearing, or null when it is bare or wearing a
     * foreign block with no colorant.
     *
     * <p>Here rather than on a block entity because all four of them need it and only one of them had
     * it. A furnace kept its costume, drew nothing, and looked like a feature that did not work on
     * furnaces — the wrapper was correctly leaving the stone alone on the understanding the
     * composition had been swapped upstream, and for a furnace upstream never ran.
     */
    @Nullable
    public Composition lentComposition() {
        return covering(0).composition();
    }

    /** The finish the primary stone's costume lends. */
    public Finish lentFinish() {
        return covering(0).finish();
    }

    /** Every donor, for handing back when the block breaks. Costumes and colorants both. */
    public List<ItemStack> donors() {
        List<ItemStack> all = new ArrayList<>();
        worn.values().forEach(dressed -> all.addAll(dressed.items()));
        return all;
    }

    /**
     * Reads a costume set, and the forms that came before regions and colorants existed.
     *
     * <p>A tag holding {@code id} is the oldest — one stack meaning the whole block — and becomes
     * {@link Region#ALL}, which is what it always meant. A region holding a bare stack is the form
     * from before colorants, and becomes that region's costume.
     */
    public static Costumes load(HolderLookup.Provider registries, CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return NONE;
        }
        CompoundTag stored = tag.getCompound(key);
        if (stored.contains("id")) {
            return new Costumes(Map.of(Region.ALL, new Dressed(
                    ItemStack.parse(registries, stored).orElse(ItemStack.EMPTY), ItemStack.EMPTY)));
        }
        Map<Region, Dressed> worn = new EnumMap<>(Region.class);
        for (String name : stored.getAllKeys()) {
            CompoundTag part = stored.getCompound(name);
            Dressed dressed = part.contains("id")
                    ? new Dressed(ItemStack.parse(registries, part).orElse(ItemStack.EMPTY),
                            ItemStack.EMPTY)
                    : new Dressed(read(registries, part, COSTUME_KEY),
                            read(registries, part, COLORANT_KEY));
            if (!dressed.isEmpty()) {
                worn.put(Region.byId(name), dressed);
            }
        }
        return new Costumes(worn);
    }

    private static ItemStack read(HolderLookup.Provider registries, CompoundTag tag, String key) {
        return tag.contains(key)
                ? ItemStack.parse(registries, tag.getCompound(key)).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
    }

    /** Writes nothing at all when nothing is worn, so an undressed block stays byte-identical. */
    public void save(HolderLookup.Provider registries, CompoundTag tag, String key) {
        if (isEmpty()) {
            return;
        }
        CompoundTag stored = new CompoundTag();
        worn.forEach((region, dressed) -> {
            CompoundTag part = new CompoundTag();
            if (!dressed.costume().isEmpty()) {
                part.put(COSTUME_KEY, dressed.costume().save(registries));
            }
            if (!dressed.colorant().isEmpty()) {
                part.put(COLORANT_KEY, dressed.colorant().save(registries));
            }
            stored.put(region.id(), part);
        });
        tag.put(key, stored);
    }
}
