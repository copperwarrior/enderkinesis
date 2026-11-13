package org.shipwrights.enderkinesis.vine

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.chains.Chain
import org.valkyrienskies.core.api.particles.ParticleId
import org.valkyrienskies.core.api.particles.QueryableParticleData
import org.valkyrienskies.core.api.particles.VsParticle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Half the side of the vine's collision cubes; the same as the drawn box. */
const val VINE_HALF_WIDTH = 2.0 / 16

/** Half the side of the knot at a vine end joined to another vine; drawn and solid. */
const val VINE_KNOT_HALF = 3.0 / 16

/** Cubes stay closed on a link up to 2 * VINE_HALF_WIDTH * this = 8 blocks long, 20 times the rest spacing. */
internal const val MAX_CUBES_PER_LINK = 32

/** How far a link top may be under the feet and still carry, in blocks: a vine falling at up to 6 m/s. */
private const val STAND_BELOW = 0.3
/** How far a link top may be over the feet and still count as under them, in blocks; past this it shoves. */
private const val STAND_ABOVE = VINE_HALF_WIDTH
/** Link speed in blocks per second under which a link is at rest and shoves nothing. */
private const val MIN_SHOVE_SPEED = 0.01
/** Chain frames can come late, so one tick can hold this many ticks of link motion. */
private const val MAX_FRAME_STEPS = 2.0
/** Past terminal fall speed, in blocks per tick: a larger vine move is a blow-up. */
private const val MAX_PLATFORM_MOVE = 4.0
private const val MIN_PLATFORM_MOVE = 1e-6
/** The same tolerance as vanilla shape collision, so faces that only touch do not overlap. */
private const val OVERLAP_EPS = 1e-7
private const val DIRECTION_EPS = 1e-9
/** The region of vanilla's ServerGamePacketListenerImpl.noBlocksAround: the box widened by this, and this much deeper. */
private const val FLOAT_CHECK_INFLATE = 0.0625
private const val FLOAT_CHECK_DEPTH = 0.55
/** How near a vine an uncarried entity still counts as on it, in blocks; an onGround flicker there gives no fling. */
private const val LEAVE_INFLATE = 0.0625
/** The bounds hold the cube half width; a pick hits up to SHEAR_RADIUS from a link. */
private const val PICK_BOUNDS_INFLATE = SHEAR_RADIUS - VINE_HALF_WIDTH
private const val VELOCITY_UNREAD: Byte = 0
private const val VELOCITY_MISSING: Byte = 1
private const val VELOCITY_READ: Byte = 2

/** Vines that one entity passes through: no cube of them collides with it, carries it, lifts it or shoves it. */
fun interface VinePass {
    fun passes(chainId: Long): Boolean
}

/**
 * Node positions of all vines in one level, copied once a tick, for entity collision.
 * The arrays grow and are then used again, so a tick allocates nothing in the steady state.
 */
class VineCollider {
    private var nodes = DoubleArray(0)
    /** For each node: true when the link from it to the next node is broken. */
    private var gaps = BooleanArray(0)
    private var ranges = IntArray(0)
    private var bounds = DoubleArray(0)
    /** For each vine: its JOINED_START and JOINED_END bits, which put a knot cube on that end node. */
    private var knots = IntArray(0)
    /** For each vine: System.nanoTime when its copied chain frame came in; 0 when not known. */
    private var frameArrivals = LongArray(0)
    private val chains = ArrayList<Chain>()
    private var vineCount = 0
    private var nodeCount = 0
    // VS2 copies a vector for each velocity read and a list for each nodeIds read: read only near entities, once a tick.
    private var velocities = DoubleArray(0)
    private var velocityState = ByteArray(0)
    private var nodeIdLists = arrayOfNulls<List<ParticleId>>(0)
    private val platform = Vector3d()
    private val motion = Vector3d()
    private val cubeVelocity = Vector3d()
    private val supportPoint = Vector3d()

    /** The VS2 particles of this level, for node velocities; set by the tick that fills the collider. */
    var particles: QueryableParticleData<VsParticle>? = null

    val isEmpty: Boolean get() = vineCount == 0

    fun clear() {
        vineCount = 0
        nodeCount = 0
        chains.clear()
    }

    /** Copies [chain]'s nodes; returns false when a position is not finite, which is a blown-up chain. */
    fun add(chain: Chain, joinedEnds: Int): Boolean {
        val n = chain.status.nodeCount
        if (n < 2) return true
        ensure(nodeCount + n, vineCount + 1)
        if (chain.copyNodePositions(nodes, nodeCount * 3) != n) return true
        return commit(chain, n, joinedEnds, 0L)
    }

    /**
     * As [add], but with [count] node positions from [positions]: the client gives the shape that it draws, from the
     * chain frame that came in at [frameArrival] (System.nanoTime).
     */
    fun add(chain: Chain, positions: DoubleArray, count: Int, joinedEnds: Int, frameArrival: Long): Boolean {
        require(positions.size >= count * 3) { "Vine positions hold fewer than $count nodes" }
        if (count < 2) return true
        ensure(nodeCount + count, vineCount + 1)
        positions.copyInto(nodes, nodeCount * 3, 0, count * 3)
        return commit(chain, count, joinedEnds, frameArrival)
    }

