package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.item.UlderScrollItem
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.spell.AbyssalPockets
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.GasField
import org.shipwrights.enderkinesis.spell.GasKind
import org.shipwrights.enderkinesis.spell.SpellNetwork
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.spell.SpellVisuals

/** Per-entity state lasts until [forget]. The server sends it again when the entity comes back into view. */
object SpellNetworkClient {
    private const val FLOW_ENTRY_MIN_BYTES = 4
    private const val MAX_VFX_LIFE = 20 * 60 * 60

    /** Two var ints of one byte or more, two ints, and the segment count. */
    private const val MERGED_BEAM_MIN_BYTES = 1 + 4 + 4 + 1 + 1
    private const val MERGED_SEGMENT_BYTES = 6 * Float.SIZE_BYTES

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
            val ox = buf.readDouble()
            val oy = buf.readDouble()
            val oz = buf.readDouble()
            val beams = List(buf.readCount(MERGED_BEAM_MIN_BYTES)) {
                val elements = buf.readVarInt()
                val parentA = buf.readInt()
                val parentB = buf.readInt()
                val fused = buf.readVarInt()
                val points = List(buf.readCount(MERGED_SEGMENT_BYTES)) {
                    Vec3(ox + buf.readFloat(), oy + buf.readFloat(), oz + buf.readFloat()) to
                        Vec3(ox + buf.readFloat(), oy + buf.readFloat(), oz + buf.readFloat())
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
            val count = buf.readVarInt()
            val ids = IntArray(count)
            val rotations = Array(count) {
                ids[it] = buf.readVarInt()
                Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat())
            }
            ctx.queue { for (i in 0 until count) SpellVisuals.setClientPose(ids[i], rotations[i]) }
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
            val from = if (buf.readBoolean()) Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) else null
            val element = Element.entries.getOrNull(buf.readVarInt() - 1)
            val n = buf.readCount(Long.SIZE_BYTES + 1, SpellNetwork.MAX_SHIFTS_PER_PACKET)
            val positions = LongArray(n)
            val states = IntArray(n)
            for (i in 0 until n) {
                positions[i] = buf.readLong()
                states[i] = buf.readVarInt()
            }
            ctx.queue {
                val level = Minecraft.getInstance().level ?: return@queue
                for (i in 0 until n) BlockShifts.add(level, from, Vec3.atCenterOf(BlockPos.of(positions[i])), element, states[i])
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.USED) { buf, ctx ->
            val id = buf.readVarInt()
            val use = UlderScrollItem.Companion.OtherUse.values().getOrNull(buf.readByte().toInt())
            val hand = if (buf.readBoolean()) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
            ctx.queue {
                val player = Minecraft.getInstance().level?.getEntity(id) as? Player ?: return@queue
                if (use != null) SpellArmPoses.started(player, SpellHand.Use.valueOf(use.name), hand)
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.GAS) { buf, ctx ->
            val kind = GasKind.entries.getOrNull(buf.readUnsignedByte().toInt()) ?: return@registerReceiver
            val chunk = buf.readLong()
            val cells = Int2FloatOpenHashMap()
            repeat(buf.readCount(2)) { cells.put(buf.readVarInt(), GasField.unpackAmount(buf.readByte().toInt())) }
            val flows = readFlows(buf)
            ctx.queue { GasFieldClient.received(kind, chunk, cells, flows) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.GAS_CELLS) { buf, ctx ->
            val kind = GasKind.entries.getOrNull(buf.readUnsignedByte().toInt()) ?: return@registerReceiver
            // Each chunk entry is a long and two counts at least.
            val n = buf.readCount(Long.SIZE_BYTES + 2)
            val chunks = LongArray(n)
            val cellMaps = ArrayList<Int2FloatOpenHashMap>(n)
            val flowMaps = ArrayList<Int2IntOpenHashMap>(n)
            for (c in 0 until n) {
                chunks[c] = buf.readLong()
                val cells = Int2FloatOpenHashMap()
                repeat(buf.readCount(2)) { cells.put(buf.readVarInt(), GasField.unpackAmount(buf.readByte().toInt())) }
                cellMaps += cells
                flowMaps += readFlows(buf)
            }
            ctx.queue { for (c in 0 until n) GasFieldClient.changed(kind, chunks[c], cellMaps[c], flowMaps[c]) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.GAS_JET) { buf, ctx ->
            val id = buf.readLong()
            val kind = GasKind.entries.getOrNull(buf.readUnsignedByte().toInt())
            val hand = buf.readBoolean()
            val stop = buf.readBoolean()
            val frame = buf.readLong()
            val px = buf.readDouble(); val py = buf.readDouble(); val pz = buf.readDouble()
            val dx = buf.readFloat().toDouble(); val dy = buf.readFloat().toDouble(); val dz = buf.readFloat().toDouble()
            val strength = buf.readFloat(); val parcelMass = buf.readFloat(); val start = buf.readLong(); val seed = buf.readLong()
            if (kind != null) ctx.queue { GasJetClient.received(id, kind, hand, stop, frame, px, py, pz, dx, dy, dz, strength, parcelMass, start, seed) }
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
    /** Packed int8 x3 flows, as SpellNetwork.writeFlows sends them. */
    private fun readFlows(buf: FriendlyByteBuf): Int2IntOpenHashMap {
        val flows = Int2IntOpenHashMap()
        repeat(buf.readCount(FLOW_ENTRY_MIN_BYTES)) {
            val i = buf.readVarInt()
            val x = buf.readByte().toInt() and 0xFF
            val y = buf.readByte().toInt() and 0xFF
            val z = buf.readByte().toInt() and 0xFF
            flows.put(i, x or (y shl 8) or (z shl 16))
        }
        return flows
    }

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
