package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView

/** Render thread only. Glows queue while signatures render and draw at renderLevel TAIL. */
object StillpointGlowVolume {
    private const val MAX_GLOWS_PER_FRAME = 8
    private const val MIN_ALPHA = 0.004f

    private val view = VolumeView()
    private val effect = VolumeEffect("stillpoint_glow_volume")
    private var uniforms: Uniforms? = null
    private val localToView = Matrix4f()

    private var count = 0
    private val centreX = DoubleArray(MAX_GLOWS_PER_FRAME)
    private val centreY = DoubleArray(MAX_GLOWS_PER_FRAME)
    private val centreZ = DoubleArray(MAX_GLOWS_PER_FRAME)
    private val radius = FloatArray(MAX_GLOWS_PER_FRAME)
    private val strength = FloatArray(MAX_GLOWS_PER_FRAME)
    private val colour = IntArray(MAX_GLOWS_PER_FRAME)

    /**
     * [centre] is in world space. [alpha] is the centre alpha of the flat sprite that the glow replaces.
     * Returns false when the volume cannot draw the glow. Then the caller draws the flat sprite.
     */
    fun queue(centre: Vec3, radius: Double, rgb: Int, alpha: Float): Boolean {
        require(radius > 0.0) { "radius must be positive: $radius" }
        if (alpha <= MIN_ALPHA) return true
        if (count >= MAX_GLOWS_PER_FRAME || IrisCompat.isShaderPackInUse() || effect.get() == null) return false
        val i = count++
        centreX[i] = centre.x
        centreY[i] = centre.y
        centreZ[i] = centre.z
        this.radius[i] = radius.toFloat()
        // Quantize as the sprite vertex alpha does, so that the two paths look the same.
        strength[i] = (alpha * 255).toInt().coerceIn(0, 255) / 255f
        colour[i] = rgb
        return true
    }

    // The volume writes no depth, so call it at renderLevel TAIL. Then nothing drawn after it paints over it.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val n = count
        count = 0
        if (n == 0 || IrisCompat.isShaderPackInUse()) return
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = Minecraft.getInstance().mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        u.pushFrame(main.width, main.height)
        VolumeProxy.begin(main, VolumeComposite.Blend.ADDITIVE)
        for (i in 0 until n) {
            u.pushGlow(i)
            VolumeProxy.draw(eff)
        }
        VolumeProxy.end(main, eff)
    }

    private class Uniforms(private val eff: EffectInstance) {
        private val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        private val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        private val localToViewU: AbstractUniform = eff.safeGetUniform("LocalToView")
        private val glowCentre: AbstractUniform = eff.safeGetUniform("GlowCentre")
        private val radiusU: AbstractUniform = eff.safeGetUniform("Radius")
        private val strengthU: AbstractUniform = eff.safeGetUniform("Strength")
        private val glowColour: AbstractUniform = eff.safeGetUniform("GlowColour")
        private val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")

        fun pushFrame(width: Int, height: Int) {
            view.pushViewProj(eff)
            view.pushDepthParams(eff)
            screenSize.set(width.toFloat(), height.toFloat())
        }

        fun pushGlow(i: Int) {
            val cx = (centreX[i] - view.camX).toFloat()
            val cy = (centreY[i] - view.camY).toFloat()
            val cz = (centreZ[i] - view.camZ).toFloat()
            val r = radius[i]
            boxMin.set(-r, -r, -r)
            boxMax.set(r, r, r)
            localToViewU.set(localToView.translation(cx, cy, cz))
            glowCentre.set(cx, cy, cz)
            radiusU.set(r)
            strengthU.set(strength[i])
            val rgb = colour[i]
            glowColour.set(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)
        }
    }
}