    /** Nanoseconds from the arrival of vine [chainId]'s copied chain frame to [now]; 0 when not known. */
    fun frameAgeNanos(chainId: Long, now: Long): Long {
        val v = indexOf(chainId)
        if (v < 0 || frameArrivals[v] == 0L) return 0L
        return (now - frameArrivals[v]).coerceAtLeast(0L)
    }

    private fun commit(chain: Chain, n: Int, joinedEnds: Int, frameArrival: Long): Boolean {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (i in 0 until n) {
            val k = (nodeCount + i) * 3
            val x = nodes[k]; val y = nodes[k + 1]; val z = nodes[k + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
            minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
            maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
            gaps[nodeCount + i] = i == n - 1
            velocityState[nodeCount + i] = VELOCITY_UNREAD
        }
        nodeIdLists[vineCount] = null
        val broken = chain.status.brokenLinks
        if (broken.isNotEmpty()) for (link in broken) if (link in 0 until n) gaps[nodeCount + link] = true
        knots[vineCount] = joinedEnds and (JOINED_START or JOINED_END)
        frameArrivals[vineCount] = frameArrival
        val pad = if (knots[vineCount] != 0) VINE_KNOT_HALF else VINE_HALF_WIDTH
        val b = vineCount * 6
        bounds[b] = minX - pad; bounds[b + 1] = minY - pad; bounds[b + 2] = minZ - pad
        bounds[b + 3] = maxX + pad; bounds[b + 4] = maxY + pad; bounds[b + 5] = maxZ + pad
        ranges[vineCount * 2] = nodeCount
        ranges[vineCount * 2 + 1] = nodeCount + n
        chains.add(chain)
        vineCount++
        nodeCount += n
        return true
    }

    /**
     * The nearest unbroken link along the ray, closer than [maxT]: its chain id and nearer node go into [out].
     * Returns false when no link is there.
     */
    fun pick(origin: Vector3d, dir: Vector3d, maxT: Double, out: VineNodeRef): Boolean {
        var bestT = maxT
        var found = false
        for (v in 0 until vineCount) {
            if (!rayHitsVine(v, origin, dir, bestT)) continue
            for (i in ranges[v * 2] until ranges[v * 2 + 1]) {
                if (gaps[i]) continue
                val t = nodes.rayHitsSegment(origin, dir, i * 3, bestT)
                if (t < 0) continue
                bestT = t
                val k = i * 3
                val hx = origin.x + dir.x * t; val hy = origin.y + dir.y * t; val hz = origin.z + dir.z * t
                val d0 = sq(nodes[k] - hx) + sq(nodes[k + 1] - hy) + sq(nodes[k + 2] - hz)
                val d1 = sq(nodes[k + 3] - hx) + sq(nodes[k + 4] - hy) + sq(nodes[k + 5] - hz)
                out.chainId = chains[v].id.value
                out.node = i - ranges[v * 2] + (if (d1 < d0) 1 else 0)
                found = true
            }
        }
        return found
    }

    /**
     * Position and VS2 velocity of node [node] of vine [chainId], into [position] and [velocity]. Returns false when
     * the vine is not here, the node is out of range or a link of the vine is broken.
     */
    fun nodeState(chainId: Long, node: Int, position: Vector3d, velocity: Vector3d): Boolean {
        val v = indexOf(chainId)
        if (v < 0) return false
        val first = ranges[v * 2]
        val end = ranges[v * 2 + 1]
        val i = first + node
        if (node < 0 || i >= end) return false
        // The last node's gap is the vine end, not a break.
        for (j in first until end - 1) if (gaps[j]) return false
        position.set(nodes[i * 3], nodes[i * 3 + 1], nodes[i * 3 + 2])
        if (readVelocity(v, i)) velocity.set(velocities[i * 3], velocities[i * 3 + 1], velocities[i * 3 + 2]) else velocity.zero()
        return true
    }

    /** As [nodeState], but the position only; it reads no VS2 velocity. */
    fun nodePosition(chainId: Long, node: Int, position: Vector3d): Boolean {
        val v = indexOf(chainId)
        if (v < 0) return false
        val first = ranges[v * 2]
        val i = first + node
        if (node < 0 || i >= ranges[v * 2 + 1]) return false
        position.set(nodes[i * 3], nodes[i * 3 + 1], nodes[i * 3 + 2])
        return true
    }

    /** The point at [t] along unbroken link [link] of vine [chainId] into [position]; false when that link is not here. */
    fun linkPoint(chainId: Long, link: Int, t: Double, position: Vector3d): Boolean {
        val v = indexOf(chainId)
        if (v < 0) return false
        val i = ranges[v * 2] + link
        if (link < 0 || i + 1 >= ranges[v * 2 + 1] || gaps[i]) return false
        val k = i * 3
        position.set(
            nodes[k] + (nodes[k + 3] - nodes[k]) * t,
            nodes[k + 1] + (nodes[k + 4] - nodes[k + 1]) * t,
            nodes[k + 2] + (nodes[k + 5] - nodes[k + 2]) * t,
        )
        return true
    }

    /** The point at [grip] (node units) of vine [chainId] into [position]; false when the vine is not here. */
    fun gripPoint(chainId: Long, grip: Double, position: Vector3d): Boolean {
        val v = indexOf(chainId)
        if (v < 0) return false
        val first = ranges[v * 2]
        val count = ranges[v * 2 + 1] - first
        if (count < 1 || !grip.isFinite()) return false
        val k = (first + gripLink(grip, count)) * 3
        if (count == 1) {
            position.set(nodes[k], nodes[k + 1], nodes[k + 2])
            return true
        }
        val t = (grip - gripLink(grip, count)).coerceIn(0.0, 1.0)
        position.set(
            nodes[k] + (nodes[k + 3] - nodes[k]) * t,
            nodes[k + 1] + (nodes[k + 4] - nodes[k + 1]) * t,
            nodes[k + 2] + (nodes[k + 5] - nodes[k + 2]) * t,
        )
        return true
    }

    /** The node count of vine [chainId]; 0 when the vine is not here. */
    fun nodeCount(chainId: Long): Int {
        val v = indexOf(chainId)
        return if (v < 0) 0 else ranges[v * 2 + 1] - ranges[v * 2]
    }

    private fun indexOf(chainId: Long): Int {
        for (v in 0 until vineCount) if (chains[v].id.value == chainId) return v
        return -1
    }

    /** True when a collision cube of an unbroken link, not of a vine that [pass] passes, touches the given bounds. */
    @Suppress("LongParameterList") // A box as plain doubles.
    fun touches(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, pass: VinePass? = null): Boolean {
        forEachCube(minX, minY, minZ, maxX, maxY, maxZ, pass) { _, _, _, _, _, _, _ -> return true }
        return false
    }

    /** True when a collision cube of vine [chainId] touches [box]. */
    fun touchesVine(chainId: Long, box: AABB): Boolean {
        forEachCube(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, null) { v, _, _, _, _, _, _ ->
            if (chains[v].id.value == chainId) return true
        }
        return false
    }

    /** True when a collision cube, not of a vine that [pass] passes, touches [box] grown by [LEAVE_INFLATE]. */
    fun touchesNear(box: AABB, pass: VinePass? = null): Boolean = touches(
        box.minX - LEAVE_INFLATE, box.minY - LEAVE_INFLATE, box.minZ - LEAVE_INFLATE,
        box.maxX + LEAVE_INFLATE, box.maxY + LEAVE_INFLATE, box.maxZ + LEAVE_INFLATE, pass,
    )

    /** True when a vine's bounds overlap [box]; a cheap test before the full lookup. */
    fun overlaps(box: AABB): Boolean {
        for (v in 0 until vineCount) if (overlapsVine(v, box)) return true
        return false
    }

    /**
     * Each unbroken link that touches [box], into [out], weighted by how many of its collision cubes touch it.
     * [VineContacts.t] is the mean place of those cubes along the link, 0 at its first node and 1 at the second.
     * A knot that touches [box] is one more cube on its end link.
     */
    fun contacts(box: AABB, out: VineContacts, pass: VinePass? = null) {
        out.clear()
        val r = VINE_HALF_WIDTH
        for (v in 0 until vineCount) {
            if (!overlapsVine(v, box) || pass?.passes(chains[v].id.value) == true) continue
            val first = ranges[v * 2]
            val last = ranges[v * 2 + 1] - 1
            for (end in 0..1) {
                if (knots[v] and (if (end == 0) JOINED_START else JOINED_END) == 0) continue
                val link = if (end == 0) first else last - 1
                if (gaps[link] || !knotTouches((if (end == 0) first else last) * 3, box)) continue
                if (!out.add(chains[v], link - first, end.toDouble(), 1.0)) return
            }
            for (i in first until ranges[v * 2 + 1]) {
                if (gaps[i]) continue
                val k = i * 3
                val ux = nodes[k + 3] - nodes[k]; val uy = nodes[k + 4] - nodes[k + 1]; val uz = nodes[k + 5] - nodes[k + 2]
                val steps = cubeSteps(sqrt(ux * ux + uy * uy + uz * uz))
                var touching = 0
                var tSum = 0.0
                for (s in 0..steps) {
                    val t = s.toDouble() / steps
                    val x = nodes[k] + ux * t; val y = nodes[k + 1] + uy * t; val z = nodes[k + 2] + uz * t
                    if (x + r < box.minX || x - r > box.maxX || y + r < box.minY || y - r > box.maxY ||
                        z + r < box.minZ || z - r > box.maxZ) continue
                    touching++
                    tSum += t
                }
                if (touching > 0 && !out.add(chains[v], i - first, tSum / touching, touching.toDouble())) return
            }
        }
    }

    /** The chain of vine [chainId] when its link [link] is unbroken and touches [box], or a knot on it does; else null. */
    fun touchingLink(chainId: Long, link: Int, box: AABB): Chain? {
        for (v in 0 until vineCount) {
            val chain = chains[v]
            if (chain.id.value != chainId) continue
            val first = ranges[v * 2]
            val last = ranges[v * 2 + 1] - 1
            val i = first + link
            if (link < 0 || i >= last + 1 || gaps[i]) return null
            val knotted = (i == first && knots[v] and JOINED_START != 0) || (i == last - 1 && knots[v] and JOINED_END != 0)
            val r = if (knotted) VINE_KNOT_HALF else VINE_HALF_WIDTH
            val k = i * 3
            val apart = box.maxX < min(nodes[k], nodes[k + 3]) - r || box.minX > max(nodes[k], nodes[k + 3]) + r ||
                box.maxY < min(nodes[k + 1], nodes[k + 4]) - r || box.minY > max(nodes[k + 1], nodes[k + 4]) + r ||
                box.maxZ < min(nodes[k + 2], nodes[k + 5]) - r || box.minZ > max(nodes[k + 2], nodes[k + 5]) + r
            return if (apart) null else chain
        }
        return null
    }

    /** The part of the last [platformMove] that is real link motion (VS2 node velocity), in blocks per tick. */
    val platformMotion: Vector3dc get() = motion

    /**
     * The move in blocks that the vines give [box] in entity tick [tick]; null when there is none. When [standing], or
     * one tick after it, the box goes with the link under its feet. Then it leaves each link that swept into it. The
     * vines that [pass] passes do nothing.
     */
    fun platformMove(box: AABB, standing: Boolean, support: VineSupport? = null, tick: Int = 0, pass: VinePass? = null): Vector3dc? {
        platform.zero()
        motion.zero()
        // One tick of grace: a late chain frame can drop the link under the feet for a tick and clear onGround.
        val graced = !standing && support != null && support.grounded && support.tick == tick - 1
        if (standing || graced) carry(box, standing, support, tick, pass) else support?.clear()
        lift(box, pass)
        shove(box, pass)
        if (platform.lengthSquared() < MIN_PLATFORM_MOVE * MIN_PLATFORM_MOVE) return null
        return platform.set(
            platform.x.coerceIn(-MAX_PLATFORM_MOVE, MAX_PLATFORM_MOVE),
            platform.y.coerceIn(-MAX_PLATFORM_MOVE, MAX_PLATFORM_MOVE),
            platform.z.coerceIn(-MAX_PLATFORM_MOVE, MAX_PLATFORM_MOVE),
        )
    }

    /**
     * Moves the box by how far the link point under its feet moved since the last tick, so it stays on a vine that
     * sways or jumps a late frame. With no point from the last tick, it moves at the link velocity.
     */
    private fun carry(box: AABB, standing: Boolean, support: VineSupport?, tick: Int, pass: VinePass?) {
        val last = support?.takeIf { it.tick == tick - 1 && linkPoint(it.chainId, it.link, it.t, supportPoint) }
        val dx = if (last != null) supportPoint.x - last.position.x else 0.0
        val dy = if (last != null) supportPoint.y - last.position.y else 0.0
        val dz = if (last != null) supportPoint.z - last.position.z else 0.0
        val minX = box.minX + dx; val minZ = box.minZ + dz; val maxX = box.maxX + dx; val maxZ = box.maxZ + dz
        val feet = box.minY + dy
        val low = feet - STAND_BELOW
        val high = feet + STAND_ABOVE
        val centreX = (minX + maxX) * 0.5; val centreZ = (minZ + maxZ) * 0.5
        var count = 0
        var moving = 0
        var top = -Double.MAX_VALUE
        var nearest = Double.MAX_VALUE
        var bestVine = -1; var bestNode = 0; var bestT = 0.0
        var bestX = 0.0; var bestY = 0.0; var bestZ = 0.0
        forEachCube(minX, low - 2 * VINE_KNOT_HALF, minZ, maxX, high, maxZ, pass) { v, i, t, x, y, z, half ->
            val cubeTop = y + half
            if (cubeTop < low || cubeTop > high || !overlapsAcross(x, z, half, minX, minZ, maxX, maxZ)) return@forEachCube
            count++
            top = max(top, cubeTop)
            val d = sq(x - centreX) + sq(z - centreZ)
            if (d < nearest) {
                nearest = d; bestVine = v; bestNode = i; bestT = t; bestX = x; bestY = y; bestZ = z
            }
            if (loadCubeVelocity(v, i, t)) {
                motion.add(cubeVelocity)
                moving++
            }
        }
        if (count == 0) {
            support?.clear()
            return
        }
        if (moving > 0) motion.mul(SECONDS_PER_TICK / moving)
        if (last != null) platform.set(dx, dy, dz) else platform.set(motion)
        // A late chain frame, or two frames in one tick, can leave the feet inside the link: put them on its top.
        platform.y = max(platform.y, top - box.minY)
        support?.set(chains[bestVine].id.value, bestNode - ranges[bestVine * 2], bestT, bestX, bestY, bestZ, tick, standing)
    }

    /**
     * Puts the feet on the top of the cubes they already overlap from above. Vanilla collide ignores a shape that the
     * box already overlaps, so a link that rose into the feet between ticks let the entity fall through it.
     */
    private fun lift(box: AABB, pass: VinePass?) {
        val minX = box.minX + platform.x; val minY = box.minY + platform.y; val minZ = box.minZ + platform.z
        val maxX = box.maxX + platform.x; val maxY = box.maxY + platform.y; val maxZ = box.maxZ + platform.z
        var top = minY
        forEachCube(minX, minY, minZ, maxX, maxY, maxZ, pass) { _, _, _, x, y, z, half ->
            // A cube with its bottom over the feet is above the entity, not under it.
            if (y - half <= minY && y + half > top + OVERLAP_EPS && overlapsAcross(x, z, half, minX, minZ, maxX, maxZ)) {
                top = y + half
            }
        }
        val rise = top - minY
        // A link through the lifted box too (a steep vine inside the entity) is for shove; a lift there would climb it.
        if (rise <= 0.0 || overlapsAnyCube(minX, top, minZ, maxX, maxY + rise, maxZ, pass)) return
        platform.y += rise
    }

    @Suppress("LongParameterList") // A box as plain doubles.
    private fun overlapsAnyCube(
        minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, pass: VinePass?,
    ): Boolean {
        forEachCube(minX, minY, minZ, maxX, maxY, maxZ, pass) { _, _, _, x, y, z, half ->
            if (overlapsCube(x, y, z, half, minX, minY, minZ, maxX, maxY, maxZ)) return true
        }
        return false
    }

    private fun shove(box: AABB, pass: VinePass?) {
        val minX = box.minX + platform.x; val minY = box.minY + platform.y; val minZ = box.minZ + platform.z
        val maxX = box.maxX + platform.x; val maxY = box.maxY + platform.y; val maxZ = box.maxZ + platform.z
        var posX = 0.0; var posY = 0.0; var posZ = 0.0
        var negX = 0.0; var negY = 0.0; var negZ = 0.0
        var vPosX = 0.0; var vPosY = 0.0; var vPosZ = 0.0
        var vNegX = 0.0; var vNegY = 0.0; var vNegZ = 0.0
        forEachCube(minX, minY, minZ, maxX, maxY, maxZ, pass) { v, i, t, x, y, z, half ->
            // Vanilla collide lets a box pass through a shape that it already overlaps.
            if (!overlapsCube(x, y, z, half, minX, minY, minZ, maxX, maxY, maxZ) || !loadCubeVelocity(v, i, t)) return@forEachCube
            val speed = cubeVelocity.length()
            if (speed < MIN_SHOVE_SPEED) return@forEachCube
            val ux = cubeVelocity.x / speed; val uy = cubeVelocity.y / speed; val uz = cubeVelocity.z / speed
            val exit = min(exitAlong(ux, x, half, minX, maxX), min(exitAlong(uy, y, half, minY, maxY), exitAlong(uz, z, half, minZ, maxZ)))
            // A deeper overlap than this tick's sweep did not come from the link's motion.
            if (exit > speed * SECONDS_PER_TICK * MAX_FRAME_STEPS) return@forEachCube
            posX = max(posX, exit * ux); negX = min(negX, exit * ux)
            posY = max(posY, exit * uy); negY = min(negY, exit * uy)
            posZ = max(posZ, exit * uz); negZ = min(negZ, exit * uz)
            val vx = cubeVelocity.x * SECONDS_PER_TICK; val vy = cubeVelocity.y * SECONDS_PER_TICK; val vz = cubeVelocity.z * SECONDS_PER_TICK
            vPosX = max(vPosX, vx); vNegX = min(vNegX, vx)
            vPosY = max(vPosY, vy); vNegY = min(vNegY, vy)
            vPosZ = max(vPosZ, vz); vNegZ = min(vNegZ, vz)
        }
        platform.add(posX + negX, posY + negY, posZ + negZ)
        // The exit depth is a position fix; only the link speed may become a fling.
        motion.add(vPosX + vNegX, vPosY + vNegY, vPosZ + vNegZ)
    }

    /** How far the box [lo]..[hi] must go along direction [u] to leave the cube at [c] of [half] size on this axis. */
    private fun exitAlong(u: Double, c: Double, half: Double, lo: Double, hi: Double): Double = when {
        u > DIRECTION_EPS -> (c + half - lo) / u
        u < -DIRECTION_EPS -> (hi - c + half) / -u
        else -> Double.MAX_VALUE
    }

    @Suppress("LongParameterList") // A cube centre, its half size and a box.
    private fun overlapsAcross(x: Double, z: Double, half: Double, minX: Double, minZ: Double, maxX: Double, maxZ: Double): Boolean =
        x + half > minX + OVERLAP_EPS && x - half < maxX - OVERLAP_EPS && z + half > minZ + OVERLAP_EPS && z - half < maxZ - OVERLAP_EPS

    @Suppress("LongParameterList") // A cube centre, its half size and a box.
    private fun overlapsCube(
        x: Double, y: Double, z: Double, half: Double,
        minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double,
    ): Boolean = overlapsAcross(x, z, half, minX, minZ, maxX, maxZ) && y + half > minY + OVERLAP_EPS && y - half < maxY - OVERLAP_EPS

    /**
     * Each collision cube that touches the given bounds: the cubes of unbroken links, then the knots. A knot gives
     * the link and place along it where its velocity is read. The vines that [pass] passes have no cubes.
     */
    @Suppress("LongParameterList") // A box as plain doubles keeps the per-cube walk free of allocation.
    private inline fun forEachCube(
        minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, pass: VinePass?,
        action: (vine: Int, node: Int, t: Double, x: Double, y: Double, z: Double, half: Double) -> Unit,
    ) {
        val r = VINE_HALF_WIDTH
        val h = VINE_KNOT_HALF
        for (v in 0 until vineCount) {
            if (!overlapsVine(v, minX, minY, minZ, maxX, maxY, maxZ) || pass?.passes(chains[v].id.value) == true) continue
            val first = ranges[v * 2]
            val last = ranges[v * 2 + 1] - 1
            for (i in first until last + 1) {
                if (gaps[i]) continue
                val k = i * 3
                val ax = nodes[k]; val ay = nodes[k + 1]; val az = nodes[k + 2]
                val ux = nodes[k + 3] - ax; val uy = nodes[k + 4] - ay; val uz = nodes[k + 5] - az
                if (maxX < min(ax, ax + ux) - r || minX > max(ax, ax + ux) + r || maxY < min(ay, ay + uy) - r ||
                    minY > max(ay, ay + uy) + r || maxZ < min(az, az + uz) - r || minZ > max(az, az + uz) + r) continue
                val steps = cubeSteps(sqrt(ux * ux + uy * uy + uz * uz))
                for (s in 0..steps) {
                    val t = s.toDouble() / steps
                    val x = ax + ux * t; val y = ay + uy * t; val z = az + uz * t
                    if (x + r < minX || x - r > maxX || y + r < minY || y - r > maxY || z + r < minZ || z - r > maxZ) continue
                    action(v, i, t, x, y, z, r)
                }
            }
            for (end in 0..1) {
                if (knots[v] and (if (end == 0) JOINED_START else JOINED_END) == 0) continue
                val k = (if (end == 0) first else last) * 3
                val x = nodes[k]; val y = nodes[k + 1]; val z = nodes[k + 2]
                if (x + h < minX || x - h > maxX || y + h < minY || y - h > maxY || z + h < minZ || z - h > maxZ) continue
                action(v, if (end == 0) first else last - 1, end.toDouble(), x, y, z, h)
            }
        }
    }

    /** The VS2 velocity at [t] along link [node] of vine [vine], into [cubeVelocity]; false when VS2 gives none. */
    private fun loadCubeVelocity(vine: Int, node: Int, t: Double): Boolean {
        if (!readVelocity(vine, node) || !readVelocity(vine, node + 1)) return false
        val a = node * 3
        cubeVelocity.set(
            velocities[a] + (velocities[a + 3] - velocities[a]) * t,
            velocities[a + 1] + (velocities[a + 4] - velocities[a + 1]) * t,
            velocities[a + 2] + (velocities[a + 5] - velocities[a + 2]) * t,
        )
        return true
    }

    private fun readVelocity(vine: Int, node: Int): Boolean {
        if (velocityState[node] == VELOCITY_UNREAD) {
            velocityState[node] = VELOCITY_MISSING
            val ids = nodeIdLists[vine] ?: chains[vine].nodeIds.also { nodeIdLists[vine] = it }
            val local = node - ranges[vine * 2]
            val velocity = if (local < ids.size) particles?.getById(ids[local])?.velocity else null
            if (velocity != null && velocity.isFinite) {
                velocities[node * 3] = velocity.x(); velocities[node * 3 + 1] = velocity.y(); velocities[node * 3 + 2] = velocity.z()
                velocityState[node] = VELOCITY_READ
            }
        }
        return velocityState[node] == VELOCITY_READ
    }

    /** Slab test of the ray for t in 0..[maxT] against vine [v]'s bounds, widened to the pick radius. */
    private fun rayHitsVine(v: Int, origin: Vector3d, dir: Vector3d, maxT: Double): Boolean {
        val b = v * 6
        var near = 0.0
        var far = maxT
        for (axis in 0..2) {
            val o = origin[axis]
            val d = dir[axis]
            val lo = bounds[b + axis] - PICK_BOUNDS_INFLATE
            val hi = bounds[b + 3 + axis] + PICK_BOUNDS_INFLATE
            if (d > -DIRECTION_EPS && d < DIRECTION_EPS) {
                if (o < lo || o > hi) return false
                continue
            }
            val t0 = (lo - o) / d
            val t1 = (hi - o) / d
            near = max(near, min(t0, t1))
            far = min(far, max(t0, t1))
            if (near > far) return false
        }
        return true
    }

    private fun knotTouches(k: Int, box: AABB): Boolean {
        val h = VINE_KNOT_HALF
        return nodes[k] + h >= box.minX && nodes[k] - h <= box.maxX && nodes[k + 1] + h >= box.minY &&
            nodes[k + 1] - h <= box.maxY && nodes[k + 2] + h >= box.minZ && nodes[k + 2] - h <= box.maxZ
    }

    private fun overlapsVine(v: Int, box: AABB): Boolean = overlapsVine(v, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)

    private fun overlapsVine(v: Int, minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): Boolean {
        val b = v * 6
        return !(maxX < bounds[b] || minX > bounds[b + 3] || maxY < bounds[b + 1] ||
            minY > bounds[b + 4] || maxZ < bounds[b + 2] || minZ > bounds[b + 5])
    }

    /**
     * [base] with cubes for each vine link and knot in [box], but none for the vines that [pass] passes; [base] itself
     * when no link is there.
     */
    fun withShapes(box: AABB, base: List<VoxelShape>, pass: VinePass? = null): List<VoxelShape> {
        var out: MutableList<VoxelShape>? = null
        for (v in 0 until vineCount) {
            if (!overlapsVine(v, box) || pass?.passes(chains[v].id.value) == true) continue
            val first = ranges[v * 2]
            val last = ranges[v * 2 + 1] - 1
            for (i in first..last) {
                if (gaps[i]) continue
                out = addLink(i * 3, box, base, out)
            }
            if (knots[v] and JOINED_START != 0) out = addCube(first * 3, VINE_KNOT_HALF, box, base, out)
            if (knots[v] and JOINED_END != 0) out = addCube(last * 3, VINE_KNOT_HALF, box, base, out)
        }
        return out ?: base
    }

    private fun addLink(k: Int, box: AABB, base: List<VoxelShape>, current: MutableList<VoxelShape>?): MutableList<VoxelShape>? {
        val ax = nodes[k]; val ay = nodes[k + 1]; val az = nodes[k + 2]
        val bx = nodes[k + 3]; val by = nodes[k + 4]; val bz = nodes[k + 5]
        val r = VINE_HALF_WIDTH
        if (box.maxX < min(ax, bx) - r || box.minX > max(ax, bx) + r || box.maxY < min(ay, by) - r ||
            box.minY > max(ay, by) + r || box.maxZ < min(az, bz) - r || box.minZ > max(az, bz) + r) return current
        val length = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
        val steps = cubeSteps(length)
        var out = current
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            out = addCube(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, r, box, base, out)
        }
        return out
    }

