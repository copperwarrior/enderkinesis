package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BucketPickup
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.shapes.CollisionContext

private const val PLACE_FLAGS = 3

private inline fun BuildJob.say(worker: BuildWorker, why: Enum<*>, detail: () -> String) =
    worker.buildNote.report(this, id, worker.workerId, "WORK", why, 0, detail)

/** Re-checks the cell, then places it from the worker's held stack for [actor]. The caller holds the reservation. */
internal fun BuildJob.place(level: ServerLevel, actor: ServerPlayer, worker: BuildWorker, task: BuildTask): PlaceResult {
    if (!isReservedBy(task.cell, worker.workerId)) return PlaceResult.BLOCKED
    val cost = checkNotNull(task.cost) { "a PLACE task has a cost" }
    return withLocal(task.cell) { s, l ->
        val c = s.cellAt(l)
        if (c < 0) null else placeCell(level, actor, worker, task, cost, s, c)
    } ?: PlaceResult.BLOCKED.also {
        say(worker, WorkerWhy.SECTION_RETIRED) { "section of cell ${task.pos.toShortString()} left the work set; the cell is dropped" }
        release(task.cell, worker.workerId)
    }
}

@Suppress("ReturnCount") // One return per re-check reads clearer than nested branches.
private fun BuildJob.placeCell(
    level: ServerLevel, actor: ServerPlayer, worker: BuildWorker, task: BuildTask, cost: PlanCost.Items,
    s: BuildSection, cell: Int,
): PlaceResult {
    val pos = task.pos
    if (!level.isLoaded(pos)) {
        say(worker, WorkerWhy.PLACE_WAIT_CHUNK) { "chunk of ${pos.toShortString()} is not loaded; the worker waits" }
        return PlaceResult.NOT_NOW
    }
    val here = level.getBlockState(pos)
    if (planMatches(task.state, here)) {
        if (s.markDone(cell)) saveDirty = true
        release(task.cell, worker.workerId)
        return PlaceResult.BLOCKED
    }
    val refused = when {
        !here.isReplaceable() -> WorkerWhy.PLACE_NOT_REPLACEABLE
        !partnerFree(level, actor, s, cell) -> WorkerWhy.PLACE_PARTNER_BLOCKED
        !mayBuild(level, actor, pos, task.state) -> WorkerWhy.PLACE_REFUSED
        else -> null
    }
    if (refused != null) {
        say(worker, refused) {
            "cell ${pos.toShortString()} blocked: ${BuildLog.blockName(here)} there; ${BuildLog.permission(level, actor, pos, task.state, null)}"
        }
        return blockCell(s, cell, task, worker, CellStatus.BLOCKED)
    }
    if (!canStand(level, task.state, pos)) {
        say(worker, WorkerWhy.CANNOT_STAND) { "${BuildLog.blockName(task.state)} at ${pos.toShortString()} cannot stand yet; deferred" }
        return blockCell(s, cell, task, worker, CellStatus.DEFERRED)
    }
    if (!worker.holdsAll(cost)) {
        say(worker, WorkerWhy.HELD_MISMATCH) {
            "worker holds ${worker.heldBlock()} x${worker.heldCount()}, the cell needs ${BuildLog.costName(cost)}; cell dropped"
        }
        release(task.cell, worker.workerId)
        return PlaceResult.BLOCKED
    }
    // An entity in the cell: try it again on a later pass, never wait on the spot.
    if (!level.isUnobstructed(task.state, pos, CollisionContext.empty())) {
        say(worker, WorkerWhy.ENTITY_IN_CELL) { "an entity stands in ${pos.toShortString()}; deferred to a later pass" }
        return blockCell(s, cell, task, worker, CellStatus.DEFERRED)
    }
    checkPaid(task.state, cost)
    // A protection mod can refuse the place; the worker then keeps the block.
    if (!level.setBlock(pos, task.state, PLACE_FLAGS)) {
        say(worker, WorkerWhy.SET_BLOCK_FAILED) { "setBlock refused at ${pos.toShortString()} (a mod cancelled the place)" }
        return blockCell(s, cell, task, worker, CellStatus.BLOCKED)
    }
    worker.takeAll(cost)
    if (s.partnerPos[cell] != NO_POS) task.state.block.setPlacedBy(level, pos, task.state, actor, cost.stack())
    readPower(level, pos)
    placeEffects(level, pos, task.state)
    s.markDone(cell)
    saveDirty = true
    s.stalled = false
    release(task.cell, worker.workerId)
    return PlaceResult.PLACED
}

