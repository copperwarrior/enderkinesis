package org.shipwrights.enderkinesis.barnacle

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.toWorldCoordinates

/** C2S: a player's left-click hit a barnacle limb. The server checks the reach and the limb before the cut. */
object YgannBarnacleLimbNetwork {

    val CUT: ResourceLocation = EnderkinesisMod.id("ygann_barnacle/cut")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, CUT) { buf, ctx ->
            val pos = buf.readBlockPos()
            val limbId = buf.readVarInt()
            val arc = buf.readFloat().toDouble()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { handleCut(player, pos, limbId, arc) }
        }
    }

    fun sendCut(pos: BlockPos, limbId: Int, arcPx: Double) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        buf.writeVarInt(limbId)
        buf.writeFloat(arcPx.toFloat())
        NetworkManager.sendToServer(CUT, buf)
    }

    private fun handleCut(player: ServerPlayer, pos: BlockPos, limbId: Int, arcPx: Double) {
        if (player.isSpectator || !player.isAlive || !arcPx.isFinite()) return
        val level = player.serverLevel()
        if (!level.isLoaded(pos)) return
        val be = level.getBlockEntity(pos) as? YgannBarnacleBlockEntity ?: return
        val paths = be.serverPaths(level, pos)
        val pathIndex = paths.indexOfFirst { it.id == limbId }
        val path = paths.getOrNull(pathIndex) ?: return
        val point = level.toWorldCoordinates(YgannBarnacleGeometry.limbPoint(pos, path, arcPx))
        if (player.eyePosition.distanceToSqr(point) > MAX_REACH_SQ) return
        cut(level, be, pathIndex, arcPx, point)
    }

    /** Cuts a limb and plays the cut at [point] (world space). False when the limb cannot be cut there now. */
    fun cut(
        level: net.minecraft.server.level.ServerLevel, be: YgannBarnacleBlockEntity, pathIndex: Int, arcPx: Double,
        point: net.minecraft.world.phys.Vec3,
    ): Boolean {
        if (!be.cutLimb(level, pathIndex, arcPx)) return false
        level.playSound(
            null, point.x, point.y, point.z, EKSounds.YGANN_BARNACLE_CUT.get(), SoundSource.BLOCKS,
            1.0f, 0.9f + level.random.nextFloat() * 0.2f,
        )
        level.playSound(null, point.x, point.y, point.z, EKSounds.YGANN_BARNACLE_RIPPLE.get(), SoundSource.BLOCKS, 1f, 1f)
        return true
    }

    /** The server's block-interaction reach limit (6 blocks), squared. */
    private const val MAX_REACH_SQ = 36.0
}