    private fun addCube(k: Int, half: Double, box: AABB, base: List<VoxelShape>, current: MutableList<VoxelShape>?) =
        addCube(nodes[k], nodes[k + 1], nodes[k + 2], half, box, base, current)

    @Suppress("LongParameterList") // A cube centre, its half size, the swept box and the shape lists.
    private fun addCube(
        x: Double, y: Double, z: Double, half: Double, box: AABB, base: List<VoxelShape>, current: MutableList<VoxelShape>?,
    ): MutableList<VoxelShape>? {
        // Entity.collide moves the box only inside the swept [box], but its step-up goes above it: keep cubes over it.
        if (x + half < box.minX || x - half > box.maxX || y + half < box.minY || z + half < box.minZ || z - half > box.maxZ) {
            return current
        }
        val out = current ?: ArrayList(base)
        out.add(Shapes.box(x - half, y - half, z - half, x + half, y + half, z + half))
        return out
    }

    // Cube centres no farther apart than one half width, so the cubes overlap into a solid rope.
    private fun cubeSteps(length: Double): Int = ceil(length / VINE_HALF_WIDTH).toInt().coerceIn(1, MAX_CUBES_PER_LINK)

    private fun ensure(nodesNeeded: Int, vinesNeeded: Int) {
        if (nodes.size < nodesNeeded * 3) {
            nodes = nodes.copyOf(max(nodesNeeded * 3, nodes.size * 2))
            gaps = gaps.copyOf(nodes.size / 3)
            velocities = velocities.copyOf(nodes.size)
            velocityState = velocityState.copyOf(nodes.size / 3)
        }
        if (ranges.size < vinesNeeded * 2) {
            ranges = ranges.copyOf(max(vinesNeeded * 2, ranges.size * 2))
            bounds = bounds.copyOf(ranges.size * 3)
            knots = knots.copyOf(ranges.size / 2)
            frameArrivals = frameArrivals.copyOf(ranges.size / 2)
            nodeIdLists = nodeIdLists.copyOf(ranges.size / 2)
        }
    }
}

