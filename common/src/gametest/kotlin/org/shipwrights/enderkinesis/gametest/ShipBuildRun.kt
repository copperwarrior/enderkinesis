package org.shipwrights.enderkinesis.gametest

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.blockentity.DraftingTableBlockEntity
import org.shipwrights.enderkinesis.construction.BuildJob
import org.shipwrights.enderkinesis.construction.BuildJobs
import org.shipwrights.enderkinesis.construction.PlanRef
import org.shipwrights.enderkinesis.construction.PlanStorage
import org.shipwrights.enderkinesis.construction.TABLE_SUPPLY_RADIUS
import org.shipwrights.enderkinesis.construction.TablePlacement
import org.shipwrights.enderkinesis.construction.buildAt
import org.shipwrights.enderkinesis.construction.choosePlan
import org.shipwrights.enderkinesis.construction.placePlan
import org.shipwrights.enderkinesis.construction.summary
import org.shipwrights.enderkinesis.construction.tableRefusal
import org.shipwrights.enderkinesis.entity.ConstriliarEntity
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.IShipObjectWorldServerProvider

/**
 * One run of the ship build test. Every step goes through the code a player's click runs: item use for the table and
 * the Scroll of Animation, the entity click for the Clawhod, the sketchbook pick, the placement screen and Build.
 */
internal class ShipBuildRun(private val helper: GameTestHelper) {
    private val level: ServerLevel = helper.level
    private val logTail = BuildLogTail.install()
    private val tablePos = helper.absolutePos(Yard.TABLE)
    private val placement = TablePlacement(Yard.PLAN_OFFSET, Rotation.NONE, Mirror.NONE)
    private lateinit var builder: MockBuilder
    private lateinit var plan: PlanRef
    private lateinit var walker: ConstriliarEntity
    private var job: BuildJob? = null
    private var buildStartTick = 0L
    private var ended: String? = null
    private var watchdog: Thread? = null

    // One instance: the wait throws it each tick, and the message is built only when the test reports it.
    private val stillRunning = object : GameTestAssertException("the build is still running") {
        override val message: String get() = "the build did not finish in $BUILD_TIME_LIMIT_TICKS ticks\n${report()}"
    }

    // GameTest steps run on the server thread.
    @OptIn(GameTickOnly::class)
    fun setUp() {
        // VS2 falls back to a dummy ship world when its pipeline did not start; only the server's own field tells.
        if ((level.server as? IShipObjectWorldServerProvider)?.shipObjectWorld == null) {
            gameTestFail("VS2 did not start its ship world on the GameTest server")
        }
        for (x in 0 until Yard.SIZE_X) for (z in 0 until Yard.SIZE_Z) helper.setBlock(x, Yard.FLOOR_Y, z, Blocks.STONE)
        startWatchdog()
        builder = MockBuilder.join(level, helper.absoluteVec(Yard.BUILDER))
        builder.useItemOn(EKItems.DRAFTING_TABLE_ITEM.get(), tablePos.below(), Direction.UP)
        if (table() == null) gameTestFail("the drafting table item placed no table at ${tablePos.toShortString()}")
        plan = importPlan(level.server, builder.player, Yard.PLAN_ID)
        loadPlan()
        stockSupplies()
        walker = animateWalker()
    }

