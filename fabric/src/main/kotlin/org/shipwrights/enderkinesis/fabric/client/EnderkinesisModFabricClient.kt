package org.shipwrights.enderkinesis.fabric.client

import com.mojang.logging.LogUtils
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.ResourcePackActivationType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.EnderkinesisModClient
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
                WyllandTomeBEWLR.STATIC_MODEL_LOC,
                WyllandTomeBEWLR.PAGE3_MODEL_LOC,
                WyllandTomeBEWLR.PAGE4_MODEL_LOC,
            )
            ctx.modifyModelAfterBake().register(
                ModelModifier.AfterBake { model, modelCtx ->
                    when (modelCtx.id()) {
                        WyllandTomeBEWLR.ICON_MODEL_LOC -> WyllandTomeBEWLR.iconModel = model
                        WyllandTomeBEWLR.STATIC_MODEL_LOC -> WyllandTomeBEWLR.staticModel = model
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

        EnderkinesisModClient.initClient()
    }

    companion object {
        private val LOG = LogUtils.getLogger()
    }
}
