package org.shipwrights.enderkinesis.remnant

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import java.util.WeakHashMap
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
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
object IrreverentRemnantNetwork {

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

    /** A resting particle that has not changed is sent again at this interval (ticks), staggered by id. Must stay
     *  under the client's UNSEEN_GRACE_SYNCS and SYNC_TIMEOUT_TICKS (10), or the client drops resting cubes. */
    private const val RESTING_RESEND_TICKS = 5

    // Server thread only. Particles in range of each player at the last sync; 0 means the player is new to them.
    private val lastInRange = WeakHashMap<ServerPlayer, IntArray>()
    private var due = BooleanArray(0)

    /** [playerParallels] holds the parallel table index of each entry in [players], in the same order. */
    fun broadcast(
        players: List<ServerPlayer>, playerParallels: IntArray, blobs: List<IrreverentRemnantBlob>, gameTime: Long,
    ) {
        markDue(blobs, gameTime)
        for (p in players.indices) {
            val player = players[p]
            val last = lastInRange.getOrPut(player) { IntArray(1) }
            // A player new to the remnant gets every particle in range at once, not over the resend interval.
            val arrival = last[0] == 0
            val inRange = countInRange(player, playerParallels[p], blobs)
            val hadAny = last[0] > 0
            last[0] = inRange
            // With nothing in range now or at the last sync there is nothing to send; one empty sync after the
            // last particle leaves starts the client's retire count.
            if (inRange == 0 && !hadAny) continue
            send(player, playerParallels[p], blobs, arrival)
        }
    }

    /** Marks each particle that changed or moves, or whose staggered resend is due. */
    private fun markDue(blobs: List<IrreverentRemnantBlob>, gameTime: Long) {
        if (due.size < blobs.size) due = BooleanArray(blobs.size * 2)
        for (i in blobs.indices) {
            val blob = blobs[i]
            val radius = radiusByte(blob)
            val parent = parentField(blob)
            val velocity = (quantiseVelocity(blob.vx) and 0xFF) or ((quantiseVelocity(blob.vy) and 0xFF) shl 8) or
                ((quantiseVelocity(blob.vz) and 0xFF) shl 16)
            // The tick a particle stops counts as a change, so the client gets its zero velocity at once.
            val changed = radius != blob.syncedRadius || blob.massId != blob.syncedMass ||
                parent != blob.syncedParent || velocity != blob.syncedVelocity
            blob.syncedRadius = radius
            blob.syncedMass = blob.massId
            blob.syncedParent = parent
            blob.syncedVelocity = velocity
            // A seeping cube creeps below one velocity step, and client prediction sinks it with no push-out.
            val resting = !changed && velocity == 0 && blob.onSurface && !blob.seeping && blob.spawnAge >= SPAWN_TICKS
            due[i] = !resting || (gameTime + blob.id) % RESTING_RESEND_TICKS == 0L
        }
    }

    private fun inReach(player: ServerPlayer, parallel: Int, blob: IrreverentRemnantBlob, viewChunks: Int): Boolean {
        if (blob.parallel != parallel) return false
        val dx = blob.x - player.x
        val dy = blob.y - player.y
        val dz = blob.z - player.z
        if (dx * dx + dy * dy + dz * dz > SYNC_RANGE * SYNC_RANGE) return false
        // Past the server view distance the client has no terrain for the cube to rest on.
        return Math.abs((Mth.floor(blob.x) shr 4) - player.chunkPosition().x) <= viewChunks &&
            Math.abs((Mth.floor(blob.z) shr 4) - player.chunkPosition().z) <= viewChunks
    }

    private fun countInRange(player: ServerPlayer, parallel: Int, blobs: List<IrreverentRemnantBlob>): Int {
        val viewChunks = player.server.playerList.viewDistance
        var count = 0
        for (blob in blobs) {
            if (count >= MAX_SYNCED) break
            if (inReach(player, parallel, blob, viewChunks)) count++
        }
        return count
    }

    private fun send(player: ServerPlayer, parallel: Int, blobs: List<IrreverentRemnantBlob>, arrival: Boolean) {
        val viewChunks = player.server.playerList.viewDistance
        // Fresh buf per player: sendToPlayer may consume the underlying ByteBuf.
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
        var inRange = 0
        var count = 0
        for (i in blobs.indices) {
            if (inRange >= MAX_SYNCED) break
            val blob = blobs[i]
            if (!inReach(player, parallel, blob, viewChunks)) continue
            inRange++
            if (!arrival && !due[i]) continue
            writeBlob(buf, blob, originX, originY, originZ)
            count++
        }
        // Nothing due this tick: the client keeps its cubes, and the next due resend is under its timeout.
        if (count == 0 && inRange > 0) return
        buf.setShort(countIndex, count)
        NetworkManager.sendToPlayer(player, SYNC, buf)
    }

    private fun radiusByte(blob: IrreverentRemnantBlob): Int {
        // Seeping rides in the radius byte's top bit: radius is small, so that bit is dead space.
        val quantised = (blob.radius / RADIUS_STEP).toInt().coerceIn(1, SEEP_FLAG - 1)
        return if (blob.seeping) quantised or SEEP_FLAG else quantised
    }

    // The client stretches a drip's skin to the particle it hangs from. Sent only while the link acts, which is
    // while the drip is airborne here, so the drawn link is the physical one.
    private fun parentField(blob: IrreverentRemnantBlob): Int = (if (blob.onSurface) -1 else blob.parent?.id ?: -1) + 1

    private fun writeBlob(buf: FriendlyByteBuf, blob: IrreverentRemnantBlob, originX: Int, originY: Int, originZ: Int) {
        buf.writeVarInt(blob.id)
        buf.writeFloat((blob.x - originX).toFloat())
        buf.writeFloat((blob.y - originY).toFloat())
        buf.writeFloat((blob.z - originZ).toFloat())
        // Velocity lets the client keep the mass moving between syncs instead of freezing and jumping.
        buf.writeByte(quantiseVelocity(blob.vx))
        buf.writeByte(quantiseVelocity(blob.vy))
        buf.writeByte(quantiseVelocity(blob.vz))
        buf.writeByte(blob.syncedRadius)
        // Contact normal: client prediction adheres along it.
        buf.writeByte(quantiseNormal(blob.normalX))
        buf.writeByte(quantiseNormal(blob.normalY))
        buf.writeByte(quantiseNormal(blob.normalZ))
        buf.writeVarInt(blob.syncedParent)
        buf.writeByte(blob.spawnAge)
        buf.writeVarInt(blob.massId)
    }
}
