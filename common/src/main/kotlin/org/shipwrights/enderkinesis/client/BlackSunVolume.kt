package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.vertex.PoseStack
import java.util.function.IntSupplier
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView

/** Render thread only. Entity rendering queues the suns, and renderLevel TAIL draws them. */
object BlackSunVolume {
    private const val MAX_SUNS_PER_FRAME = 8

    private val view = VolumeView()
    private val effect = VolumeEffect("black_sun_volume", "SceneSampler" to IntSupplier { SceneCopy.textureId })
    private var uniforms: Uniforms? = null
    private val localToView = Matrix4f()

    private var count = 0
    private val centreX = DoubleArray(MAX_SUNS_PER_FRAME)
    private val centreY = DoubleArray(MAX_SUNS_PER_FRAME)
    private val centreZ = DoubleArray(MAX_SUNS_PER_FRAME)
    private val core = FloatArray(MAX_SUNS_PER_FRAME)
    private val outer = FloatArray(MAX_SUNS_PER_FRAME)
    private val tip = FloatArray(MAX_SUNS_PER_FRAME)
    private val shown = FloatArray(MAX_SUNS_PER_FRAME)

    /** [centre] is in world space. Returns false when the volume cannot draw, so the caller draws a stand-in. */
    fun queue(centre: Vec3, core: Double, outer: Double, tip: Int, shown: Float): Boolean {
        require(tip in 0..255) { "tip out of range: $tip" }
        if (count >= MAX_SUNS_PER_FRAME || IrisCompat.isShaderPackInUse() || effect.get() == null) return false
        val i = count++
        centreX[i] = centre.x
        centreY[i] = centre.y
        centreZ[i] = centre.z
        this.core[i] = core.toFloat()
        this.outer[i] = outer.toFloat()
        this.tip[i] = tip / 255f
        // Quantized to a byte, as vertex alpha is, so the look does not change.
        this.shown[i] = (shown * 255).toInt().coerceIn(0, 255) / 255f
        return true
    }

    // Called at renderLevel TAIL: the volume writes no depth, so anything drawn after it would paint over it.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val n = count
        count = 0
        if (n == 0 || IrisCompat.isShaderPackInUse()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = mc.mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        u.pushFrame(main.width, main.height, SpellClock.time(level, mc.frameTime))
        // Copy the scene again for each sun, so each sun bends the light of the suns drawn before it.
        for (i in 0 until n) {
            if (!SceneCopy.copy()) return
            u.pushSun(i)
            VolumeProxy.begin(main, VolumeComposite.Blend.ALPHA)
            VolumeProxy.draw(eff)
            VolumeProxy.end(main, eff)
        }
    }

    private class Uniforms(private val eff: EffectInstance) {
        private val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        private val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        private val localToViewU: AbstractUniform = eff.safeGetUniform("LocalToView")
        private val sunCentre: AbstractUniform = eff.safeGetUniform("SunCentre")
        private val radii: AbstractUniform = eff.safeGetUniform("Radii")
        private val tipU: AbstractUniform = eff.safeGetUniform("Tip")
        private val shownU: AbstractUniform = eff.safeGetUniform("Shown")
        private val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        private val spellTicks: AbstractUniform = eff.safeGetUniform("SpellTicks")

        fun pushFrame(width: Int, height: Int, ticks: Float) {
            view.pushViewProj(eff)
            view.pushDepthParams(eff)
            screenSize.set(width.toFloat(), height.toFloat())
            spellTicks.set(ticks)
        }

        fun pushSun(i: Int) {
            val cx = (centreX[i] - view.camX).toFloat()
            val cy = (centreY[i] - view.camY).toFloat()
            val cz = (centreZ[i] - view.camZ).toFloat()
            val r = outer[i]
            boxMin.set(-r, -r, -r)
            boxMax.set(r, r, r)
            // The disk tilt is in world axes, so local axes are world axes and the box does not turn.
            localToViewU.set(localToView.translation(cx, cy, cz))
            sunCentre.set(cx, cy, cz)
            radii.set(core[i], r)
            tipU.set(tip[i])
            shownU.set(shown[i])
        }
    }
}
