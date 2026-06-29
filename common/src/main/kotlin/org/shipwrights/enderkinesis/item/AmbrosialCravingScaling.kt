package org.shipwrights.enderkinesis.item

/**
 * Thread-local "we're scaling food values right now" channel. Set on the
 * way into [net.minecraft.world.entity.player.Player.eat] when the player
 * has the [org.shipwrights.enderkinesis.effect.AmbrosialCravingEffect]
 * (and the food being eaten isn't itself a Wey'ye fruit — that one is
 * still allowed to fully refill the bar), cleared on the way out.
 *
 * While the flag is set, two mixins consult it to apply the 0.2×
 * scaling — `FoodDataAmbrosialCravingScaleMixin` on the
 * `(nutrition, saturation)` pair handed to `FoodData.eat`, and
 * `LivingEntityAddEatEffectAmbrosialCravingScaleMixin` on the
 * `MobEffectInstance` passed to `addEffect` for food-borne potion
 * effects.
 *
 * Thread-local scoping is the same shape as
 * [DisintegrationLootingOverride]: the loot rolls / food eat happen on
 * the same server thread synchronously inside the wrapped call, so the
 * flag is in scope for exactly the right operations and never bleeds
 * into unrelated work.
 */
object AmbrosialCravingScaling {

    /** All food effects are reduced to this fraction of their normal
     *  value while [active] is set. The spec says "0.2× effectiveness". */
    const val SCALE: Float = 0.2f

    private val flag = ThreadLocal<Boolean>()

    @JvmStatic
    fun enter() { flag.set(true) }

    @JvmStatic
    fun exit() { flag.remove() }

    @JvmStatic
    fun active(): Boolean = flag.get() == true
}