private fun sq(d: Double) = d * d

private const val NO_TICK = Int.MIN_VALUE

/** The link point that carried an entity in its last tick, so the next carry follows that point's move. */
class VineSupport {
    var chainId = 0L
        private set
    var link = 0
        private set
    var t = 0.0
        private set
    val position = Vector3d()
    /** The entity tick count of the carry; [NO_TICK] when there is no support. */
    var tick = NO_TICK
        private set
    /** True when the entity stood on the vine in that tick; only then does the next tick get the grace. */
    var grounded = false
        private set

    @Suppress("LongParameterList") // A link point, its position and the tick.
    fun set(chainId: Long, link: Int, t: Double, x: Double, y: Double, z: Double, tick: Int, grounded: Boolean) {
        this.chainId = chainId; this.link = link; this.t = t
        position.set(x, y, z)
        this.tick = tick; this.grounded = grounded
    }

    fun clear() {
        tick = NO_TICK
        grounded = false
    }
}

/** A node of one vine: the chain id value and the node index. */
class VineNodeRef {
    var chainId = 0L
    var node = 0
}

private const val MAX_CONTACTS = 64

/** Reused output of [VineCollider.contacts]: link [node] to [node] + 1 of [chain], with a [weight]. */
class VineContacts {
    val chain = arrayOfNulls<Chain>(MAX_CONTACTS)
    val node = IntArray(MAX_CONTACTS)
    val t = DoubleArray(MAX_CONTACTS)
    val weight = DoubleArray(MAX_CONTACTS)
    var size = 0
        private set
    var totalWeight = 0.0
        private set

