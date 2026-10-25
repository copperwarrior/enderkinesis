package org.shipwrights.enderkinesis.spell

import dev.architectury.event.CompoundEventResult
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.PlayerEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKEffects
import java.util.IdentityHashMap
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Void's Absent: the target is out of the world. It vanishes from every client's view (see
 * [SpellNetwork]), can't be hurt, can't act, and mobs ignore it. It reappears where it
 * vanished. An Absent player sees only the Ygann sky (client).
 */
object Absence {

    private class Away(val at: Vec3, val wasInvisible: Boolean, val hadAi: Boolean, val hadGravity: Boolean)

    // Game thread only.
    private val away = IdentityHashMap<LivingEntity, Away>()

    private const val IGNORE_RANGE = 32.0

    fun init() {
        EntityEvent.LIVING_HURT.register { entity, _, _ ->
            if (isAbsent(entity)) EventResult.interruptFalse() else EventResult.pass()
        }
        InteractionEvent.LEFT_CLICK_BLOCK.register { player, _, _, _ -> if (isAbsent(player)) EventResult.interruptFalse() else EventResult.pass() }
        InteractionEvent.RIGHT_CLICK_BLOCK.register { player, _, _, _ -> if (isAbsent(player)) EventResult.interruptFalse() else EventResult.pass() }
        InteractionEvent.RIGHT_CLICK_ITEM.register { player, hand ->
            if (isAbsent(player)) CompoundEventResult.interruptFalse(player.getItemInHand(hand)) else CompoundEventResult.pass()
        }
        InteractionEvent.INTERACT_ENTITY.register { player, _, _ -> if (isAbsent(player)) EventResult.interruptFalse() else EventResult.pass() }
        PlayerEvent.ATTACK_ENTITY.register { player, _, _, _, _ -> if (isAbsent(player)) EventResult.interruptFalse() else EventResult.pass() }
    }

    fun isAbsent(entity: LivingEntity): Boolean = entity.hasEffect(EKEffects.ABSENT.get())

    fun vanish(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        if (entity in away) return
        val mob = entity as? Mob
        away[entity] = Away(entity.position(), entity.isInvisible, mob?.isNoAi?.not() ?: true, !entity.isNoGravity)
        entity.isInvisible = true
        entity.isNoGravity = true
        mob?.isNoAi = true
        SpellNetwork.broadcastAbsent(level, entity, true)
        level.sendParticles(
            EKParticles.voidMote(), entity.x, entity.y + entity.bbHeight * 0.5, entity.z, 30,
            entity.bbWidth * 0.5, entity.bbHeight * 0.5, entity.bbWidth * 0.5, 0.05,
        )
        level.playSound(null, entity.x, entity.y, entity.z, EKSounds.ABSENT_VANISH.get(), SoundSource.HOSTILE, 1f, 0.6f)
    }

    /** Each tick while Absent: held where it vanished, and no mob keeps it as a target. */
    fun hold(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val state = away[entity] ?: return
        entity.deltaMovement = Vec3.ZERO
        if (entity !is Player && entity.position().distanceToSqr(state.at) > 1.0e-4) entity.teleportTo(state.at.x, state.at.y, state.at.z)
        for (mob in level.getEntitiesOfClass(Mob::class.java, entity.boundingBox.inflate(IGNORE_RANGE)) { it.target === entity }) {
            mob.target = null
        }
    }

    fun reappear(entity: LivingEntity) {
        val state = away.remove(entity) ?: return
        val level = entity.level() as? ServerLevel ?: return
        entity.isInvisible = state.wasInvisible
        entity.isNoGravity = !state.hadGravity
        (entity as? Mob)?.isNoAi = !state.hadAi
        entity.teleportTo(state.at.x, state.at.y, state.at.z)
        SpellNetwork.broadcastAbsent(level, entity, false)
        level.playSound(null, entity.x, entity.y, entity.z, EKSounds.ABSENT_RETURN.get(), SoundSource.HOSTILE, 1f, 1f)
    }
}
