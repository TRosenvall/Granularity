package com.tarosie.granularity.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Forces a section rebuild for a block whose <i>data</i> changed but whose state did not.
 *
 * <p>{@code Level#setBlocksDirty} is the obvious call and it does nothing here: it defers to
 * {@code ModelManager#requiresRender(old, new)}, and for these blocks the two states are identical —
 * the composition lives in the block entity, not the state. Only the tints changed, and the model
 * manager has no way to know that. {@code LevelRenderer#setBlocksDirty} is the unconditional path.
 */
public final class ClientMeshRefresh {

    private ClientMeshRefresh() {
    }

    public static void mark(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer == null) {
            return;
        }
        minecraft.levelRenderer.setBlocksDirty(
                pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }
}
