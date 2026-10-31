package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.world.entity.Entity
import org.shipwrights.enderkinesis.registry.EKEffects
import kotlin.math.exp
import kotlin.math.sin

/**
 * What the player's own statuses do to the whole view, beyond the HUD overlays ([SpellOverlays]):
 *
 * - **Hollowed:** the world drained of colour, as a pass over the finished frame.
 * - The spell cubes' effects round them ([SpellCubeFx]): the world's once the world is drawn,
 *   your hand's after the hand.
 * - **Weighted:** the view lists heavily to one side and sways slowly, a roll about the middle
 *   of the screen as vanilla's hurt tilt is, so the crosshair stays on what you'd hit.
 *
 * Both ease in and out rather than snapping, and both show in third person as in first, as the
 * HUD overlays do.
 */
object SpellScreen {
    private val LOG: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("EnderkinesisSpellScreen")

    /** Degrees the view lists while Weighted, and how far either way it sways about that. */
    private const val SAG_ROLL = 3.5f
    private const val SAG_SWAY = 1.0f
    /** How quickly the effects ease towards on or off, per second. */
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
    }

    private fun ease(current: Float, on: Boolean, dt: Double): Float {
        val k = (1 - exp(-EASE * dt)).toFloat()
        return current + ((if (on) 1f else 0f) - current) * k
    }

    /** From `Camera.setup`, once a frame: eases both effects in or out for the view's [entity]. */
    @JvmStatic
    fun camera(entity: Entity) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else ((now - lastNanos) / 1e9).coerceAtMost(0.25)
        lastNanos = now
        val player = Minecraft.getInstance().player
        val self = player != null && entity === player
        hollowed = ease(hollowed, self && player!!.hasEffect(EKEffects.HOLLOWED.get()), dt)
        sag = ease(sag, self && player!!.hasEffect(EKEffects.WEIGHTED.get()), dt)
    }

    /** With vanilla's hurt tilt (`GameRenderer.bobHurt`), for the world's view and the hand's:
     *  a Weighted player's view lists. */
    @JvmStatic
    fun roll(pose: PoseStack, partialTick: Float) {
        if (sag < 0.001f) return
        val sway = sin(SpellClock.hand(partialTick) * 0.04).toFloat()
        pose.mulPose(Axis.ZP.rotationDegrees((SAG_ROLL + SAG_SWAY * sway) * sag))
    }

    /** Once the world is drawn, before the hand: the effects round the world's spell cubes. */
    @JvmStatic
    fun beforeHand() {
        val mc = Minecraft.getInstance()
        mc.level?.let { SpellCubeFx.renderWorld(SpellClock.time(it, mc.frameTime)) }
    }

    /** Once the level and the hand are drawn: drains the colour from a Hollowed player's view. */
    @JvmStatic
    fun render() {
        val mc = Minecraft.getInstance()
        // The spell cubes' effects round them first, so Hollowed drains those too.
        mc.level?.let { SpellCubeFx.render(SpellClock.time(it, mc.frameTime)) }
        if (hollowed < 0.005f) return
        val loaded = ensureLoaded(mc) ?: return
        val strength = hollowed
        RefractionPass.run(loaded) { eff -> eff.safeGetUniform("Strength").set(strength) }
    }

    /** How far each effect has eased in, 0..1, for the HUD overlays to follow. */
    fun hollowedAmount(): Float = hollowed
    fun weightedAmount(): Float = sag
}
