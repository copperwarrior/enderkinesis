package org.shipwrights.enderkinesis.forge.client

import com.mojang.logging.LogUtils
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.resources.ResourceLocation
import net.minecraft.client.Minecraft
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.ModelEvent
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.AddPackFindersEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.resource.PathPackResources
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.CatalogerTomeWorldRenderer
import org.shipwrights.enderkinesis.client.EnderkinesisModClient
import org.shipwrights.enderkinesis.client.OrbBeamLineRenderer
import org.shipwrights.enderkinesis.client.SselithDimensionEffects
import org.shipwrights.enderkinesis.client.WohlonnogondoniaDimensionEffects
import org.shipwrights.enderkinesis.client.WyllandTomeBEWLR
import org.shipwrights.enderkinesis.client.model.CatalogerModel

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
            EnderkinesisModClient.initClient()
        }

        @JvmStatic
        fun registerLayerDefinitions(event: EntityRenderersEvent.RegisterLayerDefinitions) {
            event.registerLayerDefinition(CatalogerModel.LAYER_LOCATION, CatalogerModel::createBodyLayer)
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
        }

        /** Capture the Wylland Tome sub-models into [WyllandTomeBEWLR]
         *  after Forge finishes baking. The event's `getModels()` map
         *  is keyed by the same `ResourceLocation`s we registered with
         *  [registerAdditionalModels], so direct lookup works. */
        @JvmStatic
        fun captureWyllandTomeBakes(event: ModelEvent.ModifyBakingResult) {
            val models = event.models
            WyllandTomeBEWLR.iconModel = models[WyllandTomeBEWLR.ICON_MODEL_LOC]
            WyllandTomeBEWLR.spineModel = models[WyllandTomeBEWLR.SPINE_MODEL_LOC]
            WyllandTomeBEWLR.cover1Model = models[WyllandTomeBEWLR.COVER1_MODEL_LOC]
            WyllandTomeBEWLR.cover2Model = models[WyllandTomeBEWLR.COVER2_MODEL_LOC]
            WyllandTomeBEWLR.page3Model = models[WyllandTomeBEWLR.PAGE3_MODEL_LOC]
            WyllandTomeBEWLR.page4Model = models[WyllandTomeBEWLR.PAGE4_MODEL_LOC]
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
            LOG.info("Registered built-in Sselith translation pack (Forge)")
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
                }
                RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS -> {
                    // Orb-network beam line — after translucent blocks so it composites over
                    // water/glass and its additive bloom adds to whatever's behind it.
                    OrbBeamLineRenderer.renderAll(
                        event.poseStack, bufferSource,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        event.partialTick,
                    )
                }
                else -> {}
            }
        }

        @JvmStatic
        fun registerDimensionEffects(event: RegisterDimensionSpecialEffectsEvent) {
            // Register the custom Sselith DimensionSpecialEffects. The dimension_type JSON
            // declares `effects: enderkinesis:sselith_repertory`; this is what resolves that
            // ID into a usable effects instance. SkyType.NONE is the critical setting — see
            // [SselithDimensionEffects] for why.
            val effectsId = EnderkinesisMod.id("sselith_repertory")
            event.register(effectsId, SselithDimensionEffects())
            LOG.info("Registered Sselith DimensionSpecialEffects (id={}, skyType=NONE, clouds=NaN)", effectsId)

            // Wohlonnogondonia uses NORMAL sky type but suppresses vanilla's
            // warm sunrise/sunset gradient via getSunriseColor → null. Sun
            // and moon textures are swapped to wogor_eye.png by
            // LevelRendererWohlonSunMoonMixin.
            val wohlonEffectsId = EnderkinesisMod.id("wohlonnogondonia")
            event.register(wohlonEffectsId, WohlonnogondoniaDimensionEffects())
            LOG.info(
                "Registered Wohlonnogondonia DimensionSpecialEffects (id={}, skyType=NORMAL, no sunset gradient)",
                wohlonEffectsId,
            )
        }

        private val LOG = LogUtils.getLogger()
    }
}
