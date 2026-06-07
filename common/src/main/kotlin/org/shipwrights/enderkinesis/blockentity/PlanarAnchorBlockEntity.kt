package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.PlanarAnchorBlock
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.core.internal.joints.VSDistanceJoint
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.world.clipIncludeShips

/**
 * The Planar Anchor's block entity.
 *
 * When the block is redstone-powered *and* sits on a ship, this BE registers a
 * [VSSphericalJoint] between the world body (`shipId0 = null`) and the ship, pinning the block's
 * world position while leaving the ship free to rotate around it. The block on the ship can rock,
 * spin and yaw around the anchor point but its world centre is pulled back to where it was the
 * instant the anchor was activated.
 *
 * The joint lives only while the BE is loaded and powered — it is **not** persisted via VS2's
 * serialiser. [serverTick] re-creates it on chunk reload, and [setRemoved] tears it down on
 * unload/break. This avoids dangling persisted joints from BEs that vanished while the world was
 * down, at the cost of a one-tick re-bind on reload.
 *
 * The joint API used here lives in `org.valkyrienskies.core.internal.joints.*` — nominally an
 * internal package, but the only joint surface available on the playtest 1.1.0 vs-core. The
 * construction pattern (shipId=null for world, raw shipyard coords on the ship side, addJoint
 * with delay=4, no .serialized() so the joint stays ephemeral and is re-bound on chunk reload
 * by [serverTick]) matches VS2's own `TestHingeBlock` and vstuff's `RopePosData`.
 */
class PlanarAnchorBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.PLANAR_ANCHOR.get(), pos, state) {

    /** VS2 joint id (>= 0) when this anchor is currently holding the ship. */
    private var jointId: Int = -1

    /** True between `addJoint` being scheduled and its callback firing — prevents double-create. */
    private var jointPending: Boolean = false

    /** Ship the joint is currently attached to, for sanity checks; -1L when inactive. */
    private var jointShipId: Long = -1L

    /** World position the joint pins to (block centre at activation moment). Synced to client. */
    private var anchorWorld: Vec3? = null

    /** Outboard point — block + [CLOUD_OFFSET] along the ship-centre→block ray. Synced to client. */
    var cloudWorld: Vec3? = null; private set

    /** Server-side: the game tick depower started; the joint has been released, but the visual
     *  is still playing the reverse animation. -1 = not retracting. Synced to client as a bool. */
    private var retractStartTick: Long = -1L

    /** Client-side mirror of [retractStartTick] (just whether we're in retract phase). */
    var isRetracting: Boolean = false; private set

    /** Other ship's id when the joint binds two ships (`shipId0 = otherShip.id`); -1 otherwise.
     *  Synced to client so the renderer can re-derive the chain endpoint each frame as the
     *  other ship moves, and swap to the solid-chain visual (no fade, no portal particles). */
    var tetheredShipId: Long = -1L; private set

    /** When [tetheredShipId] >= 0: the joint's pose0 in the other ship's *body* frame (raw
     *  shipyard coords). Client re-transforms this through the other ship's `shipToWorld` each
     *  frame to get the live chain endpoint. */
    var tetheredBodyPose: Vec3? = null; private set

    val tetheredToShip: Boolean get() = tetheredShipId >= 0L

    /** When this anchor's chain has been redirected to another anchor (the 5-block-Manhattan
     *  scan around the raycast hit found a [PlanarAnchorBlock]): the target's block position.
     *  Synced to the client so the renderer can live-track the target through whichever frame it
     *  sits in (its own ship, our ship, or the world). Null when our chain ends on a non-anchor
     *  block or in empty space. */
    var chainTargetAnchorPos: BlockPos? = null; private set

    // Server-only joint-config persistence. These hold the *resolved* joint parameters from the
    // last successful activate() (or rebindJoint), saved to disk so the joint can be re-bound
    // byte-for-byte after a chunk-unload / world-reload — instead of running the trace+scan
    // again, which can pick a different target if the world has changed in the interim.
    private var persistedJointType: Int = JOINT_NONE
    private var persistedPose0: Vec3? = null
    private var persistedTargetShipId: Long = -1L
    private var persistedTetherDistance: Float = -1f
    private var persistedJointCompliance: Double = JOINT_COMPLIANCE
    private var persistedPullOnly: Boolean = false

    /** Server-only: source anchors that have latched onto *this* anchor as their chain endpoint.
     *  Mirrored to the blockstate's [PlanarAnchorBlock.TARGETED] flag — when this set is
     *  non-empty the visual chain element is shown and the BE refuses to activate its own joint.
     *  Persisted to disk so the targeted state survives world reloads. */
    private val targetingSources: MutableSet<BlockPos> = HashSet()

    /** True when *another* anchor is currently using us as its chain endpoint — gates redstone
     *  activation (a targeted anchor can't fire its own chain) and drives the TARGETED model. */
    val isTargeted: Boolean get() = targetingSources.isNotEmpty()

    /** True while a chain is or was just visible — both extending and retracting count. */
    val isActive: Boolean get() = anchorWorld != null

    /** Client-side: the tick when the current animation phase began (extend or retract). Both
     *  phases share one timer; the direction is decided by [isRetracting]. -1 when nothing
     *  visible. Server-side: never read. */
    var clientPhaseStartTick: Long = -1L; private set

    /** Called once a server tick by [org.shipwrights.enderkinesis.block.PlanarAnchorBlock].
     *
     *  An anchor that is itself being targeted (chain-endpoint of *another* anchor) refuses
     *  redstone activation — `want` falls to false even with a live signal, so an existing
     *  joint also retracts the moment we get pulled into the targeted state.
     *
     *  Active-without-a-joint case: a same-ship target is purely visual (no physical joint,
     *  since two points on the same body can't be constrained). `visuallyActive` covers that —
     *  redstone going off still tears down the visual chain via [startRetract]. */
    fun serverTick(level: ServerLevel, pos: BlockPos, @Suppress("UNUSED_PARAMETER") state: BlockState) {
        // Either a ship-resident or a world-placed anchor can activate — the AABB scan finds a
        // peer in either body, and a world source with no target is simply inert (no joint).
        val want = level.hasNeighborSignal(pos) && !isTargeted
        val jointAlive = jointId >= 0 || jointPending
        val visuallyActive = anchorWorld != null && retractStartTick < 0L
        when {
            // Rebind path — the joint isn't VS-serialised (transient on purpose), but the
            // *config* to recreate it is in our NBT. On world reload [load] restores both
            // [persistedJointType] and the visual state ([anchorWorld] / [cloudWorld] / …),
            // so `visuallyActive` is *true* while `jointAlive` is *false*. The earlier
            // condition required `!visuallyActive`, which silently dropped this case → the
            // chain kept rendering but no constraint existed and the ship fell. Re-checking
            // `persistedJointType` first means we always recreate the missing joint before
            // even considering a fresh activate.
            want && !jointAlive && persistedJointType != JOINT_NONE -> rebindJoint(level, pos)
            // Fresh activate — powered, no live joint, no persisted config to recreate, and
            // no leftover visual to honour. Runs the trace + scan + spring-build pipeline.
            want && !jointAlive && !visuallyActive -> activate(level, pos)
            !want && (jointAlive || visuallyActive) -> startRetract(level)
            retractStartTick >= 0L &&
                level.gameTime - retractStartTick >= RETRACT_TICKS -> finishRetract()
        }
    }

