package org.shipwrights.enderkinesis.possession

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.entity.Cataloger
import org.shipwrights.enderkinesis.entity.CatalogerWanderGoal
import org.shipwrights.enderkinesis.entity.WanderToTaggedBlockGoal

/** The Cataloger's own POI and wander goals on a ghost [Cataloger], run the way its goal selector runs them. */
class SselithWalk(val ghost: Cataloger) {

    private val toPoi = WanderToTaggedBlockGoal(ghost, Cataloger.SEARCH_RADIUS, scribes = false)
    private val wander = CatalogerWanderGoal(
        ghost, Cataloger.SEARCH_RADIUS, Cataloger.MOVE_SPEED,
        Cataloger.WANDER_NODE_MULTIPLIER, Cataloger.PATHFIND_NODE_MULTIPLIER,
    )
    private var running: Goal? = null

    val staringAt: Vec3? get() = toPoi.staringAt

    /** Starts at the POI the player had open, so the walk does not lead away from it. */
    fun beginAt(poi: BlockPos) {
        if (toPoi.beginAt(poi)) running = toPoi
    }

    /** One goal-selector pass: the POI goal outranks the wander, as in [Cataloger.registerGoals]. */
    fun tick() {
        val current = running
        if (current != null && !current.canContinueToUse()) {
            current.stop()
            running = null
        }
        if (running !== toPoi && toPoi.canUse()) {
            running?.stop()
            running = toPoi
            toPoi.start()
        } else if (running == null && wander.canUse()) {
            running = wander
            wander.start()
        }
        running?.tick()
    }

    fun stop() {
        running?.stop()
        running = null
    }
}
