package org.shipwrights.enderkinesis.fabric.client

import com.mojang.logging.LogUtils
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.ResourcePackActivationType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.CrownUntestedArmorRenderer
import org.shipwrights.enderkinesis.client.EnderkinesisModClient
import org.shipwrights.enderkinesis.client.TightRobeArmorRenderer
import org.shipwrights.enderkinesis.client.WyllandTomeBEWLR
import org.shipwrights.enderkinesis.client.registerDimensionEffects as registerCommonDimensionEffects
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer
import org.shipwrights.enderkinesis.client.additionalModels
import org.shipwrights.enderkinesis.client.captureAdditionalModel
import org.shipwrights.enderkinesis.client.registerCoreShaders
import org.shipwrights.enderkinesis.client.registerModelLayers
import org.shipwrights.enderkinesis.client.renderAfterEntities
import org.shipwrights.enderkinesis.client.renderAfterTranslucent
import org.shipwrights.enderkinesis.registry.EKItems

class EnderkinesisModFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        FabricLoader.getInstance().getModContainer(EnderkinesisMod.MOD_ID).ifPresent { mc ->
            val registered = ResourceManagerHelper.registerBuiltinResourcePack(
                EnderkinesisMod.id("sselith"),
                mc,
                Component.literal("Sselith"),
                ResourcePackActivationType.ALWAYS_ENABLED,
            )
            if (!registered) LogUtils.getLogger().warn("The built-in Sselith resource pack is not registered")
        }

        registerModelLayers { layer, definition -> EntityModelLayerRegistry.registerModelLayer(layer) { definition() } }

        // The renderer is common code; Forge reaches it from a mixin on the armour layer.
        val tightRobeRenderer = ArmorRenderer { matrices, consumers, stack, entity, slot, light, model ->
            TightRobeArmorRenderer.INSTANCE.render(matrices, consumers, stack, entity, slot, light, model)
        }
        ArmorRenderer.register(
            tightRobeRenderer,
            EKItems.END_CULT_HOOD.get(),
            EKItems.END_CULT_ROBES.get(),
            EKItems.END_CULT_ROBE_BOTTOMS.get(),
            EKItems.END_CULT_SHOES.get(),
            EKItems.SCHOLAR_HOOD.get(),
            EKItems.SCHOLAR_ROBES.get(),
            EKItems.SCHOLAR_ROBE_BOTTOMS.get(),
            EKItems.SCHOLAR_SHOES.get(),
            EKItems.BLUE_WITCH_HAT.get(),
            EKItems.BLUE_WITCH_ROBES.get(),
            EKItems.BLUE_WITCH_ROBE_BOTTOMS.get(),
            EKItems.BLUE_WITCH_SANDALS.get(),
        )
        ArmorRenderer.register(
            { matrices, consumers, stack, _, slot, light, model ->
                CrownUntestedArmorRenderer.INSTANCE.render(matrices, consumers, stack, slot, light, model)
            },
            EKItems.CROWN_UNTESTED.get(),
        )

        registerCommonDimensionEffects(DimensionRenderingRegistry::registerDimensionEffects)

        CoreShaderRegistrationCallback.EVENT.register { ctx ->
            registerCoreShaders { id, format, onLoad -> ctx.register(id, format) { onLoad(it) } }
        }

        ModelLoadingPlugin.register { ctx ->
            ctx.addModels(additionalModels())
            ctx.modifyModelAfterBake().register(
                ModelModifier.AfterBake { model, modelCtx ->
                    captureAdditionalModel(modelCtx.id(), model)
                    model
                },
            )
            ctx.modifyModelAfterBake().register(
                ModelModifier.AfterBake { model, modelCtx ->
                    if (model != null && modelCtx.id() == CutoutBakedModel.INEGRIC_RETORT_SUPPORTS) {
                        CutoutBakedModel.wrap(model)
                    } else {
                        model
                    }
                },
            )
        }
        BuiltinItemRendererRegistry.INSTANCE.register(
            EKItems.WYLLAND_TOME.get(),
        ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
            WyllandTomeBEWLR.renderByItem(stack, mode, poseStack, vertexConsumers, light, overlay)
        }
        BuiltinItemRendererRegistry.INSTANCE.register(
            EKItems.PARALLELOGRAPH.get(),
        ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
            org.shipwrights.enderkinesis.client.ParallelographBEWLR.renderByItem(
                stack, mode, poseStack, vertexConsumers, light, overlay,
            )
        }
        BuiltinItemRendererRegistry.INSTANCE.register(
            EKItems.ICHAERIC_INK_JAR_ITEM.get(),
        ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
            org.shipwrights.enderkinesis.client.IchaericInkJarBEWLR.renderByItem(
                stack, mode, poseStack, vertexConsumers, light, overlay,
            )
        }
        BuiltinItemRendererRegistry.INSTANCE.register(
            EKItems.HEART_OF_THE_WILD_ITEM.get(),
        ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
            org.shipwrights.enderkinesis.client.HeartOfTheWildBEWLR.renderByItem(
                stack, mode, poseStack, vertexConsumers, light, overlay,
            )
        }
        BuiltinItemRendererRegistry.INSTANCE.register(
            EKItems.QUASAR_OF_PURITY_ITEM.get(),
        ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
            org.shipwrights.enderkinesis.client.QuasarOfPurityBEWLR.renderByItem(
                stack, mode, poseStack, vertexConsumers, light, overlay,
            )
        }

        BuiltinItemRendererRegistry.INSTANCE.register(
            EKItems.LODESTAR_DART.get(),
        ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
            org.shipwrights.enderkinesis.client.LodestarDartBEWLR.renderByItem(
                stack, mode, poseStack, vertexConsumers, light, overlay,
            )
        }

        for (statue in EKItems.STATUE_ITEMS) {
            BuiltinItemRendererRegistry.INSTANCE.register(
                statue.get(),
            ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
                org.shipwrights.enderkinesis.client.StatueBEWLR.renderByItem(
                    stack, mode, poseStack, vertexConsumers, light, overlay,
                )
            }
        }

        WorldRenderEvents.AFTER_ENTITIES.register(WorldRenderEvents.AfterEntities { ctx ->
            val consumers = ctx.consumers() ?: return@AfterEntities
            renderAfterEntities(ctx.matrixStack(), consumers, ctx.camera().position, ctx.tickDelta())
        })
        WorldRenderEvents.AFTER_TRANSLUCENT.register(WorldRenderEvents.AfterTranslucent { ctx ->
            val consumers = ctx.consumers() ?: return@AfterTranslucent
            renderAfterTranslucent(ctx.matrixStack(), consumers, ctx.camera().position, ctx.tickDelta())
        })

        EnderkinesisModClient.initClient()
        org.shipwrights.enderkinesis.fabric.compat.trinkets.TrinketsClientCompat.init()
    }
}
