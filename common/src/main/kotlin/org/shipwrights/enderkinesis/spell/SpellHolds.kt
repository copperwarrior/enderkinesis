package org.shipwrights.enderkinesis.spell

import dev.architectury.event.CompoundEventResult
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import org.shipwrights.enderkinesis.registry.EKEffects

/** Frozen and Absent can hold one entity at once, so the flags come back only when the last hold ends.
 *  The holds are entity tags, so they save with the entity and survive unload or logout. */
object SpellHolds {

    enum class By(val tag: String, val effect: () -> MobEffect) {
        FROZEN("enderkinesis.held_frozen", { EKEffects.FROZEN.get() }),
        ABSENT("enderkinesis.held_absent", { EKEffects.ABSENT.get() }),
    }

    /** Set on the first hold. */
    private const val HAD_AI = "enderkinesis.had_ai"
    private const val HAD_GRAVITY = "enderkinesis.had_gravity"

    // Game thread only.
    private val loaded = ArrayList<LivingEntity>()

    fun init() {
        InteractionEvent.LEFT_CLICK_BLOCK.register { player, _, _, _ -> if (isHeld(player)) EventResult.interruptFalse() else EventResult.pass() }
        InteractionEvent.RIGHT_CLICK_BLOCK.register { player, _, _, _ -> if (isHeld(player)) EventResult.interruptFalse() else EventResult.pass() }
        InteractionEvent.RIGHT_CLICK_ITEM.register { player, hand ->
            if (isHeld(player)) CompoundEventResult.interruptFalse(player.getItemInHand(hand)) else CompoundEventResult.pass()
        }
        InteractionEvent.INTERACT_ENTITY.register { player, _, _ -> if (isHeld(player)) EventResult.interruptFalse() else EventResult.pass() }
        PlayerEvent.ATTACK_ENTITY.register { player, _, _, _, _ -> if (isHeld(player)) EventResult.interruptFalse() else EventResult.pass() }
        PlayerEvent.PLAYER_RESPAWN.register { player, _ -> repair(player) }
        TickEvent.SERVER_POST.register {
            if (loaded.isEmpty()) return@register
            val batch = loaded.toList()
            loaded.clear()
            for (entity in batch) if (!entity.isRemoved && entity.level() is ServerLevel) repair(entity)
        }
        LifecycleEvent.SERVER_STOPPED.register { loaded.clear() }
    }

    /** Works on both sides, because the client knows the effects of its own player. */
    @JvmStatic
    fun isHeld(entity: LivingEntity): Boolean =
        entity.hasEffect(EKEffects.FROZEN.get()) || entity.hasEffect(EKEffects.ABSENT.get())

    fun hold(entity: LivingEntity, by: By) {
        if (entity.level().isClientSide || by.tag in entity.tags) return
        if (By.entries.none { it.tag in entity.tags }) {
            if ((entity as? Mob)?.isNoAi == false) entity.addTag(HAD_AI)
            if (!entity.isNoGravity) entity.addTag(HAD_GRAVITY)
        }
        entity.addTag(by.tag)
        apply(entity)
    }

    fun release(entity: LivingEntity, by: By) {
        if (entity.level().isClientSide || !entity.removeTag(by.tag)) return
        apply(entity)
    }

    private fun apply(entity: LivingEntity) {
        val frozen = By.FROZEN.tag in entity.tags
        val absent = By.ABSENT.tag in entity.tags
        val mob = entity as? Mob
        if (!frozen && !absent) {
            mob?.isNoAi = HAD_AI !in entity.tags
            entity.isNoGravity = HAD_GRAVITY !in entity.tags
            entity.removeTag(HAD_AI)
            entity.removeTag(HAD_GRAVITY)
            return
        }
        mob?.isNoAi = true
        // The ice body of a frozen entity has the gravity, so the entity has none.
        entity.isNoGravity = true
    }

    /** Repair on the next tick, when the entity is in its level. */
    @JvmStatic
    fun loaded(entity: LivingEntity) {
        if (entity.level().isClientSide) return
        if (By.entries.any { it.tag in entity.tags || entity.hasEffect(it.effect()) }) loaded += entity
    }

    /** Also restores what the last session lost, such as an ice body that vanished. */
    private fun repair(entity: LivingEntity) {
        for (by in By.entries) {
            val has = entity.hasEffect(by.effect())
            if (!has && by.tag in entity.tags) release(entity, by)
            if (has) when (by) {
                By.FROZEN -> FrozenBodies.freeze(entity)
                By.ABSENT -> Absence.vanish(entity)
            }
        }
        if (By.entries.none { it.tag in entity.tags } && (HAD_AI in entity.tags || HAD_GRAVITY in entity.tags)) apply(entity)
    }
}
