package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import java.util.function.IntSupplier
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.HalfResTarget
import org.shipwrights.enderkinesis.client.volume.SectionLightVolume
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeTextures
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.mixin.LightTextureAccessor

/** Golden volumetric glow from block light during a Sselith eclipse. The march maps block light through
 *  lightmap row 0, so the glow follows the eclipse curve of the world. All state is on the client render thread. */
object SselithEclipseGlow {

    private const val WINDOW_CHUNKS = 8
    private const val WINDOW_SECTIONS = 8

    // The LightVolume uniform in sselith_eclipse_glow.json holds this unit.
    private const val LIGHT_VOLUME_UNIT = 4

    // Glow added per block of path at lightmap luminance 1, at full eclipse.
    private const val DENSITY = 0.10f

    // Fraction of the march distance where the glow starts to fade, so the window edge does not show.
    private const val FADE_START = 0.75f

    private const val GOLD_R = 1.00f
    private const val GOLD_G = 0.76f
    private const val GOLD_B = 0.28f

    private const val LIGHTMAP_LEVELS = 16
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    private val curve = FloatArray(LIGHTMAP_LEVELS)
    private val volume = SectionLightVolume(WINDOW_CHUNKS, WINDOW_SECTIONS)
    private val half = HalfResTarget()
    private val view = VolumeView()
    private val marchEffect = VolumeEffect("sselith_eclipse_glow")
    private val upsampleEffect = VolumeEffect("sselith_eclipse_glow_upsample", "HalfSampler" to IntSupplier { half.textureId })

    private var camBlockX = 0
    private var camBlockY = 0
    private var camBlockZ = 0
    private var strength = 0f
    private var marchDist = 0f

    private val pushMarchUniforms: (EffectInstance) -> Unit = ::pushMarchUniforms
    private val pushUpsampleUniforms: (EffectInstance) -> Unit = ::pushUpsampleUniforms

    /** Block and light change hook. */
    @JvmStatic
    fun markSectionDirty(sectionX: Int, sectionY: Int, sectionZ: Int) = volume.markDirty(sectionX, sectionY, sectionZ)

    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null || level.dimension() != SselithRepertory.LEVEL_KEY || IrisCompat.isShaderPackInUse()) {
            volume.unbind()
            return
        }
        val march = marchEffect.get() ?: return
        val upsample = upsampleEffect.get() ?: return

        val cam = camera.position
        camBlockX = Mth.floor(cam.x)
        camBlockY = Mth.floor(cam.y)
        camBlockZ = Mth.floor(cam.z)
        // Keep the volume current with no eclipse too: a rebind at eclipse start rebuilds every section in one frame.
        volume.refresh(level, camBlockX shr 4, camBlockY shr 4, camBlockZ shr 4)
        val eclipse = SselithRepertoryLighting.currentEclipseIntensity(partialTick)
        if (eclipse <= 0f) return
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        strength = eclipse * DENSITY
        marchDist = minOf(volume.maxMarch, mc.options.effectiveRenderDistance * 16f)

        val main = mc.mainRenderTarget
        VolumeTextures.bind3D(LIGHT_VOLUME_UNIT, volume.textureId)
        half.draw(main, march, pushMarchUniforms)
        VolumeTextures.bind3D(LIGHT_VOLUME_UNIT, 0)
        VolumeComposite.drawOverMain(main, upsample, pushUpsampleUniforms, VolumeComposite.Blend.ADDITIVE)
        VolumeComposite.restore(main)
    }

    // Lightmap row 0 is block light with no sky light: the eclipse curve the world shows.
    // The CPU copy matches the texture: LightTexture uploads its pixels each time it changes them.
    private fun pushCurve(eff: EffectInstance) {
        val pixels = (Minecraft.getInstance().gameRenderer.lightTexture() as LightTextureAccessor)
            .`enderkinesis$getLightTexture`().pixels ?: return
        for (i in 0 until LIGHTMAP_LEVELS) {
            val abgr = pixels.getPixelRGBA(i, 0)
            curve[i] = LUMA_R * ((abgr and 0xFF) / 255f) +
                LUMA_G * ((abgr ushr 8 and 0xFF) / 255f) +
                LUMA_B * ((abgr ushr 16 and 0xFF) / 255f)
        }
        eff.safeGetUniform("Curve").set(curve)
    }

    private fun pushMarchUniforms(eff: EffectInstance) {
        view.pushInvViewProj(eff)
        view.pushDepthParams(eff)
        eff.safeGetUniform("CamWrap").set(
            volume.wrapXZ(camBlockX) + (view.camX - camBlockX).toFloat(),
            volume.wrapY(camBlockY) + (view.camY - camBlockY).toFloat(),
            volume.wrapXZ(camBlockZ) + (view.camZ - camBlockZ).toFloat(),
        )
        eff.safeGetUniform("VolumeSize").set(volume.sizeXZ.toFloat(), volume.sizeXZ.toFloat(), volume.sizeY.toFloat())
        eff.safeGetUniform("MaxDist").set(marchDist)
        eff.safeGetUniform("FadeStart").set(marchDist * FADE_START)
        eff.safeGetUniform("Strength").set(strength)
        pushCurve(eff)
    }

    private fun pushUpsampleUniforms(eff: EffectInstance) {
        eff.safeGetUniform("FogColor").set(GOLD_R, GOLD_G, GOLD_B)
        view.pushDepthParams(eff)
        half.pushHalfSize(eff)
    }

    fun onDisconnect() = volume.unbind()
}
