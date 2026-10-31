package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket
import org.shipwrights.enderkinesis.effect.BloodSurgeFades
import org.shipwrights.enderkinesis.registry.EKEffects

/**
 * Blood Surge's hearts fading without the hurt tilt. As they fade the server brings health down
 * with them, and the client would play each drop as being hurt: the camera jolts and the screen
 * flashes red. The server sends the effect's removal before the fade starts, so the removal
 * leaves an allowance of the hearts it gave (4 health a level), and drops within it arriving
 * while the fade lasts are set quietly. Only Blood Surge's: Health Boost and every other effect
 * lose health vanilla's way. A real hit still jolts through its own damage event.
 */
object BloodSurgeFadeClient {
    private var allowance = 0f
    private var until = 0L

    /** On an effect coming off the local player (game thread only), while it still has it. */
    @JvmStatic
    fun onRemoveEffect(packet: ClientboundRemoveMobEffectPacket) {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) return
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (packet.effect !== EKEffects.BLOOD_SURGE.get() || packet.getEntity(level) !== player) return
        val surge = player.getEffect(EKEffects.BLOOD_SURGE.get()) ?: return
        allowance = HEALTH_PER_LEVEL * (surge.amplifier + 1)
        until = level.gameTime + BloodSurgeFades.FADE_TICKS + SLACK_TICKS
    }

    /** Sets [health] quietly and returns true if this drop is Blood Surge's hearts fading. */
    @JvmStatic
    fun quietFade(player: LocalPlayer, health: Float): Boolean {
        if (allowance <= 0f) return false
        if (player.level().gameTime > until) {
            allowance = 0f
            return false
        }
        val drop = player.health - health
        if (drop <= 0f || drop > allowance + EPSILON) return false
        allowance -= drop
        player.health = health
        return true
    }

    private const val HEALTH_PER_LEVEL = 4f
    /** Leeway past the fade's own length for the last of it to arrive. */
    private const val SLACK_TICKS = 10L
    private const val EPSILON = 0.01f
}
