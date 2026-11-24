@file:OptIn(org.valkyrienskies.core.api.util.GameTickOnly::class, org.valkyrienskies.core.api.VsBeta::class)

package org.shipwrights.enderkinesis.construction

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.Container
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.PickaxeItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.gameevent.GameEvent
import org.joml.Vector3dc
import org.joml.Matrix4d
import org.joml.primitives.AABBd
import org.valkyrienskies.mod.common.util.ShipCollisionQuery
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.spell.SpellPlatform
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.shipwrights.enderkinesis.block.ClawhodBlock
import org.shipwrights.enderkinesis.blockentity.ClawhodBlockEntity
import org.shipwrights.enderkinesis.entity.ConstriliarCargoShape
import org.shipwrights.enderkinesis.entity.ConstriliarEntity
import org.shipwrights.enderkinesis.entity.ConstriliarImpactSounds
import org.shipwrights.enderkinesis.entity.ConstriliarSurfaceNavigator
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.ArrayDeque
import java.util.UUID
import java.util.function.Predicate

/**
 * The build worker of one clawhod. Both hands work at once, each on its own trip: from the gem to a supply in
 * reach, to its cells, and back. Each hand reserves cells by its own id. When neither hand has open work in reach,
 * the clawhod asks the walker that carries it to stand at a new work position. Server thread only.
 */
internal class ClawhodWorker(private val be: ClawhodBlockEntity) {

    var jobId: UUID? = null
        private set

    /** Carrier and job lines; each hand has its own note. */
    private val note = ReasonNote()
    private val hands = Array(CLAWHOD_HANDS) { Hand(it) }

    /** The slot of hand [hand]'s working tool (a carried tool does not count), or -1. */
    internal fun workSlotOf(hand: Int): Int = hands[hand].workSlot()

    /** The gem in world space, and a scratch point. */
    private val gem = Vector3d()
    private val target = Vector3d()

    /** Work positions asked for since the last find, so the carrier is not sent back to one that gave nothing. */
    private val tried = LongArray(TRIED_MAX)
    private var triedCount = 0
    private var triedNext = 0
    /**
     * Two floors per column (nearest gem height, lowest) and one wall spot, with their [SPOT_GROUND]..[SPOT_TOP]
     * class and support face ([FLOOR_FACE] for a floor; a wall spot is its body cell, see [spotGemX]).
     */
    private val floors = LongArray(WORK_SPOT_OFFSETS.size / 2 * SPOTS_PER_COLUMN)
    private val floorClasses = ByteArray(floors.size)
    private val floorFaces = ByteArray(floors.size)
    private var floorCount = 0
    private var colNearest = NO_POS
    private var colLowest = NO_POS
    private var colWall = NO_POS
    private var colWallFace = FLOOR_FACE
    private var colCeiling = NO_POS
    /** The last move to a spot failed (CARRIER_STUCK): the next spot is the closest that reaches, not the furthest. */
    private var spotFailed = false
    private val failedSpots = SpotBlacklist(FAILED_SPOTS, FAILED_SPOT_TICKS)
    /** Carrier trips per far cell since the job last progressed; past [FAR_TRIPS_MAX] the cell joins [farGivenUp]. */
    private val farTrips = Long2IntOpenHashMap()
    /** Far cells the hands skip until the job progresses: one far cell that no spot reached starved the rest. */
    private val farGivenUp = LongOpenHashSet()
    private var farStamp = Long.MIN_VALUE
    // A spot search runs in the walk frame: the plan ship's shipyard while the carrier stands on that ship's deck, else
    // the world (VS2 keeps shipyard data in the shipyard). Targets are exact points in it; only the order makes a world point.
    private var walkShip: Ship? = null
    private var walkLift = 0.0
    // The carrier-scaled reach and the walker's stop margin in walk-frame units, and the floor search they allow.
    private var walkReach = CLAWHOD_REACH
    private var walkMargin = WORK_SPOT_MARGIN
    private var walkRings = WORK_SPOT_RINGS
    private var walkAbove = CLAWHOD_REACH.toInt()
    private var walkBelow = CLAWHOD_REACH.toInt() - 1

    /** The world scale of the ship that carries this Clawhod (1 off ships), and the reach in world blocks it gives. */
    private var carrierScale = 1.0
    private var reachNow = CLAWHOD_REACH
    private val walkGem = Vector3d()
    private val framePt = Vector3d()
    private val targetPts = DoubleArray(CLAWHOD_HANDS * 3)
    private val supplyPt = DoubleArray(3)
    /** Sampled pending cells of the target's layer: a spot that reaches more of them wins. */
    private val coverPts = DoubleArray(COVER_CAP * 3)
    /** Cells the carrier's body blocks now: a move for far work must not stand the body in them again. */
    private val avoidPts = DoubleArray(CLAWHOD_HANDS * 2 * 3)
    private var avoidCount = 0

    // The bases the carrier's whole body reaches over surfaces from where it stands, flooded at most once per tick. The
    // gate holds only when the flood ended under its cap; a capped flood proves nothing about the bases it did not reach.
    private val routeSeen = LongOpenHashSet()
    private var routeAt = Long.MIN_VALUE
    private var routeResult = WayOut.UNKNOWN
    private var routeGate = false
    private var routeUsed = false
    private val noExit = OutOfPlan { _, _, _ -> false }
    private val surfaceRoom = BodyRoom { x, y, z ->
        x in routeMinX..routeMaxX && z in routeMinZ..routeMaxZ && holdsOnSurface(x, y, z)
    }
    private val solidCell = BodyRoom { x, y, z -> solidAt(x, y, z) }
    // The flood stays in the box round the walker and the target's spot rings, so it can end under its cap.
    private var routeMinX = 0
    private var routeMaxX = 0
    private var routeMinZ = 0
    private var routeMaxZ = 0
    private var routeTarget = NO_POS
    private var routeFrame = -1L
    /** The frame of the flood that runs now: a ship's shipyard, or null for the world, where ship blocks count too. */
    private var roomFrame: Ship? = null
    /** Other walkers' bodies block the flood that runs now: yes for the route check, no for the walls-in flood. */
    private var roomWalkersBlock = false
    private val surfaceCursor = BlockPos.MutableBlockPos()

    /** Floors the last search dropped: other carriers, failed lately, no route, too few footholds to work from. */
    private var takenFloors = 0
    private var failedFloors = 0
    private var routeFloors = 0
    private var beamFloors = 0

    /** Carrier moves sent so far: a hand holds a block for a far cell over at most [HOLD_MOVES] of them. */
    private var movesSent = 0

    /** The carrier's whole body, per layer, as its route planner sees it; null with no carrier. */
    private var bodyShape: ConstriliarCargoShape? = null

    /** The carrier of the current floor search, the walkers round it, and why its planner refused floors. */
    private var standWalker: ConstriliarEntity? = null
    private var standOthers: List<Vec3> = emptyList()
    private val standRejects = IntArray(ConstriliarSurfaceNavigator.STAND_NAMES.size)

    /** An item the hands fetch at [stockAt] before the carrier goes on to the cells that need it. */
    private var stockItem: Item? = null
    private var stockAt: BlockPos? = null

    // The carrier's way out of the plan, flooded once per tick; a pick may test a few cells more.
    private var floodAt = Long.MIN_VALUE
    private var floodResult = WayOut.OUT
    private var floodsLeft = 0
    private var floodJob: BuildJob? = null

    /** Other Clawhods of this job that hold a tool, found once per tick: bare hands leave their cuts to them. */
    private val toolPeers = ArrayList<ClawhodWorker>()
    /** Other Clawhods of this job, found once per tick: no block goes into their carriers or claimed spots. */
    private val jobPeers = ArrayList<ClawhodWorker>()
    /** A cell another Clawhod of the job waits on because this carrier holds it; [NO_POS] for none. */
    private var blockedCell = NO_POS

    /** The job's supply epoch this tick: a refused fetch waits until it changes. */
    private var supplyEpochNow = -1
    private var floodExcluded = NO_POS
    private val reachable = LongOpenHashSet()
    private val floodSeen = LongOpenHashSet()
    private val floodQueue = LongArrayFIFOQueue()
    private val roomCursor = BlockPos.MutableBlockPos()
    private val bodyRoom = BodyRoom { x, y, z -> bodyFits(x, y, z) }
    private val outOfPlan = OutOfPlan { x, y, z ->
        val j = floodJob
        j == null || planOut(j, x, y, z)
    }

    /** The floor the carrier was sent to, or [NO_POS]. No hand starts a trip and no new move goes out until it ends. */
    private var moveSpot = NO_POS
    private var moveCheckAt = 0L
    private var moveFails = 0
    /** Move checks in a row with the carrier in one place; the walker can stand still on a route and never fail it. */
    private var moveStill = 0
    private var moveLastX = Double.NaN
    private var moveLastY = Double.NaN
    private var moveLastZ = Double.NaN

    private var carrierShip = NO_SHIP
    private var carrierId: UUID? = null
    private val scratch = BlockPos.MutableBlockPos()
    private val handCell = BlockPos.MutableBlockPos()

    private enum class Stage { HOME, FETCH, CELL, UNLOAD, RETURN, HANDOFF, LOOSE }

    private val level: ServerLevel get() = be.level as ServerLevel

    private inline fun say(job: BuildJob?, why: Enum<*>, arg: Int = 0, detail: () -> String) =
        note.report(job, jobId, be.workerId, "CARRIER", why, arg, detail)

    private inline fun say(h: Hand, job: BuildJob?, why: Enum<*>, arg: Int = 0, detail: () -> String) =
        h.note.report(job, jobId, h.workerId, "HAND${h.index}:${h.stage.name}", why, arg, detail)

    /** Joins [job]; a clawhod works on one job at a time. */
    fun join(job: BuildJob) {
        if (jobId == job.id) return
        jobId?.let { old ->
            BuildJobs.get(level).job(old)?.let {
                releaseAll(it)
                it.removeMember(be.workerId)
            }
        }
        stopMine()
        breakLink("the Clawhod joined a job")
        val halted = forgetJob(level)
        jobId = job.id
        job.addMember(be.workerId)
        note.clear()
        be.setChanged()
        say(job, ClawhodWhy.JOINED) { "joined the build ${job.summary()}$halted" }
    }

    /** Leaves the job: its cells go back, and the hands that are out fly home. The hands keep their items. */
    fun stop(level: ServerLevel, why: String) {
        val id = jobId ?: return
        val job = BuildJobs.get(level).job(id)
        job?.let {
            releaseAll(it)
            it.removeMember(be.workerId)
        }
        val halted = forgetJob(level)
        say(job, ClawhodWhy.LEFT) { "$why$halted" }
        jobId = null
        be.setChanged()
        for (h in hands) flyHome(h)
    }

    /**
     * Drops what the last job left in this worker: the far cells and supplies of the picks, a stock-up and a
     * carrier walk. Without this a new job sent the carrier back to the old site. Returns a log note, or "".
     */
    private fun forgetJob(level: ServerLevel): String {
        endStock()
        endWant()
        relayBackoff = NO_SIG
        for (h in hands) {
            h.dropWork()
            h.pick.clearFar()
            h.farSince = Long.MAX_VALUE
            h.resetIdle()
            h.refusal.clear()
        }
        triedCount = 0
        spotFailed = false
        failedSpots.clear()
        farTrips.clear()
        farGivenUp.clear()
        farStamp = Long.MIN_VALUE
        blockedCell = NO_POS
        val spot = moveSpot
        if (spot == NO_POS) return ""
        moveSpot = NO_POS
        val walker = carrier(level)
        if (walker == null || walker.moveClaim != be.workerId || walker.orderSettled()) return ""
        walker.commandMoveTo(walker.position())
        return "; the carrier stops its walk to ${BlockPos.of(spot).toShortString()}"
    }

    /** The block entity unloads or moves: the cells go back, the job stays for when it comes back. */
    fun unload(level: ServerLevel) {
        jobId?.let { BuildJobs.get(level).job(it)?.let(::releaseAll) }
        breakLink("the Clawhod unloaded")
        relayReturn = null
        endWant()
        BuildJobs.get(level).relays.remove(be.workerId, this)
        for (h in hands) {
            closeLid(h)
            endPunch(level, h)
            h.dropWork()
            h.stage = Stage.HOME
        }
    }

    /**
     * The user's rule: a hand that takes from or puts into an inventory opens it first, as a player would, and acts
     * once the lid is up. True when the hand may act now.
     */
    private fun lidReady(h: Hand, inventory: BlockPos? = h.supply): Boolean {
        val at = inventory ?: return true
        if (h.openAt == at) return true
        closeLid(h)
        val lids = BuildJobs.get(level).relays.lids
        if (!level.isLoaded(at) || !lids.animates(level, at)) return true
        lids.open(level, at, minerActor(level))
        h.openAt = at
        h.arriveAt = level.gameTime + LID_OPEN_TICKS
        return false
    }

    /** The hand leaves the inventory it opened: the last hand out closes it. */
    private fun closeLid(h: Hand) {
        val at = h.openAt ?: return
        h.openAt = null
        BuildJobs.get(level).relays.lids.close(level, at, minerActor(level))
    }

    private fun releaseAll(job: BuildJob) {
        for (h in hands) job.releaseAll(h.workerId)
        carrierId?.let(job::releaseAll)
        endStock()
    }

    fun tick(level: ServerLevel) {
        for (h in hands) h.syncCarried()
        tickWork(level)
        for (h in hands) h.syncCarried()
    }

    private fun tickWork(level: ServerLevel) {
        val id = jobId
        BuildJobs.get(level).relays.touch(be.workerId, this)
        if (id == null) {
            refreshGem(level)
            scanLoose(level)
            for (h in hands) if (h.stage == Stage.RETURN && level.gameTime >= h.arriveAt) h.stage = Stage.HOME
            tickRelay(level)
            // A miner's own load going up a chain does not stop the tool hand.
            if (relay?.own == false || relayReturn != null) return
            if (level.hasNeighborSignal(be.blockPos)) {
                tickMine(level)
            } else {
                stopMine()
                tickIdleLoose(level)
            }
            return
        }
        refreshGem(level)
        scanLoose(level)
        // The user's rule: a link runs only while idle, so own work ends it.
        if (relay != null && hands.any { !it.relaying && it.tasks.isNotEmpty() }) breakLink("own work came first")
        tickRelay(level)
        val job = BuildJobs.get(level).job(id)
        if (relayWant != null && level.gameTime % IDLE_RETRY_TICKS == 0L) settleRelay(job)
        if (job == null) {
            val halted = forgetJob(level)
            say(null, ClawhodWhy.JOB_GONE) { "job finished or cancelled; the hands keep their items$halted" }
            jobId = null
            be.setChanged()
            for (h in hands) flyHome(h)
            return
        }
        // The user's rule: it builds while it moves. Picks use the gem where it is now; a cell that left the
        // reach when the hand gets there goes back to the job.
        if (moveSpot != NO_POS) checkMove(level, job)
        // Stand spots are claimed per carrier, so clawhods that ride one body never crowd each other out.
        if (moveSpot == NO_POS && level.gameTime % IDLE_RETRY_TICKS == 0L) claimStandingFloor(job)
        floodJob = job
        floodsLeft = FLOODS_PER_TICK
        supplyEpochNow = job.supplyEpoch
        toolPeers.clear()
        jobPeers.clear()
        for (w in BuildJobs.get(level).relays.peersOf(id, level.gameTime)) {
            if (w === this) continue
            jobPeers.add(w)
            if (w.holdsTool()) toolPeers.add(w)
        }
        for (h in hands) {
            when {
                h.relaying -> Unit
                h.stage == Stage.HOME -> startTrip(level, job, h)
                level.gameTime >= h.arriveAt -> arrive(level, job, h)
            }
        }
    }

    /**
     * Ends the move to [moveSpot] when the walker arrives, when it fails on two checks in a row (the route search
     * proved it trapped, or no route is left), or when it stays in one place for [MOVE_STILL_CHECKS] checks; that
     * spot is then skipped. No new move goes out before it ends.
     */
    private fun checkMove(level: ServerLevel, job: BuildJob) {
        val walker = carrier(level)
        // Another clawhod (or the player) sent the carrier elsewhere: this move is over.
        if (walker == null || walker.orderSettled() || walker.moveClaim != be.workerId) {
            if (walker != null && walker.orderSettled()) spotFailed = false
            moveSpot = NO_POS
            return
        }
        if (level.gameTime < moveCheckAt) return
        moveCheckAt = level.gameTime + IDLE_RETRY_TICKS
        val still = walker.distanceToSqr(moveLastX, moveLastY, moveLastZ) < MOVE_STILL_SQ
        moveLastX = walker.x
        moveLastY = walker.y
        moveLastZ = walker.z
        moveStill = if (still) moveStill + 1 else 0
        val stalled = moveStill >= MOVE_STILL_CHECKS
        if (!stalled && !walker.orderFailed()) {
            moveFails = 0
            return
        }
        if (!stalled && ++moveFails < MOVE_FAIL_CHECKS) return
        val spot = moveSpot
        val why = if (stalled) "it stands still" else "trapped or no route"
        say(job, ClawhodWhy.CARRIER_STUCK, BlockPos.getX(spot) xor BlockPos.getZ(spot)) {
            "the carrier cannot reach ${BlockPos.of(spot).toShortString()} ($why); the next spot is tried"
        }
        moveSpot = NO_POS
        spotFailed = true
        failedSpots.add(spot, level.gameTime)
        for (h in hands) h.resetIdle()
    }

    @Suppress("ReturnCount") // One return per pick outcome reads clearer than nested branches.
    private fun startTrip(level: ServerLevel, job: BuildJob, h: Hand) {
        if (!job.isReady || job.picksLeft <= 0) return
        // A supply changed (a tool may be back): the rest pass ends, so the next pass looks for the right tool.
        if (h.passes.restPass && h.restEpoch != job.supplyEpoch) h.passes.startPass()
        if (stockUp(job, h)) return
        refreshFarGiveUp(job)
        if (level.gameTime < h.idleUntil && h.idleEpoch == job.supplyEpoch) return
        if (startLoose(h, idle = false)) return
        val p = h.pick
        if (job.pick(level, job.actor(level), h, p)) return takeWork(job, h)
        if (!p.searchedAll) return
        if (passSwap(level, job, h)) return
        if (h.hasCargo()) {
            // The user's rule: a hand with nowhere to place its block puts it away and grabs one a cell in reach needs.
            if (swapFor(level, job, h)) return startUnload(level, job, h)
            // Blocks for a cell that is only out of reach stay in the hand while the carrier takes them there.
            if (!holdsForFarCell(h) || !keepsForFar(job, h)) return startUnload(level, job, h)
        }
        h.idleUntil = level.gameTime + IDLE_RETRY_TICKS
        h.idleEpoch = job.supplyEpoch
        h.noWork = true
        if (p.farCell != NO_POS && h.farSince == Long.MAX_VALUE) h.farSince = level.gameTime
        // A move goes out only when neither hand has work in reach and the last move has ended.
        if (moveSpot != NO_POS || hands.any { !it.noWork || it.stage != Stage.HOME }) return
        if (carrierTrapped(level, job)) return
        // Far work first: that move also takes the body off the cells it blocks. A step-aside alone builds nothing.
        val older = hands.filter { it.pick.farCell != NO_POS }.minByOrNull { it.farSince }
        if (older != null && askMove(level, job, older)) return
        if (entityBlocks(level, job)) return
        if (carrierInWay(level, job)) return
        if (stepOutForOthers(level, job)) return
        if (older == null) reportIdle(level, job, h)
    }

    /**
     * An entity stands in a cell the hands want: logs which one, once per change. When it is the carrier itself,
     * the carrier steps to a floor that keeps the cell in reach (furthest first). True when the carrier was sent.
     */
    private fun entityBlocks(level: ServerLevel, job: BuildJob): Boolean {
        val h = hands.firstOrNull { it.pick.reportTally().has(WhyNot.ENTITY_IN_CELL) } ?: return false
        val cell = h.pick.reportTally().firstPosOf(WhyNot.ENTITY_IN_CELL)
        val blocker = level.getEntities(null as Entity?, AABB(cell)) { it.blocksBuilding && !it.isSpectator }.firstOrNull()
            ?: return false
        val walker = carrier(level)
        val own = walker != null && (blocker === walker || blocker.uuid == walker.uuid)
        say(job, ClawhodWhy.ENTITY_BLOCKS, blocker.id) {
            val who = when {
                own -> "its own carrier"
                blocker is Player -> "the player ${blocker.gameProfile.name}"
                else -> "a ${BuiltInRegistries.ENTITY_TYPE.getKey(blocker.type)}"
            }
            "cell ${cell.toShortString()} waits: $who stands in it"
        }
        if (!own) return false
        val ready = readyCarrier(level, job, "the carrier stands in cell ${cell.toShortString()}") ?: return true
        clearFor(ready, job)
        remember(BlockPos.offset(walkerBase(ready), 0, -1, 0))
        val other = hands.firstOrNull { it !== h && it.pick.farCell != NO_POS }?.pick?.farCell ?: NO_POS
        val spot = findSpot(level, job, cell.asLong(), NO_POS, other, cell.y)
        if (spot == NO_POS) {
            say(job, ClawhodWhy.NO_WORK_POSITION) { "no floor for the carrier to step aside to from cell ${cell.toShortString()}${rejectNote()}" }
            return true
        }
        sendCarrier(ready, spot)
        say(job, ClawhodWhy.CARRIER_STEP_ASIDE, BlockPos.getX(spot) xor BlockPos.getZ(spot)) {
            "the carrier steps aside to ${BlockPos.of(spot).toShortString()} out of cell ${cell.toShortString()}${spotNote(job, spot)}"
        }
        return true
    }

    /**
     * Tool passes in a build job, as in mining (the user's rule): a tool hand with no cut left for its tool swaps
     * at a supply in reach to a tool for a cut that is left; with none, the rest pass breaks the rest, with a
     * pickaxe from a supply when there is one. True when the hand flew.
     */
    private fun passSwap(level: ServerLevel, job: BuildJob, h: Hand): Boolean {
        val tool = h.tool()
        if (tool.isEmpty || h.passes.otherCount == 0 || h.passes.restPass) return false
        val actor = job.actor(level)
        val usable = { pos: BlockPos -> reaches(pos.asLong()) && mayEdit(level, actor, pos) }
        val skip = { pos: BlockPos, item: Item -> h.refuses(item) || job.toolStockLeft(level, pos, item, h.workerId) <= 0 }
        val next = h.passes.firstFor { bestToolIn(level, job.supplies, it, matchOnly = true, usable = usable, skip = skip) }
        if (next != null) {
            h.passes.startPass()
            say(h, job, ClawhodWhy.TOOL_PASS, BuildLog.itemId(next.item)) {
                "no cut in reach is for its ${BuildLog.itemName(tool.item)}; swaps to a ${BuildLog.itemName(next.item)} at ${next.pos.toShortString()}"
            }
            return toolTrip(job, h, next)
        }
        if (walkForTool(level, job, h, actor, skip)) return true
        val sample = h.passes.other(0)
        h.passes.startRest()
        h.restEpoch = job.supplyEpoch
        val pick = if (sample != null && tool.item !is PickaxeItem) {
            bestToolIn(level, job.supplies, sample, matchOnly = false, usable = usable, skip = skip)?.takeIf { it.item is PickaxeItem }
        } else {
            null
        }
        say(h, job, ClawhodWhy.REST_PASS) {
            "no supply in reach has a tool for the cuts left; the rest pass breaks them" +
                if (pick != null) " with a pickaxe from ${pick.pos.toShortString()}" else ""
        }
        h.resetIdle()
        return pick != null && toolTrip(job, h, pick)
    }

