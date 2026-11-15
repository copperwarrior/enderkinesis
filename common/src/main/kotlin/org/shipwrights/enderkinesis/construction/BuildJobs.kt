package org.shipwrights.enderkinesis.construction

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.blockentity.DraftingTableBlockEntity
import java.util.UUID

sealed interface StartResult {
    /** [joined]: the table already runs a job with the same plan and anchor; it took the supplies near the table now. */
    data class Started(val jobId: UUID, val cellCount: Long, val joined: Boolean) : StartResult
    data class Refused(val reason: RefuseReason) : StartResult
}

enum class RefuseReason {
    NO_PLAN, EMPTY_PLAN, WRONG_DIMENSION,
    NO_SUPPLY, NO_PERMISSION,
}

fun StartResult.message(): Component = when (this) {
    is StartResult.Started -> Component.translatable(
        if (joined) "message.enderkinesis.build.joined" else "message.enderkinesis.build.started",
        cellCount,
    )
    is StartResult.Refused -> Component.translatable("message.enderkinesis.build.refused.${reason.name.lowercase()}")
}

/** Per-level build jobs. Server thread only. */
class BuildJobs : SavedData() {

    private val jobs = LinkedHashMap<UUID, BuildJob>()

    /** The loaded Clawhods, for item chains; not saved. */
    internal val relays = ClawhodRelays()

    /** Every job's [BuildJob.watch], so the container-change hook tests one set. */
    private val watched = LongOpenHashSet()
    private var watchStale = true

    fun job(id: UUID): BuildJob? = jobs[id]

    private val jobsNow = ArrayList<BuildJob>()
    private var jobsNowAt = Long.MIN_VALUE

