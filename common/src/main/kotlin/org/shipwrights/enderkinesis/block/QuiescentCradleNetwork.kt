package org.shipwrights.enderkinesis.block

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.QuiescentCradleBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.PI

/**
 * - [TURN] (C2S): a control-orrery drag turned one gimbal ring.
 * - [RESET] (C2S): a click on the ship marker sets the gimbal back to the turn at seize.
 *
 * The server checks the player and the cradle again for each.
 */
object QuiescentCradleNetwork {

    val TURN: ResourceLocation = EnderkinesisMod.id("quiescent_cradle/turn")
    val RESET: ResourceLocation = EnderkinesisMod.id("quiescent_cradle/reset")

    /** Blocks from the player's eyes to the cradle centre. */
    const val MAX_DISTANCE = 8.0

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TURN) { buf, ctx ->
            val pos = buf.readBlockPos()
            val ring = buf.readByte().toInt()
            val delta = buf.readFloat().toDouble()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { turn(player, pos, ring, delta) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, RESET) { buf, ctx ->
            val pos = buf.readBlockPos()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { cradleFor(player, pos)?.let { it.resetTurn(player.level() as ServerLevel) } }
        }
    }

    fun sendReset(pos: BlockPos) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        NetworkManager.sendToServer(RESET, buf)
    }

    fun sendTurn(pos: BlockPos, ring: Int, delta: Float) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        buf.writeByte(ring)
        buf.writeFloat(delta)
        NetworkManager.sendToServer(TURN, buf)
    }

    private fun turn(player: ServerPlayer, pos: BlockPos, ring: Int, delta: Double) {
        if (ring !in 0 until QuiescentCradleFrame.RINGS || !delta.isFinite()) return
        val cradle = cradleFor(player, pos) ?: return
        cradle.turn(player.level() as ServerLevel, ring, delta.coerceIn(-PI, PI))
    }

    /** The cradle at [pos] if the player is near it. */
    private fun cradleFor(player: ServerPlayer, pos: BlockPos): QuiescentCradleBlockEntity? {
        val level = player.level() as? ServerLevel ?: return null
        // The position is from the client, and may be a shipyard position. A block entity read far away loads the chunk.
        val centre = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(centre)
        if (player.eyePosition.distanceToSqr(centre.x, centre.y, centre.z) > MAX_DISTANCE * MAX_DISTANCE) return null
        return level.getBlockEntity(pos) as? QuiescentCradleBlockEntity
    }
}
