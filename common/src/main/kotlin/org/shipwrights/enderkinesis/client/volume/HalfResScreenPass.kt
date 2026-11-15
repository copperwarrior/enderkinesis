package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import java.util.function.IntSupplier
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.material.FogType
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.shipwrights.enderkinesis.client.IchaericInkVisuals
import org.shipwrights.enderkinesis.client.IrisCompat

/**
 * A half-res screen filter over main: [drawHalfDepth], draw premultiplied RGBA into [layer], then [composite].
 * Depth includes particles, unlike [VolumeLayer], so a filter never washes out a near particle. Render thread only.
 */
class HalfResScreenPass(name: String) {
    val mainDepth = IntSupplier { Minecraft.getInstance().mainRenderTarget.depthTextureId }
    val mainColour = IntSupplier { Minecraft.getInstance().mainRenderTarget.colorTextureId }
    val halfDepth = HalfResTarget(GL30.GL_RG32F, GL30.GL_RG)
    val layer = HalfResTarget(GL30.GL_RGBA16F, GL11.GL_RGBA)
    val halfDepthTexture = IntSupplier { halfDepth.textureId }

    val perf = VolumePerf.group(name) { "${layer.width}x${layer.height}" }
    private val depthTimer = perf.timer("depth")
    private val upsampleTimer = perf.timer("upsample")

    private val depthEffect = VolumeEffect("volume_half_depth", "DepthSampler" to mainDepth, "NearDepthSampler" to mainDepth)
    private val upsampleEffect = VolumeEffect(
        "volume_upsample",
        "DepthSampler" to mainDepth,
        "HalfSampler" to IntSupplier { layer.textureId },
        "HalfDepthSampler" to halfDepthTexture,
        // Read only with BehindOn; a sampler must still point at a real texture.
        "OpaqueColourSampler" to IntSupplier { layer.textureId },
        "GlassColourSampler" to IntSupplier { layer.textureId },
    )

    private var view: VolumeView? = null
    private val pushNothing: (EffectInstance) -> Unit = {}
    private val pushUpsample: (EffectInstance) -> Unit = ::pushUpsample

    /** False when a shared program did not load. */
    fun ready(): Boolean = depthEffect.get() != null && upsampleEffect.get() != null

    fun drawHalfDepth(main: RenderTarget) {
        val effect = depthEffect.get() ?: return
        RenderSystem.disableScissor()
        depthTimer.begin()
        halfDepth.draw(main, effect, pushNothing)
        depthTimer.end()
    }

    /** Upsamples [layer] over main by depth and restores the main state. */
    fun composite(main: RenderTarget, view: VolumeView) {
        val effect = upsampleEffect.get()
        if (effect != null) {
            this.view = view
            upsampleTimer.begin()
            VolumeComposite.drawOverMain(main, effect, pushUpsample, VolumeComposite.Blend.PREMULTIPLIED)
            upsampleTimer.end()
        }
        VolumeComposite.restore(main)
    }

    private fun pushUpsample(e: EffectInstance) {
        checkNotNull(view) { "HalfResScreenPass upsample outside composite" }.pushDepthParams(e)
        layer.pushHalfSize(e)
        e.safeGetUniform("DepthTolerance").set(DEPTH_TOLERANCE)
        e.safeGetUniform("DitherFrame").set((VolumeDepth.frame % JITTER_PERIOD).toFloat())
        e.safeGetUniform("BehindOn").set(0f)
    }

    private companion object {
        const val JITTER_PERIOD = 64L
        const val DEPTH_TOLERANCE = 0.01f
    }
}

/** True where a dimension's own air fog applies: not in a fluid or ink, no Blindness or Darkness, no shader pack. */
fun airFogApplies(camera: Camera): Boolean {
    if (camera.fluidInCamera != FogType.NONE || IchaericInkVisuals.cameraInInk() || IrisCompat.isShaderPackInUse()) return false
    val entity = camera.entity
    return !(entity is LivingEntity && (entity.hasEffect(MobEffects.BLINDNESS) || entity.hasEffect(MobEffects.DARKNESS)))
}
