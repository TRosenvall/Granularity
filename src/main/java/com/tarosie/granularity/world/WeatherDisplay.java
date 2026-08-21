package com.tarosie.granularity.world;

import com.tarosie.granularity.Granularity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Makes the sky show what the field says — vanilla's rain, driven by our humidity.
 *
 * <p>Vanilla weather is a coin flip on a timer: a per-dimension clock decides it is raining, and it
 * rains on everything at once regardless of where any water is. Having built a field that knows
 * exactly where moisture is and where it condenses, leaving that clock running would mean the sky
 * and the ground disagreed permanently — rain falling on a desert while the windward slope of a range
 * stayed clear.
 *
 * <p>So the clock is switched off and the rain is set from the field instead.
 *
 * <h2>What this can and cannot do</h2>
 * Vanilla's rain is <b>one number per level</b>, not per chunk. There is no way to make it rain on
 * one chunk and not its neighbour without replacing the renderer. So the field is read at the
 * <i>player's own column</i> and vanilla is told to rain that much.
 *
 * <p>The result is right where it matters and approximate where it does not: walk out of a rain
 * shadow and it starts raining on you, walk back into it and it stops. Somebody standing a hundred
 * blocks away in a multiplayer world sees your weather rather than theirs, which is a real limitation
 * and the price of not writing a weather renderer. Per-chunk visuals need the field synced to the
 * client, which is its own piece of work.
 */
@EventBusSubscriber(modid = Granularity.MODID)
public final class WeatherDisplay {

    /**
     * How much recent rainfall counts as a downpour.
     *
     * <p>The scale between our drops and vanilla's 0–1 rain level. Anything at or above this shows as
     * full rain; below it the sky is proportionally lighter, so a shower reads as a shower.
     */
    private static final int DOWNPOUR = 24;

    /** How fast the visible sky follows the field, per tick. */
    private static final float FOLLOW = 0.02f;

    private WeatherDisplay() {
    }

    /**
     * Stop vanilla deciding the weather.
     *
     * <p>Done on load rather than once at world creation, because a world made before this existed —
     * or edited since — would otherwise keep its own clock running and fight the field forever.
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)) {
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE)
                    .set(false, level.getServer());
            Granularity.LOGGER.info(
                    "Weather cycle handed to the humidity field; vanilla's own clock is off.");
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.players().isEmpty()) {
            return;
        }

        // The wettest column any player is standing on. With one player that is simply their weather;
        // with several it is whoever is having the worst of it, which at least means nobody is
        // standing in a downpour under a clear sky.
        float wanted = 0.0f;
        for (ServerPlayer player : level.players()) {
            int fallen = GranularityWeather.recentRain(level,
                    player.blockPosition().getX(), player.blockPosition().getZ());
            wanted = Math.max(wanted, Math.min(1.0f, fallen / (float) DOWNPOUR));
        }

        // Eased rather than snapped. Rain that switched on the instant a player crossed a chunk
        // boundary would read as a bug however correct the field was; weather arrives.
        float now = level.getRainLevel(1.0f);
        float next = now + Math.max(-FOLLOW, Math.min(FOLLOW, wanted - now));
        level.setRainLevel(next);
        // Thunder tracks rain but never leads it, so a storm is heavy rain rather than its own event.
        level.setThunderLevel(Math.max(0.0f, next - 0.7f) * 2.0f);
    }
}
