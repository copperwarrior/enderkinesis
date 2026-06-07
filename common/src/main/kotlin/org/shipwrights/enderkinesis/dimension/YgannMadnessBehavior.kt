package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.registry.EKEffects

/**
 * Active behaviours of the [EKEffects.YGANN_MADNESS] status effect, gated by amplifier.
 *
 * | Level (amp)   | Behaviour                                                              |
 * |---------------|------------------------------------------------------------------------|
 * | 2+ (amp ≥ 1)  | per-second chorus-fruit-style random teleport, 0.1% → 2.0% by level    |
 * | 3+ (amp ≥ 2)  | [WATER_DAMAGE] HP / s while in water                                   |
 * | 4+ (amp ≥ 3)  | nearby endermen become immediately hostile (no stare required)         |
 * | 5  (amp = 4)  | [PASSIVE_DAMAGE] HP / s passive, + spawns an enderman on death         |
 *
 * Rolls happen on a 20-tick cadence (once per second per player). The teleport chance is
 * interpreted as **per second** — at 2 % per *tick* the L5 player would teleport every
 * ~2 s on average, which is unplayable; per-second gives one teleport per ~50 s, reading
 * as occasional reality-flickers.
 *
 * Application of the effect itself is handled by [YgannMadnessOnExit] on dimension exit.
 */
object YgannMadnessBehavior {

    /** L2 (amp 1) per-second teleport chance — 0.1 %. */
    private const val TELEPORT_CHANCE_MIN = 0.001
    /** L5 (amp 4) per-second teleport chance — 2.0 %. */
    private const val TELEPORT_CHANCE_MAX = 0.020
    /** ±N blocks horizontal/vertical search radius, matching [ChorusFruitItem]. */
    private const val TELEPORT_RANGE = 8.0
    /** Attempts to find a safe landing per teleport roll, matching [ChorusFruitItem]. */
    private const val TELEPORT_ATTEMPTS = 16

    /** HP per second of water-contact damage at L3+. */
    private const val WATER_DAMAGE = 1.0f
    /** HP per second of unconditional passive damage at L5. */
    private const val PASSIVE_DAMAGE = 1.0f
    /** Radius (blocks) scanned each second for endermen to force-aggro at L4+. */
    private const val ENDERMAN_AGGRO_RANGE = 32.0

    fun init() {
        TickEvent.PLAYER_POST.register(::tickPlayer)
        EntityEvent.LIVING_DEATH.register(::onLivingDeath)
    }

    private fun tickPlayer(player: Player) {
        if (player !is ServerPlayer) return
        if (player.isSpectator) return
        // 1 Hz cadence. tickCount is monotonic from spawn; modular sampling is cheaper than
        // an explicit per-player counter and produces a uniform 20-tick spacing.
        if (player.tickCount % 20 != 0) return

        val effect = player.getEffect(EKEffects.YGANN_MADNESS.get()) ?: return
        val amp = effect.amplifier
        val level = player.serverLevel()

        if (amp >= 1 && player.random.nextDouble() < chanceForAmp(amp)) {
            tryRandomTeleport(player, level)
        }
        if (amp >= 2 && player.isInWater) {
            player.hurt(EKDamageSources.brink(player), WATER_DAMAGE)
        }
        if (amp >= 3) {
            aggroEndermen(player, level)
        }
        if (amp >= 4) {
            player.hurt(EKDamageSources.brink(player), PASSIVE_DAMAGE)
        }
    }

    /** Linear interp amp 1 → amp 4 over [[TELEPORT_CHANCE_MIN], [TELEPORT_CHANCE_MAX]].
     *  Midpoints: amp 2 ≈ 0.73 %, amp 3 ≈ 1.37 %. */
    private fun chanceForAmp(amp: Int): Double {
        val capped = amp.coerceIn(1, 4)
        return TELEPORT_CHANCE_MIN +
            (capped - 1) * (TELEPORT_CHANCE_MAX - TELEPORT_CHANCE_MIN) / 3.0
    }

    /** Direct port of `ChorusFruitItem.finishUsingItem`'s teleport loop: 16 tries at ±8 in
     *  each axis, accept the first one that lands on something solid. */
    private fun tryRandomTeleport(player: ServerPlayer, level: ServerLevel) {
        val startX = player.x
        val startY = player.y
        val startZ = player.z
        for (i in 0 until TELEPORT_ATTEMPTS) {
            val x = startX + (player.random.nextDouble() - 0.5) * (TELEPORT_RANGE * 2.0)
            val y = Mth.clamp(
                startY + (player.random.nextInt(16) - 8).toDouble(),
                level.minBuildHeight.toDouble(),
                (level.minBuildHeight + level.logicalHeight - 1).toDouble(),
            )
            val z = startZ + (player.random.nextDouble() - 0.5) * (TELEPORT_RANGE * 2.0)
            if (player.randomTeleport(x, y, z, true)) {
                // Sound at origin (nearby observers hear the departure) and at the player
                // (they hear arrival even with their head in a wall).
                level.playSound(
                    null, startX, startY, startZ,
                    SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.0f,
                )
                player.playSound(SoundEvents.CHORUS_FRUIT_TELEPORT, 1.0f, 1.0f)
                player.resetFallDistance()
                break
            }
        }
    }

    /** Forces nearby endermen onto the player as their target, bypassing the look-at-me
     *  trigger. Re-applied each second so endermen that drop the target via line-of-sight
     *  rules re-acquire it next pass. */
    private fun aggroEndermen(player: ServerPlayer, level: ServerLevel) {
        val bb = player.boundingBox.inflate(ENDERMAN_AGGRO_RANGE)
        for (enderman in level.getEntitiesOfClass(EnderMan::class.java, bb)) {
            if (enderman.target !== player) {
                enderman.target = player
            }
        }
    }

    /** L5 (amp 4) death penalty: spawn an enderman at the death position. Uses default
     *  construction (no `finalizeSpawn`/persistence) so it can despawn naturally if no
     *  players are around — clutter from repeated deaths isn't the goal, the immediate
     *  threat at respawn-time is. */
    private fun onLivingDeath(entity: LivingEntity, source: DamageSource): EventResult {
        if (entity !is ServerPlayer) return EventResult.pass()
        val effect = entity.getEffect(EKEffects.YGANN_MADNESS.get()) ?: return EventResult.pass()
        if (effect.amplifier < 4) return EventResult.pass()
        val level = entity.serverLevel()
        val enderman = EntityType.ENDERMAN.create(level) ?: return EventResult.pass()
        enderman.moveTo(entity.x, entity.y, entity.z, entity.yRot, 0f)
        level.addFreshEntity(enderman)
        return EventResult.pass()
    }
}
