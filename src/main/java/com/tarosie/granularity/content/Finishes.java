package com.tarosie.granularity.content;

import com.mojang.serialization.Codec;
import com.tarosie.granularity.core.Finish;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * Reading and writing a block's {@link Finish} — the hand side of it.
 *
 * <p>Separate from the enum itself so {@link Finish} stays free of Minecraft types, the same split
 * {@link Dyes} uses and for the same reason: the concept is testable without a game, and only the
 * plumbing needs one. The codecs live here rather than in {@link GranularityComponents} because
 * touching that class loads deferred registrations and needs a bootstrapped game, which a unit test
 * does not have.
 *
 * <p><b>Absent means cobbled.</b> Every block that existed before finishes did is unworked, so a
 * missing component reads as {@link Finish#COBBLED} and no migration is needed — and a cobbled block
 * writes nothing, so it stays byte-identical to one from before this existed.
 */
public final class Finishes {

    /** Stored as its name, not its ordinal, so reordering the enum cannot rewrite saved blocks. */
    public static final Codec<Finish> CODEC =
            Codec.STRING.xmap(Finish::byId, Finish::id);

    /**
     * The same, but for names a person typed: an unknown one is an error, not a cobbled block.
     *
     * <p>{@link #CODEC} forgives, because a block saved by a version that had a finish this one has
     * never heard of should still load. A <b>recipe</b> is the opposite case — nothing wrote it but an
     * author, so an unrecognised name is a typo, and reading {@code "smoth"} as cobbled would produce
     * a recipe that quietly accepts rubble where it demanded worked stone. Datapack load reports the
     * failure by name and skips the one recipe, which is loud and fixable.
     */
    public static final Codec<Finish> STRICT_CODEC = Codec.STRING.comapFlatMap(
            id -> {
                Finish finish = Finish.find(id);
                return finish == null
                        ? com.mojang.serialization.DataResult.error(() -> "Unknown finish: " + id)
                        : com.mojang.serialization.DataResult.success(finish);
            },
            Finish::id);

    public static final StreamCodec<ByteBuf, Finish> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(Finish::byId, Finish::id);

    private Finishes() {
    }

    /** What has been done to the block this stack will place. Never null. */
    public static Finish of(ItemStack stack) {
        Finish finish = stack.get(GranularityComponents.FINISH.get());
        return finish == null ? Finish.COBBLED : finish;
    }

    /**
     * Records a finish, writing nothing when there is nothing to record.
     *
     * <p>Cobbled removes the component rather than storing it. That keeps an unworked block's stack
     * identical to one made before finishes existed — which matters because components decide whether
     * two stacks merge, and a cobblestone that quietly stopped stacking with an older cobblestone
     * would be a strange bug to chase.
     */
    public static void apply(ItemStack stack, Finish finish) {
        if (finish == null || finish == Finish.COBBLED) {
            stack.remove(GranularityComponents.FINISH.get());
        } else {
            stack.set(GranularityComponents.FINISH.get(), finish);
        }
    }
}
