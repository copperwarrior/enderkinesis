package org.shipwrights.enderkinesis.spell

import com.mojang.logging.LogUtils // TEMP gas debug, remove after the gas run
import dev.architectury.event.events.common.ChunkEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBd
import org.joml.primitives.AABBic
import org.shipwrights.enderkinesis.util.containsWorldPoint
import org.shipwrights.enderkinesis.util.standaloneBodies
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.bodies.ServerBaseVsBody
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.impl.api.ServerShipInternal
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.core.api.util.AerodynamicUtils
import org.valkyrienskies.core.api.util.GameTickOnly
import java.util.concurrent.ConcurrentLinkedQueue
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.mod.common.toWorldCoordinates
import org.valkyrienskies.mod.common.transformAabbToWorld
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.physics.GasLift
import org.shipwrights.enderkinesis.physics.LatticeHullVolume

/** Gas is never in solid blocks or water. It does not fade with time: it only spreads, leaves and is displaced. */
object GasField {
    const val PULSE_ADD = 0.05f
    /** Most gas in one cell, all kinds together. Above 1 the cell is overpressured and pushes gas out. */
    const val MAX_AMOUNT = 2f
    const val ONCE_FILL_PER_TICK = 1f / 1200f
    private const val EXPOSE_EVERY = 10
    private const val CHECK_EVERY = 20
    private const val RESYNC_EVERY = 40
    private const val EXPOSE_MIN = 0.05f
    // Refreshed each exposure pass, so it ends soon after the entity leaves the gas.
    private const val SICKNESS_TICKS = 40
    private const val POISON_TICKS = 40
    private const val POISON_TICKS_PER_GAS = 80
    private const val POISON_STRONG = 0.7f
    // Gas above 1 counts this many times in poison and sickness.
    private const val EXCESS_WEIGHT = 2f
    private const val DOME_HEIGHT = 0.7
    private const val SPREAD_PER_CHUNK = 6
    private const val SPREAD_SHARE = 0.5f
    // Share of the gap to a neighbour that an overpressured cell moves in a tick, scaled by the excess over 1.
    // With the spread share of 0.5, a cell at 2 moves 2.3 times the normal rate.
    private const val PRESSURE_SHARE = 0.65f
    private const val PRESSURE_PER_TICK = 4096
    private const val THROTTLE_CELLS = 4
    private const val SETTLE_PER_CHUNK = 8
    private const val SETTLE_SHARE = 0.5f
    private const val GRAVITY = 9.81
    private const val TICKS_PER_SECOND = 20.0
    // Below this effective gravity (m/s², squared) a ship is in free fall, and its gas does not settle.
    private const val FREE_FALL_SQ = 0.01
    // Ship acceleration under 0.5 m/s² (squared here) counts as zero.
    private const val ACCEL_DEADBAND_SQ = 0.25
    // Share of the gap to the gas centre that the lift centre moves each tick: a 2 s time constant (1 - e^(-1/40)).
    private const val LIFT_CENTRE_EASE = 0.025
    private const val SPREAD_MIN = 0.0005f
    private const val DUST = 1.0e-6f
    private const val ROOF_SCAN = 64
    private const val CACHED_OPEN: Byte = 1
    private const val CACHED_CLOSED: Byte = 2
    private const val SKY_UNSCANNED = Int.MAX_VALUE
    // Spare flow arrays kept for reuse; more are dropped to the GC.
    private const val FLOW_POOL_CAP = 4096
    private const val SPILL_CELLS = 1024
    private const val SPRAY_BLOW = 0.6f
    private const val AXIS_TUBE_SQ = 0.75
    // A gas vent pulls from a half-sphere behind its input cell and pushes a short jet from its output cell.
    private const val VENT_PULL_RADIUS = 3
    private const val VENT_PULL_SPEED = 0.25f
    private const val VENT_PUSH_SPEED = 0.35f
    private const val VENT_FLOW_BLEND = 0.5f
    // The most gas cells that one blowCone call visits for each kind, so a big cloud cannot stall a tick.
    private const val BLOW_SCAN_CAP = 8192
    private const val BLOW_BLEND = 0.5f
    // Newtons for one unit of (gas amount moved out in a tick) x (push speed). A full-signal vent fed at the rate of a held
    // Hrass Sack (about 0.02 a tick) gives 5.7e6 * 0.02 * 0.35, which is 40 kN.
    private const val VENT_THRUST_PER_FLOW = 5.7e6
    // A transfer gives its target cell a flow along the move, so the client can slide the gas. Small moves give none.
    private const val NOTE_MIN_FRACTION = 0.05f
    private const val NOTE_BLEND = 0.5f
    private const val SPRAY_BLOW_SHARE = 0.5f
    const val AREA_BLOW = 0.5f
    // Too near the middle of a burst to know which way is out.
    private const val BURST_STILL = 0.3
    // The client runs the same drag and STILL removal on the flows it receives.
    const val STILL = 0.02f
    const val FLOW_MAX = 1.0f
    // A synced flow changes only when it leaves the client's prediction by more than this many int8 steps.
    private const val FLOW_RESEND_STEPS = 2
    // When full, gas moves only into blocks that hold some. Many Sprays held for minutes stay far under it.
    private const val MAX_CELLS = 1 shl 17
    // Cached arrays, not entries: a for loop over an array allocates no iterator, and these loops are hot.
    private val FACES: Array<Direction> = Direction.values()
    private val KINDS: Array<GasKind> = GasKind.values()
    // Cells that a hull moved into, cleared in each chunk each tick at most.
    private const val DISPLACE_PER_CHUNK = 256
    // (1 mm/s)² and (1 mrad/s)²: VS2 gives a resting body a speed near zero, not exactly zero.
    private const val BODY_REST_SPEED_SQ = 1.0e-6

    private class ShipFall {
        val lastVelocity = Vector3d()
        var tick = Long.MIN_VALUE
        /** Unit effective gravity in ship axes: world gravity minus the ship's acceleration. Zero in free fall. */
        val down = Vector3d()
    }

    private class LevelGas(val kind: GasKind, val dimension: String) {
        val chunks = Long2ObjectOpenHashMap<Int2FloatOpenHashMap>()
        // Velocity in blocks a tick, by chunk and block.
        val flows = Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<FloatArray>>()
        val dirty = LongOpenHashSet()
        val sentWhole = LongOpenHashSet()
        val changed = Long2ObjectOpenHashMap<Int2FloatOpenHashMap>()
        // The packed amount that the watching clients hold for each cell; no entry is no gas.
        val sentPacked = Long2ObjectOpenHashMap<Int2ByteOpenHashMap>()
        // Player entity ids that got each chunk whole; a new id gets a whole send at the next resync.
        val watchers = Long2ObjectOpenHashMap<IntOpenHashSet>()
        val spareChanged = ArrayList<Int2FloatOpenHashMap>()
        // What each client holds for each flow: the last sent value, dragged each tick as the client drags it.
        val predicted = Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<FloatArray>>()
        // Packed int8 flows to send this tick; 0 removes the flow.
        val flowChanged = Long2ObjectOpenHashMap<Int2IntOpenHashMap>()
        val flowKeys = LongOpenHashSet()
        var count = 0
        // Chunks of persistent gas that are loading: key -> game time when to give up. They thaw when the chunk is ready.
        val awaiting = Long2LongOpenHashMap()
        // TEMP gas debug, remove after the gas run
        var dbgWholeChunks = 0; var dbgWholeCells = 0; var dbgDeltaChunks = 0; var dbgDeltaCells = 0; var dbgFlowEntries = 0

        val chunkKeys = LongArrayList()
        val indices = IntArrayList()
        val movingKeys = LongArrayList()
        val movingCells = IntArrayList()
        val movingFlows = ArrayList<FloatArray>()
        val openFaces = IntArray(FACES.size)
        // Cells (by chunk) that hold some of this kind where all kinds together exceed 1. Cleaned when they relax.
        val pressured = Long2ObjectOpenHashMap<IntOpenHashSet>()
        val pressureKeys = LongArrayList()
        val pressureCells = IntArrayList()
        val faceTotals = FloatArray(FACES.size)
        val faceMoves = FloatArray(FACES.size)
        val at = BlockPos.MutableBlockPos()
        val near = BlockPos.MutableBlockPos()
        val cell = BlockPos.MutableBlockPos()
        val shipCell = BlockPos.MutableBlockPos()
        val shipPoint = Vector3d()
        // Fetched once for each world chunk while it is used, never for each cell.
        val shipsByChunk = Long2ObjectOpenHashMap<List<Ship>>()
        val bodiesByChunk = Long2ObjectOpenHashMap<List<ServerBaseVsBody>>()
        val bodyPoint = Vector3d()
        val ventWay = Vector3d()
        val hullVelocity = Vector3d()
        val faceDots = DoubleArray(FACES.size)
        val faceUsed = BooleanArray(FACES.size)
        // Ship id -> its settle direction this tick; ships not seen in a tick are dropped.
        val falls = Long2ObjectOpenHashMap<ShipFall>()
        // Ship id -> the eased lift centre (shipyard x, y, z).
        val liftCentres = Long2ObjectOpenHashMap<DoubleArray>()
        val flowScratch = Vector3d()
        val movedFlow = FloatArray(3)
        // Entity id -> ticks in this gas without a break; ids not in the gas at a pass are dropped.
        val exposure = Int2IntOpenHashMap()
        val exposedNow = IntOpenHashSet()
        // Ship id -> amount, then the amount-weighted x, y and z of its cells, then the lift weight.
        val liftSums = Long2ObjectOpenHashMap<DoubleArray>()
        // Chunks where displace stopped at its cap, so gas can still be inside a hull that is now still.
        val displaceBacklog = LongOpenHashSet()
        // Shipyard chunks whose ship is still loading this tick; their gas does not move.
        val loading = LongOpenHashSet()
        // Valid only while [caching]: no block changes between the start and the end of the gas passes.
        var caching = false
        val openCache = Long2ByteOpenHashMap()
        // World column -> highest blocking cell in the ROOF_SCAN cells under the heightmap top.
        val skyBlock = Long2IntOpenHashMap().apply { defaultReturnValue(SKY_UNSCANNED) }
        // Removed flow arrays wait one tick before reuse, so no pass still holds them.
        val flowPool = ArrayList<FloatArray>()
        val releasedFlows = ArrayList<FloatArray>()
        val predictedPool = ArrayList<FloatArray>()
        val spareFlowChanged = ArrayList<Int2IntOpenHashMap>()
        val liftKey = "$dimension/${kind.name}"

        fun changedIn(key: Long): Int2FloatOpenHashMap =
            changed.get(key) ?: (spareChanged.removeLastOrNull() ?: Int2FloatOpenHashMap()).also { changed.put(key, it) }

        fun hasRoom(there: Float): Boolean = there > 0f || count < MAX_CELLS
    }

    // Game thread only. Gas that moveAll moved since the last passThrough started.
    private var ventMoved = 0f

    // Game thread only. Scratch for passThrough, by kind ordinal.
    private val ventTaken = FloatArray(KINDS.size)

    // Game thread only. One map for each kind, by ordinal.
    // TEMP gas debug, remove after the gas run
    private val dbgLog = LogUtils.getLogger()

    // TEMP gas debug, remove after the gas run
    private fun dbgReport(level: ServerLevel, gas: LevelGas) {
        var cells = 0; var total = 0.0; var strong = 0
        for (m in gas.chunks.values) {
            cells += m.size
            val it = m.values.iterator()
            while (it.hasNext()) { val a = it.nextFloat(); total += a; if (a >= EXPOSE_MIN) strong++ }
        }
        dbgLog.info(
            "TEMP gas debug server dim={} kind={} cells={} count={} chunks={} totalAmount={} cellsAtOrAbove0.05={} sentWholeChunks={} sentWholeCells={} sentDeltaChunks={} sentDeltaCells={} sentFlowEntries={}",
            gas.dimension, gas.kind.name, cells, gas.count, gas.chunks.size, total, strong,
            gas.dbgWholeChunks, gas.dbgWholeCells, gas.dbgDeltaChunks, gas.dbgDeltaCells, gas.dbgFlowEntries,
        )
        gas.dbgWholeChunks = 0; gas.dbgWholeCells = 0; gas.dbgDeltaChunks = 0; gas.dbgDeltaCells = 0; gas.dbgFlowEntries = 0
    }

