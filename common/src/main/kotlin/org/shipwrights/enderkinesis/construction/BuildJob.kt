package org.shipwrights.enderkinesis.construction

import com.mojang.authlib.GameProfile
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.shipwrights.enderkinesis.entity.ConstriliarCargoShape
import org.shipwrights.enderkinesis.item.PlanPlacement
import org.shipwrights.enderkinesis.spell.SpellPlatform
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard
import java.util.BitSet
import java.util.UUID

/** Server status of one plan-box cell. The client sees [DEFERRED] as TODO and [NO_BUCKET] as BLOCKED. */
object CellStatus {
    const val TODO: Byte = 0
    const val DONE: Byte = 1
    const val BLOCKED: Byte = 2
    const val MISSING: Byte = 3
    const val DEFERRED: Byte = 4

    /** A fluid to scoop with no empty bucket in a linked supply. */
    const val NO_BUCKET: Byte = 5

    fun toClient(status: Byte): BuildCellStatus = when (status) {
        DONE -> BuildCellStatus.DONE
        BLOCKED, NO_BUCKET -> BuildCellStatus.BLOCKED
        MISSING -> BuildCellStatus.MISSING
        else -> BuildCellStatus.TODO
    }
}

/**
 * One build, worked one 16^3 plan section at a time. MineColonies order: CLEAR runs over the whole plan top-down
 * before any block goes in; then the build phase works bottom-up (each section looks once more for CLEAR work first).
 * Only [progress], the CLEAR phase, the supply list and the members persist: a section's order is computed again when it comes
 * back into work.
 */
