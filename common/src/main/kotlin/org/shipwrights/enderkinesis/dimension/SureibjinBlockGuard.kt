package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import dev.architectury.event.events.common.InteractionEvent

/**
 * Block-uninteractable enforcement for [Sureibjin]. The dream coast can be
 * walked, but nothing can be broken, placed, used, or hit. The dim is
 * meant to read as untouchable scenery — the player wakes up in someone
 * else's world for the duration of the dream.
 *
 * Four events covered:
 *  - [BlockEvent.BREAK] — vanilla mining cancel.
 *  - [BlockEvent.PLACE] — places from anything that goes through the
 *    entity-place pipeline (most blocks).
 *  - [InteractionEvent.RIGHT_CLICK_BLOCK] — catches the bucket → liquid
 *    flow that bypasses BlockEvent.PLACE on Forge, plus right-click use
 *    of doors, chests, levers, beds, etc.
 *  - [InteractionEvent.LEFT_CLICK_BLOCK] — left-click on a block, e.g.
 *    starting to mine or item-specific left-click hooks.
 *
 * Player inventory is stripped on entry ([SureibjinEntry]) so most of
 * these would just no-op anyway, but the defensive cancel keeps the
 * scenery firm in case of edge cases (cheats, creative mode, mod items).
 */
object SureibjinBlockGuard {

    fun init() {
        BlockEvent.BREAK.register { level, _, _, _, _ ->
            if (level.dimension() == Sureibjin.LEVEL_KEY) EventResult.interruptFalse()
            else EventResult.pass()
        }
        BlockEvent.PLACE.register { level, _, _, _ ->
            if (level.dimension() == Sureibjin.LEVEL_KEY) EventResult.interruptFalse()
            else EventResult.pass()
        }
        InteractionEvent.RIGHT_CLICK_BLOCK.register { player, _, _, _ ->
            if (player.level().dimension() == Sureibjin.LEVEL_KEY) EventResult.interruptFalse()
            else EventResult.pass()
        }
        InteractionEvent.LEFT_CLICK_BLOCK.register { player, _, _, _ ->
            if (player.level().dimension() == Sureibjin.LEVEL_KEY) EventResult.interruptFalse()
            else EventResult.pass()
        }
    }
}
