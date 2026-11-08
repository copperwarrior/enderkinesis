package org.shipwrights.enderkinesis.block

import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.HashCommon
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.block.WohlonnogondoniaWorldRootGrower.MarchingRoot
import org.shipwrights.enderkinesis.dimension.WohlonnogondoniaSurfaceRoots
import org.shipwrights.enderkinesis.dimension.WohlonnogondoniaSurfaceRoots.RootWalker
import org.shipwrights.enderkinesis.dimension.WohlonnogondoniaSurfaceRoots.TunnelPath
import org.shipwrights.enderkinesis.registry.EKBlocks
import java.util.BitSet
import java.util.UUID

/**
 * Rite of the Reaching Root: a root of any length from a ritual tree to the rite. It is walked one leg at a
 * time, when that leg's chunks are loaded, so each leg reads real ground and only two legs are held at once.
 */
object WohlonReachingRoot {

    private const val DATA_NAME = "enderkinesis_wohlon_reaching_roots"

    private const val LEG_STEPS = 32
    private const val MAX_LEGS = 2
    // Radius 3 keeps the leg's chunks within 2 block-ticking, so its buds mature.
    private const val TICKET_RADIUS = 3
    private const val TICKET_TIMEOUT_TICKS = 100
    private const val TICKET_REFRESH_TICKS = 40L
    private const val GHOST_SYNC_TICKS = 20L
    /** A leg that places nothing for this long while loaded is given up, and the next leg starts. */
    private const val LEG_STALL_TICKS = 1200L
    private const val MAX_TREE_DESCENT = 128

    private val TICKET: TicketType<ChunkPos> = TicketType.create(
        "enderkinesis_wohlon_reaching_root", Comparator.comparingLong(ChunkPos::toLong), TICKET_TIMEOUT_TICKS,
    )

    private class Leg(val index: Int, val path: TunnelPath, val march: MarchingRoot, var lastGrowthTick: Long) {
        val centre: ChunkPos = run {
            val mid = path.count / 2
            ChunkPos(path.points[mid * 3] shr 4, path.points[mid * 3 + 2] shr 4)
        }
        /** Players sent this leg's ghost. Not saved, so a reload sends it again. */
        val viewers: MutableSet<UUID> = HashSet()
    }

