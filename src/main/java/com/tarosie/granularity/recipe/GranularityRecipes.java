package com.tarosie.granularity.recipe;

import com.tarosie.granularity.Granularity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Recipes whose output depends on their input, which JSON cannot express. */
public final class GranularityRecipes {

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, Granularity.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, CombineRecipe.Serializer> COMBINE_SERIALIZER =
            SERIALIZERS.register("combine", CombineRecipe.Serializer::new);

    public static final DeferredHolder<RecipeSerializer<?>, SmeltAverageRecipe.Serializer> SMELT_SERIALIZER =
            SERIALIZERS.register("smelt_average", SmeltAverageRecipe.Serializer::new);

    public static final DeferredHolder<RecipeSerializer<?>, GravelRecipe.Serializer> GRAVEL_SERIALIZER =
            SERIALIZERS.register("gravel", GravelRecipe.Serializer::new);

    public static final DeferredHolder<RecipeSerializer<?>, SlabRecipe.Serializer> SLAB_SERIALIZER =
            SERIALIZERS.register("slab", SlabRecipe.Serializer::new);

    public static final DeferredHolder<RecipeSerializer<?>, CutShapeRecipe.Serializer> CUT_SHAPE_SERIALIZER =
            SERIALIZERS.register("cut_shape", CutShapeRecipe.Serializer::new);

    /** Cutting one stonework style into another: see {@link StoneCutRecipe}. */
    public static final DeferredHolder<RecipeSerializer<?>, StoneCutRecipe.Serializer> STONE_CUT_SERIALIZER =
            SERIALIZERS.register("stone_cut", StoneCutRecipe.Serializer::new);

    /** Shared by every vanilla block rebuilt on grains: see {@link StonewareRecipe}. */
    public static final DeferredHolder<RecipeSerializer<?>, StonewareRecipe.Serializer> STONEWARE_SERIALIZER =
            SERIALIZERS.register("stoneware", StonewareRecipe.Serializer::new);

    /** Working a style onto stone in the crafting grid: see {@link ApplyStyleRecipe}. */
    public static final DeferredHolder<RecipeSerializer<?>, ApplyStyleRecipe.Serializer>
            APPLY_STYLE_SERIALIZER =
                    SERIALIZERS.register("apply_style", ApplyStyleRecipe.Serializer::new);

    /** The same, but built from stone worked to a stated finish: see {@link WorkedStonewareRecipe}. */
    public static final DeferredHolder<RecipeSerializer<?>, WorkedStonewareRecipe.Serializer>
            WORKED_STONEWARE_SERIALIZER =
                    SERIALIZERS.register("worked_stoneware", WorkedStonewareRecipe.Serializer::new);

    private GranularityRecipes() {
    }

    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
