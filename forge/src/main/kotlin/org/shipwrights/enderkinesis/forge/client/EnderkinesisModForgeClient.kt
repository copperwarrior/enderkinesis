package org.shipwrights.enderkinesis.forge.client

import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.resources.ResourceLocation
import net.minecraft.client.Minecraft
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.ModelEvent
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent
import net.minecraftforge.client.event.RegisterShadersEvent
import net.minecraftforge.client.event.RenderGuiEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import com.mojang.blaze3d.vertex.DefaultVertexFormat
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
import org.shipwrights.enderkinesis.client.SselithDimensionEffects
import org.shipwrights.enderkinesis.client.SureibjinDimensionEffects
import org.shipwrights.enderkinesis.client.WohlonnogondoniaDimensionEffects
import org.shipwrights.enderkinesis.client.WyllandTomeBEWLR
import org.shipwrights.enderkinesis.client.model.CatalogerModel
import org.shipwrights.enderkinesis.client.model.ConstriliarModel
import org.shipwrights.enderkinesis.client.model.HeartOfTheWildModel
import org.shipwrights.enderkinesis.client.model.PrismaticGoatModel
import org.shipwrights.enderkinesis.client.model.StatueBlackGoatModel
import org.shipwrights.enderkinesis.client.model.TightRobeArmorModel
import org.shipwrights.enderkinesis.client.model.StatueCatalogerModel
import org.shipwrights.enderkinesis.client.model.StatueCountingModel
import org.shipwrights.enderkinesis.client.model.StatueOwlModel
import org.shipwrights.enderkinesis.client.model.StatueSteveModel
import org.shipwrights.enderkinesis.client.model.StatueTentacledBeastModel
import org.shipwrights.enderkinesis.client.model.StatueTentaclesModel
import org.shipwrights.enderkinesis.client.model.StatueYellowTowerModel

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
        @JvmStatic
        fun clientInit(event: FMLClientSetupEvent) {
            // The event runs on a worker thread; the registrations are for the main thread.
            event.enqueueWork { EnderkinesisModClient.initClient() }
        }

        @JvmStatic
        fun registerParticleProviders(event: net.minecraftforge.client.event.RegisterParticleProvidersEvent) {
            EnderkinesisModClient.registerParticleProviders { type, factory ->
                event.registerSpriteSet(type) { sprites -> factory(sprites) }
            }
        }

        @JvmStatic
        fun registerLayerDefinitions(event: EntityRenderersEvent.RegisterLayerDefinitions) {
            event.registerLayerDefinition(CatalogerModel.LAYER_LOCATION, CatalogerModel::createBodyLayer)
            event.registerLayerDefinition(
                org.shipwrights.enderkinesis.client.model.WohlemModel.LAYER_LOCATION,
                org.shipwrights.enderkinesis.client.model.WohlemModel::createBodyLayer,
            )
            event.registerLayerDefinition(ConstriliarModel.LAYER_LOCATION, ConstriliarModel::createBodyLayer)
            event.registerLayerDefinition(HeartOfTheWildModel.LAYER_LOCATION, HeartOfTheWildModel::createBodyLayer)
            event.registerLayerDefinition(PrismaticGoatModel.LAYER_LOCATION, PrismaticGoatModel::createBodyLayer)
            // Robe layers, baked by the common TightRobeArmorRenderer.
            event.registerLayerDefinition(TightRobeArmorModel.INNER_LAYER, TightRobeArmorModel::createInnerLayer)
            event.registerLayerDefinition(TightRobeArmorModel.OUTER_LAYER, TightRobeArmorModel::createOuterLayer)
            event.registerLayerDefinition(TightRobeArmorModel.WITCH_HAT_LAYER, TightRobeArmorModel::createWitchHatLayer)
            event.registerLayerDefinition(
                org.shipwrights.enderkinesis.client.model.ShulkonautModel.LAYER_LOCATION,
                org.shipwrights.enderkinesis.client.model.ShulkonautModel::createBodyLayer,
            )
            event.registerLayerDefinition(StatueSteveModel.LAYER_LOCATION,          StatueSteveModel::createBodyLayer)
            event.registerLayerDefinition(StatueCatalogerModel.LAYER_LOCATION,      StatueCatalogerModel::createBodyLayer)
            event.registerLayerDefinition(StatueTentaclesModel.LAYER_LOCATION,      StatueTentaclesModel::createBodyLayer)
            event.registerLayerDefinition(StatueTentacledBeastModel.LAYER_LOCATION, StatueTentacledBeastModel::createBodyLayer)
            event.registerLayerDefinition(StatueYellowTowerModel.LAYER_LOCATION,    StatueYellowTowerModel::createBodyLayer)
            event.registerLayerDefinition(StatueBlackGoatModel.LAYER_LOCATION,      StatueBlackGoatModel::createBodyLayer)
            event.registerLayerDefinition(StatueCountingModel.LAYER_LOCATION,       StatueCountingModel::createBodyLayer)
            event.registerLayerDefinition(StatueOwlModel.LAYER_LOCATION,            StatueOwlModel::createBodyLayer)
            event.registerLayerDefinition(
                org.shipwrights.enderkinesis.client.model.SselithMothModel.LAYER_LOCATION,
                org.shipwrights.enderkinesis.client.model.SselithMothModel::createBodyLayer,
            )
        }

        /** Register the three open-Wylland-Tome sub-models so the
         *  model loader bakes them — none of them are referenced
         *  by a registered item directly, so without this call
         *  they'd never be loaded and [WyllandTomeBEWLR]'s
         *  ModelManager lookups would return the missing-model
         *  placeholder. */
        @JvmStatic
        fun registerAdditionalModels(event: ModelEvent.RegisterAdditional) {
            event.register(WyllandTomeBEWLR.ICON_MODEL_LOC)
            event.register(WyllandTomeBEWLR.SPINE_MODEL_LOC)
            event.register(WyllandTomeBEWLR.COVER1_MODEL_LOC)
            event.register(WyllandTomeBEWLR.COVER2_MODEL_LOC)
            event.register(WyllandTomeBEWLR.PAGE3_MODEL_LOC)
            event.register(WyllandTomeBEWLR.PAGE4_MODEL_LOC)
            event.register(org.shipwrights.enderkinesis.client.EchoCannonRenderer.BARREL_MODEL_LOCATION)
            event.register(org.shipwrights.enderkinesis.client.ParallelographBEWLR.BASE_MODEL_LOC)
            event.register(org.shipwrights.enderkinesis.client.ParallelographBEWLR.ARM_MODEL_LOC)
        }

        /** Capture the Wylland Tome sub-models into [WyllandTomeBEWLR]
         *  after Forge finishes baking. The event's `getModels()` map
         *  is keyed by the same `ResourceLocation`s we registered with
         *  [registerAdditionalModels], so direct lookup works. */
        /** Load the Moth Cloth glint overlay core shader once per resource reload. */
        @JvmStatic
        fun registerShaders(event: RegisterShadersEvent) {
            event.registerShader(
                net.minecraft.client.renderer.ShaderInstance(
                    event.resourceProvider,
                    ResourceLocation("enderkinesis", "moth_cloth"),
                    DefaultVertexFormat.BLOCK,
                ),
            ) { instance ->
                org.shipwrights.enderkinesis.client.MothClothRenderType.shader = instance
            }
            event.registerShader(
                net.minecraft.client.renderer.ShaderInstance(
                    event.resourceProvider,
                    ResourceLocation("enderkinesis", "moth_camo"),
                    DefaultVertexFormat.NEW_ENTITY,
                ),
            ) { instance ->
                org.shipwrights.enderkinesis.client.MothCamoRenderType.shader = instance
            }
            event.registerShader(
                net.minecraft.client.renderer.ShaderInstance(
                    event.resourceProvider,
                    ResourceLocation("enderkinesis", "owl_sight"),
                    DefaultVertexFormat.POSITION,
                ),
            ) { instance ->
                org.shipwrights.enderkinesis.client.OwlSightPasses.shader = instance
            }
        }

        @JvmStatic
        fun captureWyllandTomeBakes(event: ModelEvent.ModifyBakingResult) {
            val models = event.models
            WyllandTomeBEWLR.iconModel = models[WyllandTomeBEWLR.ICON_MODEL_LOC]
            WyllandTomeBEWLR.spineModel = models[WyllandTomeBEWLR.SPINE_MODEL_LOC]
            WyllandTomeBEWLR.cover1Model = models[WyllandTomeBEWLR.COVER1_MODEL_LOC]
            WyllandTomeBEWLR.cover2Model = models[WyllandTomeBEWLR.COVER2_MODEL_LOC]
            WyllandTomeBEWLR.page3Model = models[WyllandTomeBEWLR.PAGE3_MODEL_LOC]
            WyllandTomeBEWLR.page4Model = models[WyllandTomeBEWLR.PAGE4_MODEL_LOC]
            org.shipwrights.enderkinesis.client.EchoCannonRenderer.capturedBarrelModel =
                models[org.shipwrights.enderkinesis.client.EchoCannonRenderer.BARREL_MODEL_LOCATION]
            org.shipwrights.enderkinesis.client.ParallelographBEWLR.baseModel =
                models[org.shipwrights.enderkinesis.client.ParallelographBEWLR.BASE_MODEL_LOC]
            org.shipwrights.enderkinesis.client.ParallelographBEWLR.armModel =
                models[org.shipwrights.enderkinesis.client.ParallelographBEWLR.ARM_MODEL_LOC]
        }

        @JvmStatic
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
        @JvmStatic
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
                }
                RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS -> {
                    // Orb-network beam line — after translucent blocks so it composites over
                    // water/glass and its additive bloom adds to whatever's behind it.
                    OrbBeamLineRenderer.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                    // Magic Missile pulse-beam trails — same stage + render type as the orb
                    // network so the visual stays consistent.
                    org.shipwrights.enderkinesis.client.MagicMissileTrailRenderer.renderAll(
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
                    // Staff of Aegis, Staff of Sundering, Echo Cannon: same stage as on Fabric.
                    org.shipwrights.enderkinesis.client.AegisClient.renderAll(
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
                }
                else -> {}
            }
        }

        // `ForgeGui.render` replaces `Gui.render` with no super call, so `GuiScryingMixin`
        // never runs on Forge. These two listeners do its work.
        @JvmStatic
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

        @JvmStatic
        fun onRenderGuiPost(event: RenderGuiEvent.Post) {
            ScryingClient.renderForeground(event.guiGraphics, event.partialTick)
            TomeSummonOverlay.renderForeground(event.guiGraphics, event.partialTick)
        }

        @JvmStatic
        fun registerDimensionEffects(event: RegisterDimensionSpecialEffectsEvent) {
            // Register the custom Sselith DimensionSpecialEffects. The dimension_type JSON
            // declares `effects: enderkinesis:sselith_repertory`; this is what resolves that
            // ID into a usable effects instance. SkyType.NONE is the critical setting — see
            // [SselithDimensionEffects] for why.
            val effectsId = EnderkinesisMod.id("sselith_repertory")
            event.register(effectsId, SselithDimensionEffects())

            // Wohlonnogondonia uses NORMAL sky type but suppresses vanilla's
            // warm sunrise/sunset gradient via getSunriseColor → null. Sun
            // and moon textures are swapped to wogor_eye.png by
            // LevelRendererWohlonSunMoonMixin.
            val wohlonEffectsId = EnderkinesisMod.id("wohlonnogondonia")
            event.register(wohlonEffectsId, WohlonnogondoniaDimensionEffects())

            // Sureibjin — the dream coast. NORMAL sky for now (vanilla
            // gradient driven by the desaturated biome palette); custom
            // noise-flow shader is a follow-up.
            val sureibjinEffectsId = EnderkinesisMod.id("sureibjin")
            event.register(sureibjinEffectsId, SureibjinDimensionEffects())
        }

    }
}
