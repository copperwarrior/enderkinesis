package org.shipwrights.enderkinesis.physics

import com.mojang.logging.LogUtils
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Matrix4dc
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.Ship

/**
 * One bit for each shipyard cell of a ship: 1 where the lattice ocean is.
 *
 * A cell is wet when the sea reaches it without a climb above the cell's own height. Water
 * goes from cell to cell through each face that the block collision shapes leave open, so an
 * open hull stays dry below its lowest rim and a slab or stair cell can hold water in its
 * open part. Ship-up is the direction of gravity for the flood.
 *
 * [Volume.wet] layout: word `(x, z + (y / 32) * depth)` holds 32 Y levels of one column.
 */
object LatticeHullVolume {
    private val LOG = LogUtils.getLogger()

    const val LEVELS_PER_WORD = 32

    /** Upper limit of the box volume. A larger ship gets no hull test. */
    private const val MAX_CELLS = 8_000_000L
    private const val MAX_VOLUMES = 64
    private const val EVICT_TICKS = 1200L
    private const val RESCAN_TICKS = 100L

    /** The largest gap in a block face, in 1/16 blocks, that is closed to water. A chest stays watertight. */
    private const val MAX_SEALED_GAP = 2
    private const val FACE_EPSILON = 1.0e-7

    private const val DOWN = 1
    private const val UP = 2
    private const val NORTH = 4
    private const val SOUTH = 8
    private const val WEST = 16
    private const val EAST = 32
    private const val ALL_FACES = 63

    class Volume internal constructor() {
        internal var minX = 0
        internal var minY = 0
        internal var minZ = 0
        internal var w = 0
        internal var h = 0
        internal var d = 0
        internal var faces = ByteArray(0)
        internal var generation = 0
        internal var needsScan = true
        internal var needsFlood = false
        internal var incomplete = false
        internal var lastScanTick = 0L
        internal var lastUsedTick = 0L
        @Volatile internal var flooding = false
        internal val pending = AtomicReference<Flooded?>(null)

        /** The flood result. A new array replaces it; nothing changes it in place. */
        var wet = IntArray(0)
            internal set
        var wetMinX = 0
            internal set
        var wetMinY = 0
            internal set
        var wetMinZ = 0
            internal set
        var wetW = 0
            internal set
        var wetH = 0
            internal set
        var wetD = 0
            internal set

        /** Different for each flood result of each volume. 0 before the first result. */
        var wetVersion = 0
            internal set

        internal fun contains(x: Int, y: Int, z: Int): Boolean =
            x - minX in 0 until w && y - minY in 0 until h && z - minZ in 0 until d

        internal fun index(x: Int, y: Int, z: Int): Int = ((y - minY) * d + (z - minZ)) * w + (x - minX)

        fun isWet(x: Int, y: Int, z: Int): Boolean {
            val lx = x - wetMinX
            val ly = y - wetMinY
            val lz = z - wetMinZ
            if (lx !in 0 until wetW || ly !in 0 until wetH || lz !in 0 until wetD) return true
            val word = wet[((ly / LEVELS_PER_WORD) * wetD + lz) * wetW + lx]
            return (word ushr (ly and (LEVELS_PER_WORD - 1))) and 1 != 0
        }
    }

    internal class Flooded(
        val wet: IntArray,
        val generation: Int,
        val minX: Int, val minY: Int, val minZ: Int,
        val w: Int, val h: Int, val d: Int,
    )

    // In singleplayer the server thread and the client thread use this object at the same
    // time, and a ship id is the same on the two sides.
    private class Side {
        val volumes = Long2ObjectOpenHashMap<Volume>()
        val tmpVec = Vector3d()
        var lastSweepTick = 0L
    }

    private val clientSide = Side()
    private val serverSide = Side()
    private val versions = AtomicInteger()
    private val faceCache = ConcurrentHashMap<BlockState, Byte>()

