package org.shipwrights.enderkinesis.fabric.client

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
import org.shipwrights.enderkinesis.client.CatalogerTomeWorldRenderer
import org.shipwrights.enderkinesis.client.CrownUntestedArmorRenderer
import org.shipwrights.enderkinesis.client.EnderkinesisModClient
import org.shipwrights.enderkinesis.client.OrbBeamLineRenderer
import org.shipwrights.enderkinesis.client.TightRobeArmorRenderer
import org.shipwrights.enderkinesis.client.WyllandTomeBEWLR
import org.shipwrights.enderkinesis.client.registerDimensionEffects as registerCommonDimensionEffects
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer
import org.shipwrights.enderkinesis.client.additionalModels
import org.shipwrights.enderkinesis.client.captureAdditionalModel
import org.shipwrights.enderkinesis.client.registerCoreShaders
import org.shipwrights.enderkinesis.client.registerModelLayers
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

        // Lodestar Dart — renders as the vanilla ShulkerBulletModel via [LodestarDartBEWLR].
        BuiltinItemRendererRegistry.INSTANCE.register(
            EKItems.LODESTAR_DART.get(),
        ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
            org.shipwrights.enderkinesis.client.LodestarDartBEWLR.renderByItem(
                stack, mode, poseStack, vertexConsumers, light, overlay,
            )
        }

        // Statues — every Ulder Statue item renders its full Blockbench model in
        // inventory / hand / dropped via [StatueBEWLR].
        for (statue in EKItems.STATUE_ITEMS) {
            BuiltinItemRendererRegistry.INSTANCE.register(
                statue.get(),
            ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
                org.shipwrights.enderkinesis.client.StatueBEWLR.renderByItem(
                    stack, mode, poseStack, vertexConsumers, light, overlay,
                )
            }
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
            // Detached barnacle shrivel-out death (block already broke) — cutout, so it
            // belongs in the entity pass with the block-entity renderer's geometry.
            org.shipwrights.enderkinesis.client.YgannBarnacleDeathRenderer.renderAll(
                ctx.matrixStack(), consumers, camera.x, camera.y, camera.z, ctx.tickDelta(),
            )
            // Entity pass, so that water drawn later composites over a waterlogged bud.
            org.shipwrights.enderkinesis.client.WogorBudRenderer.renderAll(
                ctx.matrixStack(), consumers, camera.x, camera.y, camera.z, ctx.tickDelta(),
            )
            org.shipwrights.enderkinesis.client.WoggakVineRenderer.renderAll(
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
            // Lodestar Dart pulse-beam trails — same render-type and pipeline stage as the
            // orb network beam so the visual reads consistently with the rest of the mod.
            org.shipwrights.enderkinesis.client.LodestarDartTrailRenderer.renderAll(
                ctx.matrixStack(), consumers, camera.x, camera.y, camera.z, ctx.tickDelta(),
            )
            // Crepusculite Lattice virtual sea — additive mesh over the existing particle
            // system. Toggle via CrepusculiteLatticeMeshRenderer.enabled (default true) or
            // delete this block to revert to the particle-only render path.
            org.shipwrights.enderkinesis.client.CrepusculiteLatticeMeshRenderer.renderAll(
                ctx.matrixStack(), consumers, camera.x, camera.y, camera.z, ctx.tickDelta(),
            )
            // Staff of Aegis — shield wireframe drawn at the same stage so
            // it composites over translucent blocks and reads consistently
            // with the particles inside.
            org.shipwrights.enderkinesis.client.AegisClient.renderAll(
                ctx.matrixStack(), consumers, camera.x, camera.y, camera.z, ctx.tickDelta(),
            )
            org.shipwrights.enderkinesis.client.FusionClient.renderAll(
                ctx.matrixStack(), consumers, camera.x, camera.y, camera.z, ctx.tickDelta(),
            )
            // Staff of Sundering — laser beam + tip rings + spiral. Same
            // pass; additive blend keeps it composing over translucents.
            org.shipwrights.enderkinesis.client.SunderingClient.renderAll(
                ctx.matrixStack(), consumers, camera.x, camera.y, camera.z, ctx.tickDelta(),
            )
            // Echo Cannon — fading blue-green wireframe around each
            // active beam. Same translucent-pass slot for the same
            // compositing reasons as Aegis / Sundering.
            org.shipwrights.enderkinesis.client.EchoCannonClient.renderAll(
                ctx.matrixStack(), consumers, camera.x, camera.y, camera.z, ctx.tickDelta(),
            )
        })

        EnderkinesisModClient.initClient()
        org.shipwrights.enderkinesis.fabric.compat.trinkets.TrinketsClientCompat.init()
    }

    companion object {
    }
}
