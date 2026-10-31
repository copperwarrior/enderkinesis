package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket
import org.shipwrights.enderkinesis.effect.BloodSurgeFades
import org.shipwrights.enderkinesis.registry.EKEffects

// Vanilla plays each health drop from the server as a hurt (camera jolt, red flash). The removal packet
// comes before the fade, so it sets an allowance of quiet drops for Blood Surge only.
object BloodSurgeFadeClient {
    private var allowance = 0f
    private var until = 0L

    /** Game thread only. Runs while the player still has the effect. */
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
    // Margin past the fade length, so the last drops arrive in time.
    private const val SLACK_TICKS = 10L
    private const val EPSILON = 0.01f
}