    // Not the mesh executor: a flood of a large ship would stall the 30 Hz wave builds.
    private val floodExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EK-lattice-hull-flood").apply { isDaemon = true }
    }

    private fun sideOf(isClient: Boolean): Side = if (isClient) clientSide else serverSide

    /** Returns the volume of [ship] when it has a flood result. Call from the level's own thread. */
    fun volumeFor(level: Level, ship: Ship): Volume? {
        val box = ship.shipAABB ?: return null
        val side = sideOf(level.isClientSide)
        val now = level.gameTime
        if (now - side.lastSweepTick >= EVICT_TICKS || now < side.lastSweepTick) sweep(side, now)
        var volume = side.volumes.get(ship.id)
        if (volume == null) {
            if (side.volumes.size >= MAX_VOLUMES) return null
            volume = Volume()
            side.volumes.put(ship.id, volume)
        }
        volume.lastUsedTick = now

        // One block of padding, then section alignment: the box changes only when the hull
        // goes into a new chunk section, and the outer shell is always air.
        val minX = Math.floorDiv(box.minX() - 1, 16) * 16
        val minY = Math.floorDiv(box.minY() - 1, 16) * 16
        val minZ = Math.floorDiv(box.minZ() - 1, 16) * 16
        val w = (Math.floorDiv(box.maxX() + 1, 16) + 1) * 16 - minX
        val h = (Math.floorDiv(box.maxY() + 1, 16) + 1) * 16 - minY
        val d = (Math.floorDiv(box.maxZ() + 1, 16) + 1) * 16 - minZ
        if (minX != volume.minX || minY != volume.minY || minZ != volume.minZ ||
            w != volume.w || h != volume.h || d != volume.d
        ) {
            volume.minX = minX; volume.minY = minY; volume.minZ = minZ
            volume.w = w; volume.h = h; volume.d = d
            volume.needsScan = true
        }
        if (w.toLong() * h.toLong() * d.toLong() > MAX_CELLS) return null

        if (volume.needsScan || (volume.incomplete && now - volume.lastScanTick >= RESCAN_TICKS)) {
            scan(level, volume)
            volume.needsScan = false
            volume.needsFlood = true
            volume.lastScanTick = now
            volume.generation++
        }
        volume.pending.getAndSet(null)?.let { publish(volume, it) }
        if (volume.needsFlood && !volume.flooding) startFlood(volume)
        return if (volume.wetVersion != 0) volume else null
    }

    /** True when the world point is in the ocean and not in a dry part of [ship]. */
    fun isWet(level: Level, ship: Ship, worldToShip: Matrix4dc, wx: Double, wy: Double, wz: Double): Boolean {
        val volume = volumeFor(level, ship) ?: return true
        val p = sideOf(level.isClientSide).tmpVec.set(wx, wy, wz)
        worldToShip.transformPosition(p)
        return volume.isWet(Math.floor(p.x).toInt(), Math.floor(p.y).toInt(), Math.floor(p.z).toInt())
    }

    @JvmStatic
    fun onBlockChanged(isClient: Boolean, pos: BlockPos, oldState: BlockState, newState: BlockState) {
        val volumes = sideOf(isClient).volumes
        if (volumes.isEmpty()) return
        val faces = faceBits(newState)
        if (faces == faceBits(oldState)) return
        for (volume in volumes.values) {
            if (volume.needsScan || !volume.contains(pos.x, pos.y, pos.z)) continue
            val i = volume.index(pos.x, pos.y, pos.z)
            if (i >= volume.faces.size) continue
            volume.faces[i] = faces
            volume.needsFlood = true
        }
    }

    @JvmStatic
    fun onClientChunkChanged(chunkX: Int, chunkZ: Int) {
        val volumes = clientSide.volumes
        if (volumes.isEmpty()) return
        val x = chunkX shl 4
        val z = chunkZ shl 4
        for (volume in volumes.values) {
            if (x - volume.minX in 0 until volume.w && z - volume.minZ in 0 until volume.d) volume.needsScan = true
        }
    }

    // A ship id can be the same in the next session.
    fun onClientDisconnect() = clientSide.volumes.clear()

    private fun sweep(side: Side, now: Long) {
        side.lastSweepTick = now
        val it = side.volumes.values.iterator()
        while (it.hasNext()) {
            val volume = it.next()
            val idle = now - volume.lastUsedTick
            if ((idle > EVICT_TICKS || idle < 0) && !volume.flooding) it.remove()
        }
    }

    private fun scan(level: Level, volume: Volume) {
        val cells = volume.w * volume.h * volume.d
        if (volume.faces.size != cells) volume.faces = ByteArray(cells) else volume.faces.fill(0)
        volume.incomplete = false
        val minSectionY = volume.minY shr 4
        val maxSectionY = (volume.minY + volume.h) shr 4
        for (cz in (volume.minZ shr 4) until ((volume.minZ + volume.d) shr 4)) {
            for (cx in (volume.minX shr 4) until ((volume.minX + volume.w) shr 4)) {
                val chunk = level.chunkSource.getChunkNow(cx, cz)
                if (chunk == null) {
                    volume.incomplete = true
                    continue
                }
                for (sy in minSectionY until maxSectionY) {
                    val sectionIndex = level.getSectionIndexFromSectionY(sy)
                    if (sectionIndex !in chunk.sections.indices) continue
                    val section = chunk.sections[sectionIndex]
                    if (!section.hasOnlyAir()) scanSection(volume, section, cx shl 4, sy shl 4, cz shl 4)
                }
            }
        }
    }

    private fun scanSection(volume: Volume, section: LevelChunkSection, baseX: Int, baseY: Int, baseZ: Int) {
        val faces = volume.faces
        for (y in 0 until 16) {
            for (z in 0 until 16) {
                val row = volume.index(baseX, baseY + y, baseZ + z)
                for (x in 0 until 16) {
                    val state = section.getBlockState(x, y, z)
                    if (!state.isAir) faces[row + x] = faceBits(state)
                }
            }
        }
    }

    /** The cell faces that the collision shape of [state] closes to water, one bit for each direction. */
    private fun faceBits(state: BlockState): Byte {
        if (state.isAir) return 0
        return faceCache[state] ?: computeFaceBits(state).also { faceCache[state] = it }
    }

    private fun computeFaceBits(state: BlockState): Byte = try {
        // Same inputs as the vanilla BlockState shape cache. A shape that needs its position is approximate.
        val shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        when {
            shape.isEmpty -> 0
            Block.isShapeFullBlock(shape) -> ALL_FACES
            else -> axisBits(shape, Direction.Axis.Y, Direction.DOWN, DOWN, Direction.UP, UP) or
                axisBits(shape, Direction.Axis.Z, Direction.NORTH, NORTH, Direction.SOUTH, SOUTH) or
                axisBits(shape, Direction.Axis.X, Direction.WEST, WEST, Direction.EAST, EAST)
        }.toByte()
    } catch (t: Throwable) {
        LOG.warn("[EK] no collision shape for {}; the hull flood uses a full block", state, t)
        ALL_FACES.toByte()
    }

    private fun axisBits(
        shape: VoxelShape, axis: Direction.Axis,
        negative: Direction, negativeBit: Int, positive: Direction, positiveBit: Int,
    ): Int {
        val bits = (if (sealed(shape, axis, negative)) negativeBit else 0) or
            (if (sealed(shape, axis, positive)) positiveBit else 0)
        // A wall in the middle of the cell (a glass pane) closes the two directions.
        return if (bits == 0 && sealed(shape, axis, null)) negativeBit or positiveBit else bits
    }

    /**
     * True when the shape, seen along [axis], has no hole that lets water through: no empty
     * square of [MAX_SEALED_GAP] + 1 cells on the 1/16 grid that vanilla shapes use. With
     * [face], only the boxes that touch that cell face count.
     */
    private fun sealed(shape: VoxelShape, axis: Direction.Axis, face: Direction?): Boolean {
        val covered = BooleanArray(256)
        // Not VoxelShape.getFaceShape: forAllBoxes on the slice of some shapes (brewing stand) throws.
        shape.forAllBoxes { x0, y0, z0, x1, y1, z1 ->
            val lo = axis.choose(x0, y0, z0)
            val hi = axis.choose(x1, y1, z1)
            val touches = when (face?.axisDirection) {
                null -> true
                Direction.AxisDirection.NEGATIVE -> lo <= FACE_EPSILON
                Direction.AxisDirection.POSITIVE -> hi >= 1.0 - FACE_EPSILON
            }
            if (touches) {
                val u0 = if (axis == Direction.Axis.X) y0 else x0
                val u1 = if (axis == Direction.Axis.X) y1 else x1
                val v0 = if (axis == Direction.Axis.Z) y0 else z0
                val v1 = if (axis == Direction.Axis.Z) y1 else z1
                for (u in gridCoord(u0) until gridCoord(u1)) {
                    for (v in gridCoord(v0) until gridCoord(v1)) covered[u * 16 + v] = true
                }
            }
        }
        val hole = MAX_SEALED_GAP + 1
        for (u in 0..16 - hole) {
            for (v in 0..16 - hole) {
                if (holeAt(covered, u, v, hole)) return false
            }
        }
        return true
    }

    private fun holeAt(covered: BooleanArray, u: Int, v: Int, size: Int): Boolean {
        for (du in 0 until size) {
            for (dv in 0 until size) {
                if (covered[(u + du) * 16 + v + dv]) return false
            }
        }
        return true
    }

    private fun gridCoord(value: Double): Int = Math.round(value * 16.0).toInt().coerceIn(0, 16)

    private fun startFlood(volume: Volume) {
        volume.flooding = true
        volume.needsFlood = false
        val faces = volume.faces.clone()
        val generation = volume.generation
        val minX = volume.minX; val minY = volume.minY; val minZ = volume.minZ
        val w = volume.w; val h = volume.h; val d = volume.d
        floodExecutor.execute {
            try {
                volume.pending.set(Flooded(flood(faces, w, h, d), generation, minX, minY, minZ, w, h, d))
            } catch (t: Throwable) {
                LOG.error("[EK] lattice hull flood failed", t)
            } finally {
                volume.flooding = false
            }
        }
    }

    private fun publish(volume: Volume, flooded: Flooded) {
        if (flooded.generation != volume.generation) return
        volume.wet = flooded.wet
        volume.wetMinX = flooded.minX; volume.wetMinY = flooded.minY; volume.wetMinZ = flooded.minZ
        volume.wetW = flooded.w; volume.wetH = flooded.h; volume.wetD = flooded.d
        volume.wetVersion = versions.incrementAndGet()
    }

    /**
     * Priority flood from the lowest level to the highest. A cell that the flood reaches at
     * its own level is wet. Each cell goes on the stack one time, so the work is linear in
     * the box volume.
     */
    private fun flood(faces: ByteArray, w: Int, h: Int, d: Int): IntArray {
        val layer = w * d
        val visited = LongArray((faces.size + 63) ushr 6)
        val wet = IntArray(layer * ((h + LEVELS_PER_WORD - 1) / LEVELS_PER_WORD))
        val stack = IntArrayList()

        fun enter(from: Int, to: Int, outBit: Int, inBit: Int) {
            if (faces[from].toInt() and outBit != 0 || faces[to].toInt() and inBit != 0) return
            val word = to ushr 6
            val bit = 1L shl (to and 63)
            if (visited[word] and bit != 0L) return
            visited[word] = visited[word] or bit
            stack.add(to)
        }

        // The bottom layer is padding, so all of it is open sea.
        for (i in 0 until layer) {
            visited[i ushr 6] = visited[i ushr 6] or (1L shl (i and 63))
            stack.add(i)
        }
        for (level in 0 until h) {
            // Water comes up from a cell below that an earlier level flooded.
            val base = level * layer
            if (level > 0) {
                for (i in base until base + layer) {
                    val below = i - layer
                    if ((visited[below ushr 6] ushr (below and 63)) and 1L != 0L) enter(below, i, UP, DOWN)
                }
            }
            while (!stack.isEmpty) {
                val i = stack.removeInt(stack.size - 1)
                val y = i / layer
                val rem = i - y * layer
                val z = rem / w
                val x = rem - z * w
                if (y == level) {
                    val word = ((y / LEVELS_PER_WORD) * d + z) * w + x
                    wet[word] = wet[word] or (1 shl (y and (LEVELS_PER_WORD - 1)))
                }
                if (x > 0) enter(i, i - 1, WEST, EAST)
                if (x < w - 1) enter(i, i + 1, EAST, WEST)
                if (z > 0) enter(i, i - w, NORTH, SOUTH)
                if (z < d - 1) enter(i, i + w, SOUTH, NORTH)
                if (y > 0) enter(i, i - layer, DOWN, UP)
                if (y < level) enter(i, i + layer, UP, DOWN)
            }
        }
        return wet
    }
}