    /** Manhattan-shell scan around [nearHit] for the closest planar anchor (iterates ascending
     *  Manhattan distance `d = 0, 1, … ANCHOR_SCAN_MANHATTAN`, returning the first hit). Rejects
     *  the source's own [sourcePos] and any candidate on the source's [sourceShipId] (same-ship
     *  pairing is never allowed). */
    private fun findNearbyAnchor(
        level: Level, nearHit: BlockPos, sourcePos: BlockPos, sourceShipId: Long,
    ): BlockPos? {
        val anchorBlock = EKBlocks.PLANAR_ANCHOR.get()
        val r = ANCHOR_SCAN_MANHATTAN
        for (d in 0..r) {
            for (dx in -d..d) {
                val absDxBudget = d - Math.abs(dx)
                for (dy in -absDxBudget..absDxBudget) {
                    val absDz = d - Math.abs(dx) - Math.abs(dy)
                    val dzs = if (absDz == 0) intArrayOf(0) else intArrayOf(absDz, -absDz)
                    for (dz in dzs) {
                        val candidate = BlockPos(nearHit.x + dx, nearHit.y + dy, nearHit.z + dz)
                        if (candidate == sourcePos) continue
                        if (level.getBlockState(candidate).block !== anchorBlock) continue
                        if (level.getLoadedShipManagingPos(candidate)?.id == sourceShipId) continue
                        return candidate
                    }
                }
            }
        }
        return null
    }

    /** Result of an anchor scan: the candidate's block position (in its own body frame —
     *  shipyard coords if [ship] != null, world coords otherwise), its current world-space
     *  centre, and the managing ship (or null when in the world body). */
    private data class AnchorScanResult(
        val pos: BlockPos,
        val worldCenter: Vec3,
        val ship: org.valkyrienskies.core.api.ships.Ship?,
    )

    /** 7×7×7 ship-inclusive AABB scan around [sourceCenterWorld] for the **closest** other
     *  planar anchor by Euclidean distance. Two passes:
     *
     *  1. **World** — iterate integer world coords inside the world-space AABB. Catches anchors
     *     placed in the world. Skipped when [allowWorldTarget] is false (world-source case —
     *     world-to-world pairing is disallowed).
     *  2. **Ship** — for each loaded ship whose `worldAABB` intersects the scan box (skipping
     *     [sourceShipId]), transform the scan AABB into the ship's body frame, scan the bounded
     *     body-frame block range, then re-filter each candidate by world-space membership.
     *
     *  Same-ship pairings are filtered out at the ship-pass entry (`ship.id == sourceShipId`);
     *  same-world pairings are gated by [allowWorldTarget]. */
    private fun findClosestAnchorInRange(
        level: ServerLevel,
        sourceCenterWorld: Vec3,
        sourcePos: BlockPos,
        sourceShipId: Long?,
        allowWorldTarget: Boolean,
    ): AnchorScanResult? {
        val anchorBlock = EKBlocks.PLANAR_ANCHOR.get()
        val half = ANCHOR_SCAN_SIDE / 2.0
        val scanMinX = sourceCenterWorld.x - half
        val scanMaxX = sourceCenterWorld.x + half
        val scanMinY = sourceCenterWorld.y - half
        val scanMaxY = sourceCenterWorld.y + half
        val scanMinZ = sourceCenterWorld.z - half
        val scanMaxZ = sourceCenterWorld.z + half

        var best: AnchorScanResult? = null
        var bestDistSq = Double.MAX_VALUE

        // World pass — integer coords inside the world AABB. Candidates whose chunk is shipyard
        // (rare given typical scan ranges, but possible if the source is *very* close to the
        // shipyard origin of some ship) get deferred to the ship pass, which computes the
        // proper world position via the ship's transform. Skipped entirely for the world-source
        // case — world-to-world targeting is disallowed.
        if (allowWorldTarget) {
        val wx0 = Math.floor(scanMinX).toInt()
        val wx1 = Math.floor(scanMaxX).toInt()
        val wy0 = Math.floor(scanMinY).toInt()
        val wy1 = Math.floor(scanMaxY).toInt()
        val wz0 = Math.floor(scanMinZ).toInt()
        val wz1 = Math.floor(scanMaxZ).toInt()
        for (x in wx0..wx1) {
            for (y in wy0..wy1) {
                for (z in wz0..wz1) {
                    val candidate = BlockPos(x, y, z)
                    if (candidate == sourcePos) continue
                    if (level.getBlockState(candidate).block !== anchorBlock) continue
                    if (level.getLoadedShipManagingPos(candidate) != null) continue
                    val cx = candidate.x + 0.5
                    val cy = candidate.y + 0.5
                    val cz = candidate.z + 0.5
                    if (cx < scanMinX || cx > scanMaxX) continue
                    if (cy < scanMinY || cy > scanMaxY) continue
                    if (cz < scanMinZ || cz > scanMaxZ) continue
                    val dx = cx - sourceCenterWorld.x
                    val dy = cy - sourceCenterWorld.y
                    val dz = cz - sourceCenterWorld.z
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 < bestDistSq) {
                        bestDistSq = d2
                        best = AnchorScanResult(candidate, Vec3(cx, cy, cz), null)
                    }
                }
            }
        }
        }       // end if (allowWorldTarget)

