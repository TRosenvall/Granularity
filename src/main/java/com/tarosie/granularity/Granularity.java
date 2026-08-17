package com.tarosie.granularity;

import com.tarosie.granularity.content.GranularityBlocks;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.content.GranularityItems;
import com.tarosie.granularity.network.SaltLifecycle;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Granularity — every natural block is a composition of nine objects, and the world evolves.
 *
 * <p>The design lives in {@code toy_geology_model/GEOLOGY_MOD_DESIGN.md}, with the measurements that
 * amended it in {@code PROTOTYPE_FINDINGS.md} and the build order in {@code IMPLEMENTATION_PLAN.md}.
 * Two rules run through everything and are worth knowing before changing anything here:
 * <b>derive, don't store</b>, and <b>integers all the way down</b>.
 */
@Mod(Granularity.MODID)
public class Granularity {

    public static final String MODID = "granularity";

    public static final Logger LOGGER = LogUtils.getLogger();

    public Granularity(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        GranularityComponents.register(modEventBus);
        GranularityItems.register(modEventBus);
        GranularityBlocks.register(modEventBus);
        com.tarosie.granularity.content.GranularityOverlays.register(modEventBus);
        com.tarosie.granularity.content.GranularityInteractions.register(modEventBus);
        com.tarosie.granularity.content.PistonMoves.register(modEventBus);
        com.tarosie.granularity.content.MossSpread.register(modEventBus);
        com.tarosie.granularity.recipe.GranularityRecipes.register(modEventBus);

        modEventBus.addListener(SaltLifecycle::registerPayloads);
        modEventBus.addListener(com.tarosie.granularity.content.GrainRegistry::register);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Granularity: composition is derived, never stored.");
    }
}
