package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.core.Direction
import org.joml.Matrix4d
import org.joml.Vector3d
import org.junit.jupiter.api.Test

class ClawhodFrameTest {

    private fun facing(facing: Direction, shipToWorld: Matrix4d?) = Vector3d().setClawhodFacingWorld(facing, shipToWorld)

    private fun Vector3d.shouldBeNear(x: Double, y: Double, z: Double) {
        this.x shouldBe (x plusOrMinus EPS)
        this.y shouldBe (y plusOrMinus EPS)
        this.z shouldBe (z plusOrMinus EPS)
    }

    @Test
    fun `off a ship the facing is the block direction`() {
        facing(Direction.EAST, null).shouldBeNear(1.0, 0.0, 0.0)
    }

    @Test
    fun `the facing turns with the ship yaw each time it is read`() {
        val ship = Matrix4d().translation(100.0, 64.0, -30.0)
        facing(Direction.SOUTH, ship).shouldBeNear(0.0, 0.0, 1.0)
        ship.rotateY(Math.PI / 2)
        facing(Direction.SOUTH, ship).shouldBeNear(1.0, 0.0, 0.0)
    }

    @Test
    fun `an up column tilts with the ship pitch and roll`() {
        facing(Direction.UP, Matrix4d().rotateX(Math.PI / 2)).shouldBeNear(0.0, 0.0, 1.0)
        facing(Direction.UP, Matrix4d().rotateZ(Math.PI / 2)).shouldBeNear(-1.0, 0.0, 0.0)
    }

    @Test
    fun `a scaled ship still gives a unit facing`() {
        facing(Direction.NORTH, Matrix4d().translation(5.0, 0.0, 5.0).scale(0.5)).shouldBeNear(0.0, 0.0, -1.0)
    }

    private companion object {
        const val EPS = 1.0e-9
    }
}