    /**
     * The user's rule: the right tool for the cuts left is only in a job supply out of reach, so the carrier walks
     * there (a tool fetch, not a relay); the hand swaps on the next pass. True while that walk runs or was sent.
     */
    private fun walkForTool(level: ServerLevel, job: BuildJob, h: Hand, actor: ServerPlayer, skip: (BlockPos, Item) -> Boolean): Boolean {
        if (moveSpot != NO_POS) return waitForWalk(level, job, h)
        val walker = carrier(level) ?: return false
        val far = h.passes.firstFor { bestToolIn(level, job.supplies, it, matchOnly = true, usable = { pos -> mayEdit(level, actor, pos) }, skip = skip) }
            ?: return false
        // The walker walks for another Clawhod on its body: wait for it rather than fall back to the pickaxe.
        if (carrierWalks(walker)) return waitForWalk(level, job, h)
        return askMoveToSupply(level, job, far.pos, "take a ${BuildLog.itemName(far.item)}") && waitForWalk(level, job, h)
    }

    /** The hand waits at home, as an idle hand does, while the carrier walks. Returns true. */
    private fun waitForWalk(level: ServerLevel, job: BuildJob, h: Hand): Boolean {
        h.idleUntil = level.gameTime + IDLE_RETRY_TICKS
        h.idleEpoch = job.supplyEpoch
        return true
    }

    /** Sends [h] to fetch the tool at [source]; its tool goes back there. Returns true. */
    private fun toolTrip(job: BuildJob, h: Hand, source: ToolSource): Boolean {
        h.dropWork()
        h.supply = source.pos
        h.fetchItem = source.item
        h.fetchCount = 1
        job.claimFetch(h.workerId, source.pos, source.item, 1)
        h.pos.set(gem)
        h.stage = Stage.FETCH
        fly(h, source.pos)
        return true
    }

    private fun takeWork(job: BuildJob, h: Hand) {
        busyEpoch++
        relayBackoff = NO_SIG
        h.passes.clearOthers()
        val p = h.pick
        h.note.clear()
        h.farSince = Long.MAX_VALUE
        for (other in hands) other.noWork = false
        triedCount = 0
        sortFarFirst(job, p.tasks)
        h.tasks.addAll(p.tasks)
        h.supply = p.supply
        h.fetchItem = p.fetchItem
        h.fetchCount = p.fetchCount
        h.extraSupply = p.extraSupply
        h.extraItem = p.extraItem
        h.extraCount = p.extraCount
        h.kitLeg = p.kitLeg
        h.cutChain = false
        // A chained cut flies on from the cell it just cut.
        if (h.stage == Stage.HOME) h.pos.set(gem)
        say(h, job, ClawhodWhy.WORK_TAKEN, h.tasks.size) {
            val first = h.tasks.peekFirst()
            "took ${h.tasks.size} ${first?.kind} task(s) from ${first?.pos?.toShortString()}; " +
                "fetch=${h.fetchItem?.let { BuildLog.itemName(it) }} x${h.fetchCount}"
        }
        val from = h.supply
        if (from != null) {
            h.fetchItem?.let { job.claimFetch(h.workerId, from, it, h.fetchCount) }
            h.stage = Stage.FETCH
            fly(h, from)
        } else {
            goToTask(h)
        }
    }

    /** The cells beside the carrier's stand spot go last in a batch: the next spot may build them, out of the body's way. */
    private fun sortFarFirst(job: BuildJob, tasks: ArrayList<BuildTask>) {
        if (tasks.size < 2) return
        val spot = job.standSpotOf(carrierId ?: return)
        if (spot == NO_POS) return
        sortX = BlockPos.getX(spot) + HALF
        sortZ = BlockPos.getZ(spot) + HALF
        sortShip = frameOfBlock(spot)
        tasks.sortWith(farFirst)
        sortShip = null
    }

    // The stand spot's own frame (world or a shipyard): each task's cell goes into it before its distance is taken.
    private var sortX = 0.0
    private var sortZ = 0.0
    private var sortShip: Ship? = null
    private val sortPoint = Vector3d()
    private val farFirst = Comparator<BuildTask> { a, b -> standDistSq(b.pos).compareTo(standDistSq(a.pos)) }

    private fun standDistSq(pos: BlockPos): Double {
        toFrame(sortShip, pos.asLong(), sortPoint)
        val dx = sortPoint.x - sortX
        val dz = sortPoint.z - sortZ
        return dx * dx + dz * dz
    }

    private fun arrive(level: ServerLevel, job: BuildJob, h: Hand) {
        when (h.stage) {
            Stage.FETCH -> if (lidReady(h)) fetch(level, job, h)
            Stage.CELL -> work(level, job, h)
            Stage.UNLOAD -> if (lidReady(h)) unloadAt(level, job, h)
            Stage.RETURN -> h.stage = Stage.HOME
            Stage.LOOSE -> looseArrive(h, idle = false)
            Stage.HOME, Stage.HANDOFF -> Unit
        }
    }

    @Suppress("ReturnCount") // One return per fetch outcome reads clearer than nested branches.
    private fun fetch(level: ServerLevel, job: BuildJob, h: Hand) {
        job.releaseFetch(h.workerId)
        val from = h.supply ?: return goToTask(h)
        val item = h.fetchItem ?: return goToTask(h)
        if (!reaches(from.asLong())) {
            say(h, job, ClawhodWhy.NO_SUPPLY_IN_REACH) { "supply ${from.toShortString()} left the reach before the fetch; tasks dropped" }
            return dropAndReturn(job, h)
        }
        val container = HopperBlockEntity.getContainerAt(level, from)
        // The user's rule: every take runs the block permission check, as every break and place does.
        if (container != null && !mayEdit(level, job.actor(level), from)) {
            say(h, job, ClawhodWhy.SUPPLY_REFUSED, BuildLog.itemId(item)) {
                "supply ${from.toShortString()} refused: ${BuildLog.permission(level, job.actor(level), from, null, null)}; its cells wait"
            }
            h.refusal.refuse(item, job.supplyEpoch, h.heldBlock(), h.heldCount())
            return dropAndReturn(job, h)
        }
        if (isToolItem(item)) return fetchJobTool(level, job, h, item, from, container)
        // Before depositOthers: the hand keeps the pot or cake that this extra goes into.
        if (h.kitLeg) return fetchKitExtra(level, job, h, item, from, container)
        if (container != null) depositOthers(h, container, item)
        if (item == Items.BUCKET) {
            val free = h.slots.any { it.isEmpty }
            if (container == null || !free || !container.takeItems(Items.BUCKET, 1)) return missAll(level, job, h, item, from)
            pickupInsert(h.slots, ItemStack(Items.BUCKET), 1)
            be.handsChanged()
            return goToTask(h)
        }
        val stock = container?.countOf(item) ?: 0
        val firstCost = h.tasks.peekFirst()?.cost?.count ?: 1
        if (container != null && stock < firstCost) {
            // Another hand took the stock since this one set out: its cells go back, and it picks again.
            say(h, job, ClawhodWhy.SUPPLY_EMPTIED, BuildLog.itemId(item)) {
                "supply ${from.toShortString()} has $stock ${BuildLog.itemName(item)} left, under the $firstCost needed; picks again"
            }
            h.resetIdle()
            return dropAndReturn(job, h)
        }
        val want = minOf(h.fetchCount, stock)
        val taken = if (want > 0) h.hold(ItemStack(item, want)) else 0
        if (taken <= 0 || container == null || !container.takeItems(item, taken)) {
            if (taken > 0) h.takeMany(taken)
            if (stock == 0) return missAll(level, job, h, item, from)
            say(h, job, ClawhodWhy.FETCH_REFUSED, BuildLog.itemId(item)) {
                "supply ${from.toShortString()} has $stock ${BuildLog.itemName(item)} but the hand took $taken " +
                    "(holds ${h.heldBlock()} x${h.heldCount()}, slot room ${h.capacity(Block.byItem(item))}); " +
                    "its cells wait until the supplies or the hand change"
            }
            h.refusal.refuse(item, job.supplyEpoch, h.heldBlock(), h.heldCount())
            if (item == stockItem && hands.all { it.stocked || it.refuses(item) }) endStock()
            return dropAndReturn(job, h)
        }
        trimToHeld(job, h, h.heldCount())
        if (h.extraItem != null) startKitLeg(job, h) else goToTask(h)
    }

    /** Sends [h], which holds the base of a two-item cell, to the supply of the extra. */
    private fun startKitLeg(job: BuildJob, h: Hand) {
        val from = h.extraSupply
        val item = h.extraItem
        h.extraSupply = null
        h.extraItem = null
        if (from == null || item == null || h.tasks.isEmpty()) return goToTask(h)
        h.supply = from
        h.fetchItem = item
        h.fetchCount = h.extraCount
        h.extraCount = 0
        h.kitLeg = true
        job.claimFetch(h.workerId, from, item, h.fetchCount)
        h.stage = Stage.FETCH
        fly(h, from)
    }

    /** Takes the extra of a two-item cell into a free slot, as a bucket goes in: the base block stays held. */
    private fun fetchKitExtra(level: ServerLevel, job: BuildJob, h: Hand, item: Item, from: BlockPos, container: Container?) {
        h.kitLeg = false
        val need = h.fetchCount
        val stock = container?.countOf(item) ?: 0
        if (stock == 0) return missAll(level, job, h, item, from)
        if (stock < need) {
            say(h, job, ClawhodWhy.SUPPLY_EMPTIED, BuildLog.itemId(item)) {
                "supply ${from.toShortString()} has $stock ${BuildLog.itemName(item)} left, under the $need needed; picks again"
            }
            h.resetIdle()
            return dropAndReturn(job, h)
        }
        // Into the hand first, then out of the supply, so a failed take leaves nothing to drop.
        val put = if (container != null && h.hasRoomFor(item, need)) pickupInsert(h.slots, ItemStack(item, need), need) else 0
        if (put < need || container == null || !container.takeItems(item, need)) {
            if (put > 0) h.take(item, put)
            say(h, job, ClawhodWhy.FETCH_REFUSED, BuildLog.itemId(item)) {
                "the hand has no slot for $need ${BuildLog.itemName(item)} beside ${h.heldBlock()}; its cell waits"
            }
            h.refusal.refuse(item, job.supplyEpoch, h.heldBlock(), h.heldCount())
            return dropAndReturn(job, h)
        }
        be.handsChanged()
        goToTask(h)
    }

    /** A job hand whose tool broke takes the [item] tool the pick chose. */
    private fun fetchJobTool(level: ServerLevel, job: BuildJob, h: Hand, item: Item, from: BlockPos, container: Container?) {
        if (container == null || container.countOf(item) == 0) return missAll(level, job, h, item, from)
        val first = h.tool().isEmpty
        val why = takeToolInto(h, container, item)
        if (why != null) {
            say(h, job, ClawhodWhy.FETCH_REFUSED, BuildLog.itemId(item)) { "tool ${BuildLog.itemName(item)} at ${from.toShortString()} not taken: $why" }
            h.refusal.refuse(item, job.supplyEpoch, h.heldBlock(), h.heldCount())
            return dropAndReturn(job, h)
        }
        if (first) h.passes.startPass()
        say(h, job, ClawhodWhy.TOOL_FETCH, BuildLog.itemId(item)) { "takes a ${BuildLog.itemName(item)} from ${from.toShortString()}" }
        goToTask(h)
    }

    /**
     * Takes one [item] tool from [container] into a free slot of [h]; a tool the hand held goes back first.
     * Returns null, or why it took none.
     */
    private fun takeToolInto(h: Hand, container: Container, item: Item): String? {
        val slots = h.slots
        val old = h.workSlot()
        if (old >= 0) {
            val rest = HopperBlockEntity.addItem(null, container, slots[old], null)
            slots[old] = rest
            if (!rest.isEmpty) {
                be.handsChanged()
                return "no room in the chest for the tool it holds"
            }
        }
        val free = slots.indexOfFirst { it.isEmpty }
        if (free < 0) return "no free slot in the hand"
        val tool = container.takeTool(item)
        if (tool.isEmpty) return "the chest has no ${BuildLog.itemName(item)} now"
        slots[free] = tool
        h.setCarried(free, false)
        be.handsChanged()
        return null
    }

    /** Puts what the hand holds other than [keep] and its tool in [container] before a fetch, so the slots are free. */
    private fun depositOthers(h: Hand, container: Container, keep: Item) {
        val slots = h.slots
        var changed = false
        for (i in slots.indices) {
            val stack = slots[i]
            if (!h.isCargo(i) || stack.`is`(keep) && !stack.hasTag()) continue
            slots[i] = HopperBlockEntity.addItem(null, container, stack, null)
            changed = true
        }
        if (changed) be.handsChanged()
    }

    private fun work(level: ServerLevel, job: BuildJob, h: Hand) {
        val t = h.tasks.peekFirst() ?: return if (h.cutChain) chainCut(level, job, h) else flyHome(h)
        if (!job.isReservedBy(t.cell, h.workerId)) {
            say(h, job, ClawhodWhy.RESERVATION_LOST) { "cell ${t.pos.toShortString()} no longer reserved for this hand; next task" }
            return nextTask(h)
        }
        if (!reaches(t.pos.asLong())) {
            say(h, job, ClawhodWhy.TASK_OUT_OF_REACH) { "${t.kind} cell ${t.pos.toShortString()} left the reach (the carrier moved); released" }
            job.release(t.cell, h.workerId)
            return nextTask(h)
        }
        val actor = job.actor(level)
        when (t.kind) {
            TaskKind.PLACE, TaskKind.FIX -> if (!placeDwell(level, job, h, t)) return
            TaskKind.SCOOP -> scoop(level, job, h, t)
            TaskKind.CUT -> {
                if (h.punchCell != t.cell && !cutFits(level, actor, t, h.tool())) return cutWaits(level, job, h, t)
                if (!punch(level, h, t, h.tool())) return
                when (job.cut(level, actor, h, t)) {
                    CutResult.NOT_NOW -> return waitHere(h)
                    CutResult.CUT -> {
                        h.pick.lastCut = t.state.block
                        looseAt = 0L
                    }
                    CutResult.UNLOAD -> return cutUnload(level, job, h, t)
                    CutResult.DROPPED -> Unit
                }
            }
        }
        // A broken tool leaves the hand empty, so it goes home for a new one.
        if (t.kind == TaskKind.CUT && h.tasks.size == 1 && !h.tool().isEmpty && stockItem == null) {
            h.clearPlace()
            h.tasks.pollFirst()
            h.cutChain = true
            return chainCut(level, job, h)
        }
        nextTask(h)
    }

    /**
     * The user's rule: the tool hand goes from cut to cut, and home only to swap tools or when it has no next block.
     * A search that is not done yet goes on next tick while the hand waits at the cell.
     */
    private fun chainCut(level: ServerLevel, job: BuildJob, h: Hand) {
        val p = h.pick
        if (job.isReady && job.picksLeft > 0 && stockItem == null) {
            if (job.pick(level, job.actor(level), h, p)) return takeWork(job, h)
            if (!p.searchedAll) return waitHere(h)
        }
        flyHome(h)
    }

    /**
     * PLACE and FIX keep the hand at the cell for the client's "place" clip: the block goes in at the press
     * ([placeWait]) and the hand leaves when the clip ends ([placeHold]). True when the task is done.
     */
    private fun placeDwell(level: ServerLevel, job: BuildJob, h: Hand, t: BuildTask): Boolean {
        val now = level.gameTime
        if (h.placeSince == NO_TICK) h.placeSince = now
        if (!h.placeDone) {
            val wait = placeWait(now - h.placeSince)
            if (wait > 0) {
                h.arriveAt = now + wait
                return false
            }
            val actor = job.actor(level)
            // A walker may walk into a cell after the pick reserved it; a block put there lifts its body onto the build.
            if (t.kind == TaskKind.PLACE && walkerHolds(job, t.pos)) {
                say(h, job, ClawhodWhy.CARRIER_IN_WAY, t.pos.hashCode()) {
                    "a walker's body or stand spot holds cell ${t.pos.toShortString()} now; the cell goes back to the job"
                }
                job.release(t.cell, h.workerId)
                return true
            }
            if (t.kind == TaskKind.PLACE) {
                if (job.place(level, actor, h, t) == PlaceResult.NOT_NOW) {
                    waitHere(h)
                    return false
                }
            } else {
                job.fix(level, actor, h, t)
            }
            h.placeDone = true
            h.placedSinceSwap = true
        }
        val hold = placeHold(now - h.placeSince)
        if (hold > 0) {
            h.arriveAt = now + hold
            return false
        }
        return true
    }

    private fun cutUnload(level: ServerLevel, job: BuildJob, h: Hand, t: BuildTask) {
        say(h, job, ClawhodWhy.CUT_UNLOAD) {
            "yield of ${t.pos.toShortString()} does not fit with what the hand holds (${h.heldBlock()} x${h.heldCount()}); unloads first"
        }
        h.tasks.pollFirst()
        job.release(t.cell, h.workerId)
        h.releaseTasks(job)
        startUnload(level, job, h)
    }

    /** The cap on floating drops, as in mining, over both hands. */
    private fun cutFits(level: ServerLevel, actor: ServerPlayer, t: BuildTask, tool: ItemStack): Boolean {
        val state = level.getBlockState(t.pos)
        if (state.isAir) return true
        return looseFits(Block.getDrops(state, level, t.pos, level.getBlockEntity(t.pos), actor, tool), null)
    }

    /** Too much floats in reach: a hand with items unloads, an empty one goes home to collect. */
    private fun cutWaits(level: ServerLevel, job: BuildJob, h: Hand, t: BuildTask) {
        if (h.hasCargo()) return cutUnload(level, job, h, t)
        say(h, job, ClawhodWhy.CUT_UNLOAD) {
            "the floating drops in reach and the drops of ${t.pos.toShortString()} do not fit in the hands; collects first"
        }
        h.tasks.pollFirst()
        job.release(t.cell, h.workerId)
        h.releaseTasks(job)
        // A tool hand cannot collect: it waits for the free hand, as an idle hand does.
        if (!h.tool().isEmpty) {
            h.idleUntil = level.gameTime + IDLE_RETRY_TICKS
            h.idleEpoch = job.supplyEpoch
        }
        flyHome(h)
    }

    private fun scoop(level: ServerLevel, job: BuildJob, h: Hand, t: BuildTask) {
        val slots = h.slots
        val i = slots.indexOfFirst { it.`is`(Items.BUCKET) }
        // The filled bucket needs the emptied slot or a free one, or it would have to drop.
        if (i < 0 || (slots[i].count > 1 && slots.none { it.isEmpty })) {
            job.release(t.cell, h.workerId)
            return
        }
        val bucket = slots[i].split(1)
        if (slots[i].isEmpty) slots[i] = ItemStack.EMPTY
        val filled = job.scoop(level, job.actor(level), h, t, bucket)
        for (stack in h.holdPickups(listOf(filled))) Block.popResource(level, t.pos, stack)
    }

    /**
     * The user's rule: a hand breaks a block as a player with [tool] (empty: a bare hand) would, punch by punch, with
     * the vanilla cracks and hit sounds. True once the block is ready to break (or is gone, so the cut sees that).
     */
    private fun punch(level: ServerLevel, h: Hand, t: BuildTask, tool: ItemStack = ItemStack.EMPTY): Boolean {
        val pos = t.pos
        if (!level.isLoaded(pos)) return true
        val state = level.getBlockState(pos)
        if (state.isAir) return endPunch(level, h)
        if (h.punchCell != t.cell || h.punchState !== state) {
            endPunch(level, h)
            h.punchCell = t.cell
            h.punchState = state
            h.punchPos = pos
            h.punchTicks = 0
            h.punchSent = -1
            h.punchTotal = breakTicksWith(level, pos, state, tool)
            be.setHandTarget(h.index, pos.asLong(), punch = true)
        }
        if (h.punchTotal < 0) return endPunch(level, h)
        if (h.punchTicks % PUNCH_TICKS == 0) {
            val sound = state.soundType
            level.playSound(null, pos, sound.hitSound, SoundSource.BLOCKS, (sound.volume + 1f) / HIT_VOLUME_DIVISOR, sound.pitch * HIT_PITCH)
        }
        h.punchTicks++
        if (h.punchTicks >= h.punchTotal) return endPunch(level, h)
        val stage = punchStage(h.punchTicks, h.punchTotal)
        if (stage != h.punchSent) {
            h.punchSent = stage
            level.destroyBlockProgress(breakerId(h), pos, stage)
        }
        waitHere(h)
        return false
    }

    /** Clears the cracks of a punch in progress. Returns true. */
    private fun endPunch(level: ServerLevel, h: Hand): Boolean {
        val pos = h.punchPos
        if (pos != null && h.punchSent >= 0) level.destroyBlockProgress(breakerId(h), pos, -1)
        h.punchPos = null
        h.punchCell = NO_POS
        h.punchState = null
        h.punchSent = -1
        return true
    }

    // Negative, so it never matches a player's entity id: the server sends the cracks to every player.
    private fun breakerId(h: Hand): Int = -1 - ((be.workerId.hashCode() and Int.MAX_VALUE) % BREAKER_IDS) * CLAWHOD_HANDS - h.index

    private fun waitHere(h: Hand) {
        h.arriveAt = level.gameTime + 1
    }

    /** Takes the hand to a supply in reach to empty it; with none, asks the carrier to go near one. */
    private fun startUnload(level: ServerLevel, job: BuildJob, h: Hand) {
        val to = unloadTarget(level, job, h)
        if (to == null) {
            flyHome(h)
            h.idleUntil = level.gameTime + IDLE_RETRY_TICKS
            h.idleEpoch = job.supplyEpoch
            say(h, job, ClawhodWhy.NO_SUPPLY_IN_REACH) { "the hand must unload but no linked supply is in reach" }
            if (moveSpot != NO_POS) return
            askMoveToSupply(level, job, nearestSupply(level, job, null), "unload")
            return
        }
        if (h.stage == Stage.HOME) h.pos.set(gem)
        h.pick.lastCut = null
        h.supply = to
        h.stage = Stage.UNLOAD
        say(h, job, ClawhodWhy.UNLOAD) { "unloads at ${to.toShortString()}" }
        fly(h, to)
    }

    /** A supply in reach with room for the hand's first stack, one that holds it first; null when none has room. */
    private fun unloadTarget(level: ServerLevel, job: BuildJob, h: Hand): BlockPos? {
        val first = h.firstCargo() ?: return null
        var roomy: BlockPos? = null
        for (pos in job.supplies) {
            if (!level.isLoaded(pos) || !reaches(pos.asLong())) continue
            val container = HopperBlockEntity.getContainerAt(level, pos) ?: continue
            if (container.roomFor(first) > 0) {
                if (container.holdsSame(first)) return pos
                if (roomy == null) roomy = pos
            }
        }
        return roomy
    }

    // The user's rule: carried items never drop. What no supply in reach takes (full, or the carrier walked the
    // supply away) stays in the hand, and startUnload tries another supply or waits and asks the carrier to move.
    private fun unloadAt(level: ServerLevel, job: BuildJob, h: Hand) {
        val at = h.supply ?: return flyHome(h)
        val slots = h.slots
        var kept: ItemStack? = null
        for (i in slots.indices) {
            if (!h.isCargo(i)) continue
            slots[i] = depositInReach(job, slots[i])
            if (!slots[i].isEmpty && kept == null) kept = slots[i]
        }
        be.handsChanged()
        val left = kept ?: return flyHome(h)
        say(h, job, ClawhodWhy.SUPPLY_FULL, BuildLog.itemId(left.item)) {
            "${left.count} ${BuildLog.itemName(left.item)} fit in no supply in reach of ${at.toShortString()}; the hand keeps them"
        }
        startUnload(level, job, h)
    }

    private fun missAll(level: ServerLevel, job: BuildJob, h: Hand, item: Item, from: BlockPos) {
        if (item == stockItem) endStock()
        say(h, job, ClawhodWhy.FETCH_MISSED, BuildLog.itemId(item)) {
            "fetch of ${BuildLog.itemName(item)} at ${from.toShortString()} failed; ${BuildLog.supplyReport(level, job.supplies, item)}"
        }
        for (t in h.tasks) job.missed(t.cell, h.workerId, item)
        h.clearPlace()
        h.tasks.clear()
        flyHome(h)
    }

