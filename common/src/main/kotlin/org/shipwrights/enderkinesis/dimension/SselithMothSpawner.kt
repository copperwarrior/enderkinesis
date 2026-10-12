package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.BlockPos
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.phys.AABB
import org.shipwrights.enderkinesis.entity.SselithMothEntity
import org.shipwrights.enderkinesis.registry.EKEntities
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hostile-mob-style periodic spawner for Sselith Moths. Every
 * [SPAWN_TICK_PERIOD] ticks, for each player in the Sselith dimension:
 *   1. Count existing moths within a wide XZ radius around the player.
 *   2. If the count is under [MAX_MOTHS_PER_PLAYER], roll [SPAWN_CHANCE].
 *   3. On success, spawn one flock of [MIN_FLOCK]..[MAX_FLOCK] moths at
 *      a random horizontal angle, [SPAWN_MIN_DIST]..[SPAWN_MAX_DIST]
 *      blocks XZ from the player, at Y = ±[MOTH_ALTITUDE] (whichever is
 *      closer to the player's Y).
 *
 * The flock shares a random [SselithMothEntity.setDriftYaw]; combined
 * with the entity's forward-only migration behaviour, this produces the
 * "generate at distance, float off, despawn, repeat" cycle typical of
 * vanilla hostile mobs — no chunk-gen persistence, no accumulation over
 * time. The moth entity's XZ-only [SselithMothEntity.checkDespawn] then
 * removes any flock that drifts past [SselithMothEntity.DESPAWN_XZ] from
 * the nearest player, so the count stays self-limiting.
 */
object SselithMothSpawner {

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
    }

    private fun tickLevel(level: ServerLevel) {
        if (level.dimension() != SselithRepertory.LEVEL_KEY) return
        if (level.gameTime % SPAWN_TICK_PERIOD != 0L) return
        for (player in level.players()) {
            if (player.isSpectator) continue
            trySpawnForPlayer(level, player)
        }
    }

    private fun trySpawnForPlayer(level: ServerLevel, player: net.minecraft.server.level.ServerPlayer) {
        val bbHalf = SPAWN_MAX_DIST * 2.0
        val presence = level.getEntitiesOfClass(
            SselithMothEntity::class.java,
            AABB(
                player.x - bbHalf, MIN_ALT_SEARCH, player.z - bbHalf,
                player.x + bbHalf, MAX_ALT_SEARCH, player.z + bbHalf,
            ),
        )
        if (presence.size >= MAX_MOTHS_PER_PLAYER) return
        if (level.random.nextFloat() > SPAWN_CHANCE) return

        val rng = level.random
        val angle = rng.nextFloat() * (2f * PI.toFloat())
        val distance = SPAWN_MIN_DIST + rng.nextFloat() * (SPAWN_MAX_DIST - SPAWN_MIN_DIST)
        val cx = player.x + cos(angle.toDouble()) * distance
        val cz = player.z + sin(angle.toDouble()) * distance
        val cy: Double = if (player.y >= 0.0) MOTH_ALTITUDE else -MOTH_ALTITUDE

        // An entity added to an unloaded section is hidden, and the count above does not see it.
        if (!level.isPositionEntityTicking(BlockPos.containing(cx, cy, cz))) return
        spawnFlock(level, cx, cy, cz)
    }

    private fun spawnFlock(level: ServerLevel, cx: Double, cy: Double, cz: Double) {
        val type = EKEntities.SSELITH_MOTH.get()
        val rng = level.random
        val flockSize = MIN_FLOCK + rng.nextInt(MAX_FLOCK - MIN_FLOCK + 1)
        // Shared migration heading for the whole flock. Random — some
        // flocks will drift toward the player, others past, others away.
        val flockDriftYaw = rng.nextFloat() * (2f * PI.toFloat())
        // Perpendicular to the drift so the flock spawns as a wide
        // skirmish line across their heading, not a queue along it.
        val perpX = -sin(flockDriftYaw.toDouble())
        val perpZ = cos(flockDriftYaw.toDouble())
        repeat(flockSize) {
            val spread = (rng.nextFloat() - 0.5f) * FLOCK_SPREAD
            val jx = perpX * spread + (rng.nextFloat() - 0.5f) * FLOCK_JITTER
            val jz = perpZ * spread + (rng.nextFloat() - 0.5f) * FLOCK_JITTER
            val jy = (rng.nextFloat() - 0.5f) * 4.0f
            if (!level.isPositionEntityTicking(BlockPos.containing(cx + jx, cy + jy, cz + jz))) return@repeat
            val moth = type.create(level) ?: return@repeat
            moth.moveTo(
                cx + jx, cy + jy, cz + jz,
                Math.toDegrees(flockDriftYaw.toDouble()).toFloat(), 0f,
            )
            moth.setDriftYaw(flockDriftYaw)
            moth.finalizeSpawn(
                level, level.getCurrentDifficultyAt(moth.blockPosition()),
                MobSpawnType.NATURAL, null, null,
            )
            level.addFreshEntity(moth)
        }
    }

    /** Spawn cadence in ticks. Every 200 ticks (10 s) is slow enough that
     *  the cost stays negligible, fast enough that a player exploring
     *  Sselith sees new flocks appear regularly. */
    private const val SPAWN_TICK_PERIOD: Long = 200

    /** Chance per player per spawn tick that a new flock is generated. */
    private const val SPAWN_CHANCE: Float = 0.35f

    /** Cap on the moth count within the search box around a player.
     *  Prevents flock accumulation when a player camps in one spot. */
    private const val MAX_MOTHS_PER_PLAYER: Int = 24

    /** Min horizontal distance from the player at which a fresh flock
     *  spawns. Far enough to fade out of view; close enough to reach the
     *  player before despawning. */
    private const val SPAWN_MIN_DIST: Float = 48f

    /** Max horizontal distance from the player at which a fresh flock
     *  spawns. Beyond [SselithMothEntity.DESPAWN_XZ] flocks would
     *  immediately despawn again, so this stays inside that radius. */
    private const val SPAWN_MAX_DIST: Float = 80f

    /** Search box Y bounds when counting moths near a player. Wide enough
     *  to catch both the high and low band regardless of player Y. */
    private const val MIN_ALT_SEARCH: Double = -260.0
    private const val MAX_ALT_SEARCH: Double = 260.0

    /** Altitude every moth converges to (Y = ±this). Must match
     *  [SselithMothEntity.MOTH_ALTITUDE]'s numeric value. */
    private const val MOTH_ALTITUDE: Double = 134.5

    private const val MIN_FLOCK: Int = 3
    private const val MAX_FLOCK: Int = 5

    /** Full range of perpendicular-to-drift spawn spread, in blocks. 24
     *  gives a wide, visibly spaced skirmish line. */
    private const val FLOCK_SPREAD: Float = 24f

    /** Small random XZ jitter on top of the perpendicular spread. */
    private const val FLOCK_JITTER: Float = 4f
}
