package org.shipwrights.enderkinesis.body

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.util.playersHolding
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID

/**
 * Channels + server senders for the Wand-of-Ga'Mylan cage sync layer.
 *
 *  - **[CAGE_ADDED] (S2C)** broadcast on capture + on player join.
 *    Full payload: bodyId, captured NBT, animation NBT, captureOrigin,
 *    hoverPos, shapeDims, addedAtGameTime, maxHp, hp.
 *  - **[CAGE_REMOVED] (S2C)** on cage break.
 *  - **[CAGE_HP] (S2C)** on per-hit HP update so clients can drive any
 *    visible damage cue (cracks, screen-shake, whatever — TBD).
 *  - **[CAGE_ALIGN] (S2C)** when a cage starts or stops its drift to a
 *    pedestal. Drives the client align loop.
 *  - **[CAGE_ATTACK] (C2S)** sent by the client after its raycast
 *    confirms a cage hit. Server validates range + applies damage in
 *    [org.shipwrights.enderkinesis.item.GamylanCageManager].
 */
object GamylanCageNetwork {

    val CAGE_ADDED: ResourceLocation = EnderkinesisMod.id("gamylan_cage/added")
    val CAGE_REMOVED: ResourceLocation = EnderkinesisMod.id("gamylan_cage/removed")
    val CAGE_HP: ResourceLocation = EnderkinesisMod.id("gamylan_cage/hp")
    val CAGE_IMPACT: ResourceLocation = EnderkinesisMod.id("gamylan_cage/impact")
    val CAGE_ALIGN: ResourceLocation = EnderkinesisMod.id("gamylan_cage/align")
    val CAGE_ATTACK: ResourceLocation = EnderkinesisMod.id("gamylan_cage/attack")

    /** How often each player's set of cages in view is checked again, in ticks. */
    private const val VIEW_CHECK_TICKS = 10L

    private class Known(var dimension: ResourceKey<Level>?) {
        val ids = LongOpenHashSet()
    }

    // Server thread only. The cages each player's client holds: those within the server view distance.
    private val known = HashMap<UUID, Known>()

    // ---- S2C senders ----

    /** A new cage goes to the players who can see it; the others get it when it comes into their view. */
    fun broadcastAdded(level: ServerLevel, data: GamylanCageData) {
        val buf = encodeAdded(data)
        for (player in level.players()) {
            if (!inView(level, player, data)) continue
            knownFor(player, level).ids.add(data.bodyId)
            NetworkManager.sendToPlayer(player, CAGE_ADDED, FriendlyByteBuf(buf.copy()))
        }
    }

    /** The cage broke or went: clients that hold it show the shatter. */
    fun broadcastRemoved(level: ServerLevel, bodyId: Long) {
        val buf = encodeRemoved(bodyId, shatter = true)
        for (player in level.players()) {
            if (known[player.uuid]?.takeIf { it.dimension == level.dimension() }?.ids?.remove(bodyId) != true) continue
            NetworkManager.sendToPlayer(player, CAGE_REMOVED, FriendlyByteBuf(buf.copy()))
        }
    }

