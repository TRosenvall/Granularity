package com.tarosie.granularity.core;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The grain registry — everything that can occupy one of a block's nine slots.
 *
 * <p>Populated from code below, but built as <b>data</b>: a grain is a {@link Grain} record with no
 * behaviour, and everything downstream — drops, tints, overlays, which minerals a bedrock family
 * admits — reads the registry rather than a hardcoded list. Moving population to datapack JSON so
 * other mods can contribute their own ores is a mechanical follow-up, not a redesign. Slots store
 * grain <i>ids</i> for exactly that reason.
 *
 * <h2>The roster</h2>
 * <b>Minerals</b> are deliberately few and real. Which family each occurs in is real geology, and it
 * is what makes design §4's prospecting promise work: bedrock tells you the rock family, the family
 * tells you which minerals are possible. Sedimentary country holds no precious ore and no gems at
 * all, so igneous and metamorphic ground is worth travelling for.
 *
 * <p><b>Stones</b> are named rocks with the colours real rocks have. Granite, diorite and andesite
 * match vanilla's on purpose: once these generate, vanilla's generation of them can be switched off.
 *
 * <h2>Deliberately absent</h2>
 * Nether and End grains, and redstone. Nothing unique to those dimensions is modelled yet, and
 * redstone has plans of its own. {@link GrainClass#ORGANIC} and {@link GrainClass#TREASURE} exist
 * with almost nothing in them — the model can express them, so they can be filled in later without
 * a structural change.
 *
 * <p><b>Ids are declaration order and are part of the world.</b> Append; do not insert or reorder.
 */
public final class Grains {

    // ---- The permanent record ------------------------------------------------------------------
    // These three outlive any one roster: they say what ids have ever been handed out and what the
    // code asked for. Everything below them is derived and rebuilt wholesale by reindex().

    /**
     * Every grain this run has ever known, indexed by id.
     *
     * <p>Append-only, and entries are <b>replaced rather than removed</b>. A live
     * {@link Composition} holds ids, not grains, so an id that changed meaning would silently turn
     * the stone inside placed blocks into something else.
     */
    private static final List<Grain> BY_ID = new ArrayList<>();

    /** Which ids are in the roster now. A retired grain stays resolvable by id but is not offered. */
    private static final BitSet ACTIVE = new BitSet();

    /** Name to the id it was first given. <b>An id is never reissued to a different name.</b> */
    private static final Map<String, Integer> IDS = new HashMap<>();

    /** Grains the code registered, which data may not replace — see {@link #applyDataGrains}. */
    private static final Map<String, Grain> CODE_GRAINS = new LinkedHashMap<>();

    /** Names currently supplied by data rather than by code, so a later apply knows what to retire. */
    private static final Set<String> DATA_NAMES = new LinkedHashSet<>();

    /** The last batch applied, sorted, so an identical one can be recognised and skipped. */
    private static List<GrainSpec> LAST_APPLIED = List.of();

    // ---- Derived views, rebuilt by reindex() ----------------------------------------------------
    // Replaced wholesale rather than mutated in place, and volatile, because they are read from the
    // worldgen and chunk-meshing threads while a datapack load may be rebuilding them. Clearing and
    // repopulating a live map would let a reader see a family with no stone in it and put air in a
    // mountain. Swapping a finished table means a reader gets the old roster or the new one, never
    // half of either.

    private static volatile Map<String, Grain> BY_NAME = Map.of();
    private static volatile Map<String, Grain> BY_ITEM = Map.of();
    private static volatile Map<GrainClass, List<Grain>> BY_CLASS = Map.of();
    private static volatile Map<BedrockType, Map<GrainClass, List<Grain>>> BY_FAMILY = Map.of();

    /** {@link Grain#nameHash()} per grain id — see {@link #pick}, which reads it per block. */
    private static volatile long[] NAME_HASHES = new long[0];

    // ---- The two that yield no item ------------------------------------------------------------
    // Air is first so its id is 0: a default-initialised slot array is a block of nothing.
    public static final Grain AIR = plain("air", GrainClass.AIR, 0x000000, "minecraft:air");
    public static final Grain WATER = plain("water", GrainClass.WATER, 0x3F76E4, "minecraft:water_bucket");

    // ---- Stones ---------------------------------------------------------------------------------
    public static final Grain GRANITE = rock("granite", 0x9C6B5A, "granularity:granite_chunk", BedrockType.IGNEOUS);
    public static final Grain DIORITE = rock("diorite", 0xBFBFBD, "granularity:diorite_chunk", BedrockType.IGNEOUS);
    public static final Grain ANDESITE = rock("andesite", 0x8B8B8B, "granularity:andesite_chunk", BedrockType.IGNEOUS);
    public static final Grain BASALT = rock("basalt", 0x4A4A4E, "granularity:basalt_chunk", BedrockType.IGNEOUS);
    public static final Grain GABBRO = rock("gabbro", 0x50554A, "granularity:gabbro_chunk", BedrockType.IGNEOUS);

    public static final Grain MARBLE = rock("marble", 0xE8E4DC, "granularity:marble_chunk", BedrockType.METAMORPHIC);
    public static final Grain SLATE = rock("slate", 0x4E5A63, "granularity:slate_chunk", BedrockType.METAMORPHIC);
    public static final Grain SCHIST = rock("schist", 0x7C8676, "granularity:schist_chunk", BedrockType.METAMORPHIC);
    public static final Grain GNEISS = rock("gneiss", 0x9A8E86, "granularity:gneiss_chunk", BedrockType.METAMORPHIC);
    public static final Grain QUARTZITE = rock("quartzite", 0xD6C8C0, "granularity:quartzite_chunk", BedrockType.METAMORPHIC);

    public static final Grain SANDSTONE = rock("sandstone", 0xD9C89A, "granularity:sandstone_chunk", BedrockType.SEDIMENTARY);
    public static final Grain LIMESTONE = rock("limestone", 0xCFC9B4, "granularity:limestone_chunk", BedrockType.SEDIMENTARY);
    public static final Grain SHALE = rock("shale", 0x5C544A, "granularity:shale_chunk", BedrockType.SEDIMENTARY);
    public static final Grain MUDSTONE = rock("mudstone", 0x7A6551, "granularity:mudstone_chunk", BedrockType.SEDIMENTARY);
    public static final Grain CHALK = rock("chalk", 0xF0EDE4, "granularity:chalk_chunk", BedrockType.SEDIMENTARY);
    public static final Grain CONGLOMERATE = rock("conglomerate", 0x998A76, "granularity:conglomerate_chunk", BedrockType.SEDIMENTARY);

    // ---- Ores -----------------------------------------------------------------------------------
    // Banded iron is sedimentary, magmatic iron igneous — iron is the one ore in every country.
    public static final Grain IRON = mineral("iron", GrainClass.ORE, 0xD8AF93, "minecraft:raw_iron",
            BedrockType.IGNEOUS, BedrockType.METAMORPHIC, BedrockType.SEDIMENTARY);
    // Porphyry copper is igneous, sediment-hosted copper sedimentary. Green, as malachite.
    public static final Grain COPPER = mineral("copper", GrainClass.ORE, 0x4FBA98, "minecraft:raw_copper",
            BedrockType.IGNEOUS, BedrockType.SEDIMENTARY);
    // Mississippi-Valley-type zinc is sedimentary; skarn zinc metamorphic.
    public static final Grain ZINC = mineral("zinc", GrainClass.ORE, 0xC7B27A, "granularity:raw_zinc",
            BedrockType.METAMORPHIC, BedrockType.SEDIMENTARY);
    // Coal is organic in origin but behaves as an ore, and coal measures are sedimentary basins.
    // Treated as ore for now; the organic story comes later.
    public static final Grain COAL = mineral("coal", GrainClass.ORE, 0x2B2B30, "minecraft:coal",
            BedrockType.SEDIMENTARY);

    // ---- Precious ores --------------------------------------------------------------------------
    // Orogenic gold and epithermal silver sit in igneous and metamorphic country, not in sediments.
    public static final Grain GOLD = mineral("gold", GrainClass.PRECIOUS_ORE, 0xF2C846, "minecraft:raw_gold",
            BedrockType.IGNEOUS, BedrockType.METAMORPHIC);
    public static final Grain SILVER = mineral("silver", GrainClass.PRECIOUS_ORE, 0xD8DCE0, "granularity:raw_silver",
            BedrockType.IGNEOUS, BedrockType.METAMORPHIC);

    // ---- Gems -----------------------------------------------------------------------------------
    // Lazurite is contact-metamorphosed limestone — metamorphic only, and the reason to go looking.
    public static final Grain LAPIS = mineral("lapis", GrainClass.GEM, 0x2B4FB8, "minecraft:lapis_lazuli", BedrockType.METAMORPHIC);
    // Diamond arrives in kimberlite, which is igneous.
    public static final Grain DIAMOND = mineral("diamond", GrainClass.GEM, 0x6FE3DC, "minecraft:diamond", BedrockType.IGNEOUS);
    // Beryl is schist- and pegmatite-hosted.
    public static final Grain EMERALD = mineral("emerald", GrainClass.GEM, 0x2FBF57, "minecraft:emerald",
            BedrockType.IGNEOUS, BedrockType.METAMORPHIC);
    // Vanilla only has quartz in the Nether, but hydrothermal quartz veins are the commonest vein
    // mineral there is. Overworld quartz as a gem; nether quartz becomes its own gem later.
    public static final Grain QUARTZ = mineral("quartz", GrainClass.GEM, 0xE6E0D8, "minecraft:quartz",
            BedrockType.IGNEOUS, BedrockType.METAMORPHIC);

    // ---- Soil -----------------------------------------------------------------------------------
    // Not family-constrained: sediment travels.
    public static final Grain QUARTZ_SAND = plain("quartz_sand", GrainClass.SAND, 0xDCCBA0, "minecraft:sand");
    public static final Grain IRON_SAND = plain("iron_sand", GrainClass.SAND, 0x8A6A52, "minecraft:red_sand");
    public static final Grain LOESS = plain("loess", GrainClass.SILT, 0xBFA980, "granularity:loess");
    public static final Grain GLACIAL_SILT = plain("glacial_silt", GrainClass.SILT, 0x9AA0A2, "granularity:glacial_silt");
    public static final Grain KAOLIN = plain("kaolin", GrainClass.CLAY, 0xD9D2C8, "minecraft:clay_ball");
    public static final Grain RED_CLAY = plain("red_clay", GrainClass.CLAY, 0xA05B44, "granularity:red_clay");

    // ---- Organic ---------------------------------------------------------------------------------
    // One entry, on purpose. Humus is what separates topsoil from sediment and what plants root in;
    // the rest of the organic story waits.
    public static final Grain HUMUS = plain("humus", GrainClass.ORGANIC, 0x4A3B28, "granularity:humus");

    // ---- Treasure --------------------------------------------------------------------------------
    // Deliberately empty. The class exists so a slot can hold something with no business being
    // there — an echo shard in ordinary dirt — without a structural change when that arrives.

    // ---- Appended after the fact -----------------------------------------------------------------
    // These two are stones and belong up with the stones. They are down here because **a grain's id
    // is its position in this list** (see register), and a Composition slot stores that id — so
    // inserting a rock beside its cousins renumbers every grain after it and silently rewrites the
    // stone in every crafted block already in a world. Appending is the only safe edit.
    //
    // Both fill gaps left by suppressing the vanilla blocks: see docs/BLOCK_COVERAGE.md §3b.
    // Colours are the vanilla texture's own average, nudged a few points brighter, which is how
    // granite, diorite and andesite above were derived.

    // Consolidated volcanic ash — igneous by origin, whatever it looks like.
    public static final Grain TUFF = rock("tuff", 0x6F7069, "granularity:tuff_chunk", BedrockType.IGNEOUS);
    // A chemical precipitate: flowstone, travertine, vein calcite. Sedimentary, and the pale end of it.
    public static final Grain CALCITE = rock("calcite", 0xE2E3DF, "granularity:calcite_chunk", BedrockType.SEDIMENTARY);

    static {
        reindex();
    }

    /**
     * Rebuilds every derived view from {@link #BY_ID} and {@link #ACTIVE}.
     *
     * <p>This used to be a bare static block, which was correct while the roster was closed and
     * fatal the moment it opened: another mod's {@code register} call triggers our class init — so
     * the index is built from <i>our</i> grains — and then adds theirs to a table nothing rebuilds.
     * Their stone would exist, be craftable and never once generate.
     *
     * <p>It rebuilds wholesale rather than adding incrementally because a data grain can now
     * <i>replace</i> one already installed, changing its class or its item — and an incremental add
     * has no way to withdraw what the previous version put in {@link #BY_CLASS} and
     * {@link #BY_ITEM}. Doing it in full is O(roster) per registration and so O(n²) across startup,
     * which for a few hundred grains registered once is nothing at all.
     */
    private static void reindex() {
        Map<String, Grain> byName = new HashMap<>();
        Map<String, Grain> byItem = new HashMap<>();
        Map<GrainClass, List<Grain>> byClass = new EnumMap<>(GrainClass.class);
        Map<BedrockType, Map<GrainClass, List<Grain>>> byFamily = new EnumMap<>(BedrockType.class);
        long[] hashes = new long[BY_ID.size()];

        for (Grain grain : BY_ID) {
            if (grain == null) {
                continue;   // an id reserved for a definition that turned out to be unusable
            }
            // Kept for retired grains too, and indexed by id, so the hot path in `pick` is an array
            // read. Hashing a two-dozen-character string per candidate per block cost about 6us a
            // block — enough to push a section rebuild from under 60ms to 84ms. CompositionCostTest
            // caught it.
            hashes[grain.id()] = grain.nameHash();
            if (!ACTIVE.get(grain.id())) {
                continue;
            }
            byName.put(grain.name(), grain);
            // Air and water share no item worth mapping back -- water borrows a bucket so it has an
            // icon, but a bucket is not a water grain and must not be craftable as one.
            if (claimsItem(grain.clazz())) {
                Grain clash = byItem.put(grain.itemId(), grain);
                if (clash != null) {
                    throw new IllegalStateException("Item " + grain.itemId() + " is claimed by both "
                            + clash.name() + " and " + grain.name());
                }
            }
            byClass.computeIfAbsent(grain.clazz(), ignored -> new ArrayList<>()).add(grain);
        }
        byClass.replaceAll((clazz, grains) -> Collections.unmodifiableList(grains));
        for (BedrockType family : BedrockType.values()) {
            Map<GrainClass, List<Grain>> perClass = new EnumMap<>(GrainClass.class);
            for (GrainClass clazz : GrainClass.values()) {
                List<Grain> admitted = new ArrayList<>();
                for (Grain grain : byClass.getOrDefault(clazz, List.of())) {
                    if (grain.occursIn(family)) {
                        admitted.add(grain);
                    }
                }
                perClass.put(clazz, Collections.unmodifiableList(admitted));
            }
            byFamily.put(family, Collections.unmodifiableMap(perClass));
        }

        // Published last, and hashes first: a reader that has just picked up the new family table
        // must find a hash for every grain in it.
        NAME_HASHES = hashes;
        BY_NAME = Collections.unmodifiableMap(byName);
        BY_ITEM = Collections.unmodifiableMap(byItem);
        BY_CLASS = Collections.unmodifiableMap(byClass);
        BY_FAMILY = Collections.unmodifiableMap(byFamily);
    }

    private Grains() {
    }

    /** This mod's own namespace, applied to the roster below so its lines stay readable. */
    public static final String NAMESPACE = "granularity";

    private static Grain rock(String name, int tint, String itemId, BedrockType family) {
        return register(NAMESPACE + ":" + name, GrainClass.ROCK, tint, itemId, family);
    }

    private static Grain mineral(String name, GrainClass clazz, int tint, String itemId,
                                 BedrockType... families) {
        return register(NAMESPACE + ":" + name, clazz, tint, itemId, families);
    }

    private static Grain plain(String name, GrainClass clazz, int tint, String itemId) {
        return register(NAMESPACE + ":" + name, clazz, tint, itemId);
    }

    /**
     * Adds a grain. <b>This is the API another mod calls</b>, and the whole of it.
     *
     * <p>{@code register("mymod:ruby", GEM, 0x9B111E, "mymod:ruby", METAMORPHIC)} is a complete
     * addition: the grain is placed by the composition function wherever metamorphic country admits
     * a gem, tinted, drawn, dropped, hammered and craftable, with nothing to configure in worldgen.
     * Declaring <i>what it is and where it belongs</i> is the entire contract — {@code families} is
     * the "where", and everything downstream asks {@link #admitted} rather than naming grains.
     *
     * <p>Two rules make that safe for a third party. The name must be <b>namespaced</b>, so two mods
     * may both add a "slate". And which grain wins a region is decided by {@link #pick}, which hashes
     * that name — so a grain claims only its own share of the world, and installing another mod does
     * not move anybody else's stone.
     *
     * <p>Call it during mod construction, before a world loads. The roster is read by the composition
     * function on every block, so it must be complete before generation starts and must not change
     * afterwards.
     */
    /**
     * Adds a grain, taking its colour from the item's own texture.
     *
     * <p>The short form, and the one to reach for: {@code register("mymod:ruby", GEM, "mymod:ruby",
     * METAMORPHIC)}. A material already has a picture, and the average of that picture is what the
     * stone around it should look like — see {@link TextureTint}, which also explains why the bytes
     * come from the jar rather than the texture atlas.
     *
     * <p>Use the overload with an explicit tint when the art does not speak for the material: an ore
     * drawn mostly as grey rock with a fleck of colour averages to grey, and a grain backed by a
     * <b>vanilla</b> item must always state its colour, because vanilla textures are not present on a
     * dedicated server.
     */
    public static Grain register(String name, GrainClass clazz, String itemId,
                                 BedrockType... families) {
        return register(name, clazz, TextureTint.averageOf(itemId), itemId, families);
    }

    public static Grain register(String name, GrainClass clazz, int tint, String itemId,
                                 BedrockType... families) {
        if (CODE_GRAINS.containsKey(name)) {
            throw new IllegalStateException("Duplicate grain: " + name);
        }
        Grain grain = Grain.of(idFor(name), name, clazz, tint, itemId, families);
        CODE_GRAINS.put(name, grain);
        install(grain);
        reindex();
        return grain;
    }

    /**
     * Replaces the set of grains supplied by data — the whole set, not a delta.
     *
     * <p>Datapacks are reloadable, so there is no "remove one" call: the loader hands over everything
     * it found and this works out the difference. A definition that has gone away leaves its grain
     * <b>retired</b> — struck from every list the world generator, the recipes and the name lookup
     * consult, but still resolvable by {@link #byId}, because blocks made of it may already exist.
     *
     * <h2>Additions only</h2>
     * A data grain may not take a name the code has registered. Letting a pack retune
     * {@code granularity:granite} sounds harmless and is not the same feature as adding one: a
     * definition can also change a grain's <i>class</i>, and {@code granularity:air} redefined as a
     * rock would make every block in the world solid. Colour overrides, if they are ever wanted, can
     * be a narrow thing that only touches the tint.
     *
     * <h2>Order</h2>
     * The batch is sorted by name before ids are assigned, so two fresh installs carrying the same
     * packs number their grains identically and a bug found on one reproduces on the other. It is no
     * longer a <i>correctness</i> requirement — since {@code CompositionCodecs} started sending names
     * rather than ids, nothing compares an id across two processes — but reproducibility is worth the
     * one call to sort.
     *
     * <p><b>Call before a world loads.</b> The composition function reads the roster for every block
     * it generates, so a roster that changes underneath a loaded world puts different stone in
     * chunks generated after the change. Rendezvous hashing in {@link #pick} makes that a visible
     * seam rather than a corrupted save, but it is still a seam.
     *
     * @return one message per definition that was refused, for the caller to log; empty means all
     *         were accepted. A refusal here is reported, never thrown, because what is refused at
     *         this level — a name the code already has, an item another grain already claims —
     *         depends on which <i>other</i> mods happen to be installed. Nothing a pack can write
     *         should stop a world from loading, and nothing does; {@code GrainRegistry} explains why
     *         that is safe even for data the world generator depends on.
     */
    public static synchronized List<String> applyDataGrains(Collection<GrainSpec> specs) {
        List<String> problems = new ArrayList<>();

        List<GrainSpec> ordered = new ArrayList<>();
        for (GrainSpec spec : specs) {
            if (spec == null || spec.name() == null || spec.name().isBlank()) {
                problems.add("A grain definition has no name; ignored");
                continue;
            }
            ordered.add(spec);
        }
        ordered.sort(Comparator.comparing(GrainSpec::name));

        if (ordered.equals(LAST_APPLIED)) {
            // Applying the same set twice is the normal case, not an odd one: in single player the
            // integrated server mirrors the registry at world load and the client mirrors it again at
            // login. Re-installing identical grains would be harmless in content and still rebuild
            // every derived table — while chunk meshing and worldgen are reading them. Recognising
            // the repeat means the second pass touches nothing at all.
            return problems;
        }

        // Every item spoken for by a grain that will still be here afterwards, so a clash can be
        // reported against a name rather than blowing up inside reindex().
        Map<String, String> claimedItems = new HashMap<>();
        for (Grain code : CODE_GRAINS.values()) {
            if (claimsItem(code.clazz())) {
                claimedItems.put(code.itemId(), code.name());
            }
        }

        Set<String> retiring = new LinkedHashSet<>(DATA_NAMES);
        DATA_NAMES.clear();
        Set<String> seen = new LinkedHashSet<>();

        for (GrainSpec spec : ordered) {
            String name = spec.name();
            if (CODE_GRAINS.containsKey(name)) {
                problems.add(name + " is defined in code and cannot be redefined by data; ignored");
                continue;
            }
            if (!seen.add(name)) {
                problems.add(name + " is defined twice; the later one is ignored");
                continue;
            }
            String owner = claimedItems.get(spec.itemId());
            if (owner != null && claimsItem(spec.clazz())) {
                problems.add(name + " claims item " + spec.itemId() + ", which already belongs to "
                        + owner + "; ignored");
                continue;
            }
            Grain grain;
            try {
                grain = spec.toGrain(idFor(name));
            } catch (RuntimeException failed) {
                // A malformed name, a tint out of range, or a texture that could not be averaged.
                problems.add(name + " could not be read: " + failed.getMessage());
                continue;
            }
            if (claimsItem(grain.clazz())) {
                claimedItems.put(grain.itemId(), name);
            }
            install(grain);
            DATA_NAMES.add(name);
            retiring.remove(name);
        }

        for (String gone : retiring) {
            ACTIVE.clear(IDS.get(gone));
        }
        LAST_APPLIED = List.copyOf(ordered);
        reindex();
        return problems;
    }

    /**
     * Forgets every data grain <b>and the ids they held</b> — for tests only.
     *
     * <p>{@link #applyDataGrains} deliberately keeps a withdrawn grain's id allocated forever, which
     * is right in a running game and wrong across a test suite: one test applying a pack would leave
     * the roster permanently longer, and a later test that counts grains would pass or fail
     * depending on the order the classes happened to run in. This restores the roster to code alone.
     *
     * <p>Safe only because data ids are always a suffix — every {@link #register} call happens during
     * class initialisation and mod construction, long before any data is applied.
     */
    static void forgetDataGrains() {
        IDS.keySet().removeIf(name -> !CODE_GRAINS.containsKey(name));
        DATA_NAMES.clear();
        LAST_APPLIED = List.of();
        int codeIds = CODE_GRAINS.size();
        while (BY_ID.size() > codeIds) {
            BY_ID.remove(BY_ID.size() - 1);
        }
        for (int id = codeIds; id < ACTIVE.length(); id++) {
            ACTIVE.clear(id);
        }
        reindex();
    }

    /** Whether a grain of this class holds an item id anyone can hand back — see {@link #reindex}. */
    private static boolean claimsItem(GrainClass clazz) {
        return clazz != GrainClass.AIR && clazz != GrainClass.WATER;
    }

    /**
     * The id this name has always had here, allocating one the first time it is seen.
     *
     * <p>Ids are handed out per <b>name</b> and never reissued, so a grain that goes away and comes
     * back on a later reload comes back as the same id. That matters because a {@link Composition}
     * already in memory holds ids: reusing 34 for a different material would turn every placed block
     * holding it into something else, silently and with no way to tell afterwards.
     */
    private static int idFor(String name) {
        Integer existing = IDS.get(name);
        if (existing != null) {
            return existing;
        }
        int id = BY_ID.size();
        if (id > Composition.MAX_GRAIN_ID) {
            throw new IllegalStateException("Grain roster is full at " + Composition.MAX_GRAIN_ID
                    + " entries; cannot add " + name);
        }
        IDS.put(name, id);
        // Reserved here rather than in install(), so that allocating several ids before filling any
        // of them cannot hand the same one out twice.
        BY_ID.add(null);
        return id;
    }

    /** Puts a grain at its id, replacing whatever was there, and marks that id live. */
    private static void install(Grain grain) {
        BY_ID.set(grain.id(), grain);
        ACTIVE.set(grain.id());
    }

    /**
     * The grains registered from code — the part of the roster no data can take away.
     *
     * <p>For working out what an inferred grain must not tread on. Reading the live roster instead
     * would count data grains that this very batch may be about to retire.
     */
    public static Collection<Grain> codeGrains() {
        return Collections.unmodifiableCollection(CODE_GRAINS.values());
    }

    /** The grains currently in the roster, in id order. Excludes any that data has retired. */
    public static List<Grain> all() {
        List<Grain> out = new ArrayList<>(BY_ID.size());
        for (Grain grain : BY_ID) {
            if (grain != null && ACTIVE.get(grain.id())) {
                out.add(grain);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static int count() {
        return BY_ID.size();
    }

    /**
     * The grain an id denotes — <b>including one that data has since retired</b>.
     *
     * <p>Deliberately not filtered by {@link #ACTIVE}. Ids are what a live {@link Composition} holds,
     * so this is the lookup that keeps already-placed blocks meaning what they meant. A retired grain
     * is absent from {@link #all}, from {@link #byName} and from every {@link #admitted} list, so it
     * will never be generated, crafted or offered again — but the cobblestone in a chest that is
     * already made of it still renders and still names itself correctly.
     */
    public static Grain byId(int id) {
        Grain grain = id < 0 || id >= BY_ID.size() ? null : BY_ID.get(id);
        if (grain == null) {
            throw new IllegalArgumentException("Unassigned grain id: " + id);
        }
        return grain;
    }

    /**
     * The grain an item is, or null if it is not a grain.
     *
     * <p>The primary direction now that grains are items: a stack of raw iron <i>is</i> the iron
     * grain, with no component to carry and nothing to keep in sync.
     */
    public static Grain byItem(String itemId) {
        return BY_ITEM.get(itemId);
    }

    /**
     * Every item id {@link #byItem} recognises — the grains a player can actually hold.
     *
     * <p>Exactly the keys of the reverse map, so it cannot drift from what crafting accepts. Air and
     * water are absent for the reason {@link #register} gives.
     */
    public static Set<String> itemIds() {
        return Collections.unmodifiableSet(BY_ITEM.keySet());
    }

    public static Grain byName(String name) {
        Grain grain = BY_NAME.get(name);
        if (grain == null) {
            throw new IllegalArgumentException("No such grain: " + name);
        }
        return grain;
    }

    /**
     * The grain of that name, or null if nothing answers to it.
     *
     * <p>For callers guessing at a name rather than holding one — mapping an ingot back to a metal,
     * or reading a save written when another mod was installed. {@link #byName} throws, which is
     * right when a missing grain is a bug and wrong when it is simply an unknown material.
     */
    public static Grain find(String name) {
        return BY_NAME.get(name);
    }

    /**
     * Which of these grains owns a region — rendezvous hashing, not a modulo.
     *
     * <p>The obvious implementation is {@code candidates.get(hash % candidates.size())}, and it has a
     * property that only shows up once the roster can grow: <b>changing the count re-rolls almost
     * everything</b>. Adding one rock to a family of five moved 83% of that family's regions, because
     * every remainder shifts. Adding two rocks in one afternoon moved 72% of the world.
     *
     * <p>So each candidate scores itself instead — {@code mix64(region ^ nameHash)} — and the highest
     * wins. A newcomer takes only the regions where its own score happens to be highest, about one in
     * n, and leaves every other region exactly as it was. Removing a grain hands its regions back to
     * the runners-up and disturbs nothing else. The score depends on the <b>name</b>, never on a list
     * position, so two installs with different mods or a different load order still agree.
     *
     * <p>Ties are broken by name so the answer is total even in the astronomically unlikely case; a
     * tie broken by list position would have quietly reintroduced load-order dependence.
     */
    public static Grain pick(List<Grain> candidates, long region) {
        Grain best = null;
        long bestScore = 0L;
        for (int i = 0; i < candidates.size(); i++) {
            Grain candidate = candidates.get(i);
            // NAME_HASHES, not candidate.nameHash(): this runs nine times per block and once per
            // candidate, and hashing a two-dozen-character string each time cost about 6us a block —
            // enough to push a section rebuild from under 60ms to 84ms. CompositionCostTest caught it.
            long score = Rng.mix64(region ^ NAME_HASHES[candidate.id()]);
            if (best == null || score > bestScore
                    || (score == bestScore && candidate.name().compareTo(best.name()) < 0)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    public static List<Grain> ofClass(GrainClass clazz) {
        return BY_CLASS.getOrDefault(clazz, List.of());
    }

    /**
     * The grains of a class that a bedrock family admits.
     *
     * <p>Empty is a meaningful answer and the point of the table: sedimentary country returns
     * nothing for {@link GrainClass#GEM}, so there are no gems to be found there at all.
     */
    public static List<Grain> admitted(BedrockType family, GrainClass clazz) {
        return BY_FAMILY.get(family).getOrDefault(clazz, List.of());
    }
}
