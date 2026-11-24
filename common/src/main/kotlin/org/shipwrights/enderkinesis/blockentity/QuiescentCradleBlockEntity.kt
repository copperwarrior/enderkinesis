package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.primitives.AABBic
import org.shipwrights.enderkinesis.block.QuiescentCradleBlock
import org.shipwrights.enderkinesis.block.QuiescentCradleFrame
import org.shipwrights.enderkinesis.client.QuiescentCradleDrag
import org.shipwrights.enderkinesis.client.QuiescentCradleView
import org.shipwrights.enderkinesis.dimension.SselithBookshelfScan
import org.shipwrights.enderkinesis.physics.QuiescentCradleHold
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.util.INFINITE_RENDER_BOX
import org.shipwrights.enderkinesis.util.ServerTicks
import org.shipwrights.enderkinesis.util.pointVelocity
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.internal.joints.VSFixedJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.function.Predicate
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

@OptIn(GameTickOnly::class, VsBeta::class)
class QuiescentCradleBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.QUIESCENT_CRADLE.get(), pos, state) {

    var shipId: Long = NO_SHIP
        private set
    val holding: Boolean get() = shipId != NO_SHIP

    /** True while a fixed joint holds the ship, or one is on its way. Not saved: after a load the spring re-settles first. */
    var locked = false
        private set

    // Joint ids do not survive a reload, so none of these are saved.
    private var jointId = NO_JOINT
    private var jointPending = false
    private var jointCarrierId: Long? = null

    /** Bounding-sphere radius of the held ship; only meaningful while [holding]. */
    var sphereRadius = 0.0
        private set

    /** The sphere the big circles fit: the held ship, or with no ship the current reach, so idle circles show the reach. */
    val circleSphere: Double
        get() = if (holding) sphereRadius else QuiescentCradleFrame.sphereForPitch(reachFor(shelves))

    /** Pitch, yaw and roll of the gimbal in radians, each in [-π, π). */
    val angles = DoubleArray(QuiescentCradleFrame.RINGS)

    /** The cardinal turn nearest the held ship's orientation at the seize, in the cradle frame: gimbal angle 0. */
    private val seizeOrientation = Quaterniond()

    /** The held point, in shipyard coordinates. Fixed at the seize, so building on the ship does not shift it. */
    private val shipCentre = Vector3d()

    private var targetDirty = true

    /** False after a held ship outgrew the limits; the next power-on arms the cradle again. */
    private var armed = true

    /** Bookshelves near the cradle, 0 to [MAX_SHELVES]. Synced, because the idle circles show the reach. */
    var shelves = 0
        private set
    private var shelfScanTick = Long.MIN_VALUE
    private var shelvesDirty = true

    /** Client thread only. */
    var view: QuiescentCradleView? = null

    // Game thread only.
    private val scratch = Vector3d()
    private val scratchQ = Quaterniond()
    private val scratch2 = Vector3d()
    private val candidate = Vector3d()
    private val reachCentre = Vector3d()
    private var candidateRadius = 0.0

    // The circles reach far from the block; see INFINITE_RENDER_BOX.
    @Suppress("unused")
    fun getRenderBoundingBox(): AABB = INFINITE_RENDER_BOX

    fun tick(level: Level, state: BlockState) {
        if (level.isClientSide) {
            val shown = view ?: QuiescentCradleView().also {
                view = it
                QuiescentCradleDrag.track(this)
            }
            shown.tick(this)
        } else {
            serverTick(level as ServerLevel, state)
        }
    }

    private fun serverTick(level: ServerLevel, state: BlockState) {
        shelfCount(level)
        if (!state.getValue(QuiescentCradleBlock.POWERED)) {
            armed = true
            if (holding) release(level)
            return
        }
        if (holding) {
            val ship = level.shipObjectWorld.loadedShips.getById(shipId)
            if (ship != null) {
                hold(level, state, ship)
                return
            }
            // VS drops the joints of an unloaded ship; a remove of that id would crash Krunch.
            if (locked) {
                forgetJoint()
                sync()
            }
            // An unloaded ship comes back; a deleted one does not.
            if (level.shipObjectWorld.allShips.getById(shipId) != null) return
            shipId = NO_SHIP
            sync()
        }
        if (armed) seize(level, state)
    }

