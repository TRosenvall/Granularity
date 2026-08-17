package com.tarosie.granularity.content;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tarosie.granularity.core.BedrockType;
import com.tarosie.granularity.core.GrainClass;
import com.tarosie.granularity.core.GrainSpec;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A grain as written in a datapack, at {@code data/<namespace>/granularity/grain/<name>.json}.
 *
 * <p>The file's own path is the grain's name — {@code data/mymod/granularity/grain/ruby.json} is
 * {@code mymod:ruby} — which is how every other datapack-defined thing in Minecraft is named, and it
 * means the namespacing that keeps two mods' "slate" apart is not something an author can forget.
 *
 * <pre>{@code
 * {
 *   "class": "gem",
 *   "item": "mymod:ruby",
 *   "families": ["metamorphic"],
 *   "tint": "#9B111E"
 * }
 * }</pre>
 *
 * <p>This is a <b>datapack registry</b> ({@code granularity:grain}), which is why the path looks the
 * way it does and why the client gets these for free: registry contents are sent during the
 * configuration phase, before the first chunk arrives. It matters because a natural block's
 * composition is derived rather than stored, so client and server each run the composition function
 * against their own roster and the two must hold the same grains.
 *
 * <p>{@code class} and {@code item} are required; both others have defaults worth knowing.
 * <b>Omitting {@code families} means every family</b>, which is right for the soils — sediment
 * travels — and wrong for almost anything else: a gem that occurs everywhere is a gem worth nobody's
 * journey, and design §4's prospecting promise is that the country rock tells you what is possible.
 * <b>Omitting {@code tint}</b> averages the item's own texture, which is usually what you want; see
 * {@link com.tarosie.granularity.core.TextureTint} for when it is not, and note that an item from
 * the vanilla jar always needs an explicit colour because a dedicated server has no vanilla textures.
 *
 * <p>Separate from the loader that reads these off disk so that the format can be tested without a
 * running game — this class knows nothing about resource packs, and the loader knows nothing about
 * the shape of a definition.
 */
