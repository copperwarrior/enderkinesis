package org.shipwrights.enderkinesis.body

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.joints.VSFixedJoint
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Server-side coordinator for Ga'Mylan cage ↔ Crystal Pedestal homing.
 * Runs from [org.shipwrights.enderkinesis.item.GamylanCageManager]'s
 * server tick. Four responsibilities:
 *
 *  1. **Home discovery** — for each cage without an active tome grab,
 *     search the block column below the crystal's live body position.
 *     The column is checked against every ship intersecting a thin
 *     vertical AABB around the crystal (each ship's shipyard-frame
 *     column) as well as the plain world column. The nearest unpowered
 *     [org.shipwrights.enderkinesis.block.CrystalPedestalBlock] wins,
 *     and its owning frame (ship id or null) is recorded on the cage.
 *  2. **Per-tick refresh** — while a pedestal home is set, recompute
 *     [GamylanCageData.hoverPos] and [GamylanCageData.homeRotTarget]
 *     from the host ship's current transform each server tick so a
 *     crystal welded to a mobile pedestal follows the ship in position
 *     and orientation.
 *  3. **Drift-and-align → weld** — once rotation is within
 *     [ALIGN_TOL_COS] of the target we snap-align via
 *     [org.valkyrienskies.core.api.bodies.ServerBaseVsBody.setTransform]
 *     and request a [VSFixedJoint] against the host ship (or world) at
 *     the pedestal-top pose in the owning frame.
 *  4. **Powered / broken pedestal** — [onPedestalPowered] and
 *     [onPedestalRemoved] release the joint (if any) and clear the
 *     home so the crystal is free to move; discovery re-adopts on the
 *     next tick if the pedestal is still standing and unpowered.
 */
object GamylanCagePedestals {

    private val LOG = LogUtils.getLogger()

    /** Max vertical distance (blocks) between the live body position and
     *  the pedestal top face for the block to count as a home candidate. */
    private const val HOME_SCAN_MAX_DROP: Int = 8

    /** XZ tolerance (blocks) between the crystal centre and the
     *  pedestal centre in the pedestal's own frame — a cage drifted
     *  this far off column no longer belongs to that pedestal. */
    private const val HOME_XZ_TOLERANCE: Double = 1.0

    /** Vertical clearance between the pedestal's top face and the
     *  bottom tip of the crystal at rest, in the pedestal's own frame. */
    private const val PEDESTAL_TOP_CLEARANCE: Double = 0.25

    /** Cosine of the max tilt-angle for issuing the weld. Compared
     *  against `bodyUp · targetUp` — yaw around the pedestal's up axis
     *  is deliberately ignored so players can spin the crystal to
     *  whichever facing before it welds. */
    private val TILT_TOL_COS: Double = Math.cos(Math.toRadians(3.0))

    /** VS Core weld compliance — 0.0 = perfectly rigid. */
    private const val WELD_COMPLIANCE: Double = 0.0

    /** Explicit "no force cap" for the joint. `null` on `maxForceTorque`
     *  in some Krunch builds is interpreted as a modest default limit;
     *  passing `Float.MAX_VALUE` for both maxForce and maxTorque asks
     *  the solver to treat the constraint as effectively unconditional
     *  so the weld holds under heavy angular acceleration (fast ship
     *  spins, ship-mounted pedestal flipped upside-down). */
    private val WELD_MAX_FORCE_TORQUE =
        org.valkyrienskies.core.internal.joints.VSJointMaxForceTorque(Float.MAX_VALUE, Float.MAX_VALUE)

