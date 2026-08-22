package com.tarosie.granularity.client;

import com.tarosie.granularity.Granularity;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Heavy air looks heavy — the first thing the synced field buys.
 *
 * <p>Air close to raining is greyer and closer than clear air, and the difference is visible long
 * before the first drop falls. That is the whole reason for syncing the field: <b>this cannot be done
 * from the server at all</b>. Vanilla's only weather lever is the level's rain number, which spawns
 * rain particles as a side effect, so "overcast but not yet raining" is a state the server has no way
 * to ask for.
 *
 * <p>It is also what makes the rest of the atmosphere legible. A rain shadow is not much of a payoff
 * if both sides of the range look identical until one of them happens to be raining; with this, the
 * windward side is visibly muggy and the lee is visibly clear, and the weather is a thing you can see
 * coming.
 *
 * <h2>Restraint</h2>
 * The effect is deliberately small at ordinary humidity and only strong near saturation. An
 * atmosphere that tinted everything all the time would read as a broken shader rather than as
 * weather, and the player would learn to ignore it — which is the opposite of the point.
 *
 * <p>Underwater and in lava, nothing happens: those have their own fog, they are not the sky, and
 * fighting them would look like a bug.
 */
@EventBusSubscriber(modid = Granularity.MODID, value = Dist.CLIENT)
public final class SkyGloom {

    /** How much of the distance haze eats at full saturation. */
    private static final float HAZE = 0.35f;

    /** Below this, the sky is simply clear and nothing is touched. */
    private static final float ONSET = 0.45f;

    /** Grey the fog moves toward as the air fills. */
    private static final float GREY_RED = 0.52f;
    private static final float GREY_GREEN = 0.55f;
    private static final float GREY_BLUE = 0.58f;

    private SkyGloom() {
    }

    /**
     * How far ahead the air is sampled, in blocks. Roughly a long render distance.
     */
    private static final int[] LOOK_AHEAD = {0, 48, 112, 192};

    /**
     * Weights for those samples. The air you are standing in matters most; the air on the horizon
     * still matters, because that is where the fog you are looking through actually is.
     */
    private static final float[] LOOK_WEIGHT = {0.4f, 0.25f, 0.2f, 0.15f};

    /** How fast the effect follows the air, per frame. Slow enough that turning does not snap. */
    private static final float EASE = 0.03f;

    private static float smoothed;

    /**
     * How overcast the air is <b>in the direction the camera is facing</b>, 0 to 1.
     *
     * <h2>Why not simply the column underfoot</h2>
     * Because vanilla's fog is isotropic — one colour and one distance for the whole view, applied by
     * camera distance in the shader — so a single reading under the player makes the entire sky
     * uniformly hazy or uniformly clear. Standing at the edge of a front, with a wall of weather to
     * the west and clear air to the east, would look identical in both directions, which is the one
     * thing weather never does.
     *
     * <p>Sampling along the view direction cannot make the fog genuinely directional; nothing can,
     * through that pipeline. What it does is choose <i>which</i> air the uniform fog is standing in
     * for — and since the fog you see is mostly the air between you and what you are looking at, the
     * air in that direction is the honest choice. Turn toward the storm and it thickens; turn away
     * and it clears.
     *
     * <p>Eased over frames, or turning your head would snap the sky. Real fog does not care which way
     * you are facing, and the illusion only holds if it arrives slowly enough to read as the weather
     * being over there rather than as the camera doing something.
     */
    private static float overcast() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !SkyMoisture.known()) {
            smoothed = Mth.lerp(EASE, smoothed, 0.0f);
            return smoothed;
        }

        var look = client.gameRenderer.getMainCamera().getLookVector();
        double baseX = client.player.getX();
        double baseZ = client.player.getZ();
        float weighted = 0.0f;
        for (int i = 0; i < LOOK_AHEAD.length; i++) {
            int x = (int) (baseX + look.x() * LOOK_AHEAD[i]);
            int z = (int) (baseZ + look.z() * LOOK_AHEAD[i]);
            weighted += SkyMoisture.saturationAt(x, z) * LOOK_WEIGHT[i];
        }

        float target = Mth.clamp((weighted - ONSET) / (1.0f - ONSET), 0.0f, 1.0f);
        smoothed = Mth.lerp(EASE, smoothed, target);
        return smoothed;
    }

    @SubscribeEvent
    public static void onFogColour(ViewportEvent.ComputeFogColor event) {
        if (event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        float overcast = overcast();
        if (overcast <= 0.0f) {
            return;
        }
        event.setRed(Mth.lerp(overcast, event.getRed(), GREY_RED));
        event.setGreen(Mth.lerp(overcast, event.getGreen(), GREY_GREEN));
        event.setBlue(Mth.lerp(overcast, event.getBlue(), GREY_BLUE));
    }

    @SubscribeEvent
    public static void onFogDistance(ViewportEvent.RenderFog event) {
        if (event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        float overcast = overcast();
        if (overcast <= 0.0f) {
            return;
        }
        // Bringing the far plane in is what reads as damp air: distance goes soft before the sky
        // goes dark, which is the order it happens in outside.
        event.setFarPlaneDistance(event.getFarPlaneDistance() * (1.0f - HAZE * overcast));
        event.setCanceled(true);
    }

    /**
     * Put the numbers on the F3 screen.
     *
     * <p>Because the effect this class produces is deliberately restrained, and a restrained visual
     * is indistinguishable from a broken one without ground truth. "Is the air hazier here?" is a
     * question nobody can answer honestly by looking; "saturation 0.72, overcast 0.49" is checkable,
     * and it also says plainly when the sync has not arrived at all.
     */
    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (!SkyMoisture.known()) {
            event.getLeft().add("[Granularity] sky: not synced");
            return;
        }
        float here = SkyMoisture.saturationAt(
                client.player.getBlockX(), client.player.getBlockZ());
        // Both numbers, because they answer different questions: what the air is like where you are
        // standing, and what the air is like where you are looking.
        event.getLeft().add(String.format(java.util.Locale.ROOT,
                "[Granularity] sky: here %.2f, ahead %.2f, overcast %.2f%s",
                here, aheadSaturation(client), overcast(),
                here >= 1.0f ? " (raining)" : ""));
    }

    /** Mean saturation along the view direction, for the readout. */
    private static float aheadSaturation(Minecraft client) {
        var look = client.gameRenderer.getMainCamera().getLookVector();
        float weighted = 0.0f;
        for (int i = 0; i < LOOK_AHEAD.length; i++) {
            int x = (int) (client.player.getX() + look.x() * LOOK_AHEAD[i]);
            int z = (int) (client.player.getZ() + look.z() * LOOK_AHEAD[i]);
            weighted += SkyMoisture.saturationAt(x, z) * LOOK_WEIGHT[i];
        }
        return weighted;
    }

    /** Forget the sky on disconnect, so the next world does not inherit this one's weather. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SkyMoisture.clear();
        NearbyWater.clear();
    }
}
