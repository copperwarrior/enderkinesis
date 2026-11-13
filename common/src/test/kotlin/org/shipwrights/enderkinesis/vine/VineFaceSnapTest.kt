package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VineFaceSnapTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun Vec3.shouldBeAt(x: Double, y: Double, z: Double) {
        this.x shouldBe (x plusOrMinus 1.0e-9)
        this.y shouldBe (y plusOrMinus 1.0e-9)
        this.z shouldBe (z plusOrMinus 1.0e-9)
    }

    private val cube = listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))

    @Test
    fun `full cube face snaps to the corner, edge middle, centre and quadrant points`() {
        snapToFaceAnchor(cube, Direction.UP, Vec3(0.05, 1.0, 0.9)).shouldBeAt(0.0, 1.0, 1.0)
        snapToFaceAnchor(cube, Direction.UP, Vec3(0.48, 1.0, 0.03)).shouldBeAt(0.5, 1.0, 0.0)
        snapToFaceAnchor(cube, Direction.UP, Vec3(0.55, 1.0, 0.45)).shouldBeAt(0.5, 1.0, 0.5)
        snapToFaceAnchor(cube, Direction.UP, Vec3(0.3, 1.0, 0.8)).shouldBeAt(0.25, 1.0, 0.75)
        snapToFaceAnchor(cube, Direction.NORTH, Vec3(0.7, 0.2, 0.0)).shouldBeAt(0.75, 0.25, 0.0)
        snapToFaceAnchor(cube, Direction.EAST, Vec3(1.0, 0.98, 0.52)).shouldBeAt(1.0, 1.0, 0.5)
    }

    @Test
    fun `slab top puts the points on the slab top`() {
        val slab = listOf(AABB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0))
        snapToFaceAnchor(slab, Direction.UP, Vec3(0.8, 0.5, 0.3)).shouldBeAt(0.75, 0.5, 0.25)
        snapToFaceAnchor(slab, Direction.SOUTH, Vec3(0.5, 0.45, 1.0)).shouldBeAt(0.5, 0.5, 1.0)
        snapToFaceAnchor(slab, Direction.SOUTH, Vec3(0.3, 0.15, 1.0)).shouldBeAt(0.25, 0.125, 1.0)
    }

    @Test
    fun `thin post uses its own box and the box that holds the hit`() {
        val post = AABB(0.375, 0.0, 0.375, 0.625, 1.0, 0.625)
        val bar = AABB(0.0, 0.375, 0.4375, 1.0, 0.5625, 0.5625)
        val fence = listOf(post, bar)
        snapToFaceAnchor(fence, Direction.UP, Vec3(0.6, 1.0, 0.4)).shouldBeAt(0.625, 1.0, 0.375)
        snapToFaceAnchor(fence, Direction.UP, Vec3(0.5, 1.0, 0.5)).shouldBeAt(0.5, 1.0, 0.5)
        snapToFaceAnchor(fence, Direction.UP, Vec3(0.1, 0.5625, 0.5)).shouldBeAt(0.0, 0.5625, 0.5)
        snapToFaceAnchor(fence, Direction.UP, Vec3(0.8, 0.5625, 0.45)).shouldBeAt(0.75, 0.5625, 0.46875)
    }

    @Test
    fun `empty shape keeps the hit`() {
        snapToFaceAnchor(emptyList(), Direction.UP, Vec3(0.3, 0.7, 0.1)).shouldBeAt(0.3, 0.7, 0.1)
    }
}
