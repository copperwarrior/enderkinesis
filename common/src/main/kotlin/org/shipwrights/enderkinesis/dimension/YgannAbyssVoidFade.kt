package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/**
 * Void-death replacement for [YgannAbyss]: when a player drops below the dimension's void
 * killplane, vanilla `FELL_OUT_OF_WORLD` damage is suppressed and the death is instead
 * attributed to the shared `brink` damage type ("<player> succumbed to the brink") —
 * applied at the moment the player crosses below the killplane.
 *
 *  ## Position-driven, not time-driven
 *
 *  The fade visual (drawn client-side by `YgannAbyssVoidOverlay`) ramps in proportion
 *  to how far the player has descended into the 64-block void zone between
 *  `minBuildHeight` (top of void) and `minBuildHeight − 64` (killplane). A free-falling
 *  player crosses that zone in ~0.8 s; a slowly-descending one (elytra / hovering
 *  ship-fall) lingers in it for as long as the descent takes, and the fade matches.
 *  Server-side there's nothing to count — we just kill the moment Y crosses the
 *  killplane.
 *
 *  ## Notes
 *
 *  - Spectators are exempt (intangible; vanilla doesn't void-damage them either).
 *  - Creative is *not* exempt — vanilla void damage hits creative players via the
 *    `bypasses_invulnerability` tag, and `brink` carries the same tag so our kill
 *    hit lands too.
 *  - Mobs are not tracked; they still drop to vanilla void damage.
 */
object YgannAbyssVoidFade {

    fun init() {
        EntityEvent.LIVING_HURT.register(::onLivingHurt)
        TickEvent.PLAYER_POST.register(::tickPlayer)
    }

    private fun onLivingHurt(
        entity: LivingEntity, source: DamageSource, amount: Float,
    ): EventResult {
        // Cancel vanilla void damage only in YgannAbyss and only for players. Mobs and
        // other dimensions are untouched. Without this, vanilla would chip 4 HP/tick at
        // the killplane in parallel with our fade — the player'd see red flash + black
        // overlay instead of just the fade-into-portal we want.
        if (entity !is ServerPlayer) return EventResult.pass()
        if (entity.level().dimension() != YgannAbyss.LEVEL_KEY) return EventResult.pass()
        if (!source.`is`(DamageTypes.FELL_OUT_OF_WORLD)) return EventResult.pass()
        return EventResult.interruptFalse()
    }

    private fun tickPlayer(player: Player) {
        if (player !is ServerPlayer) return
        if (player.level().dimension() != YgannAbyss.LEVEL_KEY) return
        if (player.isSpectator) return
        // Trigger death the same instant vanilla's outOfWorld() would fire — `y <
        // minBuildHeight − 64`. The client overlay reaches full pattern at this same
        // y-level, so the two appear synced without any per-player state to track.
        val killplane = player.level().minBuildHeight - 64.0
        if (player.y >= killplane) return
        player.hurt(EKDamageSources.brink(player), Float.MAX_VALUE)
    }
}
