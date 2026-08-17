package com.tarosie.granularity.client;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionFunction;
import com.tarosie.granularity.core.CompositionLayers;
import com.tarosie.granularity.core.WorldSalt;
import net.minecraft.core.BlockPos;

/**
 * Client-side composition lookup, with a one-entry memo per thread.
 *
 * <p>The memo is not premature. Meshing asks for the same block's composition twice by two
 * different routes: {@code getModelData} needs it to choose overlay steps, and the tint provider
 * needs it again per tinted quad, because {@code BlockColor} is handed a level and a position but
 * not the model data. Both happen back-to-back for one position, so a single-entry cache hits
 * nearly every time and turns roughly seven derivations per block into one.
 *
 * <p>Per-thread because section compilation runs on a pool. Nothing is shared, so there is no
 * locking and no invalidation problem — the memo is only ever a shortcut for a pure function, and
 * the worst a stale entry could do is be recomputed.
 */
public final class ClientCompositions {

    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    private ClientCompositions() {
    }

    /**
     * The composition at a position, or {@code null} before the salt has been synced.
     *
     * <p>Null rather than a guess: rendering a world derived from the wrong salt is exactly the
     * silent desync the salt sync exists to prevent, so the renderer falls back to an untinted
     * base instead.
     */
    public static Composition at(BlockPos pos) {
        if (!WorldSalt.ClientView.isPresent()) {
            return null;
        }
        long salt = WorldSalt.ClientView.get().value();
        Memo memo = MEMO.get();
        long key = pos.asLong();
        if (memo.composition != null && memo.key == key && memo.salt == salt) {
            return memo.composition;
        }
        Composition composition = CompositionFunction.stone(pos.getX(), pos.getY(), pos.getZ(), salt);
        memo.key = key;
        memo.salt = salt;
        memo.composition = composition;
        memo.layers = null;
        return composition;
    }

    /** The render layers at a position, memoised alongside the composition. */
    public static CompositionLayers layersAt(BlockPos pos) {
        Composition composition = at(pos);
        if (composition == null) {
            return null;
        }
        Memo memo = MEMO.get();
        if (memo.layers == null) {
            memo.layers = CompositionLayers.of(composition);
        }
        return memo.layers;
    }

    private static final class Memo {
        private long key = Long.MIN_VALUE;
        private long salt;
        private Composition composition;
        private CompositionLayers layers;
    }
}
