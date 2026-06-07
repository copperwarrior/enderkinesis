package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory

/**
 * Lingering after-effect of exposure to Ygann's Abyss. Applied by [YgannMadnessOnExit] every
 * time a player leaves the dimension; each application bumps the amplifier (capped at level 5
 * = amplifier 4) and refreshes the duration to 7 minutes.
 *
 * The effect itself carries no per-tick behaviour — gameplay consequences (random teleport,
 * water damage, enderman aggression, passive damage + death-spawn) live in
 * `YgannMadnessBehavior`, gated by the amplifier. See that class's doc for the level table.
 * The screen-darkening overlay lives in `YgannAbyssVoidOverlay`.
 */
class YgannMadnessEffect :
    MobEffect(MobEffectCategory.HARMFUL, 0x3B1F4F) {

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = false
}
