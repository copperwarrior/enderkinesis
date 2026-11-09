package org.shipwrights.enderkinesis.entity

import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.physics.ConstriliarShipController

/**
 * The physical gait: its stance anchors are the leg force points. In carry mode the
 * client draws these legs from the synced anchors and swing targets.
 */
class ConstriliarServerGait {

    val anchors = Array(4) { Vector3d() }
    private val homes = Array(4) { Vector3d() }
    private val hips = Array(4) { Vector3d() }
    private var bodyOrigin = Vec3.ZERO
    private val homeGrounded = BooleanArray(4)
    private val homeGrip = BooleanArray(4)
    private val gripping = BooleanArray(4)
    /** Feet that caught a surface while the body fell; kept until support returns. */
    private val caught = BooleanArray(4)
    private val axisX = Vector3d()
    private val axisZ = Vector3d()
    private val axisY = Vector3d()
    private val swingProgress = FloatArray(4) { PLANTED }
    private val grounded = BooleanArray(4)
    private val due = BooleanArray(4)
    private val lateTicks = IntArray(4)
    private val scratch = Vector3d()
    private val lead = Vector3d()
    /** In-plane offset of the centre of mass from the platform; the homes centre on it. */
    val comShift: Vector3dc get() = comShiftValue
    private val comShiftValue = Vector3d()
    private val planeA = Vector3d()
    private val planeB = Vector3d()
    private val e1 = Vector3d()
    private val e2 = Vector3d()
    private val triX = DoubleArray(3)
    private val triY = DoubleArray(3)
    private var sitBlend = 0f
    private var lastOrigin = Vec3.ZERO
    private val lastRotation = Quaterniond()
    private var initialized = false
    /** Reach: legs that grabbed the next face of the path, held there until the body
     *  stands on that face. Set each tick by [setReach]. */
    private val reachHeld = BooleanArray(4)
    private val reachFoot = Array(4) { Vector3d() }
    private var reachActive = false
    private val reachNormal = Vector3d()
    private val reachPoint = Vector3d()
    private val reachDir = Vector3d()
    private var reachWaitTicks = 0
    /** A lead leg still has to grab the next face: the body waits for it. */
    var reachPending = false
        private set
    /** Squeeze: the way on through a slot (world, unit). Set each tick by [setSqueeze]. */
    private var squeezeActive = false
    private val squeezeDir = Vector3d()
    private var squeezeGap = 0
    /** The walker's own ship, read once per tick for every leg ray. */
    private var ownShip: Long? = null
    private val fallbackMemo = Array(4) { LegMemo() }
    private val catchMissMemo = Array(4) { LegMemo() }

    /** Re-plant every foot at its home on the next tick. Anchors go stale while the
     *  struts are off, and a stale stance anchor pulls the body to an old height. */
    fun reset() {
        initialized = false
        swingProgress.fill(PLANTED)
        due.fill(false)
    }

    /** [desiredPerTick]: the commanded walk velocity, for the Raibert feedback term. */
    fun tick(entity: ConstriliarEntity, level: ServerLevel, desiredPerTick: Vector3dc) {
        ownShip = entity.ownShipIdForCollision()
        val gait = entity.gaitClock()
        val lastSit = sitBlend
        sitBlend = (sitBlend + if (entity.isSitting()) ConstriliarGait.SIT_TUCK_RATE else -ConstriliarGait.SIT_TUCK_RATE)
            .coerceIn(0f, 1f)
        val up = entity.serverUp()
        // Same frame as the client's shipRenderPose (platform centre, ACTUAL ship
        // rotation), so the strut anchors sit where the drawn feet stand.
        val pose = entity.serverShipPose(level)
        // MODEL-frame convention: the client gait's homes live in bodyQuat = shipRot ∘
        // rotY(180°); without the flip each ±symmetric home index lands on the
        // diagonally opposite corner (legs crossed, forces at the wrong hips).
        val rotation = (pose?.second ?: entity.serverShipRotation()).rotateY(Math.PI)
        val origin = pose?.first ?: entity.currentLift().let { lift ->
            Vec3(entity.x + up.x * lift, entity.y + up.y * lift, entity.z + up.z * lift)
        }
        // Touchdown aims at the CURRENT home plus a surface-plane lead, cast with the
        // home so the foot lands on the surface under the moving body (client twin).
        ConstriliarGaitClock.footLead(gait, entity.deltaMovement, desiredPerTick, up, lead)
        planeA.set(if (Math.abs(up.x) < 0.9) 1.0 else 0.0, if (Math.abs(up.x) < 0.9) 0.0 else 1.0, 0.0)
        planeA.sub(up.x * planeA.dot(up), up.y * planeA.dot(up), up.z * planeA.dot(up)).normalize()
        up.cross(planeA, planeB)
        setBodyFrame(origin, rotation)
        if (desiredPerTick.lengthSquared() > 0.0) updateComShift(entity, level, origin, up)
        refreshHomes(entity, level, origin, rotation, up, lastSit)
        updateReach(entity, level, up)
        if (!initialized) {
            for (i in 0..3) {
                anchors[i].set(homes[i])
                grounded[i] = homeGrounded[i]
                gripping[i] = homeGrip[i]
            }
            initialized = true
            return
        }
        advanceSwings(ConstriliarGait.swingRate(entity.deltaMovement.length()))
        if (squeezeActive && !reachActive) {
            squeezeSteps()
        } else {
            startSwings(gait, up.y < ConstriliarShipController.CONE_MIN_UP_Y)
        }
    }

