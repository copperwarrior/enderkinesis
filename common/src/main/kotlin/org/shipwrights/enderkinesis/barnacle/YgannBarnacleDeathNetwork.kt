package org.shipwrights.enderkinesis.barnacle

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.valkyrienskies.mod.common.toWorldCoordinates

/**
 * S2C notice that a barnacle just died, sent from `YgannBarnacleBlock.onRemove` for each
 * cause of removal. The block breaks normally on both sides; this
 * fire-and-forget packet hands nearby clients the state they need to play the detached
 * shrivel effect ([org.shipwrights.enderkinesis.client.YgannBarnacleDeathRenderer]) —
 * so nothing keeps the block alive or fights the client's break prediction.
 */
object YgannBarnacleDeathNetwork {

    val DEATH: ResourceLocation = EnderkinesisMod.id("ygann_barnacle/death")

    fun broadcastDeath(
        level: ServerLevel, pos: BlockPos, seed: Long, facing: Direction, reveals: DoubleArray,
    ) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        buf.writeLong(seed)
        buf.writeByte(facing.ordinal)
        buf.writeVarInt(reveals.size)
        for (r in reveals) buf.writeFloat(r.toFloat())
        // On a ship `pos` is in shipyard coordinates; the range test needs the world position.
        val centre = level.toWorldCoordinates(Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))
        for (player in level.players()) {
            if (player.distanceToSqr(centre) < RANGE_SQ) {
                NetworkManager.sendToPlayer(player, DEATH, FriendlyByteBuf(buf.copy()))
            }
        }
    }

    private const val RANGE_SQ = 128.0 * 128.0
}
