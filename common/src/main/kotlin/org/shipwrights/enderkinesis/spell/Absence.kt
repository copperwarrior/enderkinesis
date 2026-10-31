package org.shipwrights.enderkinesis.spell

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKEffects
import java.util.IdentityHashMap
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Void's Absent: the target is out of the world. It vanishes from every client's view (see
 * [SpellNetwork]), can't be hurt, can't act ([SpellHolds]), and mobs ignore it. It's held
 * where it vanished, players included, and reappears there. An Absent player sees only the
 * Ygann sky (client).
 */
object Absence {

    private class Away(val at: Vec3)

    // Game thread only.
    private val away = IdentityHashMap<LivingEntity, Away>()

    private const val IGNORE_RANGE = 32.0
    private const val PLAYER_SLACK = 0.1

    fun init() {
        EntityEvent.LIVING_HURT.register { entity, _, _ ->
            if (isAbsent(entity)) EventResult.interruptFalse() else EventResult.pass()
        }
        TickEvent.SERVER_POST.register { sweep() }
        LifecycleEvent.SERVER_STOPPED.register { away.clear() }
    }

    /**
     * Brings back anything still away that no longer has the effect, however it lost it. Milk
     * and `/effect clear` tell an effect it's being removed before they take it off, so the
     * effect can't always see it's gone yet; this catches any that slip through. Anything dead
     * or unloaded is forgotten here, and its hold kept with it ([SpellHolds]) for when it's
     * back.
     */
    private fun sweep() {
        if (away.isEmpty()) return
        for (entity in away.keys.toList()) {
            when {
                entity.isRemoved || !entity.isAlive -> away.remove(entity)
                !isAbsent(entity) -> reappear(entity)
            }
        }
    }

    fun isAbsent(entity: LivingEntity): Boolean = entity.hasEffect(EKEffects.ABSENT.get())

    fun vanish(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        if (entity in away) return
        away[entity] = Away(entity.position())
        SpellHolds.hold(entity, SpellHolds.By.ABSENT)
        entity.deltaMovement = Vec3.ZERO
        SpellNetwork.broadcastAbsent(level, entity, true)
        level.sendParticles(
            EKParticles.voidMote(), entity.x, entity.y + entity.bbHeight * 0.5, entity.z, 10,
            entity.bbWidth * 0.5, entity.bbHeight * 0.5, entity.bbWidth * 0.5, 0.05,
        )
    }

    /** Each tick while Absent: held where it vanished, and no mob keeps it as a target. */
    fun hold(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val state = away[entity] ?: return
        entity.deltaMovement = Vec3.ZERO
        if (entity.isUsingItem) entity.releaseUsingItem()
        // A player's client moves them itself, so they're only put back once they've drifted.
        val slack = if (entity is Player) PLAYER_SLACK else 1.0e-2
        if (entity.position().distanceToSqr(state.at) > slack * slack) entity.teleportTo(state.at.x, state.at.y, state.at.z)
        for (mob in level.getEntitiesOfClass(Mob::class.java, entity.boundingBox.inflate(IGNORE_RANGE)) { it.target === entity }) {
            mob.target = null
        }
    }

    fun reappear(entity: LivingEntity) {
        SpellHolds.release(entity, SpellHolds.By.ABSENT)
        val state = away.remove(entity) ?: return
        val level = entity.level() as? ServerLevel ?: return
        entity.teleportTo(state.at.x, state.at.y, state.at.z)
        SpellNetwork.broadcastAbsent(level, entity, false)
    }
}
