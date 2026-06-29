package org.shipwrights.enderkinesis.block

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Clearable
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import org.joml.Quaterniond
import org.joml.Vector3i
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.joml.Vector3d
import org.shipwrights.enderkinesis.physics.BindingRootsPullAttachment
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.core.api.attachment.getOrPutAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.internal.joints.VSFixedJoint
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.vsCore

/**
 * Two-path merger triggered when a candidate binding_roots pair passes [checkPair] (snap
 * distance, anti-parallel ≤ ~5°, cardinal ≤ ~5°). The path is chosen by world-up alignment:
 *
 *  - **Up-aligned → [performBlockMerge]**: relative rotation is a pure world-Y yaw, so the
 *    candidate's blocks transfer via vanilla `state.rotate(Rotation)` and the candidate
 *    ship is reaped. One body, no joint.
 *  - **Tilted → [performJointWeld]**: vanilla `Rotation` can't model non-Y rotations, so we
 *    snap to the nearest octahedral and create a [VSFixedJoint] welding the two anchors.
 *    [activeJoints] tracks the joint id; [checkPair] gates engaged anchors so the spring
 *    doesn't fight the joint.
 *
 * Joint welds persist via [BindingRootsSavedData] (anchor positions + ship ids + pose pair),
 * replayed on `SERVER_LEVEL_LOAD` by [restoreSavedJoints]; records whose anchor blocks are
 * gone are pruned rather than welding to phantoms. Block merges need no persistence — the
 * candidate's blocks live in the host.
 */
object BindingRootsMerger {

    private val LOG = LogUtils.getLogger()

    // Engagement gate is now a block-volume intersection check (per-axis |delta| < 1 — see
    // [arePairIntersecting]) rather than a Euclidean radius. The player has to physically
    // overlap the two roots' 1×1×1 cubes for the spring + PD torque + snap to engage.
    private const val SNAP_DISTANCE_BLOCKS: Double = 0.5

    /** Engagement check: facings must be at least pointing TOWARD each other (dot < 0).
     *  Tight anti-parallel is enforced separately at the snap gate. Loose engagement lets
     *  the alignment torque pull the ships into anti-parallel orientation. */
    private const val FACING_ENGAGEMENT_DOT_THRESHOLD: Double = 0.0

    /** Snap-gate tolerance: facings must be ≤ ~5° from exactly anti-parallel for the merge
     *  to fire. The alignment torque drives the ships here. */
    private const val FACING_ANTIPARALLEL_TOLERANCE_RAD: Double = 0.087266

    private const val PERPENDICULAR_TOLERANCE_BLOCKS: Double = 0.5

    /** Snap-gate tolerance for cardinal alignment. Each shipyard axis's world-direction
     *  largest-magnitude component must be ≥ this value (cos(5°) ≈ 0.9962). */
    private const val CARDINAL_TOLERANCE_COS: Double = 0.9962

    /** Proportional gain on the linear pull, N/block. P force at the anchor = Kp × distance.
     *  Paired with [ATTRACTION_KD] for damping — this is a PD controller, not a pure
     *  spring. Critical-damping ratio at Kp=5 000 and Kd=4 000 gives ~1 s settling time
     *  for a typical 50–500 kg ship, matching SE merge-block feel. */
    private const val ATTRACTION_KP: Double = 5_000.0

    /** Derivative gain on the linear pull, N·s/m. D force = -Kd × ship.velocity. Damps the
     *  ship's absolute velocity (correct when host is world; for ship-ship pairs each ship
     *  is damped against world too, which still suppresses oscillation since both ships
     *  approach the meeting point and slow on the way). */
    private const val ATTRACTION_KD: Double = 4_000.0

    /** Proportional gain on the cardinal-alignment torque, N·m per sin(angle). P torque =
     *  Kp × Σ (currentAxis × nearestCardinal). With matching [ALIGNMENT_KD] this critically
     *  damps the rotation so the ship settles into cardinal without oscillating. */
    private const val ALIGNMENT_KP: Double = 10_000.0

    /** Derivative gain on the alignment torque, N·m·s/rad. D torque = -Kd × angularVelocity. */
    private const val ALIGNMENT_KD: Double = 4_000.0

    /** Tolerance for treating two ships' uniform scales as equal. Mismatched
     *  scales force the joint-weld path instead of block-merge, since vanilla
     *  `state.rotate(Rotation)` can't represent a scale change. */
    private const val SCALE_MATCH_EPSILON: Double = 1e-4

    private val positions: MutableMap<ServerLevel, MutableSet<Long>> = mutableMapOf()

    @JvmStatic
    fun registerPosition(level: ServerLevel, pos: BlockPos) {
        positions.getOrPut(level) { mutableSetOf() }.add(pos.asLong())
        BindingRootsSavedData.forLevel(level).addAnchor(pos.asLong())
    }

