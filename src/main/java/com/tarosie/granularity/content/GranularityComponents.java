package com.tarosie.granularity.content;

import com.mojang.serialization.Codec;
import com.tarosie.granularity.Granularity;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Data components carried by dropped objects. */
public final class GranularityComponents {

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Granularity.MODID);

    // A `grain` component once carried which grain a dropped object was, as an id, back when one
    // registered item per class covered the whole roster and the component supplied the tint and the
    // name. Phase 3 took the other branch of that decision — a registered item per grain — and
    // `GrainItemColour` now reads the item's own id, so nothing had written the component for a long
    // time. It is gone rather than merely unused: an id is only meaningful beside the roster that
    // issued it, which is exactly the property CompositionCodecs.STREAM_CODEC was just changed to
    // stop relying on, and a dormant one is an invitation to reintroduce the bug.

    /**
     * The nine slots a crafted block or its item remembers.
     *
     * <p>Only crafted things carry this. Natural blocks derive their composition from position
     * (design §4) and store nothing — that asymmetry is the whole reason the mod is affordable.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.tarosie.granularity.core.Composition>>
            COMPOSITION = COMPONENTS.register("composition",
                    () -> DataComponentType.<com.tarosie.granularity.core.Composition>builder()
                            .persistent(CompositionCodecs.CODEC)
                            .networkSynchronized(CompositionCodecs.STREAM_CODEC)
                            .build());

    /**
     * The <b>second</b> composition, for a block built out of two stones at once.
     *
     * <p>The stonecutter is the first: its bench is divided by a wooden rail, the stone below the rail
     * comes from one block and the stone above it from another, and the recipe places them left and
     * right. In the world that second composition already had a home — {@code CompositeBlockEntity}
     * has carried an {@code upper} since double slabs, and {@code CompositeBlockColour} already
     * resolves tint indices 10–19 against it — so this is only the missing half of the round trip.
     *
     * <p>And it is genuinely required, not a convenience. Without it, a crafted stonecutter would
     * carry one stone in the hand and show two once placed. {@code docs/RENDERING.md} records that
     * exact class of bug five times over: a block that looks one way in the world and another in
     * inventory is the failure mode nothing logs and only a person can see.
     *
     * <p>Absent means "one stone", which is every other composite, and the renderer already falls back
     * to the first composition when it is.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.tarosie.granularity.core.Composition>>
            UPPER_COMPOSITION = COMPONENTS.register("upper_composition",
                    () -> DataComponentType.<com.tarosie.granularity.core.Composition>builder()
                            .persistent(CompositionCodecs.CODEC)
                            .networkSynchronized(CompositionCodecs.STREAM_CODEC)
                            .build());

    /**
     * What has been done to the block — smelted, and later polished or cut.
     *
     * <p>Deliberately <b>not</b> a separate block. Smooth stone was one, and a finish that is a block
     * multiplies against every form: a smooth slab, a smooth stair, a smooth wall, each its own
     * registration, model and recipe set. As a component it multiplies against nothing.
     *
     * <p>It is also the half of a block's identity that cannot be recomputed. Whether a block is
     * loose or solid follows from its composition's porosity; what it counts as follows from its
     * majority class. Smelting changes neither — the same nine grains go in and come out — so the only
     * record that it happened is this. See {@link com.tarosie.granularity.core.Finish}.
     *
     * <p>Absent means cobbled, so nothing that predates finishes needs migrating.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.tarosie.granularity.core.Finish>>
            FINISH = COMPONENTS.register("finish",
                    () -> DataComponentType.<com.tarosie.granularity.core.Finish>builder()
                            .persistent(Finishes.CODEC)
                            .networkSynchronized(Finishes.STREAM_CODEC)
                            .build());

    /**
     * Explicit colours for a crafted block's matrix, per face, overriding the average.
     *
     * <p>Averaging is honest but not always what a builder wants: nine different grains average to
     * mud, and no amount of saturation can rescue a colour that genuinely has no hue. Dyeing gives
     * the player direct control over the one part of the block that is not a specific stone, while
     * the nine stones keep showing exactly what went into it.
     *
     * <p>Six slots, in {@link net.minecraft.core.Direction#get3DDataValue()} order, an undyed face
     * written as -1 — see {@link Dyes}. A list rather than a map keyed by face because the length is
     * fixed and a list of ints already has both codecs it needs. {@link Dyes#CODEC} also reads the
     * single colour this component used to be.
     *
     * <p><b>The registry id stays {@code matrix_tint}</b> even though the component no longer is one.
     * Renaming it would orphan the component on every dyed stack already in a world, and the codec
     * below reads the old single integer anyway; a rename would buy nothing but a broken inventory.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<java.util.List<Integer>>>
            DYES = COMPONENTS.register("matrix_tint",
                    () -> DataComponentType.<java.util.List<Integer>>builder()
                            .persistent(Dyes.CODEC)
                            .networkSynchronized(ByteBufCodecs.INT.apply(ByteBufCodecs.list()))
                            .build());

    /**
     * What is growing on, spattered across or settled upon a crafted block's item form.
     *
     * <p>The hand-side half of {@link Overlay}: the block entity carries overlays in the world, this
     * carries them in the inventory, and each writes the other on placing and breaking. Stored as
     * ids so a stack survives the mod that defined an overlay being removed.
     *
     * <p>Absent means bare, so a cobblestone with nothing on it stays byte-identical to one from
     * before overlays existed.
     */
    /**
     * Reads either shape: a map of id to face mask, or the flat list of ids that came before faces.
     *
     * <p>Without the second branch every stack in an existing inventory would fail to decode and
     * quietly lose its moss. A flat list meant "the whole block", so it reads as all six faces.
     */
    private static final Codec<java.util.Map<net.minecraft.resources.ResourceLocation, Integer>> OVERLAY_CODEC =
            Codec.either(
                    Codec.unboundedMap(net.minecraft.resources.ResourceLocation.CODEC, Codec.INT),
                    net.minecraft.resources.ResourceLocation.CODEC.listOf())
                    .xmap(either -> either.map(java.util.function.Function.identity(), flat -> {
                        java.util.Map<net.minecraft.resources.ResourceLocation, Integer> faces =
                                new java.util.LinkedHashMap<>();
                        for (net.minecraft.resources.ResourceLocation id : flat) {
                            faces.put(id, Coating.ALL_FACES);
                        }
                        return faces;
                    }), com.mojang.datafixers.util.Either::left);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<java.util.Map<net.minecraft.resources.ResourceLocation, Integer>>>
            OVERLAYS = COMPONENTS.register("overlays",
                    () -> DataComponentType.<java.util.Map<net.minecraft.resources.ResourceLocation, Integer>>builder()
                            .persistent(OVERLAY_CODEC)
                            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.map(
                                    java.util.LinkedHashMap::new,
                                    net.minecraft.resources.ResourceLocation.STREAM_CODEC,
                                    ByteBufCodecs.VAR_INT))
                            .build());

    /**
     * The timber a block was built from, where a block is built from timber as well as stone.
     *
     * <p>A piston is the case: its plate is wood, not rock, and tinting it with the stone's colour
     * made a spruce piston's plate look like slate. Stored as the plank block's id rather than as a
     * colour, so the wood keeps its identity — a colour could be drawn from it, but not the other way
     * round, and naming a piston after its timber later should not need a migration.
     *
     * <p>Absent means the block has no timber in it, which is every block here but the piston.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<net.minecraft.resources.ResourceLocation>>
            WOOD = COMPONENTS.register("wood",
                    () -> DataComponentType.<net.minecraft.resources.ResourceLocation>builder()
                            .persistent(net.minecraft.resources.ResourceLocation.CODEC)
                            .networkSynchronized(net.minecraft.resources.ResourceLocation.STREAM_CODEC)
                            .build());

    /**
     * The metal a block was built from, where a block is built from metal as well as stone.
     *
     * <p>A piston again: its head is a wooden ram held together with metal brackets and braces, and
     * those fittings are neither the stone nor the timber. Stored as the ingot's item id, the same
     * way {@link #WOOD} stores a plank's, so the metal keeps its identity rather than being reduced
     * to a colour at craft time.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<net.minecraft.resources.ResourceLocation>>
            METAL = COMPONENTS.register("metal",
                    () -> DataComponentType.<net.minecraft.resources.ResourceLocation>builder()
                            .persistent(net.minecraft.resources.ResourceLocation.CODEC)
                            .networkSynchronized(net.minecraft.resources.ResourceLocation.STREAM_CODEC)
                            .build());

    private GranularityComponents() {
    }

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