    /**
     * True when a job's plan wants a block at world ([x], [y], [z]) that is not built yet; a ship job's plan is read
     * through its ship. Jobs are listed once per tick.
     */
    internal fun unbuiltPlanCell(level: ServerLevel, x: Int, y: Int, z: Int): Boolean {
        listJobs(level)
        for (i in jobsNow.indices) {
            val job = jobsNow[i]
            val p = job.toPlanFrame(level, x, y, z)
            if (job.unbuiltAt(level, BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p))) return true
        }
        return false
    }

    /** True when world block ([x], [y], [z]) is in, or just over, the body room claimed for a walker other than [walker]. */
    internal fun claimedBodyAt(level: ServerLevel, x: Int, y: Int, z: Int, walker: UUID?): Boolean {
        listJobs(level)
        for (i in jobsNow.indices) if (jobsNow[i].claimedBodyAt(level, x, y, z, walker)) return true
        return false
    }

    private fun listJobs(level: ServerLevel) {
        if (jobsNowAt == level.gameTime) return
        jobsNow.clear()
        jobsNow.addAll(jobs.values)
        jobsNowAt = level.gameTime
    }

    internal val allJobs: Collection<BuildJob> get() = jobs.values

    fun jobsOf(owner: UUID): List<BuildJob> = jobs.values.filter { it.owner == owner }

    fun cancel(level: ServerLevel, id: UUID): Boolean {
        val job = jobs.remove(id) ?: return false
        BuildLog.job(job, JobWhy.JOB_CANCELLED, "cancelled by an order; ${job.summary()}")
        dropped(level, job)
        return true
    }

    /** Clawhod cell (packed) to the [DraftingTableBlockEntity.tableId] it works for. A Clawhod works for one table. */
    private val crews = Long2ObjectOpenHashMap<UUID>()

    /** Table id to the owner of a job whose table block left; the job starts again where the table loads. Not saved. */
    private val detached = HashMap<UUID, UUID>()

    /** The job of the drafting table at [table]; one table, one job. */
    fun jobAt(table: BlockPos): BuildJob? = jobs.values.firstOrNull { it.table == table }

    internal fun assignCrew(clawhod: BlockPos, tableId: UUID) {
        if (crews.put(clawhod.asLong(), tableId) != tableId) setDirty()
    }

    internal fun dropCrew(clawhod: BlockPos) {
        if (crews.containsKey(clawhod.asLong())) {
            crews.remove(clawhod.asLong())
            setDirty()
        }
    }

    /** The Clawhod cells assigned to the table [tableId]. Bounded by the Clawhods of this level. */
    internal fun crewOf(tableId: UUID): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        val cursor = crews.long2ObjectEntrySet().iterator()
        while (cursor.hasNext()) {
            val e = cursor.next()
            if (e.value == tableId) out.add(BlockPos.of(e.longKey))
        }
        return out
    }

    private fun end(level: ServerLevel, job: BuildJob, why: String) {
        jobs.remove(job.id) ?: return
        BuildLog.job(job, JobWhy.JOB_ENDED, "$why; ${job.summary()}")
        dropped(level, job)
    }

    /** After [job] left [jobs]. */
    private fun dropped(level: ServerLevel, job: BuildJob) {
        setDirty()
        watchStale = true
        level.server.playerList.getPlayer(job.owner)?.let { BuildProgressNetwork.sendEnd(it, job.id) }
    }

    @Suppress("ReturnCount") // One early return per refusal reason reads clearer than a nested chain.
    private fun startJob(
        level: ServerLevel,
        owner: ServerPlayer,
        table: BlockPos,
        tableId: UUID,
        ref: PlanRef,
        anchor: PlanAnchor,
        supplies: List<BlockPos>,
    ): StartResult {
        refusal(level, owner, anchor, supplies)?.let { return StartResult.Refused(it) }
        // The stored ref carries the checked size, so the job's grid matches the plan file.
        val stored = PlanStorage.find(level.server, ref.owner, ref.hash) ?: return StartResult.Refused(RefuseReason.NO_PLAN)
        val plan = buildPlan(level.server, stored) ?: return StartResult.Refused(RefuseReason.NO_PLAN)
        val cellCount = plan.nonAir
        if (cellCount == 0L) return StartResult.Refused(RefuseReason.EMPTY_PLAN)

        val existing = jobAt(table)
        val same = existing != null && existing.ref.key == stored.key && existing.anchor == anchor
        val job = if (same) checkNotNull(existing) else openJob(level, owner.uuid, table, tableId, stored, anchor, supplies)
        if (same) relink(level, job, supplies)
        return StartResult.Started(job.id, cellCount, same)
    }

    /** A new job for the table at [table], in place of any job that table runs now. No permission check. */
    @Suppress("LongParameterList") // The job's fixed inputs.
    internal fun openJob(
        level: ServerLevel, owner: UUID, table: BlockPos, tableId: UUID, ref: PlanRef, anchor: PlanAnchor, supplies: List<BlockPos>,
    ): BuildJob {
        jobAt(table)?.let { end(level, it, "the table started another plan or placement") }
        detached.remove(tableId)
        val job = BuildJob(UUID.randomUUID(), owner, table.immutable(), tableId, ref, anchor, ArrayList())
        jobs[job.id] = job
        relink(level, job, supplies)
        return job
    }

    private fun relink(level: ServerLevel, job: BuildJob, supplies: List<BlockPos>) {
        job.pruneSupplies(level)
        val linked = supplies.filterNot { isStaleSupply(level, it) }.map { it.immutable() }
        job.relinkSupplies(linked, buildTuning().maxLinkedSupplies)
        job.fullSyncPending = true
        setDirty()
        // A started job always needs a watch, so the changed flags add nothing here.
        watchStale = true
    }

    private fun refusal(
        level: ServerLevel,
        owner: ServerPlayer,
        anchor: PlanAnchor,
        supplies: List<BlockPos>,
    ): RefuseReason? {
        return when {
            anchor.dimension != level.dimension() -> RefuseReason.WRONG_DIMENSION
            supplies.isEmpty() -> RefuseReason.NO_SUPPLY
            !mayEdit(level, owner, anchor.pos) -> RefuseReason.NO_PERMISSION
            else -> null
        }
    }

    private fun tick(level: ServerLevel) {
        if (watchStale) rewatch(level)
        if (jobs.isEmpty()) return
        val tuning = buildTuning()
        val it = jobs.values.iterator()
        while (it.hasNext()) {
            val job = it.next()
            if (!job.prepare(level, tuning.orderBudget)) {
                it.remove()
                setDirty()
                watchStale = true
                level.server.playerList.getPlayer(job.owner)?.let { p -> BuildProgressNetwork.sendEnd(p, job.id) }
                continue
            }
            if (job.saveDirty) {
                job.saveDirty = false
                setDirty()
            }
            if (!job.isReady) continue
            if (level.gameTime % PRUNE_INTERVAL_TICKS == 0L) {
                // A ship deletion or a move removes the table without a block update at its cell.
                if (level.isLoaded(job.table) && level.getBlockEntity(job.table) !is DraftingTableBlockEntity) {
                    it.remove()
                    BuildLog.job(job, JobWhy.JOB_ENDED, "its drafting table is gone; ${job.summary()}")
                    dropped(level, job)
                    continue
                }
                if (job.pruneSupplies(level)) {
                    setDirty()
                    watchStale = true
                }
            }
            if (Math.floorMod(level.gameTime + job.id.hashCode(), SUPPLY_SCAN_TICKS) == 0L && level.isLoaded(job.table)) {
                if (job.relinkSupplies(level.suppliesNear(job.table, tuning.maxLinkedSupplies), tuning.maxLinkedSupplies)) {
                    setDirty()
                    watchStale = true
                }
            }
            job.picksLeft = tuning.picksPerTick
            val owner = level.server.playerList.getPlayer(job.owner)
            noteTick(job, owner)
            sync(owner, job)
            if (job.isFinished) {
                BuildLog.job(job, JobWhy.JOB_FINISHED, "every section is complete")
                it.remove()
                setDirty()
                watchStale = true
                owner?.let { p ->
                    BuildProgressNetwork.sendEnd(p, job.id)
                    p.displayClientMessage(Component.translatable("message.enderkinesis.build.finished", job.ref.name), true)
                }
            }
        }
    }

    private fun noteTick(job: BuildJob, owner: ServerPlayer?) {
        if (owner != null) {
            job.tickNote.clear()
        } else if (job.tickNote.changed(JobWhy.OWNER_OFFLINE)) {
            BuildLog.job(job, JobWhy.OWNER_OFFLINE, "owner offline; checks run for a fake player of the owner ${job.summary()}")
        }
    }

    // A double chest is one container, so a change in either half counts.
    private fun rewatch(level: ServerLevel) {
        val before = watched.size
        watched.clear()
        for (job in jobs.values) {
            job.watch.clear()
            for (pos in job.supplies) {
                job.watch.add(pos.asLong())
                if (!level.isLoaded(pos)) continue
                val state = level.getBlockState(pos)
                if (state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    job.watch.add(pos.relative(ChestBlock.getConnectedDirection(state)).asLong())
                }
            }
            watched.addAll(job.watch)
        }
        watchedSupplyCount.addAndGet(watched.size - before)
        watchStale = false
    }

    /** A container block entity at [pos] changed. Cheap when [pos] is no linked supply. */
    internal fun supplyChanged(pos: BlockPos) {
        val key = pos.asLong()
        if (mineRefused.remove(key)) {
            watchedSupplyCount.decrementAndGet()
            mineChestEpoch++
        }
        if (watched.isEmpty() || !watched.contains(key)) return
        for (job in jobs.values) if (job.watch.contains(key)) job.wake()
    }

    /** Chests where a redstone miner's tool swap failed, watched until their first change; not saved. */
    private val mineRefused = LongOpenHashSet()

    /** Goes up when a chest in [mineRefused] changes, so miners try their refused chests again at once. */
    internal var mineChestEpoch = 0
        private set

    internal fun watchRefusedChest(pos: BlockPos) {
        if (mineRefused.add(pos.asLong())) watchedSupplyCount.incrementAndGet()
    }

    private fun sync(owner: ServerPlayer?, job: BuildJob) {
        if (owner == null) job.sync.drop(job) else BuildProgressNetwork.flush(owner, job)
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for (job in jobs.values) list.add(job.save())
        tag.put(JOBS, list)
        val crewList = ListTag()
        val cursor = crews.long2ObjectEntrySet().iterator()
        while (cursor.hasNext()) {
            val e = cursor.next()
            crewList.add(CompoundTag().also { c ->
                c.putLong(CELL, e.longKey)
                c.putUUID(TABLE, e.value)
            })
        }
        tag.put(CREWS, crewList)
        return tag
    }

    companion object {
        private const val NAME = "enderkinesis_build_jobs"
        private const val JOBS = "Jobs"
        private const val CREWS = "Crews"
        private const val CELL = "Cell"
        private const val TABLE = "Table"
        private const val PRUNE_INTERVAL_TICKS = 20L
        /** The supply search near each table runs on this cadence, spread over jobs by id. */
        private const val SUPPLY_SCAN_TICKS = 100L

        fun get(level: ServerLevel): BuildJobs = level.dataStorage.computeIfAbsent(::load, ::BuildJobs, NAME)

        fun start(
            level: ServerLevel,
            owner: ServerPlayer,
            table: BlockPos,
            tableId: UUID,
            ref: PlanRef,
            anchor: PlanAnchor,
            supplies: List<BlockPos>,
        ): StartResult = get(level).startJob(level, owner, table, tableId, ref, anchor, supplies)

        /** Stop: cancels the job of the table at [table]. False when it had none. */
        fun cancelTable(level: ServerLevel, table: BlockPos): Boolean {
            val data = get(level)
            val job = data.jobAt(table) ?: return false
            return data.cancel(level, job.id)
        }

        /**
         * The table block left [table]. Its job ends, as its plan cells may move with it; the Clawhods stay assigned.
         * When the same table loads again elsewhere (a VS2 assembly), [tableLoaded] starts the job there.
         */
        fun tableRemoved(level: ServerLevel, table: BlockPos, tableId: UUID, moving: Boolean) {
            val data = get(level)
            val job = data.jobAt(table)?.takeIf { it.tableId == tableId }
            job?.let { data.end(level, it, if (moving) "its drafting table moves with a ship" else "its drafting table was broken") }
            if (moving) {
                job?.let { data.detached[tableId] = it.owner }
                return
            }
            // A real break: nothing will load this table again, so its id and Clawhod assignments go.
            data.detached.remove(tableId)
            val cursor = data.crews.long2ObjectEntrySet().iterator()
            while (cursor.hasNext()) {
                if (cursor.next().value != tableId) continue
                cursor.remove()
                data.setDirty()
            }
        }

        /**
         * The table [entity] loaded at [table]. Only a table that left by a move (see [tableRemoved]) starts its job
         * again, here, with the same owner and Clawhods. A running job never moves to another cell, so a copy or a
         * table whose own chunk is unloaded cannot take it.
         */
        fun tableLoaded(level: ServerLevel, table: BlockPos, entity: DraftingTableBlockEntity) {
            val data = get(level)
            if (data.detached.isEmpty()) return
            val id = entity.tableId
            if (data.jobs.values.any { it.tableId == id }) return
            val owner = data.detached.remove(id) ?: return
            level.restartTable(owner, table, entity)
        }

        fun init() {
            LifecycleEvent.SERVER_STOPPED.register { _ -> watchedSupplyCount.set(0) }
            TickEvent.SERVER_LEVEL_POST.register(TickEvent.ServerLevelTick { get(it).tick(it) })
            PlayerEvent.PLAYER_JOIN.register { player ->
                for (level in player.server.allLevels) {
                    for (job in get(level).jobsOf(player.uuid)) job.fullSyncPending = true
                }
            }
        }

        private fun load(tag: CompoundTag): BuildJobs {
            val data = BuildJobs()
            val list = tag.getList(JOBS, Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val job = BuildJob.load(list.getCompound(i)) ?: continue
                data.jobs[job.id] = job
            }
            val crews = tag.getList(CREWS, Tag.TAG_COMPOUND.toInt())
            for (i in 0 until crews.size) {
                val c = crews.getCompound(i)
                if (c.hasUUID(TABLE)) data.crews.put(c.getLong(CELL), c.getUUID(TABLE))
            }
            return data
        }
    }
}
