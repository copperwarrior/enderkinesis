package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleLimbNetwork
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos
import java.util.Collections
import java.util.IdentityHashMap

/** Left-click hits on barnacle limbs. Limbs are not blocks or entities, so vanilla picking cannot find them. */
object YgannBarnacleLimbClient {

    // Client thread only (renderer and Minecraft.startAttack/continueAttack).
    private val rendered: MutableSet<YgannBarnacleBlockEntity> =
        Collections.newSetFromMap(IdentityHashMap())
    private val reveals = DoubleArray(YgannBarnacleGeometry.MAX_TENTACLES)
    private val from = Vector3d()
    private val to = Vector3d()
    private val segA = Vector3d()
    private val segB = Vector3d()
    private val tmp = Vector3d()
    private val dir1 = Vector3d()
    private val dir2 = Vector3d()
    private val rel = Vector3d()
    private var lastT = 0.0
    // The last hit: [hitArc] and [hitDistance] of one tube, then the best limb over all barnacles.
    private var hitArc = 0.0
    private var hitDistance = 0.0
    private var targetBe: YgannBarnacleBlockEntity? = null
    private var targetPath = 0
    private var targetArc = 0.0
    private var targetDistance = 0.0
    /** A cut happened under the attack press that is still held. */
    private var cutHeld = false

    fun noteRendered(be: YgannBarnacleBlockEntity) {
        rendered.add(be)
    }

    fun onDisconnect() {
        rendered.clear()
        cutHeld = false
    }

    /** Mixin hook for `startAttack`: true when the click cut a limb, so vanilla must skip it. */
    @JvmStatic
    fun tryCut(mc: Minecraft): Boolean {
        val found = limbInFront(mc)
        val be = targetBe
        targetBe = null
        val walk = be?.clientWalk
        if (!found || be == null || walk == null || targetPath !in walk.paths.indices) {
            cutHeld = false
            return false
        }
        // By limb id: the server's walk can list limbs in another order for a moment after a hull change.
        YgannBarnacleLimbNetwork.sendCut(be.blockPos, walk.paths[targetPath].id, targetArc)
        mc.player?.swing(InteractionHand.MAIN_HAND)
        cutHeld = true
        return true
    }

    /** Mixin hook for `continueAttack`: true when a limb is in front, so the block behind it must not be mined.
     *  After a cut, also while the attack key stays down: the cut part is gone, and the block it lay on
     *  must not start to break under the same press. */
    @JvmStatic
    fun blocksMining(mc: Minecraft): Boolean {
        if (cutHeld) {
            if (mc.options.keyAttack.isDown) return true
            cutHeld = false
        }
        val found = limbInFront(mc)
        targetBe = null
        return found
    }

    /** True when the crosshair ray hits a drawn limb before any block or mob; the hit is in the target fields. */
    private fun limbInFront(mc: Minecraft): Boolean {
        targetBe = null
        if (rendered.isEmpty()) return false
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val reach = mc.gameMode?.pickRange?.toDouble() ?: return false
        val eye = player.getEyePosition(1.0f)
        val end = eye.add(player.getLookAngle().scale(reach))
        val it = rendered.iterator()
        while (it.hasNext()) {
            val be = it.next()
            if (be.isRemoved || be.level !== level) { it.remove(); continue }
            val walk = be.clientWalk ?: continue
            val display = be.clientMorph ?: continue
            if (display.size != walk.paths.size) continue
            val ship = level.getShipManagingPos(be.blockPos) as? ClientShip
            // The limbs are in the barnacle's frame (shipyard on a ship); a rigid transform keeps distances.
            toLocal(be, ship, eye, from)
            toLocal(be, ship, end, to)
            // The bounds hold every drawn limb with room to spare, so a ray that misses them hits no limb.
            val bounds = be.clientBounds
            if (bounds != null && !segmentHitsBox(bounds)) continue
            be.limbReveals(walk.paths, level.gameTime, 0f, reveals)
            for (i in display.indices) {
                val mp = display[i] as YgannBarnacleRenderer.MorphPath
                if (!hitDrawnLimb(mp.currentPoints(), mp.originArc, reveals[i])) continue
                if (targetBe == null || hitDistance < targetDistance) {
                    targetBe = be
                    targetPath = i
                    targetArc = hitArc
                    targetDistance = hitDistance
                }
            }
        }
        if (targetBe == null) return false
        // A limb behind a wall or a mob must not take the click from it.
        val vanilla = mc.hitResult
        if (vanilla != null && vanilla.type != HitResult.Type.MISS && eye.distanceTo(vanilla.location) < targetDistance) {
            targetBe = null
            return false
        }
        return true
    }

