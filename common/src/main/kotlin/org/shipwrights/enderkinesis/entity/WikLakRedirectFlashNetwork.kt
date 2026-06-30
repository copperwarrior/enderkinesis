package org.shipwrights.enderkinesis.entity

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import java.util.UUID
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * S2C trigger for the wik-lak skin fade overlay on a player's body after a
 * [WikLakDeathRedirect] swap. Carries the redirected player's UUID; clients
 * within [BROADCAST_RADIUS] of the broadcast point stamp the UUID into
 * [org.shipwrights.enderkinesis.client.WikLakRedirectFlashTracker], and the
 * player render layer [org.shipwrights.enderkinesis.client.WikLakRedirectFlashLayer]
 * draws the wik-lak skin over the player with alpha fading from 1 → 0
 * across [FADE_TICKS].
 *
 * No server-side init — Architectury's [NetworkManager.sendToPlayer] sends
 * by channel ID without sender-side registration. The receiver is registered
 * client-side inside [org.shipwrights.enderkinesis.client.EnderkinesisModClient].
 */
object WikLakRedirectFlashNetwork {

    val FLASH: ResourceLocation = EnderkinesisMod.id("wik_lak_flash")

    /** Total ticks the fade overlay persists on the client. 30 ticks ≈
     *  1.5 s — long enough to read as a deliberate transition, short
     *  enough not to drag. */
    const val FADE_TICKS: Long = 30L

    /** Broadcast radius (blocks) around the player's new position. Any
     *  client tracking the area receives the flash trigger so observers
     *  see the same fade their target does. */
    private const val BROADCAST_RADIUS: Double = 128.0
    private const val BROADCAST_RADIUS_SQR: Double = BROADCAST_RADIUS * BROADCAST_RADIUS

    /** Server-side: stamp every player within [BROADCAST_RADIUS] of
     *  [pos] with a fade trigger for [playerUuid]. The redirected player
     *  themselves are within range by definition (they just teleported
     *  there). */
    fun broadcast(level: ServerLevel, pos: Vec3, playerUuid: UUID) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(playerUuid)
        for (player in level.players()) {
            val dx = player.x - pos.x
            val dy = player.y - pos.y
            val dz = player.z - pos.z
            if (dx * dx + dy * dy + dz * dz <= BROADCAST_RADIUS_SQR) {
                NetworkManager.sendToPlayer(player, FLASH, FriendlyByteBuf(buf.copy()))
            }
        }
    }
}
