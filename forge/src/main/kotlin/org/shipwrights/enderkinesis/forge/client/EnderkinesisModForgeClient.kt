package org.shipwrights.enderkinesis.forge.client

import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.client.Minecraft
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.ModelEvent
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent
import net.minecraftforge.client.event.RegisterShadersEvent
import net.minecraftforge.client.event.RenderGuiEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraft.client.renderer.ShaderInstance
import net.minecraftforge.event.AddPackFindersEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.resource.PathPackResources
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.CatalogerTomeWorldRenderer
import org.shipwrights.enderkinesis.client.EnderkinesisModClient
import org.shipwrights.enderkinesis.client.OrbBeamLineRenderer
import org.shipwrights.enderkinesis.client.ScryingClient
import org.shipwrights.enderkinesis.client.TomeSummonOverlay
import org.shipwrights.enderkinesis.client.registerDimensionEffects as registerCommonDimensionEffects
import org.shipwrights.enderkinesis.client.additionalModels
import org.shipwrights.enderkinesis.client.captureAdditionalModel
import org.shipwrights.enderkinesis.client.registerCoreShaders
import org.shipwrights.enderkinesis.client.registerModelLayers

/**
 * Forge client entrypoint. Routes `FMLClientSetupEvent` into the common
 * [EnderkinesisModClient.initClient], and registers platform-only resources that
 * have no Architectury abstraction on this version of the API:
 *
 *   - The Cataloger's model layer definition (`RegisterLayerDefinitions` mod-bus event).
 *   - The Sselith dimension's custom `DimensionSpecialEffects` (`RegisterDimensionSpecialEffectsEvent`
 *     mod-bus event). On Fabric this is done via `DimensionRenderingRegistry`; on Forge
 *     we use the dedicated event.
 *
 * Both listeners are wired onto the mod event bus in [EnderkinesisModForge]'s constructor.
 */
