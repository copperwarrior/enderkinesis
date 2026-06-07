package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory

/**
 * Creeping madness that takes hold while a player lingers in Sselith's Repertory.
 * Applied and levelled by [org.shipwrights.enderkinesis.dimension.SselithMadness]:
 * it lands at level 1 on entry, climbs to level 5 the longer you stay, and decays
 * slowly once you leave (so it follows you home for a while).
 *
 * The effect carries **no particles and no HUD icon** (applied with
 * `visible = false, showIcon = false`), so it stays out of the player's face — but
 * it does show in the inventory effects panel (which renders active effects
 * regardless of `showIcon`), so a player who checks can see the madness and its
 * level. The player otherwise knows it only by its symptoms.
 *
 * Like [YgannMadnessEffect], the effect itself carries no per-tick behaviour —
 * symptoms are gated by amplifier in dedicated handlers (Phase 2+). See
 * [org.shipwrights.enderkinesis.dimension.SselithMadness] for the level mechanics.
 */
class SselithMadnessEffect :
    MobEffect(MobEffectCategory.HARMFUL, 0x6E5128) {

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = false
}
