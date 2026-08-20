package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionLayers;
import net.minecraft.core.BlockPos;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * The composition a crafted block remembers.
 *
 * <p>Design §4 forbids block entities on <b>natural</b> stone, and this is not that. §2 is equally
 * explicit the other way: crafted blocks are "the only blocks carrying real data", and they are
 * sparse against world stone, so one block entity each is affordable. A cobblestone made from nine
 * particular chunks has to remember which nine, because nothing about where it sits implies them.
 *
 * <p>The composition is synced to the client because the block is rendered from it.
 */
public class CompositeBlockEntity extends BlockEntity implements CompositionHolder {

    private static final String COMPOSITION_KEY = "Composition";

    /** The whole-block colour that came before per-face dye; read on load, never written. */
    private static final String LEGACY_MATRIX_KEY = "MatrixTint";

    private static final String DYES_KEY = "Dyes";

    private static final String UPPER_DYES_KEY = "UpperDyes";

    private static final String UPPER_KEY = "Upper";

    private static final String TRANSMOG_KEY = "Transmog";
    private static final String FINISH_KEY = "Finish";
    private static final String UPPER_FINISH_KEY = "UpperFinish";

    private static final String WOOD_KEY = "Wood";

    private static final String METAL_KEY = "Metal";

    private static final String STORED_IS_TOP_KEY = "StoredIsTop";

    /**
     * What has been done to this block, as against what it is made of.
     *
     * <p>Beside {@link #composition} rather than derived from it, because it cannot be: smelting
     * leaves the nine grains exactly as they were. See {@link com.tarosie.granularity.core.Finish}.
     */
    private com.tarosie.granularity.core.Finish finish = com.tarosie.granularity.core.Finish.COBBLED;

    /** What has been done to a double slab's upper half. Equals {@link #finish} for everything else. */
    private com.tarosie.granularity.core.Finish upperFinish = com.tarosie.granularity.core.Finish.COBBLED;

    private Composition composition = Composition.uniform(com.tarosie.granularity.core.Grains.ANDESITE.id());
    private CompositionLayers layers;

    /** The reduction of the costume's composition, when the costume is one of our own blocks. */
    private CompositionLayers costumeLayers;

    /** The dyed faces of this block, or of a double slab's lower half. */
    private Dyes dyes = Dyes.NONE;

    /** The dyed faces of a double slab's upper half. Empty for every other block. */
    private Dyes upperDyes = Dyes.NONE;

    /**
     * The top half of a double slab, when its two halves came from different stone.
     *
     * <p>A double slab is two slabs, not one block, so it remembers two compositions. Null for every
     * other block and for a double whose halves happen to match.
     */
    @Nullable
    private Composition upper;

    /** What this block is wearing, part by part. See {@link Costumes}. */
    private Costumes costumes = Costumes.NONE;

    /** Whether the single half this holds is the top one — needed to know which half is free. */
    private boolean storedIsTop;

    private static final String OVERLAYS_KEY = "Overlays";

    private static final String UPPER_OVERLAYS_KEY = "UpperOverlays";

    /**
     * What is growing on this block — or on a double slab's lower half.
     *
     * <p>Overlays live here rather than in the blockstate so that any number of them combine for
     * free and other mods can add their own; see {@link Overlay}. The cost of that choice is that
     * arriving overlay data has to dirty the chunk section by hand, which {@link #refreshClientMesh}
     * already does for composition.
     */
    private Coating overlays = Coating.NONE;

    /** What is growing on a double slab's upper half. Empty for every other block. */
    private Coating upperOverlays = Coating.NONE;

    @Override
    public Coating overlays() {
        return overlays;
    }

    @Override
    public Coating upperOverlays() {
        return upperOverlays;
    }

