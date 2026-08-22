package com.tarosie.granularity.client;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.network.NearbyWaterPayload;
import java.util.HashMap;
import java.util.Map;

/**
 * What the client knows about water that has actually moved.
 *
 * <p>The counterpart to deriving a composition locally: the derivation says what a block <i>should</i>
 * hold, and this says how far from that it has been pushed. Together they are what the server knows.
 *
 * <p>Empty is the normal state and means "everything nearby is at equilibrium", not "no data" — a
 * distinction that matters, because the two would otherwise be reported the same way and the second
 * would silently show baselines as though they were current.
 */
public final class NearbyWater {

    private static final Map<Long, Integer> DEVIATIONS = new HashMap<>();

    private static boolean known;
    private static int patches;
    private static int springs;
    private static int humidity;
    private static int recentRain;
    private static long weepsEmitted;

    private NearbyWater() {
    }

    public static void accept(NearbyWaterPayload payload) {
        DEVIATIONS.clear();
        payload.deviations().forEach(entry -> DEVIATIONS.put(entry.position(), entry.delta()));
        patches = payload.patches();
        springs = payload.springs();
        humidity = payload.humidity();
        recentRain = payload.recentRain();
        weepsEmitted = payload.weepsEmitted();
        known = true;
    }

    /** Forget everything, on disconnect. */
    public static void clear() {
        DEVIATIONS.clear();
        known = false;
    }

    public static boolean known() {
        return known;
    }

    /**
     * What a block actually holds: its derived baseline, moved by whatever the server has stored.
     *
     * <p>Clamped to the pores that could hold it, exactly as the server does, so the two agree rather
     * than merely resembling each other.
     */
    public static int waterAt(long packedPosition, Composition derived) {
        int delta = DEVIATIONS.getOrDefault(packedPosition, 0);
        return Math.max(0, Math.min(derived.porosity(), derived.water() + delta));
    }

    /** Whether this block differs from what the field alone would say. */
    public static boolean disturbed(long packedPosition) {
        return DEVIATIONS.containsKey(packedPosition);
    }

    public static int patches() {
        return patches;
    }

    public static int springs() {
        return springs;
    }

    public static int humidity() {
        return humidity;
    }

    public static int recentRain() {
        return recentRain;
    }

    public static long weepsEmitted() {
        return weepsEmitted;
    }

    /** How many nearby blocks are away from their baseline. */
    public static int disturbedCount() {
        return DEVIATIONS.size();
    }
}