    /** True when the segment [from]→[to] passes through [box] (slab test). */
    private fun segmentHitsBox(box: AABB): Boolean {
        var t0 = 0.0
        var t1 = 1.0
        for (axis in 0 until 3) {
            val o = if (axis == 0) from.x else if (axis == 1) from.y else from.z
            val d = (if (axis == 0) to.x else if (axis == 1) to.y else to.z) - o
            val lo = if (axis == 0) box.minX else if (axis == 1) box.minY else box.minZ
            val hi = if (axis == 0) box.maxX else if (axis == 1) box.maxY else box.maxZ
            if (Math.abs(d) < 1.0e-12) {
                if (o < lo || o > hi) return false
                continue
            }
            var a = (lo - o) / d
            var b = (hi - o) / d
            if (a > b) { val t = a; a = b; b = t }
            t0 = Math.max(t0, a)
            t1 = Math.min(t1, b)
            if (t0 > t1) return false
        }
        return true
    }

    /** The nearest hit of the segment [from]→[to] on the tube that the renderer drew last frame, into
     *  [hitArc] and [hitDistance] (from [from]); false when there is none. The tube sits one radius above
     *  the hull, like the draw. */
    private fun hitDrawnLimb(points: List<YgannBarnacleGeometry.BWaypoint>, originArc: Double, reveal: Double): Boolean {
        if (points.size < 2 || reveal <= MIN_HIT_REVEAL_PX) return false
        val rayLen = from.distance(to)
        if (rayLen < 1.0e-9) return false
        var bestS = Double.MAX_VALUE
        var bestArc = 0.0
        for (k in 1 until points.size) {
            val a = points[k - 1]
            val b = points[k]
            if (a.arcPx >= reveal) break
            val f = if (b.arcPx > reveal) (reveal - a.arcPx) / (b.arcPx - a.arcPx) else 1.0
            val ra = YgannBarnacleColonyDraw.radiusAt(a.arcPx, reveal, originArc)
            val rb = YgannBarnacleColonyDraw.radiusAt(a.arcPx + (b.arcPx - a.arcPx) * f, reveal, originArc)
            val r = (Math.max(ra, rb) + PICK_MARGIN_PX) / PX_PER_BLOCK
            segA.set(a.normal).mul(ra / PX_PER_BLOCK).add(a.pos)
            segB.set(b.pos).sub(a.pos).mul(f).add(a.pos).add(tmp.set(b.normal).mul(rb / PX_PER_BLOCK))
            val s = closestRayParam(segA, segB, r)
            if (s < 0.0) continue
            if (s < bestS) {
                bestS = s
                bestArc = a.arcPx + (b.arcPx - a.arcPx) * f * lastT
            }
        }
        if (bestS == Double.MAX_VALUE) return false
        hitArc = bestArc
        hitDistance = bestS * rayLen
        return true
    }

    /** Ray parameter (0..1 along [from]→[to]) where the ray enters the tube of [radius] round segment
     *  [a]→[b]; sets [lastT] to the segment parameter there. -1 when the ray passes farther. It is the
     *  entry, not the closest approach: on a glancing click the closest approach lies past the block
     *  face behind the limb, and the block would win the click. */
    private fun closestRayParam(a: Vector3d, b: Vector3d, radius: Double): Double {
        val d1 = dir1.set(to).sub(from)
        val d2 = dir2.set(b).sub(a)
        val r = rel.set(from).sub(a)
        val aa = d1.lengthSquared(); val ee = d2.lengthSquared()
        val ff = d2.dot(r)
        var s: Double
        var t: Double
        if (ee < 1.0e-12) {
            s = (-d1.dot(r) / aa).coerceIn(0.0, 1.0)
            t = 0.0
        } else {
            val c = d1.dot(r)
            val bb = d1.dot(d2)
            val denom = aa * ee - bb * bb
            s = if (denom > 1.0e-12) ((bb * ff - c * ee) / denom).coerceIn(0.0, 1.0) else 0.0
            t = (bb * s + ff) / ee
            if (t < 0.0) { t = 0.0; s = (-c / aa).coerceIn(0.0, 1.0) }
            else if (t > 1.0) { t = 1.0; s = ((bb - c) / aa).coerceIn(0.0, 1.0) }
        }
        val dx = from.x + d1.x * s - (a.x + d2.x * t)
        val dy = from.y + d1.y * s - (a.y + d2.y * t)
        val dz = from.z + d1.z * s - (a.z + d2.z * t)
        val dist2 = dx * dx + dy * dy + dz * dz
        if (dist2 > radius * radius) return -1.0
        lastT = t
        return (s - Math.sqrt(radius * radius - dist2) / Math.sqrt(aa)).coerceAtLeast(0.0)
    }

    /** [world] in [be]'s local frame (relative to its block position, shipyard axes on a ship), into [out]. */
    private fun toLocal(be: YgannBarnacleBlockEntity, ship: ClientShip?, world: Vec3, out: Vector3d) {
        out.set(world.x, world.y, world.z)
        ship?.renderTransform?.worldToShip?.transformPosition(out)
        out.sub(be.blockPos.x.toDouble(), be.blockPos.y.toDouble(), be.blockPos.z.toDouble())
    }

    private const val PX_PER_BLOCK = 16.0
    /** Extra pick radius (px), so a click on the tube's square edge still counts. */
    private const val PICK_MARGIN_PX = 0.75
    private const val MIN_HIT_REVEAL_PX = 1.0
}
