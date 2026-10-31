package org.shipwrights.enderkinesis.effect

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import java.util.UUID
import java.util.WeakHashMap

/**
 * Who put each spell effect on an entity, so damage the effect deals later (Crystallized, Rot,
 * Bleeding, Weighted) credits them with the kill, as the spec asks. Recorded from every
 * `addEffect` that names its source (the mixin); a dose with no source keeps the last one's.
 * Server only, not saved: after a reload the effect's damage goes uncredited.
 */
object SpellCredit {
    // Game thread only.
    private val casters = WeakHashMap<LivingEntity, HashMap<MobEffect, UUID>>()

    @JvmStatic
    fun applied(entity: LivingEntity, effect: MobEffect, source: Entity?) {
        if (entity.level().isClientSide || source !is LivingEntity || source === entity) return
        casters.getOrPut(entity) { HashMap() }[effect] = source.uuid
    }

    /** Whoever last put [effect] on [entity], if they're still about. */
    fun caster(entity: LivingEntity, effect: MobEffect): Entity? {
        val id = casters[entity]?.get(effect) ?: return null
        return (entity.level() as? ServerLevel)?.getEntity(id)
    }
}