    fun clear() {
        chain.fill(null, 0, size)
        size = 0
        totalWeight = 0.0
    }

    /** False when full; the caller stops there. */
    fun add(chain: Chain, node: Int, t: Double, weight: Double): Boolean {
        if (size >= MAX_CONTACTS) return false
        this.chain[size] = chain; this.node[size] = node; this.t[size] = t; this.weight[size] = weight
        size++
        totalWeight += weight
        return true
    }
}

/** Client-side collider; the client tick fills it, and only the client thread reads it. */
val clientVineCollider = VineCollider()

/** For the entity collision mixin: [base] with the vine cubes of [level] inside [box], less the ones [pass] passes. */
fun Level.withVineShapes(box: AABB, base: List<VoxelShape>, pass: VinePass? = null): List<VoxelShape> {
    val collider = when {
        this is ServerLevel -> (loadedWoggakVines ?: return base).collider
        isClientSide -> clientVineCollider
        else -> return base
    }
    if (collider.isEmpty) return base
    return collider.withShapes(box, base, pass)
}

/** The vines that this entity passes through: for the local player, the held vine and the vines it just left. */
internal fun Entity.vinePass(): VinePass? =
    if (this is Player && isLocalPlayer && level().isClientSide && clientVineHold.passesAny) clientVineHold else null

