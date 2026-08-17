package com.tarosie.granularity.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;

/**
 * Moss creeping onto the stone around something you made grow.
 *
 * <p>Moss used to be applied by sneak-clicking a block with a moss block in hand, which worked and
 * was wrong: it made moss a paint, and the point of moving overlays to
 * {@linkplain Coating per face} was that moss should look like it arrived rather than like it was
 * put there. Bonemeal is the gesture that already means "make things grow", so moss rides along with
 * it — green the ground, and the wall behind you starts to take.
 *
 * <p>This is not the spreading ecosystem itself; that is a bigger thing and may well be its own mod.
 * It is the smallest version that behaves like one, and it goes through exactly the call a real
 * spreader would use, so replacing it later means deleting this class and nothing else.
 *
 * <h2>The rules, and why</h2>
 * <ul>
 *   <li><b>A face must be exposed.</b> Moss needs air and light, and a face buried against another
 *       block cannot be seen anyway, so growing there would be work no one ever sees.</li>
 *   <li><b>Never the underside.</b> Real moss does grow on damp overhangs, but a block greening from
 *       below reads as a bug rather than as weather. The data still allows it — {@link Coating} has
 *       a bit for {@code DOWN} — so anything else may put it there deliberately.</li>
 *   <li><b>A few faces at most, by chance.</b> One application should nudge a wall, not soak it.</li>
 * </ul>
 */
public final class MossSpread {

    /** How far from the bonemeal the stone can be. Roughly "the wall you are standing next to". */
    private static final int RADIUS = 4;

    /** The chance any one bare face takes moss from a single application. */
    private static final float CHANCE = 0.25F;

    /** The most faces one application can green, so a wall does not go from clean to sodden. */
    private static final int LIMIT = 3;

    private MossSpread() {
    }

    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(MossSpread::onBonemeal);
    }

    /**
     * Rides along with a successful bonemealing rather than replacing it.
     *
     * <p>The event is not cancelled and its outcome is not touched, so whatever the player was
     * actually growing still grows. {@code isValidBonemealTarget} is the test for "this was a use
     * that made something grow" — bonemeal waved at bare stone does nothing here either.
     */
    private static void onBonemeal(BonemealEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !event.isValidBonemealTarget()) {
            return;
        }
        spreadAround(level, event.getPos());
    }

    /** Greens a few exposed faces of the composite stone near a position. */
    public static void spreadAround(ServerLevel level, BlockPos origin) {
        Overlay moss = GranularityOverlays.MOSS.get();
        List<Target> targets = bareFacesNear(level, origin, moss);
        if (targets.isEmpty()) {
            return;
        }
        RandomSource random = level.getRandom();
        Collections.shuffle(targets, new java.util.Random(random.nextLong()));
        int grown = 0;
        for (Target target : targets) {
            if (grown >= LIMIT) {
                return;
            }
            if (random.nextFloat() >= CHANCE) {
                continue;
            }
            if (grow(level, target, moss)) {
                grown++;
            }
        }
    }

    private record Target(BlockPos pos, Direction face, boolean upper) {
    }

    /**
     * Which half or halves of a block a face belongs to.
     *
     * <p>A double slab is two slabs, and each keeps its own coating — so writing moss to the lower
     * half when the player greened the top puts it on the box from y=0 to y=8, whose up face is
     * <i>inside</i> the block. The moss saves and never appears. Everything but a double slab has one
     * half; a double slab's top belongs to the upper, its bottom to the lower, and each of its sides
     * genuinely belongs to both, so both are offered and each greens its own half of that side.
     */
    private static boolean[] halvesFor(BlockState state, Direction face) {
        boolean isDouble = state.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)
                && state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                        == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE;
        if (!isDouble) {
            return new boolean[] {false};
        }
        if (face == Direction.UP) {
            return new boolean[] {true};
        }
        if (face == Direction.DOWN) {
            return new boolean[] {false};
        }
        return new boolean[] {false, true};
    }

    private static List<Target> bareFacesNear(Level level, BlockPos origin, Overlay moss) {
        List<Target> targets = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-RADIUS, -RADIUS, -RADIUS),
                origin.offset(RADIUS, RADIUS, RADIUS))) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof CompositeStone) || state.is(GranularityTags.MOSS_WONT_GROW)) {
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof CompositionHolder composite)) {
                continue;
            }
            for (Direction face : Direction.values()) {
                if (face == Direction.DOWN) {
                    continue;
                }
                BlockPos beyond = pos.relative(face);
                if (level.getBlockState(beyond).isSolidRender(level, beyond)) {
                    continue;
                }
                for (boolean upper : halvesFor(state, face)) {
                    Coating coating = upper ? composite.upperOverlays() : composite.overlays();
                    if (Coating.covers(coating.facesOf(moss), face)) {
                        continue;
                    }
                    // Immutable: BlockPos.betweenClosed hands out one cursor it keeps moving.
                    targets.add(new Target(pos.immutable(), face, upper));
                }
            }
        }
        return targets;
    }

    private static boolean grow(ServerLevel level, Target target, Overlay moss) {
        if (!(level.getBlockEntity(target.pos()) instanceof CompositionHolder composite)) {
            return false;
        }
        Coating coating = target.upper() ? composite.upperOverlays() : composite.overlays();
        Coating grown = coating.with(moss, target.face());
        if (grown == null) {
            return false;
        }
        composite.setOverlays(target.upper(), grown);
        level.levelEvent(2005, target.pos(), 0);
        return true;
    }
}