        // Ship pass — iterate ships intersecting the world scan box (skipping source's ship per
        // the same-ship rule).
        for (ship in level.shipObjectWorld.allShips) {
            if (ship.id == sourceShipId) continue
            val sa = ship.worldAABB
            if (sa.maxX() < scanMinX || sa.minX() > scanMaxX) continue
            if (sa.maxY() < scanMinY || sa.minY() > scanMaxY) continue
            if (sa.maxZ() < scanMinZ || sa.minZ() > scanMaxZ) continue

            // Body-frame bounding box: transform the 8 corners of the world scan box, then take
            // the axis-aligned bounds. This is a (slight) over-estimate for rotated ships —
            // candidates that fall outside the actual world scan box are re-filtered below.
            val w2s = ship.transform.worldToShip
            var bxMin = Double.POSITIVE_INFINITY
            var bxMax = Double.NEGATIVE_INFINITY
            var byMin = Double.POSITIVE_INFINITY
            var byMax = Double.NEGATIVE_INFINITY
            var bzMin = Double.POSITIVE_INFINITY
            var bzMax = Double.NEGATIVE_INFINITY
            val cornersX = doubleArrayOf(scanMinX, scanMaxX)
            val cornersY = doubleArrayOf(scanMinY, scanMaxY)
            val cornersZ = doubleArrayOf(scanMinZ, scanMaxZ)
            for (cx in cornersX) for (cy in cornersY) for (cz in cornersZ) {
                val v = Vector3d(cx, cy, cz)
                w2s.transformPosition(v)
                if (v.x < bxMin) bxMin = v.x; if (v.x > bxMax) bxMax = v.x
                if (v.y < byMin) byMin = v.y; if (v.y > byMax) byMax = v.y
                if (v.z < bzMin) bzMin = v.z; if (v.z > bzMax) bzMax = v.z
            }

            val sx0 = Math.floor(bxMin).toInt()
            val sx1 = Math.floor(bxMax).toInt()
            val sy0 = Math.floor(byMin).toInt()
            val sy1 = Math.floor(byMax).toInt()
            val sz0 = Math.floor(bzMin).toInt()
            val sz1 = Math.floor(bzMax).toInt()
            val s2w = ship.transform.shipToWorld
            for (x in sx0..sx1) {
                for (y in sy0..sy1) {
                    for (z in sz0..sz1) {
                        val candidate = BlockPos(x, y, z)
                        if (candidate == sourcePos) continue
                        if (level.getBlockState(candidate).block !== anchorBlock) continue
                        // Confirm the block really belongs to *this* ship (guard against
                        // adjacent ships' shipyard chunks bleeding into the bounding range).
                        if (level.getLoadedShipManagingPos(candidate)?.id != ship.id) continue
                        val cw = Vector3d(candidate.x + 0.5, candidate.y + 0.5, candidate.z + 0.5)
                        s2w.transformPosition(cw)
                        if (cw.x < scanMinX || cw.x > scanMaxX) continue
                        if (cw.y < scanMinY || cw.y > scanMaxY) continue
                        if (cw.z < scanMinZ || cw.z > scanMaxZ) continue
                        val dx = cw.x - sourceCenterWorld.x
                        val dy = cw.y - sourceCenterWorld.y
                        val dz = cw.z - sourceCenterWorld.z
                        val d2 = dx * dx + dy * dy + dz * dz
                        if (d2 < bestDistSq) {
                            bestDistSq = d2
                            best = AnchorScanResult(candidate, Vec3(cw.x, cw.y, cw.z), ship)
                        }
                    }
                }
            }
        }

