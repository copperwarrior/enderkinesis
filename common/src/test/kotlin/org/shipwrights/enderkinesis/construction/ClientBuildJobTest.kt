package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientBuildJobTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun job(): ClientBuildJob {
        val ref = PlanRef(UUID(0, 1), "a".repeat(PlanRef.HASH_LENGTH), "plan", Vec3i(48, 16, 16))
        return ClientBuildJob(UUID(0, 2), ref, PlanAnchor(Level.OVERWORLD, BlockPos.ZERO, Rotation.NONE, Mirror.NONE))
    }

    @Test
    fun `a word index outside the grid is skipped`() {
        val job = job()
        job.setWord(-1, -1L)
        job.setWord(Int.MAX_VALUE, -1L)
        job.setWord(1, -1L)
        job.completeSections shouldBe 0
    }

    @Test
    fun `a word in the grid marks only its sections`() {
        val job = job()
        job.setWord(0, -1L)
        job.completeSections shouldBe 3
        job.isSectionDone(2, 0, 0) shouldBe true
    }

    @Test
    fun `a delta bumps the section version only when a status changes`() {
        val job = job()
        val todo = BuildCellStatus.TODO.ordinal.toByte()
        val done = BuildCellStatus.DONE.ordinal.toByte()
        job.setCells(0, shortArrayOf(5), byteArrayOf(todo))
        job.sectionVersion(0, 0, 0) shouldBe 0
        job.setCells(0, shortArrayOf(5), byteArrayOf(done))
        job.sectionVersion(0, 0, 0) shouldBe 1
        job.setCells(0, shortArrayOf(5), byteArrayOf(done))
        job.sectionVersion(0, 0, 0) shouldBe 1
        job.status(0, 0, 0, 5) shouldBe BuildCellStatus.DONE
    }
}
