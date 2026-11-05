package org.shipwrights.enderkinesis.effect

import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeMap

/** Mosquito-borne blood infection. Visual only for now; see `TrypoemiaRender`. */
class TrypoemiaEffect : MobEffect(MobEffectCategory.HARMFUL, WOHLON_BLUE) {

    // Vanilla sends a mob's effects to other clients only when they start to track it.
    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        val level = entity.level() as? ServerLevel ?: return
        val instance = entity.getEffect(this) ?: return
        level.chunkSource.broadcast(entity, ClientboundUpdateMobEffectPacket(entity.id, instance))
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        val level = entity.level() as? ServerLevel ?: return
        level.chunkSource.broadcast(entity, ClientboundRemoveMobEffectPacket(entity.id, this))
    }

    private companion object {
        const val WOHLON_BLUE = 0x8CC8F0
    }
}
