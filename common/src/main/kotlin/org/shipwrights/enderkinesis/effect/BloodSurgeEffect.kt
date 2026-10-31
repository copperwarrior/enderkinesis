package org.shipwrights.enderkinesis.effect

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeMap
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import org.shipwrights.enderkinesis.spell.Element
import java.util.UUID
import java.util.WeakHashMap

/**
 * Blood's gift to whoever its drain feeds: extra hearts, 4 health a level as Health Boost gives,
 * which the Regeneration it comes with fills. Unlike Health Boost's, they don't vanish the moment
 * it ends: they fade away over [BloodSurgeFades.FADE_TICKS], health following the shrinking
 * maximum down (and on the client that drop isn't played as being hurt; see
 * `client/BloodSurgeFadeClient`). Another drain picks up from wherever a fade has got to, and
 * refreshing it never takes the hearts away in between, so they don't flicker.
 */
class BloodSurgeEffect : MobEffect(MobEffectCategory.BENEFICIAL, Element.BLOOD.color) {

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        BloodSurgeFades.stop(entity)
        val max = attributes.getInstance(Attributes.MAX_HEALTH) ?: return
        max.removeModifier(MODIFIER)
        max.addPermanentModifier(AttributeModifier(MODIFIER, "Blood Surge", HEALTH_PER_LEVEL * (amplifier + 1), AttributeModifier.Operation.ADDITION))
    }

    /** Takes the hearts away gently: they start to fade rather than going at once, and health
     *  isn't cut to the plain maximum here as Health Boost's is. */
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

/**
 * Blood Surge's hearts fading after it ends: a modifier on maximum health, shrinking to nothing
 * over [FADE_TICKS], with health brought down to the maximum as it goes. The modifier isn't
 * saved, so a fade cut short by leaving the world just ends there.
 */
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
