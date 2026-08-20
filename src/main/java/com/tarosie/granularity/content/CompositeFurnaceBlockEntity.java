package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionLayers;
import com.tarosie.granularity.core.Grains;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * A furnace that remembers the stone it was built from.
 *
 * <p>Extends {@link AbstractFurnaceBlockEntity} rather than {@code FurnaceBlockEntity} because that
 * class hard-codes {@code BlockEntityType.FURNACE}, whose valid blocks are vanilla's furnace and not
 * ours. Everything a furnace does — smelting, fuel, the menu — is inherited untouched; only the
 * composition is added.
 *
 * <p>It carries {@link CompositionHolder} instead of extending {@link CompositeBlockEntity}, because
 * one position holds one block entity and this one has to be a furnace. That interface is what lets
 * the colour handler, the drops and the dye treat it identically to every other composite.
 */
public class CompositeFurnaceBlockEntity extends AbstractFurnaceBlockEntity implements CompositionHolder {

    private static final String COMPOSITION_KEY = "Composition";

    /** The whole-block colour that came before per-face dye; read on load, never written. */
    private static final String LEGACY_MATRIX_KEY = "MatrixTint";

    private static final String DYES_KEY = "Dyes";

    private static final String COSTUMES_KEY = "Transmog";

    private static final String OVERLAYS_KEY = "Overlays";

    private Composition composition = Composition.uniform(Grains.ANDESITE.id());

    private CompositionLayers layers;

    private Dyes dyes = Dyes.NONE;

    /** What this block is wearing, part by part. See {@link Costumes}. */
    private Costumes costumes = Costumes.NONE;

    /** The reduction of the costume's composition, dropped whenever the costume changes. */
    private CompositionLayers costumeLayers;

    @Override
    public Costumes costumes() {
        return costumes;
    }

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


    private Coating overlays = Coating.NONE;

    public CompositeFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(GranularityBlocks.COMPOSITE_FURNACE_ENTITY.get(), pos, state, RecipeType.SMELTING);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.furnace");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory player) {
        return new FurnaceMenu(id, player, this, this.dataAccess);
    }

    @Override
    public Composition composition() {
        return composition;
    }

    /** A furnace is one block with one composition; only a slab is ever two. */
    @Nullable
    @Override
    public Composition upper() {
        return null;
    }

    /**
     * A furnace's halves share their growth, because they are one block.
     *
     * <p>The model was split in two so the top and the bottom can wear different rock, and that split
     * put the top's stone into the 10-19 tint band — which every other part of this mod reads as "the
     * second half". Left saying it has no second half, a furnace would take moss and dye on its bottom
     * only and nobody would be told why. The division is for costumes and for nothing else.
     */
    @Override
    public Coating upperOverlays() {
        return overlays;
    }

    @Override
    public Coating overlays() {
        return overlays;
    }

    @Override
    public Dyes dyes() {
        return dyes;
    }

    /** See {@link #upperOverlays()}: the halves are one block for everything but costumes. */
    @Override
    public Dyes upperDyes() {
        return dyes;
    }

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
        this.layers = null;
        changed();
    }

    @Override
    public void setDyes(boolean upper, Dyes value) {
        // `upper` is meaningless here and is simply ignored: a furnace has no second half.
        this.dyes = value;
        changed();
    }

    @Override
    public void setOverlays(boolean upper, Coating value) {
        // `upper` is meaningless here and is simply ignored: a furnace has no second half.
        this.overlays = value;
        changed();
    }

    private void changed() {
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

    @Override
    public ModelData getModelData() {
        // A costume from one of our blocks is worn as a composition, not as a sprite: its stones are
        // what the block is drawn from while it is on. See Costumes.lentComposition.
        Composition worn = costumes.lentComposition();
        if (worn != null && costumeLayers == null) {
            costumeLayers = CompositionLayers.of(worn);
        }
        return ModelData.builder()
                .with(com.tarosie.granularity.client.CompositionBakedModel.LAYERS,
                        worn == null ? layers() : costumeLayers)
                // A costume lends its finish as well as its stones, which is what makes the surface
                // actually change rather than merely recolour. The two halves are carried separately
                // because a block split at the waist can wear two different rocks — which is exactly
                // the lower/upper pair this State was built for when a double slab needed it.
                .with(com.tarosie.granularity.client.FinishBakedModel.FINISH,
                        new com.tarosie.granularity.client.FinishBakedModel.State(
                                costumes.covering(0).finish(),
                                costumes.covering(com.tarosie.granularity.client
                                        .CompositeBlockColour.UPPER_BASE).finish()))
                .with(com.tarosie.granularity.client.TransmogBakedModel.COSTUMES, costumes)
                .with(com.tarosie.granularity.client.OverlayBakedModel.OVERLAYS,
                        new com.tarosie.granularity.client.OverlayBakedModel.State(
                                overlays, overlays, dyes, dyes))
                .build();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        Composition loaded = CompositionCodecs.load(tag, COMPOSITION_KEY);
        if (loaded != null) {
            composition = loaded;
            layers = null;
        }
        dyes = Dyes.load(tag, DYES_KEY, LEGACY_MATRIX_KEY);
        costumes = Costumes.load(registries, tag, COSTUMES_KEY);
        costumeLayers = null;
        overlays = GranularityOverlays.load(tag, OVERLAYS_KEY);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeComposition(tag, registries);
    }

    /**
     * The update tag carries the composition <b>and nothing else the furnace holds</b>.
     *
     * <p>{@code saveAdditional} would send the fuel and the smelting contents to every client that
     * can see the block, which is both wasteful and a small information leak. The client only needs
     * to know what the furnace is made of, because that is all it draws.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        writeComposition(tag, registries);
        return tag;
    }

    private void writeComposition(CompoundTag tag, HolderLookup.Provider registries) {
        CompositionCodecs.save(tag, COMPOSITION_KEY, composition);
        dyes.save(tag, DYES_KEY);
        costumes.save(registries, tag, COSTUMES_KEY);
        GranularityOverlays.save(tag, OVERLAYS_KEY, overlays);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this, (entity, registries) -> entity.getUpdateTag(registries));
    }

    /** See {@link CompositeBlockEntity#onDataPacket}: arriving data has to dirty the section itself. */
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
        com.tarosie.granularity.client.ClientMeshRefresh.mark(getBlockPos());
    }
}
