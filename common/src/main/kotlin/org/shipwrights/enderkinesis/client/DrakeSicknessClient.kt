package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import kotlin.math.exp

/** Render thread only: the sound engine and the camera both run on it. */
object DrakeSicknessClient {
    private val LOG: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("EnderkinesisDrakeSickness")

    /** Most UV offset of the ripple, as a share of the screen, at full sickness. */
    private const val RIPPLE_AMPLITUDE = 0.004f
    /** Multiplies the ripple speeds in the shader. */
    private const val RIPPLE_SPEED = 1.0f
    /** Added to the pitch of every sound at full sickness. */
    private const val PITCH_RAISE = 0.25f
    /** Per second. */
    private const val EASE = 2.0
    private val OPAQUE_BLEND = BlendMode()

    private var amount = 0f
    private var effect: EffectInstance? = null
    private var loadFailed = false

    /** Call once each frame. */
    fun update(sick: Boolean, dt: Double) {
        val k = (1 - exp(-EASE * dt)).toFloat()
        amount += ((if (sick) 1f else 0f) - amount) * k
    }

    @JvmStatic
    fun pitch(pitch: Float): Float = if (amount < 0.001f) pitch else pitch * (1f + PITCH_RAISE * amount)

    /** Call after the level and the hand. */
    fun render() {
        val mc = Minecraft.getInstance()
        val scale = mc.options.screenEffectScale().get().toFloat()
        val strength = amount * scale * scale
        if (strength < 0.001f) return
        val player = mc.player ?: return
        val eff = ensureLoaded(mc) ?: return
        val time = (player.tickCount + mc.frameTime) / 20f * RIPPLE_SPEED
        RefractionPass.run(eff) { e ->
            e.safeGetUniform("Strength").set(strength)
            e.safeGetUniform("Amplitude").set(RIPPLE_AMPLITUDE)
            e.safeGetUniform("Time").set(time)
        }
        // Core shaders share the BlendMode cache: leave it opaque, or the next one turns blending off.
        OPAQUE_BLEND.apply()
        RenderSystem.defaultBlendFunc()
    }

    private fun ensureLoaded(mc: Minecraft): EffectInstance? {
        effect?.let { return it }
        if (loadFailed) return null
        return try {
            EffectInstance(mc.resourceManager, "drake_sickness_ripple").also { effect = it }
        } catch (t: Throwable) {
            loadFailed = true
            LOG.warn("Failed to load the Drake Sickness ripple shader", t)
            null
        }
    }

    fun close() {
        effect?.close()
        effect = null
        loadFailed = false
    }
}
