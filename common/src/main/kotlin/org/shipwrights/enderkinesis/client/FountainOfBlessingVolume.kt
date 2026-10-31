package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.shaders.Uniform
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView

/** Draws the `wohlon_fountain_volume` upwelling for each fountain the BER queued this frame. Render thread only. */
object FountainOfBlessingVolume {

    private const val MAX_QUEUED = 8
    private const val STRIDE = 7
    private const val HALF_WIDTH = 3.4f
    private const val MIST_HEIGHT = 4.2f
    private const val BELOW_SURFACE = 0.3f

    private val view = VolumeView()
    private val effect = VolumeEffect("wohlon_fountain_volume")
    private var uniforms: Uniforms? = null
    private val localToView = Matrix4f()

    // Spring x, y, z; surface height above the spring; charge; burst; rise phase.
    private val queue = DoubleArray(MAX_QUEUED * STRIDE)
    private var queued = 0

    fun queue(x: Double, springY: Double, z: Double, surface: Float, charge: Float, burst: Float, rise: Float) {
        if (queued == MAX_QUEUED) return
        val i = queued * STRIDE
        queue[i] = x
        queue[i + 1] = springY
        queue[i + 2] = z
        queue[i + 3] = surface.toDouble()
        queue[i + 4] = charge.toDouble()
        queue[i + 5] = burst.toDouble()
        queue[i + 6] = rise.toDouble()
        queued++
    }

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (queued == 0) return
        val count = queued
        queued = 0
        if (IrisCompat.isShaderPackInUse()) return
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = Minecraft.getInstance().mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        view.pushViewProj(eff)
        view.pushInvViewProj(eff)
        u.screenSize.set(main.width.toFloat(), main.height.toFloat())
        u.time.set(((System.nanoTime() * 1e-9) % 3600.0).toFloat())
        u.mist.set(MIST[0], MIST[1], MIST[2])
        VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
        for (n in 0 until count) {
            val i = n * STRIDE
            val ox = queue[i] - view.camX
            val oy = queue[i + 1] - view.camY
            val oz = queue[i + 2] - view.camZ
            val surface = queue[i + 3].toFloat()
            localToView.translation(ox.toFloat(), oy.toFloat(), oz.toFloat())
            u.localToView.set(localToView)
            u.camLocal.set(-ox.toFloat(), -oy.toFloat(), -oz.toFloat())
            // Only the mist over the water remains, so the march starts just under the surface.
            u.boxMin.set(-HALF_WIDTH, surface - BELOW_SURFACE, -HALF_WIDTH)
            u.boxMax.set(HALF_WIDTH, surface + MIST_HEIGHT, HALF_WIDTH)
            u.surface.set(surface)
            u.charge.set(queue[i + 4].toFloat())
            u.burst.set(queue[i + 5].toFloat())
            u.rise.set(queue[i + 6].toFloat())
            if (n == 0) VolumeProxy.draw(eff) else {
                u.upload()
                VolumeProxy.drawApplied()
            }
        }
        VolumeProxy.end(main, eff)
    }

    private val MIST = floatArrayOf(0.6f, 0.9f, 0.85f)

    private class Uniforms(eff: EffectInstance) {
        val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        val time: AbstractUniform = eff.safeGetUniform("Time")
        val mist: AbstractUniform = eff.safeGetUniform("MistColour")
        val localToView: AbstractUniform = eff.safeGetUniform("LocalToView")
        val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        val surface: AbstractUniform = eff.safeGetUniform("Surface")
        val charge: AbstractUniform = eff.safeGetUniform("Charge")
        val burst: AbstractUniform = eff.safeGetUniform("Burst")
        val rise: AbstractUniform = eff.safeGetUniform("Rise")
        private val perFountain: List<Uniform> =
            listOf(localToView, camLocal, boxMin, boxMax, surface, charge, burst, rise).filterIsInstance<Uniform>()

        fun upload() {
            for (uniform in perFountain) uniform.upload()
        }
    }
}
