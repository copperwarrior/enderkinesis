package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.HitResult
import org.joml.Vector3d
import org.shipwrights.enderkinesis.vine.CLIMB_DOWN
import org.shipwrights.enderkinesis.vine.CLIMB_MASK
import org.shipwrights.enderkinesis.vine.CLIMB_UP
import org.shipwrights.enderkinesis.vine.LOG
import org.shipwrights.enderkinesis.vine.MOVE_BACK
import org.shipwrights.enderkinesis.vine.MOVE_FORWARD
import org.shipwrights.enderkinesis.vine.MOVE_LEFT
import org.shipwrights.enderkinesis.vine.MOVE_MASK
import org.shipwrights.enderkinesis.vine.MOVE_RIGHT
import org.shipwrights.enderkinesis.vine.PICK_REACH
import org.shipwrights.enderkinesis.vine.VineNodeRef
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_GRIP
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_HOLD
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_HOLDER
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_LET_GO
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_REEL_END
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_SHORTEN
import org.shipwrights.enderkinesis.vine.canHoldVine
import org.shipwrights.enderkinesis.vine.clientVineCollider
import org.shipwrights.enderkinesis.vine.clientVineHold
import org.shipwrights.enderkinesis.vine.takeNodeVelocity
import org.shipwrights.enderkinesis.vine.toPacket
import kotlin.math.abs
import kotlin.math.min

/** Remote shown grip step in node units per tick; a larger gap than [REMOTE_GRIP_SNAP] jumps. */
private const val REMOTE_GRIP_STEP = 0.15
private const val REMOTE_GRIP_SNAP = 2.0
/** The server sends a held grip every 40 ticks; with no packet for this long the hold is stale. */
private const val REMOTE_HOLD_EXPIRE_TICKS = 100L
/** Let-go fling: the node velocity times this. */
private const val RELEASE_BOOST = 1.75
/** Let-go lift in blocks per tick, when the node does not fall. */
private const val RELEASE_LIFT = 0.15
/** Below this node speed in m/s a let-go is a plain drop, with no boost or lift. */
private const val RELEASE_MIN_SPEED = 1.0
/** A node that falls slower than this, in m/s, counts as level for the lift. */
private const val RELEASE_LEVEL_FALL = 0.5
// TODO [VineHoldDebug] temporary
private const val DEBUG_RELEASE_TICKS = 3

/** The grip point of the last two ticks, so the arm pose can take it at the partial tick, as vanilla draws the body. */
private class GripTrack {
    private val previous = Vector3d()
    private val current = Vector3d()
    private var valid = false

    fun push(x: Double, y: Double, z: Double) {
        if (valid) previous.set(current) else previous.set(x, y, z)
        current.set(x, y, z)
        valid = true
    }

    fun invalidate() {
        valid = false
    }

    fun at(partialTick: Double, out: Vector3d): Boolean {
        if (!valid) return false
        previous.lerp(current, partialTick, out)
        return true
    }
}

/** Another player's hold, from WOGGAK_VINE_HOLDER packets. */
private class RemoteVineHold(var chainId: Long, var grip: Double, var lastPacket: Long) {
    var shownGrip = grip
    val track = GripTrack()
}

