package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import java.util.UUID
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Network packets for the Wylland Tome gravity gun.
 *
 * **C2S** (client → server):
 *  - [BEGIN_GRAB_SHIP] — raycast found a ship; start grabbing.
 *  - [BEGIN_GRAB_ENTITY] — raycast found an entity; start grabbing.
 *  - [RELEASE] — player let go of left-click.
 *  - [ROTATE_INPUT] — mouse delta (yaw + pitch degrees) while mod key
 *    is held + grab active.
 *
 * **S2C** (server → client):
 *  - [GRAB_POINT_SYNC] — current world position of the grab anchor on
 *    the target, sent each server tick to the grabbing player so the
 *    enchant-particle beam can terminate on the actual ship/entity
 *    contact point instead of a look-projected guess.
 */
object WyllandTomeNetwork {

    val BEGIN_GRAB_SHIP: ResourceLocation = EnderkinesisMod.id("wylland_tome/begin_grab_ship")
    val BEGIN_GRAB_ENTITY: ResourceLocation = EnderkinesisMod.id("wylland_tome/begin_grab_entity")
    val RELEASE: ResourceLocation = EnderkinesisMod.id("wylland_tome/release")
    val ROTATE_INPUT: ResourceLocation = EnderkinesisMod.id("wylland_tome/rotate_input")
    val APPLY_ROLL: ResourceLocation = EnderkinesisMod.id("wylland_tome/apply_roll")
    val ADJUST_DISTANCE: ResourceLocation = EnderkinesisMod.id("wylland_tome/adjust_distance")
    val GRAB_POINT_SYNC: ResourceLocation = EnderkinesisMod.id("wylland_tome/grab_point_sync")
    val GRAB_END: ResourceLocation = EnderkinesisMod.id("wylland_tome/grab_end")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, BEGIN_GRAB_SHIP) { buf, ctx ->
            val shipId = buf.readLong()
            val hx = buf.readDouble()
            val hy = buf.readDouble()
            val hz = buf.readDouble()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                WyllandTomeManager.beginGrab(
                    player,
                    WyllandTomeManager.GrabTarget.Ship(shipId, hx, hy, hz),
                )
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, BEGIN_GRAB_ENTITY) { buf, ctx ->
            val uuid = UUID(buf.readLong(), buf.readLong())
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                WyllandTomeManager.beginGrab(
                    player,
                    WyllandTomeManager.GrabTarget.Entity(uuid),
                )
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, RELEASE) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { WyllandTomeManager.release(player) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ROTATE_INPUT) { buf, ctx ->
            val yawDeg = buf.readDouble()
            val pitchDeg = buf.readDouble()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { WyllandTomeManager.applyRotateInput(player, yawDeg, pitchDeg) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, APPLY_ROLL) { buf, ctx ->
            val degrees = buf.readDouble()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { WyllandTomeManager.applyRollInput(player, degrees) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ADJUST_DISTANCE) { buf, ctx ->
            val delta = buf.readDouble()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { WyllandTomeManager.adjustDistance(player, delta) }
        }
    }

    /** Hit position is in **ship-local (shipyard) coordinates** —
     *  the client re-derives this from a voxelshape clip in ship-frame
     *  after VS2's raycast identifies the block (see
     *  [org.shipwrights.enderkinesis.client.WyllandTomeClient.tryBeginGrab]).
     *  Ship-frame eliminates the precision sag VS2's mixed-frame
     *  BlockHitResult produces on partial blocks. */
    fun sendBeginGrabShip(shipId: Long, shipLocalX: Double, shipLocalY: Double, shipLocalZ: Double) {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        buf.writeLong(shipId)
        buf.writeDouble(shipLocalX)
        buf.writeDouble(shipLocalY)
        buf.writeDouble(shipLocalZ)
        NetworkManager.sendToServer(BEGIN_GRAB_SHIP, buf)
    }

    fun sendBeginGrabEntity(uuid: UUID) {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        buf.writeLong(uuid.mostSignificantBits)
        buf.writeLong(uuid.leastSignificantBits)
        NetworkManager.sendToServer(BEGIN_GRAB_ENTITY, buf)
    }

    fun sendRelease() {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        NetworkManager.sendToServer(RELEASE, buf)
    }

    /** Explicit mouse-delta rotation while the player is holding the
     *  mod key (which locks the camera client-side). Yaw and pitch are
     *  degrees per packet, already scaled by vanilla's 0.15
     *  mouse-to-degrees factor. Used only in mod-key mode — when the
     *  mod key is up the server's per-tick camera-delta path drives
     *  rotation on its own. */
    fun sendRotateInput(yawDeg: Double, pitchDeg: Double) {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        buf.writeDouble(yawDeg)
        buf.writeDouble(pitchDeg)
        NetworkManager.sendToServer(ROTATE_INPUT, buf)
    }

    /** Mouse-wheel roll: signed degrees of rotation around the player's
     *  current look direction. Sent when the player scrolls with the mod
     *  key held while grabbing a ship. */
    fun sendApplyRoll(degrees: Double) {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        buf.writeDouble(degrees)
        NetworkManager.sendToServer(APPLY_ROLL, buf)
    }

    /** Push/pull the grabbed target along the player's look direction.
     *  Positive [delta] pulls the target closer; negative pushes away.
     *  Clamped server-side to MIN/MAX grab distance. */
    fun sendAdjustDistance(delta: Double) {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        buf.writeDouble(delta)
        NetworkManager.sendToServer(ADJUST_DISTANCE, buf)
    }

    /** Sends both the **anchor** (actual world position of the grab
     *  point on the ship/entity) and the **cursor** (the look-projected
     *  spring target) so the client beam can use one as its endpoint
     *  and the other as a bezier control point — visualises the spring
     *  tension when the ship lags behind the cursor. */
    fun sendGrabPointSync(
        player: ServerPlayer,
        shipId: Long?,
        anchorX: Double, anchorY: Double, anchorZ: Double,
        cursorX: Double, cursorY: Double, cursorZ: Double,
    ) {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        // Ship id of the held target (or absent for an entity grab) so
        // the client can rain glyph particles from inside the ship's
        // local AABB while it's targeted.
        buf.writeBoolean(shipId != null)
        buf.writeLong(shipId ?: 0L)
        buf.writeDouble(anchorX)
        buf.writeDouble(anchorY)
        buf.writeDouble(anchorZ)
        buf.writeDouble(cursorX)
        buf.writeDouble(cursorY)
        buf.writeDouble(cursorZ)
        NetworkManager.sendToPlayer(player, GRAB_POINT_SYNC, buf)
    }

    /** Tell the client that its grab has been ended server-side (max
     *  distance exceeded, target despawned, player got dragged by the
     *  ship, etc.). The client uses this to clear its beam state so
     *  the particle stream stops the same tick the connection breaks. */
    fun sendGrabEnd(player: ServerPlayer) {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        NetworkManager.sendToPlayer(player, GRAB_END, buf)
    }
}