    private class Reach(val target: Long, val walker: RootWalker, var nextLeg: Int) {
        val legs = ArrayDeque<Leg>()
        var pending: ChunkPos? = null
        var ticketRefreshAt = 0L
        // Homing reaches the target well inside the taper estimate; this only bounds a walk that never does.
        val planned: Boolean get() = walker.arrived || walker.step >= walker.taperSteps * 3 + 64
    }

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tick)
    }

    fun start(level: ServerLevel, tree: BlockPos, target: BlockPos) {
        // The Heart Candle sits two below the portal, on the ground.
        val walker = WohlonnogondoniaSurfaceRoots.startReachingWalker(
            tree.x, tree.z,
            tree.x + 0.5, (tree.y - 3).toDouble(), tree.z + 0.5,
            target.x + 0.5, target.z + 0.5,
        )
        val data = getData(level)
        data.reaches.add(Reach(target.asLong(), walker, 0))
        data.setDirty()
    }

    private fun tick(level: ServerLevel) {
        val data = level.dataStorage.get({ tag -> ReachData.load(tag) }, DATA_NAME) ?: return
        if (data.reaches.isEmpty()) return
        data.reaches.removeIf { tickReach(level, it) }
        data.setDirty()
    }

    /** True when the root is complete. */
    private fun tickReach(level: ServerLevel, reach: Reach): Boolean {
        holdArea(level, reach)
        planLeg(level, reach)
        val leg = reach.legs.firstOrNull() ?: return reach.planned
        val result = WohlonnogondoniaWorldRootGrower.tickRoot(level, leg.march)
        if (result.changed) leg.lastGrowthTick = level.gameTime
        if (result.completed || level.gameTime - leg.lastGrowthTick > LEG_STALL_TICKS) {
            syncLeg(level, reach, leg, done = true)
            reach.legs.removeFirst()
            return reach.legs.isEmpty() && reach.planned
        }
        if (level.gameTime % GHOST_SYNC_TICKS == 0L) {
            for (l in reach.legs) syncLeg(level, reach, l, done = false)
        }
        return false
    }

    private fun holdArea(level: ServerLevel, reach: Reach) {
        if (level.gameTime < reach.ticketRefreshAt) return
        reach.ticketRefreshAt = level.gameTime + TICKET_REFRESH_TICKS
        for (leg in reach.legs) addTicket(level, leg.centre)
        reach.pending?.let { addTicket(level, it) }
    }

    private fun addTicket(level: ServerLevel, centre: ChunkPos) =
        level.chunkSource.addRegionTicket(TICKET, centre, TICKET_RADIUS, centre)

    private fun planLeg(level: ServerLevel, reach: Reach) {
        if (reach.planned || reach.legs.size >= MAX_LEGS) return
        val walker = reach.walker
        val centre = reach.pending ?: aheadCentre(walker).also {
            reach.pending = it
            addTicket(level, it)
        }
        if (!isAreaLoaded(level, centre)) return
        reach.pending = null

        val anchor = BlockPos(Math.floor(walker.x).toInt(), Math.round(walker.y).toInt(), Math.floor(walker.z).toInt())
        val heights = Long2IntOpenHashMap()
        val heightAt: (Int, Int) -> Int = { wx, wz ->
            val key = ChunkPos.asLong(wx, wz)
            if (heights.containsKey(key)) heights.get(key)
            else groundUnderTrees(level, wx, wz, anchor.y).also { heights.put(key, it) }
        }
        val yMin = level.minBuildHeight + 2
        val yMax = level.maxBuildHeight - 2
        val path = walker.walk(LEG_STEPS, heightAt, yMin, yMax, groundOnly = false, waterTopY = level.seaLevel - 1)
        if (path.count == 0) return
        val voxelSet = HashSet<Long>(2048)
        WohlonnogondoniaSurfaceRoots.paintPath(path) { x, y, z -> if (y in yMin..yMax) voxelSet.add(BlockPos.asLong(x, y, z)) }
        if (voxelSet.isEmpty()) return

        val march = MarchingRoot(
            regionPacked = WohlonnogondoniaWorldRootGrower.REACHING_REGION,
            anchorX = anchor.x, anchorY = anchor.y, anchorZ = anchor.z,
            voxels = WohlonnogondoniaWorldRootGrower.sortedOutward(voxelSet, anchor.x, anchor.y, anchor.z),
            placed = BitSet(voxelSet.size),
            scanCursor = 0,
            reaching = true,
        )
        val leg = Leg(reach.nextLeg++, path, march, level.gameTime)
        reach.legs.addLast(leg)
        addTicket(level, leg.centre)
    }

    private fun aheadCentre(walker: RootWalker): ChunkPos {
        val dx = walker.targetX - walker.x
        val dz = walker.targetZ - walker.z
        val dist = Math.sqrt(dx * dx + dz * dz).coerceAtLeast(1.0e-3)
        val ahead = minOf(LEG_STEPS * WohlonnogondoniaSurfaceRoots.TUNNEL_STEP_LEN * 0.5, dist * 0.5)
        return ChunkPos(Math.floor(walker.x + dx / dist * ahead).toInt() shr 4, Math.floor(walker.z + dz / dist * ahead).toInt() shr 4)
    }

    /** `getChunkNow`, not `hasChunk`: `hasChunk` is true for a chunk that still generates. */
    private fun isAreaLoaded(level: ServerLevel, centre: ChunkPos): Boolean {
        for (cx in centre.x - TICKET_RADIUS..centre.x + TICKET_RADIUS) {
            for (cz in centre.z - TICKET_RADIUS..centre.z + TICKET_RADIUS) {
                if (level.chunkSource.getChunkNow(cx, cz) == null) return false
            }
        }
        return true
    }

    /** Top of the ground under any canopy or trunk; [fallback] for a column whose chunk is not loaded. */
    @Suppress("DEPRECATION") // BlockState.blocksMotion has no 1.20.1 replacement.
    private fun groundUnderTrees(level: ServerLevel, wx: Int, wz: Int, fallback: Int): Int {
        val chunk = level.chunkSource.getChunkNow(wx shr 4, wz shr 4) ?: return fallback
        val pos = BlockPos.MutableBlockPos(wx, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, wx and 15, wz and 15), wz)
        var steps = 0
        while (steps < MAX_TREE_DESCENT && pos.y > level.minBuildHeight) {
            val state = chunk.getBlockState(pos)
            if (state.blocksMotion() && !isTreePart(state)) break
            pos.move(Direction.DOWN)
            steps++
        }
        return pos.y
    }

    private fun isTreePart(state: BlockState): Boolean =
        state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.LEAVES) ||
            state.`is`(EKBlocks.WOGOR_WOOD.get()) || state.`is`(EKBlocks.WOGOR_BUD.get()) ||
            state.`is`(EKBlocks.HEART_OF_THE_WILD.get())

    /** The marching leg sends its front; a leg ahead of it shows whole; a player who comes near gets the shape first. */
    private fun syncLeg(level: ServerLevel, reach: Reach, leg: Leg, done: Boolean) {
        val key = HashCommon.mix(reach.target + leg.index)
        val front = when {
            done -> WohlonRitualNetwork.ROOT_DONE
            leg === reach.legs.first() -> frontDistance(leg.march)
            else -> 0f
        }
        var segments: FloatArray? = null
        for (player in level.players()) {
            if (!isNearLeg(player.x, player.z, leg)) {
                leg.viewers.remove(player.uuid)
                continue
            }
            if (!done && leg.viewers.add(player.uuid)) {
                val shape = segments ?: ghostSegments(leg).also { segments = it }
                val m = leg.march
                WohlonRitualNetwork.sendRootGhost(player, key, BlockPos(m.anchorX, m.anchorY, m.anchorZ), shape)
            }
            WohlonRitualNetwork.sendRootFront(player, key, front)
        }
    }

    // The voxels sort outward from the anchor, so the farthest placed one marks the front.
    private fun frontDistance(march: MarchingRoot): Float {
        val last = march.placed.length() - 1
        if (last < 0) return 0f
        val pos = march.voxels[last]
        val dx = (BlockPos.getX(pos) - march.anchorX).toDouble()
        val dy = (BlockPos.getY(pos) - march.anchorY).toDouble()
        val dz = (BlockPos.getZ(pos) - march.anchorZ).toDouble()
        return Math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }

    private fun isNearLeg(x: Double, z: Double, leg: Leg): Boolean {
        val ax = leg.march.anchorX + 0.5
        val az = leg.march.anchorZ + 0.5
        val last = (leg.path.count - 1) * 3
        val lx = leg.path.points[last] + 0.5 - ax
        val lz = leg.path.points[last + 2] + 0.5 - az
        val t = (((x - ax) * lx + (z - az) * lz) / (lx * lx + lz * lz).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        val dx = x - (ax + lx * t)
        val dz = z - (az + lz * t)
        return dx * dx + dz * dz <= WohlonRitualNetwork.SEND_RANGE * WohlonRitualNetwork.SEND_RANGE
    }

    /** Segments from the anchor through each path point, relative to the anchor. Points are voxel corners, so add half a block. */
    private fun ghostSegments(leg: Leg): FloatArray {
        val stride = WohlonnogondoniaTreeGrower.GHOST_SEGMENT_FLOATS
        val path = leg.path
        val pts = path.points
        val m = leg.march
        val out = FloatArray(path.count * stride)
        for (i in 0 until path.count) {
            val b = i * stride
            if (i == 0) {
                out[b] = 0.5f; out[b + 1] = 0.5f; out[b + 2] = 0.5f
                out[b + 6] = path.startRadius.toFloat()
            } else {
                out[b] = pts[(i - 1) * 3] - m.anchorX + 0.5f
                out[b + 1] = pts[(i - 1) * 3 + 1] - m.anchorY + 0.5f
                out[b + 2] = pts[(i - 1) * 3 + 2] - m.anchorZ + 0.5f
                out[b + 6] = WohlonnogondoniaSurfaceRoots.radiusAt(path, i - 1).toFloat()
            }
            out[b + 3] = pts[i * 3] - m.anchorX + 0.5f
            out[b + 4] = pts[i * 3 + 1] - m.anchorY + 0.5f
            out[b + 5] = pts[i * 3 + 2] - m.anchorZ + 0.5f
            out[b + 7] = WohlonnogondoniaSurfaceRoots.radiusAt(path, i).toFloat()
            out[b + 8] = 1f
        }
        return out
    }

    private fun getData(level: ServerLevel): ReachData =
        level.dataStorage.computeIfAbsent({ tag -> ReachData.load(tag) }, { ReachData() }, DATA_NAME)

    private class ReachData : SavedData() {
        val reaches: MutableList<Reach> = mutableListOf()

        override fun save(tag: CompoundTag): CompoundTag {
            val list = ListTag()
            for (reach in reaches) {
                val t = CompoundTag()
                t.putLong("target", reach.target)
                t.putInt("nextLeg", reach.nextLeg)
                t.put("walker", saveWalker(reach.walker))
                val legs = ListTag()
                for (leg in reach.legs) legs.add(saveLeg(leg))
                t.put("legs", legs)
                list.add(t)
            }
            tag.put("reaches", list)
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): ReachData {
                val data = ReachData()
                val list = tag.getList("reaches", Tag.TAG_COMPOUND.toInt())
                for (i in 0 until list.size) {
                    val t = list.getCompound(i)
                    val reach = Reach(t.getLong("target"), loadWalker(t.getCompound("walker")), t.getInt("nextLeg"))
                    val legs = t.getList("legs", Tag.TAG_COMPOUND.toInt())
                    for (j in 0 until legs.size) loadLeg(legs.getCompound(j))?.let(reach.legs::addLast)
                    data.reaches.add(reach)
                }
                return data
            }
        }
    }

    private fun saveWalker(w: RootWalker): CompoundTag {
        val t = CompoundTag()
        t.putIntArray("seed", intArrayOf(w.regionX, w.regionZ, w.pathIdx, w.taperSteps, w.step, w.archLeft, w.archLen, w.groundLeft))
        t.putLongArray(
            "pos",
            doubleArrayOf(w.x, w.y, w.z, w.yaw, w.pitch, w.startRadius, w.targetX, w.targetZ, w.archStartY, w.archEndY, w.archHeight)
                .map { it.toRawBits() }.toLongArray(),
        )
        t.putBoolean("arrived", w.arrived)
        return t
    }

    private fun loadWalker(t: CompoundTag): RootWalker {
        val i = t.getIntArray("seed")
        val d = t.getLongArray("pos").map(Double::fromBits)
        return RootWalker(i[0], i[1], i[2], d[0], d[1], d[2], d[3], d[4], i[3], d[5], d[6], d[7]).apply {
            step = i[4]
            archLeft = i[5]
            archLen = i[6]
            groundLeft = i[7]
            archStartY = d[8]
            archEndY = d[9]
            archHeight = d[10]
            arrived = t.getBoolean("arrived")
        }
    }

    private fun saveLeg(leg: Leg): CompoundTag {
        val t = CompoundTag()
        val m = leg.march
        t.putInt("index", leg.index)
        t.putIntArray("path", leg.path.points.copyOf(leg.path.count * 3))
        t.putDouble("r0", leg.path.startRadius)
        t.putDouble("r1", leg.path.endRadius)
        t.putIntArray("anchor", intArrayOf(m.anchorX, m.anchorY, m.anchorZ))
        t.putLongArray("v", m.voxels)
        t.putLongArray("placed", m.placed.toLongArray())
        t.putInt("cursor", m.scanCursor)
        t.putBoolean("seed", m.hasSeed)
        t.putLong("grew", leg.lastGrowthTick)
        return t
    }

    private fun loadLeg(t: CompoundTag): Leg? {
        val points = t.getIntArray("path")
        val voxels = t.getLongArray("v")
        val anchor = t.getIntArray("anchor")
        if (points.isEmpty() || voxels.isEmpty() || anchor.size != 3) return null
        val march = MarchingRoot(
            regionPacked = WohlonnogondoniaWorldRootGrower.REACHING_REGION,
            anchorX = anchor[0], anchorY = anchor[1], anchorZ = anchor[2],
            voxels = voxels,
            placed = BitSet.valueOf(t.getLongArray("placed")),
            scanCursor = t.getInt("cursor"),
            hasSeed = t.getBoolean("seed"),
            reaching = true,
        )
        val path = TunnelPath(points, points.size / 3, t.getDouble("r0"), t.getDouble("r1"))
        return Leg(t.getInt("index"), path, march, t.getLong("grew"))
    }
}
