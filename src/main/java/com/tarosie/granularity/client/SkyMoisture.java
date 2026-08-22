package com.tarosie.granularity.client;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.network.HumiditySyncPayload;
import net.minecraft.util.Mth;

/**
 * What the client knows about the sky over it.
 *
 * <p>The client half of the atmosphere. A grid of saturations, one per chunk, arriving every couple
 * of seconds from the server; everything the player <i>sees</i> of the weather is read from here.
 *
 * <h2>Two grids, and why</h2>
 * Snapshots arrive seconds apart, and weather that jumped every time one landed would read as
 * stuttering however correct the field was. So the last two are kept and read blended, which turns a
 * slow packet into continuous motion — the same trick every networked game uses for position, applied
 * to a scalar field.
 *
 * <p>Held statically because there is exactly one sky and exactly one client. Cleared on disconnect,
 * for the same reason {@code SaltLifecycle} clears the salt: a grid left behind from the last world
 * would be read as this one's weather, and would look plausible while being entirely wrong.
 */
public final class SkyMoisture {

    /** How long a snapshot takes to fully replace the one before it, in milliseconds. */
    private static final long BLEND_MILLIS = 2000L;

    private static byte[] previous;
    private static byte[] current;
    private static int originX;
    private static int originZ;
    private static int width;
    private static long arrived;

    private SkyMoisture() {
    }

    /**
     * Take a snapshot from the server.
     *
     * <p>The first one is logged. A sync that silently never arrives leaves every visual downstream
     * of it doing nothing at all, which looks exactly like a sky that happens to be clear — so the
     * wire says once, out loud, that it is working, and how saturated the air was when it did.
     */
    public static void accept(HumiditySyncPayload payload) {
        if (current == null) {
            int peak = 0;
            for (byte cell : payload.saturation()) {
                peak = Math.max(peak, cell & 0xFF);
            }
            Granularity.LOGGER.info(
                    "Sky synced: {} columns across, wettest {}% of capacity.",
                    payload.width(), peak * 100 / 255);
        }
        // Only blend between grids that describe the same ground. A player who has teleported gets
        // the new sky outright rather than a two-second dissolve from the sky of somewhere else.
        boolean sameGround = payload.originX() == originX && payload.originZ() == originZ
                && payload.width() == width;
        previous = sameGround ? current : payload.saturation();
        current = payload.saturation();
        originX = payload.originX();
        originZ = payload.originZ();
        width = payload.width();
        arrived = System.currentTimeMillis();
    }

    /** Forget everything. Called on disconnect. */
    public static void clear() {
        previous = null;
        current = null;
        width = 0;
    }

    public static boolean known() {
        return current != null && width > 0;
    }

    /**
     * How close the sky over a block position is to raining, 0 to 1.
     *
     * <p>Reads outside the synced grid clamp to its edge rather than returning zero, so the world
     * does not acquire a hard rectangle of clear weather at the edge of what the server sent.
     */
    public static float saturationAt(int blockX, int blockZ) {
        if (!known()) {
            return 0.0f;
        }
        int cellX = Mth.clamp((blockX >> 4) - originX, 0, width - 1);
        int cellZ = Mth.clamp((blockZ >> 4) - originZ, 0, width - 1);
        int index = cellZ * width + cellX;
        if (index < 0 || index >= current.length) {
            return 0.0f;
        }

        float now = (current[index] & 0xFF) / 255.0f;
        if (previous == null || previous.length != current.length) {
            return now;
        }
        float then = (previous[index] & 0xFF) / 255.0f;
        float blend = Mth.clamp((System.currentTimeMillis() - arrived) / (float) BLEND_MILLIS,
                0.0f, 1.0f);
        return Mth.lerp(blend, then, now);
    }
}
