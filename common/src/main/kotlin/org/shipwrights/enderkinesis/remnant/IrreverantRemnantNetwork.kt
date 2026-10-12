package org.shipwrights.enderkinesis.remnant

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * S2C sync for the Sselith Remnant. The remnant is a pseudo-entity — there
 * is no vanilla entity to ride the usual tracking machinery — so blob state
 * is pushed explicitly to nearby players, who draw it as cubes.
 *
 * Each player gets only the blobs on their own parallel within [SYNC_RANGE]
 * of them, capped at [MAX_SYNCED]. The cap
 * bounds the packet regardless of how much remnant has pooled in the
 * dimension. Positions go as float offsets from an integer origin at the
 * player, and the rest as quantised bytes. A float of the world position
 * itself is only exact to 2 blocks at 19 million out.
 */
object IrreverantRemnantNetwork {

    val SYNC: ResourceLocation = EnderkinesisMod.id("remnant/sync")

    /** Blobs beyond this are omitted — well past where the cubes are legible. */
    const val SYNC_RANGE = 64.0

    /** Hard cap per packet (CLAUDE.md rule 2). Equal to the manager's `MAX_BLOBS`: a lower
     *  cap left blobs that do damage but are not drawn. */
    private const val MAX_SYNCED = 768

    /** Radius quantisation: stored as `radius / RADIUS_STEP` in seven bits, so
     *  up to 0.635. Fine, because volume sharing grows and shrinks radii
     *  gradually and a coarse step shows as size jumps. */
    const val RADIUS_STEP = 0.005f

    /** Ticks a fresh drip takes to form on the face it seeps from. It is held still for that long. Sent as a
     *  signed byte, so it must stay under 128. */
    const val SPAWN_TICKS = 60

    /** Top bit of the radius byte, carrying the seeping flag. */
    const val SEEP_FLAG = 0x80

    /** Velocity quantisation: one signed byte per axis. Covers ±0.508 b/t,
     *  comfortably past the blob terminal speed of 0.35. */
    const val VELOCITY_STEP = 0.004f

    private fun quantiseVelocity(v: Double): Int =
        Math.round(v / VELOCITY_STEP).toInt().coerceIn(-127, 127)

    /** Unit-vector component in one signed byte. */
    const val NORMAL_STEP = 1f / 127f

    private fun quantiseNormal(n: Double): Int =
        Math.round(n * 127.0).toInt().coerceIn(-127, 127)

    /** [playerParallels] holds the parallel table index of each entry in [players], in the same order. */
    fun broadcast(players: List<ServerPlayer>, playerParallels: IntArray, blobs: List<IrreverantRemnantBlob>) {
        val rangeSq = SYNC_RANGE * SYNC_RANGE
        for (p in players.indices) {
            val player = players[p]
            val parallel = playerParallels[p]
            // Fresh buf per player — sendToPlayer may consume the underlying
            // ByteBuf, so a shared one would fail after the first send.
            val buf = FriendlyByteBuf(Unpooled.buffer())
            val originX = Math.floor(player.x).toInt()
            val originY = Math.floor(player.y).toInt()
            val originZ = Math.floor(player.z).toInt()
            buf.writeInt(originX)
            buf.writeInt(originY)
            buf.writeInt(originZ)
            // Count is patched in once known; reserve the slot.
            val countIndex = buf.writerIndex()
            buf.writeShort(0)
            var count = 0
            for (blob in blobs) {
                if (count >= MAX_SYNCED) break
                if (blob.parallel != parallel) continue
                val dx = blob.x - player.x
                val dy = blob.y - player.y
                val dz = blob.z - player.z
                if (dx * dx + dy * dy + dz * dz > rangeSq) continue
                buf.writeVarInt(blob.id)
                buf.writeFloat((blob.x - originX).toFloat())
                buf.writeFloat((blob.y - originY).toFloat())
                buf.writeFloat((blob.z - originZ).toFloat())
                // Velocity is what lets the client keep the mass moving
                // between syncs instead of freezing and jumping. Quantised
                // to bytes — blob speeds never exceed ±0.35 b/t.
                buf.writeByte(quantiseVelocity(blob.vx))
                buf.writeByte(quantiseVelocity(blob.vy))
                buf.writeByte(quantiseVelocity(blob.vz))
                // Seeping rides in the radius byte's top bit. Radius is
                // uniform and small, so that bit is dead space, and a byte
                // per particle per sync is not worth spending on one flag.
                val quantised = (blob.radius / RADIUS_STEP).toInt().coerceIn(1, SEEP_FLAG - 1)
                buf.writeByte(if (blob.seeping) quantised or SEEP_FLAG else quantised)
                // Contact normal — client prediction adheres along it.
                buf.writeByte(quantiseNormal(blob.normalX))
                buf.writeByte(quantiseNormal(blob.normalY))
                buf.writeByte(quantiseNormal(blob.normalZ))
                // The client stretches a drip's skin to the particle it hangs from. Sent only while the link
                // acts, which is while the drip is airborne here, so the drawn link is the physical one.
                buf.writeVarInt((if (blob.onSurface) -1 else blob.parent?.id ?: -1) + 1)
                buf.writeByte(blob.spawnAge)
                count++
            }
            buf.setShort(countIndex, count)
            NetworkManager.sendToPlayer(player, SYNC, buf)

        }
    }
}
