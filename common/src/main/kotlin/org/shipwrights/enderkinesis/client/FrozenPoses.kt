package org.shipwrights.enderkinesis.client

import net.minecraft.world.entity.LivingEntity
import org.shipwrights.enderkinesis.spell.SpellVisuals
import java.util.WeakHashMap

/**
 * Holds a frozen mob's pose by replaying the animation inputs of its first frozen frame, as a Ga'mylan cage
 * copy that never ticks. Any model that poses from LivingEntityRenderer's inputs freezes, modded models too.
 * A frozen tumble still turns the whole model (see `LivingEntityRendererFrozenTumbleMixin`).
 */
object FrozenPoses {
    const val ATTACK_TIME = 0
    const val BODY_ROT = 1
    const val AGE_IN_TICKS = 2
    const val PARTIAL_TICK = 3
    const val LIMB_SWING = 4
    const val LIMB_SWING_AMOUNT = 5
    const val HEAD_YAW = 6
    const val HEAD_PITCH = 7
    private const val SLOTS = 8

    // Render thread only. NaN marks an input not yet kept.
    private val kept = WeakHashMap<LivingEntity, FloatArray>()

    /** [live] while [entity] is not frozen; else the value [slot] had on the first frozen frame. */
    @JvmStatic
    fun input(entity: LivingEntity, slot: Int, live: Float): Float {
        if (!SpellVisuals.isFrozen(entity)) {
            if (kept.isNotEmpty()) kept.remove(entity)
            return live
        }
        val inputs = kept.getOrPut(entity) { FloatArray(SLOTS) { Float.NaN } }
        if (inputs[slot].isNaN()) inputs[slot] = live
        return inputs[slot]
    }

    fun reset() = kept.clear()
}