    fun broadcastHp(level: ServerLevel, bodyId: Long, hp: Int) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(bodyId)
        buf.writeVarInt(hp)
        for (player in holders(level, bodyId)) NetworkManager.sendToPlayer(player, CAGE_HP, FriendlyByteBuf(buf.copy()))
    }

    /** A joining, respawned or travelling player starts with no cages, then gets the ones in view. */
    fun refreshViewer(player: ServerPlayer, level: ServerLevel) {
        known[player.uuid] = Known(level.dimension())
        updateViewer(player, level)
    }

    fun forgetViewer(player: ServerPlayer) {
        known.remove(player.uuid)
    }

    fun clearViewers() = known.clear()

    fun tickViewers(level: ServerLevel) {
        if (level.gameTime % VIEW_CHECK_TICKS != 0L) return
        for (player in level.players()) updateViewer(player, level)
    }

    /** Sends the cages that came into [player]'s view, and forgets (without a shatter) the ones that left it. */
    private fun updateViewer(player: ServerPlayer, level: ServerLevel) {
        val cages = GamylanCageRegistry.snapshot(level)
        val holder = knownFor(player, level)
        if (cages.isEmpty() && holder.ids.isEmpty()) return
        for (data in cages) {
            val seen = inView(level, player, data)
            if (seen && holder.ids.add(data.bodyId)) {
                NetworkManager.sendToPlayer(player, CAGE_ADDED, encodeAdded(data))
                GamylanCagePedestals.sendAlignState(player, data.bodyId)
            } else if (!seen && holder.ids.remove(data.bodyId)) {
                NetworkManager.sendToPlayer(player, CAGE_REMOVED, encodeRemoved(data.bodyId, shatter = false))
            }
        }
        // Cages that went without a removal broadcast (another dimension's record, a cleared registry).
        if (holder.ids.size > cages.size) {
            val present = LongOpenHashSet()
            for (data in cages) present.add(data.bodyId)
            val gone = holder.ids.iterator()
            while (gone.hasNext()) {
                val id = gone.nextLong()
                if (id in present) continue
                gone.remove()
                NetworkManager.sendToPlayer(player, CAGE_REMOVED, encodeRemoved(id, shatter = false))
            }
        }
    }

    private fun knownFor(player: ServerPlayer, level: ServerLevel): Known {
        val holder = known.getOrPut(player.uuid) { Known(level.dimension()) }
        // The client keeps its cages per dimension, so a new dimension starts an empty set here.
        if (holder.dimension != level.dimension()) {
            holder.dimension = level.dimension()
            holder.ids.clear()
        }
        return holder
    }

    private fun holders(level: ServerLevel, bodyId: Long): List<ServerPlayer> =
        level.players().filter { known[it.uuid]?.let { k -> k.dimension == level.dimension() && bodyId in k.ids } == true }

    @OptIn(GameTickOnly::class, VsBeta::class) // kinematics is VsBeta
    private fun inView(level: ServerLevel, player: ServerPlayer, data: GamylanCageData): Boolean {
        val live = level.shipObjectWorld.allBodies.getById(data.bodyId)?.kinematics?.position
        val x = live?.x() ?: data.hoverPos.x
        val z = live?.z() ?: data.hoverPos.z
        val range = player.server.playerList.viewDistance + 1
        return Math.abs((Mth.floor(x) shr 4) - player.chunkPosition().x) <= range &&
            Math.abs((Mth.floor(z) shr 4) - player.chunkPosition().z) <= range
    }

    private fun encodeRemoved(bodyId: Long, shatter: Boolean): FriendlyByteBuf {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(bodyId)
        buf.writeBoolean(shatter)
        return buf
    }

    /** Impact-damage tick — client uses the ping to spawn a light glass
     *  shatter cloud at the cage's live position. Emitted alongside the
     *  matching HP update; the HP update alone doesn't distinguish an
     *  impact from a wand attack, and the two should trigger different
     *  effects. */
    fun broadcastImpact(level: ServerLevel, bodyId: Long, at: Vec3) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(bodyId)
        // A local shatter cloud: only players who hold the cage's chunk can see it.
        for (player in level.playersHolding(listOf(at))) {
            NetworkManager.sendToPlayer(player, CAGE_IMPACT, FriendlyByteBuf(buf.copy()))
        }
    }

    /** [hoverInFrame] is in the frame of [shipId] (world when null). A null [hoverInFrame] stops the align loop. */
    fun broadcastAlign(level: ServerLevel, bodyId: Long, shipId: Long?, hoverInFrame: Vector3dc?) {
        val buf = encodeAlign(bodyId, shipId, hoverInFrame)
        for (player in holders(level, bodyId)) NetworkManager.sendToPlayer(player, CAGE_ALIGN, FriendlyByteBuf(buf.copy()))
    }

    fun sendAlign(player: ServerPlayer, bodyId: Long, shipId: Long?, hoverInFrame: Vector3dc) {
        NetworkManager.sendToPlayer(player, CAGE_ALIGN, encodeAlign(bodyId, shipId, hoverInFrame))
    }

    private fun encodeAlign(bodyId: Long, shipId: Long?, hoverInFrame: Vector3dc?): FriendlyByteBuf {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(bodyId)
        buf.writeBoolean(hoverInFrame != null)
        if (hoverInFrame == null) return buf
        buf.writeBoolean(shipId != null)
        if (shipId != null) buf.writeLong(shipId)
        buf.writeDouble(hoverInFrame.x()); buf.writeDouble(hoverInFrame.y()); buf.writeDouble(hoverInFrame.z())
        return buf
    }

    private fun encodeAdded(data: GamylanCageData): FriendlyByteBuf {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(data.bodyId)
        buf.writeNbt(data.capturedTag)
        buf.writeNbt(data.animationTag)
        buf.writeDouble(data.captureOrigin.x); buf.writeDouble(data.captureOrigin.y); buf.writeDouble(data.captureOrigin.z)
        buf.writeDouble(data.hoverPos.x); buf.writeDouble(data.hoverPos.y); buf.writeDouble(data.hoverPos.z)
        buf.writeDouble(data.shapeDims.x); buf.writeDouble(data.shapeDims.y); buf.writeDouble(data.shapeDims.z)
        buf.writeLong(data.addedAtGameTime)
        buf.writeVarInt(data.maxHp)
        buf.writeVarInt(data.hp)
        // Player capture marker — nullable profile UUID + name. False
        // for normal mob captures (the overwhelming majority); when
        // true, the client builds a RemotePlayer rather than rebuilding
        // the entity from capturedTag. The customization byte drives
        // skin layer visibility; the 6-slot equipment list drives armor
        // + hand rendering.
        val uuid = data.playerProfileUuid
        val name = data.playerName
        if (uuid != null && name != null) {
            buf.writeBoolean(true)
            buf.writeUUID(uuid)
            buf.writeUtf(name)
            buf.writeByte(data.playerCustomizationByte.toInt())
            buf.writeVarInt(data.playerEquipment.size)
            for (stack in data.playerEquipment) buf.writeItem(stack)
        } else {
            buf.writeBoolean(false)
        }
        // Pedestal-conjured item cages: the displayed item (empty for
        // ordinary entity captures — decoded as EMPTY client-side).
        buf.writeItem(data.displayedItem)
        return buf
    }
}
