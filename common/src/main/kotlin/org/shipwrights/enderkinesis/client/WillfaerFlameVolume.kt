package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView

/** Render thread only. Will-o'-the-wisps and Willfaer Lanterns queue while they render and draw at renderLevel TAIL. */
object WillfaerFlameVolume {
    private const val MAX_FLAMES = 64

    // Holds the burst motes, which the shader throws out to about 2.7 Radius.
    private const val BURST_BOX = 1.8f

    private val view = VolumeView()
    private val effect = VolumeEffect("willfaer_flame_volume")
    private var uniforms: Uniforms? = null
    private val localToView = Matrix4f()

    private var count = 0
    private val centreX = DoubleArray(MAX_FLAMES)
    private val centreY = DoubleArray(MAX_FLAMES)
    private val centreZ = DoubleArray(MAX_FLAMES)
    private val radii = FloatArray(MAX_FLAMES)
    private val seeds = FloatArray(MAX_FLAMES)
    private val fades = FloatArray(MAX_FLAMES)
    private val bursts = FloatArray(MAX_FLAMES)

    /**
     * [x], [y], [z] are the flame centre in world space. [radius] also holds the motes. [fade] and [burst] are 0..1.
     * Drops the flame past the cap.
     */
    fun queue(x: Double, y: Double, z: Double, radius: Float, seed: Float, fade: Float = 1f, burst: Float = 0f) {
        require(radius > 0f) { "flame radius must be positive: $radius" }
        require(fade in 0f..1f && burst in 0f..1f) { "fade and burst must be in 0..1: $fade, $burst" }
        if (fade <= 0f) return
        if (count >= MAX_FLAMES) return
        val i = count++
        centreX[i] = x
        centreY[i] = y
        centreZ[i] = z
        radii[i] = radius
        seeds[i] = seed
        fades[i] = fade
        bursts[i] = burst
    }

    // The volume writes no depth, so call it at renderLevel TAIL. Then nothing drawn after it paints over it.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val n = count
        count = 0
        if (n == 0 || IrisCompat.isShaderPackInUse()) return
        val eff = effect.get() ?: return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = mc.mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        u.pushFrame(main.width, main.height, SpellClock.time(level, mc.frameTime))
        VolumeProxy.begin(main, VolumeComposite.Blend.ADDITIVE)
        for (i in 0 until n) {
            u.pushFlame(i)
            VolumeProxy.draw(eff)
        }
        VolumeProxy.end(main, eff)
    }

    private class Uniforms(private val eff: EffectInstance) {
        private val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        private val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        private val localToViewU: AbstractUniform = eff.safeGetUniform("LocalToView")
        private val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        private val radiusU: AbstractUniform = eff.safeGetUniform("Radius")
        private val seedU: AbstractUniform = eff.safeGetUniform("Seed")
        private val fadeU: AbstractUniform = eff.safeGetUniform("Fade")
        private val burstU: AbstractUniform = eff.safeGetUniform("Burst")
        private val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        private val spellTicks: AbstractUniform = eff.safeGetUniform("SpellTicks")

        fun pushFrame(width: Int, height: Int, ticks: Float) {
            view.pushViewProj(eff)
            view.pushDepthParams(eff)
            screenSize.set(width.toFloat(), height.toFloat())
            spellTicks.set(ticks)
        }

        fun pushFlame(i: Int) {
            val x = (centreX[i] - view.camX).toFloat()
            val y = (centreY[i] - view.camY).toFloat()
            val z = (centreZ[i] - view.camZ).toFloat()
            val r = radii[i]
            val b = bursts[i]
            // Same ease-out as spreadOf in the shader.
            val box = r * (1f + BURST_BOX * (1f - (1f - b) * (1f - b)))
            boxMin.set(-box, -box, -box)
            boxMax.set(box, box, box)
            localToViewU.set(localToView.translation(x, y, z))
            camLocal.set(-x, -y, -z)
            radiusU.set(r)
            seedU.set(seeds[i])
            fadeU.set(fades[i])
            burstU.set(b)
        }
    }
}
