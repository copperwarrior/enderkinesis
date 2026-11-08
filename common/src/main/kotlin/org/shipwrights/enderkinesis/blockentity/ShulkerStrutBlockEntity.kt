package org.shipwrights.enderkinesis.blockentity

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3i
import org.shipwrights.enderkinesis.physics.ShulkerStrutDamper
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.ShipBlockMove
import org.shipwrights.enderkinesis.util.ShipMoveListener
import org.shipwrights.enderkinesis.util.ShipMoves
import org.shipwrights.enderkinesis.util.SavedJoints
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.joints.VSD6Joint
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointAndId
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.core.internal.joints.VSPrismaticJoint
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.abs
import kotlin.math.roundToInt
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.UnavailableInKrunch

/**
 * Drives the Shulker Strut. The lid is a **1-block VS2 ship** containing a single
 * [org.shipwrights.enderkinesis.block.ShulkerStrutTopBlock] (with FACING mirrored from
 * the base) — assembled on first server tick after place/load.
 *
 * Why a Ship + dedicated block rather than the previous raw `VsBody`: a real block carries
 * its own MC-pipeline rendering, collision, mass, and serialization, all of which would
 * otherwise have to be reproduced on the renderer / BE side. The half-cube collision split
 * that solves the closed-state visual still lives on the two block classes themselves
 * (opposite-FACING half on the base, FACING half on the lid block), so the closed pair
 * tiles into a full 1×1×1 collision cube at extension=0.
 *
 * The newly assembled ship is teleported to `basePos.centre` immediately so it starts at
 * extension=0 instead of bouncing in from `basePos.up().centre` while the joint settles.
 *
 * Joint lifecycle: a [VSPrismaticJoint] anchored at `basePos.centre` with slide axis
 * `FACING.normal`, [VSD6Joint.LinearLimitPair] clamped at the current extension target.
 * A signal change updates the live joint in place with `updateJoint`. A base or lid ship
 * change removes the joint and adds a new one.
 *
 * Client-side: BER renders both shulker shells (base at the block, lid at the ship's
 * `renderTransform`). See [org.shipwrights.enderkinesis.client.ShulkerStrutRenderer].
 */
@OptIn(GameTickOnly::class, VsBeta::class)
class ShulkerStrutBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.SHULKER_STRUT.get(), pos, state), ShipMoveListener {

    var topShipId: Long = NO_SHIP
        private set

    /** Shipyard-frame block coords of the [ShulkerStrutTopBlock] inside [topShipId]. Used
     *  as the joint anchor, the air-out target on strut removal, and (client-side) the
     *  endpoint anchor for the chain renders. Stable across blocks the player adds to the
     *  ship — the lid block itself doesn't move once assembled. */
    var lidShipyardPos: BlockPos? = null
        private set

    /** Dye colour of the strut (null = uncolored, vanilla shulker purple). Persisted +
     *  synced via the BE update tag so the BER picks the right shulker entity texture.
     *  Setting via [setDyeColor] propagates to the lid BE on the same tick. */
    var dyeColor: DyeColor? = null
        private set

    private var jointId: Int = NO_JOINT
    private var jointPending: Boolean = false
    /** Base ship the joint names; a VS2 split or weld can move the base to another ship. */
    private var boundBaseShipId: Long? = null
    private var boundScale: Double = 1.0
    private var lastTargetExtension: Float = -1f
    /** The saved [jointId] is checked against VS2 once, on the first tick with a loaded lid. */
    private var jointReclaimed = false

    var comparatorSignal: Int = 0
        private set
    private val scratchLidPos = Vector3d()

