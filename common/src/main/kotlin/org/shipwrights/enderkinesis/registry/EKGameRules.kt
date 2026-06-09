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
     *  Default `1` = the intended slow, creeping spread. Higher
     *  values accelerate every part of the system together — useful
     *  for testing, debugging, or running an accelerated session.
     *
     *  Concretely, with `wohlonSpreadSpeed = N`:
     *  - **Frontier sampler** enqueues `2 × N` candidate cells per
     *    server tick (default `2`).
     *  - **Spread queue drain** flips `5 × N` cells per second
     *    horizontally and `500 × N` cells per second vertically;
     *    the drain rate matches the sample rate so the queue
     *    doesn't grow unbounded at high `N`.
     *  - **Tainted-chunk force-load interval** scales `1000 / N`
     *    ticks (default 1 force-load per in-game hour → N=10 ⇒
     *    10 per hour).
     *  - **Portal-creation seed sphere** scales `300 / N` ticks
     *    total (default ≈ 15 s for the 270-cell sphere → N=10 ⇒
     *    1.5 s).
     *  - **Per-section random-tick count** scales `randomTickSpeed × N`
     *    (default 3 → N=10 ⇒ 30 rolls per Wohlon section per tick).
     *  - **Boundary-fringe block conversion** is distance-banded
     *    per *block* (not per cell). Inside a non-Wohlon cell,
     *    blocks at block-level Manhattan distance 1 from any
     *    Wohlon-cell block (tier 1) convert at `0.30 × N` per
     *    random tick; blocks at distance 2 (tier 2) convert at
     *    `0.05 × N`; blocks at distance ≥ 3 don't convert at all.
     *    The fringe is concentrated on the 1- and 2-block layers
     *    physically nearest the biome line — visually a thin rim
     *    instead of a uniformly painted adjacent cell. Both rates
     *    cap at 1.0.
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
