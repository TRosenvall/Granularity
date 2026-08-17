package com.tarosie.granularity.client;

import com.tarosie.granularity.content.Coating;
import com.tarosie.granularity.content.Dyes;
import com.tarosie.granularity.content.Moss;
import com.tarosie.granularity.content.Overlay;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import com.tarosie.granularity.content.Finishes;
import com.tarosie.granularity.core.Finish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * The same overlays, on the item in your hand.
 *
 * <p>An item has no block entity, so it cannot be handed {@link ModelData} the way a placed block
 * can. What it has is {@link ItemOverrides}, which vanilla consults per stack — so the overlays are
 * read off the stack's component there and folded into a model that carries them as a constant.
 *
 * <p>This replaces an {@code ItemProperties} predicate, which worked for exactly one overlay and
 * could never work for more: a predicate returns a number and a model can only branch on it, so N
 * overlays would need 2<sup>N</sup> item models named in advance. Resolving per stack has no such
 * limit and needs no model files at all.
 */
public class OverlayItemModel extends BakedModelWrapper<BakedModel> {

    private final ItemOverrides overrides;

    public OverlayItemModel(BakedModel wrapped) {
        super(wrapped);
        this.overrides = new Overrides(wrapped, wrapped.getOverrides());
    }

    @Override
    public ItemOverrides getOverrides() {
        return overrides;
    }

    private static final class Overrides extends ItemOverrides {

        private final BakedModel bare;
        private final ItemOverrides wrapped;
        // One model per distinct appearance, not per stack: resolve() runs for every item drawn in
        // every inventory slot, every frame. Keyed by the whole state rather than by the coating
        // alone, so a red block and a blue one of the same composition are not one cached model.
        private final Map<Appearance, BakedModel> byAppearance = new ConcurrentHashMap<>();

        /**
         * Everything that decides how a stack is drawn.
         *
         * <p>The finish is part of the key and not an afterthought: it was left out at first, and a
         * smooth block and a cobbled one with the same dye then shared one cached model — so a stack
         * drew as whichever of the two happened to be resolved first, and washing the dye off changed
         * the key and appeared to "fix" it.
         */
        private record Appearance(OverlayBakedModel.State state, Finish finish) {
        }

        private Overrides(BakedModel bare, ItemOverrides wrapped) {
            this.bare = bare;
            this.wrapped = wrapped;
        }

        @Nullable
        @Override
        public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level,
                                  @Nullable LivingEntity entity, int seed) {
            BakedModel resolved = wrapped.resolve(model, stack, level, entity, seed);
            BakedModel base = resolved != null ? resolved : bare;
            // The finish picks the surface before anything is drawn on it, exactly as
            // FinishBakedModel does for the placed block -- otherwise a worked block would render
            // one way in the world and another in the hand. An item has no model data, so the finish
            // is read off the stack here and fixed into the model.
            Finish finish = Finishes.of(stack);
            if (!finish.showsGrains()) {
                base = FinishBakedModel.of(base, finish);
            }
            Coating overlays = Moss.of(stack);
            Dyes dyes = Dyes.of(stack);
            if (overlays.isEmpty() && dyes.isEmpty()) {
                return base;
            }
            // The item shows the faces the block actually carries, not all six: a stack mossy only
            // on top is drawn mossy only on top, and a stack dyed only on its north side is drawn
            // dyed only there. That is the honest picture of what you are holding — and it is also
            // what makes the inventory the place you check before placing.
            // The item has to answer the same question the block does, or a dyed gravel would look
            // one way in the world and another in the hand.
            boolean whole = stack.getItem() instanceof net.minecraft.world.item.BlockItem item
                    && item.getBlock().defaultBlockState()
                            .is(com.tarosie.granularity.content.GranularityTags.DYED_WHOLE);
            // Both halves get the same coating, because no *item* is ever two halves — a slab item is
            // one slab however it will sit once placed, so there has never been a second set to
            // carry. What there can be is a second stone: a stonecutter draws its upper section at
            // tint 10, and leaving that set empty would put moss on the item's body and none on its
            // top while the placed block wore it everywhere. Same question, same answer, both sides.
            // See Moss.hasTwoHalves for the block's half of this.
            OverlayBakedModel.State state =
                    new OverlayBakedModel.State(overlays, overlays, dyes, dyes, whole);
            BakedModel chosen = base;
            return byAppearance.computeIfAbsent(new Appearance(state, finish),
                    fixed -> new Fixed(chosen, fixed.state()));
        }
    }

    /** An overlay model whose overlays are settled in advance rather than arriving as model data. */
    private static final class Fixed extends OverlayBakedModel {

        private final State state;

        private Fixed(BakedModel wrapped, State state) {
            super(wrapped);
            this.state = state;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState blockState, @Nullable Direction side,
                                        RandomSource rand, ModelData data, @Nullable RenderType renderType) {
            return super.getQuads(blockState, side, rand,
                    data.derive().with(OVERLAYS, state).build(), renderType);
        }
    }
}