/**
 * For the entity collision mixin: [base] with the vine cubes of this entity's level inside [box]. The server replays
 * a player's move without vines, because the client already collided that move with its own vine snapshot.
 */
fun Entity.withVineShapes(box: AABB, base: List<VoxelShape>): List<VoxelShape> {
    val level = level()
    if (this is Player && level is ServerLevel) return base
    return level.withVineShapes(box, base, vinePass())
}

/** For the server floating check: true when the player holds a vine, or a link is where noBlocksAround looks for ground. */
fun Entity.hasVineSupport(): Boolean {
    val data = (level() as? ServerLevel ?: return false).loadedWoggakVines ?: return false
    if (this is Player && data.isHolding(this)) return true
    val collider = data.collider
    if (collider.isEmpty) return false
    val box = boundingBox
    return collider.touches(
        box.minX - FLOAT_CHECK_INFLATE, box.minY - FLOAT_CHECK_INFLATE - FLOAT_CHECK_DEPTH, box.minZ - FLOAT_CHECK_INFLATE,
        box.maxX + FLOAT_CHECK_INFLATE, box.maxY + FLOAT_CHECK_INFLATE, box.maxZ + FLOAT_CHECK_INFLATE,
    )
}

/** Implemented on every Entity by EntityWoggakVineCollisionMixin. */
interface VineCarried {
    /** The vine move of the last tick in blocks; zero when no vine moved the entity. Null until the first move. */
    fun enderkinesisVineCarry(): Vector3d?