    override fun load(tag: CompoundTag) {
        super.load(tag)
        topShipId = if (tag.contains(NBT_TOP_SHIP)) tag.getLong(NBT_TOP_SHIP) else NO_SHIP
        lidShipyardPos = if (tag.contains(NBT_LID_POS_X))
            BlockPos(tag.getInt(NBT_LID_POS_X), tag.getInt(NBT_LID_POS_Y), tag.getInt(NBT_LID_POS_Z))
        else null
        dyeColor = if (tag.contains(NBT_DYE_COLOR)) DyeColor.byId(tag.getInt(NBT_DYE_COLOR)) else null
        lastTargetExtension =
            if (tag.contains(NBT_LAST_EXTENSION)) tag.getFloat(NBT_LAST_EXTENSION) else -1f
        jointId = if (tag.contains(NBT_JOINT_ID)) tag.getInt(NBT_JOINT_ID) else NO_JOINT
        boundBaseShipId = if (tag.contains(NBT_BOUND_BASE)) tag.getLong(NBT_BOUND_BASE) else null
        boundScale = if (tag.contains(NBT_BOUND_SCALE)) tag.getDouble(NBT_BOUND_SCALE) else 1.0
        jointReclaimed = false
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (topShipId != NO_SHIP) tag.putLong(NBT_TOP_SHIP, topShipId)
        lidShipyardPos?.let {
            tag.putInt(NBT_LID_POS_X, it.x); tag.putInt(NBT_LID_POS_Y, it.y); tag.putInt(NBT_LID_POS_Z, it.z)
        }
        dyeColor?.let { tag.putInt(NBT_DYE_COLOR, it.id) }
        if (lastTargetExtension >= 0f) tag.putFloat(NBT_LAST_EXTENSION, lastTargetExtension)
        if (jointId != NO_JOINT) {
            tag.putInt(NBT_JOINT_ID, jointId)
            boundBaseShipId?.let { tag.putLong(NBT_BOUND_BASE, it) }
            tag.putDouble(NBT_BOUND_SCALE, boundScale)
        }
    }

    /** Set the strut's dye colour and propagate to the lid BE so both halves' BERs render
     *  with the matching shulker texture. Called from
     *  [org.shipwrights.enderkinesis.block.ShulkerStrutBlock.use] on a dye right-click. */
    fun setDyeColor(color: DyeColor?) {
        if (this.dyeColor == color) return
        this.dyeColor = color
        setChanged()
        val srv = this.level as? ServerLevel ?: return
        // Push the base BE's update tag.
        srv.sendBlockUpdated(blockPos, blockState, blockState, 3)
        // Propagate to lid BE on the same tick so the lid BER agrees with the base BER on
        // colour from frame 1 of the change.
        val lidPos = lidShipyardPos ?: return
        val lidBe = srv.getBlockEntity(lidPos) as? ShulkerStrutTopBlockEntity ?: return
        lidBe.dyeColor = color
        lidBe.setChanged()
        srv.sendBlockUpdated(lidPos, lidBe.blockState, lidBe.blockState, 3)
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()
    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    fun serverTick(level: ServerLevel, pos: BlockPos, state: BlockState) {
        // After a worldgen assembly, a second block entity object can tick at this position.
        // Each object made its own lid ship.
        if (level.getBlockEntity(pos) !== this) return
        // Stage 1: ship lifecycle.
        //   - First tick after place/load: `topShipId == NO_SHIP` → create the lid ship.
        //   - Lid ship gone after creation (binding_roots merge edge case, manual delete,
        //     mod conflict): cascade-destroy the base too. The lid+base are a pair — losing
        //     one means the strut is broken and we drop the base item.
        lidShipyardPos?.let { lid ->
            // The lid BE does not tick, so the base keeps the lid's back-reference current.
            val top = level.getBlockEntity(lid) as? ShulkerStrutTopBlockEntity
            if (top != null && top.basePos != pos) {
                top.basePos = pos.immutable()
                top.setChanged()
            }
        }
        if (topShipId == NO_SHIP) {
            if (!tryAssembleTopShip(level, pos, state)) return
        } else if (level.shipObjectWorld.allShips.getById(topShipId) == null) {
            // Lid ship vanished externally. Prevent re-entry, then destroyBlock(self) to
            // run vanilla loot drops on the base. The cascade hop into our own onRemove →
            // releaseAndDestroyTop is harmless: topShipId is already cleared, so the lid
            // setBlock is skipped.
            topShipId = NO_SHIP
            lidShipyardPos = null
            jointId = NO_JOINT
            jointPending = false
            level.destroyBlock(pos, true)
            return
        }

        // Stage 2: wait for the ship to promote to LoadedServerShip — joints target loaded
        // ships, and the ship's inertia / COM isn't valid until then.
        val topShip = level.shipObjectWorld.loadedShips.getById(topShipId) ?: return
        val baseShip: Ship? = level.getShipManagingPos(pos)
        if (!jointReclaimed) {
            jointReclaimed = true
            jointId = SavedJoints.reclaim(level, jointId, baseShip?.id, topShipId)
        }
        val facing = state.getValue(BlockStateProperties.FACING)
        val scale = baseShip?.transform?.shipToWorldScaling?.x() ?: 1.0
        matchLidScale(level, topShip, scale)
        val extension = measureExtension(pos, topShip, baseShip, facing) ?: return
        updateComparatorSignal(level, pos, state, extension)

        val power = state.getValue(BlockStateProperties.POWER).coerceIn(0, 15)
        driveJoint(level, pos, baseShip, topShip, facing, power * MAX_EXTENSION_BLOCKS / 15f, scale)
    }

    /** Stage 3: bind or update the joint. The pending gate covers the addJoint → callback round trip. */
    private fun driveJoint(
        level: ServerLevel, pos: BlockPos, baseShip: Ship?, topShip: LoadedServerShip,
        facing: Direction, target: Float, scale: Double,
    ) {
        if (jointPending) return
        val baseShipId = baseShip?.id
        if (jointId != NO_JOINT && target == lastTargetExtension &&
            baseShipId == boundBaseShipId && scale == boundScale) return

        playMoveSound(level, pos, target)
        lastTargetExtension = target
        setChanged()
        val joint = buildJoint(pos, baseShip, topShip, facing, target, scale)
        boundScale = scale
        if (jointId != NO_JOINT && baseShipId == boundBaseShipId) {
            ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).updateJoint(VSJointAndId(jointId, joint.serialized()))
            return
        }
        releaseJoint(level)
        boundBaseShipId = baseShipId
        updateDamper(pos, baseShipId, topShip, facing)
        addJoint(level, joint)
    }