    private val levels = KINDS.map { WeakHashMap<ServerLevel, LevelGas>() }

    private fun gasOf(level: ServerLevel, kind: GasKind): LevelGas = levels[kind.ordinal].getOrPut(level) { LevelGas(kind, level.dimensionId) }

    private fun gasIn(level: ServerLevel, kind: GasKind): LevelGas? = levels[kind.ordinal][level]

    // The unloaded chunks of every kind are frozen in a GasStore, so they keep their gas as it was.
    private const val AWAIT_TICKS = 1200L
    private const val MAX_THAW_PER_TICK = 4096

    private class ThawRequest(val level: ServerLevel, val key: Long)

    // Chunk data can load on a worker thread (C2ME), so the load event only queues the key for the game thread.
    private val thawQueue = ConcurrentLinkedQueue<ThawRequest>()

    private fun requestThaws() {
        for (n in 0 until MAX_THAW_PER_TICK) {
            val r = thawQueue.poll() ?: return
            val store = r.level.gasStore()
            for (kind in KINDS) if (store.frozen(kind).containsKey(r.key)) gasOf(r.level, kind).awaiting.put(r.key, r.level.gameTime + AWAIT_TICKS)
        }
    }

    private fun thawReady(level: ServerLevel, gas: LevelGas) {
        val it = gas.awaiting.long2LongEntrySet().fastIterator()
        while (it.hasNext()) {
            val e = it.next()
            val key = e.longKey
            if (level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                thaw(level, gas, key)
                it.remove()
            } else if (level.gameTime > e.longValue) it.remove()
        }
    }

    private fun thaw(level: ServerLevel, gas: LevelGas, key: Long) {
        val store = level.gasStore()
        val record = store.frozen(gas.kind).remove(key) ?: return
        store.setDirty()
        if (gas.chunks.containsKey(key)) return
        val cells = record.cells
        val low = level.minBuildHeight; val high = level.maxBuildHeight
        val c = cells.int2FloatEntrySet().fastIterator()
        while (c.hasNext()) if (cellY(low, c.next().intKey) !in low until high) c.remove()
        if (cells.isEmpty()) return
        gas.chunks.put(key, cells)
        gas.count += cells.size
        if (!record.flows.isEmpty()) {
            val flows = Int2ObjectOpenHashMap<FloatArray>()
            val f = record.flows.int2IntEntrySet().fastIterator()
            while (f.hasNext()) {
                val e = f.next()
                if (cells.containsKey(e.intKey)) flows.put(e.intKey, unpackFlow(e.intValue, FloatArray(3)))
            }
            if (!flows.isEmpty()) gas.flows.put(key, flows)
        }
        gas.dirty.add(key)
    }

    private fun packedFlows(flows: Int2ObjectOpenHashMap<FloatArray>?): Int2IntOpenHashMap {
        val out = Int2IntOpenHashMap()
        val it = flows?.int2ObjectEntrySet()?.fastIterator() ?: return out
        while (it.hasNext()) {
            val e = it.next()
            val packed = packFlow(e.value)
            if (packed != 0) out.put(e.intKey, packed)
        }
        return out
    }

    private fun freeze(level: ServerLevel, kind: GasKind, key: Long, cells: Int2FloatOpenHashMap, flows: Int2ObjectOpenHashMap<FloatArray>?) {
        if (cells.isEmpty()) return
        val store = level.gasStore()
        store.frozen(kind).put(key, GasChunkRecord(cells, packedFlows(flows)))
        store.setDirty()
    }

