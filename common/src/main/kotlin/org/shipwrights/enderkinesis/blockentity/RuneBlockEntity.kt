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
 * A rune's stored spell, its owner (who placed it; may be absent), and the block state it was
 * written on, so a change of host can be told apart from an ordinary neighbour update (see
 * [hostChanged]). Moves with the block between ships, like any block entity.
 *
 * A freshly written rune isn't armed for its first [ARM_TICKS]: contact can't set it off
 * until it has appeared on clients, so a rune written under the writer's own feet doesn't go
 * off before it's even seen. Every other trigger (mining, its host changing, a fluid) works
 * from the start: those are deliberate acts on the rune, not someone standing where it's
 * being written.
 */
class RuneBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(EKBlockEntities.RUNE.get(), pos, state) {

    var spell: Spell = Spell(emptyList())
        private set
    var owner: UUID? = null
        private set
    /** The host block's state as last seen. Null for a rune with no recorded host. */
    var host: BlockState? = null
        private set

    /** For a rune written before the host's state was kept: just its block. */
    private var legacyHost: Block? = null

    /** Game time contact arms the rune from. Not saved: a rune loaded from disk is armed. */
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

    /** Whether contact sets the rune off yet; see [ARM_TICKS]. */
    fun isArmed(): Boolean = (level?.gameTime ?: Long.MAX_VALUE) >= armsAt

    /**
     * Whether the host becoming [now] counts as the host changing, which sets the rune off.
     * Either way the rune remembers [now], so a later change is measured from it.
     *
     * It counts when the host becomes another block, or the same block in another shape or
     * orientation (a Chaos swap of a log for one on another axis, a stair turned over). What
     * a block does by itself in the world doesn't count, nor does its ordinary working: see
     * [isNaturalChange].
     */
    fun hostChanged(now: BlockState): Boolean {
        val was = host
        if (was === now) return false
        host = now
        setChanged()
        if (was == null) {
            // Only the block is known: it changed if the block did, unnaturally.
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

        /** Ticks after writing before contact can set a rune off: the client's appear delay
         *  (`RuneVfx`), so the rune is there to see before it can go off. */
        const val ARM_TICKS = 8

        /** Properties a block changes by itself or in its ordinary working: growth, weathering,
         *  moisture, snow cover, leaf distance, redstone power, being lit or opened, fill
         *  levels. Matched by name, so modded blocks using the same names follow along. */
        private val NATURAL_PROPERTIES = setOf(
            "age", "stage", "moisture", "distance", "persistent", "snowy", "berries", "bloom",
            "hatch", "eggs", "pickles", "bites", "level", "honey_level", "charges",
            "powered", "power", "lit", "signal_fire", "open", "triggered", "enabled", "in_wall",
            "has_book", "occupied", "note", "sculk_sensor_phase", "shrieking", "can_summon",
            "attached", "disarmed", "unstable", "locked", "extended", "has_record", "cracked",
            "tilt", "thickness", "waterlogged",
        )

        /** Soil that turns into one another on its own (grass spreading and dying, farmland
         *  drying out) or with a hoe or shovel. */
        private val SOILS: Set<Block> by lazy {
            setOf(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.FARMLAND, Blocks.DIRT_PATH, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT)
        }

        /**
         * A host change the rune ignores: the same block with only [NATURAL_PROPERTIES]
         * changed; copper oxidising, being scraped or waxed (in either direction); one soil
         * becoming another ([SOILS]); a cauldron filling or emptying.
         */
        fun isNaturalChange(was: BlockState, now: BlockState): Boolean {
            val a = was.block
            val b = now.block
            if (a === b) return was.values.keys.all { it.name in NATURAL_PROPERTIES || was.getValue(it) == now.getValue(it) }
            if (WeatheringCopper.NEXT_BY_BLOCK.get()[a] === b || WeatheringCopper.PREVIOUS_BY_BLOCK.get()[a] === b) return true
            if (HoneycombItem.WAXABLES.get()[a] === b || HoneycombItem.WAX_OFF_BY_BLOCK.get()[a] === b) return true
            if (a in SOILS && b in SOILS) return true
            return a is AbstractCauldronBlock && b is AbstractCauldronBlock
        }

        /** The client's rune tick (its glyphs and sparks), set by the client. */
        var clientTick: ((Level, BlockPos, BlockState, RuneBlockEntity) -> Unit)? = null
    }
}