    private fun updateDamper(pos: BlockPos, baseShipId: Long?, topShip: LoadedServerShip, facing: Direction) {
        val lid = lidShipyardPos ?: return
        topShip.getOrPutAttachment(ShulkerStrutDamper::class.java) { ShulkerStrutDamper() }.update(
            baseShipId,
            Vector3d(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble()),
            Vector3d(lid.x + 0.5, lid.y + 0.5, lid.z + 0.5),
            Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5),
        )
    }

    /** A host ship can be rescaled after assembly; a lid at another scale breaks the half-cube split. */
    private fun matchLidScale(level: ServerLevel, topShip: LoadedServerShip, scale: Double) {
        val t = topShip.transform
        if (abs(t.shipToWorldScaling.x() - scale) < SCALE_EPSILON) return
        val data = vsCore.newShipTeleportData(
            newPos = t.positionInWorld,
            newRot = t.shipToWorldRotation,
            newDimension = level.dimensionId,
            newScale = scale,
            newPosInShip = t.positionInShip,
        )
        vsCore.teleportShip(level.shipObjectWorld as ServerShipWorld, topShip, data)
    }

    /** Lid offset along FACING in base-local blocks. */
    private fun measureExtension(pos: BlockPos, topShip: Ship, baseShip: Ship?, facing: Direction): Double? {
        val lid = lidShipyardPos ?: return null
        val p = scratchLidPos.set(lid.x + 0.5, lid.y + 0.5, lid.z + 0.5)
        topShip.shipToWorld.transformPosition(p)
        baseShip?.worldToShip?.transformPosition(p)
        return (p.x - pos.x - 0.5) * facing.stepX +
            (p.y - pos.y - 0.5) * facing.stepY +
            (p.z - pos.z - 0.5) * facing.stepZ
    }

    private fun updateComparatorSignal(level: ServerLevel, pos: BlockPos, state: BlockState, extension: Double) {
        val max = MAX_EXTENSION_BLOCKS.toDouble()
        val signal = (extension.coerceIn(0.0, max) * 15.0 / max).roundToInt()
        if (signal == comparatorSignal) return
        comparatorSignal = signal
        level.updateNeighbourForOutputSignal(pos, state.block)
    }

    /** No sound on the first bind after placement, when [lastTargetExtension] is still -1. */
    private fun playMoveSound(level: ServerLevel, pos: BlockPos, target: Float) {
        val last = lastTargetExtension
        if (last < 0f || target == last) return
        val event = when {
            last == 0f -> EKSounds.SHULKER_STRUT_OPEN
            target == 0f -> EKSounds.SHULKER_STRUT_CLOSE
            target > last -> EKSounds.SHULKER_STRUT_EXTEND
            else -> EKSounds.SHULKER_STRUT_RETRACT
        }
        level.playSound(
            null, pos, event.get(), SoundSource.BLOCKS, MOVE_SOUND_VOLUME, 0.95f + level.random.nextFloat() * 0.1f,
        )
    }

    /** Release the joint and air *only* the lid block — not the whole ship. If the player
     *  added more blocks to the lid ship while it was bound, those stay; the ship floats
     *  free once the joint is gone. If the lid block was the ship's only block, removing
     *  it leaves an empty ship that VS2 cleans up on its own. */
    override fun onShipBlocksMoved(level: ServerLevel, move: ShipBlockMove) {
        val lid = lidShipyardPos ?: return
        if (topShipId != move.fromShipId || !move.contains(lid)) return
        topShipId = move.toShipId
        lidShipyardPos = move.mapBlock(lid)
        releaseJoint(level)
        setChanged()
    }

    fun releaseAndDestroyTop() {
        val level = this.level as? ServerLevel ?: return
        releaseJoint(level)
        val lidPos = lidShipyardPos
        if (topShipId != NO_SHIP && lidPos != null) {
            // setBlock routes through MC's standard block-change path; VS2's mixins forward
            // it to the ship's chunks so this airs the lid block inside the ship.
            level.setBlock(lidPos, Blocks.AIR.defaultBlockState(), 3)
        }
        topShipId = NO_SHIP
        lidShipyardPos = null
    }


    /** Create a fresh 1-block VS2 ship for the lid via `createNewShipAtBlock` + `setBlock`
     *  into the ship's *own* chunk claim. This avoids the two failure modes of the previous
     *  `assembleToShip` path:
     *
     *  1. `basePos.above()` blocked by another block — irrelevant here because we never
     *     touch the world above the strut; the lid block goes straight into the new ship's
     *     reserved chunk claim.
     *  2. Strut placed on a host ship — `basePos.above()` lived inside the host's chunk
     *     claim, which made `getShipManagingPos(topPos)` return the host and previously
     *     refused the assembly. With direct ship creation we don't read host chunks at all.
     *
     *  Pattern mirrors VS2's own `TestHingeBlock` — create empty ship at world coords, fish
     *  out the chunk-claim centre as the shipyard position to place the block at, then
     *  teleport for exact positioning + host rotation. */
    private fun tryAssembleTopShip(level: ServerLevel, basePos: BlockPos, state: BlockState): Boolean {
        val facing = state.getValue(BlockStateProperties.FACING)
        val hostShip: Ship? = level.getShipManagingPos(basePos)
        val scale = hostShip?.transform?.shipToWorldScaling?.x() ?: 1.0

        // World-frame target for the lid at extension=0. For a ship-mounted strut, basePos
        // is in host shipyard coords; convert.
        val centreWorld = Vector3d(basePos.x + 0.5, basePos.y + 0.5, basePos.z + 0.5)
        hostShip?.shipToWorld?.transformPosition(centreWorld)
        val centreBlock = Vector3i(
            Math.floor(centreWorld.x).toInt(),
            Math.floor(centreWorld.y).toInt(),
            Math.floor(centreWorld.z).toInt(),
        )

        val ship = try {
            level.shipObjectWorld.createNewShipAtBlock(centreBlock, false, scale, level.dimensionId)
        } catch (t: Throwable) {
            LOG.warn("[EK shulker strut] failed to create lid ship for strut at {}", basePos, t)
            return false
        }

        // The ship's chunk-claim centre is where setBlock will land. After ship creation,
        // `transform.positionInShip` returns the chunk-claim centre in shipyard coords.
        val posInShip = ship.transform.positionInShip
        val lidPos = BlockPos(
            Math.floor(posInShip.x()).toInt(),
            Math.floor(posInShip.y()).toInt(),
            Math.floor(posInShip.z()).toInt(),
        )
        val topState = EKBlocks.SHULKER_STRUT_TOP.get().defaultBlockState()
            .setValue(BlockStateProperties.FACING, facing)
        level.setBlock(lidPos, topState, 3)

        // Back-reference the base from the lid BE so the lid block's onRemove can cascade
        // back here (drops the base item if the lid is destroyed first — by binding_roots
        // merge, explosion, etc.). Also seed the dye colour so the lid BER matches.
        (level.getBlockEntity(lidPos) as? ShulkerStrutTopBlockEntity)?.let { lidBe ->
            lidBe.basePos = basePos
            lidBe.dyeColor = dyeColor
            lidBe.setChanged()
        }

        lidShipyardPos = lidPos

        // Teleport for exact positioning. `newPosInShip` is the shipyard point that should
        // land at `newPos` (world). Host-rotation copied for ship-mounted struts so the lid
        // ship's local frame matches the host's — the joint relies on both bodies sharing
        // the same FACING-direction interpretation in their local frames.
        val newRot = hostShip?.transform?.shipToWorldRotation
        val data = vsCore.newShipTeleportData(
            newPos = centreWorld,
            newPosInShip = Vector3d(lidPos.x + 0.5, lidPos.y + 0.5, lidPos.z + 0.5),
            newRot = newRot ?: Quaterniond(),
            newDimension = level.dimensionId,
            newScale = scale,
        )
        vsCore.teleportShip(level.shipObjectWorld as ServerShipWorld, ship, data)

        topShipId = ship.id
        lastTargetExtension = -1f                                    // no sound on the first bind
        setChanged()
        level.sendBlockUpdated(basePos, blockState, blockState, 3)
        return true
    }

    // Konstant is the only target backend.
    @OptIn(UnavailableInKrunch::class)
    private fun buildJoint(
        basePos: BlockPos,
        baseShip: Ship?,
        topShip: LoadedServerShip,
        facing: Direction,
        targetExtension: Float,
        scale: Double,
    ): VSJoint {
        // pose0 anchor lives in baseShip's local frame (world frame if no host ship).
        // basePos is already a BlockPos in that frame, so basePos.centre is already in
        // pose0's frame — no further transform needed.
        val pose0Pos = Vector3d(basePos.x + 0.5, basePos.y + 0.5, basePos.z + 0.5)

        // FACING in the blockstate is interpreted in the strut's own local frame (= world
        // for a world-mounted strut, host ship local for a ship-mounted one). The
        // assembled lid ship inherits the host ship's world rotation, so its local frame
        // matches the host's — both poses use the same orientation, no worldToShipRotation
        // gymnastics required. (Hinging-style worldToShipRot transforms are only needed
        // when the joint frame is defined in WORLD coords; here it's defined in each
        // body's own local frame.)
        val facingVec = Vector3d(
            facing.normal.x.toDouble(),
            facing.normal.y.toDouble(),
            facing.normal.z.toDouble(),
        )
        val orientation = Quaterniond().rotationTo(LOCAL_X, facingVec)
        val pose0Rot = Quaterniond(orientation)

        // pose1 anchor: the *lid block's* centre in lid-ship shipyard frame — not the
        // COM. The ship's COM shifts when the player extends the ship with their own
        // blocks; the lid block stays put. Anchoring on the lid block keeps the strut
        // pinned to its original mount point regardless of what gets added.
        val pose1Pos = lidShipyardPos?.let {
            Vector3d(it.x + 0.5, it.y + 0.5, it.z + 0.5)
        } ?: Vector3d(topShip.inertiaData.centerOfMass)
        val pose1Rot = Quaterniond(orientation)

        // Pose positions are shipyard coords that VS2 scales; limits are raw world distances.
        val s = scale.toFloat()
        val limit = VSD6Joint.LinearLimitPair(
            lowerLimit = ((targetExtension - 0.01f) * s).coerceAtLeast(0f),
            upperLimit = (targetExtension + 0.01f) * s,
            stiffness = LIMIT_STIFFNESS,
            damping = LIMIT_DAMPING,
        )

        return VSPrismaticJoint(
            shipId0 = baseShip?.id,
            pose0 = VSJointPose(pose0Pos, pose0Rot),
            shipId1 = topShip.id,
            pose1 = VSJointPose(pose1Pos, pose1Rot),
            maxForceTorque = null,
            compliance = JOINT_COMPLIANCE,
            linearLimitPair = limit,
        )
    }

    private fun addJoint(level: ServerLevel, joint: VSJoint) {
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        jointPending = true
        val lidShipId = topShipId
        gtpa.addJoint(joint.serialized(), 0) { id: Int ->
            level.executeOrSchedule {
                // A lid ship change while the add is in flight leaves a joint that names the old ship.
                if (this.isRemoved || level.getBlockEntity(blockPos) !== this || topShipId != lidShipId) {
                    gtpa.removeJoint(id)
                    jointPending = false
                    return@executeOrSchedule
                }
                jointId = id
                jointPending = false
                setChanged()
            }
        }
    }

    // At chunk unload VS2 keeps the saved joint, and the next load reclaims it by id. A break
    // releases it in releaseAndDestroyTop; a ship move copies this block entity, so release here.
    override fun setRemoved() {
        super.setRemoved()
        val level = level as? ServerLevel ?: return
        if (ShipMoves.isMovingBlocks(level)) releaseJoint(level)
    }

    private fun releaseJoint(level: ServerLevel) {
        if (jointId == NO_JOINT) return
        level.shipObjectWorld.loadedShips.getById(topShipId)?.getAttachment(ShulkerStrutDamper::class.java)?.clear()
        ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).removeJoint(jointId)
        jointId = NO_JOINT
        jointPending = false
    }

    companion object {
        private val LOG = LogUtils.getLogger()
        private const val MOVE_SOUND_VOLUME = 0.6f
        private const val SCALE_EPSILON = 1e-6

        const val NO_SHIP: Long = Long.MIN_VALUE
        private const val NO_JOINT: Int = -1

        private const val NBT_TOP_SHIP = "TopShipId"
        private const val NBT_LAST_EXTENSION = "LastExtension"
        private const val NBT_JOINT_ID = "Joint"
        private const val NBT_BOUND_BASE = "BoundBase"
        private const val NBT_BOUND_SCALE = "BoundScale"
        private const val NBT_LID_POS_X = "LidPosX"
        private const val NBT_LID_POS_Y = "LidPosY"
        private const val NBT_LID_POS_Z = "LidPosZ"
        private const val NBT_DYE_COLOR = "Color"

        /** Template NBT names lid ships from the world that built it; those ids can match live ships. */
        fun removeShipState(tag: CompoundTag) {
            tag.remove(NBT_TOP_SHIP)
            tag.remove(NBT_LID_POS_X)
            tag.remove(NBT_LID_POS_Y)
            tag.remove(NBT_LID_POS_Z)
            tag.remove(NBT_LAST_EXTENSION)
        }

        /** Highest signal (POWER 15) extends the lid this far along FACING. */
        private const val MAX_EXTENSION_BLOCKS: Float = 5f


        /** PhysX prismatic-joint convention: local +X = slide axis. */
        private val LOCAL_X = Vector3d(1.0, 0.0, 0.0)

        /** Soft spring at the commanded slide position. Tuned for *smooth* extension /
         *  retraction at the 100 kg lid scale:
         *   - `ω = √(k/m) = √(1e5/100) ≈ 31.6 rad/s` undamped natural frequency.
         *   - Critical damping at `m = 100 kg` is `2√(k·m) ≈ 6325`; `LIMIT_DAMPING = 16000`
         *     puts the spring at ζ ≈ 2.5 — solidly overdamped, the lid drifts to target
         *     monotonically with strong viscous resistance. No overshoot, no oscillation.
         *  Heavier lid ships (player extended) will sag a few cm at full load — spring
         *  deflection scales with mass for fixed gravity, by design. */
        private const val LIMIT_STIFFNESS: Float = 1e5f
        private const val LIMIT_DAMPING: Float = 1.6e4f

        /** VS2 documents compliance as used by Krunch_Classic and ignored by PhysX; Konstant use is unconfirmed. */
        private const val JOINT_COMPLIANCE: Double = 1e-10
    }
}
