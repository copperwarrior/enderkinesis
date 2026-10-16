package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.util.ShipMoves

/**
 * Rendering hook for [org.shipwrights.enderkinesis.block.ShulkerStrutTopBlock] *and*
 * carrier of the back-reference to the strut's base position. The base BE tracks the lid;
 * the lid BE tracks the base, so destruction can cascade in either direction:
 *
 *  - base broken → base BE airs the lid block via `releaseAndDestroyTop`
 *  - lid broken → lid block calls `destroyBlock(basePos, drop=true)` so the base block
 *    drops its item naturally
 *
 * [basePos] lives in whichever frame `BlockPos` is in for the base (world for a
 * world-mounted strut, host shipyard for a ship-mounted one) — `level.destroyBlock`
 * routes both through VS2's chunk mixins identically.
 */
class ShulkerStrutTopBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.SHULKER_STRUT_TOP.get(), pos, state) {

    var basePos: BlockPos? = null
    var dyeColor: DyeColor? = null

    private var checkTimer = 0
    private var orphanStrikes = 0

    /** The lid has no interaction shape, so a lid without its base can only remove itself. */
    fun serverTick(level: ServerLevel, pos: BlockPos) {
        if (++checkTimer < ORPHAN_CHECK_INTERVAL) return
        checkTimer = 0
        val base = basePos ?: return
        if (!level.isLoaded(base) || ShipMoves.isMovingBlocks(level)) return
        val owner = level.getBlockEntity(base) as? ShulkerStrutBlockEntity
        if (owner?.lidShipyardPos == pos) {
            orphanStrikes = 0
            return
        }
        // After a block move the base re-points basePos on its next tick, so one miss is not proof.
        if (++orphanStrikes < ORPHAN_STRIKES_TO_REMOVE) return
        level.removeBlock(pos, false)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        basePos = if (tag.contains(NBT_BASE_X))
            BlockPos(tag.getInt(NBT_BASE_X), tag.getInt(NBT_BASE_Y), tag.getInt(NBT_BASE_Z))
        else null
        dyeColor = if (tag.contains(NBT_DYE_COLOR)) DyeColor.byId(tag.getInt(NBT_DYE_COLOR)) else null
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        basePos?.let {
            tag.putInt(NBT_BASE_X, it.x); tag.putInt(NBT_BASE_Y, it.y); tag.putInt(NBT_BASE_Z, it.z)
        }
        dyeColor?.let { tag.putInt(NBT_DYE_COLOR, it.id) }
    }

    /** Sync `dyeColor` to clients so the lid BER picks the matching shulker texture. */
    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()
    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    private companion object {
        private const val NBT_BASE_X = "BaseX"
        private const val NBT_BASE_Y = "BaseY"
        private const val NBT_BASE_Z = "BaseZ"
        private const val NBT_DYE_COLOR = "Color"
        private const val ORPHAN_CHECK_INTERVAL = 20
        private const val ORPHAN_STRIKES_TO_REMOVE = 2
    }
}
