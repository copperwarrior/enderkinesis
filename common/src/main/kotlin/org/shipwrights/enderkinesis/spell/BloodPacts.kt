package org.shipwrights.enderkinesis.spell

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKSounds
import java.util.UUID

/** Not saved, because the effects behind the bonds are short. A bind never refreshes while it
 *  is active, so pulses cannot keep it going. */
object BloodPacts {
    private val thralls = HashMap<UUID, ArrayDeque<UUID>>()
    private val masters = HashMap<UUID, UUID>()
    private val bound = HashMap<UUID, MutableSet<UUID>>()
    private val binders = HashMap<UUID, UUID>()
    private var redirecting = false

    const val REDIRECTED = 0.5f

    fun init() {
        LifecycleEvent.SERVER_STOPPED.register {
            thralls.clear(); masters.clear(); bound.clear(); binders.clear()
        }
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
        SpellSounds.play(level, mob.position(), EKSounds.SPELL_BLOOD_ENTHRALL, SpellSounds.magnitudeVolume(magnitude))
    }

    fun isThrallOf(entity: LivingEntity?, master: UUID): Boolean = entity != null && masters[entity.uuid] == master

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

    fun bind(target: LivingEntity, binder: LivingEntity, ticks: Int) {
        val effect = EKEffects.BLOODBOUND.get()
        if (target.hasEffect(effect)) return
        target.addEffect(MobEffectInstance(effect, ticks), binder)
        (target.level() as? ServerLevel)?.let { SpellSounds.play(it, target.position(), EKSounds.SPELL_BLOOD_PACT) }
        bound.getOrPut(binder.uuid) { HashSet() } += target.uuid
        binders[target.uuid] = binder.uuid
    }

    fun unbind(entity: LivingEntity) {
        val binder = binders.remove(entity.uuid) ?: return
        bound[binder]?.remove(entity.uuid)
    }

    // Subtract only the damage the targets took, so targets that cannot be hurt spare the binder nothing.
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