    private fun startWatchdog() {
        val serverThread = Thread.currentThread()
        val watchdog = Thread({
            try {
                Thread.sleep(WALL_CLOCK_LIMIT_SECONDS * MILLIS_PER_SECOND)
            } catch (_: InterruptedException) {
                return@Thread
            }
            val stack = serverThread.stackTrace.joinToString("\n  ") { it.toString() }
            LOG.error("[EkGameTest] no result after {} s; server thread:\n  {}", WALL_CLOCK_LIMIT_SECONDS, stack)
            Runtime.getRuntime().halt(WATCHDOG_EXIT_CODE)
        }, "ek-gametest-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
        this.watchdog = watchdog
    }

    private fun table(): DraftingTableBlockEntity? = level.getBlockEntity(tablePos) as? DraftingTableBlockEntity

    private fun loadPlan() {
        level.choosePlan(builder.player, tablePos, plan)
        level.placePlan(builder.player, tablePos, placement)
        val table = table() ?: gameTestFail("the drafting table is gone")
        if (table.plan?.hash != plan.hash) gameTestFail("the sketchbook pick did not load ${plan.name}")
        if (table.placement != placement) {
            gameTestFail("the placement screen refused $placement: ${level.tableRefusal(tablePos, plan.size, placement)}")
        }
        val box = placement.box(tablePos, plan.size)
        val yard = BoundingBox.fromCorners(
            helper.absolutePos(BlockPos(0, Yard.FLOOR_Y + 1, 0)),
            helper.absolutePos(BlockPos(Yard.SIZE_X - 1, Yard.SIZE_Y, Yard.SIZE_Z - 1)),
        )
        val clear = listOf(tablePos, helper.absolutePos(Yard.WALKER_BODY), BlockPos.containing(helper.absoluteVec(Yard.BUILDER)))
        if (!yard.isInside(BlockPos(box.minX(), box.minY(), box.minZ())) || !yard.isInside(BlockPos(box.maxX(), box.maxY(), box.maxZ())) ||
            clear.any { box.isInside(it) }
        ) {
            gameTestFail("the yard layout does not fit the plan box $box (size ${plan.size})")
        }
    }

    private fun stockSupplies() {
        val sections = PlanStorage.sections(level.server, plan) ?: gameTestFail("the stored plan has no sections")
        val materials = planMaterials(sections)
        if (materials.refused.isNotEmpty()) gameTestFail("no build can place these plan cells: ${materials.refused}")
        val stacks = materials.stacks()
        val tableMiddle = Vec3.atCenterOf(tablePos)
        val cells = Yard.SUPPLY_Z.map { helper.absolutePos(BlockPos(Yard.SUPPLY_X, Yard.TABLE.y, it)) }
            .filter { Vec3.atCenterOf(it).distanceTo(tableMiddle) <= TABLE_SUPPLY_RADIUS }
        var next = 0
        for (cell in cells) {
            if (next == stacks.size) break
            level.setBlockAndUpdate(cell, Blocks.CHEST.defaultBlockState())
            val chest = level.getBlockEntity(cell) as? ChestBlockEntity ?: gameTestFail("no chest at ${cell.toShortString()}")
            for (slot in 0 until chest.containerSize) {
                if (next == stacks.size) break
                chest.setItem(slot, stacks[next++])
            }
        }
        if (next < stacks.size) gameTestFail("the ${cells.size} supply chests hold ${next} of ${stacks.size} stacks")
        LOG.info("[EkGameTest] stocked {} stacks for {}: {}", stacks.size, plan.name, materials.items)
    }

    private fun animateWalker(): ConstriliarEntity {
        val body = helper.absolutePos(Yard.WALKER_BODY)
        level.setBlockAndUpdate(body, Blocks.PURPUR_BLOCK.defaultBlockState())
        val result = builder.useItemOn(EKItems.SCROLL_OF_ANIMATION.get(), body, Direction.UP)
        if (!result.consumesAction()) gameTestFail("the Scroll of Animation did not animate the purpur block ($result)")
        return level.getEntitiesOfClass(ConstriliarEntity::class.java, AABB(body).inflate(1.0)).firstOrNull()
            ?: gameTestFail("no Constriliar woke at ${body.toShortString()}")
    }

    fun awaitWalkerShip() {
        if (!walker.hasBackShip()) throw GameTestAssertException("the walker has no VS2 back ship yet")
    }

    fun startBuild() = asTestFailure("startBuild") {
        val mounted = builder.interactWith(walker, EKItems.CLAWHOD_ITEM.get())
        if (!mounted.consumesAction()) gameTestFail("the walker did not take the Clawhod ($mounted)")
        level.buildAt(builder.player, tablePos)
        val data = BuildJobs.get(level)
        val started = data.jobAt(tablePos)
            ?: gameTestFail("Build started no job: refusal=${level.tableRefusal(tablePos, plan.size, placement)}")
        val tableId = table()?.tableId ?: gameTestFail("the drafting table is gone")
        if (data.crewOf(tableId).isEmpty()) gameTestFail("Build recruited no Clawhod\n${report()}")
        buildStartTick = helper.tick
        job = started
        LOG.info("[EkGameTest] build started at tick {}: {}", buildStartTick, started.summary())
    }

    /** Waits on the job's own completion; a job that leaves the job list unfinished, or a lost walker, ends the wait. */
    fun awaitBuildEnd() {
        val job = job ?: return
        try {
            when {
                job.isFinished -> return
                BuildJobs.get(level).job(job.id) !== job -> {
                    ended = "the build job ended before it finished: ${logTail?.lastJobEnd()}"
                    return
                }
                !walker.isAlive -> {
                    ended = "the walker is gone (${walker.removalReason})"
                    return
                }
            }
        } catch (e: RuntimeException) {
            ended = "the build check threw $e"
            return
        }
        throw stillRunning
    }

    fun verify() = asTestFailure("verify") {
        val job = job ?: return@asTestFailure
        try {
            ended?.let { gameTestFail("$it\n${report()}") }
            val sections = PlanStorage.sections(level.server, plan) ?: gameTestFail("the stored plan has no sections")
            val cells = checkPlanCells(level, sections, job.anchor)
            if (cells.wrong > 0) gameTestFail("the job reports complete, but the world does not match the plan\n${report()}")
            LOG.info("[EkGameTest] {} built in {} ticks", plan.name, helper.tick - buildStartTick)
        } finally {
            watchdog?.interrupt()
            logTail?.uninstall()
            builder.leave()
        }
    }

    private fun report(): String = buildString {
        appendLine("elapsed: ${helper.tick - buildStartTick} build ticks, limit $BUILD_TIME_LIMIT_TICKS")
        val job = job
        if (job != null) appendLine("job: ${job.summary()} complete sections=${job.progress.completeCount}/${job.grid.count}")
        appendLine(
            "walker: at ${walker.blockPosition().toShortString()} alive=${walker.isAlive} " +
                "backShip=${walker.hasBackShip()} sitting=${walker.isSitting()}",
        )
        val sections = PlanStorage.sections(level.server, plan)
        if (job != null && sections != null) append(checkPlanCells(level, sections, job.anchor).describe(sections))
        append(logTail?.describe() ?: "no log capture: the logging back end is not log4j core\n")
    }

    // A sequence step catches only GameTestAssertException; any other exception escapes into the server tick.
    private inline fun asTestFailure(step: String, body: () -> Unit) {
        try {
            body()
        } catch (e: GameTestAssertException) {
            throw e
        } catch (e: RuntimeException) {
            LOG.error("[EkGameTest] {} threw", step, e)
            gameTestFail("$step threw $e")
        }
    }

    private companion object {
        val LOG = LogUtils.getLogger()
        const val MILLIS_PER_SECOND = 1000L
        const val WATCHDOG_EXIT_CODE = 3
    }
}