    @JvmStatic
    fun unregisterPosition(level: ServerLevel, pos: BlockPos) {
        positions[level]?.remove(pos.asLong())
        BindingRootsSavedData.forLevel(level).removeAnchor(pos.asLong())
    }

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(TickEvent.ServerLevelTick { level -> tickLevel(level) })
        LifecycleEvent.SERVER_LEVEL_LOAD.register { level -> restoreSavedJoints(level) }
        LifecycleEvent.SERVER_STOPPED.register { _ ->
            positions.clear()
            activeJoints.clear()
        }
    }

    /** Recreate the VS2 fixed joints saved in [BindingRootsSavedData] for this level after a
     *  world load. VS2 joints are themselves transient (not VS-serialised), so we save the
     *  recipe and re-issue `gtpa.addJoint` here. Records whose binding_roots blocks are
     *  missing (chunks failed to save, /setblock-ed away, etc.) are dropped instead of
     *  welding to a phantom block — the joint would otherwise be unbreakable.
     *
     *  Also re-populates the in-memory [positions] watch-set from the persisted anchor list
     *  so free roots are immediately joinable after a server restart — without this the
     *  scanner would only see roots placed during the current session. */
    private fun restoreSavedJoints(level: ServerLevel) {
        val data = BindingRootsSavedData.forLevel(level)
        // Restore the anchor watch-set unconditionally — even if there are no saved joints,
        // there may be free roots that need scanning.
        if (data.anchorPositions.isNotEmpty()) {
            val watch = positions.getOrPut(level) { mutableSetOf() }
            watch.addAll(data.anchorPositions)
            LOG.info(
                "BindingRoots: restored {} anchor position(s) in {}.",
                data.anchorPositions.size, level.dimension().location(),
            )
        }
        if (data.records.isEmpty()) return
        // Capture records up-front so we can mutate `data.records` (the prune below) without
        // a ConcurrentModificationException.
        val snapshot = data.records.values.toList()
        var revived = 0
        var pruned = 0
        for (record in snapshot) {
            // Schedule the load-check + revive on the level's tick so chunk loading has time
            // to expose the bound_root states.
            level.server.execute {
                val aState = level.getBlockState(record.blockAPos)
                val bState = level.getBlockState(record.blockBPos)
                if (!aState.`is`(EKBlocks.BINDING_ROOTS.get()) || !bState.`is`(EKBlocks.BINDING_ROOTS.get())) {
                    data.records.remove(record.blockAPos.asLong())
                    data.setDirty()
                    pruned++
                    return@execute
                }
                val joint: VSJoint = VSFixedJoint(
                    shipId0 = record.shipAId,
                    pose0 = VSJointPose(record.pose0Pos, record.pose0Rot),
                    shipId1 = record.shipBId,
                    pose1 = VSJointPose(record.pose1Pos, record.pose1Rot),
                    maxForceTorque = null,
                    compliance = WELD_COMPLIANCE,
                )
                val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
                val aLong = record.blockAPos.asLong()
                val bLong = record.blockBPos.asLong()
                gtpa.addJoint(joint, 0) { id ->
                    level.executeOrSchedule {
                        val map = activeJoints.getOrPut(level) { mutableMapOf() }
                        map[aLong] = JointBinding(id, bLong)
                        map[bLong] = JointBinding(id, aLong)
                    }
                }
                revived++
            }
        }
        LOG.info(
            "BindingRoots: restoring {} saved joint(s) in {} (pending block-existence checks).",
            snapshot.size, level.dimension().location(),
        )
    }

    private class ForceAccum {
        val force: Vector3d = Vector3d()
        val point: Vector3d = Vector3d()
        val torque: Vector3d = Vector3d()
        var active: Boolean = false
    }

    private val forceAccumulator: MutableMap<LoadedServerShip, ForceAccum> = mutableMapOf()

    /** Per-pair outcome from [checkPair]: invalid (no engagement), engaged (forces applied
     *  this tick), or merged (the pair was consumed by an actual merge — caller must bail
     *  out of the pair walk because the position snapshot is now stale). */
    private enum class PairOutcome { INVALID, ENGAGED, MERGED }

    private fun tickLevel(level: ServerLevel) {
        val watched = positions[level] ?: return

        // Clear last tick's accumulators before walking pairs.
        for (accum in forceAccumulator.values) {
            accum.force.set(0.0); accum.point.set(0.0); accum.torque.set(0.0); accum.active = false
        }

        if (watched.size >= 2) {
            val snapshot = watched.toLongArray()
            // Each root participates in AT MOST ONE pair per tick. Without this guard, a
            // ship with multiple binding-roots stacks force from every valid pair it's in
            // and gets violently dragged around. Greedy assignment: first valid pair wins.
            val engaged = HashSet<Long>(snapshot.size)
            outer@ for (i in snapshot.indices) {
                val a = snapshot[i]
                if (a in engaged) continue
                for (j in i + 1 until snapshot.size) {
                    val b = snapshot[j]
                    if (b in engaged) continue
                    when (checkPair(level, BlockPos.of(a), BlockPos.of(b))) {
                        PairOutcome.MERGED -> {
                            flushForcesAndClear()
                            return
                        }
                        PairOutcome.ENGAGED -> {
                            engaged.add(a)
                            engaged.add(b)
                            continue@outer
                        }
                        PairOutcome.INVALID -> {}
                    }
                }
            }
        }
        flushForcesAndClear()
    }

    private fun flushForcesAndClear() {
        for ((ship, accum) in forceAccumulator) {
            val attachment = ship.getOrPutAttachment { BindingRootsPullAttachment() }
            if (!accum.active) {
                attachment.clear()
            } else {
                attachment.update(accum.force, accum.point, accum.torque)
            }
        }
    }

    private fun accumPull(ship: LoadedServerShip, force: Vector3d, worldPoint: Vector3d) {
        val accum = forceAccumulator.getOrPut(ship) { ForceAccum() }
        accum.force.add(force)
        accum.point.add(worldPoint)
        accum.active = true
    }

    private fun accumTorque(ship: LoadedServerShip, torque: Vector3d) {
        val accum = forceAccumulator.getOrPut(ship) { ForceAccum() }
        accum.torque.add(torque)
        accum.active = true
    }

    /** Tri-state outcome — see [PairOutcome]. */
    private fun checkPair(level: ServerLevel, posA: BlockPos, posB: BlockPos): PairOutcome {
        val stateA = level.getBlockState(posA)
        val stateB = level.getBlockState(posB)
        val block = EKBlocks.BINDING_ROOTS.get()
        if (!stateA.`is`(block) || !stateB.`is`(block)) return PairOutcome.INVALID

        // Skip binding_roots already anchoring a joint weld — they're physically locked
        // into place; applying more spring force would just fight the joint and produce
        // shaky physics.
        val jointMap = activeJoints[level]
        if (jointMap != null && (posA.asLong() in jointMap || posB.asLong() in jointMap)) {
            return PairOutcome.INVALID
        }

        val shipA: LoadedServerShip? = level.getLoadedShipManagingPos(posA) as? LoadedServerShip
        val shipB: LoadedServerShip? = level.getLoadedShipManagingPos(posB) as? LoadedServerShip
        if (shipA != null && shipA === shipB) return PairOutcome.INVALID
        if (shipA == null && shipB == null) return PairOutcome.INVALID

        val centreA = worldCenter(level, posA, shipA)
        val centreB = worldCenter(level, posB, shipB)
        val delta = Vector3d(centreB).sub(centreA)
        val distance = delta.length()
        // Engagement requires the two 1×1×1 root cubes to actually overlap in world space —
        // i.e. per-axis centre delta < 1 (block-volume intersection, not Euclidean radius).
        // The player has to physically push the roots into intersection before forces engage.
        if (Math.abs(delta.x) >= 1.0 || Math.abs(delta.y) >= 1.0 || Math.abs(delta.z) >= 1.0) {
            return PairOutcome.INVALID
        }

        // Coarse engagement gate: facings must point toward each other (dot < 0). Tight
        // anti-parallel is enforced at the snap firing — the alignment torque pulls the
        // ships to anti-parallel from this looser engagement.
        val facingA = worldFacing(stateA, shipA)
        val facingB = worldFacing(stateB, shipB)
        if (facingA.dot(facingB) >= FACING_ENGAGEMENT_DOT_THRESHOLD) return PairOutcome.INVALID

        // PD attraction at each ship's anchor. P = Kp × delta (pulls toward the other anchor);
        // D = -Kd × shipVelocity (damps the ship's absolute motion). Critical-damped tuning
        // means the ship converges in ~1 s without overshoot. Off-axis application at the
        // anchor (not COM) also induces alignment torque from the force itself.
        val pForceMag = distance * ATTRACTION_KP
        val forceDirAtoB = if (distance > 0.0) Vector3d(delta).normalize() else Vector3d(0.0, 0.0, 0.0)
        if (shipA != null) {
            val pull = Vector3d(forceDirAtoB).mul(pForceMag)
            val damp = Vector3d(shipA.velocity).mul(-ATTRACTION_KD)
            accumPull(shipA, pull.add(damp), centreA)
            accumTorque(shipA, alignmentPDTorque(shipA))
        }
        if (shipB != null) {
            val pull = Vector3d(forceDirAtoB).mul(-pForceMag)
            val damp = Vector3d(shipB.velocity).mul(-ATTRACTION_KD)
            accumPull(shipB, pull.add(damp), centreB)
            accumTorque(shipB, alignmentPDTorque(shipB))
        }

        // Snap firing requires the tight gates: cardinal alignment on both ships, exact
        // anti-parallel facings, perpendicular offset, and snap distance.
        if (distance > SNAP_DISTANCE_BLOCKS) return PairOutcome.ENGAGED
        if (!isCardinallyAligned(shipA) || !isCardinallyAligned(shipB)) return PairOutcome.ENGAGED
        val antiAngle = Math.acos((-facingA.dot(facingB)).coerceIn(-1.0, 1.0))
        if (antiAngle > FACING_ANTIPARALLEL_TOLERANCE_RAD) return PairOutcome.ENGAGED
        val along = facingA.dot(delta)
        val perpendicular = Vector3d(delta).sub(Vector3d(facingA).mul(along))
        if (perpendicular.length() > PERPENDICULAR_TOLERANCE_BLOCKS) return PairOutcome.ENGAGED

        // Branch based on whether the two ships' world-up axes are aligned (within ~5°). If
        // both ships have the same world-up direction, the relative rotation is a pure yaw
        // around world Y, which means the candidate's blocks can be cleanly transformed into
        // the host's frame via vanilla `state.rotate(Rotation)` — full block merge into one
        // body. If the ups are mis-aligned (different ship is tilted / upside-down), we
        // can't represent the rotation with vanilla Rotation, so we fall back to a fixed
        // joint via bound_root blocks instead.
        // EXCEPTION to the block-merge / joint-weld choice: a shulker strut's lid ship MUST
        // survive any binding_roots merge. The block-merge path reaps the candidate ship; if
        // the lid is the candidate (e.g. a lid-side binding_roots pairs with a world-side
        // one), reaping the lid leaves the strut without a top half. Force the joint-weld
        // path instead — both bodies stay alive and the binding becomes a fixed joint at
        // the engaged anchors.
        val involvesStrutLid = (shipA != null && containsStrutLidBlock(level, shipA)) ||
            (shipB != null && containsStrutLidBlock(level, shipB))
        // Block-merge transforms candidate blocks into the host's frame via
        // vanilla `state.rotate(Rotation)` — that only works when both ships
        // share the same uniform scale. Mismatched scales (e.g. one ship was
        // resized by Staff of Scales) can't be expressed by vanilla Rotation,
        // so we fall through to a joint-weld instead — the joint accepts any
        // anchor frames and preserves each ship's scale independently.
        val fired = if (!involvesStrutLid && areUpAligned(shipA, shipB) && scalesMatch(shipA, shipB)) {
            performBlockMerge(level, posA, posB, shipA, shipB)
        } else {
            performJointWeld(level, posA, posB, shipA, shipB, centreA, centreB)
        }
        return if (fired) PairOutcome.MERGED else PairOutcome.ENGAGED
    }

    /** Two ships have matching uniform scale when their X-axis shipToWorld
     *  scaling differs by less than [SCALE_MATCH_EPSILON]. A null ship
     *  (world side) is treated as scale 1.0. */
    private fun scalesMatch(shipA: LoadedServerShip?, shipB: LoadedServerShip?): Boolean {
        val sA = shipA?.transform?.shipToWorldScaling?.x() ?: 1.0
        val sB = shipB?.transform?.shipToWorldScaling?.x() ?: 1.0
        return kotlin.math.abs(sA - sB) < SCALE_MATCH_EPSILON
    }

    /** True if [ship] contains at least one [EKBlocks.SHULKER_STRUT_TOP] block — i.e., the
     *  ship is (or contains) a shulker strut's lid. Called only on the snap-fire path
     *  (once per binding pair when the merge would fire), so the per-tick cost is zero in
     *  the steady state. */
    private fun containsStrutLidBlock(level: ServerLevel, ship: LoadedServerShip): Boolean {
        val ab = ship.shipAABB ?: return false
        val target = EKBlocks.SHULKER_STRUT_TOP.get()
        val cursor = BlockPos.MutableBlockPos()
        for (x in ab.minX()..ab.maxX()) {
            for (y in ab.minY()..ab.maxY()) {
                for (z in ab.minZ()..ab.maxZ()) {
                    cursor.set(x, y, z)
                    if (level.getBlockState(cursor).`is`(target)) return true
                }
            }
        }
        return false
    }

    /** True when both ships' world-up axes are within ~5° of each other (i.e. their relative
     *  rotation has no roll/pitch — only yaw around world Y). World is trivially at +Y. */
    private fun areUpAligned(shipA: LoadedServerShip?, shipB: LoadedServerShip?): Boolean {
        val upA = if (shipA == null) Vector3d(0.0, 1.0, 0.0)
        else shipA.transform.shipToWorldRotation.transform(Vector3d(0.0, 1.0, 0.0), Vector3d())
        val upB = if (shipB == null) Vector3d(0.0, 1.0, 0.0)
        else shipB.transform.shipToWorldRotation.transform(Vector3d(0.0, 1.0, 0.0), Vector3d())
        return upA.dot(upB) >= CARDINAL_TOLERANCE_COS
    }

    /** Block-level merge — used when the two ships' world-ups are aligned. The candidate
     *  ship's blocks are moved into the host's frame via vanilla state.rotate(Rotation), and
     *  the welded position becomes a single `wogor_wood` block. Candidate ship is reaped. */
    private fun performBlockMerge(
        level: ServerLevel,
        posA: BlockPos,
        posB: BlockPos,
        shipA: LoadedServerShip?,
        shipB: LoadedServerShip?,
    ): Boolean {
        val roles = when {
            shipA == null -> MergeRoles(shipB!!, posB, null, posA)
            shipB == null -> MergeRoles(shipA, posA, null, posB)
            shipA.id < shipB.id -> MergeRoles(shipB, posB, shipA, posA)
            else -> MergeRoles(shipA, posA, shipB, posB)
        }
        val candidate = roles.candidate
        val candidatePos = roles.candidatePos
        val hostShip = roles.hostShip
        val hostPos = roles.hostPos

        val aabb = candidate.shipAABB ?: return false

        val blocks = mutableSetOf<BlockPos>()
        val cursor = BlockPos.MutableBlockPos()
        for (x in aabb.minX()..aabb.maxX()) {
            for (y in aabb.minY()..aabb.maxY()) {
                for (z in aabb.minZ()..aabb.maxZ()) {
                    cursor.set(x, y, z)
                    if (!level.getBlockState(cursor).isAir) blocks.add(cursor.immutable())
                }
            }
        }
        if (blocks.isEmpty()) return false

        val rotation = computeYawRotation(candidate, hostShip)

        if (rotation == Rotation.NONE) {
            // Pure translation — VS2's `moveBlocksFromTo` handles BE / lighting / chunk
            // update packets. `floorDiv` (not `/`) because the centroid formula 2N+1 needs
            // floor rounding for negative-shipyard-coord ships, not truncate-toward-zero.
            val minStructurePos = BlockPos(aabb.minX(), aabb.minY(), aabb.minZ())
            val maxStructurePos = BlockPos(aabb.maxX(), aabb.maxY(), aabb.maxZ())
            val toCenter = Vector3i(
                Math.floorDiv(aabb.minX() + aabb.maxX() + 1, 2) + (hostPos.x - candidatePos.x),
                Math.floorDiv(aabb.minY() + aabb.maxY() + 1, 2) + (hostPos.y - candidatePos.y),
                Math.floorDiv(aabb.minZ() + aabb.maxZ() + 1, 2) + (hostPos.z - candidatePos.z),
            )
            val result = ShipAssembler.moveBlocksFromTo(
                level, blocks, candidate, hostShip,
                minStructurePos, maxStructurePos, toCenter, true,
            )
            if (!result.wasSuccessful) {
                LOG.warn(
                    "BindingRoots: moveBlocksFromTo failed for candidate {} into host {}.",
                    candidate.id, hostShip?.id ?: "world",
                )
                return false
            }
        } else {
            performYawMove(level, blocks, candidatePos, hostPos, rotation)
        }

        level.setBlock(hostPos, EKBlocks.WOGOR_WOOD.get().defaultBlockState(), 3)
        positions[level]?.remove(candidatePos.asLong())
        positions[level]?.remove(hostPos.asLong())

        // Re-target any existing joints the candidate had with OTHER ships into the host's
        // frame BEFORE we reap the candidate. Without this, deleteShip orphans them.
        migrateJointsToHost(level, candidate.id, hostShip, candidatePos, hostPos, rotation)

        ShipAssembler.deleteShip(level, candidate, false, false)

        LOG.info(
            "BindingRoots: block-merged candidate ship {} into host {} (rotation={}, {} blocks).",
            candidate.id, hostShip?.id ?: "world", rotation, blocks.size,
        )
        return true
    }

    /** Yaw rotation candidate→host as a vanilla [Rotation] enum. Used only on the up-aligned
     *  merge path, where the relative rotation is guaranteed to be a Y-axis quarter-turn. */
    private fun computeYawRotation(candidate: LoadedServerShip, host: LoadedServerShip?): Rotation {
        val cForward = candidate.transform.shipToWorldRotation
            .transform(Vector3d(0.0, 0.0, 1.0), Vector3d())
        val hForward = if (host == null) Vector3d(0.0, 0.0, 1.0)
        else host.transform.shipToWorldRotation.transform(Vector3d(0.0, 0.0, 1.0), Vector3d())
        var delta = Math.atan2(hForward.x, hForward.z) - Math.atan2(cForward.x, cForward.z)
        while (delta > Math.PI) delta -= 2 * Math.PI
        while (delta < -Math.PI) delta += 2 * Math.PI
        val quarter = Math.round(delta / (Math.PI / 2.0)).toInt()
        return when (((quarter % 4) + 4) % 4) {
            0 -> Rotation.NONE
            1 -> Rotation.CLOCKWISE_90
            2 -> Rotation.CLOCKWISE_180
            else -> Rotation.COUNTERCLOCKWISE_90
        }
    }

    /** Migrate every saved joint that references the about-to-be-deleted candidate ship
     *  into the host's frame: re-target the ship id, translate (and yaw-rotate) the
     *  candidate-side pose, re-issue the joint with the new recipe. Called from
     *  [performBlockMerge] right before `ShipAssembler.deleteShip(candidate)` — without
     *  this step, joints anchored at the candidate's binding_roots blocks would point at a
     *  dead ship id and dangle. */
    private fun migrateJointsToHost(
        level: ServerLevel,
        candidateShipId: Long,
        hostShip: LoadedServerShip?,
        candidatePos: BlockPos,
        hostPos: BlockPos,
        rotation: Rotation,
    ) {
        val savedData = BindingRootsSavedData.forLevel(level)
        val affected = savedData.records.values.filter {
            it.shipAId == candidateShipId || it.shipBId == candidateShipId
        }.toList()
        if (affected.isEmpty()) return

        val rotQ = rotationToQuaternion(rotation)
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        val map = activeJoints[level]

        for (old in affected) {
            // Skip joints between the candidate and the host itself — those become same-ship
            // self-loops after the merge, which the solver doesn't model. Their bound blocks
            // are already in the host post-merge so the geometric relationship is preserved
            // by the block move; the joint is just redundant.
            val aSide = old.shipAId == candidateShipId
            val bSide = old.shipBId == candidateShipId
            val newShipA = if (aSide) hostShip?.id else old.shipAId
            val newShipB = if (bSide) hostShip?.id else old.shipBId
            val redundantSelfLoop = newShipA != null && newShipA == newShipB

            val oldKey = old.blockAPos.asLong()
            val oldEntry = map?.get(oldKey)
            if (oldEntry != null) {
                map.remove(oldKey)
                map.remove(old.blockBPos.asLong())
                gtpa.removeJoint(oldEntry.jointId)
            }
            savedData.records.remove(oldKey)
            savedData.setDirty()

            if (redundantSelfLoop) {
                LOG.info(
                    "BindingRoots: dropping joint {} → {} after merge (became same-ship self-loop).",
                    old.blockAPos, old.blockBPos,
                )
                continue
            }

            val newBlockA = if (aSide) transformBlockPos(old.blockAPos, candidatePos, hostPos, rotation) else old.blockAPos
            val newBlockB = if (bSide) transformBlockPos(old.blockBPos, candidatePos, hostPos, rotation) else old.blockBPos
            val newPose0Pos = if (aSide) transformPoseVec(old.pose0Pos, candidatePos, hostPos, rotation) else Vector3d(old.pose0Pos)
            val newPose0Rot = if (aSide) Quaterniond(rotQ).mul(old.pose0Rot) else Quaterniond(old.pose0Rot)
            val newPose1Pos = if (bSide) transformPoseVec(old.pose1Pos, candidatePos, hostPos, rotation) else Vector3d(old.pose1Pos)
            val newPose1Rot = if (bSide) Quaterniond(rotQ).mul(old.pose1Rot) else Quaterniond(old.pose1Rot)

            val migrated = BindingRootsSavedData.JointRecord(
                blockAPos = newBlockA,
                blockBPos = newBlockB,
                shipAId = newShipA,
                shipBId = newShipB,
                pose0Pos = newPose0Pos,
                pose0Rot = newPose0Rot,
                pose1Pos = newPose1Pos,
                pose1Rot = newPose1Rot,
            )

            val joint: VSJoint = VSFixedJoint(
                shipId0 = migrated.shipAId,
                pose0 = VSJointPose(migrated.pose0Pos, migrated.pose0Rot),
                shipId1 = migrated.shipBId,
                pose1 = VSJointPose(migrated.pose1Pos, migrated.pose1Rot),
                maxForceTorque = null,
                compliance = WELD_COMPLIANCE,
            )
            val newALong = migrated.blockAPos.asLong()
            val newBLong = migrated.blockBPos.asLong()
            gtpa.addJoint(joint, 0) { id ->
                level.executeOrSchedule {
                    val activeMap = activeJoints.getOrPut(level) { mutableMapOf() }
                    activeMap[newALong] = JointBinding(id, newBLong)
                    activeMap[newBLong] = JointBinding(id, newALong)
                    BindingRootsSavedData.forLevel(level).add(migrated)
                }
            }
            LOG.info(
                "BindingRoots: migrated joint {}↔{} → {}↔{} (candidate ship {} absorbed by {}).",
                old.blockAPos, old.blockBPos, newBlockA, newBlockB,
                candidateShipId, hostShip?.id ?: "world",
            )
        }
    }

    /** Y-axis rotation around `candidatePos` (block centre), then translation to `hostPos`. */
    private fun transformBlockPos(
        pos: BlockPos,
        candidatePos: BlockPos,
        hostPos: BlockPos,
        rotation: Rotation,
    ): BlockPos {
        val rel = BlockPos(pos.x - candidatePos.x, pos.y - candidatePos.y, pos.z - candidatePos.z)
        val rotated = rel.rotate(rotation)
        return BlockPos(hostPos.x + rotated.x, hostPos.y + rotated.y, hostPos.z + rotated.z)
    }

    /** Continuous-pose-vector counterpart of [transformBlockPos]: rotate around the
     *  candidate's anchor block centre and translate to the host's anchor block centre. */
    private fun transformPoseVec(
        pos: Vector3d,
        candidatePos: BlockPos,
        hostPos: BlockPos,
        rotation: Rotation,
    ): Vector3d {
        val rx = pos.x - (candidatePos.x + 0.5)
        val ry = pos.y - (candidatePos.y + 0.5)
        val rz = pos.z - (candidatePos.z + 0.5)
        val nx: Double; val ny: Double; val nz: Double
        when (rotation) {
            Rotation.NONE -> { nx = rx; ny = ry; nz = rz }
            Rotation.CLOCKWISE_90 -> { nx = -rz; ny = ry; nz = rx }
            Rotation.CLOCKWISE_180 -> { nx = -rx; ny = ry; nz = -rz }
            Rotation.COUNTERCLOCKWISE_90 -> { nx = rz; ny = ry; nz = -rx }
        }
        return Vector3d(nx + hostPos.x + 0.5, ny + hostPos.y + 0.5, nz + hostPos.z + 0.5)
    }

    /** Convert vanilla [Rotation] to a JOML [Quaterniond] (Y-axis rotation). Used to compose
     *  the merge yaw with each migrated pose's rotation. */
    private fun rotationToQuaternion(rotation: Rotation): Quaterniond = when (rotation) {
        Rotation.NONE -> Quaterniond()
        Rotation.CLOCKWISE_90 -> Quaterniond().rotateY(-Math.PI / 2.0)
        Rotation.CLOCKWISE_180 -> Quaterniond().rotateY(Math.PI)
        Rotation.COUNTERCLOCKWISE_90 -> Quaterniond().rotateY(Math.PI / 2.0)
    }

    /** Manual Y-axis-rotated move. Uses vanilla `BlockPos.rotate(Rotation)` (Y-axis rotation
     *  around origin) for positions and `state.rotate(Rotation)` for block states — both
     *  built-in for the Y-axis case. BE NBT is captured before clearing, restored after
     *  placement. */
    private fun performYawMove(
        level: ServerLevel,
        candidateBlocks: Set<BlockPos>,
        candidatePos: BlockPos,
        hostPos: BlockPos,
        rotation: Rotation,
    ) {
        data class Move(val src: BlockPos, val target: BlockPos, val newState: BlockState, val beTag: CompoundTag?)
        val moves = ArrayList<Move>(candidateBlocks.size)
        for (srcPos in candidateBlocks) {
            val state = level.getBlockState(srcPos)
            val rel = BlockPos(srcPos.x - candidatePos.x, srcPos.y - candidatePos.y, srcPos.z - candidatePos.z)
            val rotatedRel = rel.rotate(rotation)
            val target = BlockPos(hostPos.x + rotatedRel.x, hostPos.y + rotatedRel.y, hostPos.z + rotatedRel.z)
            val be = level.getBlockEntity(srcPos)
            val beTag = be?.saveWithFullMetadata()
            moves.add(Move(srcPos, target, state.rotate(rotation), beTag))
        }
        val air = Blocks.AIR.defaultBlockState()
        for (mv in moves) {
            level.getBlockEntity(mv.src)?.let { be ->
                if (be is Clearable) Clearable.tryClear(be) else be.load(CompoundTag())
                level.removeBlockEntity(mv.src)
            }
            level.setBlock(mv.src, air, 2)
        }
        for (mv in moves) {
            level.setBlock(mv.target, mv.newState, 2)
            val tag = mv.beTag ?: continue
            level.getBlockEntity(mv.target)?.load(tag)
        }
    }

    /** Joint-based weld — used when the two ships' world-ups are mis-aligned and a clean
     *  block merge isn't possible. Snap-aligns the candidate to its nearest octahedral
     *  rotation and exact target position, then setBlocks both binding_roots → bound_root
     *  and creates a [VSFixedJoint] welding the two bodies at the anchor frames.
     *
     *  Returns true once the joint-creation request is in flight; the actual joint id is
     *  written back to [activeJoints] from the async callback. */
    private fun performJointWeld(
        level: ServerLevel,
        posA: BlockPos,
        posB: BlockPos,
        shipA: LoadedServerShip?,
        shipB: LoadedServerShip?,
        centreA: Vector3d,
        centreB: Vector3d,
    ): Boolean {
        val roles = when {
            shipA == null -> MergeRoles(shipB!!, posB, null, posA)
            shipB == null -> MergeRoles(shipA, posA, null, posB)
            shipA.id < shipB.id -> MergeRoles(shipB, posB, shipA, posA)
            else -> MergeRoles(shipA, posA, shipB, posB)
        }
        val candidate = roles.candidate
        val candidatePos = roles.candidatePos
        val hostShip = roles.hostShip
        val hostPos = roles.hostPos

        // Target world geometry: host's anchor stays put. Candidate's anchor centre lands
        // at the EXACT SAME world position as the host's anchor centre — the two
        // binding_roots blocks occupy the same blockspace, one in each ship's frame. The
        // joint then locks the bodies at this overlapping configuration. Visually the
        // player sees one block; in fact there are two, on different ships.
        val hostCentreWorld = worldCenter(level, hostPos, hostShip)
        val candTargetCentreWorld = Vector3d(hostCentreWorld)

        // Snap candidate's rotation to nearest octahedral, then derive the transform position
        // that places the candidate's anchor centre at the target. Both gates upstream
        // guarantee the snap is within ~5° so this is effectively a noise-killer.
        val snappedRotation = snapRotationToOctahedral(candidate.transform.shipToWorldRotation)
        val candAnchorInShipFrame = Vector3d(
            candidatePos.x + 0.5 - candidate.transform.positionInModel.x(),
            candidatePos.y + 0.5 - candidate.transform.positionInModel.y(),
            candidatePos.z + 0.5 - candidate.transform.positionInModel.z(),
        )
        val rotatedAnchorOffset = snappedRotation.transform(Vector3d(candAnchorInShipFrame))
        val candTargetPosition = Vector3d(candTargetCentreWorld).sub(rotatedAnchorOffset)

        // Build and apply the snapped transform. `unsafeSetTransform` skips physics
        // interpolation, which is what we want — the alignment torque already converged the
        // candidate to within tolerance; this exact-snap kills the residual numerical drift.
        val newTransform = vsCore.transformFactory.build {
            position.set(candTargetPosition)
            rotation.set(snappedRotation)
            positionInModel.set(candidate.transform.positionInModel)
            scaling.set(candidate.transform.shipToWorldScaling)
        }
        candidate.unsafeSetTransform(newTransform)

        // Don't replace the binding_roots — they ARE the joint indicator. The snap-align
        // above has already placed them at the touching positions; the joint just locks
        // them there. Breaking either binding_roots releases the joint (see
        // BindingRootsBlock.onRemove → onJointAnchorBroken). The pair scanner keeps the
        // positions in [positions] so re-engagement is checked, but [checkPair] short-
        // circuits when either pos is already in [activeJoints] so the spring force
        // doesn't fight the joint.

        // Build the joint. Anchor pose0 at the candidate's binding_roots block centre in
        // candidate's local frame, pose1 at the same world point transformed into the host's
        // local frame. CRITICAL: we compute pose1 from the snap-align TARGETS
        // (`candTargetCentreWorld`, `snappedRotation`) rather than reading
        // `candidate.transform.shipToWorld` back — `unsafeSetTransform` doesn't always
        // synchronise into the kinematics' transform within the same game tick, so a
        // round-trip read gives stale numbers and the joint lands 1 physics tick of
        // residual drift off-centre. Using the targets directly nets a frame-perfect weld.
        val pose0Pos = Vector3d(candidatePos.x + 0.5, candidatePos.y + 0.5, candidatePos.z + 0.5)
        val pose0Rot = Quaterniond()
        val pose1Pos = Vector3d(candTargetCentreWorld)
        hostShip?.transform?.worldToShip?.transformPosition(pose1Pos)
        val pose1Rot = Quaterniond(snappedRotation)
        if (hostShip != null) {
            val w2s = Quaterniond()
            hostShip.transform.shipToWorldRotation.invert(w2s)
            val combined = Quaterniond(w2s).mul(pose1Rot)
            pose1Rot.set(combined)
        }

        val joint: VSJoint = VSFixedJoint(
            shipId0 = candidate.id,
            pose0 = VSJointPose(pose0Pos, pose0Rot),
            shipId1 = hostShip?.id,
            pose1 = VSJointPose(pose1Pos, pose1Rot),
            maxForceTorque = null,
            compliance = WELD_COMPLIANCE,
        )

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        val candidatePosLong = candidatePos.asLong()
        val hostPosLong = hostPos.asLong()
        // Capture the joint's recipe ahead of the async callback so we can persist it via
        // BindingRootsSavedData; the joint id itself is transient, the recipe is what
        // survives a world reload.
        val record = BindingRootsSavedData.JointRecord(
            blockAPos = candidatePos,
            blockBPos = hostPos,
            shipAId = candidate.id,
            shipBId = hostShip?.id,
            pose0Pos = Vector3d(pose0Pos),
            pose0Rot = Quaterniond(pose0Rot),
            pose1Pos = Vector3d(pose1Pos),
            pose1Rot = Quaterniond(pose1Rot),
        )
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                // If the player broke either binding_roots between the request and the
                // callback, abandon the joint.
                val candStill = level.getBlockState(candidatePos).`is`(EKBlocks.BINDING_ROOTS.get())
                val hostStill = level.getBlockState(hostPos).`is`(EKBlocks.BINDING_ROOTS.get())
                if (!candStill || !hostStill) {
                    gtpa.removeJoint(id)
                    return@executeOrSchedule
                }
                val map = activeJoints.getOrPut(level) { mutableMapOf() }
                map[candidatePosLong] = JointBinding(id, hostPosLong)
                map[hostPosLong] = JointBinding(id, candidatePosLong)
                BindingRootsSavedData.forLevel(level).add(record)
            }
        }

        LOG.info(
            "BindingRoots: welded candidate ship {} to host {} at anchor {} (joint pending).",
            candidate.id, hostShip?.id ?: "world", hostPos,
        )
        return true
    }

    /** Snap an arbitrary rotation to the nearest of the 24 octahedral rotations. Builds the
     *  rotation matrix's columns by snapping each rotated shipyard axis to the closest world
     *  cardinal, then back-fills the matrix into a Quaternion via JOML's setFromUnnormalized. */
    private fun snapRotationToOctahedral(rot: org.joml.Quaterniondc): Quaterniond {
        val matrixCols = Array(3) { Vector3d() }
        val sources = arrayOf(
            Vector3d(1.0, 0.0, 0.0),
            Vector3d(0.0, 1.0, 0.0),
            Vector3d(0.0, 0.0, 1.0),
        )
        for (i in 0..2) {
            rot.transform(sources[i], matrixCols[i])
            val absX = Math.abs(matrixCols[i].x)
            val absY = Math.abs(matrixCols[i].y)
            val absZ = Math.abs(matrixCols[i].z)
            when {
                absX >= absY && absX >= absZ ->
                    matrixCols[i].set(if (matrixCols[i].x > 0) 1.0 else -1.0, 0.0, 0.0)
                absY >= absZ ->
                    matrixCols[i].set(0.0, if (matrixCols[i].y > 0) 1.0 else -1.0, 0.0)
                else ->
                    matrixCols[i].set(0.0, 0.0, if (matrixCols[i].z > 0) 1.0 else -1.0)
            }
        }
        val matrix = org.joml.Matrix3d(
            matrixCols[0].x, matrixCols[0].y, matrixCols[0].z,
            matrixCols[1].x, matrixCols[1].y, matrixCols[1].z,
            matrixCols[2].x, matrixCols[2].y, matrixCols[2].z,
        )
        return Quaterniond().setFromUnnormalized(matrix)
    }

    // Joint lifecycle: track joint ids per bound_root block; release on break.

    private data class JointBinding(val jointId: Int, val partnerPos: Long)
    private val activeJoints: MutableMap<ServerLevel, MutableMap<Long, JointBinding>> = mutableMapOf()

    /** Called from [BindingRootsBlock.onRemove] when a binding_roots block is broken. If the
     *  block is currently anchoring a joint weld, removes both endpoints from [activeJoints]
     *  AND the persistent [BindingRootsSavedData] record so the joint doesn't come back
     *  after a world reload. No-op for binding_roots that aren't part of a joint. */
    @JvmStatic
    fun onJointAnchorBroken(level: ServerLevel, pos: BlockPos) {
        BindingRootsSavedData.forLevel(level).removeInvolving(pos)
        val map = activeJoints[level] ?: return
        val binding = map.remove(pos.asLong()) ?: return
        map.remove(binding.partnerPos)
        ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).removeJoint(binding.jointId)
        LOG.info("BindingRoots: released joint {} (binding_roots broken at {}).", binding.jointId, pos)
    }

    /** Inverse stiffness for the weld — 0.0 = perfectly rigid. Krunch_Konstant (PhysX)
     *  ignores compliance anyway and treats the joint as a true rigid constraint; we don't
     *  support Krunch_Classic. */
    private const val WELD_COMPLIANCE: Double = 0.0

    /** Per-ship PD torque toward nearest octahedral. P = Kp × Σ (currentAxis × cardinal) —
     *  cross-product magnitude is sin(angle), direction is the rotation axis. D = -Kd × ω
     *  damps the ship's angular velocity. Net is a critically-damped rotational controller
     *  that settles the ship into cardinal alignment in ~1 s without oscillation. */
    private fun alignmentPDTorque(ship: LoadedServerShip): Vector3d {
        val rot = ship.transform.shipToWorldRotation
        val pTorque = Vector3d()
        val workSrc = Vector3d()
        for (i in 0..2) {
            when (i) {
                0 -> workSrc.set(1.0, 0.0, 0.0)
                1 -> workSrc.set(0.0, 1.0, 0.0)
                else -> workSrc.set(0.0, 0.0, 1.0)
            }
            val world = rot.transform(workSrc, Vector3d())
            val cardinal = nearestCardinal(world)
            pTorque.add(Vector3d(world).cross(cardinal))
        }
        pTorque.mul(ALIGNMENT_KP)
        val omega = ship.kinematics.angularVelocity
        val dTorque = Vector3d(omega).mul(-ALIGNMENT_KD)
        return pTorque.add(dTorque)
    }

    private fun nearestCardinal(v: Vector3d): Vector3d {
        val absX = Math.abs(v.x)
        val absY = Math.abs(v.y)
        val absZ = Math.abs(v.z)
        return when {
            absX >= absY && absX >= absZ -> Vector3d(if (v.x > 0) 1.0 else -1.0, 0.0, 0.0)
            absY >= absZ -> Vector3d(0.0, if (v.y > 0) 1.0 else -1.0, 0.0)
            else -> Vector3d(0.0, 0.0, if (v.z > 0) 1.0 else -1.0)
        }
    }

    private fun isCardinallyAligned(ship: LoadedServerShip?): Boolean {
        if (ship == null) return true
        val rot = ship.transform.shipToWorldRotation
        val workSrc = Vector3d()
        val workDst = Vector3d()
        for (i in 0..2) {
            when (i) {
                0 -> workSrc.set(1.0, 0.0, 0.0)
                1 -> workSrc.set(0.0, 1.0, 0.0)
                else -> workSrc.set(0.0, 0.0, 1.0)
            }
            rot.transform(workSrc, workDst)
            val maxAbs = maxOf(Math.abs(workDst.x), Math.abs(workDst.y), Math.abs(workDst.z))
            if (maxAbs < CARDINAL_TOLERANCE_COS) return false
        }
        return true
    }

    private data class MergeRoles(
        val candidate: LoadedServerShip,
        val candidatePos: BlockPos,
        val hostShip: LoadedServerShip?,
        val hostPos: BlockPos,
    )

    private fun worldCenter(level: ServerLevel, pos: BlockPos, host: ServerShip?): Vector3d {
        val shipyardCenter = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        return if (host == null) shipyardCenter
        else host.transform.shipToWorld.transformPosition(shipyardCenter, Vector3d())
    }

    private fun worldFacing(state: BlockState, host: ServerShip?): Vector3d {
        val facing = state.getValue(BindingRootsBlock.FACING)
        val local = Vector3d(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())
        return if (host == null) local
        else host.transform.shipToWorldRotation.transform(local, Vector3d()).normalize()
    }

}
