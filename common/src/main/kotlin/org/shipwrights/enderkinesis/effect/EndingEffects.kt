package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.LivingEntity

/**
 * Vanilla `removeAllEffects` and Forge milk call `removeAttributeModifiers` before they remove the effect, so it looks like a refresh.
 * The mixin marks the effect here for the duration of that call.
 */
object EndingEffects {
    private class Ending(val entity: LivingEntity, val effect: MobEffect)

    private val ending = ThreadLocal<Ending?>()

    @JvmStatic
    fun begin(entity: LivingEntity, effect: MobEffect) = ending.set(Ending(entity, effect))

    @JvmStatic
    fun end() = ending.remove()

    fun stillHas(entity: LivingEntity, effect: MobEffect): Boolean {
        if (!entity.hasEffect(effect)) return false
        val e = ending.get() ?: return true
        return !(e.entity === entity && e.effect === effect)
    }
}
