package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffectInstance
import org.shipwrights.enderkinesis.registry.EKEffects

object OwlsMarkClient {
    /** Held steady instead of pulsing; deeper than Sselith's base darkening so it still reads there. */
    private const val DARKEN_PEAK = 0.55f
    private const val DURATION_TICKS = 5 * 20

    /** Client-only effect: the server never hears of it, so it never sends the removal. */
    fun apply() {
        val player = Minecraft.getInstance().player ?: return
        val fresh = MobEffectInstance(EKEffects.OWLS_MARK.get(), DURATION_TICKS, 0, false, false, true)
        // Extend in place so the fade factor carries over instead of restarting from zero.
        val existing = player.getEffect(EKEffects.OWLS_MARK.get())
        if (existing != null) existing.update(fresh) else player.forceAddEffect(fresh, null)
    }

    /** Vanilla only removes expired effects server-side. */
    fun tick(mc: Minecraft) {
        val player = mc.player ?: return
        val effect = player.getEffect(EKEffects.OWLS_MARK.get()) ?: return
        if (effect.duration <= 0) player.removeEffectNoUpdate(EKEffects.OWLS_MARK.get())
    }

    @JvmStatic
    fun factor(partialTick: Float): Float {
        val player = Minecraft.getInstance().player ?: return 0f
        val data = player.getEffect(EKEffects.OWLS_MARK.get())?.factorData?.orElse(null) ?: return 0f
        return data.getFactor(player, partialTick)
    }

    /** Matches vanilla's `calculateDarknessScale` contract, which LightTexture scales once more. */
    @JvmStatic
    fun darknessScale(partialTick: Float): Float = DARKEN_PEAK * factor(partialTick) * effectScale()

    /** Final per-channel subtraction, for lightmaps that bypass vanilla's updateLightTexture. */
    fun lightmapSubtraction(partialTick: Float): Float = darknessScale(partialTick) * effectScale()

    private fun effectScale(): Float = Minecraft.getInstance().options.darknessEffectScale().get().toFloat()
}