    /** Server thread. A neighbour change can be a bookshelf placed or broken next to the cradle. */
    fun markShelvesDirty() {
        shelvesDirty = true
    }

    /** The cached bookshelf count, rescanned at most once a second or after a neighbour change. */
    private fun shelfCount(level: ServerLevel): Int {
        val now = level.gameTime
        if (!shelvesDirty && now - shelfScanTick < SHELF_REFRESH_TICKS) return shelves
        shelvesDirty = false
        shelfScanTick = now
        val centre = scratch.set(worldPosition.x + HALF, worldPosition.y + HALF, worldPosition.z + HALF)
        level.getLoadedShipManagingPos(worldPosition)?.transform?.shipToWorld?.transformPosition(centre)
        val count = SselithBookshelfScan.collect(
            level, centre.x, centre.y, centre.z, MAX_SHELVES, null, SHELF_RADIUS, SHELF_RADIUS, IS_SHELF,
        )
        if (count != shelves) {
            shelves = count
            sync()
        }
        return shelves
    }

    private fun facing(state: BlockState): Direction = state.getValue(QuiescentCradleBlock.FACING)

    /** Seizes the largest ship that fits whole inside the largest pitch circle and is within the mass limit. */
    private fun seize(level: ServerLevel, state: BlockState) {
        val shelves = shelfCount(level)
        val reach = reachFor(shelves)
        val maxMass = massFor(shelves)
        val carrier = level.getLoadedShipManagingPos(worldPosition)
        // Every smaller seated circle lies inside the largest one, so that sphere is the whole volume of the circles.
        QuiescentCradleFrame.holdCentre(facing(state), reach, reachCentre)
            .add(worldPosition.x.toDouble(), worldPosition.y.toDouble(), worldPosition.z.toDouble())
        carrier?.transform?.shipToWorld?.transformPosition(reachCentre)
        val dimension = level.dimensionId
        var best: LoadedServerShip? = null
        var bestRadius = 0.0
        for (ship in level.shipObjectWorld.loadedShips) {
            if (rejectReason(ship, carrier?.id, dimension, maxMass, reach, bestRadius) != null) continue
            best = ship
            bestRadius = candidateRadius
        }
        val ship = best ?: return
        val aabb = ship.shipAABB ?: return
        shipId = ship.id
        QuiescentCradleFrame.boxCentre(aabb, shipCentre)
        seizeOrientation.set(ship.transform.shipToWorldRotation)
        carrier?.let { seizeOrientation.premul(scratchQ.set(it.transform.shipToWorldRotation).conjugate()) }
        // Angle 0 is the nearest cardinal turn in the cradle's frame (the carrier's axes on a ship), so the spring aligns the ship.
        QuiescentCradleFrame.nearestAxisAligned(seizeOrientation, seizeOrientation)
        sphereRadius = bestRadius
        angles.fill(0.0)
        locked = false
        targetDirty = true
        sync()
    }

    /** Null when [ship] fits and is larger than [minRadius]; then [candidateRadius] is its sphere radius. Uses [reachCentre]. */
    private fun rejectReason(
        ship: LoadedServerShip, carrierId: Long?, dimension: String, maxMass: Double, reach: Double, minRadius: Double,
    ): String? {
        if (ship.id == carrierId) return "carrier of the cradle"
        if (ship.chunkClaimDimension != dimension) return "other dimension"
        val mass = ship.inertiaData.mass
        if (ship.isStatic) return "static"
        if (mass <= 0.0) return "no mass"
        if (mass > maxMass) return "too heavy"
        val other = ship.getAttachment(QuiescentCradleHold::class.java)
        if (other != null && other.isFresh() && other.target?.owner != worldPosition.asLong()) return "held by another cradle"
        val aabb = ship.shipAABB ?: return "no ship AABB"
        candidateRadius = QuiescentCradleFrame.sphereAround(aabb, QuiescentCradleFrame.boxCentre(aabb, candidate))
        if (candidateRadius <= minRadius) return "smaller than the best so far"
        if (QuiescentCradleFrame.circleRadius(QuiescentCradleFrame.PITCH, candidateRadius) > reach) return "too big for the reach"
        if (insideFraction(ship, aabb, reach) < MIN_INSIDE) return "not inside the circles"
        return null
    }

