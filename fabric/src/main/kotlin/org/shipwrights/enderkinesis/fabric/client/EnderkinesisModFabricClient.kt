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
import org.shipwrights.enderkinesis.client.SureibjinDimensionEffects
import org.shipwrights.enderkinesis.client.WohlonnogondoniaDimensionEffects
import org.shipwrights.enderkinesis.client.WyllandTomeBEWLR
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer
import org.shipwrights.enderkinesis.client.model.CatalogerModel
import org.shipwrights.enderkinesis.client.model.HeartOfTheWildModel
import org.shipwrights.enderkinesis.client.model.PrismaticGoatModel
import org.shipwrights.enderkinesis.client.model.TightRobeArmorModel
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

        // Tight-fit robe armor — two HumanoidModel bakes at CubeDeformation(0.15f) instead
        // of vanilla's 0.5/1.0, plus an ArmorRenderer registration for every robe item so
        // the model is swapped in at render time. Forge picks up the namespaced texture
        // path via ScalingRobeArmorItem.getArmorTexture; no per-loader Forge wiring needed.
        EntityModelLayerRegistry.registerModelLayer(TightRobeArmorModel.INNER_LAYER) {
            TightRobeArmorModel.createInnerLayer()
        }
        EntityModelLayerRegistry.registerModelLayer(TightRobeArmorModel.OUTER_LAYER) {
            TightRobeArmorModel.createOuterLayer()
        }
        // Standalone witch-hat layer — baked at 128×128 to match the artist's
        // Blockbench texture atlas. Rendered as a second pass on top of the
        // Blue Witch helmet by [TightRobeArmorRenderer].
        EntityModelLayerRegistry.registerModelLayer(TightRobeArmorModel.WITCH_HAT_LAYER) {
            TightRobeArmorModel.createWitchHatLayer()
        }
        val tightRobeRenderer = TightRobeArmorRenderer()
        // Mystic Wind: tagged-item right-clicks fire a 0.1-second backward
        // wind pulse on the wearer's robes.
        TightRobeArmorRenderer.registerEvents(tightRobeRenderer)
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

        // Sureibjin — the dream coast. NORMAL sky for now (vanilla gradient
        // driven by the desaturated biome palette); custom noise-flow shader
        // is a follow-up.
        val sureibjinEffectsId = EnderkinesisMod.id("sureibjin")
        DimensionRenderingRegistry.registerDimensionEffects(
            sureibjinEffectsId, SureibjinDimensionEffects()
        )
        LOG.info(
            "Registered Sureibjin DimensionSpecialEffects (id={}, skyType=NONE, custom dream sky)",
            sureibjinEffectsId,
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

        // Magic Missile — renders as the vanilla ShulkerBulletModel via [MagicMissileBEWLR].
        BuiltinItemRendererRegistry.INSTANCE.register(
            EKItems.MAGIC_MISSILE.get(),
        ) { stack, mode, poseStack, vertexConsumers, light, overlay ->
            org.shipwrights.enderkinesis.client.MagicMissileBEWLR.renderByItem(
                stack, mode, poseStack, vertexConsumers, light, overlay,
            )
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
            // Magic Missile pulse-beam trails — same render-type and pipeline stage as the
            // orb network beam so the visual reads consistently with the rest of the mod.
            org.shipwrights.enderkinesis.client.MagicMissileTrailRenderer.renderAll(
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
    }

    companion object {
        private val LOG = LogUtils.getLogger()
    }
}
