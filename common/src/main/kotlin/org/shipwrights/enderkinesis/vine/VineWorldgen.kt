package org.shipwrights.enderkinesis.vine

import kotlin.math.min
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.duck.WorldgenVineHolder
import org.valkyrienskies.core.api.chains.ChainEndpoint
import org.valkyrienskies.core.api.util.GameTickOnly

private const val MAX_ROOM_SCAN = 384
/** A loop whose anchor moved this much nearer its other end is too tight to hang; it is not made. */
private const val LOOP_MIN_SLACK = 1.02

/** Reads the cells a planned vine needs: solid like the anchor rule in releaseIfLost, open like the air a rope hangs in. */
internal interface VineProbe {
    fun solid(x: Int, y: Int, z: Int): Boolean
    fun open(x: Int, y: Int, z: Int): Boolean
    val minY: Int
}

internal class BlockVineProbe(private val blocks: BlockGetter, override val minY: Int) : VineProbe {
    private val pos = BlockPos.MutableBlockPos()

    override fun solid(x: Int, y: Int, z: Int): Boolean {
        pos.set(x, y, z)
        return !blocks.getBlockState(pos).getCollisionShape(blocks, pos).isEmpty
    }

    override fun open(x: Int, y: Int, z: Int): Boolean {
        pos.set(x, y, z)
        val state = blocks.getBlockState(pos)
        return state.fluidState.isEmpty && state.getCollisionShape(blocks, pos).isEmpty
    }
}

/** The planned voxels of a tree that is not grown yet; only for the snap, so no cell counts as open ground. */
internal class VoxelVineProbe(private val sets: List<Set<Long>>) : VineProbe {
    override val minY: Int get() = Int.MIN_VALUE
    override fun solid(x: Int, y: Int, z: Int): Boolean {
        val key = BlockPos.asLong(x, y, z)
        return sets.any { key in it }
    }
    override fun open(x: Int, y: Int, z: Int): Boolean = !solid(x, y, z)
}

internal class RealVine(val start: VinePoint, val end: VinePoint?, val nodes: DoubleArray, val rest: Double)

/** The anchor block nearest [y] in reach [snap] that is solid with an open cell under it, or null. */
internal fun snapUnderside(probe: VineProbe, x: Int, y: Int, z: Int, snap: Int): Int? {
    for (d in 0..snap) {
        if (probe.solid(x, y - d, z) && probe.open(x, y - d - 1, z)) return y - d
        if (d > 0 && probe.solid(x, y + d, z) && probe.open(x, y + d - 1, z)) return y + d
    }
    return null
}

private fun openRoom(probe: VineProbe, x: Int, y: Int, z: Int): Int {
    var yy = y - 1
    var steps = 0
    while (yy >= probe.minY && probe.open(x, yy, z) && steps < MAX_ROOM_SCAN) {
        yy--
        steps++
    }
    return y - (yy + 1)
}

/** The rope point under the anchor block: its bottom face, at the column centre. */
internal fun anchorPoint(x: Int, y: Int, z: Int) = VinePoint(BlockPos(x, y, z), Vector3d(x + 0.5, y.toDouble(), z + 0.5), null)

/**
 * The plan made real against the blocks: the home anchor snaps to the real underside, and a strand
 * stops [VineGenRules.FLOOR_GAP] above the first cell that is not open, so it starts at rest.
 */
internal fun realiseVine(plan: PlannedVine, probe: VineProbe): RealVine? {
    val y = snapUnderside(probe, plan.ax, plan.ay, plan.az, plan.snap) ?: return null
    val start = anchorPoint(plan.ax, y, plan.az)
    if (plan.kind == PlannedKind.LOOP) {
        val dx = plan.bx - plan.ax.toDouble(); val dy = plan.by - y.toDouble(); val dz = plan.bz - plan.az.toDouble()
        if (plan.length < Math.sqrt(dx * dx + dy * dy + dz * dz) * LOOP_MIN_SLACK) return null
        val nodes = catenaryNodes(
            plan.ax + 0.5, y.toDouble(), plan.az + 0.5, plan.bx + 0.5, plan.by.toDouble(), plan.bz + 0.5, plan.length, VINE_TARGET_SPACING,
        )
        return RealVine(start, anchorPoint(plan.bx, plan.by, plan.bz), nodes, polylineLength(nodes))
    }
    val room = openRoom(probe, plan.ax, y, plan.az) - VineGenRules.FLOOR_GAP
    val length = if (plan.kind == PlannedKind.DROP) room else min(plan.length, room)
    if (length < plan.minLength) return null
    val nodes = strandNodes(plan.ax + 0.5, y.toDouble(), plan.az + 0.5, length, VINE_TARGET_SPACING)
    return RealVine(start, null, nodes, polylineLength(nodes))
}