    fun enderkinesisSetVineCarry(carry: Vector3d)

    fun enderkinesisVineSupport(): VineSupport
}

/**
 * Before an entity's own move: moves it with the vine links it stands on, and out of the links that swing into it.
 * When no vine moves or touches it any more, it keeps the last vine move as velocity, so a fast swing can fling it.
 */
fun Entity.rideVines(type: MoverType) {
    if (type != MoverType.SELF) return
    val collider = vineCollider()
    if (collider == null) {
        releaseVineCarry()
        return
    }
    val pass = vinePass()
    val moved = vineMove(collider, pass)
    if (moved == null) {
        if (!collider.touchesNear(boundingBox, pass)) releaseVineCarry()
        return
    }
    val carried = this as VineCarried
    val carry = carried.enderkinesisVineCarry()
    // The carry is the fling on a step-off: only the link motion that the entity really made, never a lift or snap.
    val motion = collider.platformMotion
    val x = flingPart(motion.x(), moved.x); val y = flingPart(motion.y(), moved.y); val z = flingPart(motion.z(), moved.z)
    if (carry != null) carry.set(x, y, z) else carried.enderkinesisSetVineCarry(Vector3d(x, y, z))
}

/** The link [motion] on one axis, cut to the [moved] distance; 0 when the move went the other way. */
internal fun flingPart(motion: Double, moved: Double): Double = when {
    motion > 0.0 && moved > 0.0 -> min(motion, moved)
    motion < 0.0 && moved < 0.0 -> max(motion, moved)
    else -> 0.0
}

