package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.LivingEntity

/**
 * Which effect is ending right now. An effect hears it's ending (`removeAttributeModifiers`)
 * both when it ends and when a new dose replaces it, and tells the two apart by whether it's
 * still on the entity. But vanilla's `removeAllEffects` (milk, `/effect clear`) and Forge's
 * milk tell it before they take it off, so it would look like a refresh; the mixin marks the
 * effect being taken off here for the length of that call.
 */
object EndingEffects {
    private class Ending(val entity: LivingEntity, val effect: MobEffect)

    private val ending = ThreadLocal<Ending?>()

    @JvmStatic
    fun begin(entity: LivingEntity, effect: MobEffect) = ending.set(Ending(entity, effect))

    @JvmStatic
    fun end() = ending.remove()

    /** Whether [entity] still has [effect], counting one being taken off as gone. */
    @JvmStatic
    fun stillHas(entity: LivingEntity, effect: MobEffect): Boolean {
        if (!entity.hasEffect(effect)) return false
        val e = ending.get() ?: return true
        return !(e.entity === entity && e.effect === effect)
    }
}
