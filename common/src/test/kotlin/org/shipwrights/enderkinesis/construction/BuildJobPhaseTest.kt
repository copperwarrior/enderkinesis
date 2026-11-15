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
class BuildJobPhaseTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun job(): BuildJob {
        val ref = PlanRef(UUID(0, 1), "a".repeat(PlanRef.HASH_LENGTH), "plan", Vec3i(48, 32, 16))
        val anchor = PlanAnchor(Level.OVERWORLD, BlockPos.ZERO, Rotation.NONE, Mirror.NONE)
        return BuildJob(UUID(0, 2), UUID(0, 3), BlockPos(0, -1, 0), UUID(0, 4), ref, anchor, ArrayList())
    }

    @Test
    fun `a new job starts in the whole-plan CLEAR phase and keeps it through a save`() {
        val job = job()
        job.clearing shouldBe true
        checkNotNull(BuildJob.load(job.save())).clearing shouldBe true
    }

    @Test
    fun `a saved job past CLEAR loads in the build phase`() {
        val tag = job().save().also { it.putBoolean("Clearing", false) }
        checkNotNull(BuildJob.load(tag)).clearing shouldBe false
    }
}
