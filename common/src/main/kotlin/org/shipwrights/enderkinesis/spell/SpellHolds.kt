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

/**
 * What Frozen and Absent do to an entity's own flags while they hold it: a mob's AI is off,
 * and it has no gravity (a frozen mob rides its ice body, see [FrozenBodies]; an Absent one is
 * out of the world, see [Absence]). A held player can't act at all: no walking (the effects'
 * speed modifiers), no jumping (the mixin), and no attacking, using or interacting (here).
 * A frozen player keeps their gravity and falls as ice would.
 *
 * Both effects can hold one entity at once, so each holds it by its own tag and the entity's
 * flags come back only when the last lets go. The holds and the flags as they were before
 * are kept as the entity's own tags, so they're saved with it: an entity unloaded or a player
 * logged out while held comes back held, and is let go properly when its effect ends, or at
 * once if the effect was taken off while it was away.
 */
object SpellHolds {

    enum class By(val tag: String, val effect: () -> MobEffect) {
        FROZEN("enderkinesis.held_frozen", { EKEffects.FROZEN.get() }),
        ABSENT("enderkinesis.held_absent", { EKEffects.ABSENT.get() }),
    }

    /** Set on the first hold when the entity had AI or gravity of its own. */
    private const val HAD_AI = "enderkinesis.had_ai"
    private const val HAD_GRAVITY = "enderkinesis.had_gravity"

    // Game thread only: entities just read from a save, to bring up to date next tick.
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

    /** Whether a Frozen or Absent effect has [entity] (both sides: the client knows its own
     *  player's effects). */
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

    /** The entity's flags for whatever still holds it, or as they were once nothing does. */
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
        // A frozen body's ice carries it, gravity and all.
        entity.isNoGravity = absent || frozen || HAD_GRAVITY !in entity.tags
    }

    /** [entity] was just read from a save (a chunk loading, a player logging in, a mob
     *  changing dimension): it's brought up to date next tick, once it's in its level. */
    @JvmStatic
    fun loaded(entity: LivingEntity) {
        if (entity.level().isClientSide) return
        if (By.entries.any { it.tag in entity.tags || entity.hasEffect(it.effect()) }) loaded += entity
    }

    /** Lets go any hold whose effect is gone, and takes up again any effect whose hold was lost
     *  with the entity's last session (the ice body, where it vanished). */
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