    /** Part of the ship's box inside the circles' sphere, from a fixed grid of sample points in the box. */
    private fun insideFraction(ship: LoadedServerShip, aabb: AABBic, reach: Double): Double {
        val toWorld = ship.transform.shipToWorld
        val reachSq = reach * reach
        var inside = 0
        for (i in 0 until FIT_SAMPLES) {
            val x = aabb.minX() + (aabb.maxX() - aabb.minX()) * (i + HALF) / FIT_SAMPLES
            for (j in 0 until FIT_SAMPLES) {
                val y = aabb.minY() + (aabb.maxY() - aabb.minY()) * (j + HALF) / FIT_SAMPLES
                for (k in 0 until FIT_SAMPLES) {
                    val z = aabb.minZ() + (aabb.maxZ() - aabb.minZ()) * (k + HALF) / FIT_SAMPLES
                    toWorld.transformPosition(candidate.set(x, y, z))
                    if (candidate.distanceSquared(reachCentre) <= reachSq) inside++
                }
            }
        }
        return inside.toDouble() / (FIT_SAMPLES * FIT_SAMPLES * FIT_SAMPLES)
    }

    private fun hold(level: ServerLevel, state: BlockState, ship: LoadedServerShip) {
        val carrier = level.getLoadedShipManagingPos(worldPosition)
        if (carrier?.id == shipId) {
            // The cradle is now part of the ship it held.
            release(level)
            return
        }
        // The cradle moved to another carrier (an assembly), so the joint is between the wrong bodies.
        if (jointId != NO_JOINT && jointCarrierId != carrier?.id) unlock(level)
        updateRadius(level, ship)
        val shelves = shelfCount(level)
        if (ship.inertiaData.mass > massFor(shelves) ||
            QuiescentCradleFrame.circleRadius(QuiescentCradleFrame.PITCH, sphereRadius) > reachFor(shelves)
        ) {
            // Outgrown: let go as at power-off, and seize nothing more until the power comes on again.
            armed = false
            release(level)
            return
        }
        val hold = ship.getOrPutAttachment(QuiescentCradleHold::class.java) { QuiescentCradleHold() }
        val carrierId = carrier?.id ?: QuiescentCradleHold.NO_CARRIER
        val current = hold.target
        if (targetDirty || current == null || current.owner != worldPosition.asLong() || current.carrierId != carrierId) {
            hold.target = target(state, carrierId)
            targetDirty = false
        }
        hold.refreshTick = ServerTicks.now
        hold.jointed = jointId != NO_JOINT
        val target = hold.target ?: return
        if (!locked && isStill(ship, carrier, target)) lock(level, ship, carrier, target)
    }

