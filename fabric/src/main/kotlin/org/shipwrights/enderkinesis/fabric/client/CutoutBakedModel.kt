package org.shipwrights.enderkinesis.fabric.client

import net.fabricmc.fabric.api.renderer.v1.RendererAccess
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.block.state.BlockState
import java.util.function.Supplier

// A block has one chunk layer on Fabric. This puts one part of a translucent block on the cutout layer.
// Forge does the same with the "render_type" key in the model JSON.
class CutoutBakedModel private constructor(model: BakedModel, private val cutout: RenderMaterial) :
    ForwardingBakedModel() {

    init {
        wrapped = model
    }

    override fun isVanillaAdapter(): Boolean = false

    override fun emitBlockQuads(
        level: BlockAndTintGetter, state: BlockState, pos: BlockPos,
        random: Supplier<RandomSource>, context: RenderContext,
    ) {
        context.pushTransform { quad ->
            quad.material(cutout)
            true
        }
        super.emitBlockQuads(level, state, pos, random, context)
        context.popTransform()
    }

    companion object {
        @JvmField val INEGRIC_RETORT_SUPPORTS = ResourceLocation("enderkinesis", "block/inegric_retort_supports")

        // Without a Fabric renderer (Sodium with no Indium), the model stays on the block's layer.
        fun wrap(model: BakedModel): BakedModel {
            val renderer = RendererAccess.INSTANCE.renderer ?: return model
            return CutoutBakedModel(model, renderer.materialFinder().blendMode(BlendMode.CUTOUT).find())
        }
    }
}
