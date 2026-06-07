package org.shipwrights.enderkinesis.registry

import net.minecraft.world.level.GameRules

/**
 * Custom gamerules registered by the mod.
 *
 * **Static-init timing**: vanilla `GameRules.register` mutates a
 * static registry on first call. We trigger that mutation from
 * [init] inside the mod's main init hook so the rule is in place
 * before any save loads.
 */
object EKGameRules {

    /** **`wohlonSpreadSpeed`** — integer multiplier applied to
     *  every Wohlonnogondonia spread/convert cadence in
     *  [org.shipwrights.enderkinesis.block.WohlonnogondoniaSpreader].
     *
     *  Default `1` = unchanged behaviour. Higher values speed up
     *  every element together so the dimension's encroachment
     *  feels less leisurely on a test world (or, set high enough,
     *  becomes a runaway invasion for the lulz).
     *
     *  Concretely, with `wohlonSpreadSpeed = N`:
     *  - Cell-spread interval scales `400 / N` ticks (default
     *    3/min → at N=10 it's 30/min).
     *  - Hourly tainted-chunk force-load scales `1000 / N` ticks
     *    (default 1/in-game-hour → N=10 means 10/in-game-hour).
     *  - Portal-creation seed (270 cells, 15 s by default) scales
     *    `300 / N` ticks total (N=10 ⇒ 1.5 s sphere).
     *  - Per-section random-tick count scales `randomTickSpeed * N`
     *    (default 3 → N=10 means 30 ticks/section/tick).
     *  - Boundary-block rarity divisor scales `100 / N`
     *    (default 1-in-100 → N=10 means 1-in-10 per random tick).
     *
     *  Effective range clamped to `[1, 100]` inside the spreader.
     *  Values outside that range are silently snapped — vanilla
     *  `IntegerValue.create` has no min/max overload in 1.20.1. */
    @JvmField
    val WOHLON_SPREAD_SPEED: GameRules.Key<GameRules.IntegerValue> =
        GameRules.register(
            "wohlonSpreadSpeed",
            GameRules.Category.UPDATES,
            GameRules.IntegerValue.create(1),
        )

    /** Force the class to load so its `val` initialisers run. */
    fun init() = Unit
}