        return best
    }

    /** Register that [sourcePos] is using us as its chain endpoint. Flips the TARGETED model
     *  variant on the empty → non-empty transition. Called by another anchor's BE on activate. */
    fun addSource(sourcePos: BlockPos) {
        val wasEmpty = targetingSources.isEmpty()
        if (!targetingSources.add(sourcePos)) return        // already known
        setChanged()
        if (wasEmpty) updateTargetedBlockstate()
    }

    /** Unregister a previously-added source. Flips TARGETED off when the last source leaves. */
    fun removeSource(sourcePos: BlockPos) {
        if (!targetingSources.remove(sourcePos)) return
        setChanged()
        if (targetingSources.isEmpty()) updateTargetedBlockstate()
    }

    /** Re-stamp the blockstate's TARGETED property to match [isTargeted]. UPDATE_CLIENTS only —
     *  no neighbour cascade. */
    private fun updateTargetedBlockstate() {
        val l = level ?: return
        if (l.isClientSide) return
        val state = blockState
        val cur = state.getValue(PlanarAnchorBlock.TARGETED)
        val want = isTargeted
        if (cur != want) {
            l.setBlock(blockPos, state.setValue(PlanarAnchorBlock.TARGETED, want), Block.UPDATE_CLIENTS)
        }
    }

    private fun activate(level: ServerLevel, pos: BlockPos) {
        // Activation fallback chain:
        //   Ship source: trace → (Manhattan scan around hit → anchor or hit block) → else AABB
        //                scan around source → anchor; else spherical "anchor in space".
        //   World source: AABB scan around source (ships only — world-to-world disallowed); else
        //                 do nothing.
        if (retractStartTick >= 0L) retractStartTick = -1L
        val ship = level.getLoadedShipManagingPos(pos)

        val centerLocal = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        val centerWorld = Vector3d(centerLocal)
        ship?.transform?.shipToWorld?.transformPosition(centerWorld)
        // Clockwork's `hasFinitePoseData` guard: a recently-teleported ship can briefly have a
        // non-finite transform; feeding NaN into VS2 joints corrupts the body. Drop this tick.
        if (!centerWorld.x.isFinite() || !centerWorld.y.isFinite() || !centerWorld.z.isFinite()) return

        val sourceCenterWorld = Vec3(centerWorld.x, centerWorld.y, centerWorld.z)
        // pose1 (source side): raw body-frame coords (shipyard if on ship, world otherwise).
        val poseSrcPos = Vector3d(centerLocal)
        val srcShipId: Long? = ship?.id

        // Filled in by the resolution below. `tetherDistance = -1f` is the marker for the
        // not-yet-resolved state — every active path overrides it before joint construction.
        var pose0Vec: Vector3d = Vector3d(centerWorld)
        var targetShipId: Long? = null
        var tetherDistance = -1f
        var targetAnchorPos: BlockPos? = null
        var cloudVec: Vec3 = sourceCenterWorld
        // Defaults to the standard compliance; the no-peer empty-space fallback below overrides
        // to a stiffer value because a single-DOF distance joint feels looser at the same
        // compliance as a 3-DOF spherical pin would.
        var jointCompliance = JOINT_COMPLIANCE
        // Pull-only mode for the empty-space tetherball: rope-style (`minDistance = null,
        // maxDistance = tetherDistance`) so the ship can drift closer freely but is yanked
        // back when it tries to leave the sphere. Concrete-target paths stay rigid (min==max).
        var isPullOnly = false

        if (ship == null) {
            // WORLD SOURCE: AABB scan, ship targets only.
            val aabbTarget = findClosestAnchorInRange(
                level, sourceCenterWorld, pos, sourceShipId = null, allowWorldTarget = false,
            ) ?: return                                          // no peer → inert
            val dist = sourceCenterWorld.distanceTo(aabbTarget.worldCenter).toFloat()
            if (dist < 0.1f) return
            pose0Vec = Vector3d(aabbTarget.pos.x + 0.5, aabbTarget.pos.y + 0.5, aabbTarget.pos.z + 0.5)
            targetShipId = aabbTarget.ship!!.id
            tetherDistance = dist
            targetAnchorPos = aabbTarget.pos
            cloudVec = aabbTarget.worldCenter
        } else {
            // SHIP SOURCE — compute the outboard direction once (used for both raycast endpoints
            // and the spherical fallback's cloud).
            val s2w = ship.transform.shipToWorld
            val cloudDistance = ship.shipAABB?.let { sb ->
                val dx = (sb.maxX() - sb.minX() + 1).toDouble()
                val dy = (sb.maxY() - sb.minY() + 1).toDouble()
                val dz = (sb.maxZ() - sb.minZ() + 1).toDouble()
                (Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5).coerceAtMost(CLOUD_MAX_DISTANCE)
            } ?: CLOUD_MAX_DISTANCE
            val com = ship.transform.positionInShip
            val shipCenterWorld = Vector3d(com).also { s2w.transformPosition(it) }
            val outward = Vector3d(centerWorld).sub(shipCenterWorld)
            val outDist = outward.length()
            if (outDist > 1e-6) outward.div(outDist) else outward.set(0.0, 1.0, 0.0)
            val outboardCloud = Vector3d(centerWorld).add(Vector3d(outward).mul(cloudDistance))

            // Two-range raycasts. Vanilla `level.clip()` is VS2-mixed to call clipIncludeShips
            // with NO skipShip — so both raycasts must pass `ship.id` explicitly or the trace
            // hits our own ship.
            val worldEndVec = Vec3(outboardCloud.x, outboardCloud.y, outboardCloud.z)
            val shipReachEnd = Vector3d(centerWorld).add(Vector3d(outward).mul(cloudDistance * SHIP_REACH_MULT))
            val shipEndVec = Vec3(shipReachEnd.x, shipReachEnd.y, shipReachEnd.z)
            val worldCtx = ClipContext(sourceCenterWorld, worldEndVec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null as Entity?)
            val shipCtx = ClipContext(sourceCenterWorld, shipEndVec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null as Entity?)
            val worldHitResult = level.clipIncludeShips(worldCtx, true, ship.id, false)
            val shipHitResult = level.clipIncludeShips(shipCtx, true, ship.id, true)
            val worldHitDist = if (worldHitResult.type == HitResult.Type.BLOCK)
                worldHitResult.location.distanceTo(sourceCenterWorld) else Double.MAX_VALUE
            val shipHitDist = if (shipHitResult.type == HitResult.Type.BLOCK)
                shipHitResult.location.distanceTo(sourceCenterWorld) else Double.MAX_VALUE
            val hit = when {
                worldHitResult.type == HitResult.Type.BLOCK && shipHitResult.type == HitResult.Type.BLOCK ->
                    if (worldHitDist <= shipHitDist) worldHitResult else shipHitResult
                worldHitResult.type == HitResult.Type.BLOCK -> worldHitResult
                shipHitResult.type == HitResult.Type.BLOCK -> shipHitResult
                else -> null
            }

            if (hit != null) {
                // Trace hit. Manhattan scan around the hit for a nearby anchor.
                val nearbyAnchor = findNearbyAnchor(level, hit.blockPos, pos, ship.id)
                if (nearbyAnchor != null) {
                    // Target = the nearby anchor (snap chain endpoint to its block CENTRE).
                    val managingShip = level.getLoadedShipManagingPos(nearbyAnchor)
                    val centerSy = Vector3d(nearbyAnchor.x + 0.5, nearbyAnchor.y + 0.5, nearbyAnchor.z + 0.5)
                    val anchorWorldCenter = if (managingShip != null) {
                        val v = Vector3d(centerSy)
                        managingShip.transform.shipToWorld.transformPosition(v)
                        Vec3(v.x, v.y, v.z)
                    } else Vec3(centerSy.x, centerSy.y, centerSy.z)
                    val dist = sourceCenterWorld.distanceTo(anchorWorldCenter).toFloat()
                    if (dist < 0.1f) return
                    pose0Vec = if (managingShip != null) centerSy
                               else Vector3d(anchorWorldCenter.x, anchorWorldCenter.y, anchorWorldCenter.z)
                    targetShipId = managingShip?.id
                    tetherDistance = dist
                    targetAnchorPos = nearbyAnchor
                    cloudVec = anchorWorldCenter
                } else {
                    // No nearby anchor → target = the trace-hit block itself (ship block or
                    // world terrain). pose0 in target's body frame.
                    val managingShip = level.getLoadedShipManagingPos(hit.blockPos)
                    val hitWorld = hit.location
                    val dist = sourceCenterWorld.distanceTo(hitWorld).toFloat()
                    if (dist < 0.1f) return
                    pose0Vec = if (managingShip != null) {
                        val v = Vector3d(hitWorld.x, hitWorld.y, hitWorld.z)
                        managingShip.transform.worldToShip.transformPosition(v)
                        v
                    } else Vector3d(hitWorld.x, hitWorld.y, hitWorld.z)
                    targetShipId = managingShip?.id
                    tetherDistance = dist
                    targetAnchorPos = null
                    cloudVec = hitWorld
                }
            } else {
                // Trace missed. AABB-around-source fallback.
                val aabbTarget = findClosestAnchorInRange(
                    level, sourceCenterWorld, pos, sourceShipId = ship.id, allowWorldTarget = true,
                )
                if (aabbTarget != null) {
                    val dist = sourceCenterWorld.distanceTo(aabbTarget.worldCenter).toFloat()
                    if (dist >= 0.1f) {
                        pose0Vec = if (aabbTarget.ship != null) {
                            Vector3d(aabbTarget.pos.x + 0.5, aabbTarget.pos.y + 0.5, aabbTarget.pos.z + 0.5)
                        } else {
                            Vector3d(aabbTarget.worldCenter.x, aabbTarget.worldCenter.y, aabbTarget.worldCenter.z)
                        }
                        targetShipId = aabbTarget.ship?.id
                        tetherDistance = dist
                        targetAnchorPos = aabbTarget.pos
                        cloudVec = aabbTarget.worldCenter
                    }
                }
                if (tetherDistance < 0f) {
                    // Still no target. Pull-only distance-joint tether to the outboard cloud:
                    // ship can drift closer to the cloud freely (rope is slack), but the joint
                    // yanks it back if it tries to move past `cloudDistance`. World body
                    // (shipId0 = null) so the cloud doesn't move with anything. Stiffer
                    // compliance restores the firm feel the previous spherical pin had.
                    pose0Vec = Vector3d(outboardCloud)
                    targetShipId = null
                    tetherDistance = cloudDistance.toFloat()
                    targetAnchorPos = null
                    cloudVec = Vec3(outboardCloud.x, outboardCloud.y, outboardCloud.z)
                    jointCompliance = EMPTY_SPACE_COMPLIANCE
                    isPullOnly = true
                }
            }
        }

        // Notify the target anchor (if any) BEFORE the joint callback so both anchors' chain
        // visuals appear in the same tick.
        targetAnchorPos?.let { tap ->
            (level.getBlockEntity(tap) as? PlanarAnchorBlockEntity)?.addSource(pos)
        }

        // Pull-only paths use a damped spring at the `max` limit so multiple coexisting
        // joints don't feed runaway correction impulses to each other. Rigid-target paths
        // (anchor / wall / ship-block hit) leave stiffness/damping null → hard limit.
        //
        // Spring constants scale with ship mass — a fixed 10 kN/m was orders of magnitude
        // too soft for heavy ships (12 Mkg test ship would sag through `m·g / 4k` = 3 km of
        // travel before equilibrating). See [computeEmptySpaceSpring] for the maths.
        val (epStiffness, epDamping) =
            if (isPullOnly) computeEmptySpaceSpring(ship?.inertiaData?.mass)
            else 0f to 0f                                  // values unused; stiffness=null below
        val joint: VSJoint = VSDistanceJoint(
            shipId0 = targetShipId,
            pose0 = VSJointPose(pose0Vec, Quaterniond()),
            shipId1 = srcShipId,
            pose1 = VSJointPose(poseSrcPos, Quaterniond()),
            compliance = jointCompliance,
            minDistance = if (isPullOnly) null else tetherDistance,
            maxDistance = tetherDistance,
            stiffness = if (isPullOnly) epStiffness else null,
            damping = if (isPullOnly) epDamping else null,
        )
        if (isPullOnly) {
            LOG.info(
                "[EK anchor] empty-space joint at {} ship={} mass={} kg → k={} N/m, c={} N·s/m, " +
                    "tetherDistance={}",
                pos, srcShipId, ship?.inertiaData?.mass, epStiffness, epDamping, tetherDistance,
            )
        }

        val anchorVec = cloudVec
        val tetherShipForCallback: Long = targetShipId ?: -1L
        val tetherBodyPoseForCallback: Vec3? = if (targetShipId != null)
            Vec3(pose0Vec.x, pose0Vec.y, pose0Vec.z) else null
        val cloudVecForCallback = cloudVec
        val chainTargetForCallback: BlockPos? = targetAnchorPos
        // Snapshot the resolved joint config for the callback — these get written into the
        // persisted* fields on success so we can rebind the same joint after a chunk-unload /
        // world-reload without re-running trace+scan. Always a distance joint now.
        val persistedTypeForCallback: Int = JOINT_DISTANCE
        val persistedPose0ForCallback: Vec3 = Vec3(pose0Vec.x, pose0Vec.y, pose0Vec.z)
        val persistedTargetShipIdForCallback: Long = targetShipId ?: -1L
        val persistedTetherDistanceForCallback: Float = tetherDistance
        val persistedComplianceForCallback: Double = jointCompliance
        val persistedPullOnlyForCallback: Boolean = isPullOnly
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        val sid = srcShipId ?: -1L
        jointPending = true

        // `addJoint`'s callback fires on the physics tick — bounce back onto the game thread to
        // touch BE state and the client-sync packet. Same race-handling pattern as before:
        //  - cancelled before the callback fired → drop the joint
        //  - BE was removed → callback still runs and cleans up via the captured GTPA reference.
        // `delay = 0` matches Clockwork's SpinoffBearing and vstuff's ropes (both bind joints
        // between already-loaded bodies); 4 is only needed when also spawning a fresh ship.
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                if (!jointPending) {
                    LOG.warn(
                        "[EK anchor] addJoint callback dropped at {} (jointPending=false — " +
                            "redstone cleared, startRetract called, or BE removed between activate " +
                            "and callback); released id={}", pos, id,
                    )
                    gtpa.removeJoint(id)
                } else {
                    jointId = id
                    jointShipId = sid
                    anchorWorld = anchorVec
                    cloudWorld = cloudVecForCallback
                    tetheredShipId = tetherShipForCallback
                    tetheredBodyPose = tetherBodyPoseForCallback
                    chainTargetAnchorPos = chainTargetForCallback
                    persistedJointType = persistedTypeForCallback
                    persistedPose0 = persistedPose0ForCallback
                    persistedTargetShipId = persistedTargetShipIdForCallback
                    persistedTetherDistance = persistedTetherDistanceForCallback
                    persistedJointCompliance = persistedComplianceForCallback
                    persistedPullOnly = persistedPullOnlyForCallback
                    jointPending = false
                    LOG.info(
                        "[EK anchor] activate callback set joint={} at {} anchorWorld={} " +
                            "cloudWorld={} tetheredShipId={} chainTarget={} pullOnly={}",
                        id, pos, anchorWorld, cloudWorld, tetheredShipId,
                        chainTargetAnchorPos, persistedPullOnly,
                    )
                    sync()
                }
            }
        }
    }

    /** Re-create the same joint that was last successfully bound, using the persisted config.
     *  Called by [serverTick] after a chunk-unload / world-reload when [persistedJointType] is
     *  non-zero (the BE came back online still believing it has an active anchor). Skips the
     *  trace+scan step in [activate] — the world could have changed in the interim, and we want
     *  the joint to come back to *the same* target it was bound to, not a freshly-resolved one.
     *
     *  Visual state (`anchorWorld`, `cloudWorld`, `tetheredShipId`, `tetheredBodyPose`,
     *  `chainTargetAnchorPos`) was already restored by [load]; we only need to recreate the
     *  physics joint here. */
    private fun rebindJoint(level: ServerLevel, pos: BlockPos) {
        if (retractStartTick >= 0L) retractStartTick = -1L
        val pose0Stored = persistedPose0 ?: return                  // shouldn't happen — guarded
        val ship = level.getLoadedShipManagingPos(pos)
        LOG.info(
            "[EK anchor] rebindJoint at {} ship={} mass={} kg pose0={} tetherDistance={} " +
                "pullOnly={} targetShipId={}",
            pos, ship?.id, ship?.inertiaData?.mass, pose0Stored,
            persistedTetherDistance, persistedPullOnly, persistedTargetShipId,
        )
        val centerLocal = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        val poseSrcPos = Vector3d(centerLocal)
        val srcShipId: Long? = ship?.id
        val pose0 = Vector3d(pose0Stored.x, pose0Stored.y, pose0Stored.z)
        val targetShipIdNullable: Long? = if (persistedTargetShipId >= 0L) persistedTargetShipId else null

        if (persistedJointType != JOINT_DISTANCE) return
        // Mass-aware empty-space spring on rebind too. Mass is read live from the current
        // ship state — if the user packed extra blocks since last activation, the spring
        // gets stiffer with the heavier ship instead of using a stale persisted value.
        val (epStiffness, epDamping) =
            if (persistedPullOnly) computeEmptySpaceSpring(ship?.inertiaData?.mass)
            else 0f to 0f
        val joint: VSJoint = VSDistanceJoint(
            shipId0 = targetShipIdNullable,
            pose0 = VSJointPose(pose0, Quaterniond()),
            shipId1 = srcShipId,
            pose1 = VSJointPose(poseSrcPos, Quaterniond()),
            compliance = persistedJointCompliance,
            minDistance = if (persistedPullOnly) null else persistedTetherDistance,
            maxDistance = persistedTetherDistance,
            stiffness = if (persistedPullOnly) epStiffness else null,
            damping = if (persistedPullOnly) epDamping else null,
        )

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        val sid = srcShipId ?: -1L
        jointPending = true
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                if (!jointPending) {
                    gtpa.removeJoint(id)
                } else {
                    jointId = id
                    jointShipId = sid
                    // visual state was already restored from disk in load(); leave it alone.
                    jointPending = false
                    sync()
                }
            }
        }

        // Idempotent re-notify so the target's TARGETED state catches up if its BE also reloaded
        // (or if the source's chunk loaded first and the target wasn't yet around).
        chainTargetAnchorPos?.let { tap ->
            (level.getBlockEntity(tap) as? PlanarAnchorBlockEntity)?.addSource(pos)
        }
    }

    /** Joint released, but anchor/cloud kept around so the client can play the retract animation
     *  (chain leading edge walks block → portal). [finishRetract] clears them when it's done. */
    private fun startRetract(level: ServerLevel) {
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        if (jointId >= 0) gtpa.removeJoint(jointId)
        jointId = -1
        jointShipId = -1
        jointPending = false                  // any in-flight callback will drop its joint
        retractStartTick = level.gameTime
        sync()
    }

    /** Tear down the visual once the retract animation has elapsed. Releasing the target
     *  anchor at *finish* (not start) keeps the two anchors' chain visuals in sync during the
     *  retract animation — the target's TARGETED model stays on while our chain is still
     *  visibly pulling away.
     *
     *  Also clears the persisted joint config so the next power-up runs a fresh trace+scan
     *  instead of re-binding the same target — a clean retract is an "intentional sever". */
    private fun finishRetract() {
        retractStartTick = -1L
        anchorWorld = null
        cloudWorld = null
        tetheredShipId = -1L
        tetheredBodyPose = null
        persistedJointType = JOINT_NONE
        persistedPose0 = null
        persistedTargetShipId = -1L
        persistedTetherDistance = -1f
        persistedJointCompliance = JOINT_COMPLIANCE
        persistedPullOnly = false
        releaseChainTarget()
        sync()
    }

    /** Tell the anchor we were chained to that we've released. Safe to call when there is no
     *  target — does nothing in that case. */
    private fun releaseChainTarget() {
        val tap = chainTargetAnchorPos ?: return
        chainTargetAnchorPos = null
        val srv = level as? ServerLevel ?: return
        (srv.getBlockEntity(tap) as? PlanarAnchorBlockEntity)?.removeSource(blockPos)
    }

    /**
     * Mass-aware spring constants for the empty-space tetherball.
     *
     *  ## Why mass-aware
     *
     *  A fixed spring (the old `10_000 N/m` constant) is fine for sub-100 t ships but turns
     *  catastrophically soft as ship mass climbs. With 4 anchors at 10 kN/m each, the
     *  ship's equilibrium drop is `m·g / (4 · 10 kN/m)`:
     *
     *  - 10 t ship → 2.5 m sag (acceptable).
     *  - 100 t ship → 25 m sag (visibly drooping past the cloud).
     *  - **12 Mt ship (user's test rig) → 3 km sag** (ship plummets through the void).
     *
     *  Scaling stiffness with mass keeps the sag bounded at [DESIRED_DROP] regardless of
     *  ship weight. Per spec ("4 anchors that need to hold it up"), we assume an
     *  [ASSUMED_JOINT_COUNT]-anchor rig and divide the total weight evenly.
     *
     *  ## Maths
     *
     *  Per-anchor stiffness `k`:
     *      `ASSUMED_JOINT_COUNT · k · DESIRED_DROP = m · g`
     *      → `k = m · g / (ASSUMED_JOINT_COUNT · DESIRED_DROP)`
     *
     *  Per-anchor damping `c` at critical (mass-share = m/n per anchor):
     *      `c = 2 · √(k · m/n)`
     *
     *  Critical damping makes the ship settle without oscillating — undershooting (high
     *  `c`) makes the system feel sluggish; overshooting causes the bouncing the original
     *  fixed-stiffness comment warned about. Mass-aware critical adapts to whatever ship is
     *  hanging from it.
     */
    private fun computeEmptySpaceSpring(shipMass: Double?): Pair<Float, Float> {
        val m = (shipMass ?: MIN_MASS_FOR_SPRING).coerceAtLeast(MIN_MASS_FOR_SPRING)
        val k = m * GRAVITY / (ASSUMED_JOINT_COUNT * DESIRED_DROP)
        val c = 2.0 * Math.sqrt(k * m / ASSUMED_JOINT_COUNT)
        return k.toFloat() to c.toFloat()
    }

    /**
     * Wipe every scrap of joint state so the next [serverTick] runs a fresh [activate] in the
     * BE's *current* body frame, instead of [rebindJoint]ing with stale data.
     *
     * Used by the structure-shipify processors (the Ygann Anchor in particular) after
     * [ShipAssembler.assembleToShip] moves the structure's blocks into a new ship: the NBT
     * the BEs came back from has `persistedPose0` / `persistedTargetShipId` pointing at
     * whichever world built the structure template, and that state is meaningless against
     * the freshly-assembled ship. The BE has to forget its history *and* tear down any joint
     * it has live, then let the next tick resolve a target by trace+scan in the new frame.
     *
     * Mirrors the cleanup [setRemoved] does for the joint, plus a full reset of the
     * persisted-config + visual fields + targeting relationships that survive normally.
     */
    fun resetForStructureRespawn() {
        val srv = level as? ServerLevel
        val hadJoint = jointId >= 0
        val hadPersisted = persistedJointType != JOINT_NONE
        if (hadJoint && srv != null) {
            ValkyrienSkiesMod.getOrCreateGTPA(srv.dimensionId).removeJoint(jointId)
        }
        jointId = -1
        jointPending = false
        jointShipId = -1L
        anchorWorld = null
        cloudWorld = null
        retractStartTick = -1L
        tetheredShipId = -1L
        tetheredBodyPose = null
        chainTargetAnchorPos = null
        // Templated targeting relationships are also stale — the other anchor referenced
        // here might not exist on this ship at all, or might be in a different relative
        // position after assembly. Let the next activate()'s addSource rebuild it.
        targetingSources.clear()
        persistedJointType = JOINT_NONE
        persistedPose0 = null
        persistedTargetShipId = -1L
        persistedTetherDistance = -1f
        persistedJointCompliance = JOINT_COMPLIANCE
        persistedPullOnly = false
        setChanged()
        sync()
        LOG.info(
            "[EK anchor] resetForStructureRespawn at {} (hadJoint={}, hadPersistedConfig={}) — " +
                "all joint + visual state cleared; next serverTick will run fresh activate()",
            blockPos, hadJoint, hadPersisted,
        )
    }

    override fun setRemoved() {
        super.setRemoved()
        // Block-break / chunk-unload: cleanup now, no animation — the BE is going away.
        (level as? ServerLevel)?.let { srv ->
            if (jointId >= 0) {
                ValkyrienSkiesMod.getOrCreateGTPA(srv.dimensionId).removeJoint(jointId)
            }
            // Notify whichever anchor we were chained to so its TARGETED state clears even if
            // we never got to finishRetract (chunk unload, block broken mid-active, etc.).
            chainTargetAnchorPos?.let { tap ->
                (srv.getBlockEntity(tap) as? PlanarAnchorBlockEntity)?.removeSource(blockPos)
            }
        }
        jointId = -1
        jointPending = false
        retractStartTick = -1L
        tetheredShipId = -1L
        tetheredBodyPose = null
        chainTargetAnchorPos = null
    }

    /** Push a state change to clients so the renderer can show/hide chains and particles. */
    private fun sync() {
        setChanged()
        val l = level ?: return
        // UPDATE_ALL (neighbours + clients) matches vanilla `LecternBlockEntity.bookChanged`'s
        // pattern. Just UPDATE_CLIENTS alone *should* be enough, but the broader flag is what's
        // been battle-tested for BE-data sync.
        if (!l.isClientSide) l.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_ALL)
    }

    // Disk persistence: targeting relationships AND the resolved joint config. The joint itself
    // is ephemeral (not VS-serialized), but saving the config means [rebindJoint] can recreate
    // the exact same joint after a chunk-unload / world-reload instead of re-running trace+scan.
    // Matches Clockwork's `SpinoffBearingBlockEntity` pattern (config-in-NBT + verify-then-
    // recreate-on-load).
    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        chainTargetAnchorPos?.let {
            tag.putInt("TargetAnchorX", it.x)
            tag.putInt("TargetAnchorY", it.y)
            tag.putInt("TargetAnchorZ", it.z)
        }
        if (targetingSources.isNotEmpty()) {
            val list = ListTag()
            for (p in targetingSources) {
                val sub = CompoundTag()
                sub.putInt("X", p.x); sub.putInt("Y", p.y); sub.putInt("Z", p.z)
                list.add(sub)
            }
            tag.put("TargetingSources", list)
        }
        // Resolved joint config — only when we currently have an active joint to persist.
        if (persistedJointType != JOINT_NONE) {
            tag.putByte("JointType", persistedJointType.toByte())
            persistedPose0?.let {
                tag.putDouble("Pose0X", it.x)
                tag.putDouble("Pose0Y", it.y)
                tag.putDouble("Pose0Z", it.z)
            }
            tag.putLong("PersistedTargetShipId", persistedTargetShipId)
            tag.putFloat("PersistedTetherDistance", persistedTetherDistance)
            tag.putDouble("PersistedJointCompliance", persistedJointCompliance)
            if (persistedPullOnly) tag.putBoolean("PersistedPullOnly", true)
            // The visual state on the source side too — so the chain reappears at the same
            // length/endpoint the moment the BE loads, before [rebindJoint] runs.
            anchorWorld?.let {
                tag.putDouble("AnchorX", it.x); tag.putDouble("AnchorY", it.y); tag.putDouble("AnchorZ", it.z)
            }
            cloudWorld?.let {
                tag.putDouble("CloudX", it.x); tag.putDouble("CloudY", it.y); tag.putDouble("CloudZ", it.z)
            }
            if (tetheredShipId >= 0L) {
                tag.putLong("TetheredShipId", tetheredShipId)
                tetheredBodyPose?.let {
                    tag.putDouble("TetheredPoseX", it.x)
                    tag.putDouble("TetheredPoseY", it.y)
                    tag.putDouble("TetheredPoseZ", it.z)
                }
            }
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        // [load] is also invoked by [handleUpdateTag] on the client — read the visual state if
        // it's there. The server reaches this path on world load with an empty tag (fine).
        val prevHadAnchor = anchorWorld != null
        val prevRetracting = isRetracting
        anchorWorld = if (tag.contains("AnchorX")) Vec3(
            tag.getDouble("AnchorX"), tag.getDouble("AnchorY"), tag.getDouble("AnchorZ")
        ) else null
        cloudWorld = if (tag.contains("CloudX")) Vec3(
            tag.getDouble("CloudX"), tag.getDouble("CloudY"), tag.getDouble("CloudZ")
        ) else null
        isRetracting = tag.getBoolean("Retracting")
        tetheredShipId = if (tag.contains("TetheredShipId")) tag.getLong("TetheredShipId") else -1L
        tetheredBodyPose = if (tag.contains("TetheredPoseX")) Vec3(
            tag.getDouble("TetheredPoseX"), tag.getDouble("TetheredPoseY"), tag.getDouble("TetheredPoseZ")
        ) else null
        chainTargetAnchorPos = if (tag.contains("TargetAnchorX")) BlockPos(
            tag.getInt("TargetAnchorX"), tag.getInt("TargetAnchorY"), tag.getInt("TargetAnchorZ")
        ) else null
        targetingSources.clear()
        if (tag.contains("TargetingSources")) {
            val list = tag.getList("TargetingSources", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val sub = list.getCompound(i)
                targetingSources.add(BlockPos(sub.getInt("X"), sub.getInt("Y"), sub.getInt("Z")))
            }
        }
        // Joint config — server-only (the client doesn't rebind, only the visual state matters
        // to it, and that's already covered by AnchorX/CloudX/TetheredShipId above). Disk loads
        // include the joint NBT, packet updates from sync() typically don't.
        if (tag.contains("JointType")) {
            persistedJointType = tag.getByte("JointType").toInt()
            persistedPose0 = if (tag.contains("Pose0X")) Vec3(
                tag.getDouble("Pose0X"), tag.getDouble("Pose0Y"), tag.getDouble("Pose0Z")
            ) else null
            persistedTargetShipId =
                if (tag.contains("PersistedTargetShipId")) tag.getLong("PersistedTargetShipId") else -1L
            persistedTetherDistance =
                if (tag.contains("PersistedTetherDistance")) tag.getFloat("PersistedTetherDistance") else -1f
            persistedJointCompliance =
                if (tag.contains("PersistedJointCompliance")) tag.getDouble("PersistedJointCompliance") else JOINT_COMPLIANCE
            persistedPullOnly = tag.getBoolean("PersistedPullOnly")
        } else {
            persistedJointType = JOINT_NONE
            persistedPose0 = null
            persistedTargetShipId = -1L
            persistedTetherDistance = -1f
            persistedJointCompliance = JOINT_COMPLIANCE
            persistedPullOnly = false
        }
        // Client only: stamp the moment of *this* animation phase locally — any transition
        // (inactive → extending, extending → retracting, retracting → extending) resets the timer.
        val lvl = level
        if (lvl != null && lvl.isClientSide) {
            if (anchorWorld == null) {
                clientPhaseStartTick = -1L
            } else if (!prevHadAnchor || prevRetracting != isRetracting) {
                clientPhaseStartTick = lvl.gameTime
            }
        }
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        // Initial chunk-load case: `load()` ran before `setLevel()`, so its client-side stamp
        // was skipped. Stamp now if we arrived already-visible.
        if (level.isClientSide && anchorWorld != null && clientPhaseStartTick < 0L) {
            clientPhaseStartTick = level.gameTime
        }
    }

    override fun getUpdateTag(): CompoundTag {
        val tag = super.getUpdateTag()
        anchorWorld?.let {
            tag.putDouble("AnchorX", it.x); tag.putDouble("AnchorY", it.y); tag.putDouble("AnchorZ", it.z)
        }
        cloudWorld?.let {
            tag.putDouble("CloudX", it.x); tag.putDouble("CloudY", it.y); tag.putDouble("CloudZ", it.z)
        }
        if (retractStartTick >= 0L) tag.putBoolean("Retracting", true)
        if (tetheredShipId >= 0L) {
            tag.putLong("TetheredShipId", tetheredShipId)
            tetheredBodyPose?.let {
                tag.putDouble("TetheredPoseX", it.x)
                tag.putDouble("TetheredPoseY", it.y)
                tag.putDouble("TetheredPoseZ", it.z)
            }
        }
        chainTargetAnchorPos?.let {
            tag.putInt("TargetAnchorX", it.x)
            tag.putInt("TargetAnchorY", it.y)
            tag.putInt("TargetAnchorZ", it.z)
        }
        return tag
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    companion object {
        private val LOG = com.mojang.logging.LogUtils.getLogger()

        /** Hard cap on how far past the block the cloud is allowed to sit, regardless of ship
         *  size. The actual distance is `min(shipAABBHalfDiagonal, CLOUD_MAX_DISTANCE)`. */
        const val CLOUD_MAX_DISTANCE = 24.0
        /** Multiplier on the world-clip range for the ship-only raycast. Terrain is reached at
         *  `cloudDistance`; ships extend that to `cloudDistance × this`. */
        private const val SHIP_REACH_MULT = 1.5
        /** Manhattan radius scanned around the trace hit for a nearby [PlanarAnchorBlock]. If
         *  found, the chain endpoint snaps to that anchor and the anchor becomes TARGETED. */
        private const val ANCHOR_SCAN_MANHATTAN = 7
        /** Side length of the world-space cube AABB centred on the source that we scan for a
         *  peer anchor when the trace misses. 7 → a 7×7×7 cube (half-extent 3.5). */
        private const val ANCHOR_SCAN_SIDE = 7
        /** Inverse spring stiffness for the position pin. 1e-10 (default) is dead-rigid, which
         *  makes the solver build up large stored forces — felt as both stiffness when rotating
         *  around the pivot *and* a "trailing" effect when the joint is removed (the integrator
         *  still has to dissipate those forces). 1e-6 is a soft-but-firm spring: under normal
         *  loads the block barely drifts, and at depower the constraint's effect drops to zero
         *  cleanly. (Ignored on Krunch_PhysX, where the joint is always rigid.) */
        private const val JOINT_COMPLIANCE = 1e-6
        /** Stiffer compliance for the no-peer "tetherball" case. A distance joint with
         *  `min == max == cloudDistance` constrains only the scalar separation (1 DOF), so at
         *  the regular [JOINT_COMPLIANCE] it feels noticeably looser than the previous spherical
         *  pin (3 DOFs) did at the same value. 1e-10 here is acceptable where it wouldn't be on
         *  the peer-pinned [JOINT_COMPLIANCE] path: the single-DOF distance constraint can't
         *  build the large 3-DOF stored-force pileup that motivated 1e-6 for the spherical pin,
         *  so we can run effectively rigid without trailing-impulse artefacts. */
        private const val EMPTY_SPACE_COMPLIANCE = 1e-10
        /** VS2 world gravity magnitude (m/s²); same constant the lattice's force inducer
         *  uses. */
        private const val GRAVITY = 10.0

        /** How many empty-space anchors we *assume* a typical heavy ship is hung from when
         *  picking the per-anchor stiffness. With 4 anchors at the spec'd [DESIRED_DROP],
         *  per-anchor force at equilibrium is `mass · g / 4` — matches the user's actual
         *  4-anchor rig and is a reasonable middle ground for 1–8-anchor setups (3 anchors
         *  sag slightly more, 6 sag slightly less). */
        private const val ASSUMED_JOINT_COUNT = 4.0

        /** Target sag (m) at full ship weight — the springs are sized so [ASSUMED_JOINT_COUNT]
         *  anchors together produce exactly `mass · g` of restoring force at this much drop
         *  below the equilibrium point. Smaller = stiffer-feeling hang. */
        private const val DESIRED_DROP = 1.0

        /** Floor on the ship-mass used to size the spring. Stops a near-massless ship from
         *  computing absurdly soft springs (or zero from a ship that hasn't finished
         *  initialising its inertia data); 1000 kg is roughly one slab of cobble. */
        private const val MIN_MASS_FOR_SPRING = 1000.0
        /** Server-side: ticks the retract animation occupies before the visual is cleared. */
        private const val RETRACT_TICKS = 6L
        /** Joint-type tag used by [persistedJointType] (and the on-disk "JointType" NBT byte).
         *  Only DISTANCE is in use — every active path builds a `VSDistanceJoint`. */
        private const val JOINT_NONE = 0
        private const val JOINT_DISTANCE = 2
    }
}
