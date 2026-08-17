package com.tarosie.granularity.content;

import com.tarosie.granularity.Granularity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Natural blocks. Crafted composites are Phase 4 and are registered separately by design §2. */
public final class GranularityBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Granularity.MODID);

    /**
     * Registered without a {@code BlockItem} on purpose — see {@link NaturalStoneBlock}. Anything
     * that adds one re-couples natural and placed blocks and quietly breaks the exemption pillar.
     */
    public static final DeferredBlock<NaturalStoneBlock> NATURAL_STONE = BLOCKS.register("natural_stone",
            () -> new NaturalStoneBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    /**
     * Crafted cobblestone: nine chunks, assembled.
     *
     * <p>Design §3 gives it the behaviour natural stone does not have — it "shows the constituent
     * colours as distinct rocks" rather than averaging them. Smelting is what averages.
     */
    public static final DeferredBlock<CompositeBlock> COBBLESTONE = BLOCKS.register("cobblestone",
            () -> new CompositeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    // Smooth stone was a block here. It is a *finish* on cobblestone now — see core/Finish — because
    // a finish that is a block multiplies against every form, and because two slabs can only merge in
    // one block space when they are the same block. Its geometry lives on as a model the finish
    // selects; nothing else about it is gone.

    /**
     * Crafted from four grains in any 2x2 — a muddle, so any partition of the texture will do.
     *
     * <p>It falls, like the gravel it is; see {@link CompositeGravelBlock} for how a block that
     * carries data survives the trip down.
     */
    public static final DeferredBlock<CompositeGravelBlock> GRAVEL = BLOCKS.register("gravel",
            () -> new CompositeGravelBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(0.6F)
                    .sound(SoundType.GRAVEL)));

    /** Smelted from a cobblestone holding rock and a mineral: the ore block you originally mined. */
    public static final DeferredBlock<CompositeBlock> ORE_BLOCK = BLOCKS.register("ore_block",
            () -> new CompositeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    /** Smelted from a cobblestone of pure mineral: metal, averaged. */
    public static final DeferredBlock<CompositeBlock> ALLOY_BLOCK = BLOCKS.register("alloy_block",
            () -> new CompositeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    /** Half a block of whatever it was cut from. See {@link CompositeSlabBlock}. */
    public static final DeferredBlock<CompositeSlabBlock> COBBLESTONE_SLAB = BLOCKS.register("cobblestone_slab",
            () -> new CompositeSlabBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    /** Forty variants over three models, all rotated by the blockstate. See {@link CompositeStairBlock}. */
    public static final DeferredBlock<CompositeStairBlock> COBBLESTONE_STAIRS = BLOCKS.register("cobblestone_stairs",
            () -> new CompositeStairBlock(COBBLESTONE.get().defaultBlockState(),
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    /** Multipart, which is what the model rework was for. See {@link CompositeWallBlock}. */
    public static final DeferredBlock<CompositeWallBlock> COBBLESTONE_WALL = BLOCKS.register("cobblestone_wall",
            () -> new CompositeWallBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    /**
     * A lever cut from one grain. The first vanilla block rebuilt on grains — see
     * {@link CompositeLeverBlock}.
     */
    public static final DeferredBlock<CompositeLeverBlock> LEVER = BLOCKS.register("lever",
            () -> new CompositeLeverBlock(BlockBehaviour.Properties.of()
                    .noCollission()
                    .strength(0.5F)
                    .sound(SoundType.STONE)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)));

    /** Eight grains, eight dimples. See {@link CompositeFurnaceBlock}. */
    public static final DeferredBlock<CompositeFurnaceBlock> FURNACE = BLOCKS.register("furnace",
            () -> new CompositeFurnaceBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.5F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> state.getValue(CompositeFurnaceBlock.LIT) ? 13 : 0)));

    /*
     * The four below copy vanilla's properties rather than approximating them, down to the note
     * block instrument: these are redstone parts before they are decoration, and a piston that
     * conducts redstone or an observer that suffocates would be a different component wearing the
     * same texture.
     */

    /** Seven grains, seven dimples. See {@link CompositeDispenserBlock}. */
    public static final DeferredBlock<CompositeDispenserBlock> DISPENSER = BLOCKS.register("dispenser",
            () -> new CompositeDispenserBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .strength(3.5F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    /** The dispenser's cost and its dimpling; only the muzzle differs. */
    public static final DeferredBlock<CompositeDropperBlock> DROPPER = BLOCKS.register("dropper",
            () -> new CompositeDropperBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .strength(3.5F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    /** Six grains across its four stone faces. See {@link CompositeObserverBlock}. */
    public static final DeferredBlock<CompositeObserverBlock> OBSERVER = BLOCKS.register("observer",
            () -> new CompositeObserverBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .strength(3.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()
                    .isRedstoneConductor((state, level, pos) -> false)));

    /**
     * Four grains — the planks, the iron and the redstone are not stone. See
     * {@link CompositePistonBlock}, which has to say two things vanilla only ever says about itself.
     *
     * <p>Vanilla's piston also carries {@code pushReaction(BLOCK)}, and that one property is
     * deliberately <b>not</b> copied. Vanilla never reads it on a piston — {@code isPushable}
     * short-circuits on {@code is(Blocks.PISTON)} long before the push reaction is consulted — so
     * setting it here, where no such short-circuit applies, would make ours immovable outright
     * rather than immovable only while extended. {@code CompositePistonBlock.getPistonPushReaction}
     * states the rule vanilla actually enforces.
     */
    public static final DeferredBlock<CompositePistonBlock> PISTON = BLOCKS.register("piston",
            () -> new CompositePistonBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(1.5F)
                    .sound(SoundType.STONE)
                    .isRedstoneConductor((state, level, pos) -> false)
                    // An extended piston is only twelve deep, so it neither smothers nor blinds
                    // whatever is standing in the four it left behind.
                    .isSuffocating((state, level, pos) -> !state.getValue(CompositePistonBlock.EXTENDED))
                    .isViewBlocking((state, level, pos) -> !state.getValue(CompositePistonBlock.EXTENDED))));

    /**
     * The arm our piston pushes out. Registered without a {@code BlockItem}: you never hold one.
     *
     * <p>Vanilla's properties for the head exactly — it is not mineable, it takes no push reaction of
     * its own, and it is destroyed with its base.
     */
    public static final DeferredBlock<CompositePistonHeadBlock> PISTON_HEAD = BLOCKS.register("piston_head",
            () -> new CompositePistonHeadBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(1.5F)
                    .sound(SoundType.STONE)
                    .noLootTable()
                    .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)));

    /**
     * Three blocks of worked stone and a bar. See {@link CompositeStonecutterBlock}.
     *
     * <p>Vanilla's properties, which for a stonecutter is only its strength and its sound. This one
     * exists because vanilla's had become uncraftable — its recipe asks for {@code minecraft:stone} —
     * and a stonecutter is where every stonework style is cut.
     */
    public static final DeferredBlock<CompositeStonecutterBlock> STONECUTTER =
            BLOCKS.register("stonecutter",
                    () -> new CompositeStonecutterBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(3.5F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    /*
     * There are no mossy_* blocks and no moss property, deliberately. What grows on a block is an
     * {@link Overlay} held by its block entity, so any number of overlays combine for free and other
     * mods can register their own — see {@link Overlay} for the arithmetic that forces this.
     */

    public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE,
                    com.tarosie.granularity.Granularity.MODID);

    /**
     * A second type, because a furnace must extend {@code AbstractFurnaceBlockEntity} and a position
     * holds only one block entity. {@link CompositionHolder} is what keeps them interchangeable.
     */
    public static final java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntityType<CompositeFurnaceBlockEntity>>
            COMPOSITE_FURNACE_ENTITY = BLOCK_ENTITIES.register("composite_furnace",
                    () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                            .of(CompositeFurnaceBlockEntity::new, FURNACE.get())
                            .build(null));

    /**
     * A dispenser and a dropper each need their own type, because vanilla's do: a block entity type
     * lists the blocks it is valid on, and one shared type would let a dropper's entity sit in a
     * dispenser. Ours mirror that split for the same reason.
     */
    public static final java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntityType<CompositeDispenserBlockEntity>>
            COMPOSITE_DISPENSER_ENTITY = BLOCK_ENTITIES.register("composite_dispenser",
                    () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                            .of(CompositeDispenserBlockEntity::new, DISPENSER.get())
                            .build(null));

    public static final java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntityType<CompositeDropperBlockEntity>>
            COMPOSITE_DROPPER_ENTITY = BLOCK_ENTITIES.register("composite_dropper",
                    () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                            .of(CompositeDropperBlockEntity::new, DROPPER.get())
                            .build(null));

    /**
     * The plain composite entity, shared by everything with no vanilla entity of its own.
     *
     * <p>The observer and the piston are on this list rather than on one of their own: neither has a
     * block entity in vanilla, so nothing about them needs more than a composition.
     */
    public static final java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntityType<CompositeBlockEntity>>
            COMPOSITE_BLOCK_ENTITY = BLOCK_ENTITIES.register("composite",
                    () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                            .of(CompositeBlockEntity::new, COBBLESTONE.get(),
                                    GRAVEL.get(), ORE_BLOCK.get(), ALLOY_BLOCK.get(),
                                    COBBLESTONE_SLAB.get(), COBBLESTONE_STAIRS.get(),
                                    COBBLESTONE_WALL.get(), LEVER.get(),
                                    OBSERVER.get(), PISTON.get(), PISTON_HEAD.get(),
                                    STONECUTTER.get())
                            .build(null));

    private GranularityBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
    }
}
