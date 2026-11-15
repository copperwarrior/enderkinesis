package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import java.util.function.IntSupplier
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.shipwrights.enderkinesis.client.volume.HalfResScreenPass
import org.shipwrights.enderkinesis.client.volume.HalfResTarget
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.client.volume.airFogApplies
import org.shipwrights.enderkinesis.dimension.Sureibjin

/** Sureibjin distance haze: a half-res blur that grows past the fog start and pulls far texels toward the fog colour. */
object SureibjinDistanceBlur {

    // Fog-relative distances: 0 is the fog start, 1 the fog end.
    private const val BLUR_START = 0.2f
    private const val BLUR_FULL = 0.9f

    // Half-res texels at full blur. The shader loop holds at most MAX_TAPS = 8.
    private const val MAX_RADIUS = 6f

    // Blur amount over which the blurred image fades in, so the blur start shows no line.
    private const val FADE_IN = 0.25f

    // Pull toward the fog colour at full blur.
    private const val FOG_PULL = 0.3f

    // renderLevel passes max(renderDistance, 32) to setupFog, which FogRendererSureibjinMixin scales.
    private const val MIN_FAR_PLANE = 32f

    private val pass = HalfResScreenPass("sureibjin distance blur")
    private val pong = HalfResTarget(GL30.GL_RGBA16F, GL11.GL_RGBA)
    private val prefilterEffect = VolumeEffect(
        "distance_blur_prefilter",
        "DepthSampler" to pass.mainDepth,
        "MainSampler" to pass.mainColour,
        "HalfDepthSampler" to pass.halfDepthTexture,
    )
    private val blurAcross = VolumeEffect("distance_blur", "BlurSampler" to IntSupplier { pass.layer.textureId })
    private val blurDown = VolumeEffect("distance_blur", "BlurSampler" to IntSupplier { pong.textureId })
    private val prefilterTimer = pass.perf.timer("prefilter")
    private val blurTimer = pass.perf.timer("blur")

    private val view = VolumeView()
    private var blurNear = 0f
    private var blurFar = 1f

    private val pushPrefilter: (EffectInstance) -> Unit = ::pushPrefilter
    private val pushAcross: (EffectInstance) -> Unit = { pushBlur(it, 1f, 0f, 0f) }
    private val pushDown: (EffectInstance) -> Unit = { pushBlur(it, 0f, 1f, 1f) }

    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val mc = Minecraft.getInstance()
        if (mc.level?.dimension() != Sureibjin.LEVEL_KEY || !airFogApplies(camera)) return
        val prefilter = prefilterEffect.get() ?: return
        val across = blurAcross.get() ?: return
        val down = blurDown.get() ?: return
        if (!pass.ready()) return

        val farPlane = maxOf(mc.gameRenderer.renderDistance, MIN_FAR_PLANE)
        val fogStart = farPlane * SureibjinWaterFilm.FOG_START_FRAC
        val fogEnd = farPlane * SureibjinWaterFilm.FOG_END_FRAC
        blurNear = fogStart + BLUR_START * (fogEnd - fogStart)
        blurFar = fogStart + BLUR_FULL * (fogEnd - fogStart)
        val main = mc.mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)

        pass.drawHalfDepth(main)
        prefilterTimer.begin()
        pass.layer.draw(main, prefilter, pushPrefilter)
        prefilterTimer.end()
        blurTimer.begin()
        pong.draw(main, across, pushAcross)
        pass.layer.draw(main, down, pushDown)
        blurTimer.end()
        pass.composite(main, view)
    }

    private fun pushPrefilter(e: EffectInstance) {
        view.pushInvViewProj(e)
        e.safeGetUniform("BlurRange").set(blurNear, blurFar)
        // FogRendererSureibjinMixin sets FogShape.SPHERE.
        e.safeGetUniform("SphereFog").set(1f)
    }

    private fun pushBlur(e: EffectInstance, dirX: Float, dirY: Float, finalPass: Float) {
        e.safeGetUniform("Direction").set(dirX, dirY)
        e.safeGetUniform("MaxRadius").set(MAX_RADIUS)
        e.safeGetUniform("Final").set(finalPass)
        e.safeGetUniform("FadeIn").set(FADE_IN)
        e.safeGetUniform("FogPull").set(FOG_PULL)
        e.safeGetUniform("FogColor").set(SureibjinWaterFilm.FOG_R, SureibjinWaterFilm.FOG_G, SureibjinWaterFilm.FOG_B)
        e.safeGetUniform("Strength").set(1f)
    }
}
