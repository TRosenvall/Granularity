package com.tarosie.granularity.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The sky over one chunk, and what has lately fallen out of it.
 *
 * <p>Design §11's humidity is "one scalar per column", and §8 puts the field tier on a coarse grid;
 * a chunk is the coarse cell, because it is the unit the game already loads, saves and iterates.
 *
 * <h2>Stored outright, unlike water in rock</h2>
 * {@link com.tarosie.granularity.core.WaterDeviations} stores only deviations, because almost every
 * block in the world is at its derived baseline and storing the rest would be a database. Weather is
 * the opposite case: <b>every</b> chunk has some, it is always changing, and there are four bytes of
 * it per chunk rather than nine slots per block. A sparse representation would cost more to maintain
 * than it saved.
 *
 * <p>What is derived is the <i>starting</i> value. A chunk that has never been simulated reports its
 * climate baseline, so weather blowing in from country nobody has visited arrives at a sensible
 * humidity rather than at zero.
 *
 * @param humidity   drops of water vapour standing over this chunk
 * @param recentRain drops that have fallen here lately, decaying — what feeds {@link Recharge}
 */
public record ChunkWeather(int humidity, int recentRain) {

    /** A chunk nobody has simulated yet. Negative humidity means "ask the climate". */
    public static final ChunkWeather UNKNOWN = new ChunkWeather(-1, 0);

    public static final Codec<ChunkWeather> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.fieldOf("humidity").forGetter(ChunkWeather::humidity),
                    Codec.INT.fieldOf("recent_rain").forGetter(ChunkWeather::recentRain)
            ).apply(instance, ChunkWeather::new));

    public ChunkWeather withHumidity(int drops) {
        return new ChunkWeather(Math.max(0, drops), recentRain);
    }

    public ChunkWeather withRain(int drops) {
        return new ChunkWeather(humidity, Math.max(0, recentRain + drops));
    }

    /** Whether this chunk has ever been simulated, or is still reporting the climate. */
    public boolean known() {
        return humidity >= 0;
    }

    /**
     * Let recent rainfall fade.
     *
     * <p>Recharge reads this, so without decay a single storm would keep an aquifer topped up
     * forever and every spring in the world would be perennial after the first rain. What ought to
     * happen is that a wet season leaves the ground wet for a while and a dry one lets it fall away —
     * which is the whole of the difference between a perennial spring and a seasonal one.
     */
    public ChunkWeather fade() {
        return recentRain <= 0 ? this : new ChunkWeather(humidity, recentRain - 1);
    }
}
