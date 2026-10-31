package org.shipwrights.enderkinesis.spell

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.registry.EKEffects

object SpellDeaths {
    private class Death(val element: Element, val start: Long)

    // Each side keeps its own map: the server from the death, the client from the packet.
    private val server = HashMap<Int, Death>()
    private val client = HashMap<Int, Death>()

    fun init() {
        EntityEvent.LIVING_DEATH.register { entity, source ->
            onDeath(entity, source)
            EventResult.pass()
        }
    }

    private fun onDeath(entity: LivingEntity, source: DamageSource) {
        val level = entity.level() as? ServerLevel ?: return
        // A fall under Weighted counts as a Gravity death.
        val element = elementOf(source)
            ?: Element.GRAVITY.takeIf { source.`is`(DamageTypeTags.IS_FALL) && entity.hasEffect(EKEffects.WEIGHTED.get()) }
            ?: return
        server.entries.removeIf { level.gameTime - it.value.start > FORGET_TICKS }
        server[entity.id] = Death(element, level.gameTime)
        SpellVfx.death(level, entity, element)
    }

    fun elementOf(source: DamageSource): Element? = when {
        source.`is`(EKDamageSources.HEAT_KEY) -> Element.HEAT
        source.`is`(EKDamageSources.GROWTH_KEY) -> Element.GROWTH
        source.`is`(EKDamageSources.CRYSTALLIZATION_KEY) -> Element.PURITY
        source.`is`(EKDamageSources.GRAVITY_KEY) -> Element.GRAVITY
        source.`is`(EKDamageSources.CHAOS_KEY) -> Element.CHAOS
        source.`is`(EKDamageSources.BLOOD_DRAIN_KEY) || source.`is`(EKDamageSources.BLEEDING_KEY) -> Element.BLOOD
        source.`is`(EKDamageSources.SUNFIRE_KEY) -> Element.SUNFIRE
        source.`is`(EKDamageSources.ROT_KEY) -> Element.POISON
        source.`is`(EKDamageSources.VOID_KEY) -> Element.VOID
        else -> null
    }

    fun setClient(entityId: Int, element: Element, tick: Long) {
        client.entries.removeIf { tick - it.value.start > FORGET_TICKS }
        client[entityId] = Death(element, tick)
    }

    fun resetClient() = client.clear()

    @JvmStatic
    fun of(entity: LivingEntity): Element? = (if (entity.level().isClientSide) client else server)[entity.id]?.element

    @JvmStatic
    fun deathTicks(entity: LivingEntity, vanilla: Int): Int = of(entity)?.let(::bodyTicks) ?: vanilla

    @JvmStatic
    fun keepsPoof(entity: LivingEntity): Boolean = of(entity).let { it == null || it == Element.BLOOD }

    @JvmStatic
    fun tipsOver(entity: LivingEntity): Boolean = of(entity).let { it == null || it == Element.BLOOD || it == Element.POISON }

    /** Ticks. Each value is the length of its client death animation. */
    fun bodyTicks(element: Element): Int = when (element) {
        Element.HEAT -> 50
        Element.GROWTH -> 54
        Element.PURITY -> 19
        Element.GRAVITY -> 60
        Element.CHAOS -> 7
        Element.BLOOD -> 47
        Element.SUNFIRE -> 5
        Element.POISON -> 45
        Element.VOID -> 56
        else -> 20
    }

    private const val FORGET_TICKS = 400L
}