/** Ends the vine carry of this entity: it goes on with the last vine move in this tick and keeps it as velocity. */
private fun Entity.releaseVineCarry() {
    val carry = (this as VineCarried).enderkinesisVineCarry() ?: return
    if (carry.lengthSquared() == 0.0) return
    val fling = Vec3(carry.x, carry.y, carry.z)
    carry.zero()
    // The movement of this tick was read from deltaMovement before this hook, so move the fling here too.
    moveWithVines(fling)
    deltaMovement = deltaMovement.add(fling)
}

/** Ends the vine carry of this entity without a fling. */
fun Entity.dropVineCarry() {
    val carried = this as VineCarried
    carried.enderkinesisVineCarry()?.zero()
    carried.enderkinesisVineSupport().clear()
}

private fun Entity.vineCollider(): VineCollider? {
    if (noPhysics) return null
    val level = level()
    val collider = when {
        // Players move client-authoritatively: their own client carries them, and the server replays that move.
        level is ServerLevel -> if (this is Player) return null else (level.loadedWoggakVines ?: return null).collider
        level.isClientSide && isControlledByLocalInstance -> clientVineCollider
        else -> return null
    }
    return if (collider.isEmpty) null else collider
}

private fun Entity.vineMove(collider: VineCollider, pass: VinePass?): Vec3? {
    val move = collider.platformMove(boundingBox, onGround(), (this as VineCarried).enderkinesisVineSupport(), tickCount, pass) ?: return null
    return moveWithVines(Vec3(move.x(), move.y(), move.z()))
}

/** Moves the entity by [delta] with block, entity and vine collision; returns the move it made. */
private fun Entity.moveWithVines(delta: Vec3): Vec3 {
    val level = level()
    val box = boundingBox
    val sweep = box.expandTowards(delta)
    val moved = Entity.collideBoundingBox(this, delta, box, level, level.withVineShapes(sweep, level.getEntityCollisions(this, sweep), vinePass()))
    setPos(x + moved.x, y + moved.y, z + moved.z)
    return moved
}
