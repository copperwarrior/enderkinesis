package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeMap
import org.shipwrights.enderkinesis.item.GrandStaffDread

/** Players walk by [org.shipwrights.enderkinesis.possession.Possession]; mobs by the flee goal. */
class UlderParanoiaEffect : MobEffect(MobEffectCategory.HARMFUL, COLOR) {

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = duration % 10 == 0

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        (entity as? Mob)?.let(GrandStaffDread::holdFleeGoal)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        if (!EndingEffects.stillHas(entity, this)) GrandStaffDread.release(entity)
    }

    private companion object {
        // The darkest texel of the staff's orb.
        const val COLOR = 0x032620
    }
}
