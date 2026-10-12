package org.shipwrights.enderkinesis.platform

import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level

/**
 * For an explosion that does not go through `Level.explode`. Forge fires its cancellable start
 * event only there, so protection mods never see a direct `Explosion.explode()`.
 */
object ExplosionHooks {

    /** Set one time by the Forge entry point, during mod construction. True = cancelled. */
    @Volatile
    var startCancelled: (Level, Explosion) -> Boolean = { _, _ -> false }
}
