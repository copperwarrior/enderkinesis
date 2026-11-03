package org.shipwrights.enderkinesis.effect

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeMap
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellSounds
import java.util.UUID
import java.util.WeakHashMap

/** The client must not show the fade health drop as damage; see `client/BloodSurgeFadeClient`. */
class BloodSurgeEffect : MobEffect(MobEffectCategory.BENEFICIAL, Element.BLOOD.color) {

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        BloodSurgeFades.stop(entity)
        val max = attributes.getInstance(Attributes.MAX_HEALTH) ?: return
        max.removeModifier(MODIFIER)
        max.addPermanentModifier(AttributeModifier(MODIFIER, "Blood Surge", HEALTH_PER_LEVEL * (amplifier + 1), AttributeModifier.Operation.ADDITION))
    }

    /** Unlike Health Boost, do not cut health to the plain maximum here: [BloodSurgeFades] lowers it gradually. */
    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        val max = attributes.getInstance(Attributes.MAX_HEALTH) ?: return
        val modifier = max.getModifier(MODIFIER) ?: return
        max.removeModifier(MODIFIER)
        if (!entity.level().isClientSide) BloodSurgeFades.start(entity, modifier.amount)
    }

    private companion object {
        val MODIFIER: UUID = UUID.fromString("b1d0a5e3-6c2f-4e7a-9a41-3f8d2c7e5b10")
        const val HEALTH_PER_LEVEL = 4.0
    }
}

/** The fade modifier is transient, so a fade that a world exit interrupts stops there. */
object BloodSurgeFades {
    private class Fade(val from: Double, var age: Int = 0)

    // Server thread only.
    private val fading = WeakHashMap<LivingEntity, Fade>()

    const val FADE_TICKS = 80

    private val MODIFIER: UUID = UUID.fromString("4e8c2a91-0d7b-4f36-8b25-c6a19e3f7d42")

    fun init() {
        TickEvent.SERVER_POST.register { tick() }
        LifecycleEvent.SERVER_STOPPED.register { fading.clear() }
    }

    fun start(entity: LivingEntity, amount: Double) {
        fading[entity] = Fade(amount)
        hold(entity, amount)
        (entity.level() as? ServerLevel)?.let { SpellSounds.play(it, entity.position(), EKSounds.SPELL_BLOOD_SURGE_FADE) }
    }

    fun stop(entity: LivingEntity) {
        if (fading.remove(entity) != null) entity.getAttribute(Attributes.MAX_HEALTH)?.removeModifier(MODIFIER)
    }

    private fun tick() {
        if (fading.isEmpty()) return
        val it = fading.entries.iterator()
        while (it.hasNext()) {
            val (entity, fade) = it.next()
            if (entity.isRemoved || !entity.isAlive) {
                entity.getAttribute(Attributes.MAX_HEALTH)?.removeModifier(MODIFIER)
                it.remove()
                continue
            }
            fade.age++
            val left = fade.from * (1.0 - fade.age.toDouble() / FADE_TICKS)
            if (left > 0.0) hold(entity, left)
            else {
                entity.getAttribute(Attributes.MAX_HEALTH)?.removeModifier(MODIFIER)
                it.remove()
            }
            if (entity.health > entity.maxHealth) entity.health = entity.maxHealth
        }
    }

    private fun hold(entity: LivingEntity, amount: Double) {
        val max = entity.getAttribute(Attributes.MAX_HEALTH) ?: return
        max.removeModifier(MODIFIER)
        max.addTransientModifier(AttributeModifier(MODIFIER, "Blood Surge fading", amount, AttributeModifier.Operation.ADDITION))
    }
}
