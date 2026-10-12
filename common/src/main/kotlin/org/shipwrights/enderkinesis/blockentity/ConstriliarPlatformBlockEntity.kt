package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Back-reference from the shipyard platform block to its Constriliar, so the platform's
 * BER can render the body + IK legs in ship space (one interpolation stream with the
 * cargo). The id is a per-session entity network id — never persisted; the entity
 * re-stamps it every cargo scan.
 */
class ConstriliarPlatformBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.CONSTRILIAR_PLATFORM.get(), pos, state) {

    var ownerEntityId: Int = -1
        private set

    /** The animated source block — mining hardness (and tool rules) delegate to it.
     *  Persisted with the ship so wrecks keep their skin's feel without an owner. */
    var sourceState: BlockState? = null
        private set

    /** The entity id changes each session. The UUID lets a plate find an owner that has a new id. */
    var ownerUuid: java.util.UUID? = null
        private set

    fun setOwner(owner: net.minecraft.world.entity.Entity) {
        if (ownerUuid != owner.uuid) {
            ownerUuid = owner.uuid
            setChanged()
        }
        if (ownerEntityId == owner.id) return
        ownerEntityId = owner.id
        level?.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    fun setSource(state: BlockState) {
        if (sourceState == state) return
        sourceState = state
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        sourceState?.let { tag.put(NBT_SOURCE, net.minecraft.nbt.NbtUtils.writeBlockState(it)) }
        ownerUuid?.let { tag.putUUID(NBT_OWNER_UUID, it) }
    }

    override fun getUpdateTag(): CompoundTag = CompoundTag().also {
        it.putInt(NBT_OWNER, ownerEntityId)
        sourceState?.let { s -> it.put(NBT_SOURCE, net.minecraft.nbt.NbtUtils.writeBlockState(s)) }
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    override fun load(tag: CompoundTag) {
        super.load(tag)
        if (tag.contains(NBT_OWNER)) ownerEntityId = tag.getInt(NBT_OWNER)
        if (tag.hasUUID(NBT_OWNER_UUID)) ownerUuid = tag.getUUID(NBT_OWNER_UUID)
        if (tag.contains(NBT_SOURCE)) {
            // The server loads a block entity before it sets the level.
            sourceState = net.minecraft.nbt.NbtUtils.readBlockState(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                tag.getCompound(NBT_SOURCE),
            )
        }
    }

    companion object {
        private const val NBT_OWNER = "EkOwnerEntity"
        private const val NBT_OWNER_UUID = "EkOwnerUuid"
        private const val NBT_SOURCE = "EkSourceBlock"
    }
}
