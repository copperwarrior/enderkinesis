package org.shipwrights.enderkinesis.entity

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.shipwrights.enderkinesis.entity.ConstriliarSurfaceNavigator.Node
import org.shipwrights.enderkinesis.entity.NavigatorTestWorld.Companion.CARRIER

/**
 * Along a face change, every pose of a slow body must have a hover surface: one of its hover rays
 * (at the plate, or ahead along the route) meets a block within the ray's reach. The attitude, the
 * route direction and the sample points are the gait's own; only the ray is this test's voxel march.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NavigatorHoverSupportTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun n(x: Int, y: Int, z: Int, face: Direction = Direction.DOWN) = Node(BlockPos(x, y, z), face)

    @Test
    fun `a 2-block notch climb keeps a hover surface in every pose`() {
        NavigatorTestWorld().use { world ->
            world.fill(-5, 5, 3, 7, 0, 1)
            val route = listOf(
                n(0, 1, -3), n(0, 1, -2), n(0, 1, -1), n(0, 3, -1, Direction.SOUTH), n(0, 4, -1, Direction.SOUTH),
                n(0, 5, -1, Direction.SOUTH), n(0, 6, -1, Direction.SOUTH),
            )
            // Over the floor to the face at floor height (the turn), then up the face past the notch.
            val path = listOf(Vec3(0.5, 1.75, -1.5), Vec3(0.5, 1.75, -0.75), Vec3(0.5, 4.5, -0.75))
            assertSupported(world, route, path)
        }
    }

    @Test
    fun `a 1-block notch climb keeps a hover surface in every pose`() {
        NavigatorTestWorld().use { world ->
            world.fill(-5, 5, 2, 7, 0, 1)
            val route = listOf(
                n(0, 1, -3), n(0, 1, -2), n(0, 1, -1), n(0, 2, -1, Direction.SOUTH), n(0, 3, -1, Direction.SOUTH),
                n(0, 4, -1, Direction.SOUTH), n(0, 5, -1, Direction.SOUTH),
            )
            val path = listOf(Vec3(0.5, 1.75, -1.5), Vec3(0.5, 1.75, -0.75), Vec3(0.5, 3.5, -0.75))
            assertSupported(world, route, path)
        }
    }

    @Test
    fun `round a 2-tall lip from the underside to the top keeps a hover surface in every pose`() {
        NavigatorTestWorld().use { world ->
            world.fill(-5, 5, 1, 4, 2, 3)
            world.fill(-5, 5, 5, 6, -1, 3)
            val route = listOf(
                n(0, 4, 1, Direction.UP), n(0, 4, 0, Direction.UP), n(0, 4, -1, Direction.UP),
                n(0, 5, -2, Direction.SOUTH), n(0, 6, -2, Direction.SOUTH), n(0, 7, -1), n(0, 7, 0),
            )
            // Under the ledge to its lip, round the lower edge, up the lip face, round the upper edge.
            val path = listOf(
                Vec3(0.5, 4.25, 0.5), Vec3(0.5, 4.25, -1.0), Vec3(0.5, 4.6, -1.6), Vec3(0.5, 5.0, -1.75),
                Vec3(0.5, 6.9, -1.75), Vec3(0.5, 7.3, -1.6), Vec3(0.5, 7.75, -1.0), Vec3(0.5, 7.75, 0.5),
            )
            assertSupported(world, route, path)
        }
    }

    @Test
    fun `over a 1-tall lip (a fold) from the underside to the top keeps a hover surface in every pose`() {
        NavigatorTestWorld().use { world ->
            world.fill(-5, 5, 1, 4, 2, 3)
            world.fill(-5, 5, 5, 5, -1, 3)
            val route = listOf(
                n(0, 4, 1, Direction.UP), n(0, 4, 0, Direction.UP), n(0, 4, -1, Direction.UP),
                n(0, 5, -2, Direction.SOUTH), n(0, 6, -1), n(0, 6, 0),
            )
            val path = listOf(
                Vec3(0.5, 4.25, 0.5), Vec3(0.5, 4.25, -1.0), Vec3(0.5, 4.6, -1.6), Vec3(0.5, 5.5, -1.75),
                Vec3(0.5, 6.3, -1.6), Vec3(0.5, 6.75, -1.0), Vec3(0.5, 6.75, 0.5),
            )
            assertSupported(world, route, path)
        }
    }

    @Test
    fun `under a stepped overhang's next step the hover lifts the body one block out`() {
        NavigatorTestWorld().use { world ->
            for (k in 0..4) world.fill(-5, 5, 1 + k, 1 + k, -6, k - 1)
            val route = listOf(
                n(0, 1, 0, Direction.NORTH), n(0, 2, 1, Direction.NORTH), n(0, 3, 2, Direction.NORTH),
                n(0, 4, 3, Direction.NORTH),
            )
            val nav = world.navigator(CARRIER)
            nav.followForTest(route, 1)
            assertTrue(nav.stepsOutAhead())
            // The plate rides off the face at z=1 with the next step's underside right over it.
            val plate = Vec3(0.5, 2.5, 1.5)
            val up = nav.nodeWorldUp(world.level, route[1], Vector3d())
            val dir = Vector3d()
            assertTrue(nav.routeDirection(world.level, plate, dir))
            dir.normalize()
            val along = DoubleArray(HOVER_ROUTE_SAMPLES) { k -> hitAlong(world, hoverSampleBase(plate, dir, hoverRouteOffset(k)), up) }
            val chosen = chooseHoverHit(routeOffsets, along, HOVER_ROUTE_SAMPLES)
            assertTrue(chosen >= 0) { "no hover hit: ${along.toList()}" }
            assertEquals(2.0, along[chosen], RAY_STEP) { "the hover held the lower face: ${along.toList()}" }
        }
    }

    // Route samples in order: plate, ahead, ahead, behind (offsets 0, 1, 2, -1).
    private val routeOffsets = IntArray(HOVER_ROUTE_SAMPLES) { hoverRouteOffset(it) }

    @Test
    fun `at a notch's end the wall ahead wins over the floor hit behind`() {
        // Up is off the wall: the floor hit behind lies higher along up (2.05) than the wall ahead (0.0).
        val along = doubleArrayOf(Double.NaN, Double.NaN, 0.0, 2.05)
        assertEquals(2, chooseHoverHit(routeOffsets, along, HOVER_ROUTE_SAMPLES))
    }

    @Test
    fun `at a lip's top edge the behind hit is used when nothing ahead hits`() {
        val along = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, 1.0)
        assertEquals(3, chooseHoverHit(routeOffsets, along, HOVER_ROUTE_SAMPLES))
    }

    @Test
    fun `no hit gives no hover surface`() {
        assertEquals(-1, chooseHoverHit(routeOffsets, DoubleArray(HOVER_ROUTE_SAMPLES) { Double.NaN }, HOVER_ROUTE_SAMPLES))
        assertEquals(-1, chooseHoverHit(routeOffsets, doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, 1.0), 1))
    }

    @Test
    fun `moving samples keep the highest hit, as before`() {
        val moving = intArrayOf(0, 1, 2)
        assertEquals(1, chooseHoverHit(moving, doubleArrayOf(0.2, 0.9, Double.NaN), 3))
    }

    /**
     * Walks [path] in short steps. The real follower ([ConstriliarSurfaceNavigator.tickFollow], fold gate included)
     * keeps the current node, with the body's attitude as the gait last set it; each pose needs a hover hit.
     */
    private fun assertSupported(world: NavigatorTestWorld, route: List<Node>, path: List<Vec3>) {
        val nav = world.navigator(CARRIER)
        nav.followForTest(route, 0)
        val up = nav.nodeWorldUp(world.level, route[0], Vector3d())
        val dir = Vector3d()
        for (leg in 1 until path.size) {
            val from = path[leg - 1]
            val to = path[leg]
            for (k in 0..STEPS) {
                val body = from.lerp(to, k.toDouble() / STEPS)
                // Null at the route's end too; a dropped route is emptied.
                val node = nav.tickFollow(world.level, body, up, transitioning = true) ?: route.last()
                assertTrue(nav.routeLength() == route.size) { "the follower dropped the route at $body" }
                if (!nav.transitionUp(world.level, body, up)) nav.nodeWorldUp(world.level, node, up)
                val hasDir = nav.routeDirection(world.level, body, dir) && dir.lengthSquared() > 1.0e-9
                if (hasDir) dir.normalize()
                val hit = (0 until HOVER_ROUTE_SAMPLES).any { k ->
                    (k == 0 || hasDir) && rayHits(world, hoverSampleBase(body, dir, hoverRouteOffset(k)), up)
                }
                assertTrue(hit) { "no hover surface at $body (up $up, node ${nav.currentNode()})" }
            }
        }
    }

    /** The hover ray: from [HOVER_RAY_START_UP] over [base] down the body's up, as far as it reaches. */
    private fun rayHits(world: NavigatorTestWorld, base: Vec3, up: Vector3d): Boolean {
        val start = ConstriliarEntity.HOVER_RAY_START_UP
        val reach = start + ConstriliarEntity.HOVER_RAY_REACH + ConstriliarEntity.BODY_LIFT
        var t = 0.0
        while (t <= reach) {
            val along = start - t
            val pos = BlockPos.containing(base.x + up.x * along, base.y + up.y * along, base.z + up.z * along)
            if (!world.stateAt(pos).isAir) return true
            t += RAY_STEP
        }
        return false
    }

    /** The hover ray's first hit, as its distance along [up]; NaN when it reaches nothing. */
    private fun hitAlong(world: NavigatorTestWorld, base: Vec3, up: Vector3d): Double {
        val start = ConstriliarEntity.HOVER_RAY_START_UP
        val reach = start + ConstriliarEntity.HOVER_RAY_REACH + ConstriliarEntity.BODY_LIFT
        var t = 0.0
        while (t <= reach) {
            val along = start - t
            val p = Vec3(base.x + up.x * along, base.y + up.y * along, base.z + up.z * along)
            if (!world.stateAt(BlockPos.containing(p.x, p.y, p.z)).isAir) return p.x * up.x + p.y * up.y + p.z * up.z
            t += RAY_STEP
        }
        return Double.NaN
    }

    private companion object {
        const val STEPS = 20
        const val RAY_STEP = 0.05
    }
}
