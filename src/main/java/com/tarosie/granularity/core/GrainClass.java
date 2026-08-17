package com.tarosie.granularity.core;

/**
 * What can occupy one of a block's nine slots.
 *
 * <p>Seven of these carry a colour from the lattice; two do not. Sand, silt and clay are among the
 * coloured ones, which was not obvious from design §3 — it lists the lattice as covering rock, ore,
 * precious ore and gem. But a sandstone block is nine sand piles, so sand needs varieties in the
 * same way rock does, and topsoil is a mix of sand, silt and clay and so takes its colour from
 * whatever it is made of. See {@code docs/MATERIAL_MODEL.md}.
 *
 * <p>{@link #AIR} is not "nothing went here". In the voxel representation a free slot <i>is</i> the
 * porosity (findings §6.1), so an air slot is load-bearing: it is what water later infiltrates, it
 * is what makes a block break faster, and past a threshold it is what makes the block gravel rather
 * than stone. Neither air nor water ever becomes an item, so a block with either drops fewer than
 * nine objects.
 */
public enum GrainClass {
    /** A free slot — the porosity. Yields no item. */
    AIR(false, false),
    ROCK(true, true),
    ORE(true, true),
    PRECIOUS_ORE(true, true),
    GEM(true, true),
    SAND(true, true),
    SILT(true, true),
    CLAY(true, true),
    /** What makes topsoil topsoil rather than sediment, and what plants root in. Barely populated. */
    ORGANIC(true, true),
    /** Things with no business being there — an echo shard in ordinary dirt. Deliberately empty. */
    TREASURE(true, true),
    /** Invisible, and the player can never obtain it (design §6). Manifests as a water layer. */
    WATER(false, false);

    private final boolean coloured;
    private final boolean obtainable;

    GrainClass(boolean coloured, boolean obtainable) {
        this.coloured = coloured;
        this.obtainable = obtainable;
    }

    /** True for the classes that span the colour lattice. */
    public boolean isColoured() {
        return coloured;
    }

    /**
     * True if a slot of this class yields something the player can hold.
     *
     * <p>Air and water do not: air is porosity, and a water drop appears as a layer of water rather
     * than as an item. This is why a break can yield fewer than nine objects.
     */
    public boolean isObtainable() {
        return obtainable;
    }

    /** True for anything that occupies a slot — i.e. everything but {@link #AIR}. */
    public boolean isMaterial() {
        return this != AIR;
    }

    /**
     * True for the classes that make up soil in the USDA texture-triangle sense (design §6). Loam,
     * sandy clay and silty loam are positions in this space rather than blocks someone invented,
     * and a block that is mostly these is topsoil.
     */
    public boolean isSoil() {
        return this == SAND || this == SILT || this == CLAY;
    }

    /** True for the mineral classes — the ones that make stone, and gravel when air-heavy. */
    public boolean isMineral() {
        return this == ROCK || this == ORE || this == PRECIOUS_ORE || this == GEM;
    }

    /** True for the classes a bedrock family mediates: which ores its country can hold. */
    public boolean isFamilyMediated() {
        return this == ROCK || this == ORE || this == PRECIOUS_ORE || this == GEM;
    }
}
