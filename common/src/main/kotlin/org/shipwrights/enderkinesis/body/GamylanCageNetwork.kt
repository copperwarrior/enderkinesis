package org.shipwrights.enderkinesis.body

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.EnderkinesisMod

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

    // ---- S2C senders ----

    fun broadcastAdded(level: ServerLevel, data: GamylanCageData) {
        val buf = encodeAdded(data)
        for (player in level.players()) NetworkManager.sendToPlayer(player, CAGE_ADDED, FriendlyByteBuf(buf.copy()))
    }

    fun broadcastRemoved(level: ServerLevel, bodyId: Long) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(bodyId)
        for (player in level.players()) NetworkManager.sendToPlayer(player, CAGE_REMOVED, FriendlyByteBuf(buf.copy()))
    }

    fun broadcastHp(level: ServerLevel, bodyId: Long, hp: Int) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(bodyId)
        buf.writeVarInt(hp)
        for (player in level.players()) NetworkManager.sendToPlayer(player, CAGE_HP, FriendlyByteBuf(buf.copy()))
    }

    /** Impact-damage tick — client uses the ping to spawn a light glass
     *  shatter cloud at the cage's live position. Emitted alongside the
     *  matching HP update; the HP update alone doesn't distinguish an
     *  impact from a wand attack, and the two should trigger different
     *  effects. */
    fun broadcastImpact(level: ServerLevel, bodyId: Long) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(bodyId)
        for (player in level.players()) NetworkManager.sendToPlayer(player, CAGE_IMPACT, FriendlyByteBuf(buf.copy()))
    }

    /** [hoverInFrame] is in the frame of [shipId] (world when null). A null [hoverInFrame] stops the align loop. */
    fun broadcastAlign(level: ServerLevel, bodyId: Long, shipId: Long?, hoverInFrame: Vector3dc?) {
        val buf = encodeAlign(bodyId, shipId, hoverInFrame)
        for (player in level.players()) NetworkManager.sendToPlayer(player, CAGE_ALIGN, FriendlyByteBuf(buf.copy()))
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

    fun sendFullList(player: ServerPlayer, level: ServerLevel) {
        for (data in GamylanCageRegistry.snapshot(level)) {
            NetworkManager.sendToPlayer(player, CAGE_ADDED, encodeAdded(data))
        }
        GamylanCagePedestals.sendAlignStates(player, level)
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
