package com.tarosie.granularity.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Reading and writing a {@link Form} — the plumbing half, kept out of the enum.
 *
 * <p>The same split {@link Finishes} makes from {@code Finish}, and for the same reason: the concept
 * stays free of Minecraft types and so stays unit-testable without a running game.
 *
 * <p>There is no lenient codec here, unlike {@code Finishes}. A finish has to forgive an unknown name
 * because a <i>block saved by a later version</i> must still load, and cobbled is an honest fallback
 * for "history I cannot read". A form is never read off a saved block — it <b>is</b> the block, decided
 * by which registry entry you are holding — so the only thing that ever names one is a recipe someone
 * wrote, and an unknown name there is a typo with no honest default.
 */
public final class Forms {

    /** Strict by design: see the class note. */
    public static final Codec<Form> CODEC = Codec.STRING.comapFlatMap(
            id -> {
                Form form = Form.find(id);
                return form == null
                        ? DataResult.error(() -> "Unknown form: " + id)
                        : DataResult.success(form);
            },
            Form::id);

    /**
     * By name on the wire too.
     *
     * <p>Names rather than ordinals for the reason the whole mod sends names: an ordinal is a position
     * in a list, and reordering the enum would silently reinterpret every recipe in flight. See
     * {@code CompositionCodecs}, where the same argument retired grain ids from the network.
     */
    public static final StreamCodec<ByteBuf, Form> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(
                    id -> {
                        Form form = Form.find(id);
                        if (form == null) {
                            throw new IllegalStateException("Unknown form on the wire: " + id);
                        }
                        return form;
                    },
                    Form::id);

    private Forms() {
    }
}
