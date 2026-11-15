package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.shipwrights.enderkinesis.entity.ConstriliarSurfaceNavigator.Node
import org.shipwrights.enderkinesis.entity.NavigatorTestWorld.Companion.CARRIER

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NavigatorGaitAgreementTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun n(x: Int, y: Int, z: Int, face: Direction = Direction.DOWN) = Node(BlockPos(x, y, z), face)

    /** Over a 1-thick wall (z = 0, top at y = 5): up the near face, on the top, down the far face. */
    private val fold = listOf(
        n(0, 4, -1, Direction.SOUTH), n(0, 5, -1, Direction.SOUTH), n(0, 6, 0), n(0, 5, 1, Direction.NORTH),
        n(0, 4, 1, Direction.NORTH),
    )

    private fun thinWallWorld() = NavigatorTestWorld().apply { fill(-5, 5, 1, 5, 0, 0) }

    @Test
    fun `a wide turn and a convex wrap are told apart`() {
        val nav = NavigatorTestWorld().use { it.navigator(CARRIER) }
        nav.turnKind(n(0, 1, -2), n(0, 2, -1, Direction.SOUTH)) shouldBe ConstriliarSurfaceNavigator.TURN_WIDE_CONCAVE
        nav.turnKind(n(0, 2, -1, Direction.SOUTH), n(0, 1, -2)) shouldBe ConstriliarSurfaceNavigator.TURN_WIDE_CONCAVE
        nav.turnKind(n(0, 6, 0), n(0, 5, 1, Direction.NORTH)) shouldBe ConstriliarSurfaceNavigator.TURN_CONVEX
        nav.turnKind(n(0, 1, -1), n(0, 1, -1, Direction.SOUTH)) shouldBe ConstriliarSurfaceNavigator.TURN_CONCAVE
    }

    @Test
    fun `the wide turn's arc goes from the floor's up to the wall's up`() {
        val out = Vector3d()
        // Corner cell (0, 1, -1) between floor and wall (z = 0); the body starts a cell back, ends a cell up.
        ConstriliarSurfaceNavigator.edgeArcUp(0, 1, -1, Direction.DOWN, Direction.SOUTH, true, Vec3(0.5, 1.75, -1.5), out)
        out.y shouldBe (1.0 plusOrMinus 1.0e-9)
        ConstriliarSurfaceNavigator.edgeArcUp(0, 1, -1, Direction.DOWN, Direction.SOUTH, true, Vec3(0.5, 2.5, -0.25), out)
        out.z shouldBe (-1.0 plusOrMinus 1.0e-9)
    }

    @Test
    fun `on a fold's face the grab goes on to the face past it`() {
        thinWallWorld().use { world -> grabGoesPastFold(world) }
    }

    private fun grabGoesPastFold(world: NavigatorTestWorld) {
        val nav = world.navigator(CARRIER)
        nav.followForTest(fold, 2)
        val normal = Vector3d()
        val body = Vec3(0.5, 6.25, 0.5)
        // Still turning onto the top: the grab is for the top itself.
        nav.reachAhead(world.level, body, Vector3d(0.0, 0.0, -1.0), normal, Vector3d(), Vector3d()) shouldBe true
        normal.y shouldBe (1.0 plusOrMinus 1.0e-9)
        // Upright on the top: the grab is for the far face.
        nav.reachAhead(world.level, body, Vector3d(0.0, 1.0, 0.0), normal, Vector3d(), Vector3d()) shouldBe true
        normal.z shouldBe (1.0 plusOrMinus 1.0e-9)
    }

    @Test
    fun `the fold gate's wait is no stall until its limit, then it is`() {
        thinWallWorld().use { world -> foldWaitIsBounded(world) }
    }

    private fun foldWaitIsBounded(world: NavigatorTestWorld) {
        val nav = world.navigator(CARRIER)
        nav.followForTest(fold, 2)
        val body = Vec3(0.5, 6.25, 0.5)
        // Tilted back toward the near face: the gate holds the body on the top node.
        val tilted = Vector3d(0.0, 0.5, -1.0).normalize()
        repeat(STALL_TICKS + 20) { nav.tickFollow(world.level, body, tilted) }
        nav.isInProgress() shouldBe true
        repeat(HOLD_LIMIT_TICKS + STALL_TICKS) { nav.tickFollow(world.level, body, tilted) }
        nav.isInProgress() shouldBe false
    }

    private companion object {
        const val STALL_TICKS = 41
        const val HOLD_LIMIT_TICKS = 100
    }
}