    private val weldPending: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Server-tick entry point. Returns true if any per-cage record
     *  was mutated (so the caller can markDirty). */
    fun tick(level: ServerLevel): Boolean {
        var dirty = false
        for (data in GamylanCageRegistry.snapshot(level)) {
            if (OrbGrabState.targetFor(data.bodyId) != null) continue

            val body = level.shipObjectWorld.allBodies.getById(data.bodyId) ?: continue
            val livePos = Vector3d(body.kinematics.position)
            // The home scan reads blocks; a cage in an unloaded area loaded its chunk each tick.
            if (!columnLoaded(level, Math.floor(livePos.x).toInt(), Math.floor(livePos.z).toInt())) continue

            val found = findPedestalHome(level, livePos)
            val prevPos = data.homePedestalPos
            val prevShip = data.homePedestalShipId
            if (found == null) {
                if (prevPos != null) {
                    releaseJoint(level, data)
                    data.homePedestalPos = null
                    data.homePedestalShipId = null
                    data.homeRotTarget = Quaterniond()
                    dirty = true
                }
                continue
            }
            if (prevPos == null || prevPos != found.pedestalPos || prevShip != found.shipId) {
                releaseJoint(level, data)
                data.homePedestalPos = BlockPos(found.pedestalPos)
                data.homePedestalShipId = found.shipId
                data.fadeComplete = false
                dirty = true
            }
            // Refresh world hover + rotation target from the host's live
            // transform. On a stationary ship this is a no-op; on a
            // moving ship it's the mechanism the crystal uses to track.
            if (!refreshHomeTargets(level, data, found)) {
                // Host ship despawned mid-frame — treat like the "not
                // found" branch.
                releaseJoint(level, data)
                data.homePedestalPos = null
                data.homePedestalShipId = null
                data.homeRotTarget = Quaterniond()
                dirty = true
                continue
            }

            if (data.jointId != null) continue
            if (weldPending.contains(data.bodyId)) continue

            // Tilt-only alignment check: is the body's local +Y within
            // TILT_TOL_COS of the target frame's world-up? Yaw around
            // that axis is intentionally ignored — the phys-tick PD
            // damps only the swing component, so any player-driven yaw
            // survives here.
            val targetUp = Vector3d(0.0, 1.0, 0.0)
            data.homeRotTarget.transform(targetUp)
            val bodyUp = Vector3d(0.0, 1.0, 0.0)
            body.kinematics.rotation.transform(bodyUp)
            val tiltCos = bodyUp.x * targetUp.x + bodyUp.y * targetUp.y + bodyUp.z * targetUp.z
            if (tiltCos < TILT_TOL_COS) continue

            requestWeld(level, data, body, found)
            dirty = true
        }
        return dirty
    }

    /** A discovery result — pedestal block position in its OWNING frame
     *  (shipyard for a ship pedestal, world for a plain block) plus the
     *  host ship id if applicable. */
    private data class Home(
        val pedestalPos: BlockPos,
        val shipId: Long?,
        val shipyardHover: Vector3d,
    )

    /** Topmost unpowered pedestal within reach across every candidate
     *  frame. Iterates ships intersecting a thin vertical AABB around
     *  the crystal and the world column, ranks by distance-to-crystal
     *  in world coords, returns the nearest hit. */
    private fun findPedestalHome(level: ServerLevel, livePos: Vector3d): Home? {
        val scanAabb = AABBd(
            livePos.x - HOME_XZ_TOLERANCE,
            livePos.y - HOME_SCAN_MAX_DROP.toDouble() - 1.0,
            livePos.z - HOME_XZ_TOLERANCE,
            livePos.x + HOME_XZ_TOLERANCE,
            livePos.y,
            livePos.z + HOME_XZ_TOLERANCE,
        )

        var best: Home? = null
        var bestDropSq = Double.MAX_VALUE
        val pedestalBlock = EKBlocks.CRYSTAL_PEDESTAL.get()

        // World-frame column.
        pickHomeInFrame(level, livePos, livePos, null, pedestalBlock)?.let { candidate ->
            val dropSq = worldDistSq(livePos, candidate)
            if (dropSq < bestDropSq) { best = candidate; bestDropSq = dropSq }
        }

        // Every ship whose bbox overlaps the search AABB.
        val dimId = level.dimensionId
        for (ship in level.shipObjectWorld.allShips.getIntersecting(scanAabb)) {
            // `allShips` holds the ships of each dimension.
            if (ship.chunkClaimDimension != dimId) continue
            val shipyardPos = Vector3d(livePos)
            ship.transform.worldToShip.transformPosition(shipyardPos)
            pickHomeInFrame(level, livePos, shipyardPos, ship, pedestalBlock)?.let { candidate ->
                val dropSq = worldDistSq(livePos, candidate)
                if (dropSq < bestDropSq) { best = candidate; bestDropSq = dropSq }
            }
        }
        return best
    }

    /** Scan the owning-frame column both up and down for the closest
     *  pedestal. Bidirectional because on a flipped ship the pedestal's
     *  own-frame +Y is world -Y, so the crystal's world-above hover
     *  puts it BELOW the pedestal in the ship's frame. A single-
     *  direction scan misses those pedestals and the cage free-falls
     *  through them under gravity.
     *
     *  Ties within the same `dy` prefer the -Y direction so upright
     *  ships and world-frame pedestals keep the "cage drops onto its
     *  home from above" behaviour. */
    private fun pickHomeInFrame(
        level: ServerLevel,
        livePosWorld: Vector3d,
        crystalCentreInFrame: Vector3d,
        ship: Ship?,
        pedestalBlock: net.minecraft.world.level.block.Block,
    ): Home? {
        val px = Math.floor(crystalCentreInFrame.x).toInt()
        val pz = Math.floor(crystalCentreInFrame.z).toInt()
        val startY = Math.floor(crystalCentreInFrame.y).toInt()
        if (!columnLoaded(level, px, pz)) return null
        val probe = BlockPos.MutableBlockPos()
        for (dy in 1..HOME_SCAN_MAX_DROP) {
            // -1 first: the tie rule of the doc comment.
            for (step in 0..1) {
                val y = startY + (if (step == 0) -dy else dy)
                val state = level.getBlockState(probe.set(px, y, pz))
                if (!state.`is`(pedestalBlock)) continue
                val pos = probe.immutable()
                if (state.getValue(BlockStateProperties.POWERED)) return null
                val dx = crystalCentreInFrame.x - (pos.x + 0.5)
                val dz = crystalCentreInFrame.z - (pos.z + 0.5)
                if (Math.abs(dx) > HOME_XZ_TOLERANCE || Math.abs(dz) > HOME_XZ_TOLERANCE) continue
                return Home(
                    pedestalPos = pos,
                    shipId = ship?.id,
                    shipyardHover = Vector3d(),
                )
            }
        }
        return null
    }

