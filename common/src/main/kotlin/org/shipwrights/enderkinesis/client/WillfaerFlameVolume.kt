package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.joml.Matrix4f
import org.joml.Vector3f
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
        u.pushFrame(main.width, main.height, SpellClock.time(level, mc.frameTime), coverage = false)
        VolumeProxy.begin(main, VolumeComposite.Blend.ADDITIVE)
        for (i in 0 until n) {
            u.pushFlame(i)
            VolumeProxy.draw(eff)
        }
        VolumeProxy.end(main, eff)
    }

    /** The queue length now. A sketch draw takes the flames that queue after it, then calls [rewind]. */
    fun mark(): Int = count

    /** Moves the flames queued since [mark]. */
    fun shift(mark: Int, dx: Double, dy: Double, dz: Double) {
        for (i in mark until count) {
            centreX[i] += dx
            centreY[i] += dy
            centreZ[i] += dz
        }
    }

    /** Drops the flames queued since [mark], so a sketch draw does not add flames to the world. */
    fun rewind(mark: Int) {
        require(mark in 0..count) { "mark $mark is past the queue length $count" }
        count = mark
    }

    /**
     * Draws the flames queued since [mark] into [target], with the camera at the origin and the target's own depth.
     * The projection can be perspective or orthographic. Alpha gets the flame brightness, because the sketch reads
     * alpha as coverage.
     */
    fun renderInto(target: RenderTarget, mark: Int, projection: Matrix4f, rotation: Matrix4f) {
        if (mark >= count || IrisCompat.isShaderPackInUse()) return
        val eff = effect.get() ?: return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        view.update(projection, rotation, 0.0, 0.0, 0.0)
        u.pushFrame(target.width, target.height, SpellClock.time(level, mc.frameTime), coverage = true)
        // An orthographic projection has no perspective divide; then every ray runs along the view axis.
        if (projection.m23() == 0f) {
            val ray = Matrix4f(rotation).invert().transformDirection(Vector3f(0f, 0f, -1f)).normalize()
            u.pushOrtho(ray.x, ray.y, ray.z, 1f)
        }
        effect.useDepth(target.depthTextureId)
        VolumeProxy.begin(target, VolumeComposite.Blend.ADDITIVE)
        try {
            for (i in mark until count) {
                u.pushFlame(i)
                eff.apply()
                // After apply, which sets the JSON blend; that blend keeps the destination alpha.
                RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE,
                )
                VolumeProxy.drawApplied()
            }
        } finally {
            VolumeProxy.end(target, eff)
            effect.useSceneDepth()
        }
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
        private val coverageU: AbstractUniform = eff.safeGetUniform("Coverage")
        private val orthoRay: AbstractUniform = eff.safeGetUniform("OrthoRay")

        fun pushFrame(width: Int, height: Int, ticks: Float, coverage: Boolean) {
            view.pushViewProj(eff)
            view.pushDepthParams(eff)
            screenSize.set(width.toFloat(), height.toFloat())
            spellTicks.set(ticks)
            coverageU.set(if (coverage) 1f else 0f)
            orthoRay.set(0f, 0f, 0f, 0f)
        }

        /** [x], [y], [z] is the unit view direction in world axes; [w] is 1 for an orthographic view. */
        fun pushOrtho(x: Float, y: Float, z: Float, w: Float) = orthoRay.set(x, y, z, w)

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
