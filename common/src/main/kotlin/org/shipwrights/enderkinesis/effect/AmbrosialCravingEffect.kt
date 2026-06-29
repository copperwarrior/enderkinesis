package org.shipwrights.enderkinesis.effect

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory

/**
 * Ambrosial Craving — applied by the
 * [org.shipwrights.enderkinesis.item.WeyyeFruitItem] and **lasts until
 * death** (the item adds it at [Int.MAX_VALUE] duration; on death the
 * fresh respawn ServerPlayer drops the active-effects map).
 *
 * The effect itself does nothing per-tick — it's a flag. Its visible
 * symptoms are:
 *
 *  - **Food effectiveness × 0.2** — handled by the `FoodData.eat` /
 *    `LivingEntity.addEatEffect` mixins that consult
 *    [org.shipwrights.enderkinesis.item.AmbrosialCravingScaling]'s
 *    thread-local flag while a Player.eat call is in flight.
 *  - **Forced craving** — handled client-side by
 *    [org.shipwrights.enderkinesis.client.AmbrosialCravingClient],
 *    which periodically scrolls the hotbar to Wey'ye fruit (if one is in
 *    the bar) and force-uses it when it's already selected.
 *
 * Visual: a warm honey-amber HUD icon, no particles. Carries
 * `showIcon = true, visible = false` everywhere it's applied.
 */
class AmbrosialCravingEffect :
    MobEffect(MobEffectCategory.HARMFUL, 0xB8860B) {

    /** No per-tick callback — every symptom is gated externally. */
    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = false
}
