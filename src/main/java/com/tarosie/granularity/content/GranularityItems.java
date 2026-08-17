package com.tarosie.granularity.content;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.core.GrainClass;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The objects a natural block breaks into (design §2).
 *
 * <p>One item per {@link GrainClass} that a player can hold. Colour rides along in
 * {@link GranularityComponents#MATERIAL_COLOUR} until Phase 3 registers the full lattice.
 *
 * <p>Two classes deliberately have no item:
 * <ul>
 *   <li>{@link GrainClass#EMPTY} — a free slot is porosity, not a thing to pick up.</li>
 *   <li>{@link GrainClass#WATER} — design §6 is explicit that the water drop is invisible and
 *       the player can never obtain it. Water slots release a partial water level instead, which
 *       needs the fluid layer and so waits for Phase 6.</li>
 * </ul>
 *
 * <p><b>Stack size is vanilla, deliberately.</b> Nine objects per block is a real inventory load,
 * and raising the limit is the obvious relief — but it also hides whether the friction is a problem
 * worth solving, and IMPLEMENTATION_PLAN §6 asks for this to be decided rather than defaulted into.
 * Ship on 64 and let play answer it.
 */
public final class GranularityItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Granularity.MODID);



    /**
     * The items grains need that vanilla has no equivalent for.
     *
     * <p>Everything with a vanilla counterpart uses it — iron is {@code minecraft:raw_iron}, diamond
     * is {@code minecraft:diamond}. These are the leftovers: sixteen rock chunks, because there is
     * no vanilla granite chunk, and two raw metals vanilla does not have. Registered from the
     * roster rather than listed by hand, so adding a grain with a {@code granularity:} item id is
     * all it takes.
     */
    public static final Map<String, DeferredItem<Item>> GRAIN_ITEMS = new java.util.LinkedHashMap<>();

    static {
        for (com.tarosie.granularity.core.Grain grain : com.tarosie.granularity.core.Grains.all()) {
            String id = grain.itemId();
            if (!id.startsWith("granularity:")) {
                continue;
            }
            String path = id.substring("granularity:".length());
            GRAIN_ITEMS.put(path, ITEMS.registerSimpleItem(path));
        }
    }

    static {
    }

    /**
     * Crafted composites are placeable; natural stone is not (design §2).
     *
     * <p>One registered item each, not one per composition. Registering an entry per possible mix
     * would be item bloat with no upside — the creative tab shows a generic block with a random
     * composition, and a player wanting a specific mix crafts it or uses a command.
     */
    public static final DeferredItem<CompositeBlockItem> COBBLESTONE = ITEMS.registerItem("cobblestone",
            props -> new CompositeBlockItem(GranularityBlocks.COBBLESTONE.get(), props));
    public static final DeferredItem<CompositeBlockItem> GRAVEL = ITEMS.registerItem("gravel",
            props -> new CompositeBlockItem(GranularityBlocks.GRAVEL.get(), props));
    public static final DeferredItem<CompositeBlockItem> ORE_BLOCK = ITEMS.registerItem("ore_block",
            props -> new CompositeBlockItem(GranularityBlocks.ORE_BLOCK.get(), props));
    public static final DeferredItem<CompositeBlockItem> ALLOY_BLOCK = ITEMS.registerItem("alloy_block",
            props -> new CompositeBlockItem(GranularityBlocks.ALLOY_BLOCK.get(), props));
    public static final DeferredItem<CompositeBlockItem> COBBLESTONE_SLAB = ITEMS.registerItem("cobblestone_slab",
            props -> new CompositeBlockItem(GranularityBlocks.COBBLESTONE_SLAB.get(), props));

    public static final DeferredItem<CompositeBlockItem> COBBLESTONE_STAIRS = ITEMS.registerItem("cobblestone_stairs",
            props -> new CompositeBlockItem(GranularityBlocks.COBBLESTONE_STAIRS.get(), props));

    public static final DeferredItem<CompositeBlockItem> COBBLESTONE_WALL = ITEMS.registerItem("cobblestone_wall",
            props -> new CompositeBlockItem(GranularityBlocks.COBBLESTONE_WALL.get(), props));

    public static final DeferredItem<CompositeBlockItem> LEVER = ITEMS.registerItem("lever",
            props -> new CompositeBlockItem(GranularityBlocks.LEVER.get(), props));

    public static final DeferredItem<CompositeBlockItem> FURNACE = ITEMS.registerItem("furnace",
            props -> new CompositeBlockItem(GranularityBlocks.FURNACE.get(), props));

    public static final DeferredItem<CompositeBlockItem> DISPENSER = ITEMS.registerItem("dispenser",
            props -> new CompositeBlockItem(GranularityBlocks.DISPENSER.get(), props));

    public static final DeferredItem<CompositeBlockItem> DROPPER = ITEMS.registerItem("dropper",
            props -> new CompositeBlockItem(GranularityBlocks.DROPPER.get(), props));

    public static final DeferredItem<CompositeBlockItem> OBSERVER = ITEMS.registerItem("observer",
            props -> new CompositeBlockItem(GranularityBlocks.OBSERVER.get(), props));

    public static final DeferredItem<CompositeBlockItem> PISTON = ITEMS.registerItem("piston",
            props -> new CompositeBlockItem(GranularityBlocks.PISTON.get(), props));

    public static final DeferredItem<CompositeBlockItem> STONECUTTER = ITEMS.registerItem("stonecutter",
            props -> new CompositeBlockItem(GranularityBlocks.STONECUTTER.get(), props));

    /**
     * A bar of every metal the roster names that vanilla has no ingot for.
     *
     * <p>Iron, copper and gold already smelt into vanilla ingots, so ours are the leftovers — the
     * same division {@link #GRAIN_ITEMS} makes for chunks. Registered from the roster rather than
     * listed by hand, so a metal added to {@code Grains} gets a bar without touching this file.
     *
     * <p>One greyscale sprite serves all of them, coloured at draw time by
     * {@code CompositeBlockColour.metalTint} — the economy design §5 applies to blocks, applied to
     * items.
     */
    public static final Map<String, DeferredItem<Item>> INGOT_ITEMS = new java.util.LinkedHashMap<>();

    static {
        for (com.tarosie.granularity.core.Grain grain : com.tarosie.granularity.core.Grains.all()) {
            if (!grain.itemId().startsWith("granularity:")
                    || (grain.clazz() != GrainClass.ORE
                            && grain.clazz() != GrainClass.PRECIOUS_ORE)) {
                continue;
            }
            // path(), not name(): the registry path must be `silver_ingot`, and a grain's name is
            // namespaced now, so name() would ask for an item called `granularity:silver_ingot`
            // inside the granularity namespace.
            INGOT_ITEMS.put(grain.path(), ITEMS.registerSimpleItem(grain.path() + "_ingot"));
        }
    }

    /**
     * Takes a crafted block apart into the grains it was made from.
     *
     * <p>The counterpart to combining. A pickaxe picks a crafted block up whole — mining a wall you
     * built should give the wall back — and the hammer is how you say "no, I want the pieces".
     *
     * <p>It works on the block in the world rather than in a crafting grid because a mixed
     * cobblestone comes apart into up to nine different stacks, and a crafting recipe can only
     * produce one result. Breaking drops them as entities, which has no such limit.
     */
    public static final DeferredItem<Item> HAMMER = ITEMS.registerItem("hammer",
            props -> new HammerItem(props.durability(250)));

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, Granularity.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> OBJECTS_TAB =
            TABS.register("objects", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.granularity.objects"))
                    .withTabsBefore(CreativeModeTabs.NATURAL_BLOCKS)
                    .icon(() -> GRAIN_ITEMS.get("granite_chunk").get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        // Every grain we register an item for. The vanilla-backed grains -- raw
                        // iron, diamond, coal -- are already in their own tabs, so listing them
                        // again here would just be noise.
                        output.accept(HAMMER.get());
                        for (DeferredItem<Item> ingot : INGOT_ITEMS.values()) {
                            output.accept(ingot.get());
                        }
                        for (DeferredItem<Item> grainItem : GRAIN_ITEMS.values()) {
                            output.accept(grainItem.get());
                        }
                        // One generic entry each, with a random composition: see the note above.
                        output.accept(randomComposite(COBBLESTONE.get()));
                        output.accept(randomComposite(GRAVEL.get()));
                        // Smooth stone is the same item, smelted -- see core/Finish.
                        output.accept(smoothed(randomComposite(COBBLESTONE.get())));
                        output.accept(randomComposite(ORE_BLOCK.get()));
                        output.accept(randomComposite(ALLOY_BLOCK.get()));
                        output.accept(randomComposite(COBBLESTONE_SLAB.get()));
                        output.accept(randomComposite(COBBLESTONE_STAIRS.get()));
                        output.accept(randomComposite(COBBLESTONE_WALL.get()));
                        output.accept(randomComposite(LEVER.get()));
                        output.accept(randomComposite(FURNACE.get()));
                        output.accept(randomComposite(DISPENSER.get()));
                        output.accept(randomComposite(DROPPER.get()));
                        output.accept(randomComposite(OBSERVER.get()));
                        output.accept(randomComposite(PISTON.get()));
                        // Two stones, because that is what a stonecutter is: the bench below the
                        // wooden rail is one rock and the working top above it another. A single
                        // composition would render perfectly well — the second falls back to the
                        // first — and would quietly hide the whole feature from anyone who only ever
                        // meets the block through this tab.
                        output.accept(twoStoned(randomComposite(STONECUTTER.get())));
                        // The mossy forms are the same four items carrying a flag, so the tab shows
                        // them the same way it shows a composition: as a state of the one entry.
                        output.accept(mossy(randomComposite(COBBLESTONE.get())));
                        output.accept(mossy(randomComposite(COBBLESTONE_SLAB.get())));
                        output.accept(mossy(randomComposite(COBBLESTONE_STAIRS.get())));
                        output.accept(mossy(randomComposite(COBBLESTONE_WALL.get())));
                    })
                    .build());

    private GranularityItems() {
    }

    /**
     * A composite with a random composition, for the creative tab.
     *
     * <p>Random rather than fixed so the tab shows what these blocks are: things whose colour and
     * contents depend on what went into them, not a fixed palette to pick from.
     */
    private static net.minecraft.world.item.ItemStack randomComposite(net.minecraft.world.item.Item item) {
        java.util.List<com.tarosie.granularity.core.Grain> stones =
                com.tarosie.granularity.core.Grains.ofClass(com.tarosie.granularity.core.GrainClass.ROCK);
        java.util.Random random = new java.util.Random();
        int[] ids = new int[com.tarosie.granularity.core.Composition.SLOTS];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = stones.get(random.nextInt(stones.size())).id();
        }
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
        stack.set(GranularityComponents.COMPOSITION.get(),
                com.tarosie.granularity.core.Composition.of(ids));
        return stack;
    }

    /**
     * Gives a stack a <i>second</i>, independently drawn stone — see {@code UPPER_COMPOSITION}.
     *
     * <p>Drawn fresh rather than derived from the first, so the two halves genuinely differ and the
     * tab shows a stonecutter for what it is. It is possible for the draw to land on the same rocks
     * twice, which is fine: a player can build one out of two blocks of the same stone too.
     */
    private static net.minecraft.world.item.ItemStack twoStoned(net.minecraft.world.item.ItemStack stack) {
        stack.set(GranularityComponents.UPPER_COMPOSITION.get(),
                randomComposite(stack.getItem()).get(GranularityComponents.COMPOSITION.get()));
        return stack;
    }

    /** The same block, smelted — which is all "smooth stone" is now. See {@code core/Finish}. */
    private static net.minecraft.world.item.ItemStack smoothed(net.minecraft.world.item.ItemStack stack) {
        Finishes.apply(stack, com.tarosie.granularity.core.Finish.SMOOTH);
        return stack;
    }

    private static net.minecraft.world.item.ItemStack mossy(net.minecraft.world.item.ItemStack stack) {
        Moss.apply(stack, Coating.everywhere(GranularityOverlays.MOSS.get()));
        return stack;
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        TABS.register(modEventBus);
    }
}