    /** The live chunks of every kind, for the save of [GasStore]. */
    fun forEachLive(level: ServerLevel, action: (GasKind, Long, Int2FloatOpenHashMap, Int2IntOpenHashMap) -> Unit) {
        for (kind in KINDS) {
            val gas = gasIn(level, kind) ?: continue
            val it = gas.chunks.long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                if (!e.value.isEmpty()) action(kind, e.longKey, e.value, packedFlows(gas.flows.get(e.longKey)))
            }
        }
    }

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register { level ->
            requestThaws()
            for (kind in KINDS) gasIn(level, kind)?.let { tick(level, it) }
        }
        ChunkEvent.LOAD_DATA.register(ChunkEvent.LoadData { chunk, level, _ ->
            if (level != null) thawQueue.add(ThawRequest(level, chunk.pos.toLong()))
        })
        LifecycleEvent.SERVER_LEVEL_SAVE.register { level -> if (KINDS.any { gasIn(level, it) != null }) level.gasStore().setDirty() }
        LifecycleEvent.SERVER_STOPPING.register {
            thawQueue.clear()
            // Loaded chunks freeze first, because the world save runs after this event.
            for (perKind in levels) for ((level, gas) in perKind) {
                val it = gas.chunks.long2ObjectEntrySet().fastIterator()
                while (it.hasNext()) {
                    val e = it.next()
                    freeze(level, gas.kind, e.longKey, e.value, gas.flows.get(e.longKey))
                }
            }
            levels.forEach { it.clear() }
            GasLift.clearSession()
        }
        GasLift.register()
    }

    fun open(level: Level, pos: BlockPos): Boolean = passes(level, pos) && level.getFluidState(pos).isEmpty

    // An open door still has a thin collision slab, but gas leaks around it.
    private fun passes(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (state.getCollisionShape(level, pos).isEmpty) return true
        return (state.block is DoorBlock || state.block is TrapDoorBlock) && state.getOptionalValue(BlockStateProperties.OPEN).orElse(false)
    }

    private fun shipsNear(level: ServerLevel, gas: LevelGas, pos: BlockPos): List<Ship> {
        val key = key(pos)
        gas.shipsByChunk.get(key)?.let { return it }
        val x = (pos.x shr 4) * 16.0
        val z = (pos.z shr 4) * 16.0
        val box = AABB(x - 1.0, level.minBuildHeight.toDouble(), z - 1.0, x + 17.0, level.maxBuildHeight.toDouble(), z + 17.0)
        return level.getShipsIntersecting(box).toList().also { gas.shipsByChunk.put(key, it) }
    }

    @OptIn(VsBeta::class)
    private fun bodiesNear(level: ServerLevel, gas: LevelGas, pos: BlockPos): List<ServerBaseVsBody> {
        val key = key(pos)
        gas.bodiesByChunk.get(key)?.let { return it }
        val x = (pos.x shr 4) * 16.0
        val z = (pos.z shr 4) * 16.0
        val box = AABBd(x - 1.0, level.minBuildHeight.toDouble(), z - 1.0, x + 17.0, level.maxBuildHeight.toDouble(), z + 17.0)
        return level.standaloneBodies(box).also { gas.bodiesByChunk.put(key, it) }
    }

    /** True when a ship block or a body fills world cell [pos]; [out] then gets the hull velocity there, in m/s. */
    @OptIn(VsBeta::class)
    private fun hullAt(level: ServerLevel, gas: LevelGas, pos: BlockPos, out: Vector3d?): Boolean {
        val px = pos.x + 0.5; val py = pos.y + 0.5; val pz = pos.z + 0.5
        for (ship in shipsNear(level, gas, pos)) {
            val wa = ship.worldAABB
            if (px < wa.minX() || px > wa.maxX() || py < wa.minY() || py > wa.maxY() || pz < wa.minZ() || pz > wa.maxZ()) continue
            ship.transform.worldToShip.transformPosition(px, py, pz, gas.shipPoint)
            gas.shipCell.set(gas.shipPoint.x, gas.shipPoint.y, gas.shipPoint.z)
            if (passes(level, gas.shipCell)) continue
            out?.let { pointVelocity(ship.velocity, ship.angularVelocity, ship.transform.positionInWorld, px, py, pz, it) }
            return true
        }
        for (body in bodiesNear(level, gas, pos)) {
            if (!body.containsWorldPoint(px, py, pz, gas.bodyPoint)) continue
            out?.let { pointVelocity(body.velocity, body.angularVelocity, body.kinematics.position, px, py, pz, it) }
            return true
        }
        return false
    }

    // v + omega x (p - centre)
    private fun pointVelocity(v: Vector3dc, w: Vector3dc, c: Vector3dc, px: Double, py: Double, pz: Double, out: Vector3d) {
        val rx = px - c.x(); val ry = py - c.y(); val rz = pz - c.z()
        out.set(v.x() + w.y() * rz - w.z() * ry, v.y() + w.z() * rx - w.x() * rz, v.z() + w.x() * ry - w.y() * rx)
    }

    /** A world cell is also closed where a ship block or a body fills it. */
    private fun openAt(level: ServerLevel, gas: LevelGas, pos: BlockPos): Boolean =
        open(level, pos) && (level.isBlockInShipyard(pos) || !hullAt(level, gas, pos, null))

    /** [openAt] for a loaded cell; during the gas passes the answer is kept for the tick. */
    private fun openHere(level: ServerLevel, gas: LevelGas, pos: BlockPos): Boolean {
        if (!gas.caching) return level.isLoaded(pos) && openAt(level, gas, pos)
        val k = pos.asLong()
        when (gas.openCache.get(k)) {
            CACHED_OPEN -> return true
            CACHED_CLOSED -> return false
        }
        val open = level.isLoaded(pos) && openAt(level, gas, pos)
        gas.openCache.put(k, if (open) CACHED_OPEN else CACHED_CLOSED)
        return open
    }

    private fun newFlow(gas: LevelGas): FloatArray = gas.flowPool.removeLastOrNull()?.also { it.fill(0f) } ?: FloatArray(3)

    private fun releaseFlow(gas: LevelGas, flow: FloatArray?) {
        if (flow != null) gas.releasedFlows += flow
    }

    private fun flowSlot(gas: LevelGas, key: Long, i: Int): FloatArray =
        gas.flows.getOrPut(key) { Int2ObjectOpenHashMap() }.getOrPut(i) { newFlow(gas) }

    /** The cell that holds the gas of world cell [pos]: the ship cell when the point is in the block bounds of a ship. */
    private fun cellFor(level: ServerLevel, gas: LevelGas, pos: BlockPos, out: BlockPos.MutableBlockPos): BlockPos.MutableBlockPos {
        if (level.isBlockInShipyard(pos)) return out.set(pos)
        for (ship in shipsNear(level, gas, pos)) {
            val box = ship.shipAABB ?: continue
            ship.transform.worldToShip.transformPosition(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, gas.shipPoint)
            out.set(gas.shipPoint.x, gas.shipPoint.y, gas.shipPoint.z)
            if (inBlockBounds(box, out)) return out
        }
        return out.set(pos)
    }

    /**
     * Like [cellFor], but a shipyard cell outside the block bounds of its ship is the world air there. A vent on the
     * outside of a hull then blows into the world, and the gas does not ride along beside the ship.
     */
    private fun airCellFor(level: ServerLevel, gas: LevelGas, pos: BlockPos, out: BlockPos.MutableBlockPos): BlockPos.MutableBlockPos {
        val ship = if (level.isBlockInShipyard(pos)) level.getShipManagingPos(pos) else null
        val box = ship?.shipAABB ?: return cellFor(level, gas, pos, out)
        if (inBlockBounds(box, pos)) return out.set(pos)
        ship.transform.shipToWorld.transformPosition(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, gas.shipPoint)
        return cellFor(level, gas, BlockPos.containing(gas.shipPoint.x, gas.shipPoint.y, gas.shipPoint.z), out)
    }

    /** [v] in the axes of cell [from], turned into the axes of cell [to]. */
    private fun flowBetween(level: ServerLevel, gas: LevelGas, from: BlockPos, to: BlockPos, v: FloatArray): FloatArray {
        val w = gas.flowScratch.set(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
        if (level.isBlockInShipyard(from)) level.getShipManagingPos(from)?.transform?.shipToWorldRotation?.transform(w)
        val d = flowIn(level, gas, to, w.x, w.y, w.z)
        val out = gas.movedFlow
        out[0] = d.x.toFloat(); out[1] = d.y.toFloat(); out[2] = d.z.toFloat()
        return out
    }

    private fun inBlockBounds(box: AABBic, pos: BlockPos): Boolean =
        pos.x in box.minX()..box.maxX() && pos.y in box.minY()..box.maxY() && pos.z in box.minZ()..box.maxZ()

    /** Gas that spread off a ship's blocks into the shipyard air around them joins the world air at that point. */
    private fun releaseOutside(level: ServerLevel, gas: LevelGas) {
        val keys = gas.chunkKeys
        keys.clear(); keys.addAll(gas.chunks.keys)
        for (k in 0 until keys.size) {
            val key = keys.getLong(k)
            if (key in gas.loading) continue
            val cells = gas.chunks.get(key) ?: continue
            val ship = level.getShipManagingPos(ChunkPos.getX(key), ChunkPos.getZ(key)) ?: continue
            val box = ship.shipAABB ?: continue
            // Only the few cells outside the hull box are kept; moveAll changes [cells], so they move after the scan.
            val indices = gas.indices
            indices.clear()
            val scan = cells.keys.iterator()
            while (scan.hasNext()) {
                val i = scan.nextInt()
                if (!inBlockBounds(box, posOf(level, key, i, gas.at))) indices.add(i)
            }
            for (n in 0 until indices.size) {
                val src = posOf(level, key, indices.getInt(n), gas.at)
                // A closed world cell keeps the gas here until it can leave.
                moveAll(level, gas, src, airCellFor(level, gas, src, gas.near))
            }
        }
    }

    /** Flows are kept in the axes of the cell's frame. The result is [LevelGas.flowScratch]: copy it to keep it. */
    private fun flowIn(level: ServerLevel, gas: LevelGas, cell: BlockPos, worldX: Double, worldY: Double, worldZ: Double): Vector3d {
        val v = gas.flowScratch.set(worldX, worldY, worldZ)
        if (level.isBlockInShipyard(cell)) level.getShipManagingPos(cell)?.transform?.shipToWorldRotation?.transformInverse(v)
        return v
    }

    fun index(minBuildHeight: Int, x: Int, y: Int, z: Int): Int = ((y - minBuildHeight) shl 8) or ((z and 15) shl 4) or (x and 15)

    fun index(level: Level, pos: BlockPos): Int = index(level.minBuildHeight, pos.x, pos.y, pos.z)

    fun cellX(chunkX: Int, index: Int): Int = chunkX * 16 + (index and 15)

    fun cellY(minBuildHeight: Int, index: Int): Int = (index shr 8) + minBuildHeight

    fun cellZ(chunkZ: Int, index: Int): Int = chunkZ * 16 + ((index shr 4) and 15)

    fun posOf(level: Level, chunk: Long, index: Int, out: BlockPos.MutableBlockPos): BlockPos.MutableBlockPos = out.set(
        cellX(ChunkPos.getX(chunk), index), cellY(level.minBuildHeight, index), cellZ(ChunkPos.getZ(chunk), index),
    )

    private fun key(pos: BlockPos): Long = ChunkPos.asLong(pos.x shr 4, pos.z shr 4)

    /** [pos] can be a world or a shipyard cell. */
    fun add(level: ServerLevel, pos: BlockPos, amount: Float, kind: GasKind = GasKind.MIASMA) =
        addTo(level, gasOf(level, kind), pos, amount)

    /** Returns the gas that the cell took. */
    private fun addTo(level: ServerLevel, gas: LevelGas, pos: BlockPos, amount: Float): Float {
        if (amount <= 0f || !level.isLoaded(pos)) return 0f
        return addToCell(level, gas, cellFor(level, gas, pos, gas.cell), amount)
    }

    /** Like [addTo], but [cell] is already the cell that holds the gas. */
    private fun addToCell(level: ServerLevel, gas: LevelGas, cell: BlockPos, amount: Float): Float {
        if (amount <= 0f || !level.isLoaded(cell) || !openAt(level, gas, cell)) return 0f
        val key = key(cell)
        val i = index(level, cell)
        val had = gas.chunks.get(key)?.get(i) ?: 0f
        val room = MAX_AMOUNT - othersAt(level, gas, cell)
        if (!gas.hasRoom(had) || had >= room) return 0f
        val value = minOf(room, had + amount)
        gas.chunks.getOrPut(key) { Int2FloatOpenHashMap() }.put(i, value)
        if (had == 0f) gas.count++
        gas.changedIn(key).put(i, value)
        return value - had
    }

    // A flood fill from the hand costs less than a line-of-sight test for each block, and a wall still stops it.
    @Suppress("LongParameterList") // One value for each part of the cone.
    fun spray(
        level: ServerLevel, from: Vec3, direction: Vec3, length: Double, halfAngle: Double, amount: Float,
        kind: GasKind = GasKind.MIASMA, blowSpeed: Float = SPRAY_BLOW,
    ) {
        val gas = gasOf(level, kind)
        val origin = level.toWorldCoordinates(from)
        val start = BlockPos.containing(origin)
        if (!level.isLoaded(start) || !openAt(level, gas, cellFor(level, gas, start, gas.cell))) return
        val seen = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        seen += start
        queue += start
        val next = BlockPos.MutableBlockPos()
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            val cell = cellFor(level, gas, at, gas.cell).immutable()
            addTo(level, gas, cell, amount)
            val to = Vec3.atCenterOf(at).subtract(origin)
            val dist = to.length()
            val off = if (dist < 1.0e-3) 0.0 else Math.acos((to.dot(direction) / dist).coerceIn(-1.0, 1.0)) / halfAngle
            val blow = (blowSpeed * (1.0 - off * off).coerceAtLeast(0.0)).toFloat()
            if (blow > 0f && amountAt(gas, level, cell) > 0f) {
                val d = flowIn(level, gas, cell, direction.x, direction.y, direction.z)
                val flow = flowSlot(gas, key(cell), index(level, cell))
                flow[0] += (d.x.toFloat() * blow - flow[0]) * SPRAY_BLOW_SHARE
                flow[1] += (d.y.toFloat() * blow - flow[1]) * SPRAY_BLOW_SHARE
                flow[2] += (d.z.toFloat() * blow - flow[2]) * SPRAY_BLOW_SHARE
            }
            for (face in FACES) {
                next.setWithOffset(at, face)
                if (next in seen || !inCone(next, origin, direction, length, halfAngle)) continue
                val pos = next.immutable()
                seen += pos
                if (level.isLoaded(pos) && openAt(level, gas, cellFor(level, gas, pos, gas.cell))) queue += pos
            }
        }
    }

    // Within one block of the tip, the block of the hand counts.
    private fun inCone(pos: BlockPos, origin: Vec3, direction: Vec3, length: Double, halfAngle: Double): Boolean {
        val to = Vec3.atCenterOf(pos).subtract(origin)
        val dist = to.length()
        if (dist > length) return false
        if (dist < 1.0) return true
        val along = to.dot(direction)
        // A thin tube around the axis keeps a narrow cone connected for the flood fill.
        return along / dist >= Math.cos(halfAngle) || (along > 0.0 && dist * dist - along * along <= AXIS_TUBE_SQ)
    }

    fun addDome(level: ServerLevel, centre: Vec3, radius: Double, amount: Float, kind: GasKind = GasKind.MIASMA) {
        val gas = gasOf(level, kind)
        forDome(level.toWorldCoordinates(centre), radius) { at, _ -> addTo(level, gas, at, amount) }
    }

    fun addBurst(level: ServerLevel, from: Vec3, radius: Double, amount: Float, kind: GasKind = GasKind.MIASMA) {
        val centre = level.toWorldCoordinates(from)
        val gas = gasOf(level, kind)
        forDome(centre, radius) { at, reach ->
            addTo(level, gas, at, amount * (1f - reach * reach))
            val cell = cellFor(level, gas, at, gas.cell)
            if (amountAt(gas, level, cell) <= 0f) return@forDome
            val away = Vec3.atCenterOf(at).subtract(centre)
            val dist = away.length()
            if (dist < BURST_STILL) return@forDome
            val speed = AREA_BLOW * (1f - reach) / dist
            val v = flowIn(level, gas, cell, away.x * speed, away.y * speed, away.z * speed)
            gas.flows.getOrPut(key(cell)) { Int2ObjectOpenHashMap() }.put(index(level, cell), floatArrayOf(v.x.toFloat(), v.y.toFloat(), v.z.toFloat()))
        }
    }

    /** [f] gets each block and its reach: 0 at the middle, 1 at the edge. */
    private inline fun forDome(centre: Vec3, radius: Double, f: (BlockPos, Float) -> Unit) {
        val h = radius * DOME_HEIGHT
        val r = ceil(radius).toInt()
        val c = BlockPos.containing(centre)
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val at = BlockPos(c.x + dx, c.y + dy, c.z + dz)
            val x = (at.x + 0.5 - centre.x) / radius
            val y = (at.y + 0.5 - centre.y) / h
            val z = (at.z + 0.5 - centre.z) / radius
            val reach = x * x + y * y + z * z
            if (reach <= 1.0) f(at, Math.sqrt(reach).toFloat())
        }
    }

    /** A vent: all gas of each kind in [from] that fits goes to [to] at once, with its flow. Both are in the vent's frame. */
    // Game thread only.
    private val throttleCell = BlockPos.MutableBlockPos()

    /**
     * How freely the vent at [vent] can push gas out of its [out] face: 1 while the open cells ahead hold 1 or less,
     * down to 0 as the fullest of them nears [MAX_AMOUNT]. A vent cannot push into space that is already full.
     */
    fun ventThrottle(level: ServerLevel, vent: BlockPos, out: Direction): Float {
        var most = 0f
        for (d in 1..THROTTLE_CELLS) {
            val pos = throttleCell.set(vent).move(out, d)
            if (!level.isLoaded(pos) || !open(level, pos)) break
            var total = 0f
            for (kind in KINDS) gasIn(level, kind)?.let { total += amountAt(it, level, pos) }
            most = maxOf(most, total)
        }
        return ((MAX_AMOUNT - most) / (MAX_AMOUNT - 1f)).coerceIn(0f, 1f)
    }

    /**
     * A vent at [vent] draws gas from behind it and pushes it out of the [out] face, for every kind. Never back through.
     * Returns the recoil in newtons, along the opposite of [out].
     */
    fun vent(level: ServerLevel, vent: BlockPos, out: Direction, strength: Float, jetId: Long): Float {
        require(strength in 0f..1f) { "strength $strength" }
        val input = vent.relative(out.opposite)
        drainVent(level, input, strength, jetId)
        val moved = ventMoved
        val back = out.opposite
        val u = if (back.axis == Direction.Axis.Y) Direction.EAST else if (back.axis == Direction.Axis.X) Direction.UP else Direction.EAST
        val v = if (back.axis == Direction.Axis.Y) Direction.SOUTH else if (back.axis == Direction.Axis.X) Direction.SOUTH else Direction.UP
        val ship = if (level.isBlockInShipyard(vent)) level.getShipManagingPos(vent) else null
        for (kind in KINDS) {
            val gas = gasIn(level, kind) ?: continue
            if (gas.chunks.isEmpty()) continue
            val r = VENT_PULL_RADIUS
            for (d in 0..r) for (a in -r..r) for (b in -r..r) {
                val sq = d * d + a * a + b * b
                if (sq > r * r) continue
                gas.at.set(input.x + back.stepX * d + u.stepX * a + v.stepX * b, input.y + back.stepY * d + u.stepY * a + v.stepY * b, input.z + back.stepZ * d + u.stepZ * a + v.stepZ * b)
                val towardX = out.stepX - (back.stepX * d + u.stepX * a + v.stepX * b).toDouble()
                val towardY = out.stepY - (back.stepY * d + u.stepY * a + v.stepY * b).toDouble()
                val towardZ = out.stepZ - (back.stepZ * d + u.stepZ * a + v.stepZ * b).toDouble()
                val scale = VENT_PULL_SPEED * strength * (1f - kotlin.math.sqrt(sq.toFloat()) / (r + 1)) / kotlin.math.sqrt(towardX * towardX + towardY * towardY + towardZ * towardZ).toFloat()
                steerCell(level, gas, ship, towardX * scale, towardY * scale, towardZ * scale)
            }
        }
        return (VENT_THRUST_PER_FLOW * moved * VENT_PUSH_SPEED * strength).toFloat()
    }

    /** Blends the flow of the cell at [LevelGas.at] toward a vector in the frame of the vent ([ship], or the world when null). */
    private fun steerCell(level: ServerLevel, gas: LevelGas, ship: Ship?, x: Double, y: Double, z: Double) {
        if (!level.isLoaded(gas.at)) return
        val cell = airCellFor(level, gas, gas.at, gas.near)
        if (amountAt(gas, level, cell) <= 0f) return
        val world = gas.ventWay.set(x, y, z)
        ship?.transform?.shipToWorldRotation?.transform(world)
        val f = flowIn(level, gas, cell, world.x, world.y, world.z)
        val flow = flowSlot(gas, key(cell), index(level, cell))
        flow[0] += (f.x.toFloat() - flow[0]) * VENT_FLOW_BLEND
        flow[1] += (f.y.toFloat() - flow[1]) * VENT_FLOW_BLEND
        flow[2] += (f.z.toFloat() - flow[2]) * VENT_FLOW_BLEND
    }

    /**
     * Takes the share [strength] of the gas of each kind in [from], but no more than jet [jetId] can send. [ventTaken] holds
     * what each kind gave; the jet carries it on.
     */
    private fun drainVent(level: ServerLevel, from: BlockPos, strength: Float, jetId: Long) {
        ventMoved = 0f
        for (kind in KINDS) {
            val gas = gasIn(level, kind)
            ventTaken[kind.ordinal] = 0f
            if (gas == null || !level.isLoaded(from)) continue
            val cell = airCellFor(level, gas, from, gas.at)
            val had = amountAt(gas, level, cell)
            val take = GasJets.intakeRoom(level, jetId, kind, had * strength)
            if (take <= 0f) continue
            set(gas, level, cell, had - take)
            ventTaken[kind.ordinal] = take
            ventMoved += take
        }
    }

    /** What the last [vent] call took from the input cell, in cell units. */
    fun ventMass(kind: GasKind): Float = ventTaken[kind.ordinal]

    /** Adds gas at a parcel hand-off: [pos] is a world or shipyard cell, the flow is in world axes. */
    @Suppress("LongParameterList") // A cell, its amount and the flow of the parcel.
    fun deposit(level: ServerLevel, pos: BlockPos, amount: Float, kind: GasKind, flowX: Double, flowY: Double, flowZ: Double): Float {
        val gas = gasOf(level, kind)
        val taken = addTo(level, gas, pos, amount)
        gas.at.set(pos)
        steerCell(level, gas, null, flowX, flowY, flowZ)
        return taken
    }

    /**
     * Puts [amount] into the open cells nearest to [pos] over cell faces, each up to the cap, and never deletes it.
     * [pos] is a world or shipyard cell, as the vent drain reads it. Returns what found no room; the caller keeps it.
     */
    fun forceDeposit(level: ServerLevel, pos: BlockPos, amount: Float, kind: GasKind): Float {
        if (amount <= 0f || !level.isLoaded(pos)) return amount.coerceAtLeast(0f)
        val gas = gasOf(level, kind)
        return spill(level, gas, airCellFor(level, gas, pos, gas.cell), amount)
    }

    /** A bounded walk over open faces from [start], nearest first; [start] itself takes gas only when it is open. */
    private fun spill(level: ServerLevel, gas: LevelGas, start: BlockPos, amount: Float): Float {
        var left = amount
        spillQueue.clear(); spillSeen.clear()
        spillQueue.add(start.asLong()); spillSeen.add(start.asLong())
        var next = 0
        while (next < spillQueue.size && left > DUST) {
            spillAt.set(spillQueue.getLong(next++))
            left -= addToCell(level, gas, spillAt, left)
            for (face in FACES) {
                if (spillQueue.size >= SPILL_CELLS) break
                spillNext.setWithOffset(spillAt, face)
                val n = spillNext.asLong()
                if (spillSeen.contains(n) || !level.isLoaded(spillNext) || !openAt(level, gas, spillNext)) continue
                spillSeen.add(n)
                spillQueue.add(n)
            }
        }
        return if (left > DUST) left else 0f
    }

    // Game thread only. Scratch for spill.
    private val spillQueue = LongArrayList()
    private val spillSeen = LongOpenHashSet()
    // Game thread only.
    private val coneBox = AABBd()
    private val spillAt = BlockPos.MutableBlockPos()
    private val spillNext = BlockPos.MutableBlockPos()

    /** Moves all of [gas] in [src] that fits into [dst], with its flow. False when [dst] cannot hold gas. */
    /**
     * Gives every gas cell inside the cone (world axes) a flow along [dir], strongest at the apex and down to [farFraction]
     * of [speed] at [length]. The gas then moves by the flow rules, which stop at walls. Ship gas is reached through its
     * transform. A cloud past the scan cap is only partly blown in one call.
     */
    @Suppress("LongParameterList") // One value for each part of the cone.
    fun blowCone(
        level: ServerLevel, origin: Vec3, dir: Vec3, length: Double, tanHalfAngle: Double, apexRadius: Double,
        speed: Float, farFraction: Float,
    ) {
        val reach = length + length * tanHalfAngle + apexRadius + 12.0
        val pad = length * tanHalfAngle + apexRadius
        val end = origin.add(dir.scale(length))
        coneBox.setMin(minOf(origin.x, end.x) - pad, minOf(origin.y, end.y) - pad, minOf(origin.z, end.z) - pad)
            .setMax(maxOf(origin.x, end.x) + pad, maxOf(origin.y, end.y) + pad, maxOf(origin.z, end.z) + pad)
        for (kind in KINDS) {
            val gas = gasIn(level, kind) ?: continue
            var budget = BLOW_SCAN_CAP
            val chunks = gas.chunks.long2ObjectEntrySet().fastIterator()
            while (chunks.hasNext() && budget > 0) {
                val entry = chunks.next()
                val key = entry.longKey
                val cx = ChunkPos.getX(key); val cz = ChunkPos.getZ(key)
                val ship = level.getShipManagingPos(cx, cz)
                if (ship == null && abs(cx * 16 + 8 - origin.x) > reach || ship == null && abs(cz * 16 + 8 - origin.z) > reach) continue
                // Far ship chunks would use up the scan cap before the ships in the cone are reached.
                if (ship != null && !ship.worldAABB.intersectsAABB(coneBox)) continue
                // The direction in the frame of this chunk.
                val d = gas.ventWay.set(dir.x, dir.y, dir.z)
                ship?.transform?.shipToWorldRotation?.transformInverse(d)
                val dx = d.x; val dy = d.y; val dz = d.z
                var flows: Int2ObjectOpenHashMap<FloatArray>? = null
                val cells = entry.value.int2FloatEntrySet().fastIterator()
                while (cells.hasNext() && budget-- > 0) {
                    val i = cells.next().intKey
                    val p = gas.shipPoint.set(cellX(cx, i) + 0.5, cellY(level.minBuildHeight, i) + 0.5, cellZ(cz, i) + 0.5)
                    if (ship != null) ship.transform.shipToWorld.transformPosition(p)
                    val rx = p.x - origin.x; val ry = p.y - origin.y; val rz = p.z - origin.z
                    val along = rx * dir.x + ry * dir.y + rz * dir.z
                    if (along < 0.0 || along > length) continue
                    val radius = along * tanHalfAngle + apexRadius
                    if (rx * rx + ry * ry + rz * rz - along * along > radius * radius) continue
                    val s = speed * (1.0 - (along / length) * (1.0 - farFraction))
                    val flow = (flows ?: gas.flows.getOrPut(key) { Int2ObjectOpenHashMap() }.also { flows = it }).getOrPut(i) { newFlow(gas) }
                    flow[0] += ((dx * s).toFloat() - flow[0]) * BLOW_BLEND
                    flow[1] += ((dy * s).toFloat() - flow[1]) * BLOW_BLEND
                    flow[2] += ((dz * s).toFloat() - flow[2]) * BLOW_BLEND
                }
            }
        }
    }

    /** Blends the flow of [dst] toward the move from [src] at [fraction] of the source gas per tick (blocks per tick). */
    private fun noteMove(level: ServerLevel, gas: LevelGas, src: BlockPos, dst: BlockPos, fraction: Float) {
        if (fraction < NOTE_MIN_FRACTION || amountAt(gas, level, dst) <= 0f) return
        val dx = dst.x - src.x; val dy = dst.y - src.y; val dz = dst.z - src.z
        val sq = dx * dx + dy * dy + dz * dz
        if (sq == 0 || sq > 4 || level.isBlockInShipyard(src) != level.isBlockInShipyard(dst)) return
        val s = minOf(fraction, FLOW_MAX) / kotlin.math.sqrt(sq.toFloat())
        val flow = flowSlot(gas, key(dst), index(level, dst))
        flow[0] += (dx * s - flow[0]) * NOTE_BLEND
        flow[1] += (dy * s - flow[1]) * NOTE_BLEND
        flow[2] += (dz * s - flow[2]) * NOTE_BLEND
    }

    /** Moves up to [limit] of the gas; what other kinds block trades places with them. */
    private fun moveAll(level: ServerLevel, gas: LevelGas, src: BlockPos, dst: BlockPos, limit: Float = Float.MAX_VALUE): Boolean {
        if (!openHere(level, gas, dst)) return false
        val here = minOf(amountAt(gas, level, src), limit)
        val there = amountAt(gas, level, dst)
        if (!gas.hasRoom(there)) return true
        val moved = minOf(here, MAX_AMOUNT - there - othersAt(level, gas, dst)).coerceAtLeast(0f)
        if (moved > 0f) {
            val v = gas.flows.get(key(src))?.get(index(level, src))?.let { flowBetween(level, gas, src, dst, it) }
            val before = amountAt(gas, level, src)
            set(gas, level, src, before - moved)
            set(gas, level, dst, there + moved)
            if (v != null && amountAt(gas, level, dst) > 0f) blendFlow(gas, key(dst), index(level, dst), v, moved / (there + moved))
            noteMove(level, gas, src, dst, moved / before)
        }
        val swapped = if (here > moved) exchangeInto(level, gas, src, dst, here - moved) else 0f
        ventMoved += moved + swapped
        return true
    }

    /** For a world [pos], the larger of the world cell and the ship cell at that point. */
    fun amount(level: ServerLevel, pos: BlockPos, kind: GasKind = GasKind.MIASMA): Float =
        gasIn(level, kind)?.let { amountNear(level, it, pos) } ?: 0f

    private fun amountNear(level: ServerLevel, gas: LevelGas, pos: BlockPos): Float =
        maxOf(amountAt(gas, level, pos), amountAt(gas, level, cellFor(level, gas, pos, gas.cell)))

    private fun tick(level: ServerLevel, gas: LevelGas) {
        gas.shipsByChunk.clear()
        gas.bodiesByChunk.clear()
        if (!gas.awaiting.isEmpty()) thawReady(level, gas)
        markLoading(level, gas)
        startCaching(gas)
        val time = level.gameTime
        prune(level, gas, time % CHECK_EVERY == 0L)
        displace(level, gas)
        flow(level, gas)
        settle(level, gas)
        spread(level, gas)
        pressure(level, gas)
        exchange(level, gas)
        releaseOutside(level, gas)
        if (time % EXPOSE_EVERY == 0L) expose(level, gas)
        publishLift(level, gas)
        sync(level, gas, time % RESYNC_EVERY == 0L)
        if (time % 20L == 0L) dbgReport(level, gas) // TEMP gas debug, remove after the gas run
        // Sources between two ticks must see the ships, bodies and blocks where they are then.
        gas.caching = false
        gas.shipsByChunk.clear()
        gas.bodiesByChunk.clear()
        if (gas.chunks.isEmpty() && gas.awaiting.isEmpty()) {
            levels[gas.kind.ordinal].remove(level)
            GasLift.remove(gas.liftKey)
        }
    }

    private fun startCaching(gas: LevelGas) {
        gas.openCache.clear()
        gas.skyBlock.clear()
        gas.caching = true
        for (n in gas.releasedFlows.indices) {
            if (gas.flowPool.size >= FLOW_POOL_CAP) break
            gas.flowPool += gas.releasedFlows[n]
        }
        gas.releasedFlows.clear()
    }

    private fun markLoading(level: ServerLevel, gas: LevelGas) {
        gas.loading.clear()
        val it = gas.chunks.keys.iterator()
        while (it.hasNext()) {
            val key = it.nextLong()
            val ship = level.getShipManagingPos(ChunkPos.getX(key), ChunkPos.getZ(key)) ?: continue
            if (!ship.voxelsLoaded()) gas.loading.add(key)
        }
    }

    /** Freezes the chunks that unloaded. With [check], also drops the cells that are no longer open. Gas does not fade with time. */
    private fun prune(level: ServerLevel, gas: LevelGas, check: Boolean) {
        val chunks = gas.chunks.long2ObjectEntrySet().fastIterator()
        while (chunks.hasNext()) {
            val entry = chunks.next()
            val key = entry.longKey
            if (!level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                freeze(level, gas.kind, key, entry.value, gas.flows.get(key))
                gas.count -= entry.value.size
                chunks.remove()
                gas.flows.remove(key)
                gas.predicted.remove(key)
                gas.dirty.remove(key)
                gas.sentPacked.remove(key)
                gas.watchers.remove(key)
                gas.displaceBacklog.remove(key)
                continue
            }
            val flows = gas.flows.get(key)
            val cells = entry.value.int2FloatEntrySet().fastIterator()
            while (check && key !in gas.loading && cells.hasNext()) {
                val cell = cells.next()
                if (!open(level, posOf(level, key, cell.intKey, gas.at))) {
                    cells.remove()
                    gas.count--
                    releaseFlow(gas, flows?.remove(cell.intKey))
                }
            }
            if (entry.value.isEmpty()) {
                chunks.remove()
                gas.flows.remove(key)
                gas.displaceBacklog.remove(key)
                // The client learns that the chunk is empty.
                gas.dirty.add(key)
            }
        }
    }

    /** Sends the dirty chunks whole ([resync]: also chunks with a new watcher), then the cells whose packed value changed, and flows. */
    private fun sync(level: ServerLevel, gas: LevelGas, resync: Boolean) {
        val send = gas.sentWhole
        send.clear(); send.addAll(gas.dirty)
        gas.dirty.clear()
        if (resync) addNewWatchers(level, gas, send)
        val it = send.iterator()
        while (it.hasNext()) {
            val key = it.nextLong()
            val cells = gas.chunks.get(key)
            val dbgWhole = wholeFlows(gas, key) // TEMP gas debug, remove after the gas run
            gas.dbgWholeChunks++; gas.dbgWholeCells += cells?.size ?: 0; gas.dbgFlowEntries += dbgWhole?.size ?: 0 // TEMP gas debug, remove after the gas run
            SpellNetwork.sendGas(recipients(level, gas, key), gas.kind, key, cells, dbgWhole)
            rememberWhole(gas, key, cells)
        }
        changedFlows(gas, send)
        // Chunks sent whole this tick already carry their changes.
        val changed = gas.changed.long2ObjectEntrySet().fastIterator()
        while (changed.hasNext()) {
            val entry = changed.next()
            val key = entry.longKey
            if (!send.contains(key)) {
                dropAlreadyHeld(gas, key, entry.value)
                if (entry.value.isNotEmpty()) {
                    gas.dbgDeltaChunks++; gas.dbgDeltaCells += entry.value.size // TEMP gas debug, remove after the gas run
                    val flows = gas.flowChanged.remove(key)
                    SpellNetwork.sendGasCells(recipients(level, gas, key), gas.kind, key, entry.value, flows)
                    if (flows != null) spareFlowChanged(gas, flows)
                }
            }
            entry.value.clear()
            gas.spareChanged += entry.value
        }
        gas.changed.clear()
        val flowsOnly = gas.flowChanged.long2ObjectEntrySet().fastIterator()
        while (flowsOnly.hasNext()) {
            val entry = flowsOnly.next()
            gas.dbgDeltaChunks++; gas.dbgFlowEntries += entry.value.size // TEMP gas debug, remove after the gas run
            SpellNetwork.sendGasCells(recipients(level, gas, entry.longKey), gas.kind, entry.longKey, null, entry.value)
            spareFlowChanged(gas, entry.value)
        }
        gas.flowChanged.clear()
    }

    // sendGasCells writes the map into a buffer at once, so it can be reused.
    private fun spareFlowChanged(gas: LevelGas, flows: Int2IntOpenHashMap) {
        if (gas.spareFlowChanged.size >= FLOW_POOL_CAP) return
        flows.clear()
        gas.spareFlowChanged += flows
    }

    private fun poolPredicted(gas: LevelGas, v: FloatArray) {
        if (gas.predictedPool.size < FLOW_POOL_CAP) gas.predictedPool += v
    }

    /** Adds each chunk that a player watches but did not get whole. Every watcher gets it, so the flow prediction stays shared. */
    private fun addNewWatchers(level: ServerLevel, gas: LevelGas, send: LongOpenHashSet) {
        val chunks = gas.chunks.keys.iterator()
        while (chunks.hasNext()) {
            val key = chunks.nextLong()
            val players = level.chunkSource.chunkMap.getPlayers(ChunkPos(key), false)
            val seen = gas.watchers.getOrPut(key) { IntOpenHashSet() }
            var fresh = false
            for (player in players) if (!seen.contains(player.id)) fresh = true
            seen.clear()
            for (player in players) seen.add(player.id)
            if (fresh) send.add(key)
        }
    }

    /**
     * The players that track chunk [key] now. A watcher that is not one of them misses this send, so the watchers of the
     * chunk are forgotten and the next resync sends it whole again.
     */
    private fun recipients(level: ServerLevel, gas: LevelGas, key: Long): List<ServerPlayer> {
        val players = level.chunkSource.chunkMap.getPlayers(ChunkPos(key), false)
        val seen = gas.watchers.get(key) ?: return players
        var tracked = 0
        for (i in players.indices) if (seen.contains(players[i].id)) tracked++
        if (tracked != seen.size) seen.clear()
        return players
    }

    private fun rememberWhole(gas: LevelGas, key: Long, cells: Int2FloatOpenHashMap?) {
        if (cells == null) {
            gas.sentPacked.remove(key)
            gas.watchers.remove(key)
            return
        }
        val sent = gas.sentPacked.getOrPut(key) { Int2ByteOpenHashMap() }
        sent.clear()
        val it = cells.int2FloatEntrySet().fastIterator()
        while (it.hasNext()) {
            val e = it.next()
            sent.put(e.intKey, packAmount(e.floatValue).toByte())
        }
    }

    /** Removes from [cells] each cell whose packed value the clients already hold, and records the rest as sent. */
    private fun dropAlreadyHeld(gas: LevelGas, key: Long, cells: Int2FloatOpenHashMap) {
        val sent = gas.sentPacked.getOrPut(key) { Int2ByteOpenHashMap() }
        val it = cells.int2FloatEntrySet().fastIterator()
        while (it.hasNext()) {
            val e = it.next()
            val packed = if (e.floatValue <= 0f) 0 else packAmount(e.floatValue)
            if ((sent.get(e.intKey).toInt() and 0xFF) == packed) {
                it.remove()
            } else if (packed == 0) {
                sent.remove(e.intKey)
            } else {
                sent.put(e.intKey, packed.toByte())
            }
        }
    }

    /** All flows of a chunk sent whole; the prediction restarts from them. */
    private fun wholeFlows(gas: LevelGas, key: Long): Int2IntOpenHashMap? {
        gas.predicted.remove(key)
        val flows = gas.flows.get(key) ?: return null
        val out = Int2IntOpenHashMap(flows.size)
        val predicted = Int2ObjectOpenHashMap<FloatArray>(flows.size)
        val it = flows.int2ObjectEntrySet().fastIterator()
        while (it.hasNext()) {
            val e = it.next()
            val packed = packFlow(e.value)
            if (packed == 0) continue
            out.put(e.intKey, packed)
            predicted.put(e.intKey, unpackFlow(packed, FloatArray(3)))
        }
        if (predicted.isNotEmpty()) gas.predicted.put(key, predicted)
        return out
    }

    /** Drags the client prediction as the client does, then queues each flow that left it. */
    private fun changedFlows(gas: LevelGas, sentWhole: LongOpenHashSet) {
        val keys = gas.flowKeys
        keys.clear(); keys.addAll(gas.flows.keys); keys.addAll(gas.predicted.keys)
        val limit = FLOW_RESEND_STEPS * FLOW_MAX / 127f
        val decoded = gas.movedFlow
        val it = keys.iterator()
        while (it.hasNext()) {
            val key = it.nextLong()
            if (sentWhole.contains(key)) continue
            val predicted = gas.predicted.get(key)
            if (predicted != null) {
                val p = predicted.int2ObjectEntrySet().fastIterator()
                while (p.hasNext()) {
                    val v = p.next().value
                    for (a in 0..2) v[a] *= gas.kind.flowDrag
                    if (abs(v[0]) + abs(v[1]) + abs(v[2]) < STILL) {
                        p.remove()
                        poolPredicted(gas, v)
                    }
                }
            }
            val flows = gas.flows.get(key)
            if (flows != null) {
                val f = flows.int2ObjectEntrySet().fastIterator()
                while (f.hasNext()) {
                    val e = f.next()
                    val packed = packFlow(e.value)
                    val was = predicted?.get(e.intKey)
                    if (packed == 0) {
                        if (was == null) continue
                    } else {
                        unpackFlow(packed, decoded)
                        if (was != null && abs(decoded[0] - was[0]) <= limit && abs(decoded[1] - was[1]) <= limit &&
                            abs(decoded[2] - was[2]) <= limit) continue
                    }
                    queueFlow(gas, key, e.intKey, packed)
                }
            }
            // A predicted flow that the server no longer has is removed on the client.
            if (predicted != null) {
                val gone = gas.indices
                gone.clear()
                val p = predicted.keys.iterator()
                while (p.hasNext()) {
                    val i = p.nextInt()
                    if (flows?.containsKey(i) != true) gone.add(i)
                }
                for (n in 0 until gone.size) queueFlow(gas, key, gone.getInt(n), 0)
            }
        }
    }

    private fun queueFlow(gas: LevelGas, key: Long, index: Int, packed: Int) {
        gas.flowChanged.getOrPut(key) { gas.spareFlowChanged.removeLastOrNull() ?: Int2IntOpenHashMap() }.put(index, packed)
        val predicted = gas.predicted.get(key)
        if (packed == 0) {
            if (predicted == null) return
            predicted.remove(index)?.let { poolPredicted(gas, it) }
            if (predicted.isEmpty()) gas.predicted.remove(key)
        } else {
            val into = (predicted ?: Int2ObjectOpenHashMap<FloatArray>().also { gas.predicted.put(key, it) })
                .getOrPut(index) { gas.predictedPool.removeLastOrNull() ?: FloatArray(3) }
            unpackFlow(packed, into)
        }
    }

    /** An amount of 0 to [MAX_AMOUNT] as one byte, at least 1 for gas that exists. */
    fun packAmount(a: Float): Int = Mth.clamp(Math.round(a / MAX_AMOUNT * 255f), 1, 255)

    fun unpackAmount(b: Int): Float = (b and 0xFF) / 255f * MAX_AMOUNT

    /** Three int8 in steps of FLOW_MAX / 127, in the cell's own frame; 0 is no flow. */
    fun packFlow(v: FloatArray): Int {
        var packed = 0
        for (a in 0..2) packed = packed or ((Math.round(v[a] / FLOW_MAX * 127f).coerceIn(-127, 127) and 0xFF) shl (8 * a))
        return packed
    }

    fun unpackFlow(packed: Int, out: FloatArray): FloatArray {
        for (a in 0..2) out[a] = ((packed shr (8 * a)) and 0xFF).toByte() * FLOW_MAX / 127f
        return out
    }

    @OptIn(GameTickOnly::class, VsBeta::class)
    @Suppress("LongParameterList") // Raw doubles: no vector allocation each tick.
    private fun densityRatio(
        level: ServerLevel, gas: LevelGas, aero: AerodynamicUtils, seaDensity: Double,
        shipId: Long, sx: Double, sy: Double, sz: Double,
    ): Double {
        if (seaDensity <= 0.0) return 1.0
        val ship = level.shipObjectWorld.loadedShips.getById(shipId) ?: return 1.0
        val p = ship.transform.shipToWorld.transformPosition(sx, sy, sz, gas.shipPoint)
        return (aero.getAirDensityAt(p.x, p.y, p.z, gas.dimension) / seaDensity).coerceIn(0.0, 1.0)
    }

    @OptIn(GameTickOnly::class, VsBeta::class)
    private fun publishLift(level: ServerLevel, gas: LevelGas) {
        val sums = gas.liftSums
        sums.clear()
        val chunks = gas.chunks.long2ObjectEntrySet().fastIterator()
        while (chunks.hasNext()) {
            val entry = chunks.next()
            val key = entry.longKey
            // A move in this tick can leave a chunk entry with no cells; a sum for it would divide by zero.
            if (entry.value.isEmpty()) continue
            val ship = level.getShipManagingPos(ChunkPos.getX(key), ChunkPos.getZ(key)) ?: continue
            val sum = sums.get(ship.id) ?: DoubleArray(5).also { sums.put(ship.id, it) }
            val cells = entry.value.int2FloatEntrySet().fastIterator()
            while (cells.hasNext()) {
                val cell = cells.next()
                val a = cell.floatValue.toDouble()
                posOf(level, key, cell.intKey, gas.at)
                sum[0] += a
                sum[1] += a * (gas.at.x + 0.5)
                sum[2] += a * (gas.at.y + 0.5)
                sum[3] += a * (gas.at.z + 0.5)
                sum[4] += gas.kind.liftWeight(cell.floatValue)
            }
        }
        if (sums.isEmpty()) {
            gas.liftCentres.clear()
            GasLift.remove(gas.liftKey)
            return
        }
        // Lift follows the air density at the gas centre, relative to sea level, so a balloon levels off as the air thins.
        val aero = level.shipObjectWorld.aerodynamicUtils
        val seaDensity = aero.getAirDensityForY(aero.getAtmosphereForDimension(gas.dimension).second, gas.dimension)
        val n = sums.size
        val ids = LongArray(n); val newtons = DoubleArray(n)
        val cx = DoubleArray(n); val cy = DoubleArray(n); val cz = DoubleArray(n)
        var i = 0
        val all = sums.long2ObjectEntrySet().fastIterator()
        while (all.hasNext()) {
            val e = all.next()
            val sum = e.value
            ids[i] = e.longKey
            val c = easeLiftCentre(gas, e.longKey, sum[1] / sum[0], sum[2] / sum[0], sum[3] / sum[0])
            cx[i] = c[0]; cy[i] = c[1]; cz[i] = c[2]
            newtons[i] = sum[4] * GasKind.AIR_WEIGHT_NEWTONS * densityRatio(level, gas, aero, seaDensity, e.longKey, cx[i], cy[i], cz[i])
            i++
        }
        GasLift.publish(gas.liftKey, GasLift.Snapshot(gas.dimension, ids, newtons, cx, cy, cz))
        val gone = gas.liftCentres.long2ObjectEntrySet().fastIterator()
        while (gone.hasNext()) if (!sums.containsKey(gone.next().longKey)) gone.remove()
    }

    /**
     * Moves the lift centre of [shipId] toward the gas centre. Sloshing gas lags the tilt that moves it, and that lag can
     * pump a wobble; easing well below the swing rate cuts the pumped part. A new ship starts at the gas centre.
     */
    private fun easeLiftCentre(gas: LevelGas, shipId: Long, x: Double, y: Double, z: Double): DoubleArray {
        val c = gas.liftCentres.get(shipId) ?: return doubleArrayOf(x, y, z).also { gas.liftCentres.put(shipId, it) }
        c[0] += (x - c[0]) * LIFT_CENTRE_EASE
        c[1] += (y - c[1]) * LIFT_CENTRE_EASE
        c[2] += (z - c[2]) * LIFT_CENTRE_EASE
        return c
    }

    private fun flow(level: ServerLevel, gas: LevelGas) {
        if (gas.flows.isEmpty()) return
        // Snapshot first, so each block moves once, as it was at the start of the tick.
        val keys = gas.movingKeys; val cells = gas.movingCells; val moving = gas.movingFlows
        val chunks = gas.flows.long2ObjectEntrySet().fastIterator()
        while (chunks.hasNext()) {
            val entry = chunks.next()
            if (entry.longKey in gas.loading) continue
            val flows = entry.value.int2ObjectEntrySet().fastIterator()
            while (flows.hasNext()) {
                val f = flows.next()
                keys.add(entry.longKey); cells.add(f.intKey); moving += f.value
            }
        }
        val from = gas.at
        val to = gas.near
        for (n in moving.indices) {
            posOf(level, keys.getLong(n), cells.getInt(n), from)
            flowFrom(level, gas, from, to, moving[n])
        }
        keys.clear(); cells.clear(); moving.clear()
    }

    private fun flowFrom(level: ServerLevel, gas: LevelGas, from: BlockPos, to: BlockPos.MutableBlockPos, v: FloatArray) {
        val fromKey = key(from); val fromI = index(level, from)
        var here = amountAt(gas, fromKey, fromI)
        if (here <= 0f) {
            removeFlow(gas, fromKey, fromI)
            return
        }
        val total = abs(v[0]) + abs(v[1]) + abs(v[2])
        val scale = if (total > FLOW_MAX) FLOW_MAX / total else 1f
        for (axis in 0..2) {
            val speed = v[axis]
            if (speed == 0f) continue
            val step = if (speed > 0f) 1 else -1
            to.set(from.x + if (axis == 0) step else 0, from.y + if (axis == 1) step else 0, from.z + if (axis == 2) step else 0)
            if (!openHere(level, gas, to)) {
                v[axis] = 0f
                continue
            }
            val toKey = key(to); val toI = index(level, to)
            val there = amountAt(gas, toKey, toI)
            val want = here * abs(speed) * scale
            val moved = minOf(want, MAX_AMOUNT - there - othersAtIndex(level, gas, toKey, toI)).coerceAtLeast(0f)
            if (!gas.hasRoom(there)) continue
            if (moved > 0f) {
                here -= moved
                if (setAt(gas, level, to, toKey, toI, there + moved) > 0f) blendFlow(gas, toKey, toI, v, moved / (there + moved))
            }
            // What other kinds block trades places with them; `from` is written below from [here].
            if (want > moved) here -= exchangeInto(level, gas, from, to, minOf(want - moved, here))
        }
        val left = setAt(gas, level, from, fromKey, fromI, here)
        for (a in 0..2) v[a] *= gas.kind.flowDrag
        if (abs(v[0]) + abs(v[1]) + abs(v[2]) < STILL || left <= 0f) removeFlow(gas, fromKey, fromI)
    }

    /** Moved gas brings its velocity, weighted by its share [w] of the gas there now. */
    private fun blendFlow(gas: LevelGas, key: Long, i: Int, v: FloatArray, w: Float) {
        val dv = flowSlot(gas, key, i)
        for (a in 0..2) dv[a] += (v[a] - dv[a]) * w
    }

    private fun removeFlow(gas: LevelGas, key: Long, i: Int) {
        val flows = gas.flows.get(key) ?: return
        releaseFlow(gas, flows.remove(i))
        if (flows.isEmpty()) gas.flows.remove(key)
    }

    private fun spread(level: ServerLevel, gas: LevelGas) {
        val from = gas.at
        val pos = gas.near
        val openFaces = gas.openFaces
        val keys = gas.chunkKeys
        keys.clear(); keys.addAll(gas.chunks.keys)
        for (k in 0 until keys.size) {
            val key = keys.getLong(k)
            if (key in gas.loading) continue
            val cells = gas.chunks.get(key) ?: continue
            forRandomCells(level, gas, cells, SPREAD_PER_CHUNK) { i ->
                posOf(level, key, i, from)
                // Spreading evens one kind only, so it stays out of cells that another kind shares.
                if (othersAt(level, gas, from) > 0f) return@forRandomCells
                var faces = 0
                for (f in FACES.indices) {
                    pos.setWithOffset(from, FACES[f])
                    if (openHere(level, gas, pos) && gas.hasRoom(amountAt(gas, level, pos)) &&
                        othersAt(level, gas, pos) <= 0f
                    ) openFaces[faces++] = f
                }
                if (faces == 0) return@forRandomCells
                var total = amountAt(gas, level, from)
                for (f in 0 until faces) total += amountAt(gas, level, pos.setWithOffset(from, FACES[openFaces[f]]))
                val even = total / (faces + 1)
                val source = amountAt(gas, level, from)
                set(gas, level, from, mix(source, even, gas.kind.spreadShare))
                for (f in 0 until faces) {
                    pos.setWithOffset(from, FACES[openFaces[f]])
                    val old = amountAt(gas, level, pos)
                    val next = mix(old, even, gas.kind.spreadShare)
                    set(gas, level, pos, next)
                    if (source > 0f && next > old) noteMove(level, gas, from, pos, (next - old) / source)
                }
            }
        }
    }

    /**
     * Vented ship air mixes with the world air at the same point, at the spread rate. As a ship moves, its open
     * decks meet new world air, so they drain as world gas spreads; a sealed hold keeps its gas.
     */
    private fun exchange(level: ServerLevel, gas: LevelGas) {
        val keys = gas.chunkKeys
        keys.clear(); keys.addAll(gas.chunks.keys)
        for (k in 0 until keys.size) {
            val key = keys.getLong(k)
            if (key in gas.loading) continue
            val cells = gas.chunks.get(key) ?: continue
            val chunkX = ChunkPos.getX(key); val chunkZ = ChunkPos.getZ(key)
            val home = level.getShipManagingPos(chunkX, chunkZ)
            if (home == null && shipsNear(level, gas, gas.near.set(chunkX * 16, 0, chunkZ * 16)).isEmpty()) continue
            forRandomCells(level, gas, cells, SPREAD_PER_CHUNK) { i ->
                posOf(level, key, i, gas.at)
                if (home != null) exchangeShipCell(level, gas, home, gas.at) else exchangeWorldCell(level, gas, gas.at)
            }
        }
    }

    private fun exchangeShipCell(level: ServerLevel, gas: LevelGas, ship: Ship, cell: BlockPos) {
        val volume = sealFor(level, ship) ?: return
        if (!volume.isVented(cell.x, cell.y, cell.z)) return
        ship.transform.shipToWorld.transformPosition(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, gas.shipPoint)
        val world = gas.near.set(gas.shipPoint.x, gas.shipPoint.y, gas.shipPoint.z)
        if (level.isLoaded(world) && openAt(level, gas, world)) mixPair(level, gas, cell, world)
    }

    private fun exchangeWorldCell(level: ServerLevel, gas: LevelGas, world: BlockPos) {
        for (ship in shipsNear(level, gas, world)) {
            val box = ship.shipAABB ?: continue
            ship.transform.worldToShip.transformPosition(world.x + 0.5, world.y + 0.5, world.z + 0.5, gas.shipPoint)
            val cell = gas.cell.set(gas.shipPoint.x, gas.shipPoint.y, gas.shipPoint.z)
            if (!inBlockBounds(box, cell)) continue
            // A ship block on a world cell is a hull that moved into the gas, not an exchange.
            if (!ship.voxelsLoaded() || !level.isLoaded(cell) || !open(level, cell)) return
            val volume = sealFor(level, ship) ?: return
            if (volume.isVented(cell.x, cell.y, cell.z)) mixPair(level, gas, world, cell)
            return
        }
    }

    // A flood of a hull with chunks still loading reads them as air, so a sealed hold looks vented.
    private fun sealFor(level: ServerLevel, ship: Ship): LatticeHullVolume.Volume? =
        LatticeHullVolume.volumeFor(level, ship)?.takeIf { it.current }

    private fun mixPair(level: ServerLevel, gas: LevelGas, a: BlockPos, b: BlockPos) {
        val x = amountAt(gas, level, a)
        val y = amountAt(gas, level, b)
        if (!gas.hasRoom(x) || !gas.hasRoom(y) || othersAt(level, gas, a) > 0f || othersAt(level, gas, b) > 0f) return
        val even = (x + y) / 2f
        set(gas, level, a, mix(x, even, SPREAD_SHARE))
        set(gas, level, b, mix(y, even, SPREAD_SHARE))
    }

    /** Gas in a world cell that a hull or body moved into goes to the open neighbours, most of it ahead of the hull. */
    private fun displace(level: ServerLevel, gas: LevelGas) {
        val keys = gas.chunkKeys
        keys.clear(); keys.addAll(gas.chunks.keys)
        for (k in 0 until keys.size) {
            val key = keys.getLong(k)
            val chunkX = ChunkPos.getX(key); val chunkZ = ChunkPos.getZ(key)
            val corner = gas.near.set(chunkX * 16, 0, chunkZ * 16)
            if (level.isBlockInShipyard(corner)) continue
            val ships = shipsNear(level, gas, corner)
            val bodies = bodiesNear(level, gas, corner)
            if (ships.isEmpty() && bodies.isEmpty()) continue
            // Sources and spread test openAt, so only a hull that moved can hold new gas.
            if (key !in gas.displaceBacklog && ships.none { it.movedThisTick() } && bodies.none { it.isMoving() }) continue
            val cells = gas.chunks.get(key) ?: continue
            val indices = gas.indices
            indices.clear(); indices.addAll(cells.keys)
            var moved = 0
            for (n in 0 until indices.size) {
                if (moved >= DISPLACE_PER_CHUNK) break
                posOf(level, key, indices.getInt(n), gas.at)
                if (!hullAt(level, gas, gas.at, gas.hullVelocity)) continue
                moved++
                pushAside(level, gas, gas.at, gas.hullVelocity)
            }
            if (moved >= DISPLACE_PER_CHUNK) gas.displaceBacklog.add(key) else gas.displaceBacklog.remove(key)
        }
    }

    private fun Ship.movedThisTick(): Boolean {
        val now = transform
        val before = prevTickTransform
        return !sameVector(now.positionInWorld, before.positionInWorld) ||
            !sameVector(now.positionInShip, before.positionInShip) ||
            !sameVector(now.shipToWorldScaling, before.shipToWorldScaling) ||
            now.shipToWorldRotation.x() != before.shipToWorldRotation.x() ||
            now.shipToWorldRotation.y() != before.shipToWorldRotation.y() ||
            now.shipToWorldRotation.z() != before.shipToWorldRotation.z() ||
            now.shipToWorldRotation.w() != before.shipToWorldRotation.w()
    }

    private fun sameVector(a: Vector3dc, b: Vector3dc): Boolean = a.x() == b.x() && a.y() == b.y() && a.z() == b.z()

    @OptIn(VsBeta::class)
    private fun ServerBaseVsBody.isMoving(): Boolean =
        velocity.lengthSquared() > BODY_REST_SPEED_SQ || angularVelocity.lengthSquared() > BODY_REST_SPEED_SQ

    private fun pushAside(level: ServerLevel, gas: LevelGas, from: BlockPos, v: Vector3d) {
        var left = amountAt(gas, level, from)
        val to = gas.near
        for (f in FACES.indices) {
            val n = FACES[f].normal
            gas.faceDots[f] = n.x * v.x + n.y * v.y + n.z * v.z
            gas.faceUsed[f] = false
        }
        // The faces in order of the hull's direction of travel.
        repeat(FACES.size) {
            if (left <= 0f) return@repeat
            var best = -1
            for (f in FACES.indices) if (!gas.faceUsed[f] && (best < 0 || gas.faceDots[f] > gas.faceDots[best])) best = f
            gas.faceUsed[best] = true
            to.setWithOffset(from, FACES[best])
            if (!openHere(level, gas, to)) return@repeat
            val there = amountAt(gas, level, to)
            val moved = minOf(left, MAX_AMOUNT - there - othersAt(level, gas, to))
            if (moved <= 0f || !gas.hasRoom(there)) return@repeat
            set(gas, level, to, there + moved)
            left -= moved
            if (amountAt(gas, level, to) > 0f) setFlow(gas, level, to, v)
        }
        // Gas with no room on the faces goes to the nearest open cells; what still finds none stays until the hull moves on.
        set(gas, level, from, spill(level, gas, from, left))
    }

    /** The hull velocity in blocks a tick, capped as other flows are. */
    private fun setFlow(gas: LevelGas, level: ServerLevel, pos: BlockPos, v: Vector3d) {
        var fx = (v.x / 20.0).toFloat(); var fy = (v.y / 20.0).toFloat(); var fz = (v.z / 20.0).toFloat()
        val total = abs(fx) + abs(fy) + abs(fz)
        if (total > FLOW_MAX) { val k = FLOW_MAX / total; fx *= k; fy *= k; fz *= k }
        if (total < STILL) return
        val flow = flowSlot(gas, key(pos), index(level, pos))
        flow[0] = fx; flow[1] = fy; flow[2] = fz
    }

    /** Calls [action] with up to [count] different cells of [cells], picked at random by a partial shuffle. */
    private inline fun forRandomCells(level: ServerLevel, gas: LevelGas, cells: Int2FloatOpenHashMap, count: Int, action: (Int) -> Unit) {
        val indices = gas.indices
        indices.clear(); indices.addAll(cells.keys)
        for (n in 0 until minOf(count, indices.size)) action(pick(indices, n, level.random.nextInt(indices.size - n)))
    }

    private fun pick(indices: IntArrayList, n: Int, offset: Int): Int {
        val j = n + offset
        val i = indices.getInt(j)
        indices.set(j, indices.getInt(n))
        indices.set(n, i)
        return i
    }

    // Each block moves the same share of its difference, and the differences add to zero, so no gas is made or lost.
    private fun mix(amount: Float, even: Float, share: Float): Float = amount + (even - amount) * share

    /** Heavy gas sinks and light gas rises, by the same rules. */
    private fun settle(level: ServerLevel, gas: LevelGas) {
        val from = gas.at
        val next = gas.near
        val worldWay = if (gas.kind.rises) Direction.UP else Direction.DOWN
        val time = level.gameTime
        val keys = gas.chunkKeys
        keys.clear(); keys.addAll(gas.chunks.keys)
        for (k in 0 until keys.size) {
            val key = keys.getLong(k)
            if (key in gas.loading) continue
            val cells = gas.chunks.get(key) ?: continue
            val ship = level.getShipManagingPos(ChunkPos.getX(key), ChunkPos.getZ(key))
            val fall = if (ship == null) null else shipFall(gas, ship, time)
            if (fall != null && fall.down.lengthSquared() == 0.0) continue
            forRandomCells(level, gas, cells, SETTLE_PER_CHUNK) { i ->
                val way = if (fall == null) worldWay else pickFace(level, fall.down, gas.kind.rises)
                posOf(level, key, i, from)
                for (step in 0 until gas.kind.settleCells) {
                    next.setWithOffset(from, way)
                    if (!openHere(level, gas, next)) break
                    val here = amountAt(gas, level, from)
                    val there = amountAt(gas, level, next)
                    if (!gas.hasRoom(there)) break
                    val want = here * SETTLE_SHARE
                    val moved = minOf(want, MAX_AMOUNT - there - othersAt(level, gas, next)).coerceAtLeast(0f)
                    if (moved > 0f) {
                        set(gas, level, from, here - moved)
                        set(gas, level, next, there + moved)
                        noteMove(level, gas, from, next, moved / here)
                    }
                    if (want > moved) exchangeInto(level, gas, from, next, want - moved)
                    if (moved <= 0f) break
                    if (gas.kind.settleSpread > 0f) fanOut(level, gas, next, way)
                    from.set(next)
                }
            }
        }
        dropUnseenFalls(gas, time)
    }

    /** The settle direction of the gas of [ship] this tick, computed once for each ship and tick. */
    private fun shipFall(gas: LevelGas, ship: Ship, time: Long): ShipFall {
        val fall = gas.falls.get(ship.id) ?: ShipFall().also { gas.falls.put(ship.id, it) }
        if (fall.tick == time) return fall
        val d = fall.down
        // In the ship frame gas feels g − a. A ship first seen this tick has no previous velocity, so a = 0.
        if (fall.tick == time - 1) d.set(fall.lastVelocity).sub(ship.velocity).mul(TICKS_PER_SECOND) else d.zero()
        // VS2 velocity noise on a ship that hovers would otherwise tip its gas sideways.
        if (d.lengthSquared() < ACCEL_DEADBAND_SQ) d.zero()
        d.y -= GRAVITY
        fall.lastVelocity.set(ship.velocity)
        fall.tick = time
        if (d.lengthSquared() < FREE_FALL_SQ) d.zero() else ship.transform.shipToWorldRotation.transformInverse(d).normalize()
        return fall
    }

    /** One ship face for a settle step, each axis picked in proportion to its part of [down], so a tilted hold drains to its low side. */
    private fun pickFace(level: ServerLevel, down: Vector3d, rises: Boolean): Direction {
        val sign = if (rises) -1.0 else 1.0
        val ax = abs(down.x); val ay = abs(down.y); val az = abs(down.z)
        val r = level.random.nextDouble() * (ax + ay + az)
        return when {
            r < ax -> if (down.x * sign > 0.0) Direction.EAST else Direction.WEST
            r < ax + ay -> if (down.y * sign > 0.0) Direction.UP else Direction.DOWN
            else -> if (down.z * sign > 0.0) Direction.SOUTH else Direction.NORTH
        }
    }

    private fun dropUnseenFalls(gas: LevelGas, time: Long) {
        val it = gas.falls.long2ObjectEntrySet().fastIterator()
        while (it.hasNext()) if (it.next().value.tick != time) it.remove()
    }

    /** Gives [gas.kind.settleSpread] of the gas in [at] to its free neighbours across [way], and keeps the rest. */
    private fun fanOut(level: ServerLevel, gas: LevelGas, at: BlockPos, way: Direction) {
        val amount = amountAt(gas, level, at)
        if (amount <= 0f || othersAt(level, gas, at) > 0f) return
        val open = gas.openFaces
        var faces = 0
        for (f in FACES.indices) {
            if (FACES[f].axis == way.axis) continue
            gas.cell.setWithOffset(at, FACES[f])
            if (openHere(level, gas, gas.cell) && gas.hasRoom(amountAt(gas, level, gas.cell)) &&
                othersAt(level, gas, gas.cell) <= 0f
            ) open[faces++] = f
        }
        if (faces == 0) return
        val part = amount * gas.kind.settleSpread / faces
        var given = 0f
        for (n in 0 until faces) {
            gas.cell.setWithOffset(at, FACES[open[n]])
            val there = amountAt(gas, level, gas.cell)
            val add = minOf(part, MAX_AMOUNT - there)
            if (add <= 0f) continue
            set(gas, level, gas.cell, there + add)
            given += add
        }
        set(gas, level, at, amount - given)
    }

    /** Gas in [from] trades places with other kinds in [next], up to [most], so each cell keeps its total. Returns the amount traded. */
    private fun exchangeInto(level: ServerLevel, gas: LevelGas, from: BlockPos, next: BlockPos, most: Float): Float {
        var left = most
        for (otherKind in KINDS) {
            if (otherKind == gas.kind || left <= 0f) continue
            val other = gasIn(level, otherKind) ?: continue
            val otherThere = amountAt(other, level, next)
            val otherHere = amountAt(other, level, from)
            val swap = minOf(left, otherThere)
            if (swap <= 0f || !other.hasRoom(otherHere)) continue
            val ours = amountAt(gas, level, from)
            set(other, level, next, otherThere - swap)
            set(other, level, from, otherHere + swap)
            set(gas, level, from, ours - swap)
            set(gas, level, next, amountAt(gas, level, next) + swap)
            noteMove(level, gas, from, next, swap / ours)
            noteMove(level, other, next, from, swap / otherThere)
            left -= swap
        }
        return most - left
    }

    /** All kinds share one full cell, so this is the room that the other kinds take in [pos]. */
    private fun othersAt(level: ServerLevel, gas: LevelGas, pos: BlockPos): Float {
        var sum = 0f
        for (kind in KINDS) if (kind != gas.kind) gasIn(level, kind)?.let { sum += amountAt(it, level, pos) }
        return sum
    }

    private fun othersAtIndex(level: ServerLevel, gas: LevelGas, key: Long, i: Int): Float {
        var sum = 0f
        for (kind in KINDS) if (kind != gas.kind) gasIn(level, kind)?.let { sum += it.chunks.get(key)?.get(i) ?: 0f }
        return sum
    }

    /** Queues the cell for the pressure pass in every kind that has gas in it. */
    private fun markPressured(level: ServerLevel, key: Long, i: Int) {
        for (kind in KINDS) {
            val gas = gasIn(level, kind) ?: continue
            if (gas.chunks.get(key)?.containsKey(i) == true) gas.pressured.getOrPut(key) { IntOpenHashSet() }.add(i)
        }
    }

    /**
     * Cells where the gas of all kinds exceeds 1 push the excess out. Each open face takes a share of the gap to its
     * total, set by the excess, up to its room. Each kind moves its share of the total. The cell never drops below 1.
     */
    private fun pressure(level: ServerLevel, gas: LevelGas) {
        if (gas.pressured.isEmpty()) return
        val keys = gas.pressureKeys; val cells = gas.pressureCells
        keys.clear(); cells.clear()
        val chunks = gas.pressured.long2ObjectEntrySet().fastIterator()
        while (chunks.hasNext() && keys.size < PRESSURE_PER_TICK) {
            val entry = chunks.next()
            if (entry.longKey in gas.loading) continue
            val it = entry.value.iterator()
            while (it.hasNext() && keys.size < PRESSURE_PER_TICK) { keys.add(entry.longKey); cells.add(it.nextInt()) }
        }
        for (n in 0 until keys.size) relieve(level, gas, keys.getLong(n), cells.getInt(n))
    }

    private fun relieve(level: ServerLevel, gas: LevelGas, key: Long, i: Int) {
        val own = gas.chunks.get(key)?.get(i) ?: 0f
        val from = posOf(level, key, i, gas.at)
        val total = own + othersAt(level, gas, from)
        if (own <= 0f || total <= 1f || !level.isLoaded(from)) {
            unmarkPressured(gas, key, i)
            return
        }
        val pos = gas.near
        var faces = 0
        for (f in FACES.indices) {
            pos.setWithOffset(from, FACES[f])
            val there = amountAt(gas, level, pos)
            val open = openHere(level, gas, pos) && gas.hasRoom(there)
            gas.faceTotals[f] = if (open) there + othersAt(level, gas, pos) else -1f
            if (open) faces++
        }
        if (faces == 0) return
        val excess = total - 1f
        var sum = 0f
        for (f in FACES.indices) {
            val there = gas.faceTotals[f]
            val want = if (there < 0f || there >= total) 0f else PRESSURE_SHARE * excess * (total - there) / (faces + 1)
            gas.faceMoves[f] = minOf(want, MAX_AMOUNT - there).coerceAtLeast(0f)
            sum += gas.faceMoves[f]
        }
        if (sum <= 0f) return
        // The donor keeps what the room or the excess does not allow it to give.
        val scale = (if (sum > excess) excess / sum else 1f) * own / total
        var given = 0f
        for (f in FACES.indices) {
            val moved = gas.faceMoves[f] * scale
            if (moved <= 0f) continue
            pos.setWithOffset(from, FACES[f])
            val old = amountAt(gas, level, pos)
            set(gas, level, pos, old + moved)
            noteMove(level, gas, from, pos, moved / own)
            given += moved
        }
        set(gas, level, from, own - given)
    }

    private fun unmarkPressured(gas: LevelGas, key: Long, i: Int) {
        val set = gas.pressured.get(key) ?: return
        set.remove(i)
        if (set.isEmpty()) gas.pressured.remove(key)
    }

    /** The face of a ship cell that points nearest to world [way], so gas in a tilted ship settles along world up or down. */
    /** TEMP gas mass debug: the sum of all gas of [level] in the grid, all kinds. */
    fun totalMass(level: ServerLevel): Double {
        var sum = 0.0
        for (kind in KINDS) {
            val gas = gasIn(level, kind) ?: continue
            val chunks = gas.chunks.long2ObjectEntrySet().fastIterator()
            while (chunks.hasNext()) {
                val cells = chunks.next().value.values.iterator()
                while (cells.hasNext()) sum += cells.nextFloat()
            }
        }
        return sum
    }

    // Game thread only.
    private val skyPoint = Vector3d()
    private val roofCell = BlockPos.MutableBlockPos()

    /**
     * True when the cell sees the sky. A world cell needs no gas wall above it. A ship cell needs the world point above
     * it free, and no ship block above it in the ship column, up to [ROOF_SCAN] cells.
     */
    private fun disperses(level: ServerLevel, gas: LevelGas, pos: BlockPos): Boolean {
        if (!level.isBlockInShipyard(pos)) return openToSky(level, gas, pos.x, pos.y, pos.z)
        val ship = level.getShipManagingPos(pos) ?: return false
        ship.transform.shipToWorld.transformPosition(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, skyPoint)
        roofCell.set(skyPoint.x, skyPoint.y, skyPoint.z)
        if (!level.isLoaded(roofCell) || !openToSky(level, gas, roofCell.x, roofCell.y, roofCell.z)) return false
        val top = minOf(pos.y + ROOF_SCAN, ship.shipAABB?.maxY() ?: pos.y)
        for (y in pos.y + 1..top) if (!passes(level, roofCell.set(pos.x, y, pos.z))) return false
        return true
    }

    // canSeeSky reads sky light, and glass and leaves let it through, so a glass roof would count as open sky.
    private fun openToSky(level: ServerLevel, gas: LevelGas, x: Int, y: Int, z: Int): Boolean {
        val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
        if (top - y > ROOF_SCAN) return false
        if (!gas.caching) {
            for (cy in y + 1 until top) if (!passes(level, skyCell.set(x, cy, z))) return false
            return true
        }
        val column = BlockPos.asLong(x, 0, z)
        var block = gas.skyBlock.get(column)
        if (block == SKY_UNSCANNED) {
            block = Int.MIN_VALUE
            for (cy in top - 1 downTo top - ROOF_SCAN + 1) if (!passes(level, skyCell.set(x, cy, z))) { block = cy; break }
            gas.skyBlock.put(column, block)
        }
        return y >= block
    }

    // Game thread only.
    private val skyCell = BlockPos.MutableBlockPos()

    private fun amountAt(gas: LevelGas, level: ServerLevel, pos: BlockPos): Float =
        gas.chunks.get(key(pos))?.get(index(level, pos)) ?: 0f

    private fun amountAt(gas: LevelGas, key: Long, i: Int): Float = gas.chunks.get(key)?.get(i) ?: 0f

    private fun set(gas: LevelGas, level: ServerLevel, pos: BlockPos, amount: Float) {
        setAt(gas, level, pos, key(pos), index(level, pos), amount)
    }

    /** [set] with the chunk [key] and [i] of [pos] already known. Returns the amount the cell holds now. */
    @Suppress("LongParameterList") // A cell by position and by key and index, and its amount.
    private fun setAt(gas: LevelGas, level: ServerLevel, pos: BlockPos, key: Long, i: Int, amount: Float): Float {
        // Thin gas disperses only where it can see the sky; under a roof it stays, down to rounding dust.
        val value = if (amount < DUST || (amount < SPREAD_MIN && disperses(level, gas, pos))) 0f else minOf(MAX_AMOUNT, amount)
        if (value == 0f) {
            if ((gas.chunks.get(key)?.remove(i) ?: 0f) != 0f) gas.count--
            releaseFlow(gas, gas.flows.get(key)?.remove(i))
        } else {
            if (gas.chunks.getOrPut(key) { Int2FloatOpenHashMap() }.put(i, value) == 0f) gas.count++
            if (value + othersAtIndex(level, gas, key, i) > 1f) markPressured(level, key, i)
        }
        gas.changedIn(key).put(i, value)
        return value
    }

    private fun expose(level: ServerLevel, gas: LevelGas) {
        val kind = gas.kind
        if (!kind.poisons && kind.sickensAfterTicks <= 0) return
        val seen = gas.exposedNow
        seen.clear()
        val chunks = gas.chunks.long2ObjectEntrySet().fastIterator()
        while (chunks.hasNext()) {
            val entry = chunks.next()
            val key = entry.longKey
            var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
            val cells = entry.value.int2FloatEntrySet().fastIterator()
            while (cells.hasNext()) {
                val y = cells.next().intKey shr 8
                lo = minOf(lo, y); hi = maxOf(hi, y)
            }
            if (lo > hi) continue
            val x = ChunkPos.getX(key) * 16.0; val z = ChunkPos.getZ(key) * 16.0
            // Gas in a ship chunk reaches the entities near the ship in the world.
            val box = level.transformAabbToWorld(AABB(x, lo + level.minBuildHeight - 2.0, z, x + 16, hi + level.minBuildHeight + 1.0, z + 16))
            for (living in level.getEntitiesOfClass(LivingEntity::class.java, box) { it.isAlive }) {
                if (living.id in seen) continue
                // The gas that the entity breathes is at its eyes; amountNear reads the cell of a ship too.
                val a = amountNear(level, gas, BlockPos.containing(living.eyePosition))
                if (a < EXPOSE_MIN) continue
                seen.add(living.id)
                if (kind.poisons) poison(living, a)
                if (kind.sickensAfterTicks > 0) sicken(gas, living, a)
            }
        }
        gas.exposure.keys.retainAll(seen)
    }

    /** 1 per unit up to a full cell, then [EXCESS_WEIGHT] per unit above it. */
    private fun exposureWeight(a: Float): Float = minOf(a, 1f) + EXCESS_WEIGHT * maxOf(a - 1f, 0f)

    private fun poison(living: LivingEntity, a: Float) {
        if (!living.isAffectedByPotions || living.hasEffect(EKEffects.MANTLE_OF_POISON.get())) return
        val ticks = POISON_TICKS + Mth.floor(POISON_TICKS_PER_GAS * exposureWeight(a))
        val amplifier = if (a >= POISON_STRONG) 1 else 0
        living.addEffect(MobEffectInstance(EKEffects.ROT.get(), ticks, amplifier))
    }

    private fun sicken(gas: LevelGas, living: LivingEntity, a: Float) {
        val ticks = gas.exposure.get(living.id) + Mth.floor(EXPOSE_EVERY * (1f + EXCESS_WEIGHT * maxOf(a - 1f, 0f)))
        gas.exposure.put(living.id, ticks)
        if (ticks > gas.kind.sickensAfterTicks && living.isAffectedByPotions) {
            living.addEffect(MobEffectInstance(EKEffects.DRAKE_SICKNESS.get(), SICKNESS_TICKS, 0))
        }
    }
}

// vs-core does not save shipAABB: it grows from empty as each ship section loads, so a partial box pushes ship gas out.
internal fun Ship.voxelsLoaded(): Boolean = (this as? ServerShipInternal)?.areVoxelsFullyLoaded() != false