    /** Joins the held ship to the cradle's body (its carrier, or the world) at their current relative pose. */
    private fun lock(level: ServerLevel, ship: LoadedServerShip, carrier: LoadedServerShip?, target: QuiescentCradleHold.Target) {
        val anchor = Vector3d(target.holdCentre)
        val world = Vector3d(anchor)
        carrier?.transform?.shipToWorld?.transformPosition(world)
        // pose1 is in raw shipyard coordinates, not relative to the centre of mass.
        val onShip = ship.transform.worldToShip.transformPosition(world, Vector3d())
        // R0·q0 = R1·q1 with q0 = identity, so the joint holds the present turn.
        val turn = Quaterniond(ship.transform.shipToWorldRotation).conjugate()
        carrier?.let { turn.mul(it.transform.shipToWorldRotation) }
        val joint = VSFixedJoint(
            shipId0 = carrier?.id,
            pose0 = VSJointPose(anchor, Quaterniond()),
            shipId1 = ship.id,
            pose1 = VSJointPose(onShip, turn),
            // Krunch tears silently and VS never hears of it; a later remove of that id kills the physics thread.
            maxForceTorque = null,
        )
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        val heldId = ship.id
        val carrierId = carrier?.id
        jointPending = true
        locked = true
        sync()
        gtpa.addJoint(joint, JOINT_DELAY) { id: Int ->
            level.executeOrSchedule {
                if (!jointPending || isRemoved || shipId != heldId) {
                    if (bodiesLoaded(level, heldId, carrierId)) gtpa.removeJoint(id)
                } else {
                    jointPending = false
                    jointId = id
                    jointCarrierId = carrierId
                }
            }
        }
    }

    private fun bodiesLoaded(level: ServerLevel, heldId: Long, carrierId: Long?): Boolean {
        val ships = level.shipObjectWorld.loadedShips
        return ships.getById(heldId) != null && (carrierId == null || ships.getById(carrierId) != null)
    }

