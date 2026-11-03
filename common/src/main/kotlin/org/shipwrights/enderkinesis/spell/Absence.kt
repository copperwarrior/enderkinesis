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
import org.shipwrights.enderkinesis.registry.EKSounds
import java.util.IdentityHashMap
import org.shipwrights.enderkinesis.registry.EKParticles

object Absence {

    private class Away(val at: Vec3)

    // Game thread only.
    private val away = IdentityHashMap<LivingEntity, Away>()

    private const val IGNORE_RANGE = 32.0
    private const val PLAYER_SLACK = 0.1
    // Mob AI looks for a new target only on every other tick.
    private const val TARGET_CHECK_EVERY = 2

    fun init() {
        EntityEvent.LIVING_HURT.register { entity, _, _ ->
            if (isAbsent(entity)) EventResult.interruptFalse() else EventResult.pass()
        }
        TickEvent.SERVER_POST.register { sweep() }
        LifecycleEvent.SERVER_STOPPED.register { away.clear() }
    }

    // Milk and `/effect clear` tell the effect before they remove it, so the effect cannot always see the loss.
    // A dead or unloaded entity keeps its SpellHolds hold for when it comes back.
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
        SpellSounds.play(level, entity.position(), EKSounds.SPELL_VOID_VANISH)
        dropTargets(level, entity)
        level.sendParticles(
            EKParticles.voidMote(), entity.x, entity.y + entity.bbHeight * 0.5, entity.z, 10,
            entity.bbWidth * 0.5, entity.bbHeight * 0.5, entity.bbWidth * 0.5, 0.05,
        )
    }

    fun hold(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val state = away[entity] ?: return
        entity.deltaMovement = Vec3.ZERO
        if (entity.isUsingItem) entity.releaseUsingItem()
        // The client moves a player, so move the player back only after a drift.
        val slack = if (entity is Player) PLAYER_SLACK else 1.0e-2
        if (entity.position().distanceToSqr(state.at) > slack * slack) entity.teleportTo(state.at.x, state.at.y, state.at.z)
        if ((entity.tickCount + entity.id) % TARGET_CHECK_EVERY == 0) dropTargets(level, entity)
    }

    private fun dropTargets(level: ServerLevel, entity: LivingEntity) {
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
        SpellSounds.play(level, state.at, EKSounds.SPELL_VOID_RETURN)
    }
}
