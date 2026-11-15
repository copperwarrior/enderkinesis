package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.Vec3i
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.construction.PlanRef

/** Staff plan capture packets. */
object StaffBuildNetwork {
    /** S2C: open the name screen for a capture of a box of this size. */
    val OPEN_CAPTURE_NAME: ResourceLocation = EnderkinesisMod.id("staff_of_command/open_capture_name")
    /** C2S: the name for the capture; the server reads the box from the staff. */
    val SAVE_CAPTURE: ResourceLocation = EnderkinesisMod.id("staff_of_command/save_capture")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SAVE_CAPTURE) { buf, ctx ->
            val name = buf.readUtf(PlanRef.MAX_NAME)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                val staff = player.heldCommandStaff() ?: return@queue
                if (StaffOfCommandItem.getSelectedKey(staff) == StaffBuildKeys.SAVE) StaffBuild.saveCapture(player, staff, name)
            }
        }
    }

    fun openCaptureName(player: ServerPlayer, size: Vec3i) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(size.x)
        buf.writeVarInt(size.y)
        buf.writeVarInt(size.z)
        NetworkManager.sendToPlayer(player, OPEN_CAPTURE_NAME, buf)
    }
}