/** Grabs, climbs and lets go of vines for the local player; the move hook does the hanging. Client thread only. */
object VineHoldClient {
    private val origin = Vector3d()
    private val direction = Vector3d()
    private val grab = VineNodeRef()
    // After the server ends or refuses a hold, a new grab waits for a new press of the use key.
    private var waitForRelease = false
    /** A sneak-use press reels the vine in until a release, or until the server ends the reel. */
    private var reeling = false
    private val remoteHolds = Int2ObjectOpenHashMap<RemoteVineHold>()
    private val localTrack = GripTrack()
    private val trackScratch = Vector3d()
    // TODO [VineReelDebug] temporary
    private var debugUseDown = false
    // TODO [VineHoldDebug] temporary
    private var debugReleaseTicks = 0

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOGGAK_VINE_HOLDER) { buf, ctx ->
            val entityId = buf.readVarInt()
            val held = buf.readBoolean()
            val chainId = if (held) buf.readLong() else 0L
            val grip = if (held) buf.readDouble() else 0.0
            ctx.queue { takeHolder(entityId, held && grip.isFinite() && grip >= 0.0, chainId, grip) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOGGAK_VINE_LET_GO) { buf, ctx ->
            val chainId = buf.readLong()
            val mantle = buf.readBoolean()
            ctx.queue {
                if (chainId != clientVineHold.chainId) return@queue
                waitForRelease = true
                Minecraft.getInstance().player?.let { letGo(it, false, mantle) }
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOGGAK_VINE_REEL_END) { _, ctx ->
            ctx.queue {
            LOG.info("[VineReelDebug] client got REEL_END")
            reeling = false
        }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOGGAK_VINE_GRIP) { buf, ctx ->
            val chainId = buf.readLong()
            val previousChainId = buf.readLong()
            val grip = buf.readDouble()
            ctx.queue {
                val hold = clientVineHold
                if (!hold.isHeld || !grip.isFinite() || grip < 0.0) return@queue
                when (hold.chainId) {
                    chainId -> hold.takeServerGrip(grip)
                    previousChainId -> hold.switchChain(chainId, grip)
                }
            }
        }
    }

    fun clear() {
        reeling = false
        clientVineHold.clear()
        clientVineHold.clearGrace()
        localTrack.invalidate()
        remoteHolds.clear()
        waitForRelease = false
    }

    private fun takeHolder(entityId: Int, held: Boolean, chainId: Long, grip: Double) {
        val level = Minecraft.getInstance().level ?: return
        if (!held) {
            remoteHolds.remove(entityId)
            return
        }
        val remote = remoteHolds.get(entityId)
        if (remote == null || remote.chainId != chainId) {
            remoteHolds.put(entityId, RemoteVineHold(chainId, grip, level.gameTime))
            return
        }
        remote.grip = grip
        remote.lastPacket = level.gameTime
    }

    /** The grip point of [player]'s hold at [partialTick] into [out]; false when it holds no vine that this client has. */
    fun gripPoint(player: Player, partialTick: Double, out: Vector3d): Boolean {
        if (player.isLocalPlayer) {
            val hold = clientVineHold
            return hold.isHeld && !hold.lost && localTrack.at(partialTick, out)
        }
        val remote = remoteHolds.get(player.id) ?: return false
        return remote.track.at(partialTick, out)
    }

    /** Eases the remote grips, drops stale holds, and turns each holder's body to its look. */
    private fun tickRemoteHolds(level: ClientLevel) {
        if (remoteHolds.isEmpty()) return
        val iterator = remoteHolds.int2ObjectEntrySet().fastIterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val remote = entry.value
            val holder = level.getEntity(entry.intKey) as? Player
            if (holder == null || holder.isRemoved || level.gameTime - remote.lastPacket > REMOTE_HOLD_EXPIRE_TICKS) {
                iterator.remove()
                continue
            }
            val gap = remote.grip - remote.shownGrip
            remote.shownGrip = if (abs(gap) > REMOTE_GRIP_SNAP) remote.grip else remote.shownGrip + gap.coerceIn(-REMOTE_GRIP_STEP, REMOTE_GRIP_STEP)
            val point = trackScratch
            if (clientVineCollider.gripPoint(remote.chainId, remote.shownGrip, point)) remote.track.push(point.x, point.y, point.z)
            holder.faceLook()
        }
    }

    /** The hang faces the body where the player looks; vanilla lets the body lag the head by up to 50 degrees. */
    private fun Player.faceLook() {
        yBodyRot = yHeadRot
    }

    fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        mc.level?.let { tickRemoteHolds(it) }
        val hold = clientVineHold
        val wantsHold = mc.options.keyUse.isDown && mc.screen == null && player.canHoldVine()
        if (!mc.options.keyUse.isDown) waitForRelease = false
        hold.tickGrace(clientVineCollider, player.boundingBox)
        if (reeling) tickReel(mc, player)
        val useDown = mc.options.keyUse.isDown
        if (useDown && !debugUseDown) {
            LOG.info(
                "[VineReelDebug] client press: held={} wantsHold={} waitForRelease={} shift={} canHold={} mainHand={} collider={} pick={} reeling={}",
                hold.isHeld, wantsHold, waitForRelease, player.isShiftKeyDown, player.canHoldVine(), player.mainHandItem,
                !clientVineCollider.isEmpty, pickVine(mc, player), reeling,
            )
        }
        debugUseDown = useDown
        if (debugReleaseTicks > 0) {
            LOG.info("[VineHoldDebug] client after let go ({} left): motion={} b/t pos={}", debugReleaseTicks, player.deltaMovement, player.position())
            debugReleaseTicks--
        }
        when {
            hold.isHeld && (!wantsHold || hold.lost) -> letGo(player, true)
            hold.isHeld -> steer(player)
            wantsHold && !waitForRelease && player.isShiftKeyDown -> tryReel(mc, player)
            wantsHold && !waitForRelease -> tryGrab(mc, player)
        }
        if (hold.isHeld && !hold.lost) {
            player.faceLook()
            // hangFromVine set hold.position in this tick's player move.
            localTrack.push(hold.position.x, hold.position.y, hold.position.z)
        } else {
            localTrack.invalidate()
        }
    }

    /** For the use-key mixin: true while the player holds a vine, or when the use key would grab one. */
    fun takesUseKey(): Boolean {
        if (clientVineHold.isHeld) return true
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        // A held sneak-use press that reels still takes the key, so its repeats do not use the block behind.
        val shortening = waitForRelease && player.isShiftKeyDown
        return (!waitForRelease || shortening) && player.canHoldVine() && pickVine(mc, player)
    }

    /** The vine node under the crosshair into [grab]; a block or entity nearer than the vine takes the use key. */
    private fun pickVine(mc: Minecraft, player: LocalPlayer): Boolean {
        if (clientVineCollider.isEmpty) return false
        val eye = player.eyePosition
        val look = player.getViewVector(1f)
        origin.set(eye.x, eye.y, eye.z)
        direction.set(look.x, look.y, look.z)
        val hit = mc.hitResult
        val reach = if (hit != null && hit.type != HitResult.Type.MISS) min(PICK_REACH, hit.location.distanceTo(eye)) else PICK_REACH
        return clientVineCollider.pick(origin, direction, reach, grab)
    }

    /** A sneak-use press at a vine starts a reel on the server; the server picks the vine. */
    private fun tryReel(mc: Minecraft, player: LocalPlayer) {
        if (!pickVine(mc, player)) {
            LOG.info("[VineReelDebug] client tryReel: no client vine pick")
            return
        }
        LOG.info("[VineReelDebug] client tryReel: pick chain={} node={}", grab.chainId, grab.node)
        waitForRelease = true
        reeling = true
        player.swing(InteractionHand.MAIN_HAND)
        sendReel(true)
    }

    /** Stops the reel when either key goes up or the hand fills; else swings again, as a fishing reel does. */
    private fun tickReel(mc: Minecraft, player: LocalPlayer) {
        if (!mc.options.keyUse.isDown || !player.isShiftKeyDown || mc.screen != null || !player.canHoldVine()) {
            LOG.info("[VineReelDebug] client tickReel: stop use={} shift={}", mc.options.keyUse.isDown, player.isShiftKeyDown)
            reeling = false
            sendReel(false)
            return
        }
        if (!player.swinging) player.swing(InteractionHand.MAIN_HAND)
    }

    private fun sendReel(start: Boolean) {
        LOG.info("[VineReelDebug] client send WOGGAK_VINE_SHORTEN start={}", start)
        NetworkManager.sendToServer(WOGGAK_VINE_SHORTEN, FriendlyByteBuf(Unpooled.buffer()).apply { writeBoolean(start) })
    }

    private fun tryGrab(mc: Minecraft, player: LocalPlayer) {
        if (!pickVine(mc, player)) return
        val hold = clientVineHold
        hold.isHeld = true
        hold.lost = false
        hold.chainId = grab.chainId
        hold.startGrip(grab.node)
        hold.input = player.holdInput()
        NetworkManager.sendToServer(WOGGAK_VINE_HOLD, hold.toPacket(true, player.deltaMovement))
    }

    /**
     * Moves the shown grip, and sends the held keys when they change. The server moves the grip from the keys and the
     * look, which vanilla already sends each tick.
     */
    private fun steer(player: LocalPlayer) {
        val hold = clientVineHold
        val input = player.holdInput()
        hold.stepShownGrip((input and (CLIMB_MASK or MOVE_MASK)) != 0, clientVineCollider.nodeCount(hold.chainId))
        if (input == hold.input) return
        hold.resetGripRate()
        hold.input = input
        NetworkManager.sendToServer(WOGGAK_VINE_HOLD, hold.toPacket(false, player.deltaMovement))
    }

    /**
     * Lets go and keeps the hold point's velocity, so a swing can fling the player. After a [mantle] the server puts the
     * player on top of the vine, so the vine stays solid for it.
     */
    private fun letGo(player: LocalPlayer, tellServer: Boolean, mantle: Boolean = false) {
        val hold = clientVineHold
        if (!hold.isHeld) return
        if (!mantle && !hold.lost && hold.gripState()) {
            player.takeNodeVelocity(hold.velocity)
            if (hold.velocity.lengthSquared() >= RELEASE_MIN_SPEED * RELEASE_MIN_SPEED) player.boostRelease(hold.velocity.y)
        }
        LOG.info(
            "[VineHoldDebug] client let go: tellServer={} mantle={} lost={} grip={} node velocity={} m/s, motion={} b/t",
            tellServer, mantle, hold.lost, hold.grip, hold.velocity, player.deltaMovement,
        )
        debugReleaseTicks = DEBUG_RELEASE_TICKS
        // A mantle stands on the vine at once; any other let-go passes through it for a grace time.
        if (mantle) hold.endGrace(hold.chainId) else hold.startGrace(hold.chainId)
        hold.clear()
        if (tellServer) NetworkManager.sendToServer(WOGGAK_VINE_LET_GO, FriendlyByteBuf(Unpooled.buffer()))
    }

    /** The swing fling is more than 1:1 by design; the player owns its motion, so the server takes the boost from the moves. */
    private fun LocalPlayer.boostRelease(nodeVelocityY: Double) {
        val motion = deltaMovement
        val lift = if (nodeVelocityY >= -RELEASE_LEVEL_FALL) RELEASE_LIFT else 0.0
        setDeltaMovement(motion.x * RELEASE_BOOST, motion.y * RELEASE_BOOST + lift, motion.z * RELEASE_BOOST)
    }

    private fun LocalPlayer.holdInput(): Int =
        (if (input.up) MOVE_FORWARD else 0) or (if (input.down) MOVE_BACK else 0) or
            (if (input.left) MOVE_LEFT else 0) or (if (input.right) MOVE_RIGHT else 0) or
            (if (input.jumping) CLIMB_UP else if (input.shiftKeyDown) CLIMB_DOWN else 0)
}
