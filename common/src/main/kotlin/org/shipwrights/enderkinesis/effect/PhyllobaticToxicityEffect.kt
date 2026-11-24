package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory

/** Gives the holder the Horned Frog's poisonous skin; the hit-back lives in `entity/ToxicThorns.kt`. */
class PhyllobaticToxicityEffect : MobEffect(MobEffectCategory.BENEFICIAL, LIVER_GREEN) {

    private companion object {
        // Brightest green of the Frog Liver texture.
        const val LIVER_GREEN = 0x07999F
    }
}
