package org.shipwrights.enderkinesis.fabric.client

import com.mojang.logging.LogUtils
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.ResourcePackActivationType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.CatalogerTomeWorldRenderer
import org.shipwrights.enderkinesis.client.EnderkinesisModClient
import org.shipwrights.enderkinesis.client.OrbBeamLineRenderer
import org.shipwrights.enderkinesis.client.SselithDimensionEffects
import org.shipwrights.enderkinesis.client.WohlonnogondoniaDimensionEffects
import org.shipwrights.enderkinesis.client.WyllandTomeBEWLR
import org.shipwrights.enderkinesis.client.model.CatalogerModel
import org.shipwrights.enderkinesis.client.model.HeartOfTheWildModel
import org.shipwrights.enderkinesis.client.model.PrismaticGoatModel
import org.shipwrights.enderkinesis.registry.EKItems

class EnderkinesisModFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        FabricLoader.getInstance().getModContainer(EnderkinesisMod.MOD_ID).ifPresent { mc ->
            val ok = ResourceManagerHelper.registerBuiltinResourcePack(
                EnderkinesisMod.id("sselith"),
                mc,
                Component.literal("Sselith"),
                ResourcePackActivationType.ALWAYS_ENABLED,
            )
            LOG.info("Registered built-in Sselith translation pack (success={})", ok)
        }

        // Register Cataloger's model layer. Fabric API exposes this through
        // EntityModelLayerRegistry; the supplier is invoked once at bake time.
        EntityModelLayerRegistry.registerModelLayer(CatalogerModel.LAYER_LOCATION) {
            CatalogerModel.createBodyLayer()
        }

        // Heart of the Wild — block-entity model registered the same way.
        // Drawn by [HeartOfTheWildRenderer] (registered in the common
        // client init through Architectury's BlockEntityRendererRegistry).
        EntityModelLayerRegistry.registerModelLayer(HeartOfTheWildModel.LAYER_LOCATION) {
            HeartOfTheWildModel.createBodyLayer()
        }

        // Prismatic Goat — entity model. Drawn by [PrismaticGoatRenderer]
        // (registered in the common client init through
        // Architectury's EntityRendererRegistry).
        EntityModelLayerRegistry.registerModelLayer(PrismaticGoatModel.LAYER_LOCATION) {
            PrismaticGoatModel.createBodyLayer()
        }

        // Register the custom Sselith DimensionSpecialEffects. The dimension_type
        // JSON declares `effects: enderkinesis:sselith_repertory`; this is what
        // resolves that ID into a usable effects instance. SkyType.NONE is the
        // critical setting — see [SselithDimensionEffects] for why.
        val effectsId = EnderkinesisMod.id("sselith_repertory")
        DimensionRenderingRegistry.registerDimensionEffects(effectsId, SselithDimensionEffects())
        LOG.info("Registered Sselith DimensionSpecialEffects (id={}, skyType=NONE, clouds=NaN)", effectsId)

        // Wohlonnogondonia uses NORMAL sky type but suppresses vanilla's warm
        // sunrise/sunset gradient via getSunriseColor → null. Sun and moon
        // textures are swapped to wogor_eye.png by LevelRendererWohlonSunMoonMixin.
        val wohlonEffectsId = EnderkinesisMod.id("wohlonnogondonia")
        DimensionRenderingRegistry.registerDimensionEffects(
            wohlonEffectsId, WohlonnogondoniaDimensionEffects()
        )
        LOG.info(
            "Registered Wohlonnogondonia DimensionSpecialEffects (id={}, skyType=NORMAL, no sunset gradient)",
            wohlonEffectsId,
        )

        // Wylland Tome — register the three open-tome sub-models so
        // they get baked (no item directly references them) and wire
        // the BEWLR as the item's custom renderer. Fabric flips the
        // baked model's customRenderer flag so vanilla
        // ItemRenderer.render dispatches into our BEWLR.
        ModelLoadingPlugin.register { ctx ->
            ctx.addModels(
                WyllandTomeBEWLR.ICON_MODEL_LOC,
                WyllandTomeBEWLR.SPINE_MODEL_LOC,
                WyllandTomeBEWLR.COVER1_MODEL_LOC,
                WyllandTomeBEWLR.COVER2_MODEL_LOC,
                WyllandTomeBEWLR.PAGE3_MODEL_LOC,
                WyllandTomeBEWLR.PAGE4_MODEL_LOC,
            )
            ctx.modifyModelAfterBake().register(
                ModelModifier.AfterBake { model, modelCtx ->
                    when (modelCtx.id()) {
                        WyllandTomeBEWLR.ICON_MODEL_LOC -> WyllandTomeBEWLR.iconModel = model
                        WyllandTomeBEWLR.SPINE_MODEL_LOC -> WyllandTomeBEWLR.spineModel = model
                        WyllandTomeBEWLR.COVER1_MODEL_LOC -> WyllandTomeBEWLR.cover1Model = model
                        WyllandTomeBEWLR.COVER2_MODEL_LOC -> WyllandTomeBEWLR.cover2Model = model
                        WyllandTomeBEWLR.PAGE3_MODEL_LOC -> WyllandTomeBEWLR.page3Model = model
                        WyllandTomeBEWLR.PAGE4_MODEL_LOC -> WyllandTomeBEWLR.page4Model = model
                    }
                    model
                },
            )
        }
        BuiltinItemRendererRegistry.INSTANCE.register(
            EKItems.WYLLAND_TOME.get(),
        ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
            WyllandTomeBEWLR.renderByItem(stack, mode, poseStack, vertexConsumers, light, overlay)
        }

        // Cataloger tome-summon flourish — drawn from the level
        // renderer's post-entity event so it stands free of the
        // cataloger's own frustum culling. The book's quads end up in
        // the same world bufferSource the level draws everything else
        // through, so the GPU does the frustum work for us.
        WorldRenderEvents.AFTER_ENTITIES.register(WorldRenderEvents.AfterEntities { ctx ->
            val consumers = ctx.consumers() ?: return@AfterEntities
            val camera = ctx.camera().position
            CatalogerTomeWorldRenderer.renderAll(
                ctx.matrixStack(), consumers, camera.x, camera.y, camera.z, ctx.tickDelta(),
            )
        })

        // Orb-network beam line — drawn after translucent blocks so it composites correctly
        // over water/glass and so its additive bloom adds to whatever's behind it.
        WorldRenderEvents.AFTER_TRANSLUCENT.register(WorldRenderEvents.AfterTranslucent { ctx ->
            val consumers = ctx.consumers() ?: return@AfterTranslucent
            val camera = ctx.camera().position
            OrbBeamLineRenderer.renderAll(
                ctx.matrixStack(), consumers, camera.x, camera.y, camera.z, ctx.tickDelta(),
            )
        })

        EnderkinesisModClient.initClient()
    }

    companion object {
        private val LOG = LogUtils.getLogger()
    }
}
