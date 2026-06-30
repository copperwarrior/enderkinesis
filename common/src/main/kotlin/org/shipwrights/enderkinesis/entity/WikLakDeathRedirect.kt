package org.shipwrights.enderkinesis.entity

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.entity.EntityTypeTest
import java.util.UUID
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKEntities

/**
 * Intercepts lethal damage to any [ServerPlayer] who has a living
 * [WikLakHostEntity] anywhere on the server. The death is skipped, the
 * player is teleported onto one of their hosts (chosen uniformly at random
 * across all loaded dimensions), the host is consumed, and the player
 * receives a fixed-duration debuff trio (slowness / weakness / mining
 * fatigue) marking the borrowed body.
 *
 * Items and XP survive because the death never resolves — vanilla never
 * gets the chance to drop the inventory or scatter the orbs. Without a
 * matching host, the lethal hit goes through unchanged; the host is the
 * sole resurrection token.
 *
 * Non-lethal damage is always passed through so the visual / audio cause
 * of the borrowed-body shift reads as the killing blow's source rather
 * than out of nowhere.
 */
object WikLakDeathRedirect {

    /** Ticks the slowness / weakness / mining-fatigue debuffs persist on
     *  the borrowed body. 2400 = 2 minutes at 20 tps. Long enough that
     *  the player feels the cost; short enough that recovery is normal
     *  gameplay rather than a punishment timer. */
    private const val DEBUFF_TICKS: Int = 2400

    /** Amplifier 1 (= "level II") matches what users expect from named
     *  effects: visible icon, perceptible movement / hit-damage / mining-
     *  speed delta, not so severe that the player is locked into helpless
     *  flailing. */
    private const val DEBUFF_AMPLIFIER: Int = 1

