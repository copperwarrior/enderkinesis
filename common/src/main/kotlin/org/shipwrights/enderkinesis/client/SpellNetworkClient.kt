package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import dev.architectury.event.events.client.ClientTickEvent
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.spell.SpellNetwork

/**
 * Client half of [SpellNetwork]: which entities are Absent (not drawn), which blocks an
 * Abyssal Pocket has phased (replaced with air locally, and restored when they return), and
 * Reveal's cloak stutters.
 */
object SpellNetworkClient {

    private val absent = IntOpenHashSet()
    /** Entity id → visible spell effects; see [org.shipwrights.enderkinesis.spell.SpellVisuals]. */
    private val visuals = Int2IntOpenHashMap()
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
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.MERGED) { buf, ctx ->
            val beams = List(buf.readVarInt()) {
                val rgb = buf.readInt()
                val points = List(buf.readVarInt()) {
                    Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) to Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
                }
                points to rgb
            }
            val cuts = HashMap<Int, Double>()
            repeat(buf.readVarInt()) { cuts[buf.readVarInt()] = buf.readDouble() }
            ctx.queue { ScrollBeamClient.onMerged(beams, cuts) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.VISUALS) { buf, ctx ->
            val id = buf.readVarInt()
            val bits = buf.readVarInt()
            ctx.queue { if (bits == 0) visuals.remove(id) else visuals.put(id, bits) }
        }
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel(::tickPockets))
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.STUTTER) { buf, ctx ->
            val shipId = buf.readLong()
            val ticks = buf.readVarInt()
            ctx.queue { ClientShipCloakingState.stutter(shipId, ticks) }
        }
    }

    /** The visible spell effects on the entity with [id], as [org.shipwrights.enderkinesis.spell.SpellVisuals] bits. */
    @JvmStatic
    fun visuals(id: Int): Int = visuals.get(id)

    /** A faint void haze in the empty space of each phased block. */
    private fun tickPockets(level: ClientLevel) {
        if (phased.isEmpty()) return
        val random = level.random
        for (key in phased.keys) {
            if (random.nextInt(POCKET_PARTICLE_CHANCE) != 0) continue
            val pos = BlockPos.of(key)
            level.addParticle(
                ParticleTypes.REVERSE_PORTAL,
                pos.x + random.nextDouble(), pos.y + random.nextDouble(), pos.z + random.nextDouble(),
                0.0, 0.0, 0.0,
            )
            if (random.nextInt(4) == 0) {
                level.addParticle(ParticleTypes.SQUID_INK, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 0.0, 0.0, 0.0)
            }
        }
    }

    private const val POCKET_PARTICLE_CHANCE = 3

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
        visuals.clear()
        phased.clear()
        ScrollBeamClient.reset()
    }

    /** Redraw, no neighbour shape updates: the server never changed the block. */
    private const val LOCAL_FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE
}
