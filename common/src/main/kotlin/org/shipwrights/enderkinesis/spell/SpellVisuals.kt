package org.shipwrights.enderkinesis.spell

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import org.shipwrights.enderkinesis.registry.EKEffects
import java.util.WeakHashMap

/**
 * Which spell effects an entity visibly carries, for every client to draw: clients only
 * know their own player's effects. Sent as bits whenever one starts or ends.
 */
object SpellVisuals {
    const val CRYSTALLIZED = 1
    const val ENTANGLED = 2
    const val FROZEN = 4

    // Game thread only: the bits last sent for each entity.
    private val sent = WeakHashMap<LivingEntity, Int>()

    fun bits(entity: LivingEntity): Int {
        var bits = 0
        if (entity.hasEffect(EKEffects.CRYSTALLIZED.get())) bits = bits or CRYSTALLIZED
        if (entity.hasEffect(EKEffects.ENTANGLED.get())) bits = bits or ENTANGLED
        if (entity.hasEffect(EKEffects.FROZEN.get())) bits = bits or FROZEN
        return bits
    }

    /** Called when one of these effects starts or ends on [entity]. */
    fun update(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val bits = bits(entity)
        if ((sent[entity] ?: 0) == bits) return
        if (bits == 0) sent.remove(entity) else sent[entity] = bits
        SpellNetwork.broadcastVisuals(level, entity, bits)
    }
}