    fun init() {
        EntityEvent.LIVING_HURT.register(::onLivingHurt)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onLivingHurt(
        entity: LivingEntity, source: DamageSource, amount: Float,
    ): EventResult {
        if (entity !is ServerPlayer) return EventResult.pass()
        // Only intercept the killing hit. Non-lethal damage stays so the
        // visual / audio cause of the redirect reads as the killing hit
        // rather than appearing out of nowhere.
        if (entity.health - amount > 0f) return EventResult.pass()

        val host = pickRandomHost(entity) ?: return EventResult.pass()
        performRedirect(entity, host)
        return EventResult.interruptFalse()
    }

    /** Scan every loaded dimension for hosts owned by [player]. Returns a
     *  uniformly-random pick from the pool of alive + non-removed hosts,
     *  or `null` if none exist. The scan is bounded by the number of
     *  loaded levels (small) × the per-level Wik-Lak entity count (also
     *  small in practice), and only runs on the player's death tick. */
    private fun pickRandomHost(player: ServerPlayer): WikLakHostEntity? {
        val server = player.server
        val playerUuid = player.uuid
        val pool = ArrayList<WikLakHostEntity>()
        for (level in server.allLevels) {
            // Empty AABB filter is `null` → walks the whole entity index for
            // the type. The class filter narrows to our entity before the
            // predicate runs, so the per-host work is just a UUID compare.
            level.getEntities(EntityTypeTest.forClass(WikLakHostEntity::class.java), { host ->
                host.isAlive && !host.isRemoved && host.creatorUuid == playerUuid
            }).forEach { pool.add(it) }
        }
        if (pool.isEmpty()) return null
        return pool[player.random.nextInt(pool.size)]
    }

    private fun performRedirect(player: ServerPlayer, host: WikLakHostEntity) {
        // Snapshot the death position BEFORE moving the player — we leave a
        // dying body at this spot so the visual death animation still plays.
        val deathLevel = player.level() as? ServerLevel
        val deathPos = player.position()
        val deathYRot = player.yRot
        val deathXRot = player.xRot

        // Restore vitals BEFORE moving so the hit that triggered this
        // doesn't kill the player on the next tick from residual state
        // (fall distance, freeze ticks, etc. checked in baseTick).
        player.health = player.maxHealth
        player.foodData.foodLevel = 20
        player.airSupply = player.maxAirSupply
        player.fallDistance = 0.0f
        player.remainingFireTicks = 0

        val hostLevel = host.level() as? ServerLevel ?: return
        val pos = host.position()

        if (player.level() !== hostLevel) {
            player.teleportTo(hostLevel, pos.x, pos.y, pos.z, host.yRot, host.xRot)
        } else {
            player.teleportTo(pos.x, pos.y, pos.z)
            player.setYRot(host.yRot)
            player.setXRot(host.xRot)
        }

        // Mark the borrowed body. Always replace, not append — a player
        // who dies twice in quick succession shouldn't stack the debuff
        // (vanilla addEffect refuses lower-amp / shorter-duration adds,
        // so a follow-up redirect would no-op without the explicit reset).
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN)
        player.removeEffect(MobEffects.WEAKNESS)
        player.removeEffect(MobEffects.DIG_SLOWDOWN)
        player.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DEBUFF_TICKS, DEBUFF_AMPLIFIER))
        player.addEffect(MobEffectInstance(MobEffects.WEAKNESS, DEBUFF_TICKS, DEBUFF_AMPLIFIER))
        player.addEffect(MobEffectInstance(MobEffects.DIG_SLOWDOWN, DEBUFF_TICKS, DEBUFF_AMPLIFIER))

        // Snapshot host's position before discard so the arrival-side
        // soul burst lands at the same spot the player just teleported to.
        val hostPos = host.position()
        host.discard()

        // Soul-particle burst + sound at the NEW position, marking the
        // arrival of the player into the borrowed body.
        emitSoulBurst(hostLevel, hostPos)

        // Trigger the client-side wik-lak skin fade overlay on the player.
        // Broadcast to all clients tracking the new position so observers
        // see the same fade — and so the redirected player (definitely
        // within range) gets it on their own client too.
        WikLakRedirectFlashNetwork.broadcast(hostLevel, hostPos, player.uuid)

        // Leave a player-skinned corpse at the original death position so
        // the visual death animation plays where the player fell. The
        // PlayerCorpseEntity renders with the player's skin (looked up
        // client-side from tab-list PlayerInfo) and self-removes after
        // ~1 second when Mob.tickDeath completes.
        if (deathLevel != null) {
            spawnDyingCorpse(deathLevel, deathPos, deathYRot, deathXRot, player.uuid)
            // Soul-particle burst + sound at the OLD position, marking the
            // departure of the soul from the dying body.
            emitSoulBurst(deathLevel, deathPos)
        }
    }

    private fun spawnDyingCorpse(
        level: ServerLevel, pos: Vec3, yRot: Float, xRot: Float, playerUuid: UUID,
    ) {
        val corpse = EKEntities.PLAYER_CORPSE.get().create(level) ?: return
        corpse.moveTo(pos.x, pos.y, pos.z, yRot, xRot)
        corpse.playerUuid = playerUuid
        level.addFreshEntity(corpse)
        // Lethal generic damage drives the entity into LivingEntity.die →
        // Mob.tickDeath, which advances deathTime and broadcasts EntityEvent
        // 60 at deathTime = 20. The renderer's death-tilt animation plays
        // for those ~1 second of ticks, then the corpse self-removes.
        corpse.hurt(level.damageSources().generic(), Float.MAX_VALUE)
    }

    /** Server-side broadcast: a dense soul-particle puff at the body
     *  height of [pos], with a single soul-escape sound for sympathetic
     *  audio. Sent via `sendParticles` so all nearby tracking players see
     *  the same burst whether or not they were the one who died. */
    private fun emitSoulBurst(level: ServerLevel, pos: Vec3) {
        level.sendParticles(
            ParticleTypes.SOUL,
            pos.x, pos.y + SOUL_BURST_Y_OFFSET, pos.z,
            SOUL_BURST_COUNT,
            SOUL_BURST_SPREAD, SOUL_BURST_SPREAD * 1.5, SOUL_BURST_SPREAD,
            SOUL_BURST_SPEED,
        )
        level.playSound(
            null,
            pos.x, pos.y + SOUL_BURST_Y_OFFSET, pos.z,
            SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS,
            0.9f, 0.7f,
        )
    }

    /** Height above the entity origin where the soul burst centres. ~1
     *  block = chest height for a player-sized entity. */
    private const val SOUL_BURST_Y_OFFSET: Double = 1.0

    /** Particle count for the soul burst. 40 reads as a clear soul-puff
     *  without flooding the particle budget on busy servers. */
    private const val SOUL_BURST_COUNT: Int = 40

    /** Per-axis gaussian spread of the burst (input to `sendParticles`'s
     *  offset args). 0.3 blocks of XZ spread, 0.45 of Y spread → a
     *  vertical cigar shape that matches the column of an upright body. */
    private const val SOUL_BURST_SPREAD: Double = 0.30

    /** Per-particle initial velocity. Soul particles already drift upward
     *  on their own; a touch of speed adds initial outward motion. */
    private const val SOUL_BURST_SPEED: Double = 0.04
}
