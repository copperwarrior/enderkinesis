package org.shipwrights.enderkinesis.effect

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import java.util.UUID
import java.util.WeakHashMap

/** Not saved: after a reload, effect damage gets no kill credit. A dose with no source keeps the last caster. */
object SpellCredit {
    // Game thread only.
    private val casters = WeakHashMap<LivingEntity, HashMap<MobEffect, UUID>>()

    @JvmStatic
    fun applied(entity: LivingEntity, effect: MobEffect, source: Entity?) {
        if (entity.level().isClientSide || source !is LivingEntity || source === entity) return
        casters.getOrPut(entity) { HashMap() }[effect] = source.uuid
    }

    fun caster(entity: LivingEntity, effect: MobEffect): Entity? {
        val id = casters[entity]?.get(effect) ?: return null
        return (entity.level() as? ServerLevel)?.getEntity(id)
    }
}