internal fun RealVine.toRecord(id: Long) = VineRecord(
    id, WoggakVineAnchor(start), WoggakVineAnchor(end), -1L, fromBreak = false, dropsOnFade = true,
    restLength = rest, positions = nodes, brokenLinks = IntArray(0), chain = 0L,
)

/** Adds the records of [plans] whose home anchor is in [chunk]; it reads only [chunk]'s own blocks, so any C2ME worker can run it. */
internal fun worldgenVineRecords(chunk: ChunkAccess, plans: List<PlannedVine>, out: MutableList<VineRecord>) {
    if (plans.isEmpty()) return
    val probe = BlockVineProbe(chunk, chunk.minBuildHeight)
    for (plan in plans) realiseVine(plan, probe)?.let { out += it.toRecord(plan.id) }
}

/** Gives [chunk] the records that the chunk load turns into chains; see adoptWorldgenVines. */
internal fun ChunkAccess.putWorldgenVines(records: List<VineRecord>) {
    if (records.isNotEmpty() && this is WorldgenVineHolder) `enderkinesis$setWorldgenVines`(records)
}

internal enum class PlanResult { MADE, WAIT, DROP }

/** Makes [plan]'s vine now, as a live chain. [waitOn] is true for an anchor block that will become solid later. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.makePlannedVine(plan: PlannedVine, waitOn: (BlockState) -> Boolean): PlanResult {
    val a = BlockPos(plan.ax, plan.ay, plan.az)
    val b = if (plan.kind == PlannedKind.LOOP) BlockPos(plan.bx, plan.by, plan.bz) else null
    if (!isLoaded(a) || (b != null && !isLoaded(b))) return PlanResult.WAIT
    if (waitOn(getBlockState(a)) || (b != null && waitOn(getBlockState(b)))) return PlanResult.WAIT
    if (plan.id != 0L && woggakVines.vine(plan.id) != null) return PlanResult.DROP
    val probe = BlockVineProbe(this, minBuildHeight)
    if (b != null && !probe.solid(b.x, b.y, b.z)) return PlanResult.DROP
    val real = realiseVine(plan, probe) ?: return PlanResult.DROP
    val path = ArrayList<Vector3dc>(real.nodes.size / 3)
    for (i in 0 until real.nodes.size / 3) path += Vector3d(real.nodes[i * 3], real.nodes[i * 3 + 1], real.nodes[i * 3 + 2])
    val end = real.end?.let { ChainEndpoint.World(Vector3d(it.local)) } ?: ChainEndpoint.Free
    val chain = makeVineChain(path, ChainEndpoint.World(Vector3d(real.start.local)), end) ?: return PlanResult.DROP
    addVine(WoggakVine(if (plan.id != 0L) plan.id else newVineId(), chain.id, WoggakVineAnchor(real.start), WoggakVineAnchor(real.end)), path)
    return PlanResult.MADE
}

/** [plan] with its anchor fixed at [y]: no snap is left to do. */
internal fun PlannedVine.fixedAt(y: Int) = PlannedVine(kind, ax, y, az, length, 0, minLength, bx, by, bz, id)

internal fun PlannedVine.save(): CompoundTag = CompoundTag().apply {
    putByte("kind", kind.ordinal.toByte())
    putLong("a", BlockPos.asLong(ax, ay, az))
    putDouble("length", length)
    putByte("snap", snap.toByte())
    putDouble("min", minLength)
    if (kind == PlannedKind.LOOP) putLong("b", BlockPos.asLong(bx, by, bz))
    putLong("vine", this@save.id)
}

internal fun loadPlannedVine(tag: CompoundTag): PlannedVine? {
    val kind = PlannedKind.entries.getOrNull(tag.getByte("kind").toInt()) ?: return null
    val length = tag.getDouble("length")
    if (!length.isFinite() || length <= 0) return null
    val a = tag.getLong("a")
    val b = tag.getLong("b")
    return PlannedVine(
        kind, BlockPos.getX(a), BlockPos.getY(a), BlockPos.getZ(a), length, tag.getByte("snap").toInt(), tag.getDouble("min"),
        BlockPos.getX(b), BlockPos.getY(b), BlockPos.getZ(b), tag.getLong("vine"),
    )
}
