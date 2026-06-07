package org.shipwrights.enderkinesis.forge

import dev.architectury.platform.forge.EventBuses
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.fml.loading.FMLEnvironment
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.forge.client.EnderkinesisModForgeClient

/**
 * Forge-side entrypoint. All content is registered through Architectury in [EnderkinesisMod.init];
 * the crepusculite geode is injected into the Small End Islands biome via
 * `data/forge/biome_modifier/`, so no Forge-side worldgen code is needed.
 */
@Mod(EnderkinesisMod.MOD_ID)
class EnderkinesisModForge {
    init {
        val modEventBus = FMLJavaModLoadingContext.get().modEventBus
        EventBuses.registerModEventBus(EnderkinesisMod.MOD_ID, modEventBus)

        EnderkinesisMod.init()

        if (FMLEnvironment.dist.isClient) {
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::clientInit)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::registerLayerDefinitions)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::registerAdditionalModels)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::captureWyllandTomeBakes)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::registerDimensionEffects)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::onAddPackFinders)
        }
    }
}