    private fun columnLoaded(level: ServerLevel, x: Int, z: Int): Boolean =
        level.chunkSource.getChunkNow(x shr 4, z shr 4) != null

    /** Squared world distance from the crystal centre to the hover
     *  target the candidate would resolve to. Used to rank home
     *  candidates across frames (world + N ships). */
    private fun worldDistSq(livePos: Vector3d, home: Home): Double {
        // Candidates rank by "how far the crystal is from the hover
        // target after we adopt this pedestal". We haven't picked the
        // frame-specific hover yet at this point, so approximate with
        // the pedestal-top y in world coords — good enough for rank
        // between ship & world candidates.
        val py = home.pedestalPos.y + 1.0
        return (livePos.y - py) * (livePos.y - py)
    }

    /** Rewrite `data.hoverPos` and `data.homeRotTarget` from the host
     *  ship's live transform (or leave world-frame values as-is when no
     *  ship). Returns false if the host ship has despawned since the
     *  home was recorded — the caller drops the home in that case. */
    private fun refreshHomeTargets(level: ServerLevel, data: GamylanCageData, found: Home): Boolean {
        val shipyardHover = pedestalHoverShipyard(found.pedestalPos, data.shapeDims)
        val shipId = found.shipId
        if (shipId == null) {
            data.hoverPos = shipyardHover
            data.homeRotTarget = Quaterniond()
            return true
        }
        val ship = level.shipObjectWorld.allShips.getById(shipId) ?: return false
        val worldHover = Vector3d(shipyardHover)
        ship.transform.shipToWorld.transformPosition(worldHover)
        data.hoverPos = worldHover
        data.homeRotTarget = Quaterniond(ship.transform.shipToWorldRotation)
        return true
    }

    /** Hover pose in the pedestal's own frame — `(x+0.5, y+1+midH+tipLen+clearance, z+0.5)`. */
    private fun pedestalHoverShipyard(pedestal: BlockPos, shapeDims: Vector3d): Vector3d {
        val topFaceY = pedestal.y + 1.0
        val bottomOffset = shapeDims.y + shapeDims.z + PEDESTAL_TOP_CLEARANCE
        return Vector3d(pedestal.x + 0.5, topFaceY + bottomOffset, pedestal.z + 0.5)
    }

