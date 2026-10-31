package org.shipwrights.enderkinesis.block

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.booleans.BooleanArrayList
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.EnderkinesisMod

/** The portals are positions in saved data, not blocks, so the client cannot find them alone. */
object WohlonPortalNetwork {

    val S2C_PORTALS: ResourceLocation = EnderkinesisMod.id("wohlon_portal/nearby")

    const val SYNC_INTERVAL_TICKS: Long = 20L
    /** Larger than the loop range, so a loop can start before the player hears it, and the
     *  client portal visuals do not pop in near the player. */
    private const val SYNC_RANGE: Double = 64.0
    /** Larger than the grow loop range of 48 blocks. */
    private const val TREE_SYNC_RANGE: Double = 64.0

    /** Server thread only. */
    private val scratch = LongArrayList()
    private val trunks = LongArrayList()
    private val growing = BooleanArrayList()
    // Placed fraction of the roots, wood and leaves of each trunk's job, for the growth ghost.
    private val progress = it.unimi.dsi.fastutil.floats.FloatArrayList()
    private val progressScratch = FloatArray(3)
    private val nearTrees = IntArrayList()

    fun sync(level: ServerLevel, portals: Collection<Long>, mother: BlockPos?) {
        trunks.clear()
        growing.clear()
        progress.clear()
        WohlonnogondoniaTreeGrower.forEachTrunk(level) { trunk, isGrowing ->
            trunks.add(trunk)
            growing.add(isGrowing)
            if (!WohlonnogondoniaTreeGrower.progress(level, trunk, progressScratch)) progressScratch.fill(0f)
            for (f in progressScratch) progress.add(f)
        }
        for (player in level.players()) {
            scratch.clear()
            for (packed in portals) {
                if (inRange(player.x, player.y, player.z, BlockPos.of(packed), SYNC_RANGE)) scratch.add(packed)
            }
            val nearMother = mother?.takeIf { inRange(player.x, player.y, player.z, it, SYNC_RANGE) }
            nearTrees.clear()
            for (i in 0 until trunks.size) {
                if (inRange(player.x, player.y, player.z, BlockPos.of(trunks.getLong(i)), TREE_SYNC_RANGE)) {
                    nearTrees.add(i)
                }
            }
            if (scratch.isEmpty && nearMother == null && nearTrees.isEmpty) continue
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeVarInt(scratch.size + if (nearMother != null) 1 else 0)
            for (i in 0 until scratch.size) {
                buf.writeLong(scratch.getLong(i))
                buf.writeBoolean(false)
            }
            if (nearMother != null) {
                buf.writeLong(nearMother.asLong())
                buf.writeBoolean(true)
            }
            buf.writeVarInt(nearTrees.size)
            for (j in 0 until nearTrees.size) {
                val i = nearTrees.getInt(j)
                buf.writeLong(trunks.getLong(i))
                buf.writeBoolean(growing.getBoolean(i))
                for (k in 0 until 3) buf.writeFloat(progress.getFloat(i * 3 + k))
            }
            NetworkManager.sendToPlayer(player, S2C_PORTALS, buf)
        }
    }

    private fun inRange(x: Double, y: Double, z: Double, pos: BlockPos, range: Double): Boolean =
        pos.distToCenterSqr(x, y, z) <= range * range
}
