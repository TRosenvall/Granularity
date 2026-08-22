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
 * Vanilla's weather is <b>one state per level</b>, not per chunk. There is no way to make it rain on
 * one chunk and not its neighbour without replacing the renderer, so the field is read at the
 * <i>player's own column</i> and the whole level is told whether it is raining.
 *
 * <p>The result is right where it matters and approximate where it does not: walk out of a rain
 * shadow and it starts raining on you, walk back into it and it stops. Somebody a hundred blocks away
 * in a multiplayer world sees your weather rather than theirs, which is a real limitation and the
 * price of not writing a weather renderer. Per-chunk visuals need the field on the client — which it
 * now is, so that is buildable rather than hypothetical.
 */
@EventBusSubscriber(modid = Granularity.MODID)
public final class WeatherDisplay {

    /**
     * Recent rainfall at which the sky is told to rain.
     *
     * <p>Separate from the number that stops it, so the weather does not flicker on and off while a
     * front sits on the threshold. A sky that strobed as a player walked back and forth across one
     * chunk boundary would be a worse lie than no weather at all.
     */
    private static final int RAIN_ON = 10;

    /** And what it falls to before the sky is told to stop. */
    private static final int RAIN_OFF = 3;

    /** Recent rainfall at which it is a storm rather than a shower. */
    private static final int THUNDER_ON = 40;

    /**
     * How long the weather is set for, in ticks.
     *
     * <p>Effectively forever: with the weather cycle switched off these timers do not advance, so the
     * only thing that ever changes the weather is this class reading the field. The number exists
     * because the vanilla call demands one.
     */
    private static final int UNTIL_WE_SAY_OTHERWISE = 24000;

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

    /**
     * Tell the sky whether it is raining, and let vanilla do the rest.
     *
     * <h2>Why the boolean and not the rain level</h2>
     * This called {@code setRainLevel} at first, which is the obvious thing and does not work at all.
     * {@code ServerLevel.tickWeather} recomputes {@code rainLevel} every tick from the stored
     * {@code isRaining()} <b>boolean</b> — a hundredth up if it is raining, a hundredth down if not —
     * so a value written from outside is overwritten on the following tick and decays to nothing. And
     * the packet that tells the client is only sent when <i>vanilla's own</i> before-and-after
     * comparison differs, so the value was never synced either. The sky stayed clear in a downpour and
     * nothing anywhere reported an error.
     *
     * <p>Setting the boolean instead works with that machinery rather than against it: vanilla eases
     * the level, broadcasts it, darkens the sky and draws the rain, all on its own. The field decides
     * <i>whether</i>; vanilla still decides how it looks.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.players().isEmpty()) {
            return;
        }

        // The wettest column any player is standing on. With one player that is simply their weather;
        // with several it is whoever is having the worst of it, which at least means nobody is
        // standing in a downpour under a clear sky.
        int wettest = 0;
        for (ServerPlayer player : level.players()) {
            wettest = Math.max(wettest, GranularityWeather.recentRain(level,
                    player.blockPosition().getX(), player.blockPosition().getZ()));
        }

        boolean raining = level.isRaining();
        if (!raining && wettest >= RAIN_ON) {
            level.setWeatherParameters(0, UNTIL_WE_SAY_OTHERWISE, true, wettest >= THUNDER_ON);
        } else if (raining && wettest <= RAIN_OFF) {
            level.setWeatherParameters(UNTIL_WE_SAY_OTHERWISE, 0, false, false);
        } else if (raining && level.isThundering() != (wettest >= THUNDER_ON)) {
            // A shower turning into a storm, or back, without stopping in between.
            level.setWeatherParameters(0, UNTIL_WE_SAY_OTHERWISE, true, wettest >= THUNDER_ON);
        }
    }
}
