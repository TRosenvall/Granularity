package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionLayers;
import com.tarosie.granularity.core.Grains;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * A dispenser that remembers the stone it was built from.
 *
 * <p>Extends {@link DispenserBlockEntity} for the same reason {@link CompositeFurnaceBlockEntity}
 * extends the abstract furnace: everything a dispenser does — the nine slots, the random pick, the
 * menu — is behaviour we want unchanged, and one position holds exactly one block entity, so ours has
 * to <i>be</i> a dispenser rather than sit beside one. {@link CompositionHolder} is what lets the
 * colour handler, the drops and the dye treat it identically to every other composite.
 *
 * <p>Vanilla's own {@code DropperBlockEntity} is built the same way — a subclass differing only in
 * its registered type and its name — which is why the protected constructor here mirrors vanilla's.
 */
public class CompositeDispenserBlockEntity extends DispenserBlockEntity implements CompositionHolder {

    private static final String COMPOSITION_KEY = "Composition";

    /** The whole-block colour that came before per-face dye; read on load, never written. */
    private static final String LEGACY_MATRIX_KEY = "MatrixTint";

    private static final String DYES_KEY = "Dyes";

    private static final String OVERLAYS_KEY = "Overlays";

    private Composition composition = Composition.uniform(Grains.ANDESITE.id());

    private CompositionLayers layers;

    private Dyes dyes = Dyes.NONE;

    private Coating overlays = Coating.NONE;

    public CompositeDispenserBlockEntity(BlockPos pos, BlockState state) {
        this(GranularityBlocks.COMPOSITE_DISPENSER_ENTITY.get(), pos, state);
    }

    protected CompositeDispenserBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public Composition composition() {
        return composition;
    }

    /** A dispenser is one block with one composition; only a slab is ever two. */
    @Nullable
    @Override
    public Composition upper() {
        return null;
    }

    @Override
    public Coating upperOverlays() {
        return Coating.NONE;
    }

    @Override
    public Coating overlays() {
        return overlays;
    }

    @Override
    public Dyes dyes() {
        return dyes;
    }

    /** A dispenser has no second half, so there is nothing for an upper dye to belong to. */
    @Override
    public Dyes upperDyes() {
        return Dyes.NONE;
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
        // `upper` is meaningless here and is simply ignored: a dispenser has no second half.
        this.dyes = value;
        changed();
    }

    @Override
    public void setOverlays(boolean upper, Coating value) {
        // `upper` is meaningless here and is simply ignored: a dispenser has no second half.
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
        return ModelData.builder()
                .with(com.tarosie.granularity.client.CompositionBakedModel.LAYERS, layers())
                .with(com.tarosie.granularity.client.OverlayBakedModel.OVERLAYS,
                        new com.tarosie.granularity.client.OverlayBakedModel.State(
                                overlays, Coating.NONE, dyes, Dyes.NONE))
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
        overlays = GranularityOverlays.load(tag, OVERLAYS_KEY);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeComposition(tag);
    }

    /**
     * The update tag carries the composition <b>and nothing else the dispenser holds</b>.
     *
     * <p>{@code saveAdditional} would send the nine slots' contents to every client that can see the
     * block, which is both wasteful and a small information leak. The client only needs to know what
     * the dispenser is made of, because that is all it draws.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        writeComposition(tag);
        return tag;
    }

    private void writeComposition(CompoundTag tag) {
        CompositionCodecs.save(tag, COMPOSITION_KEY, composition);
        dyes.save(tag, DYES_KEY);
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
