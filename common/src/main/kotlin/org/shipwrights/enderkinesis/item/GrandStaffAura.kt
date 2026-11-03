package org.shipwrights.enderkinesis.item

import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

object GrandStaffAura {

    /** Mobs and players in [radius] of [caster], but not the caster and not its pets or raised dead. Server and client. */
    fun targets(level: Level, caster: LivingEntity, radius: Double): List<LivingEntity> {
        val radiusSq = radius * radius
        return level.getEntitiesOfClass(LivingEntity::class.java, caster.boundingBox.inflate(radius)) {
            affects(caster, it) && it.distanceToSqr(caster) <= radiusSq
        }
    }

    private fun affects(caster: LivingEntity, entity: LivingEntity): Boolean {
        if (entity === caster || !entity.isAlive || entity.isSpectator) return false
        if (entity !is Mob && entity !is Player) return false
        return (entity as? OwnableEntity)?.ownerUUID != caster.uuid
    }
}
