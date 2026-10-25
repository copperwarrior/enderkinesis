package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.block.RuneBlock
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.spell.RuneCollisionRouter
import org.shipwrights.enderkinesis.spell.Spell
import org.valkyrienskies.mod.common.dimensionId
import java.util.UUID

/**
 * A rune's stored spell, its owner (who placed it; may be absent), and the block it was
 * written on, so a change of host can be told apart from an ordinary neighbour update.
 * Moves with the block between ships, like any block entity.
 */
class RuneBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(EKBlockEntities.RUNE.get(), pos, state) {

    var spell: Spell = Spell(emptyList())
        private set
    var owner: UUID? = null
        private set
    var host: Block? = null
        private set

    private var registeredDimension: String? = null

    fun write(spell: Spell, owner: UUID?, host: Block) {
        this.spell = spell
        this.owner = owner
        this.host = host
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            registeredDimension = level.dimensionId
            RuneCollisionRouter.add(level.dimensionId, hostPos(), RuneBlock.facing(blockState))
        }
    }

    override fun setRemoved() {
        super.setRemoved()
        registeredDimension?.let { RuneCollisionRouter.remove(it, hostPos(), RuneBlock.facing(blockState)) }
        registeredDimension = null
    }

    private fun hostPos(): BlockPos = blockPos.relative(RuneBlock.facing(blockState).opposite)

    override fun load(tag: CompoundTag) {
        super.load(tag)
        spell = Spell.load(tag, Spell.NBT_GLYPHS) ?: Spell(emptyList())
        owner = if (tag.hasUUID(NBT_OWNER)) tag.getUUID(NBT_OWNER) else null
        host = ResourceLocation.tryParse(tag.getString(NBT_HOST))?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
        // The rune is tinted from its spell, so its chunk section redraws when the spell arrives.
        level?.takeIf { it.isClientSide }?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_IMMEDIATE)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.put(Spell.NBT_GLYPHS, spell.save())
        owner?.let { tag.putUUID(NBT_OWNER, it) }
        host?.let { tag.putString(NBT_HOST, BuiltInRegistries.BLOCK.getKey(it).toString()) }
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    private companion object {
        const val NBT_OWNER = "Owner"
        const val NBT_HOST = "Host"
    }
}
