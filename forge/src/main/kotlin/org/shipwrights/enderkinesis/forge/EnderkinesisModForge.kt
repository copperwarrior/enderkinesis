package org.shipwrights.enderkinesis.forge

import dev.architectury.platform.forge.EventBuses
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FlowerPotBlock
import java.util.function.Consumer
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.common.Mod
import thedarkcolour.kotlinforforge.forge.MOD_BUS
import net.minecraftforge.fml.loading.FMLEnvironment
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.forge.client.captureWyllandTomeBakes
import org.shipwrights.enderkinesis.forge.client.clientInit
import org.shipwrights.enderkinesis.forge.client.onAddPackFinders
import org.shipwrights.enderkinesis.forge.client.onRenderGuiPost
import org.shipwrights.enderkinesis.forge.client.onRenderGuiPre
import org.shipwrights.enderkinesis.forge.client.onRenderLevelStage
import org.shipwrights.enderkinesis.forge.client.registerAdditionalModels
import org.shipwrights.enderkinesis.forge.client.registerDimensionEffects
import org.shipwrights.enderkinesis.forge.client.registerLayerDefinitions
import org.shipwrights.enderkinesis.forge.client.registerParticleProviders
import org.shipwrights.enderkinesis.forge.client.registerShaders
import org.shipwrights.enderkinesis.forge.compat.curios.CuriosCompat
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Forge-side entrypoint. All content is registered through Architectury in [EnderkinesisMod.init];
 * the crepusculite geode is injected into the Small End Islands biome via
 * `data/enderkinesis/forge/biome_modifier/`, so no Forge-side worldgen code is needed.
 */
@Mod(EnderkinesisMod.MOD_ID)
class EnderkinesisModForge {
    init {
        val modEventBus = MOD_BUS
        EventBuses.registerModEventBus(EnderkinesisMod.MOD_ID, modEventBus)

        EnderkinesisMod.init()
        IchaericInkStorageForge.init()
        OarscribeInkStorageForge.init()
        org.shipwrights.enderkinesis.platform.ExplosionHooks.startCancelled = { level, explosion ->
            net.minecraftforge.event.ForgeEventFactory.onExplosionStart(level, explosion)
        }

        // Wire accessory-mod compat (no-op if the mod isn't loaded).
        CuriosCompat.init()
        modEventBus.addListener(::commonSetup)

        if (FMLEnvironment.dist.isClient) {
            // Before the renderer event and the options load; FMLClientSetupEvent is too late.
            org.shipwrights.enderkinesis.client.EnderkinesisModClient.initClientEarly()
            modEventBus.listen(::clientInit)
            modEventBus.listen(::registerLayerDefinitions)
            modEventBus.listen(::registerParticleProviders)
            modEventBus.listen(::registerAdditionalModels)
            modEventBus.listen(::captureWyllandTomeBakes)
            modEventBus.listen(::registerShaders)
            modEventBus.listen(::registerDimensionEffects)
            modEventBus.listen(::onAddPackFinders)
            // RenderLevelStageEvent fires on the Forge event bus, not
            // the mod bus — registered separately.
            MinecraftForge.EVENT_BUS.listen(::onRenderLevelStage)
            MinecraftForge.EVENT_BUS.listen(::onRenderGuiPre)
            MinecraftForge.EVENT_BUS.listen(::onRenderGuiPost)
        }
    }

    // The vanilla constructor keys the plant by registry name, which can be null while the plant registers.
    private fun commonSetup(event: FMLCommonSetupEvent) {
        event.enqueueWork {
            val pot = Blocks.FLOWER_POT as FlowerPotBlock
            for ((plant, potted) in EKBlocks.POTTED_PLANTS) pot.addPlant(plant.id, potted)
        }
    }
}

// TypeTools often cannot read the event type of a Kotlin function reference, which crashes at startup; pass the class.
private inline fun <reified T : Event> IEventBus.listen(noinline handler: (T) -> Unit) =
    addListener(EventPriority.NORMAL, false, T::class.java, Consumer { handler(it) })