    /** One foot at a time, three standing: the foot furthest from its home lifts. Once
     *  every foot is on, none is far from home and the body has to follow. */
    private fun squeezeSteps() {
        if (squeezeGap > 0) {
            squeezeGap--
            return
        }
        if (swingProgress.any { it < PLANTED }) return
        var leg = -1
        var worst = ConstriliarGaitClock.SETTLE_SQ
        for (i in 0..3) {
            val errSq = anchors[i].distanceSquared(homes[i])
            if (errSq > worst) {
                worst = errSq
                leg = i
            }
        }
        if (leg < 0) return
        swingProgress[leg] = 0f
        due[leg] = false
        lateTicks[leg] = 0
        squeezeGap = SQUEEZE_STEP_GAP
    }

    /** The way on through a slot the body is lined up with, or null outside one. */
    fun setSqueeze(dir: Vector3dc?) {
        squeezeActive = dir != null
        if (dir != null) squeezeDir.set(dir) else squeezeDir.zero()
    }

    /** Homes follow the body; a planted foot's home leans with the centre of mass, a
     *  swinging one's also with the walk. */
    private fun refreshHomes(
        entity: ConstriliarEntity,
        level: ServerLevel,
        origin: Vec3,
        rotation: Quaterniond,
        up: Vector3d,
        lastSit: Float,
    ) {
        // In a squeeze every foot lands further on, so the feet work the body through.
        val sq = if (squeezeActive) SQUEEZE_LEAD else 0.0
        val shiftX = comShiftValue.x + squeezeDir.x * sq
        val shiftY = comShiftValue.y + squeezeDir.y * sq
        val shiftZ = comShiftValue.z + squeezeDir.z * sq
        val leadX = lead.x + shiftX
        val leadY = lead.y + shiftY
        val leadZ = lead.z + shiftZ
        // A still body with planted feet keeps last tick's homes; the periodic refresh
        // catches blocks that change under the feet.
        val still = initialized && !squeezeActive && swingProgress.all { it >= PLANTED } && sitBlend == lastSit &&
            origin.distanceToSqr(lastOrigin) < ConstriliarGait.STILL_EPS_SQ &&
            Math.abs(rotation.dot(lastRotation)) > ConstriliarGait.STILL_DOT &&
            entity.tickCount % ConstriliarGait.HOME_REFRESH_TICKS != 0
        if (!still) {
            lastOrigin = origin
            lastRotation.set(rotation)
            for (i in 0..3) {
                if (swingProgress[i] >= PLANTED) {
                    updateHome(entity, level, origin, rotation, up, i, shiftX, shiftY, shiftZ)
                } else {
                    updateHome(entity, level, origin, rotation, up, i, leadX, leadY, leadZ)
                }
            }
        }
    }

    /** Late touchdown: a swing that ends over no support keeps searching for a few
     *  ticks before it plants in the air. */
    private fun advanceSwings(rate: Float) {
        for (i in 0..3) {
            if (swingProgress[i] >= PLANTED) continue
            // A grab is a quick reach, whatever the walk's pace while it waits.
            swingProgress[i] += if (reachHeld[i]) Math.max(rate, REACH_SWING_RATE) else rate
            if (swingProgress[i] < PLANTED) continue
            if (!homeGrounded[i] && lateTicks[i] < LATE_TOUCHDOWN_TICKS) {
                lateTicks[i]++
                swingProgress[i] = LATE_HOLD
                continue
            }
            anchors[i].set(homes[i])
            grounded[i] = homeGrounded[i]
            gripping[i] = homeGrip[i]
            swingProgress[i] = PLANTED
        }
    }

    /** Contact schedule: a leg falls due when its clock window opens, and lifts once
     *  the support rule allows. A late foot so delays the next lifter, and the
     *  support never thins below the gait's. */
    private fun startSwings(gait: Float, climbing: Boolean) {
        for (i in 0..3) {
            if (swingProgress[i] < PLANTED) continue
            // Off a floor, three feet hold while one moves: two gripping feet barely
            // hold the body, one cannot. Feet that hold nothing still step at once.
            val othersSwinging = otherSwinging(i)
            val errSq = anchors[i].distanceSquared(homes[i])
            // A foot planted in the air holds nothing: it steps as soon as its home has
            // ground. One the body left beyond leg reach is drawn stretched short of it.
            val reach = ConstriliarShipController.LEG_REACH
            // A stretched foot still holds: it lets go only while two others stand.
            val pastReach = anchors[i].distanceSquared(hips[i]) > reach * reach && standingOthers(i) >= 2
            val lift = if ((!grounded[i] && homeGrounded[i]) || pastReach) {
                true
            } else if (reachPending || (reachActive && !homeGrounded[i])) {
                // At a face change other steps wait: a grab starts only with every foot down,
                // and a step into the air only delays it.
                false
            } else if (climbing && othersSwinging) {
                false
            } else if (gait < 0f) {
                due[i] = false
                ConstriliarGaitClock.mayStep(i, errSq, gait) { swingProgress[it] < PLANTED }
            } else {
                if (ConstriliarGaitClock.mayStep(i, errSq, gait) { false }) due[i] = true
                if (errSq <= ConstriliarGaitClock.SETTLE_SQ) due[i] = false
                // A trailing foot still waits for its diagonal pair: lifting out of turn
                // left two front feet, or one, under the body. Past reach it lifts above.
                (errSq > ConstriliarGait.FORCE_STEP_SQ || due[i]) &&
                    ConstriliarGaitClock.supportAllows(i, gait) { swingProgress[it] < PLANTED }
            }
            if (lift) {
                swingProgress[i] = 0f
                due[i] = false
                lateTicks[i] = 0
            }
        }
    }