    /** Snap the body to the world-frame target pose (position + tilt)
     *  while preserving whatever yaw the crystal has around the
     *  target-frame up axis, then request a [VSFixedJoint] against the
     *  pedestal's owning frame. Joint id is written back from the async
     *  callback. */
    private fun requestWeld(
        level: ServerLevel,
        data: GamylanCageData,
        body: org.valkyrienskies.core.api.bodies.VsBody,
        home: Home,
    ) {
        val shipyardHover = pedestalHoverShipyard(home.pedestalPos, data.shapeDims)
        val target = data.homeRotTarget
        val worldHover = data.hoverPos

        // Extract the twist around target-frame Y from the body's
        // current rotation via swing-twist decomposition. The joint's
        // pose1 rotation is the twist, so the constraint fixes tilt to
        // identity in target frame but preserves the crystal's yaw.
        val curRot = body.kinematics.rotation
        val qLocal = Quaterniond(target).invert().mul(curRot).normalize()
        val twist = Quaterniond(0.0, qLocal.y, 0.0, qLocal.w)
        val twistLen = Math.sqrt(twist.y * twist.y + twist.w * twist.w)
        if (twistLen < 1e-6) {
            // Body's up axis is anti-parallel to target — the twist is
            // undefined. Rotation PD wouldn't have allowed this state
            // through the alignment gate, but guard anyway.
            twist.set(0.0, 0.0, 0.0, 1.0)
        } else {
            val inv = 1.0 / twistLen
            twist.y *= inv
            twist.w *= inv
        }
        val snappedWorldRot = Quaterniond(target).mul(twist)

        // Snap-align to the tilt-corrected pose. Relies on the
        // preemptive-standalone-body unregister fix in
        // GamylanCageManager.tryCreateDynamicBody — otherwise standalone
        // bodies never enter _loadedBodies and setTransform is silently
        // discarded.
        (body as? org.valkyrienskies.core.api.bodies.ServerBaseVsBody)?.setTransform(
            Vector3d(worldHover),
            snappedWorldRot,
            Vector3d(body.kinematics.scaling),
        )

        val joint: VSJoint = VSFixedJoint(
            shipId0 = data.bodyId,
            pose0 = VSJointPose(Vector3d(), Quaterniond()),
            shipId1 = home.shipId,
            pose1 = VSJointPose(shipyardHover, Quaterniond(twist)),
            maxForceTorque = WELD_MAX_FORCE_TORQUE,
            compliance = WELD_COMPLIANCE,
        )
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        weldPending.add(data.bodyId)
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                weldPending.remove(data.bodyId)
                val stillTracked = GamylanCageRegistry.get(level, data.bodyId) === data
                val stillOverPedestal = data.homePedestalPos == home.pedestalPos &&
                    data.homePedestalShipId == home.shipId
                val state = level.getBlockState(home.pedestalPos)
                val stillPedestal = state.`is`(EKBlocks.CRYSTAL_PEDESTAL.get()) &&
                    !state.getValue(BlockStateProperties.POWERED)
                if (!stillTracked || !stillOverPedestal || !stillPedestal) {
                    gtpa.removeJoint(id)
                    return@executeOrSchedule
                }
                data.jointId = id
                setPedestalLit(level, home.pedestalPos, true)
                GamylanCagePersistence.markDirty(level)
            }
        }
    }

    /** Flip the pedestal blockstate's LIT bit, which drives its
     *  `lightLevel { 5 if LIT else 0 }` on-block emission. Uses the
     *  block's own frame — no ship transform needed since `pos` is
     *  already in the frame the block lives in. */
    private fun setPedestalLit(level: ServerLevel, pos: BlockPos, lit: Boolean) {
        val state = level.getBlockState(pos)
        if (!state.`is`(EKBlocks.CRYSTAL_PEDESTAL.get())) return
        if (state.getValue(BlockStateProperties.LIT) == lit) return
        level.setBlock(pos, state.setValue(BlockStateProperties.LIT, lit), 3)
    }

    /** Callback from the pedestal block when it flips to powered.
     *  Releases the joint for any cage homed to this position (any
     *  frame — block-callback [pos] is in the block's own frame). */
    fun onPedestalPowered(level: ServerLevel, pos: BlockPos) {
        forCagesHomedAt(level, pos) { data ->
            releaseJoint(level, data)
            data.homePedestalPos = null
            data.homePedestalShipId = null
            data.homeRotTarget = Quaterniond()
        }
    }

    /** Callback from the pedestal block on break/replace. */
    fun onPedestalRemoved(level: ServerLevel, pos: BlockPos) {
        forCagesHomedAt(level, pos) { data ->
            releaseJoint(level, data)
            data.homePedestalPos = null
            data.homePedestalShipId = null
            data.homeRotTarget = Quaterniond()
        }
    }

    private inline fun forCagesHomedAt(level: ServerLevel, pos: BlockPos, action: (GamylanCageData) -> Unit) {
        var dirty = false
        for (data in GamylanCageRegistry.snapshot(level)) {
            if (data.homePedestalPos != pos) continue
            action(data)
            dirty = true
        }
        if (dirty) GamylanCagePersistence.markDirty(level)
    }

    private fun releaseJoint(level: ServerLevel, data: GamylanCageData) {
        val id = data.jointId ?: return
        data.jointId = null
        data.homePedestalPos?.let { setPedestalLit(level, it, false) }
        try {
            ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).removeJoint(id)
        } catch (t: Throwable) {
            LOG.warn("[ga'mylan] removeJoint({}) failed for body {}", id, data.bodyId, t)
        }
    }

    /** One occupant per pedestal: true when any cage is homed to [pos] or
     *  its hover target sits in the pedestal's column (covers the tick
     *  between a fresh spawn and the discovery pass adopting it). */
    fun isPedestalOccupied(level: ServerLevel, pos: BlockPos): Boolean {
        for (data in GamylanCageRegistry.snapshot(level)) {
            if (data.homePedestalPos == pos) return true
            val hover = data.hoverPos
            if (Math.abs(hover.x - (pos.x + 0.5)) <= HOME_XZ_TOLERANCE &&
                Math.abs(hover.z - (pos.z + 0.5)) <= HOME_XZ_TOLERANCE &&
                hover.y >= pos.y && hover.y <= pos.y + 1.0 + HOME_SCAN_MAX_DROP
            ) return true
        }
        return false
    }

    fun forgetBody(level: ServerLevel, data: GamylanCageData) {
        releaseJoint(level, data)
        weldPending.remove(data.bodyId)
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        weldPending.clear()
    }
}
