package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.item.MysticScrollItem
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.spell.AbyssalPockets
import org.shipwrights.enderkinesis.spell.SpellNetwork
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.spell.SpellVisuals

/** Per-entity state lasts until [forget]. The server sends it again when the entity comes back into view. */
object SpellNetworkClient {
    private const val MAX_VFX_LIFE = 20 * 60 * 60

    /** Two var ints of one byte or more, two ints, and the segment count. */
    private const val MERGED_BEAM_MIN_BYTES = 1 + 4 + 4 + 1 + 1
    private const val MERGED_SEGMENT_BYTES = 6 * Double.SIZE_BYTES

    private val absent = IntOpenHashSet()

    @Suppress("LongMethod") // Flat receiver wiring, one block for each packet.
    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.ABSENT) { buf, ctx ->
            val id = buf.readVarInt()
            val isAbsent = buf.readBoolean()
            ctx.queue { if (isAbsent) absent.add(id) else absent.remove(id) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.PHASE) { buf, ctx ->
            val positions = LongArray(buf.readCount(Long.SIZE_BYTES)) { buf.readLong() }
            ctx.queue { setPhased(positions, true) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.UNPHASE) { buf, ctx ->
            val positions = LongArray(buf.readCount(Long.SIZE_BYTES)) { buf.readLong() }
            ctx.queue { setPhased(positions, false) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.MERGED) { buf, ctx ->
            val beams = List(buf.readCount(MERGED_BEAM_MIN_BYTES)) {
                val elements = buf.readVarInt()
                val parentA = buf.readInt()
                val parentB = buf.readInt()
                val fused = buf.readVarInt()
                val points = List(buf.readCount(MERGED_SEGMENT_BYTES)) {
                    Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) to Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
                }
                ScrollBeamClient.MergedWire(points, elements, parentA, parentB, fused)
            }
            val cuts = HashMap<Int, Double>()
            repeat(buf.readCount(1 + Double.SIZE_BYTES)) { cuts[buf.readVarInt()] = buf.readDouble() }
            ctx.queue { ScrollBeamClient.onMerged(beams, cuts) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.VISUALS) { buf, ctx ->
            val id = buf.readVarInt()
            val bits = buf.readVarInt()
            val magnitudes = buf.readByteArray()
            ctx.queue { SpellVisuals.setClientBits(id, bits, magnitudes) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.FROZEN_POSE) { buf, ctx ->
            val id = buf.readVarInt()
            val rotation = Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat())
            ctx.queue { SpellVisuals.setClientPose(id, rotation) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.VFX) { buf, ctx ->
            val kind = buf.readByte().toInt()
            val a = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val b = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val size = buf.readFloat()
            val rgb = buf.readInt()
            // Self-casts and tethers live as long as their effect. One hour is much longer than all of them.
            val life = buf.readVarInt().coerceIn(1, MAX_VFX_LIFE)
            val seed = buf.readInt()
            ctx.queue { SpellVfxClient.add(kind, a, b, size, rgb, life, seed) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.BLOCK_SHIFTS) { buf, ctx ->
            val from = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val element = (buf.readVarInt() - 1).toFloat()
            val n = buf.readCount(Long.SIZE_BYTES + 1, SpellNetwork.MAX_SHIFTS_PER_PACKET)
            val positions = LongArray(n)
            val states = IntArray(n)
            for (i in 0 until n) {
                positions[i] = buf.readLong()
                states[i] = buf.readVarInt()
            }
            val kind = SpellVfx.Kind.BLOCK_SHIFT.ordinal
            // Block shifts do not use life or seed.
            ctx.queue { for (i in 0 until n) SpellVfxClient.add(kind, from, Vec3.atCenterOf(BlockPos.of(positions[i])), element, states[i], 1, 0) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.USED) { buf, ctx ->
            val id = buf.readVarInt()
            val use = MysticScrollItem.Companion.OtherUse.values().getOrNull(buf.readByte().toInt())
            val hand = if (buf.readBoolean()) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
            ctx.queue {
                val player = Minecraft.getInstance().level?.getEntity(id) as? Player ?: return@queue
                if (use != null) SpellArmPoses.started(player, SpellHand.Use.valueOf(use.name), hand)
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.GAS) { buf, ctx ->
            val chunk = buf.readLong()
            val cells = Int2FloatOpenHashMap()
            repeat(buf.readCount(2)) { cells.put(buf.readVarInt(), (buf.readByte().toInt() and 0xFF) / 255f) }
            ctx.queue { GasFieldClient.received(chunk, cells) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.GAS_CELLS) { buf, ctx ->
            val chunk = buf.readLong()
            val cells = Int2FloatOpenHashMap()
            repeat(buf.readCount(2)) { cells.put(buf.readVarInt(), (buf.readByte().toInt() and 0xFF) / 255f) }
            ctx.queue { GasFieldClient.changed(chunk, cells) }
        }
        GasFieldClient.init()
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.RELEASED) { buf, ctx ->
            val id = buf.readVarInt()
            ctx.queue { (Minecraft.getInstance().level?.getEntity(id) as? Player)?.let(SpellArmPoses::released) }
        }
        SpellVfxClient.init()
        ScrollBeamClient.init()
        SpellOverlays.init()
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.STUTTER) { buf, ctx ->
            val shipId = buf.readLong()
            val ticks = buf.readVarInt()
            ctx.queue { ClientShipCloakingState.stutter(shipId, ticks) }
        }
    }

    /** Refuses a count that cannot fit in the remaining bytes, before anything is allocated for it. */
    private fun FriendlyByteBuf.readCount(minBytes: Int, limit: Int = Int.MAX_VALUE): Int {
        val n = readVarInt()
        require(n in 0..minOf(limit, readableBytes() / minBytes)) { "Bad count $n in a spell packet" }
        return n
    }

    @JvmStatic
    fun visuals(id: Int): Int = SpellVisuals.clientBits(id)

    /** Call when the entity leaves this client. */
    @JvmStatic
    fun forget(id: Int) {
        absent.remove(id)
        SpellVisuals.setClientBits(id, 0)
        CrystalLayer.forget(id)
    }

    @JvmStatic
    fun isAbsent(id: Int): Boolean = absent.contains(id)

    /** The world keeps the blocks, but the mesher and collision skip them. Thus re-mesh the neighbours too. */
    private fun setPhased(positions: LongArray, isPhased: Boolean) {
        AbyssalPockets.setClientPhased(positions, isPhased)
        val renderer = Minecraft.getInstance().levelRenderer
        for (key in positions) {
            val pos = BlockPos.of(key)
            renderer.setBlocksDirty(pos.x - 1, pos.y - 1, pos.z - 1, pos.x + 1, pos.y + 1, pos.z + 1)
        }
    }

    /** Call on world change or disconnect. */
    fun reset() {
        absent.clear()
        SpellVisuals.resetClient()
        AbyssalPockets.clearClient()
        ScrollBeamClient.reset()
        SpellVfxClient.reset()
        SpellLayers.reset()
        SpellArmPoses.reset()
        FrozenPoses.reset()
        GasFieldClient.reset()
    }

}
