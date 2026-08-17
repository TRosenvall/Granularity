package com.tarosie.granularity.content;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A dropper is a dispenser that throws rather than fires, and remembers its stone either way.
 *
 * <p>The whole difference from {@link CompositeDispenserBlockEntity} is the registered type and the
 * name on the screen — exactly the difference vanilla's {@code DropperBlockEntity} has from its own
 * dispenser, and for the same reason: a separate type is what lets the two blocks each accept only
 * their own entity.
 */
public class CompositeDropperBlockEntity extends CompositeDispenserBlockEntity {

    public CompositeDropperBlockEntity(BlockPos pos, BlockState state) {
        super(GranularityBlocks.COMPOSITE_DROPPER_ENTITY.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.dropper");
    }
}