private fun BuildWorker.holdsAll(cost: PlanCost.Items): Boolean {
    for (i in 0 until cost.size) if (countOf(cost.itemAt(i)) < cost.countAt(i)) return false
    return true
}

private fun BuildWorker.takeAll(cost: PlanCost.Items) {
    for (i in 0 until cost.size) take(cost.itemAt(i), cost.countAt(i))
}

/** Turns the right block to its plan state, at no cost. */
internal fun BuildJob.fix(level: ServerLevel, actor: ServerPlayer, worker: BuildWorker, task: BuildTask) {
    if (!isReservedBy(task.cell, worker.workerId)) return
    withLocal(task.cell) { s, l ->
        val c = s.cellAt(l)
        val pos = task.pos
        val here = level.getBlockState(pos)
        if (c >= 0 && level.isLoaded(pos) && fixableInPlace(task.state, here)) {
            val fixed = fixedState(task.state, here)
            if (mayBuild(level, actor, pos, fixed) && fixed.canSurvive(level, pos) && level.setBlock(pos, fixed, PLACE_FLAGS)) {
                readPower(level, pos)
                placeEffects(level, pos, fixed)
                s.markDone(c)
                saveDirty = true
                s.stalled = false
            } else {
                say(worker, WorkerWhy.FIX_REFUSED) {
                    "fix at ${pos.toShortString()} refused: ${BuildLog.permission(level, actor, pos, fixed, null)}"
                }
                s.setStatus(c, CellStatus.BLOCKED)
            }
        }
    }
    release(task.cell, worker.workerId)
}

/**
 * CLEAR: cuts the world block for [actor]. Loot is rolled with the worker's tool, or as a bare hand that may harvest
 * anything; it floats at the cell until a hand takes it (the user's rule).
 */
internal fun BuildJob.cut(level: ServerLevel, actor: ServerPlayer, worker: BuildWorker, task: BuildTask): CutResult {
    if (!isReservedBy(task.cell, worker.workerId)) return CutResult.DROPPED
    val pos = task.pos
    if (!level.isLoaded(pos)) {
        say(worker, WorkerWhy.CUT_WAIT_CHUNK) { "chunk of ${pos.toShortString()} is not loaded; the worker waits" }
        return CutResult.NOT_NOW
    }
    val result = withLocal(task.cell) { s, l -> cutCell(level, actor, worker, pos, s, l) } ?: CutResult.DROPPED.also {
        say(worker, WorkerWhy.SECTION_RETIRED) { "section of cell ${pos.toShortString()} left the work set; the cut is dropped" }
    }
    if (result != CutResult.NOT_NOW) release(task.cell, worker.workerId)
    return result
}