    /** The next face the path turns onto, near enough to reach: its world normal, a
     *  point on it, and the travel direction along it. Null clears the reach. */
    fun setReach(normal: Vector3dc?, point: Vector3dc?, dir: Vector3dc?) {
        if (normal == null || point == null || dir == null) {
            reachActive = false
            return
        }
        reachActive = true
        reachNormal.set(normal)
        reachPoint.set(point)
        reachDir.set(dir)
    }

    /**
     * The two legs nearest the next face grab it before the body turns, one at a time
     * while the other three stand. A grab holds its foot until the body stands on that
     * face; the homes then land there too.
     */
    private fun updateReach(entity: ConstriliarEntity, level: ServerLevel, up: Vector3d) {
        val done = up.dot(reachNormal) >= REACH_DONE_DOT
        if (!reachActive || done || reachWaitTicks > REACH_WAIT_MAX_TICKS) {
            reachHeld.fill(false)
            reachPending = false
            if (!reachActive || done) reachWaitTicks = 0
            return
        }
        for (i in 0..3) {
            if (!reachHeld[i]) continue
            homes[i].set(reachFoot[i])
            homeGrounded[i] = true
            homeGrip[i] = true
        }
        val first = leadLeg(-1)
        val second = leadLeg(first)
        var pending = false
        for (leg in intArrayOf(first, second)) {
            if (reachHeld[leg]) {
                if (swingProgress[leg] < PLANTED) pending = true
                continue
            }
            val foot = reachTarget(entity, level, leg) ?: continue
            pending = true
            if (swingProgress.any { it < PLANTED }) continue
            reachFoot[leg].set(foot.x, foot.y, foot.z)
            homes[leg].set(foot.x, foot.y, foot.z)
            homeGrounded[leg] = true
            homeGrip[leg] = true
            reachHeld[leg] = true
            swingProgress[leg] = 0f
            due[leg] = false
            lateTicks[leg] = 0
        }
        reachPending = pending
        if (pending) reachWaitTicks++
    }

