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

/** Render thread only. Cloaks queue during the spell pass and draw at renderLevel TAIL. */
object SpellCloakVolume {
    private const val MAX_CLOAKS = 64

    /** Keep equal to REACH in the shader. */
    private const val REACH = 0.95f

    private val view = VolumeView()
    private val light = Program(VolumeEffect("spell_cloak_volume"), VolumeComposite.Blend.ADDITIVE)
    private val dark = Program(VolumeEffect("spell_cloak_dark_volume"), VolumeComposite.Blend.ALPHA)
    private val localToView = Matrix4f()

    private var count = 0
    private val feetX = DoubleArray(MAX_CLOAKS)
    private val feetY = DoubleArray(MAX_CLOAKS)
    private val feetZ = DoubleArray(MAX_CLOAKS)
    private val heights = FloatArray(MAX_CLOAKS)
    private val bodies = FloatArray(MAX_CLOAKS)
    private val styles = IntArray(MAX_CLOAKS)
    private val darks = BooleanArray(MAX_CLOAKS)
    private val densities = FloatArray(MAX_CLOAKS)
    private val strengths = FloatArray(MAX_CLOAKS)

    /** [x], [y], [z] are the feet of the wearer in world space. Drops the cloak past the cap. */
    @Suppress("LongParameterList") // One flat record, kept in parallel arrays so queueing does not allocate.
    fun queue(
        x: Double, y: Double, z: Double, height: Float, body: Float,
        style: Int, isDark: Boolean, density: Float, strength: Float,
    ) {
        if (count >= MAX_CLOAKS) return
        feetX[count] = x
        feetY[count] = y
        feetZ[count] = z
        heights[count] = height
        bodies[count] = body
        styles[count] = style
        darks[count] = isDark
        densities[count] = density
        strengths[count] = strength
        count++
    }

    // The volume writes no depth, so anything drawn after it would paint over it.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        try {
            if (count > 0 && !IrisCompat.isShaderPackInUse()) draw(poseStack, projection, camera)
        } finally {
            count = 0
        }
    }

    private fun draw(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val main = mc.mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        val ticks = SpellClock.time(level, mc.frameTime)
        light.uniforms()?.pushFrame(main.width, main.height, ticks)
        dark.uniforms()?.pushFrame(main.width, main.height, ticks)

        // Runs of one blend, in queue order: additive and alpha blends do not commute.
        var open: Program? = null
        for (i in 0 until count) {
            val program = if (darks[i]) dark else light
            val u = program.uniforms() ?: continue
            pushCloak(u, i)
            if (program !== open) {
                open?.let { VolumeProxy.end(main, it.eff()) }
                VolumeProxy.begin(main, program.blend)
                VolumeProxy.draw(program.eff())
                open = program
            } else {
                u.uploadCloak()
                VolumeProxy.drawApplied()
            }
        }
        open?.let { VolumeProxy.end(main, it.eff()) }
    }

    private fun pushCloak(u: Uniforms, i: Int) {
        val x = (feetX[i] - view.camX).toFloat()
        val y = (feetY[i] - view.camY).toFloat()
        val z = (feetZ[i] - view.camZ).toFloat()
        val radius = bodies[i] + REACH
        u.boxMin.set(-radius, 0f, -radius)
        u.boxMax.set(radius, heights[i], radius)
        u.localToView.set(localToView.translation(x, y, z))
        u.camLocal.set(-x, -y, -z)
        u.style.set(styles[i])
        u.density.set(densities[i])
        u.strength.set(strengths[i])
        u.height.set(heights[i])
        u.body.set(bodies[i])
    }

    private class Program(private val effect: VolumeEffect, val blend: VolumeComposite.Blend) {
        private var uniforms: Uniforms? = null

        fun eff(): EffectInstance = checkNotNull(effect.get())

        fun uniforms(): Uniforms? {
            uniforms?.let { return it }
            val eff = effect.get() ?: return null
            return Uniforms(eff).also { uniforms = it }
        }
    }

    private class Uniforms(private val eff: EffectInstance) {
        val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        val localToView: AbstractUniform = eff.safeGetUniform("LocalToView")
        val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        val style: AbstractUniform = eff.safeGetUniform("Style")
        val density: AbstractUniform = eff.safeGetUniform("Density")
        val strength: AbstractUniform = eff.safeGetUniform("Strength")
        val height: AbstractUniform = eff.safeGetUniform("Height")
        val body: AbstractUniform = eff.safeGetUniform("Body")
        private val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        private val spellTicks: AbstractUniform = eff.safeGetUniform("SpellTicks")
        private val perCloak: List<Uniform> = listOf(
            boxMin, boxMax, localToView, camLocal, style, density, strength, height, body,
        ).filterIsInstance<Uniform>()

        fun uploadCloak() {
            for (uniform in perCloak) uniform.upload()
        }

        fun pushFrame(width: Int, height: Int, ticks: Float) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            screenSize.set(width.toFloat(), height.toFloat())
            spellTicks.set(ticks)
        }
    }
}