    /** Sets one half's overlays. {@code upper} is only ever true for a double slab. */
    @Override
    public void setOverlays(boolean upper, Coating value) {
        if (upper) {
            upperOverlays = value;
        } else {
            overlays = value;
        }
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            requestModelDataUpdate();
        }
    }

    @Override
    public Dyes dyes() {
        return dyes;
    }

    @Override
    public Dyes upperDyes() {
        return upperDyes;
    }

    /**
     * Sets one half's dye.
     *
     * <p>The model data update matters as much as the block update: dye now changes <i>which tint
     * index a quad carries</i> rather than only what that index resolves to, so the mesh has to be
     * rebuilt and not merely re-tinted.
     */
    @Override
    public void setDyes(boolean upper, Dyes value) {
        if (upper) {
            upperDyes = value;
        } else {
            dyes = value;
        }
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            requestModelDataUpdate();
        }
    }

    /**
     * The timber this block was built from, for the blocks that have any.
     *
     * <p>Only the piston and its head, and it is not part of the composition: nine grains describe
     * the stone, and a piston's plate is not stone. Kept here rather than in a second block entity
     * because it is one nullable field and every composite already funnels through this class.
     */
    @Nullable
    private net.minecraft.resources.ResourceLocation wood;

    @Nullable
    @Override
    public net.minecraft.resources.ResourceLocation wood() {
        return wood;
    }

    @Override
    public void setWood(@Nullable net.minecraft.resources.ResourceLocation value) {
        this.wood = value;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /** The metal in this block's fittings. See {@link #wood} — same story, different material. */
    @Nullable
    private net.minecraft.resources.ResourceLocation metal;

    @Nullable
    @Override
    public net.minecraft.resources.ResourceLocation metal() {
        return metal;
    }

    @Override
    public void setMetal(@Nullable net.minecraft.resources.ResourceLocation value) {
        this.metal = value;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public CompositeBlockEntity(BlockPos pos, BlockState state) {
        super(GranularityBlocks.COMPOSITE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Composition composition() {
        return composition;
    }

    /** The top half of a double slab, or null when there is only one composition here. */
    @Nullable
    @Override
    public Composition upper() {
        return upper;
    }

    /**
     * Gives this block a second stone outright, for a block that is built from two at once.
     *
     * <p>Distinct from {@link #setSlabHalf}, which is about a half being <i>filled</i> — it decides
     * which half is free and swaps the two into order. A stonecutter has no free half to fill: both
     * its stones arrive together from one recipe, and which is which is fixed by the block's own art
     * rather than by the order they were placed in the world.
     *
     * <p>Null is a legitimate value and means "one stone", which is every other composite.
     */
    /** The costume this block is wearing, or empty. Never null. */
    @Override
    public Costumes costumes() {
        return costumes;
    }

    /**
     * Puts a costume on, or takes it off with an empty stack.
     *
     * <p>Dirties the model data as well as the block: a costume changes which sprite the surface is
     * drawn from, so re-tinting would not be enough — the mesh has to be rebuilt.
     */
    @Override
    public void setCostumes(Costumes value) {
        if (costumes.equals(value)) {
            return;
        }
        costumes = value;
        costumeLayers = null;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            requestModelDataUpdate();
        }
    }

    public void setUpper(@Nullable Composition value) {
        if (java.util.Objects.equals(upper, value)) {
            return;
        }
        upper = value;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            requestModelDataUpdate();
        }
    }

    /** What has been done to this block. Never null; unworked stone is {@link Finish#COBBLED}. */
    public com.tarosie.granularity.core.Finish finish() {
        return finish;
    }

    /** What has been done to a double slab's upper half. */
    public com.tarosie.granularity.core.Finish upperFinish() {
        return upperFinish;
    }

    /**
     * Records that something has been done to this block.
     *
     * <p>Dirties the model data as well as the block, because a finish changes <i>which base model
     * the block is built from</i> — cobbled shows its nine grains as separate stones, smooth shows one
     * averaged colour — rather than only what colour it is drawn in. Re-tinting would not be enough.
     */
    public void setFinish(com.tarosie.granularity.core.Finish value) {
        if (finish == value) {
            return;
        }
        finish = value;
        upperFinish = value;
        layers = null;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            requestModelDataUpdate();
        }
    }

    /**
     * Places a slab's composition into the half it occupies.
     *
     * <p>Doubling a slab fills whichever half is still free rather than overwriting, which is what
     * keeps a slate slab slate when a gabbro one is stacked onto it. A slab placed as the top half
     * still stores into {@code composition} — {@code storedIsTop} records that, so when the bottom
     * is later filled the two can be swapped into the right order.
     */
    public void setSlabHalf(boolean isDouble, boolean placedOnTop, Composition incoming,
                            Coating incomingOverlays, Dyes incomingDyes,
                            com.tarosie.granularity.core.Finish incomingFinish) {
        if (isDouble) {
            if (storedIsTop) {
                upper = composition;
                upperOverlays = overlays;
                upperDyes = dyes;
                upperFinish = finish;
                composition = incoming;
                overlays = incomingOverlays;
                dyes = incomingDyes;
                finish = incomingFinish;
                storedIsTop = false;
            } else {
                upper = incoming;
                upperOverlays = incomingOverlays;
                upperDyes = incomingDyes;
                upperFinish = incomingFinish;
            }
        } else {
            composition = incoming;
            overlays = incomingOverlays;
            dyes = incomingDyes;
            finish = incomingFinish;
            upperFinish = incomingFinish;
            upper = null;
            upperOverlays = Coating.NONE;
            upperDyes = Dyes.NONE;
            storedIsTop = placedOnTop;
        }
        layers = null;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            requestModelDataUpdate();
        }
    }

    /** The reduction the renderer wants, computed once and kept until the composition changes. */
    @Override
    public CompositionLayers layers() {
        if (layers == null) {
            layers = CompositionLayers.of(composition);
        }
        return layers;
    }

    @Override
    public void setComposition(Composition composition) {
        this.composition = composition;
        this.upper = null;
        this.upperOverlays = Coating.NONE;
        this.upperDyes = Dyes.NONE;
        this.layers = null;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            requestModelDataUpdate();
        }
    }

    /**
     * The last instant this block's appearance exists, and so where the client puts it aside.
     *
     * <p>A block about to be pushed is removed here and rebuilt two ticks later somewhere else; see
     * {@link com.tarosie.granularity.client.MovingComposites} for why the gap between the two has to
     * be filled in by hand.
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide) {
            com.tarosie.granularity.client.MovingComposites.remember(this);
        }
    }

    /**
     * The composition a costume is lending, or null when the donor is not one of our blocks.
     *
     * <p>This is the difference between the two kinds of costume, and it is not a special case so
     * much as the only way either can work. A vanilla block keeps its appearance in a texture, so
     * wearing it means borrowing that texture. One of ours keeps its appearance in nine grains and a
     * finish — its sprites are untinted greyscale layers that mean nothing on their own — so wearing
     * it means borrowing the <i>composition</i> and letting the ordinary layered path draw it.
     *
     * <p>Getting this wrong is visible and was: borrowing a granularity cobblestone's sprite lent its
     * bare base layer, dropped the nine stones, and turned the tinting off with them, so every costume
     * came out the same flat white-grey whatever you put in the slot.
     */
    @org.jetbrains.annotations.Nullable
    public Composition costumeComposition() {
        return costumes.lentComposition();
    }

    /** The finish the stone's costume is lending; only meaningful alongside the composition. */
    public com.tarosie.granularity.core.Finish costumeFinish() {
        return costumes.lentFinish();
    }

    /** The finish covering one half: the costume's if that half is dressed, otherwise the block's. */
    private com.tarosie.granularity.core.Finish finishFor(
            int tint, com.tarosie.granularity.core.Finish own) {
        return costumes.covering(tint).isEmpty() ? own : costumes.covering(tint).finish();
    }

    @Override
    public ModelData getModelData() {
        // Only a double slab coats its two halves separately. Everything else with a second stone —
        // the stonecutter — is one block, and its upper surface wears whatever the lower one does.
        // See Moss.hasTwoHalves; without this a mossy stonecutter has a clean top.
        boolean twoHalves = Moss.hasTwoHalves(getBlockState());

        // A costume from one of our own blocks is worn as a composition, not as a sprite: the layers
        // and the finish below are the donor's, and every stone, tint and finish downstream follows
        // from them. Only a foreign donor reaches TransmogBakedModel, which is the sprite swap.
        Composition worn = costumeComposition();
        CompositionLayers shownLayers = layers();
        com.tarosie.granularity.core.Finish shownFinish = finish;
        com.tarosie.granularity.core.Finish shownUpperFinish = upperFinish;
        if (worn != null) {
            if (costumeLayers == null) {
                costumeLayers = CompositionLayers.of(worn);
            }
            shownLayers = costumeLayers;
        }
        // Read per half, so a double slab wearing smooth stone on top and cobbles underneath gets
        // both. Falls back to this block's own finish for a half nothing is worn on.
        shownFinish = finishFor(0, finish);
        shownUpperFinish = finishFor(
                com.tarosie.granularity.client.CompositeBlockColour.UPPER_BASE, upperFinish);

        return ModelData.builder()
                .with(com.tarosie.granularity.client.CompositionBakedModel.LAYERS, shownLayers)
                .with(com.tarosie.granularity.client.FinishBakedModel.FINISH,
                        new com.tarosie.granularity.client.FinishBakedModel.State(
                                shownFinish, shownUpperFinish))
                // Sent for both kinds of costume. A foreign donor is a sprite swap; one of ours has
                // already had its stone dealt with above, and reaches the wrapper only so the parts
                // that are *not* stone — a piston's timber, a furnace's door — can be turned to stone
                // as well. A costume covers all of a block or it is not a costume.
                .with(com.tarosie.granularity.client.TransmogBakedModel.COSTUMES, costumes)
                .with(com.tarosie.granularity.client.OverlayBakedModel.OVERLAYS,
                        new com.tarosie.granularity.client.OverlayBakedModel.State(
                                overlays, twoHalves ? upperOverlays : overlays,
                                dyes, twoHalves ? upperDyes : dyes,
                                getBlockState().is(GranularityTags.DYED_WHOLE)))
                .build();
    }

    /**
     * An item form built straight from a saved block-entity tag.
     *
     * <p>The inverse of {@link #loadAdditional}, for the one case where the block entity itself never
     * exists: a falling block that cannot land drops an item without ever being rebuilt, so there is
     * nothing to read the composition off. Vanilla drops a bare {@code new ItemStack(block)} there,
     * which would turn a slate gravel into the default stone on the way down — data loss, and a
     * laundering hole with it.
     *
     * <p>Lives here so it reads the same key names the loader does. Two classes agreeing on string
     * literals is exactly how the piston head lost its dye.
     */
    public static net.minecraft.world.item.ItemStack itemFrom(
            net.minecraft.world.level.ItemLike item, @Nullable CompoundTag tag) {
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
        if (tag == null) {
            return stack;
        }
        Composition composition = CompositionCodecs.load(tag, COMPOSITION_KEY);
        if (composition != null) {
            stack.set(GranularityComponents.COMPOSITION.get(), composition);
        }
        Dyes.apply(stack, Dyes.load(tag, DYES_KEY, LEGACY_MATRIX_KEY));
        Moss.apply(stack, GranularityOverlays.load(tag, OVERLAYS_KEY));
        if (tag.contains(WOOD_KEY)) {
            stack.set(GranularityComponents.WOOD.get(),
                    net.minecraft.resources.ResourceLocation.tryParse(tag.getString(WOOD_KEY)));
        }
        if (tag.contains(METAL_KEY)) {
            stack.set(GranularityComponents.METAL.get(),
                    net.minecraft.resources.ResourceLocation.tryParse(tag.getString(METAL_KEY)));
        }
        return stack;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        Composition loaded = CompositionCodecs.load(tag, COMPOSITION_KEY);
        if (loaded != null) {
            composition = loaded;
            layers = null;
        }
        upper = CompositionCodecs.load(tag, UPPER_KEY);
        // Absent means cobbled, so every block placed before finishes existed reads correctly.
        finish = tag.contains(FINISH_KEY)
                ? com.tarosie.granularity.core.Finish.byId(tag.getString(FINISH_KEY))
                : com.tarosie.granularity.core.Finish.COBBLED;
        // Absent means "same as the lower half", which is true of every block that is not a double
        // slab and of every double whose halves match — so the common cases write nothing.
        upperFinish = tag.contains(UPPER_FINISH_KEY)
                ? com.tarosie.granularity.core.Finish.byId(tag.getString(UPPER_FINISH_KEY))
                : finish;
        storedIsTop = tag.getBoolean(STORED_IS_TOP_KEY);
        // A whole-block colour covered both halves of a double slab, so migrating one has to carry it
        // to both. Recognised by neither per-face key being present at all, rather than by the lower
        // half coming back empty — a block dyed only on its upper half writes no lower key either.
        boolean migrating = !tag.contains(DYES_KEY) && !tag.contains(UPPER_DYES_KEY);
        dyes = Dyes.load(tag, DYES_KEY, LEGACY_MATRIX_KEY);
        upperDyes = migrating ? dyes : Dyes.load(tag, UPPER_DYES_KEY);
        wood = tag.contains(WOOD_KEY)
                ? net.minecraft.resources.ResourceLocation.tryParse(tag.getString(WOOD_KEY))
                : null;
        costumes = Costumes.load(registries, tag, TRANSMOG_KEY);
        // This is the path a costume actually arrives on for the client — the block update packet,
        // not setTransmog — so the derived reduction has to be dropped here as well or swapping one
        // costume for another keeps the first one's stones.
        costumeLayers = null;
        metal = tag.contains(METAL_KEY)
                ? net.minecraft.resources.ResourceLocation.tryParse(tag.getString(METAL_KEY))
                : null;
        overlays = GranularityOverlays.load(tag, OVERLAYS_KEY);
        upperOverlays = GranularityOverlays.load(tag, UPPER_OVERLAYS_KEY);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompositionCodecs.save(tag, COMPOSITION_KEY, composition);
        if (upper != null) {
            CompositionCodecs.save(tag, UPPER_KEY, upper);
        }
        if (finish != com.tarosie.granularity.core.Finish.COBBLED) {
            // Written only when there is something to write, so an unworked block stays
            // byte-identical to one saved before finishes existed.
            tag.putString(FINISH_KEY, finish.id());
        }
        if (upperFinish != finish) {
            tag.putString(UPPER_FINISH_KEY, upperFinish.id());
        }
        if (storedIsTop) {
            tag.putBoolean(STORED_IS_TOP_KEY, true);
        }
        dyes.save(tag, DYES_KEY);
        upperDyes.save(tag, UPPER_DYES_KEY);
        if (wood != null) {
            tag.putString(WOOD_KEY, wood.toString());
        }
        if (metal != null) {
            tag.putString(METAL_KEY, metal.toString());
        }
        costumes.save(registries, tag, TRANSMOG_KEY);
        GranularityOverlays.save(tag, OVERLAYS_KEY, overlays);
        GranularityOverlays.save(tag, UPPER_OVERLAYS_KEY, upperOverlays);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        CompositionCodecs.save(tag, COMPOSITION_KEY, composition);
        if (upper != null) {
            CompositionCodecs.save(tag, UPPER_KEY, upper);
        }
        if (finish != com.tarosie.granularity.core.Finish.COBBLED) {
            // Written only when there is something to write, so an unworked block stays
            // byte-identical to one saved before finishes existed.
            tag.putString(FINISH_KEY, finish.id());
        }
        if (upperFinish != finish) {
            tag.putString(UPPER_FINISH_KEY, upperFinish.id());
        }
        if (storedIsTop) {
            tag.putBoolean(STORED_IS_TOP_KEY, true);
        }
        dyes.save(tag, DYES_KEY);
        upperDyes.save(tag, UPPER_DYES_KEY);
        if (wood != null) {
            tag.putString(WOOD_KEY, wood.toString());
        }
        if (metal != null) {
            tag.putString(METAL_KEY, metal.toString());
        }
        costumes.save(registries, tag, TRANSMOG_KEY);
        GranularityOverlays.save(tag, OVERLAYS_KEY, overlays);
        GranularityOverlays.save(tag, UPPER_OVERLAYS_KEY, upperOverlays);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * Arriving composition data has to dirty the chunk section itself.
     *
     * <p>Loading a block entity payload does not invalidate the mesh the section was built with, and
     * these blocks are rendered <i>from</i> that payload — so without this the block keeps whatever
     * tints it was meshed with until something else happens to dirty the section. The symptom is a
     * one-step lag: dye a block and it stays plain, dye the next one and the first corrects itself.
     */
    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                             ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        super.onDataPacket(connection, packet, registries);
        refreshClientMesh();
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        refreshClientMesh();
    }

    private void refreshClientMesh() {
        if (level == null || !level.isClientSide) {
            return;
        }
        layers = null;
        requestModelDataUpdate();
        // Only reached client-side, so the client-only class never loads on a dedicated server.
        com.tarosie.granularity.client.ClientMeshRefresh.mark(getBlockPos());
    }
}
