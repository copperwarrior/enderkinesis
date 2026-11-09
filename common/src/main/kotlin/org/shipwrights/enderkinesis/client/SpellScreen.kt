package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.world.entity.Entity
import org.shipwrights.enderkinesis.registry.EKEffects
import kotlin.math.exp
import kotlin.math.sin

object SpellScreen {
    private val LOG: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("EnderkinesisSpellScreen")

    /** In degrees. */
    private const val SAG_ROLL = 3.5f
    private const val SAG_SWAY = 1.0f
    /** Per second. */
    private const val EASE = 4.0

    @Volatile private var effect: EffectInstance? = null
    @Volatile private var loadFailed = false
    private var hollowed = 0f
    private var sag = 0f
    private var lastNanos = 0L

    private fun ensureLoaded(mc: Minecraft): EffectInstance? {
        effect?.let { return it }
        if (loadFailed) return null
        return try {
            EffectInstance(mc.resourceManager, "spell_hollowed").also { effect = it }
        } catch (t: Throwable) {
            loadFailed = true
            LOG.warn("Failed to load the Hollowed shader program", t)
            null
        }
    }

    fun close() {
        effect?.close()
        effect = null
        loadFailed = false
        SpellCubeFx.close()
        DrakeSicknessClient.close()
    }

    private fun ease(current: Float, on: Boolean, dt: Double): Float {
        val k = (1 - exp(-EASE * dt)).toFloat()
        return current + ((if (on) 1f else 0f) - current) * k
    }

    /** Call from `Camera.setup`, once each frame. */
    @JvmStatic
    fun camera(entity: Entity) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else ((now - lastNanos) / 1e9).coerceAtMost(0.25)
        lastNanos = now
        val player = Minecraft.getInstance().player
        val self = player != null && entity === player
        hollowed = ease(hollowed, self && player!!.hasEffect(EKEffects.HOLLOWED.get()), dt)
        sag = ease(sag, self && player!!.hasEffect(EKEffects.WEIGHTED.get()), dt)
        DrakeSicknessClient.update(player?.hasEffect(EKEffects.DRAKE_SICKNESS.get()) == true, dt)
    }

    /** Call with `GameRenderer.bobHurt`. A roll about the screen centre keeps the crosshair on the target. */
    @JvmStatic
    fun roll(pose: PoseStack, partialTick: Float) {
        if (sag < 0.001f) return
        val sway = sin(SpellClock.hand(partialTick) * 0.04).toFloat()
        pose.mulPose(Axis.ZP.rotationDegrees((SAG_ROLL + SAG_SWAY * sway) * sag))
    }

    /** Call after the world and before the hand. */
    @JvmStatic
    fun beforeHand() {
        val mc = Minecraft.getInstance()
        mc.level?.let { SpellCubeFx.renderWorld(SpellClock.time(it, mc.frameTime)) }
    }

    /** Call after the level and the hand. */
    @JvmStatic
    fun render() {
        val mc = Minecraft.getInstance()
        // Cube effects go first, so that Hollowed drains them too.
        mc.level?.let { SpellCubeFx.render(SpellClock.time(it, mc.frameTime)) }
        DrakeSicknessClient.render()
        if (hollowed < 0.005f) return
        val loaded = ensureLoaded(mc) ?: return
        val strength = hollowed
        RefractionPass.run(loaded) { eff -> eff.safeGetUniform("Strength").set(strength) }
    }
}
