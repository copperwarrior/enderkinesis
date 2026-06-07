package org.shipwrights.enderkinesis.entity

import java.util.EnumSet
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.util.LandRandomPos
import net.minecraft.world.phys.Vec3

/**
 * Fallback drift for the Cataloger: stroll to a random reachable point within
 * [radius] blocks. Registered at a *lower* priority than [WanderToTaggedBlockGoal],
 * so the [net.minecraft.world.entity.ai.goal.GoalSelector] only lets it run when no
 * POI target is available — the Cataloger paths to lecterns/signs/banners/etc. when
 * it can, and only wanders when there's nothing to catalogue nearby. A higher-
 * priority POI find interrupts it. Mirrors vanilla `RandomStrollGoal`, widened to
 * the Cataloger's search radius.
 */
class CatalogerWanderGoal(
    private val mob: PathfinderMob,
    private val radius: Int,
    private val speed: Double,
) : Goal() {

    private var target: Vec3? = null

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        if (mob.isVehicle) return false
        val pos = LandRandomPos.getPos(mob, radius, radius / 2) ?: return false
        target = pos
        return true
    }

    override fun canContinueToUse(): Boolean = !mob.navigation.isDone

    override fun start() {
        target?.let { mob.navigation.moveTo(it.x, it.y, it.z, speed) }
    }

    override fun stop() {
        target = null
    }
}
