package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.LivingEntity
import java.util.WeakHashMap

/** A refresh calls removeAttributeModifiers just before addAttributeModifiers, in the same tick. Server thread only. */
class RefreshMarks(private val effect: MobEffect) {
    private val marked = WeakHashMap<LivingEntity, Long>()

    fun removed(entity: LivingEntity) {
        if (EndingEffects.stillHas(entity, effect)) marked[entity] = entity.level().gameTime
    }

    /** A mark from an earlier tick is stale: no add followed its remove. */
    fun isNew(entity: LivingEntity): Boolean = marked.remove(entity) != entity.level().gameTime
}
