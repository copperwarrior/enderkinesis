package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance

/** Steady lightmap darkening with no pulse and no fog; see `OwlsMarkClient`. Uses vanilla
 *  Darkness's FactorData so the client gets a synced fade in and out. */
class OwlsMarkEffect : MobEffect(MobEffectCategory.HARMFUL, 0x7C6C32) {

    init {
        setFactorDataFactory { MobEffectInstance.FactorData(FADE_TICKS) }
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = false

    private companion object {
        /** Same padding as vanilla Darkness. */
        const val FADE_TICKS = 22
    }
}