public record GrainDefinition(GrainClass clazz, String item, List<BedrockType> families, int tint,
                              Optional<String> problem) {

    /** The file as written, before an absent tint has been resolved. */
    private record Raw(GrainClass clazz, String item, List<BedrockType> families,
                       Optional<Integer> tint, Optional<String> problem) {
    }

    private static final Codec<Raw> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    lowercase(GrainClass.class, "grain class").fieldOf("class").forGetter(Raw::clazz),
                    Codec.STRING.fieldOf("item").forGetter(Raw::item),
                    lowercase(BedrockType.class, "bedrock family").listOf()
                            .optionalFieldOf("families", List.of()).forGetter(Raw::families),
                    TintCodec.CODEC.optionalFieldOf("tint").forGetter(Raw::tint),
                    Codec.STRING.optionalFieldOf("problem").forGetter(Raw::problem))
                    .apply(instance, Raw::new));

    /**
     * <b>Decoding resolves the tint</b>, so a definition always carries a concrete colour.
     *
     * <p>Not a tidiness point. This registry is synced, and the same codec writes the network form —
     * so resolving on decode means the server averages the item's texture <i>once</i> and sends the
     * answer, instead of both sides averaging independently and hoping they agree. They would not
     * always: {@link com.tarosie.granularity.core.TextureTint} reads the PNG off the classpath, and a
     * dedicated server has no vanilla assets at all. A grain backed by a vanilla item would have been
     * rejected on the server and accepted on the client, which is the worst of both — the client
     * deriving rock the server does not have.
     */
    private static final Codec<GrainDefinition> STRICT = RAW.flatXmap(
            GrainDefinition::resolve,
            definition -> DataResult.success(new Raw(definition.clazz(), definition.item(),
                    definition.families(), Optional.of(definition.tint()), definition.problem())));

    /**
     * <b>Decoding never fails.</b> A definition that cannot be read becomes an inert one carrying the
     * reason, which {@code GrainRegistry} logs and skips.
     *
     * <p>This exists because a datapack registry would otherwise abort the world load over a single
     * typo, and here that cure is worse than the disease. The instinct is that terrain data must be
     * strict — a grain missing at generation time bakes a world without it, and no later fix can put
     * the ore back. <b>That instinct is wrong for this mod</b>, and wrong for its central reason:
     * natural blocks store nothing. Composition is derived from position and salt every time it is
     * needed, and worldgen never consults the roster at all — it places {@code natural_stone} and
     * that is the whole of it. So a grain missing today and defined tomorrow is simply <i>there</i>
     * tomorrow, in chunks generated long before, with no seam and nothing to migrate.
     *
     * <p>What a skipped grain does cost is small and bounded: ore mined while it was missing is
     * already an item, and items do store their composition. A stack in a chest keeps the stone it
     * was mined as. Weighed against refusing to open the world at all, that is the better trade.
     *
     * <p>The reason travels with the definition rather than being logged and dropped, so the client
     * skips exactly what the server skipped and says the same thing about it.
     */
    public static final Codec<GrainDefinition> CODEC = new Codec<>() {

        @Override
        public <T> DataResult<com.mojang.datafixers.util.Pair<GrainDefinition, T>> decode(
                com.mojang.serialization.DynamicOps<T> ops, T input) {
            DataResult<com.mojang.datafixers.util.Pair<GrainDefinition, T>> strict =
                    STRICT.decode(ops, input);
            if (strict.result().isPresent()) {
                return strict;
            }
            String why = strict.error().orElseThrow().message();
            return DataResult.success(
                    com.mojang.datafixers.util.Pair.of(unusable(why), ops.empty()));
        }

        @Override
        public <T> DataResult<T> encode(GrainDefinition value, com.mojang.serialization.DynamicOps<T> ops,
                                        T prefix) {
            return STRICT.encode(value, ops, prefix);
        }
    };

    /**
     * A definition that parsed but should not become a grain, carrying why.
     *
     * <p>Shaped as air deliberately: if anything ever did read one of these past the check in
     * {@code GrainRegistry}, the least harmful thing it could be is the grain that means nothing is
     * there — and air claims no item, so it cannot collide with a real one either.
     */
    private static GrainDefinition unusable(String why) {
        return new GrainDefinition(GrainClass.AIR, "minecraft:air", List.of(), 0, Optional.of(why));
    }

    /** Whether this is a definition at all, as against the record of one that could not be read. */
    public boolean isUsable() {
        return problem.isEmpty();
    }

    private static DataResult<GrainDefinition> resolve(Raw raw) {
        if (raw.problem().isPresent()) {
            return DataResult.success(new GrainDefinition(raw.clazz(), raw.item(), raw.families(),
                    raw.tint().orElse(0), raw.problem()));
        }
        if (raw.tint().isPresent()) {
            return DataResult.success(new GrainDefinition(raw.clazz(), raw.item(), raw.families(),
                    raw.tint().get(), Optional.empty()));
        }
        try {
            return DataResult.success(new GrainDefinition(raw.clazz(), raw.item(), raw.families(),
                    com.tarosie.granularity.core.TextureTint.averageOf(raw.item()), Optional.empty()));
        } catch (RuntimeException unreadable) {
            return DataResult.error(unreadable::getMessage);
        }
    }

    /** What the roster needs, given the name the file's path supplies. */
    public GrainSpec toSpec(String name) {
        return new GrainSpec(name, clazz, tint, item, Set.copyOf(families));
    }

    /**
     * An enum written the way a datapack author would write it — {@code precious_ore}, not
     * {@code PRECIOUS_ORE}. An unknown value names itself in the error rather than falling back,
     * because a grain silently demoted to the wrong class would generate in the wrong places.
     */
    private static <E extends Enum<E>> Codec<E> lowercase(Class<E> type, String what) {
        return Codec.STRING.comapFlatMap(written -> {
            try {
                return DataResult.success(Enum.valueOf(type, written.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return DataResult.error(() -> "Unknown " + what + " '" + written + "'; expected one of "
                        + java.util.Arrays.stream(type.getEnumConstants())
                                .map(value -> value.name().toLowerCase(Locale.ROOT)).toList());
            }
        }, value -> value.name().toLowerCase(Locale.ROOT));
    }

    /** Hex or a bare number, because nobody writes a colour as 10162462. */
    private static final class TintCodec {

        private static final Codec<Integer> CODEC =
                Codec.either(Codec.STRING, Codec.INT).comapFlatMap(
                        either -> either.map(TintCodec::parse, DataResult::success),
                        Either::right);

        private static DataResult<Integer> parse(String written) {
            String digits = written.startsWith("#") ? written.substring(1) : written;
            try {
                int value = Integer.parseInt(digits, 16);
                return value < 0 || value > 0xFFFFFF
                        ? DataResult.error(() -> "Tint out of range: " + written)
                        : DataResult.success(value);
            } catch (NumberFormatException notHex) {
                return DataResult.error(() -> "Not a colour: " + written + " (try \"#9B111E\")");
            }
        }

        private TintCodec() {
        }
    }
}
