package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import org.shipwrights.enderkinesis.construction.PlanRef
import org.shipwrights.enderkinesis.item.StaffBuild
import org.shipwrights.enderkinesis.item.StaffBuildKeys
import org.shipwrights.enderkinesis.item.StaffBuildNetwork
import org.shipwrights.enderkinesis.item.StaffOfCommandItem
import org.shipwrights.enderkinesis.item.heldCommandStaff
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard

/** Client side of the staff capture entries: the name screen and the gaze corner of the capture box. */
object StaffBuildClient {

    /** The block under the gaze, in the frame of the set corner, while one capture corner is set and "Mark corners" is selected. */
    var gazeCorner: BlockPos? = null
        private set

    /** The capture state of a staff stack, read from its tag once. */
    class StaffView(val capture: StaffBuild.CaptureBox?)

    // Client thread only. The server sends a new tag object on each change, so the tag reference keys the view.
    private var viewTag: CompoundTag? = null
    private var cachedView: StaffView? = null

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, StaffBuildNetwork.OPEN_CAPTURE_NAME) { buf, ctx ->
            val size = Vec3i(buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
            ctx.queue { Minecraft.getInstance().setScreen(CaptureNameScreen(size, ::saveCapture)) }
        }
        ClientTickEvent.CLIENT_POST.register { tick() }
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
            PlanGhostRenderer.clear()
            viewTag = null
            cachedView = null
        }
    }

    fun view(staff: ItemStack): StaffView {
        val tag = staff.tag
        cachedView?.let { if (tag === viewTag) return it }
        val view = StaffView(StaffBuild.capture(staff))
        viewTag = tag
        cachedView = view
        return view
    }

    private fun saveCapture(name: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUtf(name, PlanRef.MAX_NAME)
        NetworkManager.sendToServer(StaffBuildNetwork.SAVE_CAPTURE, buf)
    }

    /** One gaze clip per client tick, not per frame; only while a capture box follows the gaze. */
    private fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player
        val level = mc.level
        val staff = player?.heldCommandStaff()
        val capture = staff?.let(::view)?.capture
        val marking = staff != null && StaffOfCommandItem.getSelectedKey(staff) == StaffBuildKeys.MARK &&
            capture != null && capture.corners == 1 && capture.dimension == level?.dimension()
        if (player == null || level == null || !marking) {
            gazeCorner = null
            return
        }
        val eye = player.getEyePosition(1f)
        val end = eye.add(player.getViewVector(1f).scale(StaffBuild.MAX_CORNER_DISTANCE))
        val hit = level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        // VS2 clip: a ship hit has a shipyard blockPos.
        val ship = if (level.isBlockInShipyard(hit.blockPos)) level.getShipManagingPos(hit.blockPos)?.id else null
        if (hit.type != HitResult.Type.BLOCK || ship != capture?.ship) {
            gazeCorner = null
            return
        }
        if (gazeCorner != hit.blockPos) gazeCorner = hit.blockPos.immutable()
    }
}
