package org.shipwrights.enderkinesis.spell

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import org.shipwrights.enderkinesis.registry.EKEffects
import java.util.UUID

/**
 * Blood's lasting bonds, server-side and not saved (the effects behind them are short):
 *
 * - **Enthralled:** a thrall fights for its master, never against them or their other
 *   thralls. A master keeps at most 2 + magnitude thralls; a new one frees the oldest.
 * - **Bloodbound:** half the damage a binder takes passes to their bound targets instead,
 *   split evenly. Binding never refreshes while it's active, so pulses can't keep it going.
 */
object BloodPacts {
    private val thralls = HashMap<UUID, ArrayDeque<UUID>>()
    private val masters = HashMap<UUID, UUID>()
    private val bound = HashMap<UUID, MutableSet<UUID>>()
    private val binders = HashMap<UUID, UUID>()
    private var redirecting = false

    /** Share of a binder's incoming damage passed to their bound targets. */
    const val REDIRECTED = 0.5f

    fun init() {
        LifecycleEvent.SERVER_STOPPED.register {
            thralls.clear(); masters.clear(); bound.clear(); binders.clear()
        }
        // The dead hold no bonds: a dead thrall frees its place, a dead binder its bound.
        EntityEvent.LIVING_DEATH.register { entity, _ ->
            if (!entity.level().isClientSide) {
                release(entity)
                unbind(entity)
                bound.remove(entity.uuid)?.forEach { binders.remove(it) }
            }
            EventResult.pass()
        }
    }

    fun enthrall(level: ServerLevel, mob: Mob, master: LivingEntity, magnitude: Float, ticks: Int) {
        if (masters[mob.uuid] == master.uuid) return
        val owned = thralls.getOrPut(master.uuid) { ArrayDeque() }
        // Thralls that are gone (dead, unloaded or freed) no longer count.
        val enthralled = EKEffects.ENTHRALLED.get()
        owned.removeAll { id ->
            val thrall = level.getEntity(id) as? LivingEntity
            (thrall == null || !thrall.isAlive || !thrall.hasEffect(enthralled)).also { if (it) masters.remove(id) }
        }
        val cap = 2 + magnitude.toInt()
        while (owned.size >= cap) {
            val oldest = owned.removeFirst()
            (level.getEntity(oldest) as? LivingEntity)?.removeEffect(EKEffects.ENTHRALLED.get())
            masters.remove(oldest)
        }
        owned.addLast(mob.uuid)
        masters[mob.uuid] = master.uuid
        if (mob.target === master) mob.target = null
        mob.addEffect(MobEffectInstance(EKEffects.ENTHRALLED.get(), ticks), master)
    }

    fun isThrallOf(entity: LivingEntity?, master: UUID): Boolean = entity != null && masters[entity.uuid] == master

    /** Turns a thrall away from its master and their thralls, and onto whoever the master
     *  last fought. */
    fun steerThrall(mob: Mob) {
        val level = mob.level() as? ServerLevel ?: return
        val masterId = masters[mob.uuid] ?: return
        val master = level.getEntity(masterId) as? LivingEntity
        val target = mob.target
        if (target != null && (target.uuid == masterId || isThrallOf(target, masterId))) mob.target = null
        master ?: return
        val enemy = listOfNotNull(master.lastHurtByMob, master.lastHurtMob).firstOrNull {
            it.isAlive && it !== mob && !isThrallOf(it, masterId)
        }
        if (enemy != null) mob.target = enemy
    }

    fun release(entity: LivingEntity) {
        val master = masters.remove(entity.uuid) ?: return
        thralls[master]?.remove(entity.uuid)
    }

    /** Binds [target] to [binder] for [ticks], unless it's already bound. */
    fun bind(target: LivingEntity, binder: LivingEntity, ticks: Int) {
        val effect = EKEffects.BLOODBOUND.get()
        if (target.hasEffect(effect)) return
        target.addEffect(MobEffectInstance(effect, ticks), binder)
        bound.getOrPut(binder.uuid) { HashSet() } += target.uuid
        binders[target.uuid] = binder.uuid
    }

    fun unbind(entity: LivingEntity) {
        val binder = binders.remove(entity.uuid) ?: return
        bound[binder]?.remove(entity.uuid)
    }

    /** The part of [amount] that [self] still takes after its bound targets share it: only
     *  what they actually took comes off, so bound targets that can't be hurt (Absent, just
     *  hit, invulnerable) spare the binder nothing. Called from the damage mixins. */
    @JvmStatic
    fun redirect(self: LivingEntity, amount: Float): Float {
        if (redirecting || amount <= 0f) return amount
        val level = self.level() as? ServerLevel ?: return amount
        val ids = bound[self.uuid] ?: return amount
        val effect = EKEffects.BLOODBOUND.get()
        val targets = ids.mapNotNull { level.getEntity(it) as? LivingEntity }.filter { it.isAlive && it.hasEffect(effect) }
        if (targets.isEmpty()) return amount
        val shared = amount * REDIRECTED
        var landed = 0f
        redirecting = true
        try {
            for (target in targets) {
                val before = target.health + target.absorptionAmount
                target.hurt(level.damageSources().indirectMagic(self, self), shared / targets.size)
                landed += (before - (target.health + target.absorptionAmount)).coerceIn(0f, shared / targets.size)
            }
        } finally {
            redirecting = false
        }
        return amount - landed
    }
}
