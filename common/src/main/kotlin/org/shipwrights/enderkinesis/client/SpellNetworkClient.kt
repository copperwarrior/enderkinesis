package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.item.MysticScrollItem
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.spell.AbyssalPockets
import org.shipwrights.enderkinesis.spell.SpellNetwork
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.spell.SpellVisuals

/**
 * Client half of [SpellNetwork]: which entities are Absent (not drawn), which blocks an
 * Abyssal Pocket has phased (see [AbyssalPockets]), merged beams, visible effects, spell
 * visuals, Reveal's cloak stutters, and other players' scroll uses (their arms' motions).
 *
 * Per-entity state lasts while the entity is here: it's forgotten when the entity leaves the
 * client ([forget]), and the server sends it again if the entity comes back into view.
 */
object SpellNetworkClient {

    private val absent = IntOpenHashSet()

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.ABSENT) { buf, ctx ->
            val id = buf.readVarInt()
            val isAbsent = buf.readBoolean()
            ctx.queue { if (isAbsent) absent.add(id) else absent.remove(id) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.PHASE) { buf, ctx ->
            val positions = LongArray(buf.readVarInt()) { buf.readLong() }
            ctx.queue { setPhased(positions, true) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.UNPHASE) { buf, ctx ->
            val positions = LongArray(buf.readVarInt()) { buf.readLong() }
            ctx.queue { setPhased(positions, false) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.MERGED) { buf, ctx ->
            val beams = List(buf.readVarInt()) {
                val elements = buf.readVarInt()
                val parentA = buf.readInt()
                val parentB = buf.readInt()
                val fused = buf.readVarInt()
                val points = List(buf.readVarInt()) {
                    Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) to Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
                }
                ScrollBeamClient.MergedWire(points, elements, parentA, parentB, fused)
            }
            val cuts = HashMap<Int, Double>()
            repeat(buf.readVarInt()) { cuts[buf.readVarInt()] = buf.readDouble() }
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
            val life = buf.readVarInt()
            val seed = buf.readInt()
            ctx.queue { SpellVfxClient.add(kind, a, b, size, rgb, life, seed) }
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
            repeat(buf.readVarInt()) { cells.put(buf.readVarInt(), (buf.readByte().toInt() and 0xFF) / 255f) }
            ctx.queue { GasFieldClient.received(chunk, cells) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SpellNetwork.GAS_CELLS) { buf, ctx ->
            val chunk = buf.readLong()
            val cells = Int2FloatOpenHashMap()
            repeat(buf.readVarInt()) { cells.put(buf.readVarInt(), (buf.readByte().toInt() and 0xFF) / 255f) }
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

    /** The visible spell effects on the entity with [id], as [SpellVisuals] bits. */
    @JvmStatic
    fun visuals(id: Int): Int = SpellVisuals.clientBits(id)


    /** The entity with [id] left this client: drop what it carried. */
    @JvmStatic
    fun forget(id: Int) {
        absent.remove(id)
        SpellVisuals.setClientBits(id, 0)
    }

    /** Whether the entity with [id] is Absent, so not drawn. */
    @JvmStatic
    fun isAbsent(id: Int): Boolean = absent.contains(id)

    /** Phase or restore [positions]: the world keeps the blocks, the mesher and collision
     *  skip them (see [AbyssalPockets]). Re-mesh around them, neighbours included. */
    private fun setPhased(positions: LongArray, isPhased: Boolean) {
        AbyssalPockets.setClientPhased(positions, isPhased)
        val renderer = Minecraft.getInstance().levelRenderer
        for (key in positions) {
            val pos = BlockPos.of(key)
            renderer.setBlocksDirty(pos.x - 1, pos.y - 1, pos.z - 1, pos.x + 1, pos.y + 1, pos.z + 1)
        }
    }

    /** World change or disconnect. */
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
