package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack

/** Base for the madness effects: milk does not cure them. */
abstract class MadnessEffect(category: MobEffectCategory, color: Int) : MobEffect(category, color) {

    /** Forge reads `IForgeMobEffect.getCurativeItems` (default: milk). This is not an override
     *  in the common module, but the same signature replaces that default at run time. */
    @Suppress("unused")
    fun getCurativeItems(): List<ItemStack> = emptyList()

    companion object {
        /** Vanilla milk calls `removeAllEffects`; the Fabric milk mixin calls this instead. */
        @JvmStatic
        fun removeAllExceptMadness(entity: LivingEntity): Boolean {
            val curable = entity.activeEffects.map { it.effect }.filter { it !is MadnessEffect }
            var removed = false
            for (effect in curable) removed = entity.removeEffect(effect) || removed
            return removed
        }
    }
}
