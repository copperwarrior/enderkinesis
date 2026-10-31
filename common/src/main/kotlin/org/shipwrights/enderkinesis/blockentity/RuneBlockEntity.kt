package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.WeatheringCopper
import net.minecraft.world.item.HoneycombItem
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.block.RuneBlock
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.spell.RuneCollisionRouter
import org.shipwrights.enderkinesis.spell.Spell
import org.valkyrienskies.mod.common.dimensionId
import java.util.UUID

/**
 * Contact cannot set a fresh rune off for [ARM_TICKS], so a rune written under its writer does
 * not go off before it shows. Other triggers are deliberate acts, so they work at once.
 */
class RuneBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(EKBlockEntities.RUNE.get(), pos, state) {

    var spell: Spell = Spell(emptyList())
        private set
    var owner: UUID? = null
        private set
    /** Null when no host state is known. */
    var host: BlockState? = null
        private set

    /** Only for a rune saved before host states were kept. */
    private var legacyHost: Block? = null

    /** Not saved: a rune loaded from disk is armed. */
    private var armsAt = 0L

    private var registeredDimension: String? = null

    fun write(spell: Spell, owner: UUID?, host: BlockState) {
        this.spell = spell
        this.owner = owner
        this.host = host
        armsAt = (level?.gameTime ?: 0L) + ARM_TICKS
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    fun isArmed(): Boolean = (level?.gameTime ?: Long.MAX_VALUE) >= armsAt

    /** Remembers [now] either way, so a later change counts from it. A new shape or orientation
     *  counts as a change; an [isNaturalChange] does not. */
    fun hostChanged(now: BlockState): Boolean {
        val was = host
        if (was === now) return false
        host = now
        setChanged()
        if (was == null) {
            // Only the block is known.
            val block = legacyHost ?: return false
            legacyHost = null
            return block !== now.block && !isNaturalChange(block.defaultBlockState(), now)
        }
        return !isNaturalChange(was, now)
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
        if (tag.contains(NBT_HOST_STATE, Tag.TAG_COMPOUND.toInt())) {
            host = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound(NBT_HOST_STATE))
            legacyHost = null
        } else {
            host = null
            legacyHost = ResourceLocation.tryParse(tag.getString(NBT_HOST))?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
        }
        // The rune is tinted from its spell, so its chunk section redraws when the spell arrives.
        level?.takeIf { it.isClientSide }?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_IMMEDIATE)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.put(Spell.NBT_GLYPHS, spell.save())
        owner?.let { tag.putUUID(NBT_OWNER, it) }
        host?.let { tag.put(NBT_HOST_STATE, NbtUtils.writeBlockState(it)) }
        if (host == null) legacyHost?.let { tag.putString(NBT_HOST, BuiltInRegistries.BLOCK.getKey(it).toString()) }
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        private const val NBT_OWNER = "Owner"
        private const val NBT_HOST = "Host"
        private const val NBT_HOST_STATE = "HostState"

        /** Must agree with the client appear delay in `RuneVfx`. */
        const val ARM_TICKS = 8

        /** Matched by name, so modded blocks with the same property names follow. */
        private val NATURAL_PROPERTIES = setOf(
            "age", "stage", "moisture", "distance", "persistent", "snowy", "berries", "bloom",
            "hatch", "eggs", "pickles", "bites", "level", "honey_level", "charges",
            "powered", "power", "lit", "signal_fire", "open", "triggered", "enabled", "in_wall",
            "has_book", "occupied", "note", "sculk_sensor_phase", "shrieking", "can_summon",
            "attached", "disarmed", "unstable", "locked", "extended", "has_record", "cracked",
            "tilt", "thickness", "waterlogged",
        )

        private val SOILS: Set<Block> by lazy {
            setOf(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.FARMLAND, Blocks.DIRT_PATH, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT)
        }

        fun isNaturalChange(was: BlockState, now: BlockState): Boolean {
            val a = was.block
            val b = now.block
            if (a === b) return was.values.keys.all { it.name in NATURAL_PROPERTIES || was.getValue(it) == now.getValue(it) }
            if (WeatheringCopper.NEXT_BY_BLOCK.get()[a] === b || WeatheringCopper.PREVIOUS_BY_BLOCK.get()[a] === b) return true
            if (HoneycombItem.WAXABLES.get()[a] === b || HoneycombItem.WAX_OFF_BY_BLOCK.get()[a] === b) return true
            if (a in SOILS && b in SOILS) return true
            return a is AbstractCauldronBlock && b is AbstractCauldronBlock
        }

        /** Set by the client, because the common module cannot see client classes. */
        var clientTick: ((Level, BlockPos, BlockState, RuneBlockEntity) -> Unit)? = null
    }
}