class EnderkinesisModForgeClient {
    companion object {
        fun clientInit(event: FMLClientSetupEvent) {
            // The event runs on a worker thread; the registrations are for the main thread.
            event.enqueueWork { EnderkinesisModClient.initClient() }
            org.shipwrights.enderkinesis.forge.compat.curios.CuriosClientCompat.init()
        }

        fun registerParticleProviders(event: net.minecraftforge.client.event.RegisterParticleProvidersEvent) {
            EnderkinesisModClient.registerParticleProviders(object : EnderkinesisModClient.ParticleRegistrar {
                override fun <T : net.minecraft.core.particles.ParticleOptions> register(
                    type: net.minecraft.core.particles.ParticleType<T>,
                    factory: (net.minecraft.client.particle.SpriteSet) -> net.minecraft.client.particle.ParticleProvider<T>,
                ) {
                    event.registerSpriteSet(type) { sprites -> factory(sprites) }
                }
            })
        }

        fun registerLayerDefinitions(event: EntityRenderersEvent.RegisterLayerDefinitions) {
            registerModelLayers { layer, definition -> event.registerLayerDefinition(layer) { definition() } }
        }

        fun registerAdditionalModels(event: ModelEvent.RegisterAdditional) {
            additionalModels().forEach(event::register)
        }

        fun registerShaders(event: RegisterShadersEvent) {
            registerCoreShaders { id, format, onLoad ->
                event.registerShader(ShaderInstance(event.resourceProvider, id, format)) { onLoad(it) }
            }
        }

        fun captureWyllandTomeBakes(event: ModelEvent.ModifyBakingResult) {
            for (id in additionalModels()) captureAdditionalModel(id, event.models[id])
        }

        fun onAddPackFinders(event: AddPackFindersEvent) {
            if (event.packType != PackType.CLIENT_RESOURCES) return
            val modFile = ModList.get()
                .getModFileById(EnderkinesisMod.MOD_ID)
                ?.file ?: return
            val packPath = modFile.findResource("resourcepacks/sselith")
            val pack = Pack.readMetaAndCreate(
                EnderkinesisMod.MOD_ID + ":sselith",
                Component.literal("Sselith"),
                true,  // alwaysEnabled — pack stays on without user toggle
                { pid -> PathPackResources(pid, true, packPath) },
                PackType.CLIENT_RESOURCES,
                Pack.Position.TOP,
                PackSource.BUILT_IN,
            )
            event.addRepositorySource { consumer -> consumer.accept(pack) }
        }

        /** Forge-bus listener for the level renderer's post-entity
         *  stage. Drives [CatalogerTomeWorldRenderer], which scans the
         *  level for catalogers with active summons and draws their
         *  floating tomes at world positions. Decoupled from
         *  `CatalogerRenderer` so the book stays visible whenever the
         *  level is being drawn — the cataloger's own frustum culling
         *  doesn't matter. */
        fun onRenderLevelStage(event: RenderLevelStageEvent) {
            val cameraPos = event.camera.position
            val bufferSource = Minecraft.getInstance().renderBuffers().bufferSource()
            when (event.stage) {
                RenderLevelStageEvent.Stage.AFTER_ENTITIES -> {
                    CatalogerTomeWorldRenderer.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    org.shipwrights.enderkinesis.client.YgannBarnacleDeathRenderer.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    // Entity pass, so that water drawn later composites over a waterlogged bud.
                    org.shipwrights.enderkinesis.client.WogorBudRenderer.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    org.shipwrights.enderkinesis.client.WoggakVineRenderer.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                }
                RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS -> {
                    // Orb-network beam line — after translucent blocks so it composites over
                    // water/glass and its additive bloom adds to whatever's behind it.
                    OrbBeamLineRenderer.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    // Lodestar Dart pulse-beam trails — same stage + render type as the orb
                    // network so the visual stays consistent.
                    org.shipwrights.enderkinesis.client.LodestarDartTrailRenderer.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    // Crepusculite Lattice virtual sea — additive mesh over the existing
                    // particle system. Toggle via CrepusculiteLatticeMeshRenderer.enabled
                    // (default true) or delete this block to revert to the particle-only path.
                    org.shipwrights.enderkinesis.client.CrepusculiteLatticeMeshRenderer.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    // Staff of Aegis, Staff of Fusion, Staff of Sundering, Echo Cannon: same stage as on Fabric.
                    org.shipwrights.enderkinesis.client.AegisClient.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    org.shipwrights.enderkinesis.client.FusionClient.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    org.shipwrights.enderkinesis.client.SunderingClient.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    org.shipwrights.enderkinesis.client.EchoCannonClient.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    org.shipwrights.enderkinesis.client.VineCutHighlight.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                }
                else -> {}
            }
        }

        // `ForgeGui.render` replaces `Gui.render` with no super call, so `GuiScryingMixin`
        // never runs on Forge. These two listeners do its work.
        fun onRenderGuiPre(event: RenderGuiEvent.Pre) {
            val graphics = event.guiGraphics
            ScryingClient.renderFadeOverlay(graphics)
            ScryingClient.renderBackground(graphics, event.partialTick)
            TomeSummonOverlay.renderBackground(graphics, event.partialTick)
            if (!ScryingClient.isActive()) return
            // The mixin hides the HUD during a scry. A cancel here does that, and it also
            // stops the Post event, so the foreground is drawn now.
            ScryingClient.renderForeground(graphics, event.partialTick)
            TomeSummonOverlay.renderForeground(graphics, event.partialTick)
            event.isCanceled = true
        }

        fun onRenderGuiPost(event: RenderGuiEvent.Post) {
            ScryingClient.renderForeground(event.guiGraphics, event.partialTick)
            TomeSummonOverlay.renderForeground(event.guiGraphics, event.partialTick)
        }

        fun registerDimensionEffects(event: RegisterDimensionSpecialEffectsEvent) {
            registerCommonDimensionEffects(event::register)
        }

    }
}
