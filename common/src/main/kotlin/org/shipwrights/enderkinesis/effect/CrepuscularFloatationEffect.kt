package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory

/**
 * Marker / grace-timer effect for the virtual ocean. The buoyancy itself is driven by the
 * Crepusculite Lattice block entity; this effect only records that an entity was *recently*
 * floating, so when it transitions into a gated area (onto the deck, out of the ocean ellipse,
 * onto solid ground) it keeps floating until the effect runs out instead of dropping instantly.
 *
 * It deliberately has no attribute modifiers and never ticks — it is purely a duration counter.
 */
class CrepuscularFloatationEffect :
    MobEffect(MobEffectCategory.BENEFICIAL, 0x4FE6C8) {

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = false
}
