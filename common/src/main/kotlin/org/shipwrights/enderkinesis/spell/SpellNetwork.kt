package org.shipwrights.enderkinesis.spell

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Server senders for the spell effects clients have to see. Receivers are in
 * `SpellNetworkClient`, registered from client init.
 *
 * - [ABSENT]: an entity went Absent or came back; clients stop or resume drawing it.
 * - [PHASE] / [UNPHASE]: Abyssal Pocket blocks went or returned; clients hide them locally.
 * - [STUTTER]: Reveal makes a concealed ship's cloak flicker for a while.
 *
 * Each goes to every player in the level, like the concealment packets; the effects are a
 * few seconds long, so a player arriving mid-effect simply misses it.
 */
object SpellNetwork {
    val ABSENT: ResourceLocation = EnderkinesisMod.id("spell/absent")
    val PHASE: ResourceLocation = EnderkinesisMod.id("spell/phase")
    val UNPHASE: ResourceLocation = EnderkinesisMod.id("spell/unphase")
    val STUTTER: ResourceLocation = EnderkinesisMod.id("spell/stutter")

    fun broadcastAbsent(level: ServerLevel, entity: Entity, absent: Boolean) =
        broadcast(level, ABSENT) { it.writeVarInt(entity.id); it.writeBoolean(absent) }

    fun broadcastPhase(level: ServerLevel, positions: Collection<Long>, phased: Boolean) =
        broadcast(level, if (phased) PHASE else UNPHASE) { buf ->
            buf.writeVarInt(positions.size)
            for (pos in positions) buf.writeLong(pos)
        }

    fun broadcastStutter(level: ServerLevel, shipId: Long, ticks: Int) =
        broadcast(level, STUTTER) { it.writeLong(shipId); it.writeVarInt(ticks) }

    private inline fun broadcast(level: ServerLevel, id: ResourceLocation, write: (FriendlyByteBuf) -> Unit) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        write(buf)
        // copy(): NetworkManager keeps the buffer past send.
        for (player in level.players()) NetworkManager.sendToPlayer(player, id, FriendlyByteBuf(buf.copy()))
    }
}
