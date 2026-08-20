package com.tarosie.granularity.world;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.content.CompositionHolder;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.CompositionFunction;
import com.tarosie.granularity.core.Grain;
import com.tarosie.granularity.core.Grains;
import com.tarosie.granularity.core.WaterTable;
import com.tarosie.granularity.core.WorldSalt;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Reads out what the block you are looking at is made of.
 *
 * <p>Everything in this mod is derived from a composition and almost none of it is legible from
 * outside. Porosity was the case that forced this: air holds no colour and draws no layer, so a
 * block a third void looked exactly like solid stone, and the honest answer to "how do I find porous
 * stone" was that you could not — not by looking, not by mining, not by any means the game offered.
 *
 * <p>A command rather than a HUD or a tooltip, because this is for checking that the world is what
 * the field says it is, and that is a question asked deliberately rather than continuously. It also
 * costs nothing when nobody asks it.
 *
 * <p>Works on natural stone, which stores nothing and is derived from position, and on crafted
 * composites, which carry theirs — so the same command answers for both halves of the mod.
 */
@EventBusSubscriber(modid = Granularity.MODID)
public final class CompositionCommand {

    /** How far to look for a block, matching a creative-mode reach with room to spare. */
    private static final double REACH = 12.0;

    /**
     * How far {@code /granularity porous} searches, in blocks each way.
     *
     * <p>Sixteen is a cube of thirty-five thousand blocks, each of which may need its composition
     * derived — a tenth of a second or so, once, when somebody asks. That is affordable for a
     * command typed by hand and would not be for anything on a tick.
     */
    private static final int SEARCH = 16;

    /**
     * How far {@code /granularity porous} searches downward, in blocks.
     *
     * <p>Further than it searches sideways, because our stone is the terrain's <i>body</i> and
     * vanilla's surface rules cover it — sand and sandstone in a desert, terracotta in badlands, and
     * deeper in both than a symmetric box would reach from someone standing on top of it.
     */
    private static final int DEPTH = 40;

