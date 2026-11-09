package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.LivingEntity

/** Half a heart each second. The pitch and the screen warble are client side, in `DrakeSicknessClient`. */
class DrakeSicknessEffect : MobEffect(MobEffectCategory.HARMFUL, 0xE07AB0) {

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = duration % DAMAGE_EVERY_TICKS == 0

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        if (!entity.level().isClientSide) entity.hurt(entity.damageSources().magic(), DAMAGE)
    }

    companion object {
        private const val DAMAGE_EVERY_TICKS = 20
        private const val DAMAGE = 1f
    }
}
