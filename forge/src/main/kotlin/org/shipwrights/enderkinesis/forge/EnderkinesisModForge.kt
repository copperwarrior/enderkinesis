package org.shipwrights.enderkinesis.forge

import dev.architectury.platform.forge.EventBuses
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.fml.loading.FMLEnvironment
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.forge.client.EnderkinesisModForgeClient
import org.shipwrights.enderkinesis.forge.compat.curios.CuriosCompat

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
        IchaericInkStorageForge.init()
        org.shipwrights.enderkinesis.platform.ExplosionHooks.startCancelled = { level, explosion ->
            net.minecraftforge.event.ForgeEventFactory.onExplosionStart(level, explosion)
        }

        // Wire accessory-mod compat (no-op if the mod isn't loaded).
        CuriosCompat.init()

        if (FMLEnvironment.dist.isClient) {
            // Before the renderer event and the options load; FMLClientSetupEvent is too late.
            org.shipwrights.enderkinesis.client.EnderkinesisModClient.initClientEarly()
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::clientInit)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::registerLayerDefinitions)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::registerParticleProviders)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::registerAdditionalModels)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::captureWyllandTomeBakes)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::registerShaders)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::registerDimensionEffects)
            modEventBus.addListener(EnderkinesisModForgeClient.Companion::onAddPackFinders)
            // RenderLevelStageEvent fires on the Forge event bus, not
            // the mod bus — registered separately.
            MinecraftForge.EVENT_BUS.addListener(EnderkinesisModForgeClient.Companion::onRenderLevelStage)
            MinecraftForge.EVENT_BUS.addListener(EnderkinesisModForgeClient.Companion::onRenderGuiPre)
            MinecraftForge.EVENT_BUS.addListener(EnderkinesisModForgeClient.Companion::onRenderGuiPost)
        }
    }
}