@Suppress("ReturnCount") // One return per re-check reads clearer than nested branches.
private fun BuildJob.cutCell(
    level: ServerLevel, actor: ServerPlayer, worker: BuildWorker, pos: BlockPos, s: BuildSection, l: Int,
): CutResult {
    val c = s.cellAt(l)
    val world = level.getBlockState(pos)
    if (clearKind(if (c >= 0) s.states[c] else null, world) != ClearKind.CUT) {
        say(worker, WorkerWhy.CUT_NOTHING) { "nothing to cut at ${pos.toShortString()} any more (${BuildLog.blockName(world)})" }
        return CutResult.DROPPED
    }
    if (clearRefused(level, actor, pos, world, ClearKind.CUT)) {
        say(worker, WorkerWhy.CUT_REFUSED) { "cut of ${BuildLog.blockName(world)} at ${pos.toShortString()} refused: ${clearWhy(level, actor, pos, world)}" }
        s.setLocalStatus(l, CellStatus.BLOCKED)
        return CutResult.DROPPED
    }
    val other = world.otherHalf(pos)
    val otherState = other?.let(level::getBlockState)?.takeIf { it.block == world.block }
    val tool = worker.tool()
    val drops = ArrayList(Block.getDrops(world, level, pos, level.getBlockEntity(pos), actor, tool))
    if (other != null && otherState != null) {
        drops += Block.getDrops(otherState, level, other, level.getBlockEntity(other), actor, tool)
    }
    level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(world))
    level.removeBlock(pos, false)
    if (other != null && otherState != null && level.getBlockState(other).block == world.block) level.removeBlock(other, false)
    level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(world))
    val toolItem = tool.item
    if (worker.wearTool(level, pos, world)) {
        say(worker, WorkerWhy.TOOL_BROKE) { "the ${BuildLog.itemName(toolItem)} broke on ${BuildLog.blockName(world)} at ${pos.toShortString()}" }
    }
    for (stack in drops) popFloating(level, pos, stack)
    s.setLocalStatus(l, CellStatus.TODO)
    s.progressed()
    s.stalled = false
    return CutResult.CUT
}

/** CLEAR: scoops the fluid source with [bucket]; returns the filled bucket, or [bucket] when nothing was scooped. */
internal fun BuildJob.scoop(
    level: ServerLevel, actor: ServerPlayer, worker: BuildWorker, task: BuildTask, bucket: ItemStack,
): ItemStack {
    if (!isReservedBy(task.cell, worker.workerId)) return bucket
    val filled = withLocal(task.cell) { s, l ->
        val pos = task.pos
        val world = level.getBlockState(pos)
        val pickup = world.block as? BucketPickup
        val c = s.cellAt(l)
        when {
            !level.isLoaded(pos) || pickup == null ||
                clearKind(if (c >= 0) s.states[c] else null, world) != ClearKind.SCOOP -> null
            clearRefused(level, actor, pos, world, ClearKind.SCOOP) -> null.also { s.setLocalStatus(l, CellStatus.BLOCKED) }
            else -> pickup.pickupBlock(level, pos, world).takeIf { !it.isEmpty }?.also {
                pickup.pickupSound.ifPresent { sound -> level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f) }
                level.gameEvent(GameEvent.FLUID_PICKUP, pos, GameEvent.Context.of(world))
                s.setLocalStatus(l, CellStatus.TODO)
                s.progressed()
                s.stalled = false
            }
        }
    }
    if (filled == null) say(worker, WorkerWhy.SCOOP_FAILED) { "scoop at ${task.pos.toShortString()} gave nothing: not a source, unloaded or refused" }
    release(task.cell, worker.workerId)
    return filled ?: bucket
}

// setBlock notifies the neighbours but never the placed block, so it reads its real redstone signal only here.
// A source (a planned lever) strongly powers its neighbours, so their neighbours must hear it too, as a lever flip does.
private fun readPower(level: ServerLevel, pos: BlockPos) {
    val block = level.getBlockState(pos).block
    level.neighborChanged(pos, block, pos)
    val placed = level.getBlockState(pos)
    if (!placed.isSignalSource) return
    for (d in Direction.values()) level.updateNeighborsAt(pos.relative(d), placed.block)
}

private fun placeEffects(level: ServerLevel, pos: BlockPos, state: BlockState) {
    val sound = state.soundType
    level.playSound(null, pos, sound.placeSound, SoundSource.BLOCKS, (sound.volume + 1f) / 2f, sound.pitch * 0.8f)
    level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(state))
}

private fun BuildJob.blockCell(s: BuildSection, cell: Int, task: BuildTask, worker: BuildWorker, status: Byte): PlaceResult {
    s.setStatus(cell, status)
    release(task.cell, worker.workerId)
    return PlaceResult.BLOCKED
}
