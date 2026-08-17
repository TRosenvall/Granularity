package com.tarosie.granularity.content;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.PistonEvent;

/**
 * Carries a composite block's memory across a piston push.
 *
 * <p>A pushed block does not travel. Vanilla replaces it with {@code MovingPistonBlock} holding only
 * a {@code BlockState}, animates that for two ticks, and then <b>builds a fresh block</b> at the
 * destination — so a block entity is not moved, it is destroyed at one end and created empty at the
 * other. That is the real reason vanilla refuses to push block entities at all, and it is the half
 * that {@link com.tarosie.granularity.mixin.PistonBaseBlockMixin} cannot fix by itself.
 *
 * <p>So the data is set aside before the push and put back when the block lands.
 * {@link PistonEvent.Pre} fires while the originals are still standing, which is the last moment the
 * data exists; {@code onPlace} with {@code isMoving} set is the first moment the new block does.
 */
public final class PistonMoves {

    /**
     * Data waiting for its block to arrive, keyed by where it is going.
     *
     * <p>Server-side and short-lived — two ticks between the push and the landing. Entries are
     * removed as they are claimed.
     */
    private static final Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private PistonMoves() {
    }

    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(PistonMoves::onPistonPre);
    }

    private static void onPistonPre(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide) {
            return;
        }
        Map<BlockPos, CompoundTag> pending = IN_FLIGHT.computeIfAbsent(
                level.dimension(), key -> new ConcurrentHashMap<>());
        boolean extending = event.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND;
        if (!extending) {
            // A retracting piston demolishes *itself*. `triggerEvent` replaces the piston at its own
            // position with MOVING_PISTON, animates it, and then rebuilds it from
            // `defaultBlockState()` — so the base's block entity is destroyed and comes back empty,
            // and a slate piston would go back to being an andesite one the first time it pulled in.
            // Extending is safe by contrast: it only flips EXTENDED on the block already standing
            // there, and setting a state on the same block keeps its entity.
            //
            // Stashed under the piston's own position, which nothing else can collide with: pushed
            // blocks always move away from the piston, and a pulled one lands beside it, never on it.
            BlockEntity piston = level.getBlockEntity(event.getPos());
            if (piston instanceof CompositionHolder) {
                pending.put(event.getPos().immutable(), piston.saveWithoutMetadata(level.registryAccess()));
            }
        }
        var resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }
        // Blocks travel along the piston's facing when it extends and back down it when a sticky
        // piston retracts, so the destination is not simply "one step that way".
        Direction movement = extending ? event.getDirection() : event.getDirection().getOpposite();
        for (BlockPos from : resolver.getToPush()) {
            // Any block entity that knows its composition, not just the plain one: a furnace, a
            // dispenser and a dropper each need a different superclass and so cannot share a class,
            // and keying on CompositeBlockEntity quietly left all three behind. The whole tag is
            // taken rather than the composition alone, so a pushed furnace keeps what was smelting
            // in it — vanilla never had to answer that question, because it never moved one.
            BlockEntity entity = level.getBlockEntity(from);
            if (entity instanceof CompositionHolder) {
                pending.put(from.relative(movement), entity.saveWithoutMetadata(level.registryAccess()));
            }
        }
    }

    /**
     * Restores whatever was set aside for this position, if anything.
     *
     * <p>Called from every composite block's {@code onPlace}. The {@code isMoving} flag is what makes
     * this safe: it is true only when a piston is doing the placing, so an ordinary placement can
     * never pick up data left behind by an unrelated push.
     */
    public static void land(LevelAccessor level, BlockPos pos, boolean isMoving) {
        if (!isMoving || level.isClientSide() || !(level instanceof Level realLevel)) {
            return;
        }
        Map<BlockPos, CompoundTag> pending = IN_FLIGHT.get(realLevel.dimension());
        if (pending == null) {
            return;
        }
        CompoundTag tag = pending.remove(pos);
        if (tag == null) {
            return;
        }
        BlockEntity entity = realLevel.getBlockEntity(pos);
        if (entity instanceof CompositionHolder) {
            entity.loadWithComponents(tag, realLevel.registryAccess());
            entity.setChanged();
            realLevel.sendBlockUpdated(pos, entity.getBlockState(), entity.getBlockState(), 3);
        }
    }
}
