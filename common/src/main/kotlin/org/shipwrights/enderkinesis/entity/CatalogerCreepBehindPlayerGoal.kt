package org.shipwrights.enderkinesis.entity

import java.util.EnumSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * Walk up to the block directly behind a nearby player and stand there in
 * silence for up to 3 minutes. The goal aborts the instant any of:
 *  - the 3-minute timer expires,
 *  - the player turns enough that the cataloger lands inside their view
 *    cone with a clear line of sight, OR
 *  - the player walks more than `sqrt(MOVE_TOLERANCE_SQR)` blocks from
 *    where they were standing when the cataloger arrived.
 *
 * Uses the player's **body yaw** (not eye pitch) to anchor "behind" so the
 * standing block doesn't drift when the player tilts their gaze. After the
 * goal stops, a [COOLDOWN_TICKS] grace timer suppresses re-triggering so
 * the same player doesn't get stalked back-to-back.
 */
class CatalogerCreepBehindPlayerGoal(
    private val mob: PathfinderMob,
) : Goal() {

    private var target: Player? = null
    private var standingPos: Vec3? = null
    private var playerStartPos: Vec3? = null
    private var ticksAtTarget: Int = 0
    private var nextEligibleTick: Long = 0L

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
    }

    override fun canUse(): Boolean {
        if (mob.isVehicle) return false
        if (mob.level().gameTime < nextEligibleTick) return false
        // Probabilistic so the encounter is rare; one-in-N per canUse poll.
        if (mob.random.nextInt(ACTIVATION_DENOM) != 0) return false
        val candidate = mob.level().getNearestPlayer(mob, MAX_RANGE) ?: return false
        if (candidate.isSpectator) return false
        if (playerLookingAtMob(candidate)) return false
        val behind = computeBehindStandingPos(candidate) ?: return false
        target = candidate
        standingPos = behind
        return true
    }

    override fun canContinueToUse(): Boolean {
        val p = target ?: return false
        val pos = standingPos ?: return false
        if (ticksAtTarget >= STAND_DURATION_TICKS) return false
        val start = playerStartPos
        if (start != null && p.position().distanceToSqr(start) > MOVE_TOLERANCE_SQR) return false
        if (playerLookingAtMob(p)) return false
        // Pathfinder gave up before we arrived: bail rather than spin on
        // an unreachable target until the 3-minute timer expires.
        if (mob.navigation.isDone && mob.distanceToSqr(pos.x, pos.y, pos.z) > CLOSE_ENOUGH_SQR) return false
        return true
    }

    override fun start() {
        val pos = standingPos ?: return
        mob.navigation.moveTo(pos.x, pos.y, pos.z, MOVE_SPEED)
        playerStartPos = target?.position()
        ticksAtTarget = 0
    }

    override fun tick() {
        val p = target ?: return
        val pos = standingPos ?: return
        mob.lookControl.setLookAt(p, MAX_HEAD_YAW_PER_TICK, MAX_HEAD_PITCH_PER_TICK)
        if (mob.distanceToSqr(pos.x, pos.y, pos.z) < CLOSE_ENOUGH_SQR) {
            if (!mob.navigation.isDone) mob.navigation.stop()
            ticksAtTarget++
        }
    }

    override fun stop() {
        mob.navigation.stop()
        target = null
        standingPos = null
        playerStartPos = null
        ticksAtTarget = 0
        nextEligibleTick = mob.level().gameTime + COOLDOWN_TICKS
    }

    /** Project the player's body yaw backward by [BEHIND_DISTANCE] and round
     *  to the containing block. Returns null if that block can't host a
     *  standing cataloger (2 blocks of headroom + a sturdy floor below). */
    private fun computeBehindStandingPos(player: Player): Vec3? {
        val yawRad = Math.toRadians(player.yRot.toDouble())
        // MC body-yaw forward: (-sin yaw, +cos yaw). Reverse for "behind".
        val backX = Math.sin(yawRad)
        val backZ = -Math.cos(yawRad)
        val px = player.x + backX * BEHIND_DISTANCE
        val pz = player.z + backZ * BEHIND_DISTANCE
        val bx = Math.floor(px).toInt()
        val by = player.blockY
        val bz = Math.floor(pz).toInt()
        val level = mob.level()
        val standPos = BlockPos(bx, by, bz)
        val abovePos = standPos.above()
        if (!level.getBlockState(standPos).getCollisionShape(level, standPos).isEmpty) return null
        if (!level.getBlockState(abovePos).getCollisionShape(level, abovePos).isEmpty) return null
        val floorPos = standPos.below()
        if (!level.getBlockState(floorPos).isFaceSturdy(level, floorPos, Direction.UP)) return null
        return Vec3(bx + 0.5, by.toDouble(), bz + 0.5)
    }

    /** True if the player's gaze cone covers the cataloger AND the player has
     *  unobstructed line of sight. Aims at the cataloger's torso (mid-height)
     *  rather than its feet so eye contact registers at typical viewing
     *  pitches. */
    private fun playerLookingAtMob(player: Player): Boolean {
        val look = player.lookAngle
        val dx = mob.x - player.x
        val dy = (mob.y + mob.bbHeight * 0.5) - player.eyeY
        val dz = mob.z - player.z
        val lenSqr = dx * dx + dy * dy + dz * dz
        if (lenSqr < 1e-6) return true
        val invLen = 1.0 / Math.sqrt(lenSqr)
        val dot = look.x * dx * invLen + look.y * dy * invLen + look.z * dz * invLen
        if (dot < LOOK_DOT_THRESHOLD) return false
        return player.hasLineOfSight(mob)
    }

    companion object {
        /** Horizontal search horizon for a target player. */
        private const val MAX_RANGE: Double = 24.0
        /** Distance from the player's centre to the centre of the "behind" block. */
        private const val BEHIND_DISTANCE: Double = 1.0
        /** Distance² at which the cataloger counts as standing on the target. */
        private const val CLOSE_ENOUGH_SQR: Double = 0.36
        /** Pathfinder speed modifier — full walk. */
        private const val MOVE_SPEED: Double = 1.0
        /** Max ticks at the target before the goal yields (3 minutes). */
        private const val STAND_DURATION_TICKS: Int = 20 * 60 * 3
        /** Player-displacement² past which the player counts as having "moved". */
        private const val MOVE_TOLERANCE_SQR: Double = 0.25
        /** cos(40°). The player's gaze direction must come within ~40° of the
         *  cataloger before the look-at check pairs with the LOS test. */
        private const val LOOK_DOT_THRESHOLD: Double = 0.766
        /** Grace window after the goal stops before this cataloger may try
         *  again (30 s). */
        private const val COOLDOWN_TICKS: Long = 20L * 30L
        /** Denominator on the per-poll activation roll. */
        private const val ACTIVATION_DENOM: Int = 300
        /** Head-turn limits passed to [setLookAt]. */
        private const val MAX_HEAD_YAW_PER_TICK: Float = 30f
        private const val MAX_HEAD_PITCH_PER_TICK: Float = 30f
    }
}
