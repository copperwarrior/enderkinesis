package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.mixin.MobGoalSelectorAccessor
import org.shipwrights.enderkinesis.registry.EKEffects
import java.util.EnumSet
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** What the Grand Staff of Dread does while cast: Ulder Paranoia on all in range, and a dark place far away for each. */
object GrandStaffDread {

    const val RADIUS = 16.0
    private const val CAST_INTERVAL_TICKS = 10L
    // Outlasts the gap between refreshes, so the effect ends soon after the caster lets go.
    private const val EFFECT_TICKS = 60
    private const val REPICK_TICKS = 100L
    private const val ARRIVE_SQ = 2.0 * 2.0
    private const val SAMPLES = 24
    private const val MIN_STEP = 8.0
    private const val MAX_STEP = 24.0
    private const val SPREAD = Math.PI / 2
    private const val COLUMN_HALF_HEIGHT = 6
    // One light level is worth this many blocks of distance from the caster.
    private const val LIGHT_WEIGHT = 8.0
    private const val FLEE_SPEED = 1.25
    private const val FLEE_GOAL_PRIORITY = -1

    /** [target] is null when no dark place was found; [pickedAt] still limits how often the search runs. */
    private class Flight(val target: BlockPos?, val pickedAt: Long)

    // Server thread only. Weak keys: an unloaded entity drops its state.
    private val flights = WeakHashMap<LivingEntity, Flight>()
    private val fleeGoals = WeakHashMap<Mob, FleeIntoDarkGoal>()

    fun isCasting(entity: LivingEntity): Boolean =
        entity.isUsingItem && entity.useItem.item is GrandStaffOfDreadItem

    /** Server and client both use this, so the tendrils go to the same entities the effect does. */
    fun targetsOf(level: Level, caster: LivingEntity): List<LivingEntity> = GrandStaffAura.targets(level, caster, RADIUS)

    fun tickCast(level: ServerLevel, caster: LivingEntity) {
        if (level.gameTime % CAST_INTERVAL_TICKS != 0L) return
        val effect = EKEffects.ULDER_PARANOIA.get()
        for (target in targetsOf(level, caster)) {
            // No swirl particles: the tendrils and eyes show the effect.
            target.addEffect(MobEffectInstance(effect, EFFECT_TICKS, 0, false, false, true), caster)
            updateFlight(level, target, caster)
        }
    }

    private fun updateFlight(level: ServerLevel, target: LivingEntity, caster: LivingEntity) {
        val now = level.gameTime
        val old = flights[target]
        val arrived = old?.target?.let { arrived(target, it) } ?: false
        if (old != null && !arrived && now - old.pickedAt < REPICK_TICKS) return
        flights[target] = Flight(darkPlace(level, target, caster) ?: old?.target, now)
    }

    /** The dark place [entity] runs to, or null when it has none. Server thread only. */
    fun destinationOf(entity: LivingEntity): BlockPos? = flights[entity]?.target

    /** Within this distance of [destinationOf], the entity has arrived and cowers there. */
    fun arrived(entity: LivingEntity, destination: BlockPos): Boolean = entity.distanceToSqr(destination.center) <= ARRIVE_SQ

    /** The darkest standing place in a cone that points away from [caster]. Ties go to the place farthest from it. */
    private fun darkPlace(level: ServerLevel, entity: LivingEntity, caster: LivingEntity): BlockPos? {
        val away = Math.atan2(entity.z - caster.z, entity.x - caster.x)
        val random = entity.random
        val cursor = BlockPos.MutableBlockPos()
        var best: BlockPos? = null
        var bestScore = Double.MAX_VALUE
        repeat(SAMPLES) {
            val angle = away + (random.nextDouble() * 2.0 - 1.0) * SPREAD
            val step = MIN_STEP + random.nextDouble() * (MAX_STEP - MIN_STEP)
            val x = floor(entity.x + cos(angle) * step).toInt()
            val z = floor(entity.z + sin(angle) * step).toInt()
            if (!standOn(level, x, entity.blockY, z, cursor)) return@repeat
            val light = level.getMaxLocalRawBrightness(cursor)
            val fromCaster = sqrt(caster.distanceToSqr(cursor.center))
            val score = light * LIGHT_WEIGHT - fromCaster
            if (score < bestScore) {
                bestScore = score
                best = cursor.immutable()
            }
        }
        return best
    }

    /** Moves [out] to the standing place in column ([x], [z]) nearest [baseY]. False when there is none. */
    private fun standOn(level: ServerLevel, x: Int, baseY: Int, z: Int, out: BlockPos.MutableBlockPos): Boolean {
        val below = BlockPos.MutableBlockPos()
        for (i in 0..COLUMN_HALF_HEIGHT * 2) {
            val dy = if (i % 2 == 0) i / 2 else -(i + 1) / 2
            out.set(x, baseY + dy, z)
            if (!level.isLoaded(out)) return false
            if (!open(level, out) || !open(level, out.above())) continue
            below.setWithOffset(out, Direction.DOWN)
            if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return true
        }
        return false
    }

    private fun open(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return state.getCollisionShape(level, pos).isEmpty && state.fluidState.isEmpty
    }

    /** Called from the effect tick. Puts the flee goal over the mob's own goals while the effect lasts. */
    fun holdFleeGoal(mob: Mob) {
        if (mob.level().isClientSide || fleeGoals.containsKey(mob)) return
        val goal = FleeIntoDarkGoal(mob)
        fleeGoals[mob] = goal
        (mob as MobGoalSelectorAccessor).`enderkinesis$getGoalSelector`().addGoal(FLEE_GOAL_PRIORITY, goal)
    }

    fun release(entity: LivingEntity) {
        flights.remove(entity)
        val mob = entity as? Mob ?: return
        val goal = fleeGoals.remove(mob) ?: return
        (mob as MobGoalSelectorAccessor).`enderkinesis$getGoalSelector`().removeGoal(goal)
    }

    /** MOVE and LOOK stop attack goals; the mob only runs and cowers. */
    private class FleeIntoDarkGoal(private val mob: Mob) : Goal() {
        private var pathedTo: BlockPos? = null

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean = mob.hasEffect(EKEffects.ULDER_PARANOIA.get())

        override fun stop() {
            pathedTo = null
            mob.navigation.stop()
        }

        override fun tick() {
            mob.target = null
            val dest = flights[mob]?.target ?: return
            if (arrived(mob, dest)) return
            if (dest == pathedTo && !mob.navigation.isDone) return
            pathedTo = dest
            mob.navigation.moveTo(dest.x + 0.5, dest.y.toDouble(), dest.z + 0.5, FLEE_SPEED)
        }
    }
}
