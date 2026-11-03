package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.UlderBeamAnchorBlock
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.util.ShipBlockMove
import org.shipwrights.enderkinesis.util.ShipMoveListener
import org.valkyrienskies.mod.common.getShipManagingPos

/** Server side only. Each bound orb keeps the anchor list that the client reads. */
class UlderBeamAnchorBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.ULDER_BEAM_ANCHOR.get(), pos, state), ShipMoveListener {

    private val orbs: MutableSet<BlockPos> = LinkedHashSet()

    fun boundOrbs(): Set<BlockPos> = orbs

    /** Binds the orb at [orbPos] to this anchor, next to any orbs bound already. Null on success. */
    fun claim(level: ServerLevel, orbPos: BlockPos): Refusal? {
        val orb = level.getBlockEntity(orbPos) as? OrbOfLinkingBlockEntity ?: return Refusal.ORB_MISSING
        val orbCentre = orb.worldCenter() ?: return Refusal.ORB_MISSING
        if (!inRange(level, orbCentre, this)) return Refusal.TOO_FAR
        if (orbPos !in orbs && orbs.size >= MAX_ORBS) return Refusal.ANCHOR_FULL
        if (!orb.addAnchor(level, blockPos)) return Refusal.TOO_MANY
        if (orbs.add(orbPos)) setChanged()
        return null
    }

    /** Removes this anchor from the list of each bound orb. */
    fun release(level: ServerLevel) {
        for (orbPos in orbs) (level.getBlockEntity(orbPos) as? OrbOfLinkingBlockEntity)?.removeAnchor(blockPos)
        orbs.clear()
        setChanged()
    }

    /** The orb at [orbPos] is gone. The anchor stays for its other orbs. */
    fun removeOrb(orbPos: BlockPos) {
        if (orbs.remove(orbPos)) setChanged()
    }

    override fun onShipBlocksMoved(level: ServerLevel, move: ShipBlockMove) {
        if (orbs.none(move::contains)) return
        // Rebuilt, not changed in place: a moved position can equal one not yet visited.
        val moved = orbs.map { if (move.contains(it)) move.mapBlock(it) else it }
        orbs.clear()
        orbs.addAll(moved)
        setChanged()
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (orbs.isEmpty()) return
        val list = ListTag()
        for (orbPos in orbs) list.add(NbtUtils.writeBlockPos(orbPos))
        tag.put("Orbs", list)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        orbs.clear()
        val list = tag.getList("Orbs", Tag.TAG_COMPOUND.toInt())
        for (i in 0 until minOf(list.size, MAX_ORBS)) orbs.add(NbtUtils.readBlockPos(list.getCompound(i)))
    }

    enum class Refusal(val langSuffix: String, val limit: Int?) {
        ORB_MISSING("orb_missing", null),
        TOO_FAR("too_far", OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE.toInt()),
        TOO_MANY("too_many", OrbOfLinkingBlockEntity.MAX_ANCHORS),
        ANCHOR_FULL("anchor_full", MAX_ORBS),
    }

    companion object {
        /** Bounds the release loop when the anchor goes. */
        const val MAX_ORBS: Int = 16

        /** Block-local offset of the model's "anchor" cube centre toward the attached face. */
        const val ANCHOR_FACE_OFFSET: Double = 0.5 - 1.0 / 16.0

        /** True when [anchor] is within [OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE] of an orb centre, in world space. */
        fun inRange(level: Level, orbCentre: Vec3, anchor: UlderBeamAnchorBlockEntity): Boolean =
            orbCentre.distanceTo(anchorWorldPoint(level, anchor.blockPos, anchor.blockState)) <=
                OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE

        /** The anchor at [pos], or null when its chunk is not loaded. */
        fun loadedAt(level: ServerLevel, pos: BlockPos): UlderBeamAnchorBlockEntity? =
            level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4)?.getBlockEntity(pos) as? UlderBeamAnchorBlockEntity

        fun anchorWorldPoint(level: Level, pos: BlockPos, state: BlockState): Vec3 {
            val facing = if (state.hasProperty(UlderBeamAnchorBlock.FACING))
                state.getValue(UlderBeamAnchorBlock.FACING) else Direction.DOWN
            val v = Vector3d(
                pos.x + 0.5 + facing.stepX * ANCHOR_FACE_OFFSET,
                pos.y + 0.5 + facing.stepY * ANCHOR_FACE_OFFSET,
                pos.z + 0.5 + facing.stepZ * ANCHOR_FACE_OFFSET,
            )
            level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(v)
            return Vec3(v.x, v.y, v.z)
        }
    }
}