    /** Releases the batch tail that the held count cannot pay for. */
    private fun trimToHeld(job: BuildJob, h: Hand, held: Int) {
        var paid = 0
        val it = h.tasks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            val cost = t.cost?.count ?: 0
            if (paid + cost <= held) {
                paid += cost
                continue
            }
            job.release(t.cell, h.workerId)
            it.remove()
        }
    }

    private fun dropAndReturn(job: BuildJob, h: Hand) {
        h.releaseTasks(job)
        flyHome(h)
    }

    private fun nextTask(h: Hand) {
        h.clearPlace()
        h.tasks.pollFirst()
        goToTask(h)
    }

    private fun goToTask(h: Hand) {
        val t = h.tasks.peekFirst() ?: return flyHome(h)
        h.stage = Stage.CELL
        fly(h, t.pos)
    }

    private fun fly(h: Hand, to: BlockPos, looseId: Int = ClawhodBlockEntity.NO_LOOSE) {
        closeLid(h)
        endPunch(level, h)
        if (!worldCentre(to, target)) target.set(h.pos)
        h.arriveAt = level.gameTime + flightTicks(h.pos.distance(target))
        h.pos.set(target)
        h.at = to.asLong()
        be.setHandTarget(h.index, to.asLong(), looseId = looseId)
    }

    private fun flyHome(h: Hand) {
        closeLid(h)
        h.looseItem = null
        h.mineTask = null
        h.cutChain = false
        if (h.stage == Stage.HOME || h.stage == Stage.RETURN) return
        endPunch(level, h)
        h.stage = Stage.RETURN
        h.supply = null
        h.arriveAt = level.gameTime + flightTicks(h.pos.distance(gem))
        h.pos.set(gem)
        h.at = NO_POS
        be.setHandTarget(h.index, NO_POS)
    }

    private fun refreshGem(level: ServerLevel) {
        val p = be.blockPos
        val facing = be.blockState.getValue(ClawhodBlock.FACING)
        gem.setClawhodGem(p, facing)
        val ship = level.getShipManagingPos(p)
        ship?.shipToWorld?.transformPosition(gem)
        mineFacing.setClawhodFacingWorld(facing, ship?.shipToWorld)
        // The reach is measured in the scale of the ship that carries the Clawhod (the walker's body).
        // VS2 scaling is uniform, so one axis gives it.
        carrierScale = ship?.transform?.shipToWorldScaling?.x() ?: 1.0
        reachNow = CLAWHOD_REACH * carrierScale
        // A home hand is at the gem; else a trip times its flight from the world origin (after a load) or a stale gem.
        for (h in hands) refreshHandEnd(h)
    }

    /** Moves [h]'s leg end with its frame: the gem with this ship, a ship cell with that ship. World cells stay. */
    private fun refreshHandEnd(h: Hand) {
        if (h.stage == Stage.HOME || h.stage == Stage.RETURN || h.at == NO_POS) {
            h.pos.set(gem)
            return
        }
        handCell.set(h.at)
        if (!level.isBlockInShipyard(handCell) || worldCentre(handCell, h.pos)) return
        // VS deletes a ship when its last block breaks: the hand keeps its last world point, and a hand with no task goes home.
        if (h.tasks.isEmpty() && !h.relaying) flyHome(h)
    }

    /**
     * World centre of the block at [pos]; a supply may sit on a ship. False, and [out] unchanged, when [pos] is in the
     * shipyard but its ship is gone: the raw shipyard point is millions of blocks out.
     */
    private fun worldCentre(pos: BlockPos, out: Vector3d): Boolean {
        val ship = if (level.isBlockInShipyard(pos)) level.getShipManagingPos(pos) ?: return false else null
        out.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        ship?.shipToWorld?.transformPosition(out)
        return true
    }

    private fun reaches(packed: Long): Boolean {
        scratch.set(packed)
        if (!level.isBlockInShipyard(scratch)) return withinReach(gem.x, gem.y, gem.z, scratch.x, scratch.y, scratch.z, reachNow)
        return worldCentre(scratch, target) && target.distanceSquared(gem) <= reachNow * reachNow
    }

    private fun reportIdle(level: ServerLevel, job: BuildJob, h: Hand) {
        val tally = h.pick.reportTally()
        val top = tally.top()
        when {
            job.active.isEmpty() -> say(job, ClawhodWhy.NO_ACTIVE_SECTION) {
                "no section in work: all done, chunks not loaded or held back by CLEAR order ${job.summary()}"
            }
            top == null -> say(job, ClawhodWhy.NOTHING_OPEN) { "every cell is done or waits for another phase ${job.summary()}" }
            else -> say(job, top, BuildLog.itemId(tally.missingItem)) { idleDetail(level, job, h, tally, top) }
        }
    }

    // ---- the carrier

    /**
     * The walker whose body carries this clawhod, on any block of that body: the ship managing the clawhod's cell,
     * then the walker tracked for that ship. Found again only when the clawhod is on another ship.
     */
    private fun carrier(level: ServerLevel): ConstriliarEntity? {
        val shipId = level.getShipManagingPos(be.blockPos)?.id
        if (shipId == null) {
            carrierShip = NO_SHIP
            carrierId = null
            return null
        }
        if (shipId != carrierShip) {
            carrierShip = shipId
            carrierId = (ConstriliarImpactSounds.walkerOf(level.dimensionId, shipId) ?: level.carrierAt(be.blockPos).entity)?.uuid
        }
        return carrierId?.let { level.getEntity(it) as? ConstriliarEntity }
    }

    /** The walker that can move now, or null after a log line that says why not. */
    private fun readyCarrier(level: ServerLevel, job: BuildJob, why: String): ConstriliarEntity? {
        val walker = carrier(level)
        when {
            walker == null -> {
                carrierShip = NO_SHIP
                say(job, ClawhodWhy.NO_REACHABLE_WORK) { "$why, and no walker carries this clawhod: it works only in its own reach" }
            }
            walker.isSitting() -> say(job, ClawhodWhy.CARRIER_SITTING) { "$why, but the carrier sits" }
            // This clawhod's own order that it gave up on (CARRIER_STUCK) does not hold the carrier.
            // An order the walker failed for good (out of reach for its whole body) no longer holds it either.
            walker.orderSettled() || walker.orderFailed() || walker.moveClaim == be.workerId && moveSpot == NO_POS -> return walker
            walker.moveClaim != null && walker.moveClaim != be.workerId ->
                say(job, ClawhodWhy.CARRIER_BUSY) { "$why; the carrier walks for another clawhod on its body, so this one waits" }
            else -> say(job, ClawhodWhy.CARRIER_MOVING) { "$why; the carrier is still on its way" }
        }
        return null
    }

    /**
     * [hand] has the older out-of-reach cell; the spot must reach it, and should reach the other hand's too. False
     * only when no floor was found, so a step-aside may go out instead.
     */
    private fun askMove(level: ServerLevel, job: BuildJob, hand: Hand): Boolean {
        val p = hand.pick
        val far = p.farCell
        // The user's rule: a chain of idle Clawhods brings the item, and the carrier stays.
        if (requestRelay(level, job, hand)) return true
        val walker = readyCarrier(level, job, "nothing open in reach; next cell ${BlockPos.of(far).toShortString()}") ?: return true
        // Counted only with a carrier: a clawhod with none makes no trip.
        if (giveUpFar(job, far)) {
            p.clearFar()
            hand.farSince = Long.MAX_VALUE
            return false
        }
        clearFor(walker, job)
        noteInWay()
        val other = hands.firstOrNull { it !== hand && it.pick.farCell != NO_POS }?.pick?.farCell ?: NO_POS
        val need = neededItem(p)
        val supplyPos = p.farSupply ?: need?.let { nearestSupply(level, job, it) }
        // A supply on the carrier's own ship rides along: it is always in reach and never needs a walk.
        val supplyKey = supplyPos?.takeUnless(::onCarrierShip)?.asLong() ?: NO_POS
        var spot = findSpot(level, job, far, supplyKey, other, BlockPos.getY(far))
        var why = ClawhodWhy.ASK_MOVE
        // The hands lack the item and the cell's spot does not reach its supply: they fill up at the supply first,
        // then the carrier goes on to the cell. A cell spot alone left them empty-handed at the cell for ever.
        val stockable = need != null && supplyPos != null && supplyKey != NO_POS && Block.byItem(need) != Blocks.AIR
        if (stockable && (spot == NO_POS || !spotReaches(spot, supplyKey))) {
            stockItem = need
            stockAt = supplyPos
            if (reaches(supplyKey)) {
                for (h in hands) h.resetIdle()
                say(job, ClawhodWhy.STOCK_UP, BuildLog.itemId(need)) {
                    "no spot for cell ${BlockPos.of(far).toShortString()} reaches supply ${supplyPos?.toShortString()}; " +
                        "the hands fill up with ${need?.let(BuildLog::itemName)} here first"
                }
                avoidCount = 0
                return true
            }
            spot = findSpot(level, job, supplyKey, NO_POS)
            why = ClawhodWhy.ASK_MOVE_SUPPLY
        }
        if (spot == NO_POS) {
            // The user's rule: the carrier climbs to get close enough. Go to the nearest higher or nearer floor
            // toward the cell; the search runs again from there when the carrier settles.
            spot = climbSpot(level, job, far)
            why = ClawhodWhy.ASK_CLIMB
        }
        avoidCount = 0
        for (h in hands) {
            h.pick.clearFar()
            h.farSince = Long.MAX_VALUE
        }
        if (spot == NO_POS) {
            remember(far)
            say(job, ClawhodWhy.NO_WORK_POSITION) { "no floor near ${BlockPos.of(far).toShortString()} that the carrier can stand on or climb to${rejectNote()}" }
            return false
        }
        sendCarrier(walker, spot)
        val both = other == NO_POS || spotReaches(spot, other)
        say(job, why, BlockPos.getX(spot) xor BlockPos.getZ(spot)) {
            "carrier walks to ${BlockPos.of(spot).toShortString()} for hand ${hand.index}'s cell ${BlockPos.of(far).toShortString()}" +
                (if (both) "" else "; no floor reaches the other hand's cell ${BlockPos.of(other).toShortString()} too, so the older one goes first") +
                (supplyPos?.let { "; supply ${it.toShortString()} for ${need?.let(BuildLog::itemName)}" } ?: "") +
                spotNote(job, spot)
        }
        return true
    }

    /** A cell of the job was done or the world in its cells changed: a far cell given up may have a spot now. */
    private fun refreshFarGiveUp(job: BuildJob) {
        val stamp = job.progressStamp()
        if (stamp == farStamp) return
        farStamp = stamp
        farTrips.clear()
        farGivenUp.clear()
    }

    /** Counts one more trip for far cell [far]. True when the trips made it no nearer and the hands go on to later cells. */
    private fun giveUpFar(job: BuildJob, far: Long): Boolean {
        if (farTrips.addTo(far, 1) < FAR_TRIPS_MAX) return false
        if (farGivenUp.size >= FAR_GIVEN_UP_MAX) farGivenUp.clear()
        farGivenUp.add(far)
        say(job, ClawhodWhy.FAR_GIVEN_UP, BlockPos.getX(far) xor BlockPos.getZ(far)) {
            "$FAR_TRIPS_MAX carrier trips left cell ${BlockPos.of(far).toShortString()} out of reach; the hands go for later cells " +
                "until the job makes progress"
        }
        return true
    }

    /** The floor whose gem comes nearest out-of-reach cell [far], by at least [CLIMB_MIN_GAIN], or [NO_POS]. */
    private fun climbSpot(level: ServerLevel, job: BuildJob, far: Long): Long {
        framePoint(far, targetPts, 0)
        val n = collectFloors(level, job, route = true)
        return closerWorkSpot(
            floors, n, walkLift, targetPts[0], targetPts[1], targetPts[2], walkGem.x, walkGem.y, walkGem.z, tried, triedCount, floorFaces,
        )
    }

    /** The cells the carrier's body blocks now, as points in the walk frame: the new spot must not stand in them. */
    private fun noteInWay() {
        avoidCount = 0
        for (h in hands) {
            val tally = h.pick.reportTally()
            if (tally.has(WhyNot.CARRIER_IN_WAY)) framePoint(tally.firstPosOf(WhyNot.CARRIER_IN_WAY).asLong(), avoidPts, avoidCount++ * 3)
            if (tally.has(WhyNot.ENTITY_IN_CELL)) framePoint(tally.firstPosOf(WhyNot.ENTITY_IN_CELL).asLong(), avoidPts, avoidCount++ * 3)
        }
    }

    /** [pos] is a block on the ship that carries this Clawhod: it moves with the gem. */
    private fun onCarrierShip(pos: BlockPos): Boolean =
        level.isBlockInShipyard(pos) && level.getShipManagingPos(pos)?.id == carrierShip

    /** Sends the carrier to a floor that reaches the supply [to], so a hand can [purpose] there. True when it walks. */
    private fun askMoveToSupply(level: ServerLevel, job: BuildJob, to: BlockPos?, purpose: String): Boolean {
        if (to == null) return false
        carrier(level)
        if (onCarrierShip(to)) return false
        val walker = readyCarrier(level, job, "the hands must $purpose") ?: return false
        clearFor(walker, job)
        val spot = findSpot(level, job, to.asLong(), NO_POS)
        if (spot == NO_POS) {
            say(job, ClawhodWhy.NO_WORK_POSITION) { "no floor near supply ${to.toShortString()} that the carrier can stand on${rejectNote()}" }
            return false
        }
        sendCarrier(walker, spot)
        say(job, ClawhodWhy.ASK_MOVE_SUPPLY, BlockPos.getX(spot) xor BlockPos.getZ(spot)) {
            "carrier walks to ${BlockPos.of(spot).toShortString()} to $purpose at ${to.toShortString()}${spotNote(job, spot)}"
        }
        return true
    }

    /**
     * Sends the carrier to [spot] in the walk frame: the top of a floor, or the wall face beside a wall spot's body
     * cell. On the plan ship's deck the order holds the shipyard point and follows the ship; the world point is made
     * only there, exact.
     */
    private fun sendCarrier(walker: ConstriliarEntity, spot: Long) {
        movesSent++
        remember(spot)
        val faceId = spotFace(spot)
        jobId?.let { BuildJobs.get(level).job(it) }?.claimStandSpot(
            walker.uuid, spot, (bodyShape?.radius ?: 0) * carrierScale, faceId, (bodyShape?.height ?: 2) * carrierScale,
        )
        moveSpot = spot
        moveFails = 0
        moveStill = 0
        moveLastX = Double.NaN
        moveCheckAt = level.gameTime + IDLE_RETRY_TICKS
        val face = Direction.from3DDataValue(faceId)
        if (faceId == FLOOR_FACE) {
            framePt.set(BlockPos.getX(spot) + HALF, BlockPos.getY(spot) + 1.0, BlockPos.getZ(spot) + HALF)
        } else {
            framePt.set(
                BlockPos.getX(spot) + HALF + face.stepX * HALF, BlockPos.getY(spot) + HALF + face.stepY * HALF,
                BlockPos.getZ(spot) + HALF + face.stepZ * HALF,
            )
        }
        val orderFace = if (faceId == FLOOR_FACE) null else face.opposite
        val ship = walkShip
        if (ship == null) {
            walker.commandMoveTo(Vec3(framePt.x, framePt.y, framePt.z), orderFace)
        } else {
            ship.shipToWorld.transformPosition(framePt, target)
            walker.commandMoveToShipPoint(ship.id, framePt, Vec3(target.x, target.y, target.z), orderFace)
        }
        // After the order: a new order clears the claim, so another clawhod or the player ends this move.
        walker.moveClaim = be.workerId
    }

    private fun remember(spot: Long) {
        tried[triedNext] = spot
        triedNext = (triedNext + 1) % TRIED_MAX
        if (triedCount < TRIED_MAX) triedCount++
    }

    /** The item the far cell needs from a supply, or null when it is CLEAR work or a hand holds enough. */
    private fun neededItem(p: WorkerPick): Item? {
        val cost = p.farState?.planCost() as? PlanCost.Items
        if (!p.farBuild || cost == null) return p.farItem
        // A stock-up would fill both slots with the base and leave no slot for the extra of a two-item cell.
        if (cost.extra != null) return null
        val block = clawhodBlockOf(cost.stack()) ?: return null
        for (h in hands) if (handCount(h.slots, block) >= cost.count) return null
        return cost.item
    }

    /** The loaded supply nearest the gem that has [item] (any supply when [item] is null). */
    private fun nearestSupply(level: ServerLevel, job: BuildJob, item: Item?): BlockPos? {
        var best: BlockPos? = null
        var bestDist = Double.MAX_VALUE
        for (pos in job.supplies) {
            if (!level.isLoaded(pos)) continue
            val container = HopperBlockEntity.getContainerAt(level, pos) ?: continue
            if (item != null && container.countOf(item) == 0) continue
            if (!worldCentre(pos, target)) continue
            val d = target.distanceSquared(gem)
            if (d < bestDist) {
                best = pos
                bestDist = d
            }
        }
        return best
    }

    /**
     * Floors round [target], a block of the world or of any ship; [second] ([NO_POS] for none) is the other hand's
     * cell, a bonus to reach too. Every target becomes an exact point in the walk frame. With a plan-frame
     * [coverLayer], pending cells of that layer vote. [route] false skips the route gate: a walled-in carrier must
     * climb or jump out to a floor no flood reaches.
     */
    @Suppress("LongParameterList") // Each flag is one caller's case.
    private fun findSpot(
        level: ServerLevel, job: BuildJob, target: Long, supply: Long, second: Long = NO_POS,
        coverLayer: Int = NO_LAYER, route: Boolean = true,
    ): Long {
        framePoint(target, targetPts, 0)
        var count = 1
        if (second != NO_POS) framePoint(second, targetPts, 3 * count++)
        val n = collectFloors(level, job, route)
        val supplyAt = if (supply == NO_POS) null else supplyPt.also { framePoint(supply, it, 0) }
        val covered = if (coverLayer != NO_LAYER) collectCover(job, coverLayer) else 0
        // The user's rule as the last tie-break: the furthest floor that reaches; after one of those failed, the closest.
        return chooseWorkSpot(
            floors, floorClasses, n, walkLift, targetPts, count, supplyAt, coverPts, covered, tried, triedCount,
            furthest = !spotFailed, reach = walkReach, margin = walkMargin, faces = floorFaces,
        )
    }

    /** Up to [COVER_CAP] pending cells of plan layer [y], spread evenly, as walk-frame points; returns the count. */
    private fun collectCover(job: BuildJob, y: Int): Int {
        var pending = 0
        for (i in job.active.indices) {
            val s = job.active[i]
            for (c in s.positions.indices) if (coverable(s, c, y)) pending++
        }
        if (pending == 0) return 0
        val stride = (pending + COVER_CAP - 1) / COVER_CAP
        var seen = 0
        var n = 0
        for (i in job.active.indices) {
            val s = job.active[i]
            for (c in s.positions.indices) {
                if (n == COVER_CAP) return n
                if (coverable(s, c, y) && seen++ % stride == 0) framePoint(s.positions[c], coverPts, 3 * n++)
            }
        }
        return n
    }

    private fun coverable(s: BuildSection, cell: Int, y: Int): Boolean =
        BlockPos.getY(s.positions[cell]) == y && !s.isDone(cell) && s.status(cell) != CellStatus.BLOCKED

    /** The plan cell under walk-frame block ([x], [y], [z]): the same block when the walk frame is the plan's own. */
    private fun planCellOf(job: BuildJob, x: Int, y: Int, z: Int): Long =
        if (walkShip != null) BlockPos.asLong(x, y, z) else job.toPlanFrame(level, x, y, z)

    /** Past the plan's footprint, or on its top layer: no wall of the plan can close round a carrier there. */
    private fun outsidePlan(job: BuildJob, floor: Long): Boolean {
        val f = planCellOf(job, BlockPos.getX(floor), BlockPos.getY(floor), BlockPos.getZ(floor))
        return BlockPos.getY(f) >= job.planTopY || !job.inFootprint(BlockPos.getX(f), BlockPos.getZ(f))
    }

    private fun spotClass(job: BuildJob, floor: Long): Byte = when {
        outsidePlan(job, floor) -> SPOT_OUTSIDE
        planWants(job, BlockPos.getX(floor), BlockPos.getY(floor), BlockPos.getZ(floor)) -> SPOT_TOP
        else -> SPOT_GROUND
    }

    /** The class of [spot] and the route flood behind the choice, for the move lines. */
    private fun spotNote(job: BuildJob, spot: Long): String {
        val route = when {
            !routeUsed -> "no route gate"
            routeGate -> "route flood ${routeSeen.size} bases"
            else -> "route flood capped at ${routeSeen.size} bases, no gate"
        }
        val frame = walkShip?.let { "; on ship ${it.id}" } ?: ""
        val face = spotFace(spot)
        val wall = when (face) {
            FLOOR_FACE -> ""
            CEILING_FACE -> "; under a ceiling"
            else -> "; on the ${Direction.from3DDataValue(face).serializedName} wall"
        }
        return " [${SPOT_CLASS_NAMES[spotClass(job, spot).toInt()]} spot$wall; $route$frame]"
    }

    /** A gem planned on walk-frame spot [spot] reaches [packed], a block of the world or of any ship. */
    private fun spotReaches(spot: Long, packed: Long): Boolean {
        toFrame(walkShip, packed, framePt)
        val face = spotFace(spot)
        return plannedReachTo(
            spotGemX(spot, face, walkLift), spotGemY(spot, face, walkLift), spotGemZ(spot, face, walkLift),
            framePt.x, framePt.y, framePt.z, walkReach, walkMargin,
        )
    }

    /** The support face of [spot] in the last spot search, or [FLOOR_FACE] when the search did not hold it. */
    private fun spotFace(spot: Long): Int {
        for (i in 0 until floorCount) if (floors[i] == spot) return floorFaces[i].toInt()
        return FLOOR_FACE
    }

    /**
     * Up to two walk-frame floors, one wall spot and one ceiling spot per [WORK_SPOT_OFFSETS] column round the first
     * target point into [floors], with their classes and faces; returns the count. Bounded: 48 columns of at most 16
     * cells plus the plan height, and five faces per wall cell.
     */
    private fun collectFloors(level: ServerLevel, job: BuildJob, route: Boolean): Int {
        val tx = Mth.floor(targetPts[0])
        val ty = Mth.floor(targetPts[1])
        val tz = Mth.floor(targetPts[2])
        var n = 0
        takenFloors = 0
        failedFloors = 0
        routeFloors = 0
        beamFloors = 0
        standRejects.fill(0)
        val walker = standWalker
        gatherSurfaceShips(job, walker, tx, ty, tz)
        routeUsed = route && walker != null
        routeGate = walker != null && routeUsed && routeFlood(walker, job, tx, ty, tz) == WayOut.SHUT
        val reach = bodyShape?.radius ?: 0
        for (i in 0 until walkRings * WORK_SPOT_RING_VALUES step 2) {
            val cx = tx + WORK_SPOT_OFFSETS[i]
            val cz = tz + WORK_SPOT_OFFSETS[i + 1]
            scanColumn(level, job, cx, ty, cz, targetPts[1])
            if (keepsFloor(job, colNearest, reach)) n = addSpot(job, n, colNearest, FLOOR_FACE)
            if (colLowest != colNearest && keepsFloor(job, colLowest, reach)) n = addSpot(job, n, colLowest, FLOOR_FACE)
            scanWall(level, job, cx, ty, cz)
            if (keepsFloor(job, colWall, reach, colWallFace)) n = addSpot(job, n, colWall, colWallFace)
            if (keepsFloor(job, colCeiling, reach, CEILING_FACE)) n = addSpot(job, n, colCeiling, CEILING_FACE)
        }
        floorCount = n
        if (n == 0 && takenFloors > 0) {
            say(job, ClawhodWhy.SPOT_TAKEN, takenFloors) {
                "$takenFloors floor(s) near ${BlockPos.asLong(tx, ty, tz).let(BlockPos::of).toShortString()} are taken by other carriers of the job " +
                    "(this body r=${bodyShape?.radius ?: 0} h=${bodyShape?.height ?: 2} scale=${"%.2f".format(carrierScale)}; ${job.standClaimCount()} claims)"
            }
        }
        return n
    }

    private fun addSpot(job: BuildJob, n: Int, spot: Long, face: Int): Int {
        floors[n] = spot
        floorClasses[n] = spotClass(job, spot)
        floorFaces[n] = face.toByte()
        return n + 1
    }

    private fun keepsFloor(job: BuildJob, floor: Long, reach: Int, face: Int = FLOOR_FACE): Boolean {
        if (floor == NO_POS) return false
        // Another carrier of this job stands on or walks to a floor too near: the carriers spread out.
        if (!job.standSpotFree(level, carrierId ?: be.workerId, floor, reach * carrierScale, face, (bodyShape?.height ?: 2) * carrierScale)) {
            takenFloors++
            return false
        }
        if (failedSpots.blocks(floor, level.gameTime)) {
            failedFloors++
            return false
        }
        val base = if (face == FLOOR_FACE) BlockPos.offset(floor, 0, 1, 0) else floor
        if (routeGate && !routeSeen.contains(base)) {
            routeFloors++
            return false
        }
        for (i in 0 until avoidCount) {
            val x = avoidPts[i * 3]
            val y = avoidPts[i * 3 + 1]
            val z = avoidPts[i * 3 + 2]
            if (if (face == FLOOR_FACE) pointInBody(floor, bodyShape, x, y, z) else pointInWallBody(floor, face, bodyShape, x, y, z)) return false
        }
        return true
    }

    /** A body of [shape] on wall spot [cell] (support [face], cells [k] blocks wide) holds the point ([px], [py], [pz]). */
    @Suppress("LongParameterList") // A spot, its face, a shape, a point and the cell size.
    private fun pointInWallBody(
        cell: Long, face: Int, shape: ConstriliarCargoShape?, px: Double, py: Double, pz: Double, k: Double = bodyK,
    ): Boolean {
        val dir = Direction.from3DDataValue(face)
        for (layer in 0 until (shape?.height ?: 2)) {
            val r = maxOf(shape?.radiusAt(layer) ?: 0, 0)
            // Too wide to bound: count the point as held, as a refused spot is the safe side.
            if (!bodyLayer(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell), layer, r, k, dir)) return true
            if (px in bx0..bx1 && py in by0..by1 && pz in bz0..bz1) return true
        }
        return false
    }

    /**
     * A body of [shape] standing on [floor] holds the point ([px], [py], [pz]) of the floor's own frame. Body cells
     * are [k] frame blocks wide: the walker ship's scale through the frame's.
     */
    private fun pointInBody(floor: Long, shape: ConstriliarCargoShape?, px: Double, py: Double, pz: Double, k: Double = bodyK): Boolean {
        val rel = (py - BlockPos.getY(floor) - 1) / k
        if (rel < 0.0) return false
        val layer = Math.floor(rel).toInt()
        if (layer >= (shape?.height ?: 2)) return false
        val r = (maxOf(shape?.radiusAt(layer) ?: 0, 0) + HALF) * k
        return Math.abs(px - (BlockPos.getX(floor) + HALF)) <= r && Math.abs(pz - (BlockPos.getZ(floor) + HALF)) <= r
    }

    /** The size of one body cell in the frame tested now: the carrier's scale through that frame's. */
    private var bodyK = 1.0

    // One body layer over a base, in the frame tested now: its whole cells and its exact box. Scale 1 keeps whole cells.
    private var lx0 = 0
    private var lx1 = 0
    private var ly0 = 0
    private var ly1 = 0
    private var lz0 = 0
    private var lz1 = 0
    private var bx0 = 0.0
    private var bx1 = 0.0
    private var by0 = 0.0
    private var by1 = 0.0
    private var bz0 = 0.0
    private var bz1 = 0.0

    /**
     * Sets the cells ([lx0]..) and box ([bx0]..) of body layer [layer], radius [r], out from base ([x], [y], [z]) on
     * support [face], for body cells [k] blocks wide. False when the layer spans more than [MAX_BODY_SPAN] cells on an
     * axis: the search stays bounded.
     */
    @Suppress("LongParameterList") // A base cell, a layer, its radius, the cell size and the face.
    private fun bodyLayer(x: Int, y: Int, z: Int, layer: Int, r: Int, k: Double, face: Direction = Direction.DOWN): Boolean {
        if (face == Direction.DOWN && Math.abs(k - 1.0) < SCALE_EPS) {
            lx0 = x - r
            lx1 = x + r
            ly0 = y + layer
            ly1 = ly0
            lz0 = z - r
            lz1 = z + r
            bx0 = lx0.toDouble()
            bx1 = lx1 + 1.0
            by0 = ly0.toDouble()
            by1 = ly0 + 1.0
            bz0 = lz0.toDouble()
            bz1 = lz1 + 1.0
            return true
        }
        bodyBox(x, y, z, face, layer, layer + 1, (r + HALF) * k, k, layerBox)
        bx0 = layerBox[0]
        by0 = layerBox[1]
        bz0 = layerBox[2]
        bx1 = layerBox[3]
        by1 = layerBox[4]
        bz1 = layerBox[5]
        lx0 = Mth.floor(bx0 + SCALE_EPS)
        lx1 = Mth.ceil(bx1 - SCALE_EPS) - 1
        ly0 = Mth.floor(by0 + SCALE_EPS)
        ly1 = Mth.ceil(by1 - SCALE_EPS) - 1
        lz0 = Mth.floor(bz0 + SCALE_EPS)
        lz1 = Mth.ceil(bz1 - SCALE_EPS) - 1
        return lx1 - lx0 < MAX_BODY_SPAN && ly1 - ly0 < MAX_BODY_SPAN && lz1 - lz0 < MAX_BODY_SPAN
    }

    private val layerBox = DoubleArray(BOX_VALUES)

    /**
     * Floods the bases [walker]'s whole body reaches over surfaces (no open air) into [routeSeen], in the walk frame,
     * inside the box round the walker and the target column ([tx], [ty], [tz]); once per tick, frame and target.
     */
    private fun routeFlood(walker: ConstriliarEntity, job: BuildJob, tx: Int, ty: Int, tz: Int): WayOut {
        val key = BlockPos.asLong(tx, ty, tz)
        val frameId = walkShip?.id ?: -1L
        if (routeAt == level.gameTime && routeTarget == key && routeFrame == frameId) return routeResult
        routeAt = level.gameTime
        routeTarget = key
        routeFrame = frameId
        val start = walkerBase(walker)
        val x = BlockPos.getX(start)
        val y = BlockPos.getY(start)
        val z = BlockPos.getZ(start)
        val pad = walkRings + ROUTE_PAD
        routeMinX = minOf(x, tx) - pad
        routeMaxX = maxOf(x, tx) + pad
        routeMinZ = minOf(z, tz) - pad
        routeMaxZ = maxOf(z, tz) + pad
        val minY = minOf(y, frameBaseY(job, walkShip)) - walkBelow - 1
        val maxY = maxOf(y, frameTopY(job, walkShip)) + walkAbove + 1
        roomFrame = walkShip
        roomWalkersBlock = true
        routeResult = wayOut(x, y, z, minY, maxY, surfaceRoom, noExit, floodQueue, routeSeen, FLOOD_CAP, solidCell)
        return routeResult
    }

    /** The walker's body base cell in the walk frame. Its position sits on the support, so the base is off it along up. */
    private fun walkerBase(walker: ConstriliarEntity): Long {
        val up = walker.serverUp()
        framePt.set(walker.x + up.x * HALF, walker.y + up.y * HALF, walker.z + up.z * HALF)
        walkShip?.worldToShip?.transformPosition(framePt)
        return BlockPos.asLong(Mth.floor(framePt.x), Mth.floor(framePt.y), Mth.floor(framePt.z))
    }

    /** The plan's lowest and highest layer in [frame]: the plan's own frame, or the world through its ship. */
    private fun frameBaseY(job: BuildJob, frame: Ship?): Int = if (frame != null) job.anchor.pos.y else job.worldBaseY(level)
    private fun frameTopY(job: BuildJob, frame: Ship?): Int = if (frame != null) job.planTopY else job.worldTopY(level)

    /**
     * A body base at ([x], [y], [z]) holds on a solid block beside it, laid out on that face by the walker's own fit
     * test, so this gate and the walker's planner agree. Ships and other walkers count on top, as for a floor.
     */
    private fun holdsOnSurface(x: Int, y: Int, z: Int): Boolean {
        val walker = standWalker ?: return bodyFits(x, y, z) && onSurface(x, y, z)
        for (face in ALL_FACES) {
            if (!solidAt(x + face.stepX, y + face.stepY, z + face.stepZ)) continue
            if (walker.bodyFitsOn(surfaceCursor.set(x, y, z), face, surfaceShips) && shipsClearOn(x, y, z, face)) return true
        }
        return false
    }

    /** No ship or other walker is in the room of a body on base ([x], [y], [z]) laid out on [face]. */
    private fun shipsClearOn(x: Int, y: Int, z: Int, face: Direction): Boolean {
        val shape = bodyShape
        for (layer in 0 until (shape?.height ?: 2)) {
            if (!bodyLayer(x, y, z, layer, maxOf(shape?.radiusAt(layer) ?: 0, 0), bodyK, face)) return false
            if (!shipsClear(bx0, by0, bz0, bx1, by1, bz1, walkersBlock = true, frame = walkShip)) return false
        }
        return true
    }

    /** A body base at ([x], [y], [z]) touches a solid block to stand or climb on: below, beside, or over the body. */
    private fun onSurface(x: Int, y: Int, z: Int): Boolean =
        solidAt(x, y - 1, z) || solidAt(x + 1, y, z) || solidAt(x - 1, y, z) || solidAt(x, y, z + 1) || solidAt(x, y, z - 1) ||
            solidAt(x, y + Mth.ceil((bodyShape?.height ?: 2) * bodyK - SCALE_EPS), z)

    private fun solidAt(x: Int, y: Int, z: Int): Boolean {
        surfaceCursor.set(x, y, z)
        if (!level.isLoaded(surfaceCursor)) return false
        return !level.getBlockState(surfaceCursor).getCollisionShape(level, surfaceCursor).isEmpty || shipBlockAt(x, y, z) != null
    }

    /**
     * Takes into [surfaceShips] the ships (not walkers, not the carrier) round [walker] and the target column ([tx],
     * [ty], [tz]) in a world-frame search: the walker's planner climbs their hulls, so the search must see them too.
     */
    private fun gatherSurfaceShips(job: BuildJob, walker: ConstriliarEntity?, tx: Int, ty: Int, tz: Int) {
        surfaceShips.clear()
        if (walkShip != null) return
        val pad = (walkRings + ROUTE_PAD + 1).toDouble()
        val wx = walker?.x ?: tx.toDouble()
        val wy = walker?.y ?: ty.toDouble()
        val wz = walker?.z ?: tz.toDouble()
        val base = frameBaseY(job, null)
        val top = frameTopY(job, null)
        val minY = minOf(wy, ty.toDouble(), base.toDouble()) - walkBelow - (top - base) - 3.0
        val maxY = maxOf(wy, ty.toDouble(), top.toDouble()) + walkAbove + 3.0
        surfaceBox.setMin(minOf(wx, tx.toDouble()) - pad, minY, minOf(wz, tz.toDouble()) - pad)
        surfaceBox.setMax(maxOf(wx, tx.toDouble()) + pad + 1.0, maxY, maxOf(wz, tz.toDouble()) + pad + 1.0)
        val dim = level.dimensionId
        for (ship in level.shipObjectWorld.loadedShips.getIntersecting(surfaceBox, dim)) {
            if (ship.id == carrierShip || ConstriliarImpactSounds.walkerOf(dim, ship.id) != null) continue
            surfaceShips.add(ship)
        }
    }

    /**
     * The colliding block of a [surfaceShips] ship at the centre of world cell ([x], [y], [z]), as the walker's planner
     * reads ship cells, with its shipyard pos left in [surfaceShipPos]; null when none.
     */
    private fun shipBlockAt(x: Int, y: Int, z: Int): BlockState? {
        for (i in surfaceShips.indices) {
            val ship = surfaceShips[i]
            val bounds = ship.shipAABB ?: continue
            ship.worldToShip.transformPosition(surfacePt.set(x + HALF, y + HALF, z + HALF))
            surfaceShipPos.set(Mth.floor(surfacePt.x), Mth.floor(surfacePt.y), Mth.floor(surfacePt.z))
            if (surfaceShipPos.x < bounds.minX() || surfaceShipPos.x > bounds.maxX() || surfaceShipPos.y < bounds.minY() ||
                surfaceShipPos.y > bounds.maxY() || surfaceShipPos.z < bounds.minZ() || surfaceShipPos.z > bounds.maxZ()
            ) {
                continue
            }
            if (!level.isLoaded(surfaceShipPos)) continue
            val state = level.getBlockState(surfaceShipPos)
            if (!state.getCollisionShape(level, surfaceShipPos).isEmpty) return state
        }
        return null
    }

    /** A [surfaceShips] block fills world cell ([x], [y], [z]) and its [side] face holds a body. */
    private fun shipFaceSturdy(x: Int, y: Int, z: Int, side: Direction): Boolean =
        shipBlockAt(x, y, z)?.isFaceSturdy(level, surfaceShipPos, side) == true

    /** Block ([x], [y], [z]) of the walk frame, or a [surfaceShips] block there, has a sturdy [side] face. */
    private fun sturdyFace(x: Int, y: Int, z: Int, side: Direction): Boolean {
        scratch.set(x, y, z)
        if (!level.isLoaded(scratch)) return false
        return level.getBlockState(scratch).isFaceSturdy(level, scratch, side) || shipFaceSturdy(x, y, z, side)
    }

    /**
     * Sets [colWall] and [colWallFace] to the wall spot of walk-frame column ([x], [z]), from [y] + [walkAbove] down to
     * [y] - [walkBelow], whose gem comes nearest the first target point; [NO_POS] when none. Bounded: the band times
     * four faces.
     */
    private fun scanWall(level: ServerLevel, job: BuildJob, x: Int, y: Int, z: Int) {
        colWall = NO_POS
        colWallFace = FLOOR_FACE
        colCeiling = NO_POS
        var best = Double.MAX_VALUE
        var bestCeiling = Double.MAX_VALUE
        for (wy in y + walkAbove downTo y - walkBelow) {
            val cell = BlockPos.asLong(x, wy, z)
            for (face in HORIZONTAL_FACES) {
                val d = spotGemDistSq(cell, face.get3DDataValue())
                if (d >= best || !wallStandable(level, job, x, wy, z, face)) continue
                colWall = cell
                colWallFace = face.get3DDataValue()
                best = d
            }
            // Under a ceiling: the carrier hangs beneath a bowsprit or a crow's nest to place the cells under it.
            val d = spotGemDistSq(cell, CEILING_FACE)
            if (d < bestCeiling && wallStandable(level, job, x, wy, z, Direction.UP)) {
                colCeiling = cell
                bestCeiling = d
            }
        }
    }

    private fun spotGemDistSq(cell: Long, face: Int): Double {
        val dx = spotGemX(cell, face, walkLift) - targetPts[0]
        val dy = spotGemY(cell, face, walkLift) - targetPts[1]
        val dz = spotGemZ(cell, face, walkLift) - targetPts[2]
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * The body can hold on the wall beside cell ([x], [y], [z]) on support [face]: a sturdy wall face, room laid out
     * from it with no plan cell, ship or walker in it, no floor under the cell (a floor spot holds that cell), and
     * the walker's own goal rules on that face.
     */
    @Suppress("ReturnCount") // One early exit per rule.
    private fun wallStandable(level: ServerLevel, job: BuildJob, x: Int, y: Int, z: Int, face: Direction): Boolean {
        if (!freeCell(level, job, x, y, z) || solidAt(x, y - 1, z)) return false
        val sx = x + face.stepX
        val sy = y + face.stepY
        val sz = z + face.stepZ
        if (!sturdyFace(sx, sy, sz, face.opposite)) return false
        val shape = bodyShape
        for (layer in 0 until (shape?.height ?: 2)) {
            if (!bodyLayer(x, y, z, layer, maxOf(shape?.radiusAt(layer) ?: 0, 0), bodyK, face)) return false
            for (cx in lx0..lx1) {
                for (cy in ly0..ly1) {
                    for (cz in lz0..lz1) if (!freeCell(level, job, cx, cy, cz)) return false
                }
            }
            if (!shipsClear(bx0, by0, bz0, bx1, by1, bz1, walkersBlock = true, frame = walkShip)) return false
        }
        val walker = standWalker ?: return true
        val reject = walker.standReject(scratch.set(sx, sy, sz), standOthers, face)
        if (reject != ConstriliarSurfaceNavigator.STAND_OK) {
            standRejects[reject]++
            return false
        }
        if (walker.lastGoalHoldsWork()) return true
        beamFloors++
        return false
    }

    // World-frame ship surfaces: empty while the walk frame is a ship's shipyard, where its blocks are read directly.
    private val surfaceShips = ArrayList<Ship>()
    private val surfaceBox = AABBd()
    private val surfacePt = Vector3d()
    private val surfaceShipPos = BlockPos.MutableBlockPos()

    /**
     * Sets [colNearest] to the walk-frame floor from [y] + [walkAbove] down to [y] - [walkBelow] that puts the
     * gem nearest the target height [targetY], and [colLowest] to the lowest floor down to the plan's base, so the
     * ground stays a choice under high cells. Either is [NO_POS] when the column has none.
     */
    private fun scanColumn(level: ServerLevel, job: BuildJob, x: Int, y: Int, z: Int, targetY: Double) {
        colNearest = NO_POS
        colLowest = NO_POS
        var bestGap = Double.MAX_VALUE
        val base = frameBaseY(job, walkShip)
        val bottom = maxOf(minOf(y - walkBelow, base - 1), y - walkBelow - (frameTopY(job, walkShip) - base + 1) - 2)
        for (fy in y + walkAbove downTo bottom) {
            if (!standable(level, job, x, fy, z)) continue
            val floor = BlockPos.asLong(x, fy, z)
            colLowest = floor
            if (fy < y - walkBelow) continue
            val gap = Math.abs(fy + 1.0 + walkLift - targetY)
            if (gap < bestGap) {
                colNearest = floor
                bestGap = gap
            }
        }
    }

    /**
     * A loaded sturdy top with room above for the walker's whole body, layer by layer as its route planner
     * wants it ([ConstriliarCargoShape.radiusAt]), all left empty by the plan. In the world frame no ship block may
     * be in that room either, by VS2's own overlap test.
     */
    private fun standable(level: ServerLevel, job: BuildJob, x: Int, y: Int, z: Int): Boolean {
        scratch.set(x, y, z)
        if (!level.isLoaded(scratch)) return false
        if (!level.getBlockState(scratch).isFaceSturdy(level, scratch, Direction.UP) && !shipFaceSturdy(x, y, z, Direction.UP)) return false
        val shape = bodyShape
        val height = shape?.height ?: 2
        for (layer in 0 until height) {
            if (!bodyLayer(x, y + 1, z, layer, maxOf(shape?.radiusAt(layer) ?: 0, 0), bodyK)) return false
            for (cx in lx0..lx1) {
                for (cy in ly0..ly1) {
                    for (cz in lz0..lz1) if (!freeCell(level, job, cx, cy, cz)) return false
                }
            }
            if (!shipsClear(bx0, by0, bz0, bx1, by1, bz1, walkersBlock = true, frame = walkShip)) return false
        }
        // The walker's planner must take it as a goal too, or the carrier walks to the nearest node it does take
        // and never arrives.
        val walker = standWalker ?: return true
        val reject = walker.standReject(scratch.set(x, y, z), standOthers)
        if (reject != ConstriliarSurfaceNavigator.STAND_OK) {
            standRejects[reject]++
            return false
        }
        // Fewer than 3 quadrants with a foot or a grip hold the walker to cross, not to park through a work cycle.
        if (walker.lastGoalHoldsWork()) return true
        beamFloors++
        return false
    }

    private fun planWants(job: BuildJob, x: Int, y: Int, z: Int): Boolean {
        val f = planCellOf(job, x, y, z)
        return job.planWantsBlockAt(level, BlockPos.getX(f), BlockPos.getY(f), BlockPos.getZ(f))
    }

    private fun freeCell(level: ServerLevel, job: BuildJob, x: Int, y: Int, z: Int): Boolean {
        scratch.set(x, y, z)
        return level.isLoaded(scratch) && level.getBlockState(scratch).getCollisionShape(level, scratch).isEmpty &&
            !planWants(job, x, y, z)
    }

    /**
     * Takes the walker's whole body shape, the walkers round it, and the walk frame for the next floor search: the
     * plan ship's shipyard while the walker stands on any face of it, else the world. The gem and the lift go into it.
     */
    private fun clearFor(walker: ConstriliarEntity, job: BuildJob) {
        bodyShape = walker.navShape()
        standWalker = walker
        standOthers = walker.otherWalkersNear(walker.position())
        walkShip = walkFrame(job, walker)
        walkGem.set(gem)
        walkShip?.worldToShip?.transformPosition(walkGem)
        framePt.set(walker.x, walker.y, walker.z)
        walkShip?.worldToShip?.transformPosition(framePt)
        walkLift = walkGem.y - framePt.y
        val frameScale = walkShip?.transform?.shipToWorldScaling?.x() ?: 1.0
        bodyK = carrierScale / frameScale
        walkReach = reachNow / frameScale
        // The walker stops within its arrival radius in world blocks, whatever frame the spot is in.
        walkMargin = WORK_SPOT_MARGIN / frameScale
        walkRings = workSpotRings(walkReach, walkMargin)
        walkAbove = Math.ceil(walkReach).toInt().coerceIn(1, MAX_FLOOR_BAND)
        walkBelow = walkAbove - 1
    }

    /** The plan's ship when [walker] stands on any face of it, else null for the world. */
    private fun walkFrame(job: BuildJob, walker: ConstriliarEntity): Ship? {
        val ship = job.planShip(level) ?: return null
        if (walkFrameAt != level.gameTime || walkFrameWalker != walker.uuid) {
            walkFrameAt = level.gameTime
            walkFrameWalker = walker.uuid
            walkFrameOn = walker.standsOnShip(ship.id)
        }
        return if (walkFrameOn) ship else null
    }

    private var walkFrameAt = Long.MIN_VALUE
    private var walkFrameWalker: UUID? = null
    private var walkFrameOn = false

    /** The centre of level block [packed] (the world, or any ship's shipyard) as a point in [frame] (null: world). */
    private fun toFrame(frame: Ship?, packed: Long, out: Vector3d): Vector3d {
        val x = BlockPos.getX(packed)
        val y = BlockPos.getY(packed)
        val z = BlockPos.getZ(packed)
        out.set(x + HALF, y + HALF, z + HALF)
        if (level.isBlockInShipyard(x, y, z)) {
            val ship = level.getShipManagingPos(out)
            if (ship != null && ship.id == frame?.id) return out
            ship?.shipToWorld?.transformPosition(out)
        }
        frame?.worldToShip?.transformPosition(out)
        return out
    }

    /** Writes the walk-frame point of level block [packed] into [out] at [at]. */
    private fun framePoint(packed: Long, out: DoubleArray, at: Int) {
        toFrame(walkShip, packed, framePt)
        out[at] = framePt.x
        out[at + 1] = framePt.y
        out[at + 2] = framePt.z
    }

    /** Why floors of the last search failed the walker's own goal rules, by [ConstriliarSurfaceNavigator] code. */
    private fun rejectNote(): String {
        val sb = StringBuilder()
        for (i in 1 until standRejects.size) {
            if (standRejects[i] == 0) continue
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(ConstriliarSurfaceNavigator.STAND_NAMES[i]).append(" x").append(standRejects[i])
        }
        noteCount(sb, "too few footholds", beamFloors)
        noteCount(sb, "failed lately", failedFloors)
        noteCount(sb, "no route from the carrier", routeFloors)
        return if (sb.isEmpty()) "" else "; floors refused: $sb"
    }

    private fun noteCount(sb: StringBuilder, name: String, count: Int) {
        if (count == 0) return
        if (sb.isNotEmpty()) sb.append(", ")
        sb.append(name).append(" x").append(count)
    }

    // ---- redstone mining: no plan and no job. The user's rules: only a powered Clawhod with a tool mines, in reach
    // on the facing side, the world and every ship but its own; always the closest block first, whatever tool it needs.

    private var mining = false
    private var mineCursor = MineCursor(MINE_RADIUS)
    private var mineCursorRadius = MINE_RADIUS

    /** Restarts the sweep at the centre, with the mining radius in the carrier's scale through [frameScale]. */
    private fun restartMine(x: Int, y: Int, z: Int, frameScale: Double) {
        val r = Math.ceil(MINE_RADIUS * carrierScale / frameScale).toInt().coerceIn(1, MINE_RADIUS_CAP)
        // A new cursor only when the scale changes: its shell offsets are built once per radius.
        if (r != mineCursorRadius) {
            mineCursor = MineCursor(r)
            mineCursorRadius = r
        }
        mineCursor.restart(x, y, z)
    }
    private val mineChests = ArrayList<BlockPos>()
    private var mineFrame = FRAME_WORLD
    private var mineShip = NO_SHIP
    private var mineShips: List<Ship> = emptyList()

    /** The next sweep's frame: 0 the world, k the ship mineShips[k - 1]. One cycle sweeps them all. */
    private var sweepNext = 0

    /** Blocks this cycle found for the pass tool, over all its frames. */
    private var cycleHits = 0
    private val mineFacing = Vector3d()
    private val mineCentre = Vector3d()
    private val mineNote = ReasonNote()
    private var miner: ServerPlayer? = null

    /**
     * Chests (packed) where a tool swap failed; skipped, so a per-block swap does not fly there again. Cleared when a
     * refused chest changes ([BuildJobs.mineChestEpoch]) and at each new cycle.
     */
    private val mineSwapRefused = HashSet<Long>()
    private var mineSwapEpoch = 0

    /** Clears [mineSwapRefused] once a refused chest changed, as it may now have room or the tool. */
    private fun syncSwapRefused(jobs: BuildJobs) {
        if (jobs.mineChestEpoch == mineSwapEpoch) return
        mineSwapEpoch = jobs.mineChestEpoch
        mineSwapRefused.clear()
    }

    /** The tool broke while mining: a new one may come from a chest. With no tool at all, nothing is mined. */
    private var toolBroke = false

    private inline fun mineSay(why: MineWhy, arg: Int = 0, detail: () -> String) {
        if (mineNote.changed(why, arg)) MineLog.line(be.workerId, why, detail())
    }

    private fun minerActor(level: ServerLevel): ServerPlayer =
        miner?.takeIf { it.level() === level } ?: SpellPlatform.fakePlayer(level, MINER_PROFILE).also { miner = it }

    private fun tickMine(level: ServerLevel) {
        if (!mining) {
            mining = true
            mineNote.clear()
            sweepNext = 0
            firstMine = true
            quietSweeps = false
            mineBlocked = false
            mineSwapRefused.clear()
            startSweep(level)
            mineSay(MineWhy.STARTED) {
                val ships = if (level.getShipManagingPos(be.blockPos) != null) "other ships" else "ships"
                "redstone signal: mines the world and $ships in reach on the facing side, never its own ship"
            }
        }
        if (level.gameTime % IDLE_RETRY_TICKS == 0L || mineChests.isEmpty()) collectChests(level, mineChests)
        for (h in hands) if (!h.relaying && h.stage != Stage.HOME && level.gameTime >= h.arriveAt) mineArrive(level, h)
        val toolHand = hands.firstOrNull { !it.tool().isEmpty }
        if (toolHand == null) {
            if (hands.any { it.stage != Stage.HOME }) return
            if (!toolBroke) {
                mineSay(MineWhy.NO_TOOL) {
                    val contents = hands.joinToString(" | ") { h -> h.slots.joinToString { "${it.count} ${BuildLog.itemName(it.item)}" } }
                    "no tool in a hand; a Clawhod mines only with a tool (hands: $contents)"
                }
                return
            }
            return mineNext(level)
        }
        // The user's rule: the hand without the tool carries the drops, while the tool hand goes on mining.
        val carrier = hands.firstOrNull { it !== toolHand }
        if (carrier != null && carrier.stage == Stage.HOME && !carrier.relaying) carrierStep(level, carrier)
        if ((toolHand.stage == Stage.HOME || toolHand.cutChain) && !toolHand.relaying) mineNext(level)
    }

    /** The carrier could not take the floating drops and the next block's: it unloads even with free room. */
    private var mineBlocked = false

    /** True until this start mined its first block, for one log line. */
    private var firstMine = true

    /** Empty sweeps log until MINED_OUT; then they are quiet until a block is mined again. */
    private var quietSweeps = false

    /** The carrier hand collects loose items in reach, then takes a full load to a chest or up a chain. */
    private fun carrierStep(level: ServerLevel, carrier: Hand) {
        if (looseScanned && startLoose(carrier, idle = false)) return
        if (!carrier.hasCargo()) return
        val full = carrier.slots.none { it.isEmpty } || looseWaits(carrier)
        if (full || mineBlocked || quietSweeps) mineUnload(level, minerActor(level), carrier)
    }

    private fun stopMine() {
        if (!mining) return
        mining = false
        toolBroke = false
        for (h in hands) {
            h.mineTask = null
            h.fetchItem = null
            if (h.stage != Stage.HOME) flyHome(h)
        }
        MineLog.line(be.workerId, MineWhy.STOPPED, "the redstone signal ended, or the Clawhod joined a job")
    }

    /**
     * A new sweep of one frame (the user's rule): the world first, then each ship in reach but the one this Clawhod
     * sits on.
     */
    private fun startSweep(level: ServerLevel) {
        val own = level.getShipManagingPos(be.blockPos)
        val ship = if (sweepNext == 0) null else mineShips.getOrNull(sweepNext - 1)
        if (ship == null) {
            mineSwapRefused.clear()
            cycleHits = 0
            mineShips = level.getShipsIntersecting(AABB(gem.x, gem.y, gem.z, gem.x, gem.y, gem.z).inflate(reachNow))
                .filter { it.id != own?.id }
            sweepNext = 1
            mineFrame = FRAME_WORLD
            mineShip = NO_SHIP
            recentreMine()
            return
        }
        sweepNext++
        mineFrame = FRAME_SHIP
        mineShip = ship.id
        recentreMine()
    }

    /** Looks again from the gem out, in the sweep's frame; the gem moves with the ship that carries the Clawhod. */
    private fun recentreMine() {
        if (mineFrame == FRAME_WORLD) {
            restartMine(Mth.floor(gem.x), Mth.floor(gem.y), Mth.floor(gem.z), 1.0)
            return
        }
        val ship = mineShips.firstOrNull { it.id == mineShip }
        if (ship == null) {
            mineCursor.rewind()
            return
        }
        mineCentre.set(gem)
        ship.worldToShip.transformPosition(mineCentre)
        restartMine(Mth.floor(mineCentre.x), Mth.floor(mineCentre.y), Mth.floor(mineCentre.z), ship.transform.shipToWorldScaling.x())
    }

    private fun mineNext(level: ServerLevel) {
        val actor = minerActor(level)
        val scan = mineCursor.nextMinable(MINE_SCAN_PER_TICK) { x, y, z ->
            scratch.set(x, y, z)
            // The user's rule: two Clawhods never go for the same block; the sweep moves on.
            mineable(level, actor, scratch) && !breaksElsewhere(scratch.asLong())
        }
        when (scan) {
            MineScan.FOUND -> startMine(level, actor, scratch.immutable(), level.getBlockState(scratch))
            MineScan.ENDED -> endSweep(level)
            MineScan.BUDGET -> Unit
        }
    }

    /** At the end of a cycle that found nothing in any frame: mined out. */
    private fun endSweep(level: ServerLevel) {
        if (!quietSweeps) {
            val frame = if (mineFrame == FRAME_WORLD) "the world" else "ship $mineShip"
            MineLog.line(be.workerId, MineWhy.SWEEP_EMPTY, "the sweep of $frame found nothing to mine")
        }
        cycleHits += mineCursor.hits
        val cycleEnd = sweepNext > mineShips.size
        if (cycleEnd && cycleHits == 0) {
            quietSweeps = true
            mineSay(MineWhy.MINED_OUT) { "nothing left to mine in reach on the facing side" }
            for (h in hands) if (h.cutChain) flyHome(h)
        }
        if (cycleEnd) sweepNext = 0
        startSweep(level)
    }

    @Suppress("ReturnCount") // One return per rule reads clearer than one long condition.
    private fun mineable(level: ServerLevel, actor: ServerPlayer, pos: BlockPos): Boolean {
        if (!level.isLoaded(pos)) return false
        val state = level.getBlockState(pos)
        if (state.isAir || state.block is LiquidBlock) return false
        if (mineFrame == FRAME_WORLD) {
            if (level.isBlockInShipyard(pos)) return false
        } else if (level.getShipManagingPos(pos)?.id != mineShip) {
            return false
        }
        if (!worldCentre(pos, target) || target.distanceSquared(gem) > reachNow * reachNow) return false
        if (!inFront(target.x - gem.x, target.y - gem.y, target.z - gem.z, mineFacing.x, mineFacing.y, mineFacing.z)) return false
        if (state.getDestroySpeed(level, pos) < 0f) return false
        // Another Clawhod, or a walker's plate, is never ore.
        if (state.`is`(EKBlocks.CLAWHOD.get()) || state.`is`(EKBlocks.CONSTRILIAR_PLATFORM.get())) return false
        if (level.getBlockEntity(pos)?.holdsContents() == true) return false
        if (!mayEdit(level, actor, pos) || !SpellPlatform.mayBreak(level, pos, state, actor)) {
            mineSay(MineWhy.REFUSED) { "a block permission check refused ${BuildLog.blockName(state)} at ${pos.toShortString()}; skipped" }
            return false
        }
        return true
    }

    /** Sends the tool hand to [pos]; first to a chest for the block's tool, or a new tool when the last one broke. */
    private fun startMine(level: ServerLevel, actor: ServerPlayer, pos: BlockPos, state: BlockState) {
        val holder = hands.firstOrNull { !it.tool().isEmpty }
        if (holder == null) {
            // The user's rule: no tool, no mining; only a tool that broke here is replaced from a chest.
            val taker = hands.firstOrNull { h -> h.slots.any { it.isEmpty } }
            if (taker != null && mineToolFetch(level, actor, taker, state, pos, matchOnly = false)) return
            mineSay(MineWhy.NO_TOOL) { "its tool broke and no chest in reach has one for ${BuildLog.blockName(state)}; no mining" }
            return
        }
        if (mineSwap(level, actor, holder, state, pos)) {
            recentreMine()
            return
        }
        if (!mineFits(level, actor, pos, state, holder.tool())) {
            mineBlocked = true
            flyHome(holder)
            recentreMine()
            mineSay(MineWhy.DROPS_WAIT) {
                "the floating drops in reach and the drops of ${BuildLog.blockName(state)} at ${pos.toShortString()} " +
                    "do not fit in the carrier hand; mining waits for the carrier"
            }
            return
        }
        holder.mineTask = BuildTask(TaskKind.CUT, pos.asLong(), pos, state, null)
        holder.cutChain = false
        holder.stage = Stage.CELL
        fly(holder, pos)
        // The user's rule: closest first, so the next pick looks again from the gem out.
        recentreMine()
    }

    /**
     * The user's rule: closest block first, whatever tool it needs. A hand without the block's tool type swaps at a
     * chest in reach for one, else for the pickaxe fallback; with neither, the tool it holds breaks the block.
     */
    private fun mineSwap(level: ServerLevel, actor: ServerPlayer, h: Hand, state: BlockState, pos: BlockPos): Boolean {
        if (toolRank(h.tool(), state) == TOOL_MATCH) return false
        if (mineToolFetch(level, actor, h, state, pos, matchOnly = true)) {
            mineSay(MineWhy.TOOL_FETCH, pos.hashCode()) { "a chest in reach has the tool for ${BuildLog.blockName(state)}; swaps before mining it" }
            return true
        }
        if (h.tool().item is PickaxeItem || !mineToolFetch(level, actor, h, state, pos, matchOnly = false)) return false
        mineSay(MineWhy.TOOL_FETCH, pos.hashCode()) { "no chest in reach has the tool for ${BuildLog.blockName(state)}; swaps for a pickaxe" }
        return true
    }

    /**
     * Sends [h] to the chest in reach with the best tool for [state] (only its tool type with [matchOnly]); then on
     * to [pos]. False when no chest has one.
     */
    @Suppress("LongParameterList") // Each value is one part of the trip: who, what for, where, and which tools count.
    private fun mineToolFetch(
        level: ServerLevel, actor: ServerPlayer, h: Hand, state: BlockState, pos: BlockPos, matchOnly: Boolean,
    ): Boolean {
        syncSwapRefused(BuildJobs.get(level))
        val found = bestToolIn(
            level, mineChests, state, matchOnly, usable = { mayEdit(level, actor, it) },
            skip = { at, _ -> at.asLong() in mineSwapRefused },
        ) ?: return false
        val bestPos = found.pos
        val bestItem = found.item
        h.mineTask = BuildTask(TaskKind.CUT, pos.asLong(), pos, state, null)
        h.supply = bestPos
        h.fetchItem = bestItem
        h.cutChain = false
        h.stage = Stage.FETCH
        fly(h, bestPos)
        return true
    }

    private fun mineArrive(level: ServerLevel, h: Hand) {
        when (h.stage) {
            Stage.RETURN -> h.stage = Stage.HOME
            Stage.FETCH -> if (lidReady(h)) mineFetched(level, h)
            Stage.LOOSE -> looseArrive(h, idle = !mining)
            Stage.CELL -> {
                val t = h.mineTask
                if (t == null) {
                    if (!h.cutChain) flyHome(h)
                    return
                }
                val tool = h.tool()
                // The user's rule: no tool, no breaking.
                if (tool.isEmpty) return flyHome(h)
                if (!punch(level, h, t, tool)) return
                breakMined(level, h, t.pos)
                h.mineTask = null
                // The user's rule: the tool hand goes on from this cell; with a broken tool it goes home.
                if (h.tool().isEmpty) flyHome(h) else h.cutChain = true
            }
            Stage.UNLOAD -> {
                if (!lidReady(h)) return
                h.supply?.let { depositMined(level, minerActor(level), h, it) }
                flyHome(h)
            }
            else -> flyHome(h)
        }
    }

    private fun mineFetched(level: ServerLevel, h: Hand) {
        val at = h.supply
        val item = h.fetchItem
        h.supply = null
        h.fetchItem = null
        val actor = minerActor(level)
        val container = at?.let { HopperBlockEntity.getContainerAt(level, it) }
        val why = when {
            at == null || item == null || container == null -> "the chest is gone"
            !mayEdit(level, actor, at) -> "a block permission check refused the chest"
            else -> takeToolInto(h, container, item)
        }
        if (why != null) {
            mineSay(MineWhy.NO_TOOL) { "took no tool: $why" }
            if (at != null) {
                val jobs = BuildJobs.get(level)
                syncSwapRefused(jobs)
                mineSwapRefused.add(at.asLong())
                jobs.watchRefusedChest(at)
            }
            h.mineTask = null
            return flyHome(h)
        }
        toolBroke = false
        mineSay(MineWhy.TOOL_FETCH, item.hashCode()) { "takes a ${item?.let(BuildLog::itemName)} from ${at?.toShortString()} for the next block" }
        val t = h.mineTask ?: return flyHome(h)
        if (!mineFits(level, actor, t.pos, level.getBlockState(t.pos), h.tool())) {
            h.mineTask = null
            return flyHome(h)
        }
        h.stage = Stage.CELL
        fly(h, t.pos)
    }

    /**
     * The user's rule: no mining while the loose items in reach and this block's drops would not fit in the carrier
     * hand, the one hand that collects while the tool hand mines.
     */
    private fun mineFits(level: ServerLevel, actor: ServerPlayer, pos: BlockPos, state: BlockState, tool: ItemStack): Boolean {
        val carrier = hands.firstOrNull { it.tool().isEmpty } ?: return false
        return looseFits(Block.getDrops(state, level, pos, level.getBlockEntity(pos), actor, tool), carrier)
    }

    private fun breakMined(level: ServerLevel, h: Hand, pos: BlockPos) {
        val actor = minerActor(level)
        if (!mineable(level, actor, pos)) {
            mineSay(MineWhy.SKIPPED, pos.hashCode()) { "the block at ${pos.toShortString()} is gone or left the mining area; next block" }
            return
        }
        val state = level.getBlockState(pos)
        val tool = h.tool()
        val drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), actor, tool)
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state))
        level.removeBlock(pos, false)
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(state))
        val toolItem = tool.item
        if (h.wearTool(level, pos, state)) {
            toolBroke = true
            mineSay(MineWhy.TOOL_BROKE) { "the ${BuildLog.itemName(toolItem)} broke on ${BuildLog.blockName(state)} at ${pos.toShortString()}" }
        }
        if (firstMine) {
            firstMine = false
            MineLog.line(be.workerId, MineWhy.MINED, "first block: ${BuildLog.blockName(state)} at ${pos.toShortString()}")
        }
        quietSweeps = false
        for (drop in drops) popFloating(level, pos, drop)
        looseAt = 0L
        mineBlocked = false
    }

    /**
     * Sends a hand with items to a chest in reach with room, one that already holds the item first (like with like);
     * with none, a chain of idle Clawhods takes them to a chest, or mining waits.
     */
    private fun mineUnload(level: ServerLevel, actor: ServerPlayer, h: Hand) {
        val first = h.firstCargo() ?: return
        val at = likeForLike(level, actor, mineChests, first)
        if (at != null) {
            h.supply = at
            h.stage = Stage.UNLOAD
            fly(h, at)
            mineSay(MineWhy.UNLOAD, at.hashCode()) { "the carrier hand takes the load to ${at.toShortString()}" }
            return
        }
        if (requestReverse(level, h)) return
        mineSay(MineWhy.HANDS_FULL) { "the hands cannot take the next block's drops and no chest or chain takes them; mining waits" }
    }

    /** The first chest in [chests] that already holds [stack] and has room, else the first with room; permission checked. */
    private fun likeForLike(level: ServerLevel, actor: ServerPlayer, chests: List<BlockPos>, stack: ItemStack): BlockPos? {
        var roomy: BlockPos? = null
        for (at in chests) {
            if (!level.isLoaded(at) || !mayEdit(level, actor, at)) continue
            val container = HopperBlockEntity.getContainerAt(level, at) ?: continue
            if (container.roomFor(stack) <= 0) continue
            if (container.holdsSame(stack)) return at
            if (roomy == null) roomy = at
        }
        return roomy
    }

    private fun depositMined(level: ServerLevel, actor: ServerPlayer, h: Hand, at: BlockPos) {
        h.supply = null
        // The carrier may have walked the chest away during the flight; the load then waits for the next unload.
        if (!reaches(at.asLong()) || !mayEdit(level, actor, at)) return
        val container = HopperBlockEntity.getContainerAt(level, at) ?: return
        val slots = h.slots
        for (i in slots.indices) {
            if (!h.isCargo(i)) continue
            slots[i] = HopperBlockEntity.addItem(null, container, slots[i], null)
        }
        if (!h.hasCargo()) h.looseLoad = false
        be.handsChanged()
    }

    /**
     * The containers this Clawhod's hands reach, in its own frame and, on a ship, in the world: read from the loaded
     * chunks' block entity maps, so no cell scan (Rule 2). At most [MAX_CHAIN_CHESTS].
     */
    internal fun collectChests(level: ServerLevel, out: MutableList<BlockPos>) {
        out.clear()
        val own = be.blockPos
        chestsAround(level, own.x + HALF, own.y + HALF, own.z + HALF, out)
        if (level.isBlockInShipyard(own)) chestsAround(level, gem.x, gem.y, gem.z, out)
    }

    private fun chestsAround(level: ServerLevel, x: Double, y: Double, z: Double, out: MutableList<BlockPos>) {
        val r = reachNow
        for (cx in (Mth.floor(x - r) shr 4)..(Mth.floor(x + r) shr 4)) {
            for (cz in (Mth.floor(z - r) shr 4)..(Mth.floor(z + r) shr 4)) {
                val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
                for ((pos, entity) in chunk.blockEntities) {
                    if (out.size >= MAX_CHAIN_CHESTS) return
                    if (entity !is Container || !withinReach(x, y, z, pos.x, pos.y, pos.z, reachNow)) continue
                    out.add(pos)
                }
            }
        }
    }

    // ---- loose items. The user's rule: hands take any loose item in reach (vanilla pickup rules, no permission
    // check) to a chest; mined and cut drops float until then.

    private val loose = ArrayList<ItemEntity>()

    /** The tick of the next scan of the reach for loose items; 0 scans on the next tick. */
    private var looseAt = 0L
    private var looseScanned = false

    // An item with a long pickup delay (thrown, or never to be picked up) counts only while fresh.
    private val looseFilter = Predicate<ItemEntity> { e ->
        e.isAlive && e.distanceToSqr(gem.x, gem.y, gem.z) <= reachNow * reachNow &&
            (!e.hasPickUpDelay() || e.age < LOOSE_FRESH_TICKS)
    }

    /** Finds the loose items in reach at a fixed cadence (Rule 2): one capped AABB query, into a reused list. */
    private fun scanLoose(level: ServerLevel) {
        looseScanned = level.gameTime >= looseAt
        if (!looseScanned) return
        looseAt = level.gameTime + LOOSE_SCAN_TICKS
        loose.clear()
        val r = reachNow
        level.getEntities(EntityType.ITEM, AABB(gem.x - r, gem.y - r, gem.z - r, gem.x + r, gem.y + r, gem.z + r), looseFilter, loose, LOOSE_MAX)
        for (e in loose) {
            if (!e.isNoGravity) continue
            level.sendParticles(ParticleTypes.PORTAL, e.x, e.y + SHIMMER_LIFT, e.z, SHIMMER_COUNT, SHIMMER_SPREAD, SHIMMER_SPREAD, SHIMMER_SPREAD, SHIMMER_SPEED)
        }
    }

    /** No job and no signal: free hands take loose items that a chest in reach takes, then put them there. */
    private fun tickIdleLoose(level: ServerLevel) {
        if (relay != null) return
        if (looseScanned) collectChests(level, mineChests)
        for (h in hands) {
            if (h.relaying || h.stage == Stage.RETURN) continue
            if (h.stage != Stage.HOME) {
                if (level.gameTime >= h.arriveAt) mineArrive(level, h)
                continue
            }
            if (!looseScanned || startLoose(h, idle = true)) continue
            if (h.looseLoad && h.hasCargo()) idleUnload(level, h)
        }
    }

    private fun idleUnload(level: ServerLevel, h: Hand) {
        val first = h.firstCargo() ?: return
        val at = likeForLike(level, minerActor(level), mineChests, first) ?: return
        h.supply = at
        h.stage = Stage.UNLOAD
        fly(h, at)
    }

    /**
     * Sends [h] to the nearest loose item in reach that it has room for and no hand claims. A working hand always
     * takes floating drops; other items, and every item for an [idle] hand, only when a chest in reach takes them,
     * so an item a full supply dropped is not taken again. An idle hand takes items only while it holds nothing
     * else. True when the hand flew.
     */
    private fun startLoose(h: Hand, idle: Boolean): Boolean {
        if (loose.isEmpty() || !h.tool().isEmpty || (idle && h.hasCargo() && !h.looseLoad)) return false
        var best: ItemEntity? = null
        var bestD = Double.MAX_VALUE
        for (e in loose) {
            if (!e.isAlive || e.hasPickUpDelay()) continue
            val d = e.distanceToSqr(gem.x, gem.y, gem.z)
            if (d >= bestD || d > reachNow * reachNow || pickupRoom(h.slots, e.item) <= 0 || claimed(e)) continue
            if ((idle || !e.isNoGravity) && !chestTakes(e.item)) continue
            best = e
            bestD = d
        }
        val e = best ?: return false
        if (h.stage == Stage.HOME) h.pos.set(gem)
        h.looseItem = e
        h.stage = Stage.LOOSE
        fly(h, e.blockPosition(), looseId = e.id)
        return true
    }

    /** A job supply in reach, or with no job a chest in reach, has room for [stack]; permission checked. */
    private fun chestTakes(stack: ItemStack): Boolean {
        val job = jobId?.let { BuildJobs.get(level).job(it) } ?: return likeForLike(level, minerActor(level), mineChests, stack) != null
        val actor = job.actor(level)
        for (pos in job.supplies) {
            if (!level.isLoaded(pos) || !reaches(pos.asLong()) || !mayEdit(level, actor, pos)) continue
            if ((HopperBlockEntity.getContainerAt(level, pos)?.roomFor(stack) ?: 0) > 0) return true
        }
        return false
    }

    /** Takes the claimed item, then flies on to the next one or home. */
    private fun looseArrive(h: Hand, idle: Boolean) {
        takeLoose(h)
        if (!startLoose(h, idle)) flyHome(h)
    }

    private fun takeLoose(h: Hand) {
        val e = h.looseItem ?: return
        h.looseItem = null
        if (!e.isAlive || e.hasPickUpDelay() || e.distanceToSqr(gem.x, gem.y, gem.z) > reachNow * reachNow) return
        val stack = e.item
        val work = h.workSlot()
        val n = pickupInsert(h.slots, stack, pickupRoom(h.slots, stack))
        if (n <= 0) return
        // The user's rule: a loose tool is carried to a chest, never used, so it never becomes the working tool.
        if (isToolItem(stack.item)) for (i in 0 until HAND_SLOTS) if (i != work && isClawhodTool(h.slots[i])) h.setCarried(i, true)
        // setItem, not shrink: the entity data syncs the new count to clients.
        if (n >= stack.count) e.discard() else e.item = stack.copyWithCount(stack.count - n)
        h.looseLoad = true
        be.handsChanged()
    }

    internal fun chases(e: ItemEntity): Boolean = hands.any { it.looseItem === e }

    // Mining claims are the hands' mine tasks, read across the level's Clawhods: no shared map to go stale.
    // A claim ends when the task does: on the break, an abort, a stop, an unload or the Clawhod's removal.

    /** A hand of this Clawhod mines [packed], or flies to it, or fetches a tool for it. */
    internal fun mines(packed: Long): Boolean {
        for (h in hands) {
            val t = h.mineTask
            if (t != null && t.pos.asLong() == packed) return true
        }
        return false
    }

    /** A hand of this Clawhod mines [packed] or holds a build-job CUT task for it. */
    internal fun breaks(packed: Long): Boolean {
        if (mines(packed)) return true
        for (h in hands) for (t in h.tasks) if (t.kind == TaskKind.CUT && t.pos.asLong() == packed) return true
        return false
    }

    private fun breaksElsewhere(packed: Long): Boolean {
        for (w in BuildJobs.get(level).relays.all) if (w !== this && w.breaks(packed)) return true
        return false
    }

    private fun minedElsewhere(packed: Long): Boolean {
        for (w in BuildJobs.get(level).relays.all) if (w !== this && w.mines(packed)) return true
        return false
    }

    private fun otherClaims(e: ItemEntity): Boolean {
        for (w in BuildJobs.get(level).relays.all) if (w !== this && w.chases(e)) return true
        return false
    }

    private fun claimed(e: ItemEntity): Boolean = chases(e) || otherClaims(e)

    /** A floating drop in reach that no other Clawhod claims waits, and [h] has no room for it. */
    private fun looseWaits(h: Hand): Boolean {
        for (e in loose) if (floatingDrop(e) && pickupRoom(h.slots, e.item) <= 0 && !otherClaims(e)) return true
        return false
    }

    private fun floatingDrop(e: ItemEntity): Boolean = e.isAlive && e.isNoGravity

    /**
     * The user's cap: the floating drops in reach that no other Clawhod claims, then [drops], all fit in the room of
     * [only], or of both hands when null.
     */
    private fun looseFits(drops: List<ItemStack>, only: Hand?): Boolean {
        val copies = ArrayList<MutableList<ItemStack>>(CLAWHOD_HANDS)
        for (h in hands) if (if (only == null) h.tool().isEmpty else h === only) copies.add(h.slots.mapTo(ArrayList(HAND_SLOTS)) { it.copy() })
        for (e in loose) {
            if (floatingDrop(e) && !otherClaims(e) && !fitsIn(copies, e.item)) return false
        }
        for (drop in drops) if (!fitsIn(copies, drop)) return false
        return true
    }

    private fun fitsIn(copies: List<MutableList<ItemStack>>, stack: ItemStack): Boolean {
        var left = stack.count
        for (slots in copies) {
            if (left == 0) break
            left -= pickupInsert(slots, stack, minOf(pickupRoom(slots, stack), left))
        }
        return left == 0
    }

    // ---- item chains. The user's rules: links work for any job, only when idle, a carrier never moves for one,
    // a broken chain returns its items, and mined items go back up a chain to supply chests.

    private var relay: RelayTask? = null

    /** A link whose chain broke while a hand held its items: they go back to a chest in reach, or up a chain. */
    private var relayReturn: RelayTask? = null

    /** The item this Clawhod asked a chain for, the chain's links, and how many arrived; or null, empty and 0. */
    private var relayWant: Item? = null
    private var relayChain: List<ClawhodWorker> = emptyList()
    private var relayGot = 0

    /** The hand whose need the open request is for. */
    private var relayHand = 0

    /** The chain that last ended with nothing delivered, as [chainSig]: the same chain is not formed again. */
    private var relayBackoff = NO_SIG
    private var returnBackoff = NO_SIG
    private var returnTryAt = 0L

    /** Goes up when this Clawhod takes work or a link, so a backed-off chain through it may form again. */
    internal var busyEpoch = 0
        private set

    internal val id: UUID get() = be.workerId

    internal val gemWorld: Vector3dc get() = gem

    /**
     * No own work, no mining, no link or return, nothing asked for, a hand with a free slot, and no carrier that
     * walks or will walk for a far cell: such a link stays where the chain was planned. A Clawhod whose own chain
     * is backed off may serve (the user's rule).
     */
    internal fun relayIdle(): Boolean {
        if (relay != null || relayReturn != null || relayWant != null || mining || moveSpot != NO_POS) return false
        if (!hands.all { it.stage == Stage.HOME && it.tasks.isEmpty() && (jobId == null || it.noWork) }) return false
        if (!hands.any { h -> h.tool().isEmpty && h.slots.any { it.isEmpty } }) return false
        val walker = carrier(level) ?: return true
        return !carrierWalks(walker) && hands.none { it.pick.farCell != NO_POS }
    }

    private fun carrierWalks(walker: ConstriliarEntity): Boolean = !walker.orderSettled() && !walker.orderFailed()

    internal fun assignRelay(task: RelayTask) {
        relay = task
        busyEpoch++
    }

    internal fun wantsRelay(item: Item): Boolean = relayWant == item

    internal fun relayFor(requester: ClawhodWorker, item: Item): Boolean = relay?.let { it.requester === requester && it.item == item } == true

    /** This Clawhod's link still passes [item] on to [next]. */
    internal fun linksTo(next: ClawhodWorker, item: Item): Boolean = relay?.let { it.next === next && it.item == item } == true

    /** Takes what fits of [stack] from the link before this one into the home hands, most room first; returns the count. */
    internal fun receiveRelay(stack: ItemStack): Int {
        val item = stack.item
        val link = relay
        val forMe = relayWant == item
        if (!forMe && (link == null || link.item != item || link.supply != null || link.own)) return 0
        var taken = 0
        for (pass in 0 until CLAWHOD_HANDS) {
            var best: Hand? = null
            var bestRoom = 0
            for (h in hands) {
                if (h.stage != Stage.HOME || h.relaying || !h.tool().isEmpty) continue
                val room = relayRoom(h.slots, item)
                if (room > bestRoom) {
                    best = h
                    bestRoom = room
                }
            }
            val h = best ?: break
            val left = stack.count - taken
            if (left <= 0) break
            taken += pickupInsert(h.slots, stack.copyWithCount(left), minOf(bestRoom, left))
        }
        if (taken <= 0) return 0
        be.handsChanged()
        if (forMe) {
            relayGot += taken
            for (x in hands) x.resetIdle()
            // Full hands: the last link holds the rest, so its link ends and returns them.
            if (taken < stack.count) endWant()
        }
        return taken
    }

    /**
     * The user's rule: a chain moves loads until the need is met, here until the hands are full of the item less
     * what the links already carry. Bounded by the chain length.
     */
    internal fun needsMore(item: Item): Boolean {
        if (relayWant != item) return false
        var room = 0
        for (h in hands) if (h.tool().isEmpty) room += relayRoom(h.slots, item)
        for (w in relayChain) room -= w.slotCountAll(item)
        return room > 0
    }

    private fun endWant() {
        relayWant = null
        relayChain = emptyList()
        relayGot = 0
    }

    /**
     * Ends this Clawhod's request when no link of its chain still carries the item. A chain that delivered nothing
     * is backed off until a link or this Clawhod changes, so it does not form and break again at once.
     */
    private fun settleRelay(job: BuildJob?) {
        val want = relayWant ?: return
        val now = neededItem(hands[relayHand].pick)
        if (now != null && now != want) {
            say(job, ClawhodWhy.RELAY, BuildLog.itemId(now)) { "needs ${BuildLog.itemName(now)} now; the chain for ${BuildLog.itemName(want)} ends" }
            return endWant()
        }
        val net = BuildJobs.get(level).relays
        if (relayChain.any { net.contains(it) && it.relayFor(this, want) }) return
        if (relayGot == 0) {
            relayBackoff = chainSig(relayChain, want)
            say(job, ClawhodWhy.RELAY_BREAK, BuildLog.itemId(want)) {
                "the chain for ${BuildLog.itemName(want)} ended with nothing delivered; it waits until a link or this Clawhod changes"
            }
        }
        endWant()
    }

    /** Identity, place and work epoch of each link, the item and this Clawhod's gem and held count, hashed. */
    private fun chainSig(nodes: List<ClawhodWorker>, item: Item): Long {
        var sig = BuildLog.itemId(item).toLong() * SIG_PRIME + slotCountAll(item)
        sig = sig * SIG_PRIME + cellOf(gem)
        for (w in nodes) {
            sig = sig * SIG_PRIME + System.identityHashCode(w)
            sig = sig * SIG_PRIME + w.busyEpoch
            sig = sig * SIG_PRIME + cellOf(w.gemWorld)
        }
        return if (sig == NO_SIG) NO_SIG + 1 else sig
    }

    private fun cellOf(v: Vector3dc): Long = BlockPos.asLong(Mth.floor(v.x() * 2), Mth.floor(v.y() * 2), Mth.floor(v.z() * 2))

    private fun slotCountAll(item: Item): Int {
        var n = 0
        for (h in hands) n += slotCount(h.slots, item)
        return n
    }

    /**
     * Asks idle Clawhods between [hand]'s supply and this one to pass the item on, instead of a carrier walk.
     * True while a chain carries it.
     */
    @Suppress("ReturnCount") // One return per reason a chain is not asked for reads clearer than nested branches.
    private fun requestRelay(level: ServerLevel, job: BuildJob, hand: Hand): Boolean {
        val need = neededItem(hand.pick) ?: return false
        val block = Block.byItem(need)
        if (block == Blocks.AIR) return false
        settleRelay(job)
        if (relayWant == need) return true
        if (relayWant != null) endWant()
        // A link serves its chain first; it asks for its own when that ends.
        if (relay != null) return false
        val supply = hand.pick.farSupply?.takeIf { hand.pick.farItem == need } ?: nearestSupply(level, job, need) ?: return false
        val count = hands.maxOf { it.capacity(block) }
        if (count <= 0) return false
        val path = chainPath(level) { it.reachesPos(supply.asLong()) } ?: return false
        val chain = path.nodes
        if (relayBackoff != NO_SIG && chainSig(chain, need) == relayBackoff) {
            say(job, ClawhodWhy.RELAY_BACKOFF, BuildLog.itemId(need)) {
                "the same chain for ${BuildLog.itemName(need)} broke before; it is not formed again until a link or this Clawhod changes"
            }
            return false
        }
        relayBackoff = NO_SIG
        relayWant = need
        relayChain = chain
        relayGot = 0
        relayHand = hand.index
        for (k in chain.indices) {
            val next = chain.getOrNull(k + 1) ?: this
            val prev = chain.getOrNull(k - 1)
            chain[k].assignRelay(RelayTask(need, count, if (k == 0) supply else null, next, null, this, job.id, job.supplies, prev = prev))
        }
        say(job, ClawhodWhy.RELAY, BuildLog.itemId(need)) {
            "a chain of ${chain.size} idle Clawhod(s) brings $count ${BuildLog.itemName(need)} from ${supply.toShortString()}; the carrier stays"
        }
        return true
    }

    /**
     * The user's rule: a miner with no chest in reach that takes its items sends them back up a chain of idle
     * Clawhods to any chest the last link reaches, like with like first. True when a chain takes them.
     */
    private fun requestReverse(level: ServerLevel, h: Hand): Boolean {
        val chain = chainUp(level, h, minerActor(level), emptyList(), null, returning = false) ?: return false
        mineSay(MineWhy.RELAY_OUT, BuildLog.itemId(chain.item)) {
            "no chest in reach takes the items: a chain of ${chain.links} idle Clawhod(s) carries ${chain.count} " +
                "${BuildLog.itemName(chain.item)} to ${chain.chest.toShortString()}"
        }
        return true
    }

    private class ChainUp(val item: Item, val count: Int, val links: Int, val chest: BlockPos)

    /**
     * Sends [h]'s first plain item up a chain of idle Clawhods to a chest the last link reaches (like with like
     * first; [extra] chests count too), with [actor]'s permission checks. Null when no chain reaches one.
     */
    @Suppress("LongParameterList") // Mining and returns differ only in these.
    private fun chainUp(
        level: ServerLevel, h: Hand, actor: ServerPlayer, extra: List<BlockPos>, jobId: UUID?, returning: Boolean,
    ): ChainUp? {
        // Chains move plain items only; a carried tool waits for a chest in reach.
        val first = h.slots.firstOrNull { !it.isEmpty && !isClawhodTool(it) } ?: return null
        val found = ArrayList<BlockPos>()
        val gather = { w: ClawhodWorker ->
            w.collectChests(level, found)
            for (pos in extra) if (pos !in found && w.reachesPos(pos.asLong())) found.add(pos)
        }
        val path = chainPath(level) { w ->
            gather(w)
            likeForLike(level, actor, found, first) != null
        } ?: return null
        // The path runs chest side first; the items go the other way.
        val chain = path.nodes.asReversed()
        val last = chain.last()
        gather(last)
        val chest = likeForLike(level, actor, found, first) ?: return null
        val count = slotCount(h.slots, first.item)
        val chests = ArrayList(found)
        for (k in chain.indices) {
            val next = chain.getOrNull(k + 1)
            val prev = chain.getOrNull(k - 1) ?: this
            chain[k].assignRelay(
                RelayTask(first.item, count, null, next, if (next == null) chest else null, null, jobId, chests, prev = prev),
            )
        }
        relay = RelayTask(first.item, count, null, chain[0], null, null, jobId, chests, own = true, returning = returning)
        return ChainUp(first.item, count, chain.size, chest)
    }

    private class ChainPath(val nodes: List<ClawhodWorker>)

    /** Idle Clawhods from one that [reachesEnd] to one that reaches this Clawhod; the far end first. */
    private fun chainPath(level: ServerLevel, reachesEnd: (ClawhodWorker) -> Boolean): ChainPath? {
        val net = BuildJobs.get(level).relays
        val nodes = ArrayList<ClawhodWorker>()
        for (w in net.all) {
            if (nodes.size >= MAX_RELAY_NODES) break
            if (w !== this && w.gemWorld.distanceSquared(gem) <= CHAIN_RANGE * CHAIN_RANGE && w.relayIdle()) nodes.add(w)
        }
        if (nodes.isEmpty()) return null
        val n = nodes.size
        val path = relayPath(
            DoubleArray(n) { nodes[it].gemWorld.x() }, DoubleArray(n) { nodes[it].gemWorld.y() }, DoubleArray(n) { nodes[it].gemWorld.z() }, n,
            reachesRequester = { nodes[it].reachesPos(be.blockPos.asLong()) },
            reachesSupply = { reachesEnd(nodes[it]) },
            reach = reachNow, queue = IntArray(n), from = IntArray(n),
        ) ?: return null
        return ChainPath(path.map { nodes[it] })
    }

    /** The player whose permission checks a link runs: the asking job's owner, or the miner profile. */
    private fun relayActor(level: ServerLevel, task: RelayTask): ServerPlayer =
        task.jobId?.let { BuildJobs.get(level).job(it)?.actor(level) } ?: minerActor(level)

    /** Why this link must end now, or null while it holds. */
    @Suppress("ReturnCount") // One return per rule that ends a link reads clearer than one long condition.
    private fun linkEnds(level: ServerLevel, task: RelayTask): String? {
        val net = BuildJobs.get(level).relays
        val requester = task.requester
        if (requester != null && !net.contains(requester)) return "the Clawhod it brings the items to unloaded"
        if (requester != null && !requester.wantsRelay(task.item)) return "the Clawhod it brings the items to wants no more (full hands or a new need)"
        val next = task.next
        if (next != null && !net.contains(next)) return "the next link unloaded"
        if (carrier(level)?.let(::carrierWalks) == true) return "its carrier walks"
        if (upstreamGone(level, task) && task.requester == null) return "the link before it ended without passing the items on"
        return null
    }

    /** A later link holds none of the item and the link before it no longer passes any on. */
    private fun upstreamGone(level: ServerLevel, task: RelayTask): Boolean {
        val prev = task.prev ?: return false
        if (task.supply != null || slotCountAll(task.item) > 0) return false
        val net = BuildJobs.get(level).relays
        return !(net.contains(prev) && prev.linksTo(this, task.item))
    }

    /** Moves this Clawhod's link of a chain, or a return of a broken chain's items. */
    private fun tickRelay(level: ServerLevel) {
        val h = hands.firstOrNull { it.relaying }
        if (h != null && level.gameTime < h.arriveAt) return
        if (h != null && h.stage == Stage.UNLOAD) {
            if (lidReady(h)) relayReturned(level, h)
            return
        }
        val task = relay
        if (task == null) {
            relayReturn?.let { startReturn(level, it) }
            return
        }
        // Links are checked as they go: a moved chest, a moved Clawhod or a filled need ends the link.
        linkEnds(level, task)?.let { return breakLink(it) }
        // A chain to a Clawhod ends from the supply side: once the link before it is done, this one is too.
        if (h == null && upstreamGone(level, task)) {
            relay = null
            return
        }
        if (h == null) return startRelay(task)
        when (h.stage) {
            Stage.FETCH -> if (lidReady(h)) relayFetch(level, h, task)
            Stage.HANDOFF -> if (task.next != null || lidReady(h, task.toChest)) relayGive(level, h, task)
            else -> {
                h.relaying = false
                flyHome(h)
            }
        }
    }

    /** The first link fetches at the supply; a later one waits until a hand holds the item. */
    private fun startRelay(task: RelayTask) {
        val holder = hands.firstOrNull { it.stage == Stage.HOME && it.tool().isEmpty && slotCount(it.slots, task.item) > 0 }
        if (holder != null) {
            val to = task.next?.blockPos ?: task.toChest ?: return breakLink("it has no next link and no chest")
            holder.relaying = true
            holder.stage = Stage.HANDOFF
            return fly(holder, to)
        }
        val supply = task.supply ?: return
        if (!reaches(supply.asLong())) return breakLink("the supply ${supply.toShortString()} is out of its reach")
        val free = hands.firstOrNull { it.stage == Stage.HOME && it.tool().isEmpty && relayRoom(it.slots, task.item) > 0 } ?: return
        free.relaying = true
        free.supply = supply
        free.stage = Stage.FETCH
        fly(free, supply)
    }

    private fun relayFetch(level: ServerLevel, h: Hand, task: RelayTask) {
        val at = h.supply
        h.supply = null
        val container = at?.let { HopperBlockEntity.getContainerAt(level, it) }
        if (at == null || container == null) return breakLink("the supply is gone")
        // The take runs the block permission check for the job that asked.
        if (!mayEdit(level, relayActor(level, task), at)) return breakLink("a block permission check refused the supply ${at.toShortString()}")
        val want = minOf(task.count, relayRoom(h.slots, task.item), container.countOf(task.item))
        val taken = if (want > 0) pickupInsert(h.slots, ItemStack(task.item, want), want) else 0
        if (taken <= 0 || !container.takeItems(task.item, taken)) {
            if (taken > 0) slotTake(h.slots, task.item, taken)
            be.handsChanged()
            val loads = if (task.requester?.wantsRelay(task.item) == true) " after earlier loads" else ""
            return breakLink("the supply ${at.toShortString()} has no ${BuildLog.itemName(task.item)} it can take$loads")
        }
        be.handsChanged()
        val to = task.next?.blockPos ?: task.toChest ?: return breakLink("it has no next link and no chest")
        h.stage = Stage.HANDOFF
        fly(h, to)
    }

    private fun relayGive(level: ServerLevel, h: Hand, task: RelayTask) {
        val held = slotCount(h.slots, task.item)
        if (held == 0) return endLink(h)
        val next = task.next
        val to = next?.blockPos ?: task.toChest ?: return breakLink("it has no next link and no chest")
        if (!reaches(to.asLong())) return breakLink("${to.toShortString()} is out of its reach")
        val n = if (next != null) next.receiveRelay(ItemStack(task.item, held)) else putInChest(level, task, to, held)
        if (n == 0) {
            // The next link has no free hand yet: wait. A full chest breaks the chain, so the items go back.
            return if (next != null) waitHere(h) else breakLink("the chest ${to.toShortString()} is full")
        }
        slotTake(h.slots, task.item, n)
        be.handsChanged()
        say(h, task.jobId?.let { BuildJobs.get(level).job(it) }, ClawhodWhy.RELAY_GIVE, BuildLog.itemId(task.item)) {
            "passes $n ${BuildLog.itemName(task.item)} on to ${to.toShortString()}"
        }
        if (n < held) return waitHere(h)
        val requester = task.requester ?: return endLink(h)
        val supply = task.supply
        if (supply == null) {
            // A later link stays in the chain for the next load.
            h.relaying = false
            return flyHome(h)
        }
        if (!requester.needsMore(task.item)) return endLink(h)
        h.supply = supply
        h.stage = Stage.FETCH
        fly(h, supply)
    }

    private fun putInChest(level: ServerLevel, task: RelayTask, at: BlockPos, count: Int): Int {
        if (!reaches(at.asLong()) || !mayEdit(level, relayActor(level, task), at)) return 0
        val container = HopperBlockEntity.getContainerAt(level, at) ?: return 0
        return count - HopperBlockEntity.addItem(null, container, ItemStack(task.item, count), null).count
    }

    private fun endLink(h: Hand) {
        relay = null
        h.relaying = false
        flyHome(h)
    }

    /**
     * Ends this link and logs [why]. Items it holds for another Clawhod, or a return of them, go back to a chest
     * (the user's rule); a miner's own load stays for its next unload.
     */
    private fun breakLink(why: String) {
        val task = relay ?: return
        relay = null
        for (h in hands) {
            if (!h.relaying) continue
            h.relaying = false
            h.supply = null
            flyHome(h)
        }
        val held = slotCountAll(task.item)
        say(task.jobId?.let { BuildJobs.get(level).job(it) }, ClawhodWhy.RELAY_BREAK, BuildLog.itemId(task.item)) {
            "its link of the chain for ${BuildLog.itemName(task.item)} ends: $why" + if (held > 0) "; returns the $held it holds" else ""
        }
        if ((!task.own || task.returning) && held > 0) relayReturn = task
    }

    /** A broken chain's items go to a chest in reach, like with like; with none, up a chain of idle Clawhods. */
    private fun startReturn(level: ServerLevel, task: RelayTask) {
        val h = hands.firstOrNull { it.stage == Stage.HOME && !it.relaying && slotCount(it.slots, task.item) > 0 }
        if (h == null) {
            if (hands.none { slotCount(it.slots, task.item) > 0 }) relayReturn = null
            return
        }
        // Rule 2: the chest and chain search runs at the idle-retry cadence, not every tick.
        if (level.gameTime < returnTryAt) return
        returnTryAt = level.gameTime + IDLE_RETRY_TICKS
        val stack = ItemStack(task.item, slotCount(h.slots, task.item))
        val candidates = ArrayList<BlockPos>()
        collectChests(level, candidates)
        for (pos in task.chests) if (pos !in candidates && reaches(pos.asLong())) candidates.add(pos)
        val actor = relayActor(level, task)
        val chest = likeForLike(level, actor, candidates, stack)
        if (chest != null) {
            h.relaying = true
            h.supply = chest
            h.stage = Stage.UNLOAD
            fly(h, chest)
            return
        }
        returnUpChain(level, h, task, actor, stack)
    }

    private fun returnUpChain(level: ServerLevel, h: Hand, task: RelayTask, actor: ServerPlayer, stack: ItemStack) {
        val job = task.jobId?.let { BuildJobs.get(level).job(it) }
        val sig = chainSig(emptyList(), task.item) * SIG_PRIME + BuildJobs.get(level).relays.all.size
        if (sig == returnBackoff) return
        val chain = chainUp(level, h, actor, task.chests, task.jobId, returning = true)
        if (chain == null) {
            returnBackoff = sig
            say(h, job, ClawhodWhy.RELAY_RETURN, BuildLog.itemId(task.item)) {
                "the chain broke; keeps ${stack.count} ${BuildLog.itemName(task.item)}: no chest in reach and no chain of idle Clawhods to one"
            }
            return
        }
        returnBackoff = NO_SIG
        relayReturn = null
        say(h, job, ClawhodWhy.RELAY_RETURN, BuildLog.itemId(task.item)) {
            "the chain broke; a chain of ${chain.links} idle Clawhod(s) takes ${chain.count} ${BuildLog.itemName(task.item)} " +
                "back to ${chain.chest.toShortString()}"
        }
    }

    private fun relayReturned(level: ServerLevel, h: Hand) {
        val task = relayReturn
        val at = h.supply
        h.supply = null
        h.relaying = false
        flyHome(h)
        if (task == null || at == null) return
        val held = slotCount(h.slots, task.item)
        val n = if (held > 0) putInChest(level, task, at, held) else 0
        if (n > 0) {
            slotTake(h.slots, task.item, n)
            be.handsChanged()
            say(h, null, ClawhodWhy.RELAY_RETURN, BuildLog.itemId(task.item)) {
                "the chain broke; returned $n ${BuildLog.itemName(task.item)} to ${at.toShortString()}"
            }
        }
        if (hands.none { slotCount(it.slots, task.item) > 0 }) relayReturn = null
    }

    internal val blockPos: BlockPos get() = be.blockPos

    internal fun holdsTool(): Boolean = hands.any { !it.tool().isEmpty }

    internal fun toolHandWillCut(packed: Long, state: BlockState): Boolean = hands.any { it.willCut(state) } && reaches(packed)

    internal fun holdsWorkingTool(hand: Int): Boolean = !hands[hand].tool().isEmpty

    internal fun matchToolCuts(packed: Long, state: BlockState): Boolean =
        hands.any { toolRank(it.tool(), state) == TOOL_MATCH } && reaches(packed)

    internal fun reachesPos(packed: Long): Boolean = reaches(packed)

    // ---- stock-up: fill the hands at an out-of-the-way supply before the carrier goes to the cells

    /** Starts [h]'s stock trip when a stock-up runs and its supply is in reach. True when the hand flew. */
    private fun stockUp(job: BuildJob, h: Hand): Boolean {
        val item = stockItem ?: return false
        val at = stockAt
        val block = Block.byItem(item)
        if (at == null || block == Blocks.AIR) {
            endStock()
            return false
        }
        if (!reaches(at.asLong())) return false
        if (hands.all { it.stocked || it.capacity(block) <= 0 || it.refuses(item) }) {
            endStock()
            return false
        }
        if (h.stocked || h.capacity(block) <= 0 || h.refuses(item)) return false
        val free = (HopperBlockEntity.getContainerAt(level, at)?.countOf(item) ?: 0) - job.pendingFetch(at, item, h.workerId)
        h.stocked = true
        if (free <= 0) return false
        h.dropWork()
        h.supply = at
        h.fetchItem = item
        h.fetchCount = minOf(h.capacity(block), free)
        job.claimFetch(h.workerId, at, item, h.fetchCount)
        h.pos.set(gem)
        h.stage = Stage.FETCH
        say(h, job, ClawhodWhy.STOCK_UP, BuildLog.itemId(item)) {
            "fills up with ${BuildLog.itemName(item)} at ${at.toShortString()} before the carrier goes to the cells"
        }
        fly(h, at)
        return true
    }

    /**
     * The hand holds a block no cell in reach takes now, while a cell in reach needs another block that a supply in
     * reach has. Not during a stock-up, and never back to a block this hand put away until it placed something or
     * the supplies changed. True when the hand should put its block away; logs SWAP.
     */
    private fun swapFor(level: ServerLevel, job: BuildJob, h: Hand): Boolean {
        if (stockItem != null) return false
        val held = h.heldBlock() ?: return false
        val tally = h.pick.reportTally()
        if (!tally.has(WhyNot.HOLDING_OTHER)) return false
        val cost = tally.stateOf(WhyNot.HOLDING_OTHER)?.planCost() as? PlanCost.Items ?: return false
        val want = cost.item
        if (want == held.asItem()) return false
        val back = want == h.swappedAway && !h.placedSinceSwap && h.swapEpoch == job.supplyEpoch
        if (back) return false
        val supply = nearestSupply(level, job, want) ?: return false
        if (!reaches(supply.asLong())) return false
        say(h, job, ClawhodWhy.SWAP, BuildLog.itemId(want)) {
            "nothing in reach takes ${BuildLog.itemName(held.asItem())}; puts it away and fetches ${BuildLog.itemName(want)} " +
                "for ${tally.firstPosOf(WhyNot.HOLDING_OTHER).toShortString()} at ${supply.toShortString()}"
        }
        h.swappedAway = held.asItem()
        h.swapEpoch = job.supplyEpoch
        h.placedSinceSwap = false
        return true
    }

    /**
     * A hand keeps its block for a far cell over [HOLD_MOVES] carrier moves; after them, with no move under way and
     * the cell still out of reach, it puts the block away. Before this it held it until a supply change woke the
     * job, as the user saw. Logs the hold and its end.
     */
    private fun keepsForFar(job: BuildJob, h: Hand): Boolean {
        val held = h.heldBlock()
        val count = h.heldCount()
        val far = h.pick.farCell
        if (held !== h.holdBlock || count != h.holdCount) {
            h.holdBlock = held
            h.holdCount = count
            h.holdMoves = movesSent
            say(h, job, ClawhodWhy.HOLD_FOR_FAR, BlockPos.getX(far) xor BlockPos.getZ(far)) {
                "keeps $count ${held?.let { BuildLog.itemName(it.asItem()) }} for out-of-reach cell ${BlockPos.of(far).toShortString()}; " +
                    "the carrier takes it there"
            }
            return true
        }
        if (moveSpot != NO_POS || movesSent - h.holdMoves < HOLD_MOVES) return true
        say(h, job, ClawhodWhy.HOLD_ENDS) {
            "the carrier moved $HOLD_MOVES times and cell ${BlockPos.of(far).toShortString()} is still out of reach; puts its block away"
        }
        h.holdBlock = null
        return false
    }

    /** The hand's search found an out-of-reach BUILD cell that costs the block the hand holds. */
    private fun holdsForFarCell(h: Hand): Boolean {
        val p = h.pick
        val held = h.heldBlock() ?: return false
        if (p.farCell == NO_POS || !p.farBuild) return false
        val cost = p.farState?.planCost() as? PlanCost.Items ?: return false
        return clawhodBlockOf(cost.stack()) == held
    }

    private fun endStock() {
        stockItem = null
        stockAt = null
        for (h in hands) h.stocked = false
    }

    // ---- the carrier's room: no block goes into its body, or walls it in

    /** Claims the floor the carrier stands on, so other carriers of the job keep off it. */
    private fun claimStandingFloor(job: BuildJob) {
        val walker = carrier(level) ?: return
        if (!walker.orderSettled()) return
        // The claim is in the walk frame, as the spots sent to the walker are.
        val frame = walkFrame(job, walker)
        framePt.set(walker.x, walker.y + HALF, walker.z)
        frame?.worldToShip?.transformPosition(framePt)
        val bx = Mth.floor(framePt.x)
        val by = Mth.floor(framePt.y)
        val bz = Mth.floor(framePt.z)
        // A wall claim holds while the body stays by its cell: a floor claim under a wall body would be wrong.
        val held = job.standSpotOf(walker.uuid)
        if (held != NO_POS && job.standFaceOf(walker.uuid) != FLOOR_FACE &&
            maxOf(Math.abs(BlockPos.getX(held) - bx), Math.abs(BlockPos.getY(held) - by), Math.abs(BlockPos.getZ(held) - bz)) <= 1
        ) {
            return
        }
        val shape = walker.navShape()
        job.claimStandSpot(walker.uuid, BlockPos.asLong(bx, by - 1, bz), shape.radius * carrierScale, FLOOR_FACE, shape.height * carrierScale)
    }

    /** [packed] is a cell in the plan's frame: the shipyard for a ship job. Walkers are moved into that frame. */
    private fun keepsCarrierFree(packed: Long): Boolean {
        val x = BlockPos.getX(packed)
        val y = BlockPos.getY(packed)
        val z = BlockPos.getZ(packed)
        val job = floodJob
        val frame = job?.planShip(level)
        // A shipyard cell of a world job sits on another ship, out of this job's frame.
        if (frame == null && level.isBlockInShipyard(x, y, z)) return true
        val walker = carrier(level)
        if (otherHoldsNoted(x, y, z, walker?.uuid)) return false
        if (walker == null || job == null) return true
        val ship = level.shipObjectWorld.loadedShips.getById(carrierShip) ?: return true
        if (bodyIn(ship, walker.navShape(), x, y, z, frame)) return false
        // Only a carrier inside the plan with a way out can be walled in.
        if (refreshFlood(walker) != WayOut.OUT || reachable.isEmpty()) return true
        if (!touchesFlood(job, packed)) return true
        if (floodsLeft <= 0) return false
        floodsLeft--
        floodExcluded = packed
        val result = flood(walker, floodSeen)
        floodExcluded = NO_POS
        return result != WayOut.SHUT
    }

    /** This carrier's body, or another job carrier's body or claimed stand spot, takes plan cell [pos]. Notes the holder. */
    private fun walkerHolds(job: BuildJob, pos: BlockPos): Boolean {
        val walker = carrier(level)
        if (otherHoldsNoted(pos.x, pos.y, pos.z, walker?.uuid)) return true
        if (walker == null) return false
        val ship = level.shipObjectWorld.loadedShips.getById(carrierShip) ?: return false
        return bodyIn(ship, walker.navShape(), pos.x, pos.y, pos.z, job.planShip(level))
    }

    /** Another job carrier holds plan cell ([x], [y], [z]): its Clawhods learn which cell waits on them, so an idle one steps out. */
    private fun otherHoldsNoted(x: Int, y: Int, z: Int, own: UUID?): Boolean {
        val holder = otherCarrierHolding(x, y, z, own) ?: return false
        val cell = BlockPos.asLong(x, y, z)
        for (i in jobPeers.indices) if (jobPeers[i].carrierId == holder) jobPeers[i].blockedCell = cell
        return true
    }

    /**
     * The walker of another Clawhod of the job whose body, or whose claimed stand spot, takes plan cell
     * ([x], [y], [z]), or null. Walkers are VS2 ships, so the ENTITY_IN_CELL test does not see them.
     */
    private fun otherCarrierHolding(x: Int, y: Int, z: Int, own: UUID?): UUID? {
        val job = floodJob ?: return null
        for (i in jobPeers.indices) {
            val peer = jobPeers[i]
            val walker = peer.carrier(level) ?: continue
            if (walker.uuid == own) continue
            if (peer.carrierHolds(job, walker, x, y, z)) return walker.uuid
        }
        return null
    }

    /**
     * This Clawhod's carrier [walker], with its body or on its claimed stand spot, takes plan cell ([x], [y], [z]).
     * The body is tested in the plan's frame; the cell goes into the stand spot's own frame (world or shipyard).
     */
    private fun carrierHolds(job: BuildJob, walker: ConstriliarEntity, x: Int, y: Int, z: Int): Boolean {
        val cell = BlockPos.asLong(x, y, z)
        val spot = job.standSpotOf(walker.uuid)
        val spotFace = job.standFaceOf(walker.uuid)
        var nearSpot = false
        var spotK = carrierScale
        if (spot != NO_POS) {
            val spotShip = frameOfBlock(spot)
            spotK = carrierScale / (spotShip?.transform?.shipToWorldScaling?.x() ?: 1.0)
            toFrame(spotShip, cell, cellPoint)
            val reach = (ConstriliarCargoShape.MAX_REACH + 1.0) * spotK
            nearSpot = if (spotFace == FLOOR_FACE) {
                cellPoint.y > BlockPos.getY(spot) + 1 && cellPoint.y < BlockPos.getY(spot) + 1 + ConstriliarCargoShape.MAX_HEIGHT * spotK &&
                    Math.abs(cellPoint.x - BlockPos.getX(spot) - HALF) <= reach && Math.abs(cellPoint.z - BlockPos.getZ(spot) - HALF) <= reach
            } else {
                // A wall body lies out along any axis: a cube round its cell, as far as the longest body goes.
                val far = reach + ConstriliarCargoShape.MAX_HEIGHT * spotK
                Math.abs(cellPoint.x - BlockPos.getX(spot) - HALF) <= far && Math.abs(cellPoint.y - BlockPos.getY(spot) - HALF) <= far &&
                    Math.abs(cellPoint.z - BlockPos.getZ(spot) - HALF) <= far
            }
        }
        toFrame(null, cell, bodyPoint)
        val ship = level.shipObjectWorld.loadedShips.getById(carrierShip)
        val box = ship?.worldAABB
        val nearBody = box != null && bodyPoint.x + 1 > box.minX() && bodyPoint.x - 1 < box.maxX() &&
            bodyPoint.y + 1 > box.minY() && bodyPoint.y - 1 < box.maxY() && bodyPoint.z + 1 > box.minZ() && bodyPoint.z - 1 < box.maxZ()
        if (!nearSpot && !nearBody) return false
        // navShape rescans the body's blocks: once per tick is enough for other Clawhods' checks.
        if (heldShapeAt != level.gameTime) {
            heldShape = walker.navShape()
            heldShapeAt = level.gameTime
        }
        val shape = heldShape ?: return false
        val inSpotBody = nearSpot && if (spotFace == FLOOR_FACE) {
            pointInBody(spot, shape, cellPoint.x, cellPoint.y, cellPoint.z, spotK)
        } else {
            pointInWallBody(spot, spotFace, shape, cellPoint.x, cellPoint.y, cellPoint.z, spotK)
        }
        if (inSpotBody) return true
        return nearBody && ship != null && bodyIn(ship, shape, x, y, z, job.planShip(level))
    }

    /** The ship whose shipyard holds block [packed], or null for a world block. */
    private fun frameOfBlock(packed: Long): Ship? {
        val x = BlockPos.getX(packed)
        val z = BlockPos.getZ(packed)
        return if (level.isBlockInShipyard(x, BlockPos.getY(packed), z)) level.getShipManagingPos(x shr 4, z shr 4) else null
    }

    private val cellPoint = Vector3d()
    private val bodyPoint = Vector3d()
    private var heldShape: ConstriliarCargoShape? = null
    private var heldShapeAt = Long.MIN_VALUE

    /**
     * A block of the carrier's body (plate or load) fills more than [BODY_DIP] of cell ([x], [y], [z]) of [frame]
     * (the plan's ship, or null for the world) on every axis. VS2's own oriented-box test decides it: the body's
     * boxes go into [frame], and the cell shrunk by [BODY_DIP] on each side must touch one.
     */
    private fun bodyIn(ship: Ship, shape: ConstriliarCargoShape, x: Int, y: Int, z: Int, frame: Ship?): Boolean {
        val query = bodyQuery(ship, shape, frame)
        return query.penetrationResponse(
            x + BODY_DIP, y + BODY_DIP, z + BODY_DIP, x + 1 - BODY_DIP, y + 1 - BODY_DIP, z + 1 - BODY_DIP,
        ).lengthSquared() > 0.0
    }

    /** The carrier body's boxes moved into [frame], built once per tick and frame. */
    private fun bodyQuery(ship: Ship, shape: ConstriliarCargoShape, frame: Ship?): ShipCollisionQuery {
        val frameId = frame?.id ?: -1L
        val cached = bodyQueryNow
        if (cached != null && bodyQueryAt == level.gameTime && bodyQueryFrame == frameId && bodyQueryShip == ship.id) return cached
        if (frame == null) bodyToFrame.set(ship.shipToWorld) else frame.worldToShip.mul(ship.shipToWorld, bodyToFrame)
        val query = ShipCollisionQuery()
        val boxes = query.addShip(bodyToFrame)
        for (i in 0 until shape.cellCount) {
            val c = shape.cells[i]
            val cx = BlockPos.getX(c).toDouble()
            val cy = BlockPos.getY(c).toDouble()
            val cz = BlockPos.getZ(c).toDouble()
            boxes.addBox(cx, cy, cz, cx + 1, cy + 1, cz + 1)
        }
        bodyQueryNow = query
        bodyQueryAt = level.gameTime
        bodyQueryFrame = frameId
        bodyQueryShip = ship.id
        return query
    }

    private val bodyToFrame = Matrix4d()
    private var bodyQueryNow: ShipCollisionQuery? = null
    private var bodyQueryAt = Long.MIN_VALUE
    private var bodyQueryFrame = -1L
    private var bodyQueryShip = Long.MIN_VALUE

    /**
     * No other ship's block overlaps the box of [frame] (null: the world; else the deck ship whose shipyard the box is
     * in): VS2's own entity-vs-ship test (intersecting ships, the box in each shipyard, its block collisions as
     * oriented boxes moved into [frame]). The carrier's own body and the frame's own ship never count; the deck's own
     * blocks are checked cell by cell. Other walkers' bodies count when [walkersBlock] (a stand spot or a route must
     * not go into or onto them), not for the walls-in flood, as they walk away. While a walls-in flood tests a plan
     * cell of a ship job, that cell counts as placed.
     */
    @Suppress("LongParameterList") // A box is six plain values, a flag and a frame.
    private fun shipsClear(
        minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, walkersBlock: Boolean,
        frame: Ship? = null,
    ): Boolean {
        roomBox.setMin(minX, minY, minZ).setMax(maxX, maxY, maxZ)
        if (frame == null) roomWorld.set(roomBox) else roomBox.transform(frame.shipToWorld, roomWorld)
        val dim = level.dimensionId
        val excludedShip = if (floodExcluded == NO_POS) null else frameOfBlock(floodExcluded)
        for (ship in level.shipObjectWorld.loadedShips.getIntersecting(roomWorld, dim)) {
            if (ship.id == carrierShip || ship.id == frame?.id) continue
            if (!walkersBlock && ConstriliarImpactSounds.walkerOf(dim, ship.id) != null) continue
            if (frame == null) {
                roomBox.transform(ship.worldToShip, roomLocal)
                shipToRoom.set(ship.shipToWorld)
            } else {
                ship.worldToShip.mul(frame.shipToWorld, roomToShip)
                roomBox.transform(roomToShip, roomLocal)
                frame.worldToShip.mul(ship.shipToWorld, shipToRoom)
            }
            val query = ShipCollisionQuery()
            val boxes = query.addShip(shipToRoom)
            val local = AABB(roomLocal.minX, roomLocal.minY, roomLocal.minZ, roomLocal.maxX, roomLocal.maxY, roomLocal.maxZ)
            for (shape in level.getBlockCollisions(null, local)) shape.forAllBoxes(boxes::addBox)
            if (excludedShip != null && excludedShip.id == ship.id) {
                val ex = floodExcluded
                val ex0 = BlockPos.getX(ex).toDouble()
                val ey0 = BlockPos.getY(ex).toDouble()
                val ez0 = BlockPos.getZ(ex).toDouble()
                boxes.addBox(ex0, ey0, ez0, ex0 + 1, ey0 + 1, ez0 + 1)
            }
            val hit = query.penetrationResponse(minX, minY, minZ, maxX, maxY, maxZ)
            if (hit.lengthSquared() > 0.0) return false
        }
        return true
    }

    private val roomBox = AABBd()
    private val roomWorld = AABBd()
    private val roomLocal = AABBd()
    private val roomToShip = Matrix4d()
    private val shipToRoom = Matrix4d()

    /** The carrier's way out of the plan, flooded at most once per tick into [reachable]; empty when it is outside. */
    private fun refreshFlood(walker: ConstriliarEntity): WayOut {
        if (floodAt == level.gameTime) return floodResult
        floodAt = level.gameTime
        floodResult = flood(walker, reachable)
        return floodResult
    }

    /**
     * The walls-in flood, in the carrier's walk frame: the plan ship's shipyard on any face of it (the plan's own
     * frame), else the world, where ship blocks count through [shipsClear]. Body layers run away from the support.
     */
    private fun flood(walker: ConstriliarEntity, seen: LongOpenHashSet): WayOut {
        val job = floodJob ?: return WayOut.OUT
        bodyShape = walker.navShape()
        val frame = walkFrame(job, walker)
        floodFrame = frame
        roomFrame = frame
        roomWalkersBlock = false
        bodyK = carrierScale / (frame?.transform?.shipToWorldScaling?.x() ?: 1.0)
        val up = walker.serverUp()
        floodFace = supportFaceIn(frame, up)
        framePt.set(walker.x + up.x * HALF, walker.y + up.y * HALF, walker.z + up.z * HALF)
        frame?.worldToShip?.transformPosition(framePt)
        val x = Mth.floor(framePt.x)
        val y = Mth.floor(framePt.y)
        val z = Mth.floor(framePt.z)
        if (outOfPlan.out(x, y, z)) {
            seen.clear()
            return WayOut.OUT
        }
        val minY = minOf(y, frameBaseY(job, frame)) - 1
        roomFace = floodFace
        val result = wayOut(x, y, z, minY, frameTopY(job, frame) + 1, bodyRoom, outOfPlan, floodQueue, seen, FLOOD_CAP)
        roomFace = Direction.DOWN
        return result
    }

    /** The face [up] (world) stands on, in [frame] (null: world). */
    private fun supportFaceIn(frame: Ship?, up: Vector3dc): Direction {
        faceScratch.set(up)
        frame?.worldToShip?.transformDirection(faceScratch)
        return Direction.getNearest(-faceScratch.x, -faceScratch.y, -faceScratch.z)
    }

    private val faceScratch = Vector3d()
    /** The support face of the last walls-in flood: its body layers run away from it. */
    private var floodFace = Direction.DOWN
    /** The support face [bodyFits] lays body layers on. */
    private var roomFace = Direction.DOWN

    private var floodFrame: Ship? = null

    /** Base ([x], [y], [z]) of the flood frame is out of the plan. A plan on a ship has open air under it. */
    private fun planOut(job: BuildJob, x: Int, y: Int, z: Int): Boolean {
        val onShip = job.planShip(level) != null
        val p = if (roomFrame != null || !onShip) BlockPos.asLong(x, y, z) else job.toPlanFrame(level, x, y, z)
        val bottomY = if (onShip) job.planBottomY else GROUND_PLAN_BOTTOM
        return planLayerOut(BlockPos.getY(p), job.inFootprint(BlockPos.getX(p), BlockPos.getZ(p)), job.planTopY, bottomY)
    }

    /** A block in plan cell [packed] would fill a body base the cached flood reached, in the flood's frame. */
    private fun touchesFlood(job: BuildJob, packed: Long): Boolean {
        val x = BlockPos.getX(packed)
        val y = BlockPos.getY(packed)
        val z = BlockPos.getZ(packed)
        if (floodFrame != null || job.planShip(level) == null) return touchesReachable(x, y, z)
        // A ship cell seen from a world flood: its centre's block and the blocks round it, as a rotated cell may reach them.
        toFrame(null, packed, framePt)
        val bx = Mth.floor(framePt.x)
        val by = Mth.floor(framePt.y)
        val bz = Mth.floor(framePt.z)
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) if (touchesReachable(bx + dx, by + dy, bz + dz)) return true
        return false
    }

    /** A block at ([x], [y], [z]) would fill a body base the cached flood reached. */
    private fun touchesReachable(x: Int, y: Int, z: Int): Boolean {
        val shape = bodyShape ?: return reachable.contains(BlockPos.asLong(x, y, z))
        if (floodFace != Direction.DOWN || Math.abs(bodyK - 1.0) >= SCALE_EPS) return touchesReachableScaled(shape, x, y, z)
        for (layer in 0 until shape.height) {
            val r = maxOf(shape.radiusAt(layer), 0)
            for (a in -r..r) {
                for (b in -r..r) if (reachable.contains(BlockPos.asLong(x - a, y - layer, z - b))) return true
            }
        }
        return false
    }

    /** [touchesReachable] for scaled body cells or a body off a wall or ceiling: every reached base near the cell
     *  whose layer cells take it. */
    private fun touchesReachableScaled(shape: ConstriliarCargoShape, x: Int, y: Int, z: Int): Boolean {
        val across = minOf(Mth.ceil((maxOf(shape.radius, 0) + HALF) * bodyK), MAX_BODY_SPAN)
        val up = minOf(Mth.ceil(shape.height * bodyK), MAX_BODY_SPAN)
        val upright = floodFace == Direction.DOWN
        val side = if (upright) across else maxOf(across, up)
        for (bx in x - side..x + side) {
            for (bz in z - side..z + side) {
                for (by in (if (upright) y - up else y - side)..(if (upright) y else y + side)) {
                    if (reachable.contains(BlockPos.asLong(bx, by, bz)) && cellInBody(shape, bx, by, bz, x, y, z)) return true
                }
            }
        }
        return false
    }

    private fun cellInBody(shape: ConstriliarCargoShape, bx: Int, by: Int, bz: Int, x: Int, y: Int, z: Int): Boolean {
        for (layer in 0 until shape.height) {
            if (!bodyLayer(bx, by, bz, layer, maxOf(shape.radiusAt(layer), 0), bodyK, floodFace)) return true
            if (x in lx0..lx1 && y in ly0..ly1 && z in lz0..lz1) return true
        }
        return false
    }

    /**
     * The carrier's whole body, base at ([x], [y], [z]) of [roomFrame], has free cells (the tested block counts as
     * placed). In the world frame no ship block may be in the body's room either.
     */
    private fun bodyFits(x: Int, y: Int, z: Int): Boolean {
        val shape = bodyShape
        val height = shape?.height ?: 2
        for (layer in 0 until height) {
            if (!bodyLayer(x, y, z, layer, maxOf(shape?.radiusAt(layer) ?: 0, 0), bodyK, roomFace)) return false
            for (cx in lx0..lx1) {
                for (cy in ly0..ly1) {
                    for (cz in lz0..lz1) {
                        roomCursor.set(cx, cy, cz)
                        if (roomCursor.asLong() == floodExcluded || !level.isLoaded(roomCursor)) return false
                        if (!level.getBlockState(roomCursor).getCollisionShape(level, roomCursor).isEmpty) return false
                    }
                }
            }
            // On a deck the deck's own cells were checked above; other ships count there only for the route check.
            if ((roomFrame == null || roomWalkersBlock) && !shipsClear(bx0, by0, bz0, bx1, by1, bz1, roomWalkersBlock, roomFrame)) {
                return false
            }
        }
        return true
    }

    /**
     * The carrier stands in the plan with no way out for its whole body: logs it and sends it to a floor outside
     * the plan, so it climbs or jumps for it. True when that was handled; false lets the usual moves go on.
     */
    private fun carrierTrapped(level: ServerLevel, job: BuildJob): Boolean {
        val walker = carrier(level) ?: return false
        if (refreshFlood(walker) != WayOut.SHUT) return false
        say(job, ClawhodWhy.CARRIER_TRAPPED, walker.blockPosition().hashCode()) {
            "the carrier at ${walker.blockPosition().toShortString()} has no way out of the plan for its whole body " +
                "(h=${bodyShape?.height}, face=${floodFace.name.lowercase()}, ${reachable.size} spots inside)"
        }
        val ready = readyCarrier(level, job, "the carrier is walled in") ?: return true
        clearFor(ready, job)
        val floor = BlockPos.offset(walkerBase(ready), 0, -1, 0)
        // A floor out of the plan is often past one reach from the middle of a room: look a step out each way.
        for (d in Direction.Plane.HORIZONTAL) {
            val toward = BlockPos.offset(floor, d.stepX * TRAPPED_LOOK, 0, d.stepZ * TRAPPED_LOOK)
            val spot = findSpot(level, job, toward, NO_POS, route = false)
            if (spot == NO_POS || !outsidePlan(job, spot)) continue
            sendCarrier(ready, spot)
            say(job, ClawhodWhy.CARRIER_TRAPPED_MOVE, BlockPos.getX(spot) xor BlockPos.getZ(spot)) {
                "the walled-in carrier climbs out to ${BlockPos.of(spot).toShortString()}${spotNote(job, spot)}"
            }
            return true
        }
        // The flood can miss a way out the planner has: a trap check must never hold the carrier still for good.
        say(job, ClawhodWhy.CARRIER_TRAPPED_NO_EXIT) {
            "no floor outside the plan is near the walled-in carrier; the next cell's move goes out as usual"
        }
        return false
    }

    /** A cell waits because the block would go into the carrier, or wall it in: the carrier steps out. */
    private fun carrierInWay(level: ServerLevel, job: BuildJob): Boolean {
        val h = hands.firstOrNull { it.pick.reportTally().has(WhyNot.CARRIER_IN_WAY) } ?: return false
        val cell = h.pick.reportTally().firstPosOf(WhyNot.CARRIER_IN_WAY)
        // Another carrier holds it: that carrier steps out (stepOutForOthers), not this one.
        if (otherCarrierHolding(cell.x, cell.y, cell.z, carrier(level)?.uuid) != null) return false
        val ready = readyCarrier(level, job, "cell ${cell.toShortString()} would go into the carrier, or wall it in")
            ?: return true
        clearFor(ready, job)
        remember(BlockPos.offset(walkerBase(ready), 0, -1, 0))
        val spot = findSpot(level, job, cell.asLong(), NO_POS, coverLayer = cell.y)
        if (spot == NO_POS) {
            say(job, ClawhodWhy.NO_WORK_POSITION) { "no floor for the carrier to step out to from cell ${cell.toShortString()}${rejectNote()}" }
            return true
        }
        sendCarrier(ready, spot)
        say(job, ClawhodWhy.CARRIER_IN_WAY, BlockPos.getX(spot) xor BlockPos.getZ(spot)) {
            "cell ${cell.toShortString()} waits for the carrier: it steps out to ${BlockPos.of(spot).toShortString()}${spotNote(job, spot)}"
        }
        return true
    }

    /** Another Clawhod of the job waits on a cell this carrier's body or stand spot holds: the idle carrier steps out. */
    private fun stepOutForOthers(level: ServerLevel, job: BuildJob): Boolean {
        val cell = blockedCell
        if (cell == NO_POS) return false
        blockedCell = NO_POS
        val walker = carrier(level) ?: return false
        if (!carrierHolds(job, walker, BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell))) return false
        val at = BlockPos.of(cell).toShortString()
        val ready = readyCarrier(level, job, "another Clawhod's cell $at waits for this carrier") ?: return true
        clearFor(ready, job)
        remember(BlockPos.offset(walkerBase(ready), 0, -1, 0))
        framePoint(cell, avoidPts, 0)
        avoidCount = 1
        val spot = findSpot(level, job, cell, NO_POS)
        avoidCount = 0
        if (spot == NO_POS) {
            say(job, ClawhodWhy.NO_WORK_POSITION) { "no floor for the carrier to step out to from another Clawhod's cell $at${rejectNote()}" }
            return true
        }
        sendCarrier(ready, spot)
        say(job, ClawhodWhy.CARRIER_IN_WAY, BlockPos.getX(spot) xor BlockPos.getZ(spot)) {
            "another Clawhod's cell $at waits for this carrier: it steps out to ${BlockPos.of(spot).toShortString()}${spotNote(job, spot)}"
        }
        return true
    }

    fun save(tag: CompoundTag) {
        jobId?.let { tag.putUUID(NBT_JOB, it) }
        var mask = 0
        for (h in hands) for (i in 0 until HAND_SLOTS) if (h.isCargo(i) && isClawhodTool(h.slots[i])) mask = mask or (1 shl (h.index * HAND_SLOTS + i))
        if (mask != 0) tag.putInt(NBT_CARRIED, mask)
    }

    fun load(tag: CompoundTag) {
        jobId = if (tag.hasUUID(NBT_JOB)) tag.getUUID(NBT_JOB) else null
        val mask = tag.getInt(NBT_CARRIED)
        for (h in hands) {
            for (i in 0 until HAND_SLOTS) h.setCarried(i, mask and (1 shl (h.index * HAND_SLOTS + i)) != 0)
            h.clearPlace()
            h.tasks.clear()
            h.stage = Stage.HOME
            be.setHandTarget(h.index, NO_POS)
        }
    }

    /** One hand: its own trip, cells (by its own id), punch and debug note. */
    private inner class Hand(val index: Int) : BuildWorker {
        val pick = WorkerPick(BuildPattern.byId(be.workerId.hashCode()))
        val slots: MutableList<ItemStack> get() = be.hand(index)
        val note = ReasonNote()

        var stage = Stage.HOME
        val tasks = ArrayDeque<BuildTask>()
        var supply: BlockPos? = null
        var fetchItem: Item? = null
        var fetchCount = 0
        var arriveAt = 0L

        /** See [WorkerPick.extraSupply] and [WorkerPick.kitLeg]. */
        var extraSupply: BlockPos? = null
        var extraItem: Item? = null
        var extraCount = 0
        var kitLeg = false

        /** Where the hand is, in world space, for flight times; [refreshGem] moves it with its frame each tick. */
        val pos = Vector3d()

        /** The cell (packed) of [pos] in its own frame; NO_POS for the gem. */
        var at = NO_POS

        var idleUntil = 0L
        var idleEpoch = -1

        /** The last search found nothing in reach for this hand, and it holds nothing. */
        var noWork = false

        /** A tool hand that just cut waits at the cell for its next pick, as it goes home only to swap or idle. */
        var cutChain = false

        /** When this hand's search first found its next cell out of reach, so the older one goes first. */
        var farSince = Long.MAX_VALUE

        var punchCell = NO_POS
        var punchPos: BlockPos? = null
        var punchState: BlockState? = null
        var punchTicks = 0
        var punchTotal = 0
        var punchSent = -1

        private var idFrom: UUID? = null
        private var id: UUID? = null

        // Made from the clawhod's id, which VS2 copies with the NBT, so a moved clawhod keeps its hands' cells.
        override val workerId: UUID
            get() {
                val base = be.workerId
                if (base !== idFrom) {
                    idFrom = base
                    id = handWorkerId(base, index)
                }
                return checkNotNull(id)
            }
        override val buildNote: ReasonNote get() = note

        fun resetIdle() {
            idleUntil = 0L
            noWork = false
        }

        /** The tick the hand first worked its current PLACE or FIX task, or [NO_TICK]; and whether the block is in. */
        var placeSince = NO_TICK
        var placeDone = false

        fun clearPlace() {
            placeSince = NO_TICK
            placeDone = false
        }

        fun releaseTasks(job: BuildJob) {
            clearPlace()
            for (t in tasks) job.release(t.cell, workerId)
            tasks.clear()
        }

        fun dropWork() {
            clearPlace()
            looseItem = null
            mineTask = null
            tasks.clear()
            supply = null
            fetchItem = null
            fetchCount = 0
            extraSupply = null
            extraItem = null
            extraCount = 0
            kitLeg = false
            noWork = false
        }

        override fun heldBlock(): Block? = handBlock(slots)

        override fun heldCount(): Int = heldBlock()?.let { handCount(slots, it) } ?: 0

        // Slot room only: the user's rule is that held items add no mass to the carrier.
        // The user's rule: a hand with a tool only breaks; the free hand carries.
        override fun capacity(block: Block): Int = if (tool().isEmpty) handRoom(slots, block) else 0

        override fun hold(stack: ItemStack): Int {
            val block = clawhodBlockOf(stack) ?: return 0
            val taken = pickupInsert(slots, stack, minOf(capacity(block), stack.count))
            if (taken > 0) be.handsChanged()
            return taken
        }

        override fun takeMany(count: Int) {
            val block = heldBlock() ?: return
            if (handTake(slots, block, count) > 0) be.handsChanged()
        }

        override fun countOf(item: Item): Int = handCount(slots, item)

        override fun take(item: Item, count: Int) {
            if (handTake(slots, item, count) > 0) be.handsChanged()
        }

        override fun hasRoomFor(item: Item, count: Int): Boolean = tool().isEmpty && handRoomFor(slots, item) >= count

        // The user's rule: cut drops go in the hand, then in a supply in reach; the caller drops the rest.
        override fun holdPickups(stacks: List<ItemStack>): List<ItemStack> {
            val job = jobId?.let { BuildJobs.get(level).job(it) }
            var left: ArrayList<ItemStack>? = null
            for (stack in stacks) {
                if (stack.isEmpty) continue
                val taken = if (tool().isEmpty) pickupInsert(slots, stack, stack.count) else 0
                var rest = if (taken < stack.count) stack.copyWithCount(stack.count - taken) else ItemStack.EMPTY
                if (!rest.isEmpty && job != null) rest = depositInReach(job, rest)
                if (!rest.isEmpty) (left ?: ArrayList<ItemStack>().also { left = it }).add(rest)
            }
            be.handsChanged()
            return left ?: emptyList()
        }

        override fun reaches(packedPos: Long): Boolean = this@ClawhodWorker.reaches(packedPos)

        override fun skipsFar(packedPos: Long): Boolean = farGivenUp.contains(packedPos)

        override fun canEverHold(block: Block): Boolean = handRoom(EMPTY_HAND, block) > 0

        override fun keepsCarrierFree(packedPos: Long): Boolean = this@ClawhodWorker.keepsCarrierFree(packedPos)

        override fun minedElsewhere(packedPos: Long): Boolean = this@ClawhodWorker.minedElsewhere(packedPos)

        val refusal = FetchRefusal()

        override fun refuses(item: Item): Boolean =
            refusal.refuses(item, supplyEpochNow, heldBlock(), heldCount())

        override fun tool(): ItemStack = workSlot().let { if (it < 0) ItemStack.EMPTY else slots[it] }

        /** Build-job tool passes, as in mining. */
        val passes = ToolPasses()

        override fun takesCut(state: BlockState): Boolean {
            val tool = tool()
            if (tool.isEmpty) return true
            // A rest pass with no tool for the block leaves it to bare hands (the user's fallback).
            if (passes.restPass) return restCuts(tool, state)
            return passes.takes(tool, state)
        }

        /** True when this hand will cut [state] now or in a later pass of its tool. */
        fun willCut(state: BlockState): Boolean {
            val tool = tool()
            return !tool.isEmpty && (!passes.restPass || restCuts(tool, state))
        }

        override fun matchHolderTakes(packedPos: Long, state: BlockState): Boolean {
            for (peer in toolPeers) if (peer.matchToolCuts(packedPos, state)) return true
            return false
        }

        override fun toolHolderTakes(packedPos: Long, state: BlockState): Boolean {
            for (h in hands) if (h !== this && h.willCut(state)) return this@ClawhodWorker.reaches(packedPos)
            for (peer in toolPeers) if (peer.toolHandWillCut(packedPos, state)) return true
            return false
        }

        // One tool per Clawhod, in one hand: the user's rule.
        override fun mayFetchTool(): Boolean =
            tool().isEmpty && slots.any { it.isEmpty } && hands.none { it !== this && (!it.tool().isEmpty || it.fetchesTool()) }

        fun fetchesTool(): Boolean = stage == Stage.FETCH && fetchItem?.let(::isToolItem) == true



        /** Redstone mining: the block this hand mines next, its task for the punch, and its state when picked. */
        var mineTask: BuildTask? = null

        /** A relay link moves this hand: the job's picks leave it alone. */
        var relaying = false

        /** The inventory this hand holds open, or null. */
        var openAt: BlockPos? = null

        /** The loose item this hand flies to take; it is the hand's claim, so no other hand chases it. */
        var looseItem: ItemEntity? = null

        /** What an idle hand holds came from loose items, so it goes to a chest. */
        var looseLoad = false

        /** Slots that hold a tool taken loose: carried to a chest, never used (the user's rule). */
        private val carried = BooleanArray(HAND_SLOTS)

        fun setCarried(i: Int, on: Boolean) {
            carried[i] = on
        }

        /** Clears the marks of slots that no longer hold a tool, so a tool put there later is a working tool. */
        fun syncCarried() {
            val s = slots
            for (i in carried.indices) if (carried[i] && !isClawhodTool(s[i])) carried[i] = false
        }

        /** The slot of the working tool, or -1. */
        fun workSlot(): Int {
            val s = slots
            for (i in s.indices) if (isClawhodTool(s[i]) && !carried[i]) return i
            return -1
        }

        /** Slot [i] holds something to unload: an item, or a carried tool. */
        fun isCargo(i: Int): Boolean {
            val stack = slots[i]
            return !stack.isEmpty && (!isClawhodTool(stack) || carried[i])
        }

        fun hasCargo(): Boolean {
            for (i in 0 until HAND_SLOTS) if (isCargo(i)) return true
            return false
        }

        fun firstCargo(): ItemStack? {
            for (i in 0 until HAND_SLOTS) if (isCargo(i)) return slots[i]
            return null
        }

        override fun wearTool(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean {
            val i = workSlot()
            if (i < 0) return false
            val broke = wearToolOnce(level, pos, state, slots[i])
            if (broke) slots[i] = ItemStack.EMPTY
            be.handsChanged()
            return broke
        }

        /** This hand already filled up in the current stock-up. */
        var stocked = false

        /** What the hand holds for a far cell, and the carrier move count when it began to hold it. */
        var holdBlock: Block? = null
        var holdCount = 0
        var holdMoves = 0

        /** The block item this hand last put away for a SWAP, the supply epoch then, and whether it placed since. */
        var swappedAway: Item? = null
        var swapEpoch = -1
        var placedSinceSwap = false

        /** The job's supply epoch when this hand began its rest pass. */
        var restEpoch = -1
    }

    /** Puts [stack] in the supplies in reach; returns what is left. */
    // The user's rule: like with like, so supplies that already hold the item come first.
    private fun depositInReach(job: BuildJob, stack: ItemStack): ItemStack {
        var rest = stack
        val actor = job.actor(level)
        for (pass in 0..1) {
            for (pos in job.supplies) {
                if (rest.isEmpty) return rest
                if (!level.isLoaded(pos) || !reaches(pos.asLong()) || !mayEdit(level, actor, pos)) continue
                val container = HopperBlockEntity.getContainerAt(level, pos) ?: continue
                if (pass == 0 && !container.holdsSame(rest)) continue
                rest = HopperBlockEntity.addItem(null, container, rest, null)
            }
        }
        return rest
    }

    companion object {
        private const val NBT_JOB = "EkJob"
        private const val NBT_CARRIED = "EkCarriedTools"
        private const val NO_TICK = Long.MIN_VALUE
        private const val NO_SHIP = Long.MIN_VALUE
        private const val TRIED_MAX = 8
        /** Carrier trips toward one far cell before the hands go on to later cells. */
        private const val FAR_TRIPS_MAX = 3
        private const val FAR_GIVEN_UP_MAX = 64
        /** Spots the carrier failed to reach stay skipped this long (about 30 s) from the failure, at most this many. */
        private const val FAILED_SPOTS = 16
        private const val FAILED_SPOT_TICKS = 600L
        private const val IDLE_RETRY_TICKS = 20L
        private const val HOLD_MOVES = 3
        private const val MINE_RADIUS = 8
        /** A scaled mining radius stops at the same bound as the scaled spot rings. */
        private const val MINE_RADIUS_CAP = MINE_RADIUS * WORK_SPOT_MAX_RINGS / WORK_SPOT_RINGS
        private const val MINE_SCAN_PER_TICK = 256
        private const val LOOSE_SCAN_TICKS = 10L
        private const val LOOSE_MAX = 32
        /** Vanilla's pickup delay of a thrown item. */
        private const val LOOSE_FRESH_TICKS = 40
        private const val SHIMMER_COUNT = 2
        private const val SHIMMER_LIFT = 0.125
        private const val SHIMMER_SPREAD = 0.1
        private const val SHIMMER_SPEED = 0.3
        private const val FRAME_WORLD = 1
        private const val FRAME_SHIP = 2
        /** Chain links come from this far round the Clawhod that asks (blocks), at most this many. */
        private const val CHAIN_RANGE = 64.0
        private const val MAX_RELAY_NODES = 32
        private const val MAX_CHAIN_CHESTS = 16
        /** Vanilla's ChestLidController and shulker box open at 0.1 per tick: the lid is up after this many ticks. */
        private const val LID_OPEN_TICKS = 10L
        private const val NO_SIG = Long.MIN_VALUE
        private const val SIG_PRIME = 31L
        private const val MOVE_FAIL_CHECKS = 2
        /** Move checks (one per [IDLE_RETRY_TICKS]) with the carrier in place before its move counts as failed. */
        private const val MOVE_STILL_CHECKS = 8
        private const val MOVE_STILL_SQ = 0.05 * 0.05
        /** Walls-in floods per tick beyond the cached one, and the bases one flood may search. */
        private const val FLOODS_PER_TICK = 2
        private const val FLOOD_CAP = 1024
        /** The route flood may go this far (blocks) past the walker and the outer spot ring, for a route round a wall. */
        private const val ROUTE_PAD = 2
        private const val NO_LAYER = Int.MIN_VALUE
        /** A body block must fill a cell this deep (blocks) on every axis to block it: the plate rides 0.25 into
         *  the ground row, and a placed block there only nudges it. */
        private const val BODY_DIP = 0.3
        private const val HALF = 0.5
        /** How far each way (blocks) a walled-in carrier looks for a floor out of the plan. */
        private const val TRAPPED_LOOK = 8
        /** The floor band round a cell's height follows the scaled reach, up to this many blocks each way. */
        private const val MAX_FLOOR_BAND = 24
        /** Two floors and one wall spot per spot column. */
        private const val SPOTS_PER_COLUMN = 4
        private val ALL_FACES: Array<Direction> = Direction.values()
        private val HORIZONTAL_FACES = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
        private val CEILING_FACE = Direction.UP.get3DDataValue()
        /** A scaled body layer may span at most this many cells on an axis; past it the room counts as blocked. */
        private const val MAX_BODY_SPAN = 32
        private const val SCALE_EPS = 1.0e-6
        /** Offset values per ring of [WORK_SPOT_OFFSETS]: eight floors, x and z each. */
        private const val WORK_SPOT_RING_VALUES = 16
        private const val BREAKER_IDS = 1 shl 20
        // Vanilla MultiPlayerGameMode.continueDestroyBlock hit sound.
        private const val HIT_VOLUME_DIVISOR = 8f
        private const val HIT_PITCH = 0.5f
        private val EMPTY_HAND: List<ItemStack> = List(HAND_SLOTS) { ItemStack.EMPTY }

        /** True when [tag] holds worker state: a client update tag does not. */
        fun hasState(tag: CompoundTag): Boolean = tag.hasUUID(NBT_JOB) || tag.contains(NBT_CARRIED)
    }
}
