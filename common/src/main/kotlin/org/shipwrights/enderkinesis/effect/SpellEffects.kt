package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.LivingEntity
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.spell.SpellContext

/** Rules shared by the Mystic Scroll status effects. */
object SpellEffects {

    /** Whether [entity] is a boss ([SpellContext.BOSSES]) and [effect] one that would control,
     *  hold or turn it: Enthralled, Frozen, Absent, Entangled or Discord. */
    @JvmStatic
    fun bossImmune(entity: LivingEntity, effect: MobEffect): Boolean =
        SpellContext.isBoss(entity) && (
            effect === EKEffects.ENTHRALLED.get() || effect === EKEffects.FROZEN.get() || effect === EKEffects.ABSENT.get() ||
                effect === EKEffects.ENTANGLED.get() || effect === EKEffects.DISCORD.get()
            )
}
