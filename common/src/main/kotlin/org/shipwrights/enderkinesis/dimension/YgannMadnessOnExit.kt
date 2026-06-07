package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.events.common.PlayerEvent
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.registry.EKEffects

/**
 * Applies the [EKEffects.YGANN_MADNESS] effect when a player leaves [YgannAbyss].
 *
 *  - Duration: 7 minutes (8400 ticks) per application.
 *  - Level: starts at 1 if the player has no active madness; otherwise bumps the existing
 *    level by 1, capped at 5 (vanilla amplifier 4).
 *
 * Vanilla effect-merge rules do the right thing once we pass `amplifier = old+1`: the strictly
 * higher amplifier replaces the existing instance with the fresh 7-minute duration. At the cap
 * we re-apply at the same amplifier, which refreshes the duration (the just-set 8400 is always
 * greater than the worn-down remainder).
 */
object YgannMadnessOnExit {

    private const val DURATION_TICKS: Int = 7 * 60 * 20
    private const val MAX_AMPLIFIER: Int = 4

    fun init() {
        PlayerEvent.CHANGE_DIMENSION.register(::onChangeDimension)
    }

    private fun onChangeDimension(
        player: ServerPlayer,
        oldLevel: ResourceKey<Level>,
        newLevel: ResourceKey<Level>,
    ) {
        if (oldLevel != YgannAbyss.LEVEL_KEY) return
        if (newLevel == YgannAbyss.LEVEL_KEY) return

        val effect = EKEffects.YGANN_MADNESS.get()
        val existing = player.getEffect(effect)
        val newAmplifier = ((existing?.amplifier ?: -1) + 1).coerceAtMost(MAX_AMPLIFIER)
        // Like Sselith Madness: no particles, no HUD icon (visible/showIcon false),
        // though it still shows in the inventory effects panel.
        player.addEffect(
            MobEffectInstance(
                effect,
                DURATION_TICKS,
                newAmplifier,
                /* ambient = */ false,
                /* visible = */ false,
                /* showIcon = */ false,
            ),
        )
    }
}
