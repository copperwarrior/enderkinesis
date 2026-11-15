package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test

class ConstriliarPathShareTest {

    private fun route(end: BlockPos, anchor: Vec3?): ConstriliarPathShare.Route {
        val nodes = listOf(
            ConstriliarSurfaceNavigator.Node(BlockPos(-150, -61, -37), Direction.DOWN),
            ConstriliarSurfaceNavigator.Node(end, Direction.DOWN),
        )
        return ConstriliarPathShare.Route(nodes, Vec3.atCenterOf(end), 0L, 1L, anchor)
    }

    @Test
    fun `a shared route finishes a search only on its goal or for the same order`() {
        val goal = BlockPos(-144, -60, -37)
        // The playtest case: a route to the next supply spot ended two blocks short of this goal.
        val near = route(BlockPos(-146, -61, -37), Vec3(-144.5, -61.0, -35.5))
        ConstriliarPathShare.arrives(near, goal, Vec3(-143.5, -60.0, -36.5)) shouldBe false
        ConstriliarPathShare.arrives(route(goal, null), goal, null) shouldBe true
        ConstriliarPathShare.arrives(near, goal, Vec3(-144.5, -61.0, -35.8)) shouldBe true
    }
}