    private CompositionCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("granularity")
                .then(Commands.literal("composition").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    return report(context.getSource(), player);
                }))
                .then(Commands.literal("porous")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            return findPorous(context.getSource(), player, 1);
                        })
                        .then(Commands.argument("minimumFree", IntegerArgumentType.integer(1, Composition.SLOTS))
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    return findPorous(context.getSource(), player,
                                            IntegerArgumentType.getInteger(context, "minimumFree"));
                                })))
                .then(Commands.literal("spring")
                        // Level 2: it digs a block out and places water. Harmless in a test world and
                        // not something an ordinary player should be able to do to someone else's.
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            return buildSpring(context.getSource(), player);
                        }));
        event.getDispatcher().register(command);
    }

    private static int report(CommandSourceStack source, ServerPlayer player) {
        HitResult hit = player.pick(REACH, 0.0F, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look at a block first."));
            return 0;
        }
        BlockPos pos = block.getBlockPos();

        Composition composition = compositionAt(player, pos);
        if (composition == null) {
            source.sendFailure(Component.literal(
                    "That block has no composition: " + player.level().getBlockState(pos)
                            .getBlock().getName().getString()));
            return 0;
        }

        // Porosity and water are separated because they answer different questions and the second is
        // the one with no other way to be seen: how much rock is missing, versus how much of the hole
        // is wet. The water table's height comes along too — it is the field the water count is
        // derived from, and reading them side by side is what tells you whether a dry pore is dry
        // because it is above the table or because the rock is tight.
        String head = pos.toShortString()
                + "  porosity " + composition.porosity() + "/" + Composition.SLOTS
                + "  water " + composition.water()
                + "  free " + composition.freeSlots();
        source.sendSuccess(() -> Component.literal(head).withStyle(ChatFormatting.AQUA), false);
        if (WorldSalt.ServerView.isPresent()) {
            long salt = WorldSalt.ServerView.get().value();
            // The stored half as well as the derived one. Reading only the composition here would
            // report the equilibrium answer for a block that water has actually moved into, which is
            // the exact confusion this command exists to prevent.
            int actual = GranularityWater.waterAt(player.serverLevel(), pos, salt);
            String table = String.format(Locale.ROOT,
                    "  water table y=%.1f, saturation %.2f  |  holding %d (baseline %d)",
                    WaterTable.elevation(pos.getX(), pos.getZ(), salt),
                    WaterTable.saturation(pos.getX(), pos.getY(), pos.getZ(), salt),
                    actual, composition.water());
            source.sendSuccess(() -> Component.literal(table).withStyle(ChatFormatting.DARK_AQUA),
                    false);
            String tier = "  active water patches " + WaterTicker.activePatches(player.serverLevel());
            source.sendSuccess(() -> Component.literal(tier).withStyle(ChatFormatting.DARK_GRAY),
                    false);
        }
        tally(composition).forEach((name, count) -> source.sendSuccess(() -> Component.literal(
                "  " + count + "  " + name).withStyle(ChatFormatting.GRAY), false));
        return 1;
    }

    /**
     * Points at the nearest rock that could actually take a drop of water.
     *
     * <p>Written because finding one by hand turned out to be genuinely hard, which is worth stating
     * plainly rather than treating as a testing inconvenience: <b>rock below the water table is
     * already saturated</b>, so its free slots are zero and nothing can soak into it. That is correct
     * — it is what saturated means — but it means "porous rock" and "rock water can enter" are
     * different sets, and only the second is any use for watching infiltration. Add that the rock
     * type varies by region, so the sandstone you are looking for may be limestone where you are
     * standing, and the search by eye is hopeless.
     *
     * <p>Reports the block's rock and free slots so the answer is checkable rather than trusted.
     */
    private static int findPorous(CommandSourceStack source, ServerPlayer player, int minimumFree) {
        if (!WorldSalt.ServerView.isPresent()) {
            source.sendFailure(Component.literal("No world salt; nothing is derived yet."));
            return 0;
        }
        long salt = WorldSalt.ServerView.get().value();
        BlockPos origin = player.blockPosition();

        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        Composition bestComposition = null;
        // Counted, not assumed. The first version of this reported "everything here is tight or
        // saturated" whenever it found nothing — a cause it had never checked. Standing on a desert
        // summit, where the surface rules lay vanilla sand and sandstone over our stone, it examined
        // almost no natural stone at all and confidently blamed the water table. A search that finds
        // nothing has to say what it looked at.
        int stoneSeen = 0;
        int tight = 0;
        int saturated = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SEARCH; dx <= SEARCH; dx++) {
            for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                // Deeper than it is wide, because our stone is under whatever the surface rules put
                // on top of it, and "dig down" is the answer often enough to search that way.
                for (int dy = -DEPTH; dy <= SEARCH; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!player.level().getBlockState(cursor).is(
                            com.tarosie.granularity.content.GranularityBlocks.NATURAL_STONE.get())) {
                        continue;
                    }
                    stoneSeen++;
                    Composition c = CompositionFunction.stone(
                            cursor.getX(), cursor.getY(), cursor.getZ(), salt);
                    if (c.porosity() <= 0) {
                        tight++;
                        continue;
                    }
                    if (c.freeSlots() < minimumFree) {
                        saturated++;
                        continue;
                    }
                    int distance = dx * dx + dy * dy + dz * dz;
                    if (distance < bestDistance) {
                        best = cursor.immutable();
                        bestDistance = distance;
                        bestComposition = c;
                    }
                }
            }
        }

        if (best == null) {
            int examined = stoneSeen;
            int wasTight = tight;
            int wasSaturated = saturated;
            if (examined == 0) {
                source.sendSuccess(() -> Component.literal(
                        "No Granularity stone within " + SEARCH + " blocks across or " + DEPTH
                                + " down. You are on vanilla surface blocks — sand, sandstone, "
                                + "terracotta — which our stone sits underneath. Dig down.")
                        .withStyle(ChatFormatting.RED), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                            "Examined %d natural stone blocks: %d tight (no pores at all), %d porous "
                                    + "but already saturated. None can take water. Saturated means "
                                    + "below the water table, so try higher ground.",
                            examined, wasTight, wasSaturated))
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }

        BlockPos found = best;
        Composition composition = bestComposition;
        int examined = stoneSeen;
        int wasSaturated = saturated;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "Nearest rock that can take water: %s  free %d, porosity %d, water %d  (%.0f blocks away)",
                        found.toShortString(), composition.freeSlots(), composition.porosity(),
                        composition.water(), Math.sqrt(found.distSqr(origin))))
                .withStyle(ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "  out of %d natural stone blocks nearby, %d of them saturated",
                        examined, wasSaturated))
                .withStyle(ChatFormatting.DARK_GRAY), false);
        tally(composition).forEach((name, count) -> source.sendSuccess(() -> Component.literal(
                "  " + count + "  " + name).withStyle(ChatFormatting.GRAY), false));
        return 1;
    }

    /**
     * Builds the smallest arrangement that makes a spring happen, at rock where it can.
     *
     * <p>Exists because the natural way to test this — pour water on the ground and look for it lower
     * down — almost never works, for two reasons that are both correct behaviour. Water has to travel
     * through <i>connected</i> porous rock, and roughly half the blocks in a porous bed have a free
     * pore, so a run of four in a vertical line is about one chance in six; a single tight block stops
     * it dead, which is what an aquiclude is. And the simulation only runs in a patch a few blocks
     * across, so a chamber ten blocks down is never reached at all.
     *
     * <p>So this removes both variables rather than faking either. It finds real porous rock, opens
     * the block beneath it so there is somewhere for water to come out, and puts a source on top. One
     * block of rock between the water and the air: infiltrate, then seep, with nothing in between to
     * go wrong.
     *
     * <p><b>It cannot make rock porous.</b> Grains are derived from position, so there is nothing
     * stored to remove one from — see {@code docs/HYDROLOGY.md}. This finds a place where the field
     * already says yes.
     */
    private static int buildSpring(CommandSourceStack source, ServerPlayer player) {
        if (!WorldSalt.ServerView.isPresent()) {
            source.sendFailure(Component.literal("No world salt; nothing is derived yet."));
            return 0;
        }
        long salt = WorldSalt.ServerView.get().value();
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();

        BlockPos roof = null;
        Composition roofComposition = null;
        int bestDistance = Integer.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SEARCH; dx <= SEARCH; dx++) {
            for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                for (int dy = -DEPTH; dy <= SEARCH; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!isNaturalStone(level, cursor)) {
                        continue;
                    }
                    // Room above for the water, and stone below that can be opened into a chamber.
                    if (!level.getBlockState(cursor.above()).canBeReplaced()) {
                        continue;
                    }
                    if (!isNaturalStone(level, cursor.below())) {
                        continue;
                    }
                    Composition c = CompositionFunction.stone(
                            cursor.getX(), cursor.getY(), cursor.getZ(), salt);
                    if (c.freeSlots() <= 0) {
                        continue;
                    }
                    int distance = dx * dx + dy * dy + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        roof = cursor.immutable();
                        roofComposition = c;
                    }
                }
            }
        }

        if (roof == null) {
            source.sendFailure(Component.literal(
                    "No porous rock nearby with open space above it and stone below. Try somewhere "
                            + "with rock overhead — a hillside, or a few blocks underground."));
            return 0;
        }

        BlockPos ceiling = roof;
        Composition composition = roofComposition;
        // The chamber, then the water. In that order: filling first would let the source spread into
        // the space before there was a roof between them.
        level.setBlock(ceiling.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(ceiling.above(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        WaterTicker.disturb(level, ceiling);

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "Spring rig at %s — %d free slot(s) of rock, water above, open air below.",
                        ceiling.toShortString(), composition.freeSlots()))
                .withStyle(ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.literal(
                        "  Stand under it and watch the underside. Expect drips at the top face as it "
                                + "soaks, then water below. A placed source is infinite, so it should "
                                + "keep going for about 20 seconds and then stop.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static boolean isNaturalStone(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(
                com.tarosie.granularity.content.GranularityBlocks.NATURAL_STONE.get());
    }

    /**
     * The composition of a block, however it happens to know it.
     *
     * <p>A crafted block is asked; natural stone is derived, because §4 forbids it a block entity and
     * so there is nothing to ask.
     */
    private static Composition compositionAt(ServerPlayer player, BlockPos pos) {
        if (player.level().getBlockEntity(pos) instanceof CompositionHolder held) {
            return held.composition();
        }
        if (!player.level().getBlockState(pos).is(
                com.tarosie.granularity.content.GranularityBlocks.NATURAL_STONE.get())) {
            return null;
        }
        if (!WorldSalt.ServerView.isPresent()) {
            return null;
        }
        return CompositionFunction.stone(
                pos.getX(), pos.getY(), pos.getZ(), WorldSalt.ServerView.get().value());
    }

    /** Grain names and how many slots each holds, in slot order so air lands where it falls. */
    private static Map<String, Integer> tally(Composition composition) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < Composition.SLOTS; slot++) {
            Grain grain = composition.grainAt(slot);
            String name = grain == null ? "unknown" : grain.name();
            counts.merge(name, 1, Integer::sum);
        }
        return counts;
    }
}