class BuildJob(
    val id: UUID,
    val owner: UUID,
    /** The drafting table that owns this job, in the level's own frame (shipyard on a ship). One table, one job. */
    val table: BlockPos,
    /** [org.shipwrights.enderkinesis.blockentity.DraftingTableBlockEntity.tableId]; it finds the job again after the table moves. */
    val tableId: UUID,
    val ref: PlanRef,
    val anchor: PlanAnchor,
    val supplies: MutableList<BlockPos>,
) {
    val grid = PlanGrid(ref.size)
    val progress = SectionProgress(grid)
    private val sweep = SectionSweep(grid)
    private val clearSweep = SectionSweep(grid, topDown = true)

    /** The whole-plan CLEAR phase; no block goes in while it runs. */
    var clearing = true
        private set
    /** Sections the CLEAR phase finished; dropped when the phase ends. */
    private var cleared = BitSet()
    private var clearedCount = 0
    /** A CLEAR section that waits for the section row above it to finish, or -1. */
    private var heldBack = -1
    internal val active = ArrayList<BuildSection>(MAX_ACTIVE)
    private var current = 0
    private var ready = false
    private val seed = id.mostSignificantBits xor id.leastSignificantBits

    /** Reserved cells by [cellKey], each to the [BuildWorker.workerId] that holds it. */
    private val reservations = Long2ObjectOpenHashMap<UUID>()

    /** The worker ids of the Clawhods in this job. Saved: an unloaded Clawhod stays in it. */
    private val members = LinkedHashSet<UUID>()

    /** The floor each clawhod's carrier stands on or walks to, by clawhod id, and that body's reach; so carriers
     *  of one job spread out. */
    private val standSpots = Object2LongOpenHashMap<UUID>()

    /** Items each hand on its way to a supply will take there, by hand id, so no two hands set out for one stack. */
    private val fetchClaims = HashMap<UUID, FetchClaim>()

    private class FetchClaim(val supply: BlockPos, val item: Item, val count: Int)

    /** The last [findSupply] found the item only in stock that hands on their way will take. */
    private var supplySpoken = false

    /** [worker] sets out to take [count] of [item] at [supply]. */
    internal fun claimFetch(worker: UUID, supply: BlockPos, item: Item, count: Int) {
        fetchClaims[worker] = FetchClaim(supply, item, count)
    }

    internal fun releaseFetch(worker: UUID) {
        // A tool claim that ends without a take frees the tool for hands in a rest pass.
        if (fetchClaims.remove(worker)?.item?.let(::isToolItem) == true) supplyEpoch++
    }

    /** What hands other than [worker] on their way to [supply] will take of [item]. */
    internal fun pendingFetch(supply: BlockPos, item: Item, worker: UUID): Int {
        var n = 0
        for ((id, c) in fetchClaims) if (id != worker && c.item == item && c.supply == supply) n += c.count
        return n
    }
    /** Each claim's body reach in world blocks: the body's chebyshev radius times its walker ship's scale. */
    private val standReach = Object2DoubleOpenHashMap<UUID>()
    /** Each claim's support face ([FLOOR_FACE] by default); a wall claim holds the body cell, not a floor. */
    private val standFaces = Object2IntOpenHashMap<UUID>().apply { defaultReturnValue(FLOOR_FACE) }
    /** Each claim's body height in world blocks: its layers times its walker ship's scale. */
    private val standHeight = Object2DoubleOpenHashMap<UUID>().apply { defaultReturnValue(ConstriliarCargoShape.MAX_HEIGHT.toDouble()) }
    private val missingItems = HashSet<Item>()
    /** Grows on every supply change: a worker that holds its load for want of a supply looks again. */
    internal var supplyEpoch = 0
        private set
    internal var picksLeft = 0
    private var scansLeft = 0

    /** Last tick-level and section-level reasons, so the debug log prints on a change only. */
    internal val tickNote = ReasonNote()
    private val sectionNote = ReasonNote()

    /** Supply positions and their double-chest halves; [BuildJobs] fills it. */
    internal val watch = LongOpenHashSet()

    /** A place or a section completion since the last save mark. */
    internal var saveDirty = false
    private var ownerProfile: GameProfile? = null

    internal val sync = BuildJobSync()
    internal var fullSyncPending: Boolean
        get() = sync.fullPending
        set(value) {
            sync.fullPending = value
        }
    private val scratch = BlockPos.MutableBlockPos()

    val isReady: Boolean get() = ready
    val isFinished: Boolean get() = ready && progress.completeCount >= grid.count

    internal fun section(index: Int): BuildSection? = active.firstOrNull { it.index == index }

    /**
     * Retires finished sections, orders the ones in work, and brings in the next sections near the bottom.
     * False when the plan file is gone.
     */
    internal fun prepare(level: ServerLevel, budget: Int): Boolean {
        val plan = buildPlan(level.server, ref)
        if (plan == null) {
            BuildLog.job(this, JobWhy.PLAN_MISSING, "plan file ${ref.name} cannot be read; the job ends")
            return false
        }
        if (plan.grid != grid) {
            BuildLog.job(this, JobWhy.PLAN_CHANGED, "plan grid differs from the job grid; the job ends")
            return false
        }
        ready = true
        retireComplete()
        if (clearing && clearedCount >= grid.count) endClearing()
        var left = budget
        for (s in active) {
            if (left <= 0) return true
            left -= s.prepare(level, left)
        }
        refill(level, plan)
        return true
    }

    private fun retireComplete() {
        val it = active.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.reserved > 0) continue
            if (clearing) {
                if (!s.clearDone) continue
                markCleared(s.index)
                if (s.isComplete) markComplete(s.index)
            } else {
                if (!s.isComplete) continue
                markComplete(s.index)
            }
            it.remove()
        }
        if (current >= active.size) current = 0
    }

    private fun markComplete(index: Int) {
        progress.markComplete(index)
        BuildLog.job(this, JobWhy.SECTION_COMPLETE, "section=$index complete ${progress.completeCount}/${grid.count}")
        sync.sectionCompleted(index)
        saveDirty = true
    }

    private fun markCleared(index: Int) {
        if (cleared[index]) return
        cleared.set(index)
        clearedCount++
        saveDirty = true
    }

    private fun endClearing() {
        clearing = false
        BuildLog.job(this, JobWhy.CLEAR_ENDED, "CLEAR phase over; the build phase starts")
        cleared = BitSet()
        clearedCount = 0
        heldBack = -1
        saveDirty = true
    }

    private fun sectionDone(index: Int): Boolean = progress.isComplete(index) || clearing && cleared[index]

    // Every section of the plan box is work: CLEAR reaches the plan's air too. A section of only structure
    // void has no work. A stalled section is parked for a new one; its done bits stay in progress and the
    // sweep returns to it.
    private fun refill(level: ServerLevel, plan: PlanSections) {
        val evict = if (active.size < MAX_ACTIVE) -1 else active.indexOfFirst { it.stalled && it.reserved == 0 }
        if (active.size >= MAX_ACTIVE && evict < 0) return
        val walk = if (clearing) clearSweep else sweep
        var skips = COMPLETE_SKIPS_PER_TICK
        var looks = SWEEP_PER_TICK
        var unloaded = -1
        while (looks > 0 && skips > 0) {
            val index = if (heldBack >= 0) heldBack.also { heldBack = -1 } else walk.next()
            if (sectionDone(index)) {
                skips--
                continue
            }
            if (plan.state(index) == PlanSectionState.EMPTY) {
                if (clearing) markCleared(index)
                markComplete(index)
                skips--
                continue
            }
            looks--
            if (section(index) != null) continue
            if (clearing && active.any { grid.sectionY(it.index) > grid.sectionY(index) }) {
                // MineColonies clears top-down: a lower row waits until every section above it is clear.
                heldBack = index
                if (sectionNote.changed(JobWhy.CLEAR_HELD_BACK, index)) {
                    BuildLog.job(this, JobWhy.CLEAR_HELD_BACK, "section=$index waits for the row above to clear ${summary()}")
                }
                return
            }
            if (!chunksLoaded(level, index)) {
                if (unloaded < 0) unloaded = index
                continue
            }
            val s = BuildSection(index, progress.doneBits(index), plan.sectionAt(index), grid.origin(index), anchor, plan, seed, table.asLong())
            if (evict >= 0) {
                active[evict] = s
            } else {
                active.add(s)
            }
            sync.sectionStarted(index)
            return
        }
        if (unloaded >= 0 && sectionNote.changed(JobWhy.SECTION_UNLOADED, unloaded)) {
            BuildLog.job(this, JobWhy.SECTION_UNLOADED, "section=$unloaded not started: a chunk of it is not loaded ${summary()}")
        }
    }

    // Work never starts on a section whose blocks or support may sit in an unloaded chunk.
    private fun chunksLoaded(level: ServerLevel, index: Int): Boolean {
        val o = grid.origin(index)
        val a = anchor.cellWorldPos(o)
        val b = anchor.cellWorldPos(
            BlockPos(minOf(o.x + LAST, ref.size.x - 1), o.y, minOf(o.z + LAST, ref.size.z - 1)),
        )
        for (cx in (minOf(a.x, b.x) shr 4)..(maxOf(a.x, b.x) shr 4)) {
            for (cz in (minOf(a.z, b.z) shr 4)..(maxOf(a.z, b.z) shr 4)) {
                if (!level.chunkSource.hasChunk(cx, cz)) return false
            }
        }
        return true
    }

    /**
     * The owner, or a fake player with the owner's profile while the owner is offline, so every permission
     * check runs for the owner either way.
     */
    internal fun actor(level: ServerLevel): ServerPlayer {
        level.server.playerList.getPlayer(owner)?.let { return it }
        val profile = ownerProfile
            ?: (level.server.profileCache?.get(owner)?.orElse(null) ?: GameProfile(owner, null)).also { ownerProfile = it }
        return SpellPlatform.fakePlayer(level, profile)
    }

    /** A linked supply changed: waiting cells get a new look at once, before the pass comes round. */
    internal fun wake() {
        supplyEpoch++
        missingItems.clear()
        for (i in active.indices) active[i].wakeMissing()
    }

    /**
     * Fills [pick] with the next work for [worker]: one CUT, SCOOP or FIX, or a batch of PLACE cells of
     * one block type up to what the worker can hold. Woken waiting cells go first. False when the
     * budget ran out or nothing is open now.
     */
    internal fun pick(level: ServerLevel, actor: ServerPlayer, worker: BuildWorker, pick: WorkerPick): Boolean {
        pick.begin(worker)
        scansLeft = SCANS_PER_PICK
        val sc = Picker(level, actor, worker, pick)
        if (pickWoken(sc)) return pick.found()
        while (picksLeft > 0 && scansLeft > 0 && active.isNotEmpty()) {
            if (current >= active.size) current = 0
            val s = active[current]
            when (step(sc, s, s.pass(pick.pattern))) {
                Step.FOUND -> return pick.found()
                Step.PASS_END -> {
                    current++
                    if (pick.passEnded(active.size)) return false
                }
                Step.NEXT -> Unit
            }
        }
        return false
    }

    private enum class Step { FOUND, NEXT, PASS_END }

    /** Who picks, for one [pick] call. */
    private class Picker(val level: ServerLevel, val actor: ServerPlayer, val worker: BuildWorker, val pick: WorkerPick) {
        /** The last block this pick found no tool for in the supplies, so a pass does not scan them per cell. */
        var noToolFor: Block? = null
    }

    private fun step(sc: Picker, s: BuildSection, p: BuildSection.PatternPass): Step {
        if (!p.inBuild) {
            if (p.cursor < p.clear.size) {
                val l = p.clear[p.cursor++]
                scansLeft--
                return if (tryClear(sc, s, p, l)) Step.FOUND else Step.NEXT
            }
            s.clearClean = p.clearSeen == 0
            if (clearing) {
                s.clearDone = p.clearOpen == 0 && s.clearHeld.isEmpty
                return endPass(s, p)
            }
            // MineColonies CLEAR before BUILD: nothing open to cut or scoop, and no worker still out on it.
            if (p.clearOpen > 0 || !s.clearHeld.isEmpty || s.remaining == 0) return endPass(s, p)
            if (p.order == null) {
                p.wantOrder = true
                return endPass(s, p)
            }
            p.inBuild = true
        }
        val order = checkNotNull(p.order)
        val i = p.cursor - p.clear.size
        if (i >= order.size) return endPass(s, p)
        p.cursor++
        scansLeft--
        return if (tryBuild(sc, s, p, order[i], i + 1)) Step.FOUND else Step.NEXT
    }

    private fun endPass(s: BuildSection, p: BuildSection.PatternPass): Step {
        s.endPass(p)
        if (s.stalled) {
            // One stall episode per section, until real progress: each worker's pass sees its own reason set, so
            // printing on every change flipped between them several times a second. Print when a new reason joins.
            if (s.progressMark != s.stallMark) {
                s.stallMark = s.progressMark
                s.stallReasons = 0
            }
            val reasons = whyMask(s.passWhy)
            if (reasons and s.stallReasons.inv() != 0) {
                s.stallReasons = s.stallReasons or reasons
                val cells = "remaining=${s.remaining} missing=${s.missing}"
                BuildLog.job(
                    this, JobWhy.SECTION_STALLED,
                    "section=${s.index} pass made no progress, $cells why=[${whySummary(s.passWhy)}] ${summary()}",
                )
            }
        }
        s.passWhy.fill(0)
        missingItems.clear()
        return Step.PASS_END
    }

    private fun BuildSection.note(tally: PickTally, why: WhyNot, packedPos: Long, state: BlockState? = null) {
        passWhy[why.ordinal]++
        tally.add(why, packedPos, state)
    }

    private fun BuildSection.noteMissing(tally: PickTally, item: Item, packedPos: Long) {
        passWhy[WhyNot.NO_SUPPLY.ordinal]++
        tally.missing(item, packedPos)
    }

    private fun pickWoken(sc: Picker): Boolean {
        for (i in active.indices) {
            val s = active[i]
            val p = s.pass(sc.pick.pattern)
            while (p.wake >= 0 && picksLeft > 0 && scansLeft > 0) {
                if (p.wake >= p.cursor || s.missing == 0) {
                    p.wake = -1
                    break
                }
                val l = p.localAt(p.wake++)
                scansLeft--
                // A cell woken from the CLEAR part of the pass has no place in the build order: no batch after it.
                val next = p.wake - p.clear.size
                val found = when (s.status[l]) {
                    CellStatus.NO_BUCKET -> tryClear(sc, s, p, l)
                    CellStatus.MISSING -> s.cellAt(l).let { c -> c >= 0 && tryBuild(sc, s, p, c, if (next > 0) next else NO_BATCH) }
                    else -> false
                }
                if (found) return true
            }
        }
        return false
    }

    @Suppress("ReturnCount") // One return per CLEAR outcome reads clearer than nested branches.
    private fun tryClear(sc: Picker, s: BuildSection, p: BuildSection.PatternPass, l: Int): Boolean {
        val level = sc.level
        val pick = sc.pick
        val key = cellKey(s.index, l)
        val pos = scratch.set(s.boxPos[l])
        // The table's own cell counts as done; the builder never cuts the table.
        if (pos == table) return false
        val reserved = reservations.containsKey(key)
        // A PLACE or FIX reservation is build work. Counted as open CLEAR work, it keeps all other workers out of BUILD.
        if (reserved && !s.clearHeld[l]) return false
        if (reserved || !level.isLoaded(pos)) {
            s.note(pick.tally, if (reserved) WhyNot.RESERVED else WhyNot.NOT_LOADED, s.boxPos[l])
            p.clearSeen++
            p.clearOpen++
            return false
        }
        val c = s.cellAt(l)
        val world = level.getBlockState(pos)
        val kind = clearKind(if (c >= 0) s.states[c] else null, world)
        if (kind == ClearKind.NONE) {
            if (c < 0) s.setLocalStatus(l, CellStatus.TODO)
            return false
        }
        p.clearSeen++
        if (clearRefused(level, sc.actor, pos, world, kind)) {
            s.note(pick.tally, WhyNot.CLEAR_REFUSED, s.boxPos[l], world)
            s.setLocalStatus(l, CellStatus.BLOCKED)
            return false
        }
        if (kind == ClearKind.CUT && !sc.worker.canEverHold(world.block)) {
            s.note(pick.tally, WhyNot.NO_CAPACITY, s.boxPos[l], world)
            s.setLocalStatus(l, CellStatus.BLOCKED)
            return false
        }
        if (!sc.worker.reaches(s.boxPos[l])) {
            p.clearOpen++
            s.note(pick.tally, WhyNot.OUT_OF_REACH, s.boxPos[l], world)
            if (!sc.worker.skipsFar(s.boxPos[l])) pick.noteFar(s.boxPos[l], world, build = false)
            return false
        }
        if (kind == ClearKind.CUT && sc.worker.minedElsewhere(s.boxPos[l])) {
            s.note(pick.tally, WhyNot.RESERVED, s.boxPos[l], world)
            return false
        }
        if (kind == ClearKind.SCOOP) return reserveScoop(sc, s, p, l, world)
        p.clearOpen++
        val held = pick.held
        if (held != null) {
            // Cut more of the same block before the trip back; the yield is checked at the cut.
            if (world.block != pick.lastCut) return false
        } else if (sc.worker.tool().isEmpty && pick.capacity(sc.worker, world.block) == 0) {
            s.note(pick.tally, WhyNot.NO_CAPACITY, s.boxPos[l], world)
            return false
        }
        if (!cutTool(sc, s, l, world)) return false
        s.setLocalStatus(l, CellStatus.TODO)
        return reserveClear(sc, s, l, TaskKind.CUT, world)
    }

    /**
     * The user's rules: build jobs use the tools in the supplies when they fit, and still work without, in tool passes
     * as mining does. A hand with no tool takes the best one in reach for the cut (the block's tool type, else a
     * pickaxe); a bare hand leaves a cut to a tool holder that will take it. False, with a reason noted, when it waits.
     */
    private fun cutTool(sc: Picker, s: BuildSection, l: Int, world: BlockState): Boolean {
        if (!sc.worker.tool().isEmpty) {
            // Tool passes, as in mining: a cut its tool is not for waits for the pass of that tool.
            // The user's rule: the pickaxe fallback only when no hand with the block's own tool takes the cut.
            if (sc.worker.takesCut(world)) {
                if (toolRank(sc.worker.tool(), world) == TOOL_MATCH || !sc.worker.matchHolderTakes(s.boxPos[l], world)) return true
                s.note(sc.pick.tally, WhyNot.FOR_TOOL_HOLDER, s.boxPos[l], world)
                return false
            }
            s.note(sc.pick.tally, WhyNot.OTHER_TOOL_PASS, s.boxPos[l], world)
            return false
        }
        if (needsNoTool(world)) return true
        if (sc.worker.mayFetchTool() && sc.noToolFor !== world.block && findTool(sc, world)) return true
        if (!sc.worker.toolHolderTakes(s.boxPos[l], world)) return true
        s.note(sc.pick.tally, WhyNot.FOR_TOOL_HOLDER, s.boxPos[l], world)
        return false
    }

    /** Sets the pick's fetch to the best tool for [world] in a supply in reach. False (remembered for this pick) when none. */
    private fun findTool(sc: Picker, world: BlockState): Boolean {
        val found = bestToolIn(
            sc.level, supplies, world, matchOnly = false,
            usable = { sc.worker.reaches(it.asLong()) && mayEdit(sc.level, sc.actor, it) },
            skip = { pos, item -> sc.worker.refuses(item) || toolStockLeft(sc.level, pos, item, sc.worker.workerId) <= 0 },
        )
        if (found == null) {
            sc.noToolFor = world.block
            return false
        }
        sc.pick.supply = found.pos
        sc.pick.fetchItem = found.item
        sc.pick.fetchCount = 1
        return true
    }

    /** How many of the tool [item] at [pos] are not spoken for by other hands on their way. */
    internal fun toolStockLeft(level: ServerLevel, pos: BlockPos, item: Item, worker: UUID): Int =
        (HopperBlockEntity.getContainerAt(level, pos)?.countOf(item) ?: 0) - pendingFetch(pos, item, worker)


    private fun reserveScoop(sc: Picker, s: BuildSection, p: BuildSection.PatternPass, l: Int, world: BlockState): Boolean {
        val pick = sc.pick
        val supply = findSupply(sc, Items.BUCKET, 1)
        if (supply == null) {
            if (Items.BUCKET in missingItems) {
                s.noteMissing(pick.tally, Items.BUCKET, s.boxPos[l])
                s.setLocalStatus(l, CellStatus.NO_BUCKET)
            } else {
                p.clearOpen++
                s.note(pick.tally, WhyNot.NO_SUPPLY_IN_REACH, s.boxPos[l], world)
                pick.noteFar(s.boxPos[l], world, build = false)
            }
            return false
        }
        p.clearOpen++
        s.setLocalStatus(l, CellStatus.TODO)
        pick.supply = supply
        pick.fetchItem = Items.BUCKET
        pick.fetchCount = 1
        return reserveClear(sc, s, l, TaskKind.SCOOP, world)
    }

    private fun reserveClear(sc: Picker, s: BuildSection, l: Int, kind: TaskKind, world: BlockState): Boolean {
        picksLeft--
        val key = cellKey(s.index, l)
        reservations.put(key, sc.worker.workerId)
        s.reserved++
        s.clearHeld.set(l)
        sc.pick.tasks.add(BuildTask(kind, key, BlockPos.of(s.boxPos[l]), world, null))
        return true
    }

    // Never cut what a player could not break, a block entity that holds things, or half of a
    // two-part block whose other half is outside the plan box or in structure void.
    internal fun clearRefused(level: ServerLevel, actor: ServerPlayer, pos: BlockPos, world: BlockState, kind: ClearKind): Boolean {
        // The table owns the job: cutting it would end the job.
        if (pos == table) return true
        if (!mayEdit(level, actor, pos) || !SpellPlatform.mayBreak(level, pos, world, actor)) return true
        if (kind == ClearKind.SCOOP) return false
        if (world.getDestroySpeed(level, pos) < 0f) return true
        if (level.getBlockEntity(pos)?.holdsContents() == true) return true
        val other = world.otherHalf(pos) ?: return false
        return !mayClear(level, other)
    }

    /** True when world column ([x], [z]) is inside the plan's box. */
    internal fun inFootprint(x: Int, z: Int): Boolean {
        val local = planLocal(anchor, x, z)
        return planLocalX(local) in 0 until ref.size.x && planLocalZ(local) in 0 until ref.size.z
    }

    /** World y of the plan's top layer. */
    internal val planTopY: Int get() = anchor.pos.y + ref.size.y - 1
    internal val planBottomY: Int get() = anchor.pos.y

    /**
     * Claims [floor] on support [face] for [worker]'s carrier, a body of chebyshev [reach] and [height] world blocks:
     * a floor, or the body cell of a wall spot. The spot is in the walker's walk frame: the world, or a ship's
     * shipyard on its deck.
     */
    internal fun claimStandSpot(
        worker: UUID, floor: Long, reach: Double, face: Int = FLOOR_FACE, height: Double = ConstriliarCargoShape.MAX_HEIGHT.toDouble(),
    ) {
        require(height > 0.0) { "Body height must be positive: $height" }
        standSpots.put(worker, floor)
        standReach.put(worker, reach)
        standFaces.put(worker, face)
        standHeight.put(worker, height)
    }

    /** How many stand claims the job holds now, for the log. */
    internal fun standClaimCount(): Int = standSpots.size

    /** The support face of [worker]'s claim, or [FLOOR_FACE]. */
    internal fun standFaceOf(worker: UUID): Int = standFaces.getInt(worker)

    /** The floor [worker]'s carrier stands on or walks to, or [NO_POS]. */
    internal fun standSpotOf(worker: UUID): Long = if (standSpots.containsKey(worker)) standSpots.getLong(worker) else NO_POS

    /**
     * False when the body of another worker's carrier, on the spot it stands on or walks to, comes within
     * [STAND_GAP] of a body of [reach] and [height] world blocks on [floor] with support [face]. Deck and world claims
     * meet in the world: each body box goes through its own ship's transform.
     */
    @Suppress("LongParameterList") // A spot, its face and the body's two sizes.
    internal fun standSpotFree(
        level: ServerLevel, worker: UUID, floor: Long, reach: Double, face: Int = FLOOR_FACE,
        height: Double = ConstriliarCargoShape.MAX_HEIGHT.toDouble(),
    ): Boolean {
        claimWorldBox(level, floor, face, reach, height, boxA)
        val it = standSpots.object2LongEntrySet().iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key == worker) continue
            claimWorldBox(level, e.longValue, standFaces.getInt(e.key), standReach.getDouble(e.key), standHeight.getDouble(e.key), boxB)
            if (standBoxesClash(boxA, boxB)) return false
        }
        return true
    }

    /**
     * The world body box ([bodyBox] layout) of a body of world [reach] and [height] claimed on [spot] with support
     * [face], through the spot's own ship for a deck spot.
     */
    @Suppress("LongParameterList") // A claim is a spot, a face and two sizes.
    private fun claimWorldBox(level: ServerLevel, spot: Long, face: Int, reach: Double, height: Double, out: DoubleArray) {
        val x = BlockPos.getX(spot)
        val y = BlockPos.getY(spot)
        val z = BlockPos.getZ(spot)
        val ship = if (level.isBlockInShipyard(x, y, z)) level.getShipManagingPos(x shr 4, z shr 4) else null
        val scale = ship?.transform?.shipToWorldScaling?.x() ?: 1.0
        claimBodyBox(spot, face, reach / scale, height / scale, out)
        if (ship == null) return
        claimBox.setMin(out[0], out[1], out[2]).setMax(out[3], out[4], out[5])
        claimBox.transform(ship.shipToWorld, worldBox)
        out[0] = worldBox.minX
        out[1] = worldBox.minY
        out[2] = worldBox.minZ
        out[3] = worldBox.maxX
        out[4] = worldBox.maxY
        out[5] = worldBox.maxZ
    }

    private val claimBox = AABBd()
    private val worldBox = AABBd()
    private val boxA = DoubleArray(BOX_VALUES)
    private val boxB = DoubleArray(BOX_VALUES)

    /**
     * World block ([x], [y], [z]) is in, or one block next to, the claimed body box of a walker other than [walker].
     * The support side gets no extra block: the block the body stands on is not its room.
     */
    internal fun claimedBodyAt(level: ServerLevel, x: Int, y: Int, z: Int, walker: UUID?): Boolean {
        val it = standSpots.object2LongEntrySet().iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key == walker) continue
            val face = standFaces.getInt(e.key)
            claimWorldBox(level, e.longValue, face, standReach.getDouble(e.key), standHeight.getDouble(e.key), boxB)
            val f = Direction.from3DDataValue(face)
            if (x + 1.0 > boxB[0] - (if (f == Direction.WEST) 0.0 else 1.0) && x < boxB[3] + (if (f == Direction.EAST) 0.0 else 1.0) &&
                y + 1.0 > boxB[1] - (if (f == Direction.DOWN) 0.0 else 1.0) && y < boxB[4] + (if (f == Direction.UP) 0.0 else 1.0) &&
                z + 1.0 > boxB[2] - (if (f == Direction.NORTH) 0.0 else 1.0) && z < boxB[5] + (if (f == Direction.SOUTH) 0.0 else 1.0)
            ) {
                return true
            }
        }
        return false
    }

    /** True when the plan puts a block at world [pos], so a carrier must not stand its body there. */
    internal fun planWantsBlock(level: ServerLevel, pos: BlockPos): Boolean = planWantsBlockAt(level, pos.x, pos.y, pos.z)

    private var frameShip: Ship? = null
    private var frameAt = Long.MIN_VALUE
    private val frameScratch = Vector3d()

    /** The ship whose shipyard holds the plan (a drafting table on a ship), or null for a world job. Once per tick. */
    internal fun planShip(level: ServerLevel): Ship? {
        if (frameAt != level.gameTime) {
            frameShip = if (level.isBlockInShipyard(table)) level.getShipManagingPos(table) else null
            frameAt = level.gameTime
        }
        return frameShip
    }

    /** The plan-frame block that holds the centre of world block ([x], [y], [z]); the same block for a world job. */
    internal fun toPlanFrame(level: ServerLevel, x: Int, y: Int, z: Int): Long = toPlanFrame(level, x + HALF, y + HALF, z + HALF)

    /** The plan-frame block that holds world point ([x], [y], [z]). */
    internal fun toPlanFrame(level: ServerLevel, x: Double, y: Double, z: Double): Long {
        val ship = planShip(level) ?: return BlockPos.asLong(floorOf(x), floorOf(y), floorOf(z))
        frameScratch.set(x, y, z)
        ship.worldToShip.transformPosition(frameScratch)
        return BlockPos.asLong(floorOf(frameScratch.x), floorOf(frameScratch.y), floorOf(frameScratch.z))
    }

    private var yRangeAt = Long.MIN_VALUE
    private var worldMinY = 0
    private var worldMaxY = 0

    /** Lowest and highest world layer the plan box reaches: all 8 corners go through the ship, so a tilt counts. */
    internal fun worldBaseY(level: ServerLevel): Int {
        refreshWorldY(level)
        return worldMinY
    }

    internal fun worldTopY(level: ServerLevel): Int {
        refreshWorldY(level)
        return worldMaxY
    }

    private fun refreshWorldY(level: ServerLevel) {
        if (yRangeAt == level.gameTime) return
        yRangeAt = level.gameTime
        val ship = planShip(level)
        if (ship == null) {
            worldMinY = anchor.pos.y
            worldMaxY = planTopY
            return
        }
        val box = PlanPlacement.box(anchor.pos, ref.size, anchor.rotation, anchor.mirror)
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0 until BOX_CORNERS) {
            frameScratch.set(
                if (i and 1 == 0) box.minX().toDouble() else box.maxX() + 1.0,
                if (i and 2 == 0) box.minY().toDouble() else box.maxY() + 1.0,
                if (i and 4 == 0) box.minZ().toDouble() else box.maxZ() + 1.0,
            )
            ship.shipToWorld.transformPosition(frameScratch)
            lo = minOf(lo, frameScratch.y)
            hi = maxOf(hi, frameScratch.y)
        }
        worldMinY = floorOf(lo)
        worldMaxY = Math.ceil(hi).toInt() - 1
    }

    private fun floorOf(v: Double): Int = Math.floor(v).toInt()

    /** The plan wants a block at ([x], [y], [z]) and the cell is still empty. No allocation. */
    internal fun unbuiltAt(level: ServerLevel, x: Int, y: Int, z: Int): Boolean {
        if (y < anchor.pos.y || y > planTopY || !inFootprint(x, z) || !planWantsBlockAt(level, x, y, z)) return false
        unbuiltCursor.set(x, y, z)
        return level.isLoaded(unbuiltCursor) && level.getBlockState(unbuiltCursor).getCollisionShape(level, unbuiltCursor).isEmpty
    }

    private val unbuiltCursor = BlockPos.MutableBlockPos()

    /** [target] at [packed] hangs from or sticks to a plan block that is not built yet ([attachMask]). */
    private fun waitsForAttachment(level: ServerLevel, target: BlockState, packed: Long): Boolean {
        val mask = attachMask(target)
        if (mask == 0) return false
        val x = BlockPos.getX(packed)
        val y = BlockPos.getY(packed)
        val z = BlockPos.getZ(packed)
        if (mask and ATTACH_UNNAMED != 0) {
            return missingSupport(level, x, y + 1, z, hangOnly = true) || missingSupport(level, x, y - 1, z, hangOnly = false)
        }
        for (d in ATTACH_DIRECTIONS) {
            if (mask and attachBit(d) == 0) continue
            if (missingSupport(level, x + d.stepX, y + d.stepY, z + d.stepZ, hangOnly = false)) return true
        }
        return false
    }

    /**
     * The plan wants a block at ([x], [y], [z]) that is not built yet and can hold another block: a sturdy one, or one
     * that itself hangs from above. [hangOnly] takes only the hanging kind. Only sturdy blocks and hanging lines hold,
     * so no two attached blocks wait for each other.
     */
    private fun missingSupport(level: ServerLevel, x: Int, y: Int, z: Int, hangOnly: Boolean): Boolean {
        if (y < anchor.pos.y || y > planTopY || !inFootprint(x, z)) return false
        val local = planLocal(anchor, x, z)
        val plan = buildPlan(level.server, ref)?.stateAt(planLocalX(local), y - anchor.pos.y, planLocalZ(local)) ?: return false
        if (plan.isAir) return false
        val mask = attachMask(plan)
        val holds = if (hangOnly) mask and attachBit(Direction.UP) != 0 else mask == 0 || mask and attachBit(Direction.UP) != 0
        if (!holds) return false
        unbuiltCursor.set(x, y, z)
        return level.isLoaded(unbuiltCursor) && level.getBlockState(unbuiltCursor).isReplaceable()
    }

    /** [planWantsBlock] with no allocation: [planLocal] undoes the anchor transform by hand. */
    internal fun planWantsBlockAt(level: ServerLevel, x: Int, y: Int, z: Int): Boolean {
        val local = planLocal(anchor, x, z)
        val state = buildPlan(level.server, ref)
            ?.stateAt(planLocalX(local), y - anchor.pos.y, planLocalZ(local)) ?: return false
        return !state.isAir
    }

    /** Inside the plan box and not structure void. */
    private fun mayClear(level: ServerLevel, world: BlockPos): Boolean {
        val p = anchor.cellLocalPos(world)
        val inBox = p.x in 0 until ref.size.x && p.y in 0 until ref.size.y && p.z in 0 until ref.size.z
        return inBox && buildPlan(level.server, ref)?.isKeep(p.x, p.y, p.z) == false
    }

    @Suppress("ReturnCount") // One return per pick outcome reads clearer than nested branches.
    /** [next] is the build-order index after [cell], where a batch goes on, or [NO_BATCH]. */
    private fun tryBuild(sc: Picker, s: BuildSection, p: BuildSection.PatternPass, cell: Int, next: Int): Boolean {
        val level = sc.level
        val worker = sc.worker
        val pick = sc.pick
        val key = cellKey(s.index, s.local[cell])
        if (s.isDone(cell)) return false
        if (reservations.containsKey(key)) {
            s.note(pick.tally, WhyNot.RESERVED, s.positions[cell])
            return false
        }
        val target = s.states[cell]
        // Out of reach costs no pick, so a worker that sees few cells still searches the plan fast.
        if (!worker.reaches(s.positions[cell])) {
            s.note(pick.tally, WhyNot.OUT_OF_REACH, s.positions[cell], target)
            // A worker that holds blocks heads for a cell that uses them: the first far cell of any kind sent a
            // stocked clawhod back to unload what it had just fetched.
            if (pick.farCell == NO_POS && usesHeld(pick, target) && !worker.skipsFar(s.positions[cell])) {
                picksLeft--
                if (check(level, sc.actor, s, cell, pick) != Check.NONE) pick.noteFar(s.positions[cell], target, build = true)
            }
            return false
        }
        picksLeft--
        val checked = check(level, sc.actor, s, cell, pick)
        if (checked != Check.NONE && !worker.keepsCarrierFree(s.positions[cell])) {
            s.note(pick.tally, WhyNot.CARRIER_IN_WAY, s.positions[cell], target)
            if (s.status(cell) != CellStatus.BLOCKED) s.setStatus(cell, CellStatus.DEFERRED)
            return false
        }
        when (checked) {
            Check.FIX -> {
                reserve(worker, s, cell)
                pick.tasks.add(BuildTask(TaskKind.FIX, key, BlockPos.of(s.positions[cell]), target, null))
                return true
            }
            Check.READY -> Unit
            Check.NONE -> return false
        }
        val cost = target.planCost() as? PlanCost.Items ?: return false
        val block = clawhodBlockOf(cost.stack())
        if (block == null) {
            s.note(pick.tally, WhyNot.NO_CLAWHOD_BLOCK, s.positions[cell], target)
            s.setStatus(cell, CellStatus.BLOCKED)
            return false
        }
        if (cost.extra != null) return tryKit(sc, s, cell, cost, block)
        val limit: Int
        if (pick.held != null) {
            if (pick.held != block || pick.heldLeft < cost.count) {
                s.note(pick.tally, WhyNot.HOLDING_OTHER, s.positions[cell], target)
                return false
            }
            limit = pick.heldLeft
        } else {
            limit = fetchLimit(sc, s, cell, cost, block)
            if (limit < 0) return false
        }
        s.setStatus(cell, CellStatus.TODO)
        reserve(worker, s, cell)
        pick.tasks.add(BuildTask(TaskKind.PLACE, key, BlockPos.of(s.positions[cell]), target, cost))
        val total = cost.count + if (next == NO_BATCH) 0 else extendBatch(sc, s, p, cell, next, cost, limit - cost.count)
        if (pick.held != null) pick.heldLeft -= total else pick.fetchCount = total
        return true
    }

    /**
     * A two-item cell (pot and plant, cake and candle), never in a batch: the hand fetches the base unless it holds
     * enough, then the extra into its free slot. The place takes both.
     */
    @Suppress("ReturnCount") // One return per pick outcome reads clearer than nested branches.
    private fun tryKit(sc: Picker, s: BuildSection, cell: Int, cost: PlanCost.Items, block: Block): Boolean {
        val pick = sc.pick
        val extra = checkNotNull(cost.extra) { "a kit cost has an extra item" }
        val baseHeld = pick.held == block && pick.heldLeft >= cost.count
        if (pick.held != null && !baseHeld) {
            s.note(pick.tally, WhyNot.HOLDING_OTHER, s.positions[cell], s.states[cell])
            return false
        }
        if (baseHeld && !sc.worker.hasRoomFor(extra, cost.extraCount)) {
            s.note(pick.tally, WhyNot.NO_CAPACITY, s.positions[cell], s.states[cell])
            return false
        }
        if (!baseHeld && fetchLimit(sc, s, cell, cost, block) < 0) return false
        val baseSupply = pick.supply
        val extraSupply = supplyFor(sc, s, cell, extra, cost.extraCount)
        if (extraSupply == null) {
            pick.supply = null
            pick.fetchItem = null
            return false
        }
        s.setStatus(cell, CellStatus.TODO)
        reserve(sc.worker, s, cell)
        pick.tasks.add(BuildTask(TaskKind.PLACE, cellKey(s.index, s.local[cell]), BlockPos.of(s.positions[cell]), s.states[cell], cost))
        if (baseHeld) {
            pick.heldLeft -= cost.count
            pick.supply = extraSupply
            pick.fetchItem = extra
            pick.fetchCount = cost.extraCount
            pick.kitLeg = true
        } else {
            pick.supply = baseSupply
            pick.fetchCount = cost.count
            pick.extraSupply = extraSupply
            pick.extraItem = extra
            pick.extraCount = cost.extraCount
        }
        return true
    }

    /** True when the worker holds nothing, or holds the block that [target] costs. */
    private fun usesHeld(pick: WorkerPick, target: BlockState): Boolean {
        val held = pick.held ?: return true
        val cost = target.planCost() as? PlanCost.Items ?: return false
        return clawhodBlockOf(cost.stack()) == held
    }

    /** How many items of [cost] this worker takes from a supply, with the fetch set in the pick; -1 when none. */
    private fun fetchLimit(sc: Picker, s: BuildSection, cell: Int, cost: PlanCost.Items, block: Block): Int {
        val pick = sc.pick
        val capacity = minOf(pick.capacity(sc.worker, block), cost.stack().maxStackSize)
        if (capacity < cost.count) {
            s.note(pick.tally, WhyNot.NO_CAPACITY, s.positions[cell], s.states[cell])
            return -1
        }
        val supply = supplyFor(sc, s, cell, cost.item, cost.count) ?: return -1
        val stock = (HopperBlockEntity.getContainerAt(sc.level, supply)?.countOf(cost.item) ?: 0) -
            pendingFetch(supply, cost.item, sc.worker.workerId)
        pick.supply = supply
        pick.fetchItem = cost.item
        return minOf(capacity, stock)
    }

    /** A supply in reach with [count] of [item] for [cell], or null with the reason noted on the cell. */
    private fun supplyFor(sc: Picker, s: BuildSection, cell: Int, item: Item, count: Int): BlockPos? {
        val pick = sc.pick
        if (sc.worker.refuses(item)) {
            s.note(pick.tally, WhyNot.FETCH_REFUSED, s.positions[cell], s.states[cell])
            return null
        }
        val supply = findSupply(sc, item, count)
        if (supply != null) return supply
        when {
            // Every supply with the item is spoken for by hands on their way: wait for them.
            supplySpoken -> s.note(pick.tally, WhyNot.RESERVED, s.positions[cell], s.states[cell])
            item in missingItems -> {
                s.noteMissing(pick.tally, item, s.positions[cell])
                s.setStatus(cell, CellStatus.MISSING)
            }
            else -> {
                s.note(pick.tally, WhyNot.NO_SUPPLY_IN_REACH, s.positions[cell], s.states[cell])
                pick.noteFar(s.positions[cell], s.states[cell], build = true)
            }
        }
        return null
    }

    /**
     * More PLACE cells of [cost]'s item in the build order from index [from] on and in [first]'s layer, so a
     * batch never gets ahead of the layer order; up to [room] items. Returns the items added.
     */
    @Suppress("LongParameterList") // The pass position comes from the caller: a woken cell is not at the cursor.
    private fun extendBatch(
        sc: Picker, s: BuildSection, p: BuildSection.PatternPass, first: Int, from: Int, cost: PlanCost.Items, room: Int,
    ): Int {
        require(from >= 0) { "Batch start must not be negative: $from" }
        val order = p.order ?: return 0
        val layer = BlockPos.getY(s.positions[first])
        var left = room
        var i = from
        var looks = BATCH_SCAN
        while (left > 0 && looks-- > 0 && i < order.size && sc.pick.tasks.size < MAX_BATCH) {
            val c = order[i++]
            val key = cellKey(s.index, s.local[c])
            if (BlockPos.getY(s.positions[c]) != layer) break
            if (s.isDone(c) || reservations.containsKey(key) || !sc.worker.reaches(s.positions[c])) continue
            val other = s.states[c].planCost() as? PlanCost.Items ?: continue
            if (other.extra != null || other.item != cost.item || other.count > left) continue
            if (check(sc.level, sc.actor, s, c, sc.pick) != Check.READY || !sc.worker.keepsCarrierFree(s.positions[c])) continue
            s.setStatus(c, CellStatus.TODO)
            reserve(sc.worker, s, c)
            sc.pick.tasks.add(BuildTask(TaskKind.PLACE, key, BlockPos.of(s.positions[c]), s.states[c], other))
            left -= other.count
        }
        return room - left
    }

    private fun reserve(worker: BuildWorker, s: BuildSection, cell: Int) {
        reservations.put(cellKey(s.index, s.local[cell]), worker.workerId)
        s.reserved++
    }

    private enum class Check { NONE, FIX, READY }

    /** The world side of a plan block cell; sets its status. READY: it can go in now. */
    private fun check(level: ServerLevel, actor: ServerPlayer, s: BuildSection, cell: Int, pick: WorkerPick): Check {
        val pos = scratch.set(s.positions[cell])
        val tally = pick.tally
        if (!level.isLoaded(pos)) {
            s.note(tally, WhyNot.NOT_LOADED, s.positions[cell])
            return Check.NONE
        }
        val target = s.states[cell]
        val here = level.getBlockState(pos)
        if (planMatches(target, here)) {
            if (s.markDone(cell)) saveDirty = true
            return Check.NONE
        }
        if (fixableInPlace(target, here)) {
            val fixed = fixedState(target, here)
            if (!mayBuild(level, actor, pos, fixed) || !fixed.canSurvive(level, pos)) {
                s.note(tally, WhyNot.BUILD_REFUSED, s.positions[cell], fixed)
                s.setStatus(cell, CellStatus.BLOCKED)
                return Check.NONE
            }
            return Check.FIX
        }
        if (!here.isReplaceable()) {
            // Something came into the cell after CLEAR; the next CLEAR run cuts it.
            s.note(tally, WhyNot.OCCUPIED, s.positions[cell], here)
            s.clearClean = false
            return Check.NONE
        }
        val blocked = when {
            !partnerFree(level, actor, s, cell) -> WhyNot.PARTNER_BLOCKED
            !mayBuild(level, actor, pos, target) -> WhyNot.BUILD_REFUSED
            waterInHotLevel(level, target) -> WhyNot.WATER_IN_HOT
            else -> null
        }
        if (blocked != null) {
            s.note(tally, blocked, s.positions[cell], target)
            s.setStatus(cell, CellStatus.BLOCKED)
            return Check.NONE
        }
        if (!canStand(level, target, pos)) {
            s.note(tally, WhyNot.CANNOT_STAND, s.positions[cell], target)
            if (s.status(cell) != CellStatus.BLOCKED) s.setStatus(cell, CellStatus.DEFERRED)
            return Check.NONE
        }
        // A head under a chain stands in vanilla anywhere, but the plan hangs it: built first, out on its own, no
        // spot reached it and it held up the queue.
        if (waitsForAttachment(level, target, s.positions[cell])) {
            s.note(tally, WhyNot.NO_SUPPORT, s.positions[cell], target)
            if (s.status(cell) != CellStatus.BLOCKED) s.setStatus(cell, CellStatus.DEFERRED)
            return Check.NONE
        }
        // The order trusts plan neighbours earlier in it, but a skipped cell (missing, deferred, held by another
        // hand) left the next one in midair: a block goes in only on the world, or on a plan part that floats.
        if (target.cellKind() != CellKind.ATTACHED && !holdsInWorld(level, s.positions[cell])) {
            s.note(tally, WhyNot.NO_SUPPORT, s.positions[cell], target)
            if (s.status(cell) != CellStatus.BLOCKED) s.setStatus(cell, CellStatus.DEFERRED)
            return Check.NONE
        }
        // Checked at pick time too, so a worker does not fly to a cell an entity still stands in.
        if (!level.isUnobstructed(target, pos, CollisionContext.empty())) {
            s.note(tally, WhyNot.ENTITY_IN_CELL, s.positions[cell], target)
            if (s.status(cell) != CellStatus.BLOCKED) s.setStatus(cell, CellStatus.DEFERRED)
            return Check.NONE
        }
        return Check.READY
    }

    private val supportQueue = LongArrayFIFOQueue()
    private val supportSeen = LongOpenHashSet()
    private val supportCursor = BlockPos.MutableBlockPos()
    private var supportLevel: ServerLevel? = null
    private val worldSolid = CellTest { p ->
        val l = supportLevel
        l != null && solidAt(l, p)
    }
    private val planPending = CellTest { p ->
        val l = supportLevel
        l != null && planWantsBlockAt(l, BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p)) && !solidAt(l, p)
    }

    private fun solidAt(level: ServerLevel, p: Long): Boolean {
        supportCursor.set(p)
        return level.isLoaded(supportCursor) && !level.getBlockState(supportCursor).getCollisionShape(level, supportCursor).isEmpty
    }

    /** A block at [packed] holds on the world now, or belongs to a plan part that never touches it. */
    private fun holdsInWorld(level: ServerLevel, packed: Long): Boolean {
        supportLevel = level
        try {
            return placeSupport(packed, worldSolid, planPending, supportQueue, supportSeen) != PlaceHold.LATER
        } finally {
            supportLevel = null
        }
    }

    internal fun partnerFree(level: ServerLevel, actor: ServerPlayer, s: BuildSection, cell: Int): Boolean {
        val packed = s.partnerPos[cell]
        if (packed == NO_POS) return true
        val p = BlockPos.of(packed)
        return level.isLoaded(p) && level.getBlockState(p).isReplaceable() && mayBuild(level, actor, p, s.states[cell])
    }

    /**
     * A supply in the worker's reach with [count] of [item]. Null with [item] in [missingItems] when no supply
     * has it; null without when only supplies out of reach have it (the pick then notes the first of them).
     */
    private fun findSupply(sc: Picker, item: Item, count: Int): BlockPos? {
        if (item in missingItems) return null
        var far: BlockPos? = null
        var spoken = false
        for (pos in supplies) {
            // The user's rule: every take runs the block permission check, as every break and place does.
            if (!sc.level.isLoaded(pos) || !mayEdit(sc.level, sc.actor, pos)) continue
            val container = HopperBlockEntity.getContainerAt(sc.level, pos) ?: continue
            val stock = container.countOf(item)
            if (stock < count) continue
            // Hands already on their way take their share first.
            if (stock - pendingFetch(pos, item, sc.worker.workerId) < count) {
                spoken = true
                continue
            }
            if (sc.worker.reaches(pos.asLong())) return pos
            if (far == null) far = pos
        }
        supplySpoken = far == null && spoken
        when {
            far != null -> sc.pick.noteFarSupply(far, item)
            !spoken -> missingItems.add(item)
        }
        return null
    }

    /**
     * Drops linked supplies that hold no container now in a loaded chunk (broken or replaced), or whose ship is
     * gone, with a log line only: the user asked for no message. An unloaded supply is kept. True when any was dropped.
     */
    internal fun pruneSupplies(level: ServerLevel): Boolean {
        var dropped = false
        val it = supplies.iterator()
        while (it.hasNext()) {
            val pos = it.next()
            val why = staleSupplyWhy(level, pos) ?: continue
            BuildLog.job(this, JobWhy.SUPPLY_DROPPED, "supply ${pos.toShortString()} unlinked: $why")
            it.remove()
            dropped = true
        }
        if (dropped) {
            wake()
            saveDirty = true
        }
        return dropped
    }

    /** A table scan: the supplies become the containers [linked] near the table now. True when they changed. */
    internal fun relinkSupplies(linked: List<BlockPos>, max: Int): Boolean {
        val next = syncedSupplies(supplies, linked, max)
        if (next == supplies) return false
        for (pos in supplies) {
            if (pos !in next) BuildLog.job(this, JobWhy.SUPPLY_DROPPED, "supply ${pos.toShortString()} unlinked: it is no longer near the table")
        }
        supplies.clear()
        supplies.addAll(next)
        wake()
        saveDirty = true
        return true
    }

    /** A Clawhod is in this job, loaded or not. It leaves only by a real signal: an order, a break or another job. */
    internal val hasWorkers: Boolean get() = members.isNotEmpty()

    internal fun addMember(clawhod: UUID) {
        if (members.add(clawhod)) saveDirty = true
    }

    internal fun removeMember(clawhod: UUID) {
        if (members.remove(clawhod)) saveDirty = true
    }

    /** The section and its [planCellLocal] for a task key, while the section is in work. */
    internal inline fun <T> withLocal(key: Long, action: (BuildSection, Int) -> T): T? {
        val s = section(cellKeySection(key)) ?: return null
        return action(s, cellKeyLocal(key))
    }

    internal fun isReservedBy(cell: Long, worker: UUID): Boolean = reservations.get(cell) == worker

    /** Changes when a cell of the job is done, the world in its cells changes, or a section starts or ends. */
    internal fun progressStamp(): Long {
        var stamp = progress.completeCount.toLong()
        for (i in active.indices) stamp = stamp * PROGRESS_PRIME + active[i].index + active[i].progressMark * PROGRESS_PRIME
        return stamp
    }

    internal fun release(cell: Long, worker: UUID) {
        if (reservations.get(cell) != worker) return
        reservations.remove(cell)
        unreserve(cell)
    }

    private fun unreserve(cell: Long) {
        val s = section(cellKeySection(cell)) ?: return
        s.reserved--
        s.clearHeld.clear(cellKeyLocal(cell))
    }

    /** The supply had the item at pick time but not at pick-up time. */
    internal fun missed(cell: Long, worker: UUID, item: Item) {
        missingItems.add(item)
        withLocal(cell) { s, l ->
            val c = s.cellAt(l)
            when {
                item == Items.BUCKET -> s.setLocalStatus(l, CellStatus.NO_BUCKET)
                c >= 0 -> s.setStatus(c, CellStatus.MISSING)
            }
        }
        release(cell, worker)
    }

    internal fun releaseAll(worker: UUID) {
        standSpots.removeLong(worker)
        fetchClaims.remove(worker)
        standReach.removeDouble(worker)
        standFaces.removeInt(worker)
        standHeight.removeDouble(worker)
        val it = reservations.long2ObjectEntrySet().iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value != worker) continue
            unreserve(e.longKey)
            it.remove()
        }
    }

    private fun loadClearing(tag: CompoundTag) {
        clearing = tag.getBoolean(CLEARING)
        if (!clearing) return
        val bits = BitSet.valueOf(tag.getLongArray(CLEARED))
        var i = bits.nextSetBit(0)
        while (i in 0 until grid.count) {
            markCleared(i)
            i = bits.nextSetBit(i + 1)
        }
        saveDirty = false
    }

    fun save(): CompoundTag = CompoundTag().also { tag ->
        tag.putUUID(ID, id)
        tag.putUUID(OWNER, owner)
        tag.putLong(TABLE, table.asLong())
        tag.putUUID(TABLE_ID, tableId)
        tag.put(MEMBERS, ListTag().also { list -> for (m in members) list.add(NbtUtils.createUUID(m)) })
        tag.put(REF, ref.toTag())
        tag.put(ANCHOR, anchor.toTag())
        tag.put(SUPPLIES, LongArrayTag(LongArray(supplies.size) { supplies[it].asLong() }))
        tag.put(PROGRESS, progress.save())
        tag.putBoolean(CLEARING, clearing)
        if (clearing) tag.put(CLEARED, LongArrayTag(cleared.toLongArray()))
    }

    companion object {
        private const val HALF = 0.5
        private const val BOX_CORNERS = 8
        /** Linked supplies one build can hold, from the server config. */
        val MAX_SUPPLIES: Int get() = buildTuning().maxLinkedSupplies

        /** Sections in work at one time; memory per job stays near this many 16^3 sections. */
        const val MAX_ACTIVE = 4
        private const val SWEEP_PER_TICK = 64
        private const val COMPLETE_SKIPS_PER_TICK = 4096
        private const val SCANS_PER_PICK = 1024
        /** Cells a PLACE batch looks ahead for more of its block type. */
        private const val BATCH_SCAN = 256
        private const val MAX_BATCH = 64
        private const val NO_BATCH = -1
        private const val LAST = PLAN_SECTION_EDGE - 1
        private const val ID = "Id"
        private const val OWNER = "Owner"
        private const val REF = "Ref"
        private const val ANCHOR = "Anchor"
        private const val SUPPLIES = "Supplies"
        private const val PROGRESS = "Progress"
        private const val PROGRESS_PRIME = 31L
        private val ATTACH_DIRECTIONS = Direction.values()
        private const val CLEARING = "Clearing"
        private const val CLEARED = "Cleared"
        private const val TABLE = "Table"
        private const val TABLE_ID = "TableId"
        private const val MEMBERS = "Members"

        fun load(tag: CompoundTag): BuildJob? {
            if (!tag.hasUUID(ID) || !tag.hasUUID(OWNER) || !tag.contains(TABLE, Tag.TAG_LONG.toInt()) || !tag.hasUUID(TABLE_ID)) return null
            val ref = tag.getCompound(REF).toPlanRef() ?: return null
            if (!PlanGrid.fits(ref.size)) return null
            val anchor = tag.getCompound(ANCHOR).toPlanAnchor() ?: return null
            val supplies = tag.getLongArray(SUPPLIES).mapTo(ArrayList()) { BlockPos.of(it) }
            return BuildJob(tag.getUUID(ID), tag.getUUID(OWNER), BlockPos.of(tag.getLong(TABLE)), tag.getUUID(TABLE_ID), ref, anchor, supplies).also {
                for (m in tag.getList(MEMBERS, Tag.TAG_INT_ARRAY.toInt())) it.members.add(NbtUtils.loadUUID(m))
                it.progress.load(tag.getCompound(PROGRESS))
                it.loadClearing(tag)
            }
        }
    }
}
