package org.shipwrights.enderkinesis.entity

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.entity.ConstriliarSurfaceNavigator.Node
import java.util.WeakHashMap

/**
 * What the walkers of one level share while they plan, in the world frame: terrain
 * cell flags without ships, stances where no ship is near, and routes found to recent
 * goals. Server thread only: every walker plans in its own entity tick.
 */
internal class ConstriliarPathShare private constructor() {

    /** Cell flags of the blocks alone; each walker lays its nearby ships over them. */
    val cells = Long2IntOpenHashMap()
    val stances = Object2IntOpenHashMap<Node>().apply { defaultReturnValue(-1) }
    private var cacheBorn = Long.MIN_VALUE

    class Route(val nodes: List<Node>, val end: Vec3, val born: Long, val serial: Long, val anchor: Vec3?)

    /** A route serves a walker of the same order (one [anchor]), or one whose own goal
     *  lies near its end. */
    private fun serves(route: Route, goal: Vec3, anchor: Vec3?): Boolean {
        val a = route.anchor
        if (a != null && anchor != null && a.distanceToSqr(anchor) <= SAME_ANCHOR_SQ &&
            route.end.distanceToSqr(anchor) <= ANCHOR_END_NEAR_SQ
        ) {
            return true
        }
        return route.end.distanceToSqr(goal) <= GOAL_NEAR_SQ
    }

    /** A node on a shared route, and how far along that route it is. */
    class Portal(val route: Route, val index: Int)

    private val routes = ArrayList<Route>()

    /** Goes up with each published route: searches started before it look again. */
    var version = 0L
        private set

    /** Blocks change: the shared lookups last [CACHE_TICKS], then start again. */
    fun refresh(now: Long) {
        if (now - cacheBorn <= CACHE_TICKS && cells.size < MAX_CELLS) return
        cells.clear()
        stances.clear()
        cacheBorn = now
    }

    fun publish(now: Long, nodes: List<Node>, anchor: Vec3?) {
        if (nodes.size < 2) return
        routes.removeIf { now - it.born > ROUTE_TICKS || it.nodes.last() == nodes.last() && it.nodes.size >= nodes.size }
        if (routes.size >= MAX_ROUTES) routes.removeAt(0)
        val last = nodes.last().pos
        version++
        routes.add(Route(nodes.toList(), Vec3(last.x + 0.5, last.y + 0.5, last.z + 0.5), now, version, anchor))
    }

    /** A route serving [goal] / [anchor] was published after [version] [since]. */
    fun newRouteNear(goal: Vec3, anchor: Vec3?, since: Long): Boolean =
        routes.any { it.serial > since && serves(it, goal, anchor) }

    /** Every node of a route serving [goal] / [anchor], each with the shortest way on to it. */
    fun portalsNear(now: Long, goal: Vec3, anchor: Vec3?): Map<Node, Portal> {
        if (routes.isEmpty()) return emptyMap()
        val out = Object2ObjectOpenHashMap<Node, Portal>()
        for (route in routes) {
            if (now - route.born > ROUTE_TICKS || !serves(route, goal, anchor)) continue
            val size = route.nodes.size
            for (i in 0 until size - 1) {
                val known = out[route.nodes[i]]
                if (known == null || known.route.nodes.size - known.index > size - i) out[route.nodes[i]] = Portal(route, i)
            }
        }
        return out
    }

    companion object {
        // Server thread only (see the class doc), so one map per level is safe here.
        private val byLevel = WeakHashMap<Level, ConstriliarPathShare>()

        fun of(level: Level): ConstriliarPathShare {
            check(!level.isClientSide) { "path sharing is server-side" }
            return byLevel.getOrPut(level) { ConstriliarPathShare() }
        }

        private const val CACHE_TICKS = 100L
        private const val MAX_CELLS = 400_000
        private const val ROUTE_TICKS = 1200L
        private const val MAX_ROUTES = 16
        /** A route serves goals whose centre is this near its end (blocks²). */
        private const val GOAL_NEAR_SQ = 4.0 * 4.0
        private const val SAME_ANCHOR_SQ = 1.0
        /** An order's route ends inside its formation: this near its anchor (blocks²). */
        private const val ANCHOR_END_NEAR_SQ = 12.0 * 12.0
    }
}