    /** The leg whose hip is nearest the next face, other than [skip]. */
    private fun leadLeg(skip: Int): Int {
        var best = -1
        var bestDist = Double.MAX_VALUE
        for (i in 0..3) {
            if (i == skip) continue
            val h = hips[i]
            val dist = Math.abs(
                (h.x - reachPoint.x) * reachNormal.x + (h.y - reachPoint.y) * reachNormal.y +
                    (h.z - reachPoint.z) * reachNormal.z,
            )
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    /** A grab on the next face for [leg]: from the hip's spot on that face, probe on
     *  along the travel until the face has a foothold the leg reaches. The line runs to
     *  just above the foot, so a leg may reach over a lip. */
    private fun reachTarget(entity: ConstriliarEntity, level: ServerLevel, leg: Int): Vec3? {
        val hip = hips[leg]
        val n = reachNormal
        val off = (hip.x - reachPoint.x) * n.x + (hip.y - reachPoint.y) * n.y + (hip.z - reachPoint.z) * n.z
        val hipV = Vec3(hip.x, hip.y, hip.z)
        val reach = ConstriliarShipController.LEG_REACH
        for (step in REACH_STEPS) {
            val cx = hip.x - n.x * off + reachDir.x * step
            val cy = hip.y - n.y * off + reachDir.y * step
            val cz = hip.z - n.z * off + reachDir.z * step
            val hit = ConstriliarGait.castLegRay(
                level, entity,
                Vec3(cx + n.x * REACH_PROBE, cy + n.y * REACH_PROBE, cz + n.z * REACH_PROBE),
                Vec3(cx - n.x * REACH_PROBE, cy - n.y * REACH_PROBE, cz - n.z * REACH_PROBE),
                ownShip,
            )
            if (hit.type == HitResult.Type.MISS || hit.isInside) continue
            val face = hit.direction
            if (face.stepX * n.x + face.stepY * n.y + face.stepZ * n.z < REACH_FACE_DOT) continue
            val foot = hit.location
            if (foot.distanceToSqr(hipV) > reach * reach || !clearOfBody(leg, foot)) continue
            if (clearOfWorld(entity, level, hipV, foot, face)) return foot
        }
        return null
    }

    private fun standingOthers(leg: Int): Int {
        var n = 0
        for (other in 0..3) if (other != leg && swingProgress[other] >= PLANTED && grounded[other]) n++
        return n
    }

    private fun otherSwinging(leg: Int): Boolean {
        for (other in 0..3) if (other != leg && swingProgress[other] < PLANTED) return true
        return false
    }

    /** Where a swinging foot will land; its anchor when planted. */
    fun footTarget(leg: Int): Vector3d = if (swingProgress[leg] < PLANTED) homes[leg] else anchors[leg]

    /** A stance leg: planted on real support — a supplemental-spring force point. */
    fun isStance(leg: Int): Boolean = swingProgress[leg] >= PLANTED && grounded[leg]

    fun isSwinging(leg: Int): Boolean = swingProgress[leg] < PLANTED

    fun stanceCount(): Int = (0..3).count { isStance(it) }

    fun isLimp(leg: Int): Boolean = swingProgress[leg] >= PLANTED && !grounded[leg]

    /** Planted feet that found no ground: support the gait expects but lacks. */
    fun missingSupport(): Int = (0..3).count { swingProgress[it] >= PLANTED && !grounded[it] }

    internal fun debugLegs(): List<String> = (0..3).map { i ->
        val state = when {
            swingProgress[i] < PLANTED -> "swing ${ConstriliarDebugLog.f(swingProgress[i].toDouble())} late=${lateTicks[i]}"
            grounded[i] && gripping[i] -> "stance-grip"
            grounded[i] -> "stance"
            else -> "planted-in-air"
        }
        "leg ${LEG_NAMES[i]}: $state anchor=${ConstriliarDebugLog.v(anchors[i])} " +
            "home=${ConstriliarDebugLog.v(homes[i])} homeGround=${homeGrounded[i]} " +
            "drift=${ConstriliarDebugLog.f(anchors[i].distance(homes[i]))} due=${due[i]}" +
            (if (reachHeld[i]) " grab" else "") + (if (i == 0 && reachActive) " reach pending=$reachPending" else "")
    }

    /** Centroid and normal (on [up]'s side) of the grounded footholds. A swinging foot
     *  counts at its last plant, so the plane holds through a trot. False under three
     *  footholds: two make a line, not a plane. */
    fun footPlane(up: Vector3dc, centroid: Vector3d, normal: Vector3d): Boolean {
        if (!initialized) return false
        var count = 0
        centroid.zero()
        for (i in 0..3) {
            if (!grounded[i]) continue
            centroid.add(anchors[i])
            count++
        }
        if (count < 3) return false
        centroid.div(count.toDouble())
        // Newell's method over the footholds in leg order, which runs round the body.
        normal.zero()
        var first = -1
        var prev = -1
        for (i in 0..3) {
            if (!grounded[i]) continue
            if (prev < 0) first = i else addNewell(prev, i, centroid, normal)
            prev = i
        }
        addNewell(prev, first, centroid, normal)
        if (normal.lengthSquared() < 1.0e-8) return false
        normal.normalize()
        if (normal.dot(up) < 0.0) normal.negate()
        return true
    }

    /**
     * Crawl body shift: the in-plane move, into [out], that puts [com] inside the
     * support triangle of the next lifter's three other feet by [margin]. False when no
     * shift applies (no crawl, or a support foot is off the ground).
     */
    fun crawlShift(gait: Float, com: Vector3dc, up: Vector3dc, margin: Double, out: Vector3d): Boolean {
        val lifter = ConstriliarGaitClock.nextCrawlLifter(gait)
        if (lifter < 0 || !initialized) return false
        // In-plane basis, so the triangle test is 2D.
        e1.set(if (Math.abs(up.x()) < 0.9) 1.0 else 0.0, if (Math.abs(up.x()) < 0.9) 0.0 else 1.0, 0.0)
        e1.sub(up.x() * e1.dot(up), up.y() * e1.dot(up), up.z() * e1.dot(up)).normalize()
        up.cross(e1, e2)
        var n = 0
        for (i in 0..3) {
            if (i == lifter) continue
            if (!grounded[i]) return false
            triX[n] = anchors[i].dot(e1)
            triY[n] = anchors[i].dot(e2)
            n++
        }
        val px = com.dot(e1)
        val py = com.dot(e2)
        val cx = (triX[0] + triX[1] + triX[2]) / 3.0
        val cy = (triY[0] + triY[1] + triY[2]) / 3.0
        val area2 = (triX[1] - triX[0]) * (triY[2] - triY[0]) - (triY[1] - triY[0]) * (triX[2] - triX[0])
        if (Math.abs(area2) < 1.0e-6) return false
        // Edge distance is linear along com → centroid, so solve each edge for the
        // share of that path that clears the margin, and take the largest.
        var t = 0.0
        for (k in 0..2) {
            val d0 = edgeDistance(k, px, py, area2)
            if (d0 >= margin) continue
            val dc = edgeDistance(k, cx, cy, area2)
            t = Math.max(t, if (dc > d0) ((margin - d0) / (dc - d0)).coerceAtMost(1.0) else 1.0)
        }
        if (t <= 0.0) return false
        out.set(e1).mul((cx - px) * t).add(e2.x * (cy - py) * t, e2.y * (cy - py) * t, e2.z * (cy - py) * t)
        return true
    }

    /** Signed distance from edge [k] of the 2D support triangle; positive inside. */
    private fun edgeDistance(k: Int, px: Double, py: Double, area2: Double): Double {
        val ax = triX[k]; val ay = triY[k]
        val bx = triX[(k + 1) % 3]; val by = triY[(k + 1) % 3]
        val len = Math.hypot(bx - ax, by - ay)
        if (len < 1.0e-9) return 0.0
        return Math.signum(area2) * ((bx - ax) * (py - ay) - (by - ay) * (px - ax)) / len
    }

    private fun addNewell(a: Int, b: Int, centroid: Vector3d, normal: Vector3d) {
        val p = anchors[a]
        val q = anchors[b]
        val ax = p.x - centroid.x; val ay = p.y - centroid.y; val az = p.z - centroid.z
        val bx = q.x - centroid.x; val by = q.y - centroid.y; val bz = q.z - centroid.z
        normal.add(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
    }

    /** Server twin of the client gait's groundedHome — same offsets, same ray. */
    private fun updateHome(
        entity: ConstriliarEntity,
        level: ServerLevel,
        origin: Vec3,
        rotation: Quaterniond,
        up: Vector3d,
        leg: Int,
        leadX: Double,
        leadY: Double,
        leadZ: Double,
    ) {
        val tuck = ConstriliarGait.tuckScale(sitBlend).toDouble()
        val local = rotation.transform(
            scratch.set(ConstriliarGait.HOME_LOCAL_X[leg] * tuck, 0.0, ConstriliarGait.HOME_LOCAL_Z[leg] * tuck),
        )
        val hip = origin.add(
            local.x * HIP_SHARE + up.x * HIP_UP, local.y * HIP_SHARE + up.y * HIP_UP, local.z * HIP_SHARE + up.z * HIP_UP,
        )
        hips[leg].set(hip.x, hip.y, hip.z)
        val homePos = origin.add(local.x + leadX, local.y + leadY, local.z + leadZ)
        val hit = ConstriliarGait.castFootRay(level, entity, homePos, up.x, up.y, up.z, ownShip)
        // The ray runs past leg reach: a home it finds down a ledge would plant stretched,
        // hold no load, and lift again at once.
        val reach = ConstriliarShipController.LEG_REACH
        homeGrip[leg] = false
        val direct = if (hit.type != HitResult.Type.MISS && hit.location.distanceToSqr(hip) <= reach * reach) {
            ConstriliarEntity.fluidAdjustedSurface(level, hit.location)
                .takeIf { clearOfWorld(entity, level, hip, it, hit.direction) }
        } else {
            null
        }
        if (direct != null) fallbackMemo[leg].live = false
        val foot = direct ?: fallbackFoothold(entity, level, homePos, hip, up, leg)
        homeGrounded[leg] = foot != null
        val placed = foot ?: homePos.add(0.0, -ConstriliarGait.DROOP, 0.0)
        homes[leg].set(placed.x, placed.y, placed.z)
    }

    /** The searches for a home with no ground under it. They cost tens of rays, so a
     *  planted leg whose home and hip stay put keeps its last result for a few ticks. */
    private fun fallbackFoothold(
        entity: ConstriliarEntity,
        level: ServerLevel,
        homePos: Vec3,
        hip: Vec3,
        up: Vector3d,
        leg: Int,
    ): Vec3? {
        val memo = fallbackMemo[leg]
        val reach = ConstriliarShipController.LEG_REACH
        if (swingProgress[leg] >= PLANTED &&
            memo.matches(homePos, hip, up, entity.tickCount, FALLBACK_MEMO_EPS_SQ, FALLBACK_MEMO_TICKS) &&
            (!memo.found || memo.foot.distanceSquared(hip.x, hip.y, hip.z) <= reach * reach)
        ) {
            homeGrip[leg] = memo.grip
            return if (memo.found) Vec3(memo.foot.x, memo.foot.y, memo.foot.z) else null
        }
        val foot = sideGrip(entity, level, homePos, hip, up, leg)?.also { homeGrip[leg] = true }
            ?: reachFoothold(entity, level, homePos, hip, up, leg)
            ?: fanFoothold(entity, level, homePos, hip, up, leg)
        memo.store(homePos, hip, up, entity.tickCount, foot, homeGrip[leg])
        return foot
    }

    /**
     * A home off the edge of a narrow support (a beam, a ledge): grip its side face. Rays
     * run in, just below the body's surface, toward the body's two axis lines and its
     * centre; the nearest hit to the home is a gripping foothold. Two sides so gripped
     * clamp a beam, which a line of feet on its top cannot.
     */
    private fun sideGrip(entity: ConstriliarEntity, level: ServerLevel, homePos: Vec3, hip: Vec3, up: Vector3d, leg: Int): Vec3? {
        val start = homePos.add(-up.x * GRIP_DROP, -up.y * GRIP_DROP, -up.z * GRIP_DROP)
        var best: Vec3? = null
        var bestSq = Double.MAX_VALUE
        for (axis in 0..2) {
            val t = scratch.set(bodyOrigin.x - start.x, bodyOrigin.y - start.y, bodyOrigin.z - start.z)
            if (axis < 2) {
                val along = if (axis == 0) axisX else axisZ
                t.sub(along.x * t.dot(along), along.y * t.dot(along), along.z * t.dot(along))
            }
            t.sub(up.x * t.dot(up), up.y * t.dot(up), up.z * t.dot(up))
            if (t.lengthSquared() < 1.0e-6) continue
            t.normalize(GRIP_RAY)
            val hit = ConstriliarGait.castLegRay(
                level, entity, start, start.add(t.x, t.y, t.z), ownShip,
            )
            if (hit.type == HitResult.Type.MISS || hit.isInside ||
                !legReaches(entity, level, leg, hit.location, hit.direction)
            ) {
                continue
            }
            val reach = ConstriliarShipController.LEG_REACH
            if (hit.location.distanceToSqr(hip) > reach * reach) continue
            val distSq = hit.location.distanceToSqr(homePos)
            if (distSq < bestSq) {
                bestSq = distSq
                best = hit.location
            }
        }
        return best
    }

    /** Real support again (the plate's rays, or standing feet): caught feet are plain
     *  footholds from here, and the next fall sweeps afresh. */
    fun releaseCatch() {
        caught.fill(false)
    }

    /**
     * Falling: each free leg sweeps its hip fan, and one that meets a surface within
     * reach grips it. The other feet hang loose. Returns the feet that hold.
     */
    fun catchHold(entity: ConstriliarEntity, level: ServerLevel): Int {
        val pose = entity.serverShipPose(level) ?: return 0
        ownShip = entity.ownShipIdForCollision()
        val up = entity.serverUp()
        val origin = pose.first
        val rotation = Quaterniond(pose.second).rotateY(Math.PI)
        setBodyFrame(origin, rotation)
        var held = 0
        for (leg in 0..3) {
            if (caught[leg] && grounded[leg]) {
                held++
                continue
            }
            caught[leg] = false
            grounded[leg] = false
            gripping[leg] = false
            swingProgress[leg] = PLANTED
            val local = rotation.transform(
                scratch.set(ConstriliarGait.HOME_LOCAL_X[leg].toDouble(), 0.0, ConstriliarGait.HOME_LOCAL_Z[leg].toDouble()),
            )
                val home = origin.add(local.x, local.y, local.z)
            val hip = origin.add(
                local.x * HIP_SHARE + up.x * HIP_UP, local.y * HIP_SHARE + up.y * HIP_UP, local.z * HIP_SHARE + up.z * HIP_UP,
            )
            hips[leg].set(hip.x, hip.y, hip.z)
            // A free foot hangs below its rest spot, not at its last stance before the fall.
            anchors[leg].set(home.x, home.y - ConstriliarGait.DROOP, home.z)
            // A hip that has barely moved since its last miss misses again.
            val miss = catchMissMemo[leg]
            if (miss.matches(hip, hip, up, entity.tickCount, CATCH_MISS_EPS_SQ, CATCH_MISS_TICKS)) continue
            val hit = fanFoothold(entity, level, home, hip, up, leg)
            miss.store(hip, hip, up, entity.tickCount, hit, false)
            if (hit == null) continue
            miss.live = false
            anchors[leg].set(hit.x, hit.y, hit.z)
            homes[leg].set(hit.x, hit.y, hit.z)
            grounded[leg] = true
            gripping[leg] = true
            caught[leg] = true
            held++
        }
        initialized = true
        return held
    }

    fun gripCount(): Int = (0..3).count { isStance(it) && gripping[it] }

    /** Footing for a body at [origin] turned to [rotation] (model frame): two per rest
     *  spot with ground under it, one per spot that can only grip a side face. */
    fun footingScore(entity: ConstriliarEntity, level: ServerLevel, origin: Vec3, rotation: Quaterniond, up: Vector3d): Int {
        var score = 0
        val own = entity.ownShipIdForCollision()
        for (leg in 0..3) {
            val local = rotation.transform(
                scratch.set(ConstriliarGait.HOME_LOCAL_X[leg].toDouble(), 0.0, ConstriliarGait.HOME_LOCAL_Z[leg].toDouble()),
            )
            val home = origin.add(local.x, local.y, local.z)
            if (ConstriliarGait.castFootRay(level, entity, home, up.x, up.y, up.z, own).type != HitResult.Type.MISS) {
                score += 2
                continue
            }
            val start = home.add(-up.x * GRIP_DROP, -up.y * GRIP_DROP, -up.z * GRIP_DROP)
            val inward = Vec3(origin.x - start.x, origin.y - start.y, origin.z - start.z)
            val hit = ConstriliarGait.castLegRay(level, entity, start, start.add(inward.normalize().scale(GRIP_RAY)), own)
            if (hit.type != HitResult.Type.MISS && !hit.isInside) score += 1
        }
        return score
    }

    /** A home with no ground under it: sweep the surface plane outward from the home,
     *  nearest first, for ground the leg reaches from its hip. The feet narrow onto a
     *  ledge or beam, and reach across a gap. */
    private fun reachFoothold(
        entity: ConstriliarEntity,
        level: ServerLevel,
        homePos: Vec3,
        hip: Vec3,
        up: Vector3d,
        leg: Int,
    ): Vec3? {
        // A foot lies on the probe's line along up, moved at most by the fluid nudge: a
        // probe this far from the hip across the plane cannot pass the reach check.
        val far = ConstriliarShipController.LEG_REACH + FLUID_NUDGE_MAX
        for (k in SEARCH_A.indices) {
            val a = SEARCH_A[k]
            val b = SEARCH_B[k]
            val vx = homePos.x + planeA.x * a + planeB.x * b - hip.x
            val vy = homePos.y + planeA.y * a + planeB.y * b - hip.y
            val vz = homePos.z + planeA.z * a + planeB.z * b - hip.z
            val along = vx * up.x + vy * up.y + vz * up.z
            if (vx * vx + vy * vy + vz * vz - along * along > far * far) continue
            probeFoothold(
                entity, level, homePos,
                planeA.x * a + planeB.x * b, planeA.y * a + planeB.y * b, planeA.z * a + planeB.z * b,
                hip, up,
            )?.takeIf { legReaches(entity, level, leg, it, Direction.getNearest(up.x, up.y, up.z)) }?.let { return it }
        }
        return null
    }

    /** Last resort: rays out from the hip in a fan, so a foot grips any face the leg
     *  reaches (the side of a ledge, a wall beside the body). Nearest the home wins. */
    private fun fanFoothold(
        entity: ConstriliarEntity,
        level: ServerLevel,
        homePos: Vec3,
        hip: Vec3,
        up: Vector3d,
        leg: Int,
    ): Vec3? {
        val out = scratch.set(homePos.x - hip.x, homePos.y - hip.y, homePos.z - hip.z)
        out.sub(up.x * out.dot(up), up.y * out.dot(up), up.z * out.dot(up))
        if (out.lengthSquared() < 1.0e-6) return null
        out.normalize()
        val ox = out.x; val oy = out.y; val oz = out.z
        // Side axis in the plane, for the fan's yaw.
        val sx = up.y * oz - up.z * oy; val sy = up.z * ox - up.x * oz; val sz = up.x * oy - up.y * ox
        val reach = ConstriliarShipController.LEG_REACH
        var best: Vec3? = null
        var bestSq = Double.MAX_VALUE
        for (pitch in FAN_PITCH) {
            for (yaw in FAN_YAW) {
                val cp = Math.cos(pitch); val sp = Math.sin(pitch)
                val cy = Math.cos(yaw); val sy2 = Math.sin(yaw)
                val dx = (ox * cy + sx * sy2) * cp - up.x * sp
                val dy = (oy * cy + sy * sy2) * cp - up.y * sp
                val dz = (oz * cy + sz * sy2) * cp - up.z * sp
                val hit = ConstriliarGait.castLegRay(
                    level, entity, hip, hip.add(dx * reach, dy * reach, dz * reach), ownShip,
                )
                if (hit.type == HitResult.Type.MISS || !legReaches(entity, level, leg, hit.location, hit.direction)) continue
                val distSq = hit.location.distanceToSqr(homePos)
                if (distSq < bestSq) {
                    bestSq = distSq
                    best = hit.location
                }
            }
        }
        return best?.let { ConstriliarEntity.fluidAdjustedSurface(level, it) }
    }

    /** Cargo moves the centre of mass off the platform: the feet spread around it, so
     *  the weight does not fall outside the stance. Set only while walking, so a still
     *  body does not shuffle when cargo moves. */
    private fun updateComShift(entity: ConstriliarEntity, level: ServerLevel, origin: Vec3, up: Vector3d) {
        if (!entity.serverCom(level, comShiftValue)) {
            comShiftValue.zero()
            return
        }
        comShiftValue.sub(origin.x, origin.y, origin.z)
        comShiftValue.sub(up.x * comShiftValue.dot(up), up.y * comShiftValue.dot(up), up.z * comShiftValue.dot(up))
        val len = comShiftValue.length()
        if (len > COM_SHIFT_MAX) comShiftValue.mul(COM_SHIFT_MAX / len)
    }

    private fun setBodyFrame(origin: Vec3, rotation: Quaterniond) {
        bodyOrigin = origin
        rotation.transform(axisX.set(1.0, 0.0, 0.0))
        rotation.transform(axisY.set(0.0, 1.0, 0.0))
        rotation.transform(axisZ.set(0.0, 0.0, 1.0))
    }

    /** Any foothold is valid while the leg from its hip does not pass through the
     *  plate. Cargo above the plate does not count. */
    private fun clearOfBody(leg: Int, foot: Vec3): Boolean {
        val hip = hips[leg]
        for (k in 1..BODY_SAMPLES) {
            val t = k.toDouble() / BODY_SAMPLES
            val dx = hip.x + (foot.x - hip.x) * t - bodyOrigin.x
            val dy = hip.y + (foot.y - hip.y) * t - bodyOrigin.y
            val dz = hip.z + (foot.z - hip.z) * t - bodyOrigin.z
            val lx = dx * axisX.x + dy * axisX.y + dz * axisX.z
            val ly = dx * axisY.x + dy * axisY.y + dz * axisY.z
            val lz = dx * axisZ.x + dy * axisZ.y + dz * axisZ.z
            val inside = Math.abs(lx) < PLATE_HALF + LEG_RADIUS && Math.abs(lz) < PLATE_HALF + LEG_RADIUS &&
                ly > PLATE_BOTTOM - LEG_RADIUS && ly < PLATE_TOP + LEG_RADIUS
            if (inside) return false
        }
        return true
    }

    private fun legReaches(entity: ConstriliarEntity, level: ServerLevel, leg: Int, foot: Vec3, face: Direction): Boolean {
        val hip = hips[leg]
        return clearOfBody(leg, foot) && clearOfWorld(entity, level, Vec3(hip.x, hip.y, hip.z), foot, face)
    }

    /** No block between the hip and a point just off the foot's [face]: a leg under a
     *  thin cap planted on its top through it. Ending off the face lets the knee wrap
     *  round an edge: a line to the foot itself cut the corner of a stem and barred
     *  every side grip. */
    private fun clearOfWorld(entity: ConstriliarEntity, level: ServerLevel, hip: Vec3, foot: Vec3, face: Direction): Boolean {
        val end = foot.add(face.stepX * FOOT_LOS_LIFT, face.stepY * FOOT_LOS_LIFT, face.stepZ * FOOT_LOS_LIFT)
        if (lineClear(entity, level, hip, end)) return true
        // Bent round one edge: the knee at the hip's spot over the foot's face, just
        // off it. A hip behind the face's plane (over a rim) reaches under it so.
        val behind = (hip.x - end.x) * face.stepX + (hip.y - end.y) * face.stepY + (hip.z - end.z) * face.stepZ
        val knee = hip.subtract(face.stepX * behind, face.stepY * behind, face.stepZ * behind)
        return hip.distanceTo(knee) + knee.distanceTo(foot) <= BENT_REACH &&
            lineClear(entity, level, hip, knee) && lineClear(entity, level, knee, end)
    }

    private fun lineClear(entity: ConstriliarEntity, level: ServerLevel, from: Vec3, to: Vec3): Boolean {
        val hit = ConstriliarGait.castLegRay(level, entity, from, to, ownShip)
        return hit.type == HitResult.Type.MISS || hit.isInside
    }


    /** Centroid of the grounded stance feet into [out]; returns how many there are. */
    fun stanceCentroid(out: Vector3d): Int {
        out.zero()
        var n = 0
        for (i in 0..3) {
            if (!isStance(i)) continue
            out.add(anchors[i])
            n++
        }
        if (n > 0) out.div(n.toDouble())
        return n
    }

    private fun probeFoothold(
        entity: ConstriliarEntity,
        level: ServerLevel,
        homePos: Vec3,
        dx: Double,
        dy: Double,
        dz: Double,
        hip: Vec3,
        up: Vector3d,
    ): Vec3? {
        val probe = homePos.add(dx, dy, dz)
        val hit = ConstriliarGait.castFootRay(level, entity, probe, up.x, up.y, up.z, ownShip)
        if (hit.type == HitResult.Type.MISS) return null
        val foot = ConstriliarEntity.fluidAdjustedSurface(level, hit.location)
        val reach = ConstriliarShipController.LEG_REACH
        return if (foot.distanceToSqr(hip) <= reach * reach) foot else null
    }

    companion object {
        /** Swing progress of a planted foot (client gait's stepProgress convention). */
        private const val PLANTED = 1f
        private const val LATE_HOLD = 0.95f
        private const val LATE_TOUCHDOWN_TICKS = 6
        private val LEG_NAMES = arrayOf("FR", "FL", "BL", "BR")
        /** Hip on the home diagonal: |(0.4375, 0.4375)| of the home radius. */
        private const val HIP_SHARE = 0.4375 / ConstriliarGait.HOME_R
        /** The model's leg roots sit at 18 px: (24 - 18) / 16 above the plate centre. */
        const val HIP_UP = 0.375
        /** Hip fan: below the outward line (0) through straight down the body (90°) to
         *  tucked inward (120°), and swung to either side. */
        private val FAN_PITCH = doubleArrayOf(0.0, 0.5236, 1.0472, 1.5708, 2.0944)
        private val FAN_YAW = doubleArrayOf(0.0, -0.6109, 0.6109)
        /** How far below the body's surface a side grip takes hold, and its ray length. */
        private const val GRIP_DROP = 0.4
        private const val GRIP_RAY = 1.2
        /** Least reach out along a leg's corner, from the body centre (blocks). */
        /** The plate in body blocks: half width, and its underside and top above the origin. */
        private const val PLATE_HALF = 0.5
        private const val PLATE_BOTTOM = 0.25
        private const val PLATE_TOP = 0.5
        private const val LEG_RADIUS = 1.0 / 16.0
        private const val BODY_SAMPLES = 4
        private const val FOOT_LOS_LIFT = 0.25
        /** Thigh plus calf (blocks), for a leg bent round an edge. */
        private const val BENT_REACH = (14.4503 + 14.3053) / 16.0
        /** Reach: probe steps along the travel from the hip's spot on the next face, the
         *  probe's depth each side of the face, how square the hit face must be, when the
         *  body counts as turned, and how long the body waits for grabs. */
        private val REACH_STEPS = doubleArrayOf(0.0, 0.5, 1.0, 1.5)
        private const val REACH_PROBE = 0.6
        private const val REACH_FACE_DOT = 0.9
        private const val REACH_DONE_DOT = 0.87
        private const val REACH_WAIT_MAX_TICKS = 40
        private const val REACH_SWING_RATE = 0.4f
        /** Squeeze: how far on each foot lands (blocks), and the pause between steps (ticks). */
        private const val SQUEEZE_LEAD = 0.6
        private const val SQUEEZE_STEP_GAP = 4
        /** Most the homes move toward the centre of mass (blocks); more takes the far feet past reach. */
        private const val COM_SHIFT_MAX = 0.5
        /** Most [ConstriliarEntity.fluidAdjustedSurface] lowers a foot, plus a margin. */
        private const val FLUID_NUDGE_MAX = 0.3
        /** A planted leg's fallback result holds while its home and hip move less than this. */
        private const val FALLBACK_MEMO_EPS_SQ = 0.1 * 0.1
        private const val FALLBACK_MEMO_TICKS = 10
        /** A falling leg's fan miss holds while its hip moves less than this. */
        private const val CATCH_MISS_EPS_SQ = 0.25 * 0.25
        private const val CATCH_MISS_TICKS = 5
        private const val MEMO_UP_DOT = 0.9995
        private const val SEARCH_STEP = 0.35
        private const val SEARCH_RADIUS = 1.1
        private val SEARCH_OFFSETS: List<Pair<Double, Double>> = run {
            val n = (SEARCH_RADIUS / SEARCH_STEP).toInt()
            val all = ArrayList<Pair<Double, Double>>()
            for (i in -n..n) {
                for (j in -n..n) {
                    val a = i * SEARCH_STEP
                    val b = j * SEARCH_STEP
                    if ((i != 0 || j != 0) && a * a + b * b <= SEARCH_RADIUS * SEARCH_RADIUS) all.add(a to b)
                }
            }
            all.sortedBy { it.first * it.first + it.second * it.second }
        }
        /** Plane offsets of the foothold sweep, nearest first. */
        private val SEARCH_A = DoubleArray(SEARCH_OFFSETS.size) { SEARCH_OFFSETS[it].first }
        private val SEARCH_B = DoubleArray(SEARCH_OFFSETS.size) { SEARCH_OFFSETS[it].second }
    }

    /** One leg's last search: where it ran and what it found. */
    private class LegMemo {
        val home = Vector3d()
        val hip = Vector3d()
        val up = Vector3d()
        val foot = Vector3d()
        var tick = 0
        var live = false
        var found = false
        var grip = false

        fun matches(home: Vec3, hip: Vec3, up: Vector3dc, tick: Int, epsSq: Double, maxAge: Int): Boolean =
            live && tick - this.tick in 0 until maxAge && up.dot(this.up) > MEMO_UP_DOT &&
                this.home.distanceSquared(home.x, home.y, home.z) < epsSq &&
                this.hip.distanceSquared(hip.x, hip.y, hip.z) < epsSq

        fun store(home: Vec3, hip: Vec3, up: Vector3dc, tick: Int, foot: Vec3?, grip: Boolean) {
            this.home.set(home.x, home.y, home.z)
            this.hip.set(hip.x, hip.y, hip.z)
            this.up.set(up)
            this.tick = tick
            live = true
            found = foot != null
            if (foot != null) this.foot.set(foot.x, foot.y, foot.z)
            this.grip = grip
        }
    }
}