    /** Removes the joint when both bodies are loaded; VS already dropped it otherwise. No sync. */
    private fun dropJoint(level: ServerLevel) {
        if (jointId != NO_JOINT && bodiesLoaded(level, shipId, jointCarrierId)) {
            ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).removeJoint(jointId)
        }
        level.shipObjectWorld.loadedShips.getById(shipId)?.getAttachment(QuiescentCradleHold::class.java)?.jointed = false
        forgetJoint()
    }

    private fun forgetJoint() {
        jointId = NO_JOINT
        jointPending = false
        jointCarrierId = null
        locked = false
    }

    private fun target(state: BlockState, carrierId: Long): QuiescentCradleHold.Target {
        val facing = facing(state)
        val pitchRadius = QuiescentCradleFrame.circleRadius(QuiescentCradleFrame.PITCH, sphereRadius)
        val centre = QuiescentCradleFrame.holdCentre(facing, pitchRadius, Vector3d())
            .add(worldPosition.x.toDouble(), worldPosition.y.toDouble(), worldPosition.z.toDouble())
        val orientation = QuiescentCradleFrame.gimbal(facing, angles, Quaterniond(), scratchQ).mul(seizeOrientation)
        return QuiescentCradleHold.Target(worldPosition.asLong(), carrierId, centre, orientation, Vector3d(shipCentre))
    }

    /** Grows or shrinks the circles when building on the held ship changes a rounded circle radius. */
    private fun updateRadius(level: ServerLevel, ship: LoadedServerShip) {
        val aabb = ship.shipAABB ?: return
        val sphere = QuiescentCradleFrame.sphereAround(aabb, shipCentre)
        if (!radiiDiffer(sphere, sphereRadius)) return
        // The hold centre moves with the pitch circle, so the spring must lift the ship again.
        unlock(level)
        sphereRadius = sphere
        targetDirty = true
        sync()
    }

    private fun radiiDiffer(a: Double, b: Double): Boolean {
        for (ring in 0 until QuiescentCradleFrame.RINGS) {
            if (QuiescentCradleFrame.circleRadius(ring, a) != QuiescentCradleFrame.circleRadius(ring, b)) return true
        }
        return false
    }

    /** Still relative to the cradle's body: small error and small velocity against the carrier, if any. */
    private fun isStill(ship: LoadedServerShip, carrier: LoadedServerShip?, target: QuiescentCradleHold.Target): Boolean {
        val centre = ship.transform.positionInWorld
        scratch.set(ship.velocity)
        if (carrier != null) {
            scratch.sub(pointVelocity(carrier.velocity, carrier.angularVelocity, carrier.transform.positionInWorld, centre, scratch2))
        }
        if (scratch.lengthSquared() > STILL_SPEED * STILL_SPEED) return false
        scratch.set(ship.angularVelocity)
        carrier?.let { scratch.sub(it.angularVelocity) }
        if (scratch.lengthSquared() > STILL_SPIN * STILL_SPIN) return false
        val held = ship.transform.shipToWorld.transformPosition(scratch.set(target.shipCentre))
        scratch2.set(target.holdCentre)
        carrier?.transform?.shipToWorld?.transformPosition(scratch2)
        if (held.distanceSquared(scratch2) > STILL_DISTANCE * STILL_DISTANCE) return false
        scratchQ.set(target.orientation)
        carrier?.let { scratchQ.premul(it.transform.shipToWorldRotation) }
        val dot = abs(scratchQ.dot(ship.transform.shipToWorldRotation))
        return 2.0 * acos(min(1.0, dot)) < STILL_ANGLE
    }

    /** Server thread. Turns gimbal [ring] by [delta] radians, from a validated control-orrery drag. */
    fun turn(level: ServerLevel, ring: Int, delta: Double) {
        require(ring in 0 until QuiescentCradleFrame.RINGS) { "No gimbal ring $ring" }
        require(delta.isFinite()) { "Turn must be finite, was $delta" }
        if (!holding) return
        unlock(level)
        angles[ring] = QuiescentCradleFrame.wrap(angles[ring] + delta)
        targetDirty = true
        sync()
    }

    /** Server thread. Sets the gimbal back to the turn at seize; the spring turns the ship back, then it locks again. */
    fun resetTurn(level: ServerLevel) {
        if (!holding) return
        unlock(level)
        angles.fill(0.0)
        targetDirty = true
        sync()
        // Feedback even when the gimbal was already at 0: a sound here, and a glow on every client through the block event.
        level.playSound(null, worldPosition, RESET_SOUND, SoundSource.BLOCKS, RESET_VOLUME, RESET_PITCH)
        level.blockEvent(worldPosition, blockState.block, RESET_EVENT, 0)
    }

    override fun triggerEvent(id: Int, param: Int): Boolean {
        if (id != RESET_EVENT) return super.triggerEvent(id, param)
        if (level?.isClientSide == true) view?.flashReset()
        // True on the server too, or the event is not sent to clients.
        return true
    }

    /** Removes the joint, so the spring holds the ship again. */
    private fun unlock(level: ServerLevel) {
        if (!locked) return
        dropJoint(level)
        sync()
    }

    /** Server thread. Lets the held ship go back to normal physics. */
    fun release(level: ServerLevel) {
        if (!holding) return
        unlock(level)
        level.shipObjectWorld.loadedShips.getById(shipId)
            ?.getAttachment(QuiescentCradleHold::class.java)
            ?.takeIf { it.target?.owner == worldPosition.asLong() }
            ?.target = null
        shipId = NO_SHIP
        sync()
    }

    override fun setRemoved() {
        // Unload or break: never leave a joint to a cradle that is gone.
        (level as? ServerLevel)?.let { if (locked) dropJoint(it) }
        super.setRemoved()
    }

    private fun sync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        shipId = if (tag.contains(SHIP_KEY)) tag.getLong(SHIP_KEY) else NO_SHIP
        locked = tag.getBoolean(LOCKED_KEY)
        armed = !tag.getBoolean(DISARMED_KEY)
        sphereRadius = tag.getDouble(SPHERE_KEY)
        shelves = tag.getInt(SHELVES_KEY).coerceIn(0, MAX_SHELVES)
        ANGLE_KEYS.forEachIndexed { i, key -> angles[i] = tag.getDouble(key) }
        seizeOrientation.set(
            tag.getDouble(SEIZE_KEYS[0]), tag.getDouble(SEIZE_KEYS[1]),
            tag.getDouble(SEIZE_KEYS[2]), tag.getDouble(SEIZE_KEYS[3]),
        )
        if (seizeOrientation.lengthSquared() < UNIT_EPSILON) seizeOrientation.identity() else seizeOrientation.normalize()
        shipCentre.set(tag.getDouble(CENTRE_KEYS[0]), tag.getDouble(CENTRE_KEYS[1]), tag.getDouble(CENTRE_KEYS[2]))
        targetDirty = true
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (holding) tag.putLong(SHIP_KEY, shipId)
        tag.putBoolean(DISARMED_KEY, !armed)
        tag.putDouble(SPHERE_KEY, sphereRadius)
        tag.putInt(SHELVES_KEY, shelves)
        ANGLE_KEYS.forEachIndexed { i, key -> tag.putDouble(key, angles[i]) }
        tag.putDouble(SEIZE_KEYS[0], seizeOrientation.x)
        tag.putDouble(SEIZE_KEYS[1], seizeOrientation.y)
        tag.putDouble(SEIZE_KEYS[2], seizeOrientation.z)
        tag.putDouble(SEIZE_KEYS[3], seizeOrientation.w)
        tag.putDouble(CENTRE_KEYS[0], shipCentre.x)
        tag.putDouble(CENTRE_KEYS[1], shipCentre.y)
        tag.putDouble(CENTRE_KEYS[2], shipCentre.z)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    // The lock is only for the client look; the server never loads it.
    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata().also { it.putBoolean(LOCKED_KEY, locked) }

    companion object {
        const val NO_SHIP = -1L

        /** The reset click's sound; one place to swap it. */
        val RESET_SOUND: SoundEvent = SoundEvents.ENCHANTMENT_TABLE_USE
        private const val RESET_VOLUME = 0.6f
        private const val RESET_PITCH = 1.2f
        private const val RESET_EVENT = 1
        private const val NO_JOINT = -1

        /** Physics ticks before the joint engages, as in VS2's TestHingeBlock. */
        private const val JOINT_DELAY = 4

        /** Strength: bookshelves within [SHELF_RADIUS] blocks, capped as at an enchanting table. */
        const val MAX_SHELVES = 15
        private const val SHELF_RADIUS = 8
        private const val SHELF_REFRESH_TICKS = 20L
        private val IS_SHELF = Predicate<BlockState> { it.`is`(BlockTags.ENCHANTMENT_POWER_PROVIDER) }

        /**
         * Blocks: the largest pitch-circle radius. With no shelves a Roaming End ship (sphere radius 19.9) fits with 25 % to
         * spare; 15 shelves give 4 times the reach (118).
         */
        const val BASE_REACH = 29.5
        const val REACH_PER_SHELF = 5.9

        /** Samples per box axis for the fit test; a ship counts when [MIN_INSIDE] of its box is inside the circles. */
        private const val FIT_SAMPLES = 6
        private const val MIN_INSIDE = 0.5

        /** Kilograms: the heaviest ship. With no shelves twice a Roaming End ship (302 t); 15 shelves give 64 times that. */
        const val BASE_MASS = 605_000.0
        const val MASS_PER_SHELF = 2_541_000.0

        fun reachFor(shelves: Int): Double = BASE_REACH + REACH_PER_SHELF * shelves

        fun massFor(shelves: Int): Double = BASE_MASS + MASS_PER_SHELF * shelves

        /** Lock thresholds: metres, radians, m/s and rad/s. */
        private const val STILL_DISTANCE = 0.02
        private const val STILL_ANGLE = 0.005
        private const val STILL_SPEED = 0.02
        private const val STILL_SPIN = 0.01

        private const val HALF = 0.5
        private const val UNIT_EPSILON = 1e-12

        private const val SHIP_KEY = "Ship"
        private const val LOCKED_KEY = "Locked"
        private const val DISARMED_KEY = "Disarmed"
        private const val SPHERE_KEY = "Sphere"

        private const val SHELVES_KEY = "Shelves"
        private val ANGLE_KEYS = arrayOf("Pitch", "Yaw", "Roll")
        private val SEIZE_KEYS = arrayOf("SeizeX", "SeizeY", "SeizeZ", "SeizeW")
        private val CENTRE_KEYS = arrayOf("CentreX", "CentreY", "CentreZ")
    }
}
