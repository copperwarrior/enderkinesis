package org.shipwrights.enderkinesis.entity

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.shipwrights.enderkinesis.entity.ConstriliarSurfaceNavigator.Node
import org.shipwrights.enderkinesis.entity.NavigatorTestWorld.Companion.CARRIER
import org.shipwrights.enderkinesis.physics.ATTITUDE_MAX_STEP
import org.shipwrights.enderkinesis.physics.turnUpToward

/**
 * Every building shape a walker meets: a route must reach each goal, each node must pass
 * the walker's own fit and stance test, and each face change must be a turn the gait has.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NavigatorShapesTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private class Case(
        val name: String,
        val start: Node,
        val goal: Node,
        val load: Array<Triple<Int, Int, Int>> = arrayOf(CARRIER),
        val build: NavigatorTestWorld.() -> Unit,
    )

    private fun n(x: Int, y: Int, z: Int, face: Direction = Direction.DOWN) = Node(BlockPos(x, y, z), face)

    private val ground = n(0, 1, -8)

    private fun thinWall(w: NavigatorTestWorld) = w.fill(-5, 5, 1, 5, 0, 0)

    private fun room(w: NavigatorTestWorld) {
        w.fill(-4, 4, 1, 4, 0, 0)
        w.fill(-4, 4, 1, 4, 8, 8)
        w.fill(-4, -4, 1, 4, 0, 8)
        w.fill(4, 4, 1, 4, 0, 8)
    }

    private fun stairs(w: NavigatorTestWorld) {
        val stair = Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH)
        for (k in 0..4) {
            if (k > 0) w.fill(-2, 2, 1, k, k, k)
            w.fill(-2, 2, k + 1, k + 1, k, k, stair)
        }
        w.fill(-2, 2, 1, 5, 5, 8)
    }

    private fun cases(): List<Case> = buildList {
        val wall = { w: NavigatorTestWorld -> w.fill(-5, 5, 1, 6, 0, 1) }
        add(Case("flat wall: face", ground, n(0, 3, -1, Direction.SOUTH), build = { wall(this) }))
        add(Case("flat wall: top", ground, n(0, 7, 0), build = { wall(this) }))
        val wide = arrayOf(CARRIER, Triple(1, 1, 0))
        add(Case("flat wall: face, wide load", ground, n(0, 3, -1, Direction.SOUTH), wide) { wall(this) })
        add(Case("flat wall: face down to the floor, wide load", n(0, 3, -1, Direction.SOUTH), ground, wide) { wall(this) })

        add(Case("1-thick wall: near face", ground, n(0, 3, -1, Direction.SOUTH)) { thinWall(this) })
        add(Case("1-thick wall: top", ground, n(0, 6, 0)) { thinWall(this) })
        add(Case("1-thick wall: far face", ground, n(0, 3, 1, Direction.NORTH)) { thinWall(this) })
        add(Case("1-thick wall: far ground", ground, n(0, 1, 3)) { thinWall(this) })
        add(Case("1-thick wall: top down to far ground", n(0, 6, 0), n(0, 1, 3)) { thinWall(this) })
        add(Case("1-thick wall: near face over to far face", n(0, 3, -1, Direction.SOUTH), n(0, 3, 1, Direction.NORTH)) {
            thinWall(this)
        })

        for (width in 1..3) {
            val top = { w: NavigatorTestWorld -> w.fill(-5, 5, 1, 4, 0, width - 1) }
            add(Case("wall top width $width: top", ground, n(0, 5, 0)) { top(this) })
            add(Case("wall top width $width: far ground", ground, n(0, 1, width + 2)) { top(this) })
            add(Case("wall top width $width: step down to far ground", n(0, 5, 0), n(0, 1, width + 2)) { top(this) })
            add(Case("wall top width $width: step down to near face", n(0, 5, 0), n(0, 2, -1, Direction.SOUTH)) { top(this) })
        }

        for (k in 1..2) {
            val notch = { w: NavigatorTestWorld -> w.fill(-5, 5, k + 1, 6, 0, 1) }
            add(Case("$k-block notch: face", ground, n(0, 4, -1, Direction.SOUTH)) { notch(this) })
            add(Case("$k-block notch: top", ground, n(0, 7, 0)) { notch(this) })
            add(Case("$k-block notch: down from the face", n(0, 4, -1, Direction.SOUTH), ground) { notch(this) })
        }

        val overhang = { w: NavigatorTestWorld ->
            w.fill(-5, 5, 1, 4, 2, 3)
            w.fill(-5, 5, 5, 5, -1, 3)
        }
        add(Case("overhang: underside", ground, n(0, 4, 0, Direction.UP)) { overhang(this) })
        add(Case("overhang: top", ground, n(0, 6, 1)) { overhang(this) })
        add(Case("overhang: top down to ground", n(0, 6, 1), ground) { overhang(this) })
        add(Case("overhang: underside over the 1-tall lip to the top", n(0, 4, 0, Direction.UP), n(0, 6, 1)) { overhang(this) })
        // A 45° overhang in 1-block steps, each face cell with the next step right over it (the purpur build's
        // north face at x=-152, z=-28..-26).
        val stepped = { w: NavigatorTestWorld -> for (k in 0..4) w.fill(-5, 5, 1 + k, 1 + k, -6, k - 1) }
        val steppedGround = n(0, 1, 7)
        add(Case("stepped overhang: face under the top step", steppedGround, n(0, 4, 3, Direction.NORTH)) { stepped(this) })
        add(Case("stepped overhang: top", steppedGround, n(0, 6, 0)) { stepped(this) })
        add(Case("stepped overhang: top down to the ground", n(0, 6, 0), steppedGround) { stepped(this) })
        // A ship's mast: a 1-wide pillar 12 high under a 5x5 crow's nest.
        val mast = { w: NavigatorTestWorld ->
            w.fill(0, 0, 1, 12, 0, 0)
            w.fill(-2, 2, 13, 13, -2, 2)
        }
        add(Case("mast: face under the crow's nest", ground, n(0, 11, -1, Direction.SOUTH)) { mast(this) })
        add(Case("mast: crow's nest top", ground, n(0, 14, 0)) { mast(this) })
        // A ceiling stand spot: the carrier hangs under the nest to place cells beneath it.
        add(Case("mast: under the crow's nest", ground, n(1, 12, 1, Direction.UP)) { mast(this) })
        // A bowsprit: a 1-wide arm that climbs out from the top of a hull wall in 1-block steps.
        val arm = { w: NavigatorTestWorld ->
            w.fill(-5, 5, 1, 6, 0, 3)
            for (k in 0..5) w.fill(0, 0, 6 + k, 6 + k, -1 - k, -k)
        }
        add(Case("bowsprit: top of the tip", n(0, 7, 2), n(0, 12, -6)) { arm(this) })
        add(Case("bowsprit: under the tip", n(0, 7, 2), n(0, 10, -6, Direction.UP)) { arm(this) })
        val lip = { w: NavigatorTestWorld ->
            w.fill(-5, 5, 1, 4, 2, 3)
            w.fill(-5, 5, 5, 6, -1, 3)
        }
        add(Case("2-tall lip: lip face", ground, n(0, 5, -2, Direction.SOUTH)) { lip(this) })
        add(Case("2-tall lip: top", ground, n(0, 7, 1)) { lip(this) })
        add(Case("2-tall lip: underside round the lip to the top", n(0, 4, 0, Direction.UP), n(0, 7, 1)) { lip(this) })

        val ceiling = { w: NavigatorTestWorld ->
            for (x in intArrayOf(-4, 4)) for (z in intArrayOf(0, 8)) w.fill(x, x, 1, 4, z, z)
            w.fill(-5, 5, 5, 5, -1, 9)
        }
        add(Case("ceiling: underside", ground, n(0, 4, 4, Direction.UP)) { ceiling(this) })
        add(Case("ceiling: roof top", ground, n(0, 6, 4)) { ceiling(this) })

        val inner = { w: NavigatorTestWorld ->
            w.fill(-5, 5, 1, 5, 0, 1)
            w.fill(2, 3, 1, 5, -6, -1)
        }
        add(Case("inner corner: front face", n(-2, 1, -8), n(1, 3, -1, Direction.SOUTH)) { inner(this) })
        add(Case("inner corner: side face", n(-2, 1, -8), n(1, 3, -1, Direction.EAST)) { inner(this) })
        add(Case("inner corner: face to face on the wall", n(-2, 3, -1, Direction.SOUTH), n(1, 3, -3, Direction.EAST)) {
            inner(this)
        })

        val block = { w: NavigatorTestWorld -> w.fill(0, 3, 1, 5, 0, 3) }
        add(Case("outer corner: far face", ground, n(1, 3, 4, Direction.NORTH)) { block(this) })
        add(Case("outer corner: round the corner on the wall", n(1, 3, -1, Direction.SOUTH), n(4, 3, 1, Direction.WEST)) {
            block(this)
        })

        val doorway = { w: NavigatorTestWorld ->
            room(w)
            w.clear(0, 0, 1, 2, 0, 0)
        }
        add(Case("room: inside through the door", ground, n(0, 1, 4)) { doorway(this) })
        add(Case("room: inner face of the back wall", ground, n(0, 2, 7, Direction.SOUTH)) { doorway(this) })
        add(Case("room: inside to outside", n(0, 1, 4), ground) { doorway(this) })

        add(Case("pillar: face", ground, n(0, 3, -1, Direction.SOUTH)) { fill(0, 0, 1, 6, 0, 0) })
        add(Case("pillar: top", ground, n(0, 7, 0)) { fill(0, 0, 1, 6, 0, 0) })

        add(Case("stairs: up", n(0, 1, -4), n(0, 6, 6)) { stairs(this) })
        add(Case("stairs: down", n(0, 6, 6), n(0, 1, -4)) { stairs(this) })
        val slab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
        add(Case("slabs: across a slab row", ground, n(0, 1, 4)) { fill(-5, 5, 1, 1, -2, 0, slab) })
        add(Case("slabs: onto a slab step", ground, n(0, 2, 2)) {
            fill(-5, 5, 1, 1, -2, -2, slab)
            fill(-5, 5, 1, 1, -1, 4)
        })

        val pit = { w: NavigatorTestWorld -> w.clear(-2, 2, -3, 0, 0, 4) }
        add(Case("pit: floor", ground, n(0, -3, 2)) { pit(this) })
        add(Case("pit: wall", ground, n(0, -1, 0, Direction.NORTH)) { pit(this) })
        add(Case("pit: out", n(0, -3, 2), ground) { pit(this) })

        add(Case("tall wall: top", ground, n(0, 13, 0)) { fill(-5, 5, 1, 12, 0, 1) })

        val halfBuilt = { w: NavigatorTestWorld ->
            room(w)
            w.clear(-2, -1, 3, 4, 0, 0)
            w.clear(2, 2, 1, 4, 8, 8)
            w.fill(-4, 4, 5, 5, 0, 8)
            w.clear(-1, 0, 5, 5, 3, 4)
            w.clear(3, 3, 5, 5, 0, 8)
        }
        add(Case("half-built: inside", ground, n(0, 1, 6)) { halfBuilt(this) })
        add(Case("half-built: roof top", ground, n(0, 6, 6)) { halfBuilt(this) })
        add(Case("half-built: roof top to inside", n(-2, 6, 6), n(0, 1, 6)) { halfBuilt(this) })
    }

    @TestFactory
    fun shapes(): List<DynamicTest> = cases().map { case ->
        DynamicTest.dynamicTest(case.name) {
            NavigatorTestWorld().use { world ->
                case.build(world)
                assertRoute(world, world.navigator(*case.load), case.start, case.goal)
            }
        }
    }

    /** Spots from the 2026-11-11 playtest on the purpur build (save export, mod blocks as vanilla stand-ins). */
    @TestFactory
    fun purpurBuild(): List<DynamicTest> = listOf(
        "inside the room to the ground outside" to (BlockPos(-151, -55, -29) to BlockPos(-146, -61, -34)),
        "ground to the room floor over a 2-block notch" to (BlockPos(-151, -61, -47) to BlockPos(-152, -56, -46)),
        "south ground up under the overhang to the build" to (BlockPos(-153, -60, -22) to BlockPos(-155, -55, -35)),
        "ground to the roof" to (BlockPos(-150, -61, -38) to BlockPos(-154, -54, -32)),
        "ground up the stepped north face at x=-152" to (BlockPos(-152, -61, -25) to BlockPos(-152, -57, -26)),
    ).map { (name, ends) ->
        DynamicTest.dynamicTest(name) {
            NavigatorTestWorld(groundTop = NavigatorTestWorld.NO_GROUND).use { world ->
                world.load("/navigator/purpur_build.txt")
                val nav = world.navigator(CARRIER)
                val start = world.nodeAt(nav, ends.first)
                val goal = world.nodeAt(nav, ends.second)
                assertTrue(start != null) { "no node holds the body at start ${ends.first}" }
                assertTrue(goal != null) { "no node holds the body at goal ${ends.second}" }
                assertRoute(world, nav, start!!, goal!!)
            }
        }
    }

    /** Spots a walker parks on through a work cycle: the feet clamp a 1-wide column, walkway or mast. */
    @TestFactory
    fun workSpots(): List<DynamicTest> = listOf(
        Triple("a 1-wide column top", n(0, 6, 0)) { w: NavigatorTestWorld -> w.fill(0, 0, 1, 5, 0, 0) },
        Triple("a 1-wide walkway", n(0, 5, 0)) { w: NavigatorTestWorld ->
            w.fill(-5, 5, 4, 4, 0, 0)
            w.fill(-5, -5, 1, 3, 0, 0)
            w.fill(5, 5, 1, 3, 0, 0)
        },
        Triple("the side of a 1-wide mast", n(0, 6, -1, Direction.SOUTH)) { w: NavigatorTestWorld -> w.fill(0, 0, 1, 12, 0, 0) },
        Triple("under a crow's nest", n(1, 12, 1, Direction.UP)) { w: NavigatorTestWorld ->
            w.fill(0, 0, 1, 12, 0, 0)
            w.fill(-2, 2, 13, 13, -2, 2)
        },
    ).map { (name, node, build) ->
        DynamicTest.dynamicTest("a walker may work from $name") {
            NavigatorTestWorld().use { world ->
                build(world)
                val nav = world.navigator(CARRIER)
                assertTrue(nav.workSpotForTest(world.level, node)) { "${fmt(node)} is no work spot" }
                assertRoute(world, nav, ground, node)
            }
        }
    }

    @TestFactory
    fun steppedOverhangStepsOut(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("the stepped overhang route steps out on its face, where the hover samples ahead") {
            NavigatorTestWorld().use { world ->
                for (k in 0..4) world.fill(-5, 5, 1 + k, 1 + k, -6, k - 1)
                val nav = world.navigator(CARRIER)
                val route = nav.planForTest(world.level, n(0, 1, 7), n(0, 4, 3, Direction.NORTH))
                val text = route.joinToString(" ") { fmt(it) }
                assertTrue((1 until route.size).any { nav.isStepOut(route[it - 1], route[it]) }) { "no step out; route: $text" }
            }
        },
    )

    @TestFactory
    fun sharedRoutes(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("two walkers to spots 2-3 blocks apart each arrive on their own goal") {
            NavigatorTestWorld().use { world ->
                world.fill(1, 6, 1, 1, -4, 4)
                assertRoute(world, world.navigator(CARRIER), n(-6, 1, 0), n(0, 1, 1))
                assertRoute(world, world.navigator(CARRIER), n(-6, 1, -1), n(2, 2, 0))
                assertRoute(world, world.navigator(CARRIER), n(-6, 1, 1), n(0, 1, 3))
            }
        },
        DynamicTest.dynamicTest("a route to (-145,-61,-36) leads a second walker one step up to (-144,-60,-37)") {
            NavigatorTestWorld(groundTop = NavigatorTestWorld.NO_GROUND).use { world ->
                world.fill(-160, -145, -70, -62, -45, -28)
                world.fill(-144, -130, -70, -61, -45, -28)
                assertRoute(world, world.navigator(CARRIER), n(-152, -61, -36), n(-145, -61, -36))
                assertRoute(world, world.navigator(CARRIER), n(-152, -61, -37), n(-144, -60, -37))
            }
        },
    )

    private fun assertRoute(world: NavigatorTestWorld, nav: ConstriliarSurfaceNavigator, start: Node, goal: Node) {
        assertTrue(nav.nodeHoldsForTest(world.level, start)) { "start ${fmt(start)} does not hold the body" }
        assertTrue(nav.nodeHoldsForTest(world.level, goal)) { "goal ${fmt(goal)} does not hold the body" }
        val route = nav.planForTest(world.level, start, goal)
        val text = route.joinToString(" ") { fmt(it) }
        assertTrue(route.isNotEmpty() && route.last().pos == goal.pos && nav.routeWhole) {
            "no whole route to ${fmt(goal)}; got: $text"
        }
        for (node in route) {
            assertTrue(nav.nodeHoldsForTest(world.level, node)) { "route node ${fmt(node)} fails the fit/stance test; route: $text" }
        }
        for (k in 1 until route.size) {
            val a = route[k - 1]
            val b = route[k]
            if (a.face == b.face) continue
            assertTrue(nav.turnKind(a, b) != ConstriliarSurfaceNavigator.TURN_NONE) {
                "the gait has no turn from ${fmt(a)} to ${fmt(b)}; route: $text"
            }
        }
        assertAttitudeContinuous(world, nav, route, text)
    }

    /**
     * Walks the body from node centre to node centre at [WALK_STEP] a tick. The real follower and turn arcs give the
     * attitude target; the gait's rate cap turns the up toward it. The up must never turn faster than the cap, and the
     * body must end upright on the goal's face.
     */
    private fun assertAttitudeContinuous(world: NavigatorTestWorld, nav: ConstriliarSurfaceNavigator, route: List<Node>, text: String) {
        nav.followForTest(route, 0)
        val up = nav.nodeWorldUp(world.level, route[0], Vector3d())
        val target = Vector3d()
        val before = Vector3d()
        val travel = Vector3d()
        var worstJump = 0.0
        fun tick(body: Vec3) {
            val node = nav.tickFollow(world.level, body, up, transitioning = true) ?: route.last()
            if (!nav.transitionUp(world.level, body, target)) nav.nodeWorldUp(world.level, node, target)
            worstJump = maxOf(worstJump, Math.acos(target.dot(up).coerceIn(-1.0, 1.0)))
            before.set(up)
            turnUpToward(up, target, ATTITUDE_MAX_STEP, travel)
            val turned = Math.acos(before.dot(up).coerceIn(-1.0, 1.0))
            assertTrue(turned <= ATTITUDE_MAX_STEP + 1.0e-9) {
                "the up turned ${Math.toDegrees(turned)}° in one tick at $body (node ${nav.currentNode()}); route: $text"
            }
        }
        for (k in 1 until route.size) {
            val from = Vec3.atCenterOf(route[k - 1].pos)
            val to = Vec3.atCenterOf(route[k].pos)
            travel.set(to.x - from.x, to.y - from.y, to.z - from.z)
            val steps = maxOf(1, Math.ceil(from.distanceTo(to) / WALK_STEP).toInt())
            for (i in 1..steps) tick(from.lerp(to, i.toDouble() / steps))
        }
        repeat(SETTLE_TICKS) { tick(Vec3.atCenterOf(route.last().pos)) }
        val goalUp = nav.nodeWorldUp(world.level, route.last(), Vector3d())
        assertTrue(up.dot(goalUp) > SETTLED_DOT) {
            "the up $up did not settle on the goal face $goalUp (largest target jump ${Math.toDegrees(worstJump)}°); route: $text"
        }
    }

    private fun fmt(n: Node) = "${n.pos.x},${n.pos.y},${n.pos.z}/${n.face.name.lowercase()}"

    private companion object {
        /** Body travel per game tick (blocks): 2 m/s. */
        const val WALK_STEP = 0.1
        /** Ticks at the goal: a half turn at the rate cap takes 13. */
        const val SETTLE_TICKS = 20
        const val SETTLED_DOT = 0.999
    }
}
