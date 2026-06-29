package org.shipwrights.enderkinesis.blockentity

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3i
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.joints.VSD6Joint
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.core.internal.joints.VSPrismaticJoint
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.core.api.world.ServerShipWorld

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
 * Signal change tears down and rebuilds the joint with new limits — same pattern as
 * [org.shipwrights.enderkinesis.item.HingingTomeOrbBehavior].
 *
 * Client-side: BER renders both shulker shells (base at the block, lid at the ship's
 * `renderTransform`). See [org.shipwrights.enderkinesis.client.ShulkerStrutRenderer].
 */
class ShulkerStrutBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.SHULKER_STRUT.get(), pos, state) {

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
    private var lastTargetExtension: Float = -1f

    override fun load(tag: CompoundTag) {
        super.load(tag)
        topShipId = if (tag.contains(NBT_TOP_SHIP)) tag.getLong(NBT_TOP_SHIP) else NO_SHIP
        lidShipyardPos = if (tag.contains(NBT_LID_POS_X))
            BlockPos(tag.getInt(NBT_LID_POS_X), tag.getInt(NBT_LID_POS_Y), tag.getInt(NBT_LID_POS_Z))
        else null
        dyeColor = if (tag.contains(NBT_DYE_COLOR)) DyeColor.byId(tag.getInt(NBT_DYE_COLOR)) else null
        lastTargetExtension =
            if (tag.contains(NBT_LAST_EXTENSION)) tag.getFloat(NBT_LAST_EXTENSION) else -1f
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (topShipId != NO_SHIP) tag.putLong(NBT_TOP_SHIP, topShipId)
        lidShipyardPos?.let {
            tag.putInt(NBT_LID_POS_X, it.x); tag.putInt(NBT_LID_POS_Y, it.y); tag.putInt(NBT_LID_POS_Z, it.z)
        }
        dyeColor?.let { tag.putInt(NBT_DYE_COLOR, it.id) }
        if (lastTargetExtension >= 0f) tag.putFloat(NBT_LAST_EXTENSION, lastTargetExtension)
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
        // Stage 1: ship lifecycle.
        //   - First tick after place/load: `topShipId == NO_SHIP` → create the lid ship.
        //   - Lid ship gone after creation (binding_roots merge edge case, manual delete,
        //     mod conflict): cascade-destroy the base too. The lid+base are a pair — losing
        //     one means the strut is broken and we drop the base item.
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

        val power = state.getValue(BlockStateProperties.POWER).coerceIn(0, 15)
        val target = power * MAX_EXTENSION_BLOCKS / 15f

        // Stage 3: bind / rebind joint on signal change. Pending gate covers the gtpa
        // addJoint() → callback round-trip.
        if (jointPending) return
        if (jointId != NO_JOINT && target == lastTargetExtension) return

        val baseShip: Ship? = level.getShipManagingPos(pos)
        val facing = state.getValue(BlockStateProperties.FACING)
        releaseJoint(level)
        bindJoint(level, pos, baseShip, topShip, facing, target)
    }

    /** Release the joint and air *only* the lid block — not the whole ship. If the player
     *  added more blocks to the lid ship while it was bound, those stay; the ship floats
     *  free once the joint is gone. If the lid block was the ship's only block, removing
     *  it leaves an empty ship that VS2 cleans up on its own. */
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
            level.shipObjectWorld.createNewShipAtBlock(centreBlock, false, 1.0, level.dimensionId)
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
        )
        vsCore.teleportShip(level.shipObjectWorld as ServerShipWorld, ship, data)

        topShipId = ship.id
        lastTargetExtension = -1f                                    // force first-tick bind
        setChanged()
        level.sendBlockUpdated(basePos, blockState, blockState, 3)
        return true
    }

    private fun bindJoint(
        level: ServerLevel,
        basePos: BlockPos,
        baseShip: Ship?,
        topShip: LoadedServerShip,
        facing: Direction,
        targetExtension: Float,
    ) {
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

        val limit = VSD6Joint.LinearLimitPair(
            lowerLimit = (targetExtension - 0.01f).coerceAtLeast(0f),
            upperLimit = targetExtension + 0.01f,
            stiffness = LIMIT_STIFFNESS,
            damping = LIMIT_DAMPING,
        )

        val joint: VSJoint = VSPrismaticJoint(
            shipId0 = baseShip?.id,
            pose0 = VSJointPose(pose0Pos, pose0Rot),
            shipId1 = topShip.id,
            pose1 = VSJointPose(pose1Pos, pose1Rot),
            maxForceTorque = null,
            compliance = JOINT_COMPLIANCE,
            linearLimitPair = limit,
        )

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        jointPending = true
        val capturedTarget = targetExtension
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                if (this.isRemoved || level.getBlockEntity(blockPos) !== this) {
                    gtpa.removeJoint(id)
                    return@executeOrSchedule
                }
                jointId = id
                jointPending = false
                lastTargetExtension = capturedTarget
                setChanged()
            }
        }
    }

    private fun releaseJoint(level: ServerLevel) {
        if (jointId == NO_JOINT) return
        ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).removeJoint(jointId)
        jointId = NO_JOINT
        jointPending = false
    }

    companion object {
        private val LOG = LogUtils.getLogger()

        const val NO_SHIP: Long = Long.MIN_VALUE
        private const val NO_JOINT: Int = -1

        private const val NBT_TOP_SHIP = "TopShipId"
        private const val NBT_LAST_EXTENSION = "LastExtension"
        private const val NBT_LID_POS_X = "LidPosX"
        private const val NBT_LID_POS_Y = "LidPosY"
        private const val NBT_LID_POS_Z = "LidPosZ"
        private const val NBT_DYE_COLOR = "Color"

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

        /** Near-zero compliance on the non-slide DOFs so the lid doesn't drift sideways. */
        private const val JOINT_COMPLIANCE: Double = 1e-10
    }
}
