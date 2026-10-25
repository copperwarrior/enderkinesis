package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.spell.SpellNetwork

/**
 * Client half of [SpellNetwork]: which entities are Absent (not drawn), which blocks an
 * Abyssal Pocket has phased (replaced with air locally, and restored when they return), and
 * Reveal's cloak stutters.
 */
object SpellNetworkClient {

    private val absent = IntOpenHashSet()
    /** Phased block positions, with the state each one is restored to. */
    private val phased = Long2ObjectOpenHashMap<BlockState>()

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.ABSENT) { buf, ctx ->
            val id = buf.readVarInt()
            val isAbsent = buf.readBoolean()
            ctx.queue { if (isAbsent) absent.add(id) else absent.remove(id) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.PHASE) { buf, ctx ->
            val positions = LongArray(buf.readVarInt()) { buf.readLong() }
            ctx.queue { phase(positions) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.UNPHASE) { buf, ctx ->
            val positions = LongArray(buf.readVarInt()) { buf.readLong() }
            ctx.queue { unphase(positions) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.STUTTER) { buf, ctx ->
            val shipId = buf.readLong()
            val ticks = buf.readVarInt()
            ctx.queue { ClientShipCloakingState.stutter(shipId, ticks) }
        }
    }

    /** Whether the entity with [id] is Absent, so not drawn. */
    @JvmStatic
    fun isAbsent(id: Int): Boolean = absent.contains(id)

    private fun phase(positions: LongArray) {
        val level = Minecraft.getInstance().level ?: return
        for (key in positions) {
            if (phased.containsKey(key)) continue
            val pos = BlockPos.of(key)
            val state = level.getBlockState(pos)
            if (state.isAir) continue
            phased.put(key, state)
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), LOCAL_FLAGS)
        }
    }

    private fun unphase(positions: LongArray) {
        val level = Minecraft.getInstance().level ?: return
        for (key in positions) {
            val state = phased.remove(key) ?: continue
            val pos = BlockPos.of(key)
            if (level.getBlockState(pos).isAir) level.setBlock(pos, state, LOCAL_FLAGS)
        }
    }

    /** World change or disconnect. */
    fun reset() {
        absent.clear()
        phased.clear()
    }

    /** Redraw, no neighbour shape updates: the server never changed the block. */
    private const val LOCAL_FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE
}
