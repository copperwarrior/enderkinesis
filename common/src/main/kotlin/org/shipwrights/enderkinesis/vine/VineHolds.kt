package org.shipwrights.enderkinesis.vine

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.RelativeMovement
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.util.gravity
import org.shipwrights.enderkinesis.util.hitboxMass
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.particles.ParticleId
import org.valkyrienskies.core.api.particles.VsParticleCreateData
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** C2S on a grab or a change of input: grab flag, chain id, node, input bits, player velocity in m/s. */
val WOGGAK_VINE_HOLD: ResourceLocation = EnderkinesisMod.id("woggak_vine/hold")
/** C2S when the player lets go; S2C with the chain id and a mantle flag when the server ends the hold. */
val WOGGAK_VINE_LET_GO: ResourceLocation = EnderkinesisMod.id("woggak_vine/let_go")
/** S2C when the server moves a grip: chain id, the chain id before a knot hop, grip. */
val WOGGAK_VINE_GRIP: ResourceLocation = EnderkinesisMod.id("woggak_vine/grip")
/** S2C to the players that track a holder: entity id, held flag, then chain id and grip when held. */
val WOGGAK_VINE_HOLDER: ResourceLocation = EnderkinesisMod.id("woggak_vine/holder")

const val MOVE_FORWARD = 1
const val MOVE_BACK = 2
const val MOVE_LEFT = 4
const val MOVE_RIGHT = 8
const val MOVE_MASK = MOVE_FORWARD or MOVE_BACK or MOVE_LEFT or MOVE_RIGHT
/** The jump key: climbs up a steep vine, mantles onto a flat one. */
const val CLIMB_UP = 16
/** The sneak key: slides down a steep vine, lets go of a flat one. */
const val CLIMB_DOWN = 32
const val CLIMB_MASK = CLIMB_UP or CLIMB_DOWN

// Vanilla ladder (LivingEntity.travel and handleOnClimbable): a climb sets y to 0.2, then gravity 0.08 and drag 0.98
// give 0.1176 blocks per tick; a slide clamps the fall to 0.15 blocks per tick.
private const val CLIMB_UP_SPEED = (0.2 - 0.08) * 0.98
private const val CLIMB_DOWN_SPEED = 0.15
/** Shown grip catch-up: a multiple of the climb rate, plus a minimum step in node units per tick. */
private const val GRIP_CATCH_UP = 1.5
private const val MIN_GRIP_STEP = 0.05
/** Past this gap in node units the shown grip jumps to the server grip. */
private const val GRIP_SNAP_GAP = 2.0
/** A late grip packet is extrapolated up to one tick past its expected time. */
private const val MAX_EXTRAPOLATE_TICKS = 2
/** A longer gap between grip packets is a new climb, not a slow one. */
private const val MAX_RATE_TICKS = 3
/** How much the look turns the jump or sneak climb direction on a steep vine that leans. */
private const val CLIMB_LOOK_SHARE = 0.25

/** Added to [PICK_REACH] on the server, because its chain snapshot can differ from the client's. */
private const val GRAB_SLACK = 1.0
/**
 * Farther than this from the hang point, in blocks, the hold ends. A grab reaches [PICK_REACH] + [GRAB_SLACK] from the
 * eye, and the client's move to the node can come a tick after the grab.
 */
private const val HOLD_MAX_GAP = 8.0
/** The swing pump as a share of the player's weight. */
private const val PUMP_SHARE = 0.15
private const val HOLD_PARTICLE_RADIUS = 0.1
/** userTag of a hold particle ("WogHold"), so a level load can delete the ones that a crash left behind. */
private const val HOLD_PARTICLE_TAG = 0x576F67486F6C64L
/** Grab speed cap in m/s against a bad client; vanilla terminal fall speed is about 78 m/s. */
private const val MAX_GRAB_SPEED = 80.0
/** Food exhaustion per tick of hanging: about one food point in 80 s. */
private const val HANG_EXHAUSTION = 0.0025f
/** During a climb the trackers get the grip at each node and at least this often. */
private const val CLIMB_SYNC_TICKS = 4
/** A held grip is sent again this often, for players that start to track the holder late. */
private const val HOLDER_RESEND_TICKS = 40
private const val NANOS_TO_SECONDS = 1e-9
/** Two ticks: a late chain frame does not carry the hang far on its old velocity. */
private const val MAX_GRIP_LEAD_SECONDS = 0.1
/** A walk-key move keeps its node direction while the wish stays within about 30 degrees of the wish that chose it. */
private const val WISH_KEEP_COS = 0.866
private const val MIN_WISH = 1e-6
/** A mantle sweep that ends short by more than this, in blocks, is blocked. */
private const val MANTLE_SWEEP_SLACK = 1e-3
private const val MANTLE_PROBE_DEFLATE = 1e-5
/** A walk-key wish that the held vine does not take moves the grip to a knot this near, in blocks, when a way out of it does. */
private const val KNOT_REACH = 0.6
/** Jump and sneak climbs take any way out of a knot: the up or down wish decides it. */
private const val CLIMB_MIN_COS = -1.0

/**
 * A server-side hold: the player's mass is [particle], joined to the point at [grip] of [chain] by [pair].
 * [climbDir] is the node direction of the current climb or walk-key move; 0 until the next tick chooses it.
 * A [thrown] hold came from a vine throw; the vine items can stay in the main hand.
 */
internal class VineHold(
    var chain: ChainId, var grip: Double, var input: Int, val particle: ParticleId, var pair: JointPair, val thrown: Boolean = false,
) {
    var climbDir = 0
    /** The unit wish that chose [climbDir] for a walk-key move. */
    var wishX = 0.0
    var wishY = 0.0
    var wishZ = 0.0
    /** Input bits pressed since the last tick, so a sneak held from the grab does not drop the player. */
    var pressed = 0
    /** True when the grip moved in the last tick: a stop then sends the still grip, so clients stop their lead. */
    var moving = false
    val split = WishSplit()
    val mantle = Vector3d()
    val ways = JunctionWays()
    /** Node positions of a joined vine at a knot. */
    var otherNodes = DoubleArray(0)
    /** The chain before the last knot hop: input packets that the client sent before it heard of the hop name it. */
    var previousChain: ChainId? = null
    var syncedNode = -1
    var syncedTick = 0L
}

/** Player ticks that a vine stays passable after the player leaves it: 0.5 s, so a climb back and forth does not snag. */
internal const val VINE_PASS_GRACE_TICKS = 10
/** Vines in grace at one time; a full list drops the oldest. */
private const val MAX_GRACE_VINES = 8
private const val UNTIL_LANDED = -1

/** The local player's hold, and the vines it passes through. Client thread only. */
class ClientVineHold : VinePass {
    var isHeld = false
    var chainId = 0L
    /** The shown place along the vine in node units; it follows [serverGrip] smoothly. */
    var grip = 0.0
        private set
    private var serverGrip = 0.0
    /** Server grip change in node units per tick, from the last two grip packets. */
    private var gripRate = 0.0
    private var ticksSincePacket = 0
    var input = 0
    /** Set by the move when the held node is gone, so the client tick lets go. */
    var lost = false
    // Vines just left: each stays passable for its grace ticks, and after that until the box is clear of it.
    private val graceChains = LongArray(MAX_GRACE_VINES)
    private val graceTicks = IntArray(MAX_GRACE_VINES)
    private var graceCount = 0
    val position = Vector3d()
    val velocity = Vector3d()
    private val nextPosition = Vector3d()
    private val nextVelocity = Vector3d()
    internal val hang = VineHangPlacer()

    fun startGrip(node: Int) {
        hang.reset()
        grip = node.toDouble()
        serverGrip = grip
        gripRate = 0.0
        ticksSincePacket = 0
    }

    /** A knot hop moved the hold onto [chain] at [value]: the grip jumps, as the chain index changes. */
    fun switchChain(chain: Long, value: Double) {
        startGrace(chainId)
        chainId = chain
        grip = value
        serverGrip = value
        gripRate = 0.0
        ticksSincePacket = 0
    }

    fun takeServerGrip(value: Double) {
        if (ticksSincePacket > 0) gripRate = (value - serverGrip) / (if (ticksSincePacket <= MAX_RATE_TICKS) ticksSincePacket else 1)
        serverGrip = value
        ticksSincePacket = 0
    }

    /** A climb key changed, so the old rate can point the wrong way. */
    fun resetGripRate() {
        gripRate = 0.0
    }

    /**
     * Moves the shown grip one tick toward the server grip, extrapolated at the last rate while [climbing]. Packet
     * jitter then does not show; vanilla draws the player between ticks.
     */
    fun stepShownGrip(climbing: Boolean, nodeCount: Int) {
        if (nodeCount < 1) return
        if (ticksSincePacket < MAX_RATE_TICKS + 1) ticksSincePacket++
        val ahead = if (climbing) gripRate * minOf(ticksSincePacket, MAX_EXTRAPOLATE_TICKS) else 0.0
        val target = (serverGrip + ahead).coerceIn(0.0, (nodeCount - 1).toDouble())
        val gap = target - grip
        val maxStep = abs(gripRate) * GRIP_CATCH_UP + MIN_GRIP_STEP
        grip = if (abs(gap) > GRIP_SNAP_GAP) target else grip + gap.coerceIn(-maxStep, maxStep)
    }

    /**
     * Moves [position] on by [velocity] over the age of the chain frame. VS2 applies chain frames off the client tick,
     * so the newest frame alone moves the hang by zero, one or two frame steps a tick, which shows as a stutter.
     */
    fun leadGrip() {
        val age = clientVineCollider.frameAgeNanos(chainId, System.nanoTime()) * NANOS_TO_SECONDS
        position.fma(age.coerceAtMost(MAX_GRIP_LEAD_SECONDS), velocity)
    }

    /** The grip point and its velocity into [position] and [velocity]; false when a node of it is gone. */
    fun gripState(): Boolean {
        val node = floor(grip).toInt()
        if (!clientVineCollider.nodeState(chainId, node, position, velocity)) return false
        val t = grip - node
        if (t <= 0.0) return true
        if (!clientVineCollider.nodeState(chainId, node + 1, nextPosition, nextVelocity)) return false
        position.lerp(nextPosition, t)
        velocity.lerp(nextVelocity, t)
        return true
    }

    fun clear() {
        isHeld = false
        lost = false
        input = 0
    }

    /** True when the player passes through some vine now. */
    val passesAny: Boolean get() = isHeld || graceCount > 0

    override fun passes(chainId: Long): Boolean {
        if (isHeld && chainId == this.chainId) return true
        for (i in 0 until graceCount) if (graceChains[i] == chainId) return true
        return false
    }

    /** Makes vine [chain] passable for [VINE_PASS_GRACE_TICKS]. */
    fun startGrace(chain: Long) = addGrace(chain, VINE_PASS_GRACE_TICKS)

    /** Makes vine [chain] passable until [startGrace] or [endGrace] for it, as for a throw in flight. */
    fun passUntilLanded(chain: Long) = addGrace(chain, UNTIL_LANDED)

    private fun addGrace(chain: Long, ticks: Int) {
        endGrace(chain)
        if (graceCount == MAX_GRACE_VINES) removeGraceAt(0)
        graceChains[graceCount] = chain
        graceTicks[graceCount] = ticks
        graceCount++
    }

    /** Makes vine [chain] solid again at once, as for a mantle onto its top. */
    fun endGrace(chain: Long) {
        for (i in 0 until graceCount) if (graceChains[i] == chain) { removeGraceAt(i); return }
    }

    /** One player tick of grace; a vine whose grace is over stays passable while [box] touches it in [collider]. */
    fun tickGrace(collider: VineCollider, box: AABB) {
        var i = 0
        while (i < graceCount) {
            if (graceTicks[i] == UNTIL_LANDED) { i++; continue }
            if (graceTicks[i] > 0) graceTicks[i]--
            if (graceTicks[i] <= 0 && !collider.touchesVine(graceChains[i], box)) removeGraceAt(i) else i++
        }
    }

    fun clearGrace() {
        graceCount = 0
    }

    private fun removeGraceAt(i: Int) {
        for (j in i until graceCount - 1) {
            graceChains[j] = graceChains[j + 1]
            graceTicks[j] = graceTicks[j + 1]
        }
        graceCount--
    }
}

val clientVineHold = ClientVineHold()

/** C2S hold packet from the client's current hold and the player's [motion] in blocks per tick. */
fun ClientVineHold.toPacket(grab: Boolean, motion: Vec3): FriendlyByteBuf = FriendlyByteBuf(Unpooled.buffer()).apply {
    writeBoolean(grab)
    writeLong(chainId)
    writeVarInt(floor(grip).toInt())
    writeByte(input)
    writeDouble(motion.x / SECONDS_PER_TICK)
    writeDouble(motion.y / SECONDS_PER_TICK)
    writeDouble(motion.z / SECONDS_PER_TICK)
}

/**
 * Instead of the local player's own move while it holds a vine: moves it to hang from the grip point, and gives it
 * the point's velocity. Returns false when it holds nothing, so the vanilla move runs.
 */
fun Entity.hangFromVine(): Boolean {
    if (this !is Player || !isLocalPlayer || !level().isClientSide) return false
    val hold = clientVineHold
    if (!hold.isHeld || hold.lost) return false
    if (!hold.gripState()) {
        hold.lost = true
        return false
    }
    hold.leadGrip()
    if (!hold.hang.place(this, hold.chainId, hold.grip, hold.position)) {
        hold.lost = true
        return false
    }
    val level = level()
    val box = boundingBox
    val feet = hold.hang.feet
    // No vine shapes in this sweep, so the held vine cannot push the body off its hang place.
    val delta = Vec3(feet.x - x, feet.y - y, feet.z - z)
    // VS2 adds ship collision only in its Entity.move wrap, and the hang skips that move.
    val shipDelta = EntityShipCollisionUtils.adjustEntityMovementForShipCollisions(this, delta, box, level)
    val moved = Entity.collideBoundingBox(this, shipDelta, box, level, level.getEntityCollisions(this, box.expandTowards(shipDelta)))
    setPos(x + moved.x, y + moved.y, z + moved.z)
    takeNodeVelocity(hold.velocity)
    resetFallDistance()
    setOnGround(false)
    return true
}

/** Sets the move of this tick from a node velocity in blocks per second. */
internal fun Entity.takeNodeVelocity(velocity: Vector3dc) =
    setDeltaMovement(velocity.x() * SECONDS_PER_TICK, velocity.y() * SECONDS_PER_TICK, velocity.z() * SECONDS_PER_TICK)

/** True when [player] holds a vine in this level; the server floating check counts it as support. */
internal fun WoggakVineData.isHolding(player: Player): Boolean = holds.containsKey(player.uuid)

/** True on the server and on the holder's own client while this player hangs from a vine. */
fun Player.isHangingFromVine(): Boolean {
    val level = level()
    if (level is ServerLevel) return level.loadedWoggakVines?.isHolding(this) == true
    return level.isClientSide && isLocalPlayer && clientVineHold.isHeld && !clientVineHold.lost
}

internal fun registerVineHolds() {
    NetworkManager.registerReceiver(NetworkManager.Side.C2S, WOGGAK_VINE_HOLD) { buf, ctx ->
        val grab = buf.readBoolean()
        val chain = buf.readLong()
        val node = buf.readVarInt()
        val input = buf.readByte().toInt() and (MOVE_MASK or CLIMB_MASK)
        val velocity = grabVelocity(buf.readDouble(), buf.readDouble(), buf.readDouble())
        ctx.queue { (ctx.player as? ServerPlayer)?.holdVine(grab, ChainId(chain), node, input, velocity) }
    }
    NetworkManager.registerReceiver(NetworkManager.Side.C2S, WOGGAK_VINE_LET_GO) { _, ctx ->
        ctx.queue { (ctx.player as? ServerPlayer)?.letGoOfVine(false) }
    }
    NetworkManager.registerReceiver(NetworkManager.Side.C2S, WOGGAK_VINE_SHORTEN) { buf, ctx ->
        val start = buf.readBoolean()
        ctx.queue { (ctx.player as? ServerPlayer)?.reelVine(start) }
    }
}

/** The client's velocity, capped at [MAX_GRAB_SPEED]; zero when it is not finite. */
private fun grabVelocity(x: Double, y: Double, z: Double): Vector3d {
    val velocity = Vector3d(x, y, z)
    if (!velocity.isFinite) return velocity.zero()
    val speed = velocity.length()
    if (speed > MAX_GRAB_SPEED) velocity.mul(MAX_GRAB_SPEED / speed)
    return velocity
}

/** Ends this player's hold in its level; [tellClient] when the client did not ask for it. */
@OptIn(GameTickOnly::class)
internal fun ServerPlayer.letGoOfVine(tellClient: Boolean) {
    val level = serverLevel()
    val data = level.loadedWoggakVines ?: return
    val hold = data.holds.remove(uuid) ?: return
    level.deleteHold(data, hold)
    broadcastHolder(null)
    if (tellClient) sendLetGo(hold.chain)
}

/** Tells the players that track this player its hold, or no hold when [hold] is null. */
private fun ServerPlayer.broadcastHolder(hold: VineHold?) {
    val buf = FriendlyByteBuf(Unpooled.buffer()).apply {
        writeVarInt(id)
        writeBoolean(hold != null)
        if (hold != null) {
            writeLong(hold.chain.value)
            writeDouble(hold.grip)
        }
    }
    serverLevel().chunkSource.broadcast(this, NetworkManager.toPacket(NetworkManager.Side.S2C, WOGGAK_VINE_HOLDER, buf))
}

private fun ServerPlayer.syncHolder(hold: VineHold) {
    hold.syncedNode = floor(hold.grip).toInt()
    hold.syncedTick = serverLevel().gameTime
    broadcastHolder(hold)
}

/**
 * The client ignores a let-go for a chain that it does not hold, so a stale one cannot end a newer hold. A [mantle]
 * let-go keeps the vine solid under the player, as it then stands on the vine.
 */
private fun ServerPlayer.sendLetGo(chain: ChainId, mantle: Boolean = false) =
    NetworkManager.sendToPlayer(this, WOGGAK_VINE_LET_GO, FriendlyByteBuf(Unpooled.buffer()).apply {
        writeLong(chain.value)
        writeBoolean(mantle)
    })

@OptIn(GameTickOnly::class)
private fun ServerLevel.deleteHold(data: WoggakVineData, hold: VineHold) {
    data.jointsDirty = true
    if (!shipObjectWorld.deleteParticle(hold.particle)) LOG.warn("Woggak Vine hold particle {} was already removed", hold.particle.value)
}

/** Starts a hold when [grab], else takes the new input of the current hold; the server moves the grip. */
@OptIn(GameTickOnly::class)
private fun ServerPlayer.holdVine(grab: Boolean, chainId: ChainId, node: Int, input: Int, velocity: Vector3dc) {
    val level = serverLevel()
    val data = level.loadedWoggakVines
    if (data == null) { if (grab) sendLetGo(chainId); return }
    val held = data.holds[uuid]
    if (!grab) {
        // A hold that the server ended already sent its let-go.
        if (held == null || (held.chain != chainId && held.previousChain != chainId)) return
        if (input != held.input) held.climbDir = 0
        held.pressed = held.pressed or (input and held.input.inv())
        held.input = input
        return
    }
    if (held != null) letGoOfVine(false)
    val hold = level.grab(this, data, chainId, node, input, velocity)
    if (hold == null) {
        sendLetGo(chainId)
    } else {
        data.holds[uuid] = hold
        syncHolder(hold)
    }
}

/** Starts the thrower's hold on [node] of the thrown vine [chainId] when its tip sticks, and tells its client. */
@OptIn(GameTickOnly::class)
internal fun ServerPlayer.holdThrownVine(chainId: ChainId, node: Int) {
    val level = serverLevel()
    val data = level.loadedWoggakVines ?: return
    if (data.holds.containsKey(uuid)) letGoOfVine(true)
    val motion = deltaMovement
    val velocity = grabVelocity(motion.x / SECONDS_PER_TICK, motion.y / SECONDS_PER_TICK, motion.z / SECONDS_PER_TICK)
    val hold = level.grab(this, data, chainId, node, 0, velocity, thrown = true) ?: return
    data.holds[uuid] = hold
    syncHolder(hold)
    sendThrownVine(chainId, node)
}

/**
 * A new hold on [node] of [chainId], or null when the player cannot hold it. The player's mass starts at its own
 * [velocity], so the joint gives the catch to the vine.
 */
@OptIn(GameTickOnly::class)
@Suppress("LongParameterList") // The thrown flag only relaxes the empty-hand rule.
private fun ServerLevel.grab(
    player: ServerPlayer,
    data: WoggakVineData,
    chainId: ChainId,
    node: Int,
    input: Int,
    velocity: Vector3dc,
    thrown: Boolean = false,
): VineHold? {
    if (!player.canHoldVine(thrown) || !data.holdsUnfaded(chainId)) return null
    val chain = liveChain(chainId, node) ?: return null
    val position = nodePosition(data, chain, node) ?: return null
    if (position.distance(player.eyePosition.x, player.eyePosition.y, player.eyePosition.z) > PICK_REACH + GRAB_SLACK) return null
    val ids = chain.nodeIds
    if (node !in ids.indices) return null
    val world = shipObjectWorld
    val particle = try {
        world.createParticle(VsParticleCreateData(
            dimensionId = dimensionId,
            position = position,
            velocity = velocity,
            radius = HOLD_PARTICLE_RADIUS,
            mass = player.hitboxMass(),
            collisionMask = NO_CONTACTS,
            userTag = HOLD_PARTICLE_TAG,
        ))
    } catch (e: UnsupportedOperationException) {
        LOG.warn("Woggak Vine: particles are not available", e)
        return null
    }
    data.jointsDirty = true
    val grip = node.toDouble()
    return VineHold(chainId, grip, input, particle.id, gripPair(ids, particle.id, grip), thrown)
}

/** A joint pair from [particle] to the point at [grip] on the vine with node [ids]. */
private fun gripPair(ids: List<ParticleId>, particle: ParticleId, grip: Double): JointPair {
    require(ids.isNotEmpty()) { "a vine has at least one node" }
    val link = gripLink(grip, ids.size)
    return JointPair(particle, ids[link], ids.getOrNull(link + 1)).also { it.weight = grip - link }
}

/** The chain when it is active, has no broken link and has [node]; else null. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.liveChain(chainId: ChainId, node: Int): ServerChain? {
    val chain = shipObjectWorld.allChains.getById(chainId) ?: return null
    val status = chain.status
    if (status.lifecycle != ChainLifecycle.ACTIVE || status.brokenLinks.isNotEmpty() || node !in 0 until status.nodeCount) return null
    return chain
}

/** Copies the node positions of [chain] into [WoggakVineData.scratch]; false when the copy is short. */
private fun WoggakVineData.copyNodes(chain: ServerChain): Boolean {
    val nodes = chain.status.nodeCount
    if (scratch.size < nodes * 3) scratch = DoubleArray(nodes * 3)
    return chain.copyNodePositions(scratch) == nodes
}

private fun nodePosition(data: WoggakVineData, chain: ServerChain, node: Int): Vector3d? {
    if (!data.copyNodes(chain)) return null
    val k = node * 3
    return Vector3d(data.scratch[k], data.scratch[k + 1], data.scratch[k + 2])
}

/** A [thrown] hold keeps the vine items of a creative or off-hand throw in the main hand. */
internal fun Player.canHoldVine(thrown: Boolean = false): Boolean =
    isAlive && !isSpectator && !isPassenger && (thrown || mainHandItem.isEmpty)

/** True when [chainId] is a vine that does not fade; a fading vine has no anchor, so its holder falls with it. */
private fun WoggakVineData.holdsUnfaded(chainId: ChainId): Boolean = vines.any { it.chain == chainId && it.fadeStart < 0 }

/** Ends the holds that can no longer hold, keeps the holders from fall damage, tires them, climbs and pumps the swings. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.tickVineHolds(data: WoggakVineData) {
    if (!data.strayHoldsDeleted) {
        data.strayHoldsDeleted = true
        deleteStrayHoldParticles()
    }
    if (data.holds.isEmpty()) return
    val iterator = data.holds.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        val hold = entry.value
        val player = getPlayerByUUID(entry.key) as? ServerPlayer
        val nodes = if (player == null) 0 else heldNodeCount(data, player, hold)
        if (player == null || nodes == 0) {
            iterator.remove()
            deleteHold(data, hold)
            player?.broadcastHolder(null)
            // A player that left this level is not in getPlayerByUUID, but its client still holds.
            (player ?: server.playerList.getPlayer(entry.key))?.sendLetGo(hold.chain)
            continue
        }
        player.resetFallDistance()
        // Vanilla skips exhaustion for invulnerable (creative and spectator) players.
        player.causeFoodExhaustion(HANG_EXHAUSTION)
        val step = steerHold(data, player, hold, nodes)
        if (step != HoldStep.KEEP) {
            iterator.remove()
            deleteHold(data, hold)
            player.broadcastHolder(null)
            player.sendLetGo(hold.chain, step == HoldStep.MANTLE)
            if (step == HoldStep.MANTLE) player.mantleTo(hold.mantle)
            continue
        }
        if (gameTime - hold.syncedTick >= HOLDER_RESEND_TICKS) player.syncHolder(hold)
    }
}

/** The node count of the held vine, with its nodes in [WoggakVineData.scratch]; 0 when the hold ends. */
private fun ServerLevel.heldNodeCount(data: WoggakVineData, player: ServerPlayer, hold: VineHold): Int {
    if (!player.canHoldVine(hold.thrown) || hold.pair.broken || !data.holdsUnfaded(hold.chain)) return 0
    val chain = liveChain(hold.chain, floor(hold.grip).toInt()) ?: return 0
    if (!data.copyNodes(chain)) return 0
    val nodes = chain.status.nodeCount
    val dx = gripCoordinate(data.scratch, nodes, hold.grip, 0) - player.x
    val dy = gripCoordinate(data.scratch, nodes, hold.grip, 1) - player.bbHeight - player.y
    val dz = gripCoordinate(data.scratch, nodes, hold.grip, 2) - player.z
    // The client hangs the body up to MAX_HANG_OFFSET away from the grip.
    val maxGap = HOLD_MAX_GAP + MAX_HANG_OFFSET
    return if (dx * dx + dy * dy + dz * dz <= maxGap * maxGap) nodes else 0
}

private enum class HoldStep { KEEP, LET_GO, MANTLE }

private enum class GripStep { MOVED, STILL, LOST }

/**
 * One tick of the held keys. On a steep vine jump and sneak climb; on a flat one jump mantles and a sneak press lets
 * go. The walk keys move the grip where the vine goes along the look, and pump the swing with the rest of the wish.
 * Needs the [nodes] of the vine in [WoggakVineData.scratch].
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.steerHold(data: WoggakVineData, player: ServerPlayer, hold: VineHold, nodes: Int): HoldStep {
    val pressed = hold.pressed
    hold.pressed = 0
    val input = hold.input
    val steep = isSteepAt(data.scratch, nodes, hold.grip)
    if (!steep && (pressed and CLIMB_DOWN) != 0) return HoldStep.LET_GO
    if (!steep && (input and CLIMB_UP) != 0 && player.findMantle(data.scratch, nodes, hold.grip, hold.mantle)) return HoldStep.MANTLE
    // The walk input as a world wish: forward along the full look (pitch too), strafe along the level look-right.
    val forward = keyAxis(input, MOVE_FORWARD, MOVE_BACK)
    val right = keyAxis(input, MOVE_RIGHT, MOVE_LEFT)
    val look = player.lookAngle
    val yaw = Math.toRadians(player.yRot.toDouble())
    var wx = look.x * forward - cos(yaw) * right
    var wy = look.y * forward
    var wz = look.z * forward - sin(yaw) * right
    val length = sqrt(wx * wx + wy * wy + wz * wz)
    if (length > MIN_WISH) { wx /= length; wy /= length; wz /= length } else { wx = 0.0; wy = 0.0; wz = 0.0 }
    val climbing = steep && (input and CLIMB_MASK) != 0
    val step = when {
        climbing -> climb(data, player, hold, nodes, look)
        length > MIN_WISH -> walk(data, player, hold, nodes, wx, wy, wz)
        else -> GripStep.STILL
    }
    if (step == GripStep.LOST) return HoldStep.LET_GO
    if (step == GripStep.STILL && hold.moving) { player.syncHolder(hold); player.sendGrip(hold) }
    hold.moving = step == GripStep.MOVED
    if (climbing) pumpSwing(player, hold, wx, wz) else if (length > MIN_WISH) pumpSwing(player, hold, hold.split.restX, hold.split.restZ)
    return HoldStep.KEEP
}

/**
 * A walk-key move along the vine by the share of the unit wish ([wx], [wy], [wz]) that the vine takes. Leaves the
 * wish part to pump in [VineHold.split]: the part across the vine, or the full wish when the grip does not move.
 */
@OptIn(GameTickOnly::class)
@Suppress("LongParameterList") // Plain doubles keep the hold tick free of allocation.
private fun ServerLevel.walk(
    data: WoggakVineData, player: ServerPlayer, hold: VineHold, nodes: Int, wx: Double, wy: Double, wz: Double,
): GripStep {
    if (hold.climbDir != 0 && wx * hold.wishX + wy * hold.wishY + wz * hold.wishZ < WISH_KEEP_COS) hold.climbDir = 0
    val split = splitWish(data.scratch, nodes, hold.grip, hold.climbDir, wx, wy, wz, hold.split)
    if (split.dir == 0) return approachKnot(data, player, hold, nodes, wx, wy, wz)
    if (hold.climbDir == 0) hold.keepWay(split.dir, wx, wy, wz)
    val step = moveGrip(data, player, hold, nodes, CLIMB_UP_SPEED * split.share, wx, wy, wz, MOVE_MIN_COS)
    if (step != GripStep.MOVED) { split.restX = wx; split.restY = wy; split.restZ = wz }
    return step
}

private fun keyAxis(input: Int, plus: Int, minus: Int): Double =
    (if ((input and plus) != 0) 1.0 else 0.0) - (if ((input and minus) != 0) 1.0 else 0.0)

/** A jump or sneak climb on a steep vine, at the vanilla ladder speeds. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.climb(data: WoggakVineData, player: ServerPlayer, hold: VineHold, nodes: Int, look: Vec3): GripStep {
    val up = (hold.input and CLIMB_UP) != 0
    val wishX = look.x * CLIMB_LOOK_SHARE
    val wishY = (if (up) 1.0 else -1.0) + look.y * CLIMB_LOOK_SHARE
    val wishZ = look.z * CLIMB_LOOK_SHARE
    if (hold.climbDir == 0) hold.climbDir = climbDirection(data.scratch, nodes, hold.grip, wishX, wishY, wishZ)
    return moveGrip(data, player, hold, nodes, if (up) CLIMB_UP_SPEED else CLIMB_DOWN_SPEED, wishX, wishY, wishZ, CLIMB_MIN_COS)
}

private fun VineHold.keepWay(dir: Int, wx: Double, wy: Double, wz: Double) {
    climbDir = dir
    wishX = wx; wishY = wy; wishZ = wz
}

/**
 * Moves the grip [distance] blocks along the vine in [VineHold.climbDir], and tells the clients. Every knot node on the
 * way, and the one that the grip sits on, is a junction: the way out of it that goes most along the wish ([wx], [wy],
 * [wz]), at [minCos] or more, wins. Needs the [nodes] of the vine in [WoggakVineData.scratch].
 */
@OptIn(GameTickOnly::class)
@Suppress("LongParameterList") // Plain doubles keep the hold tick free of allocation.
private fun ServerLevel.moveGrip(
    data: WoggakVineData, player: ServerPlayer, hold: VineHold, nodes: Int, distance: Double,
    wx: Double, wy: Double, wz: Double, minCos: Double,
): GripStep {
    val on = nodeAt(hold.grip)
    if (on >= 0 && junctionWays(data, hold, nodes, on)) {
        takeJunction(data, player, hold, nodes, on, wx, wy, wz, minCos)?.let { return it }
    }
    val grip = climbAlong(data.scratch, nodes, hold.grip, hold.climbDir, distance)
    val knot = firstNodeCrossed(hold.grip, grip) { junctionWays(data, hold, nodes, it) }
    if (knot >= 0) takeJunction(data, player, hold, nodes, knot, wx, wy, wz, minCos)?.let { return it }
    return placeGrip(data, player, hold, nodes, grip)
}

/**
 * Takes the best way out of the knot at [knot], with its ways in [VineHold.ways]. Null when the current way goes on or
 * no way reaches [minCos], so the move goes on as it is.
 */
@OptIn(GameTickOnly::class)
@Suppress("LongParameterList") // Plain doubles keep the hold tick free of allocation.
private fun ServerLevel.takeJunction(
    data: WoggakVineData, player: ServerPlayer, hold: VineHold, nodes: Int, knot: Int,
    wx: Double, wy: Double, wz: Double, minCos: Double,
): GripStep? {
    val ways = hold.ways
    val way = ways.best(wx, wy, wz, minCos, hold.chain.value, hold.climbDir)
    if (way < 0) return null
    val chain = ways.chain[way]
    val dir = ways.dir[way]
    if (chain == hold.chain.value) {
        if (dir == hold.climbDir) return null
        hold.keepWay(dir, wx, wy, wz)
        return placeGrip(data, player, hold, nodes, knot.toDouble())
    }
    if (!hopKnot(data, player, hold, ChainId(chain), ways.node[way].toDouble())) return null
    hold.keepWay(dir, wx, wy, wz)
    return GripStep.MOVED
}

/**
 * When the held vine does not take the walk wish ([wx], [wy], [wz]) but a knot within [KNOT_REACH] has a way out
 * that does, moves the grip to that knot; the junction there takes the way.
 */
@OptIn(GameTickOnly::class)
@Suppress("LongParameterList") // Plain doubles keep the hold tick free of allocation.
private fun ServerLevel.approachKnot(
    data: WoggakVineData, player: ServerPlayer, hold: VineHold, nodes: Int, wx: Double, wy: Double, wz: Double,
): GripStep {
    val low = floor(hold.grip).toInt()
    val high = ceil(hold.grip).toInt()
    for (n in low..high) {
        if (n < 0 || n >= nodes || data.gripGap(nodes, hold.grip, n) > KNOT_REACH) continue
        if (!junctionWays(data, hold, nodes, n)) continue
        if (hold.ways.best(wx, wy, wz, MOVE_MIN_COS, hold.chain.value, hold.climbDir) < 0) continue
        if (nodeAt(hold.grip) == n) return takeJunction(data, player, hold, nodes, n, wx, wy, wz, MOVE_MIN_COS) ?: GripStep.STILL
        val speed = CLIMB_UP_SPEED * hold.ways.bestScore
        hold.keepWay(if (n > hold.grip) 1 else -1, wx, wy, wz)
        return moveGrip(data, player, hold, nodes, speed, wx, wy, wz, MOVE_MIN_COS)
    }
    return GripStep.STILL
}

/** Distance in blocks from the point at [grip] to [node] of the held vine in [WoggakVineData.scratch]. */
private fun WoggakVineData.gripGap(count: Int, grip: Double, node: Int): Double {
    val k = node * 3
    val dx = gripCoordinate(scratch, count, grip, 0) - scratch[k]
    val dy = gripCoordinate(scratch, count, grip, 1) - scratch[k + 1]
    val dz = gripCoordinate(scratch, count, grip, 2) - scratch[k + 2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** Puts the grip at [grip] on the held vine, moves the joint, and tells the clients. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.placeGrip(data: WoggakVineData, player: ServerPlayer, hold: VineHold, nodes: Int, grip: Double): GripStep {
    if (grip == hold.grip) return GripStep.STILL
    val link = gripLink(grip, nodes)
    if (link == gripLink(hold.grip, nodes)) {
        hold.pair.weight = grip - link
    } else {
        val chain = liveChain(hold.chain, link) ?: return GripStep.LOST
        hold.pair = gripPair(chain.nodeIds, hold.particle, grip)
        data.jointsDirty = true
    }
    hold.grip = grip
    if (floor(grip).toInt() != hold.syncedNode || gameTime - hold.syncedTick >= CLIMB_SYNC_TICKS) player.syncHolder(hold)
    player.sendGrip(hold)
    return GripStep.MOVED
}

/**
 * The feet place on top of the vine at [grip] into [out]; false when the player has no head room there. Needs the
 * [nodes] of the vine.
 */
private fun ServerPlayer.findMantle(nodes: DoubleArray, count: Int, grip: Double, out: Vector3d): Boolean {
    val gx = gripCoordinate(nodes, count, grip, 0)
    val gy = gripCoordinate(nodes, count, grip, 1)
    val gz = gripCoordinate(nodes, count, grip, 2)
    out.set(gx, mantleFeetY(gy), gz)
    return hasMantleRoom(gx, gy, gz, bbWidth / 2.0, bbHeight.toDouble()) { minX, minY, minZ, maxX, maxY, maxZ ->
        mantleSpaceFree(AABB(minX, minY, minZ, maxX, maxY, maxZ).deflate(MANTLE_PROBE_DEFLATE))
    }
}

/**
 * True when [target] is free of blocks and entities, and the pull from the hang place up to it meets no block or ship.
 * Server noCollision has no vine shapes, so the held vine under the target does not count.
 */
private fun ServerPlayer.mantleSpaceFree(target: AABB): Boolean {
    val level = level()
    if (!level.noCollision(this, target)) return false
    val from = boundingBox
    val move = Vec3(target.minX - from.minX, target.minY - from.minY, target.minZ - from.minZ)
    val slackSq = MANTLE_SWEEP_SLACK * MANTLE_SWEEP_SLACK
    val blocks = Entity.collideBoundingBox(this, move, from, level, level.getEntityCollisions(this, from.expandTowards(move)))
    if (blocks.distanceToSqr(move) > slackSq) return false
    // level.noCollision does not see VS2 ships.
    return EntityShipCollisionUtils.adjustEntityMovementForShipCollisions(this, move, from, level).distanceToSqr(move) <= slackSq
}

/** Puts the player on top of the vine; the client's let-go came first, so its hang does not move it back. */
private fun ServerPlayer.mantleTo(feet: Vector3dc) =
    connection.teleport(feet.x(), feet.y(), feet.z(), yRot, xRot, EnumSet.of(RelativeMovement.X_ROT, RelativeMovement.Y_ROT))

/** Moves the hold onto [target] at [grip]; false when that chain cannot be held, so the hold stays where it is. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.hopKnot(data: WoggakVineData, player: ServerPlayer, hold: VineHold, target: ChainId, grip: Double): Boolean {
    val chain = liveChain(target, floor(grip).toInt()) ?: return false
    hold.previousChain = hold.chain
    hold.chain = target
    hold.grip = grip
    hold.pair = gripPair(chain.nodeIds, hold.particle, grip)
    data.jointsDirty = true
    player.syncHolder(hold)
    player.sendGrip(hold)
    return true
}

/** Grip packet: the chain, the chain that the client may still hold (the one before a knot hop), and the grip. */
private fun ServerPlayer.sendGrip(hold: VineHold) =
    NetworkManager.sendToPlayer(this, WOGGAK_VINE_GRIP, FriendlyByteBuf(Unpooled.buffer()).apply {
        writeLong(hold.chain.value)
        writeLong((hold.previousChain ?: hold.chain).value)
        writeDouble(hold.grip)
    })

/** A small force on the player's mass along the level part of the unit wish ([x], [z]), so the player can build up a swing. */
private fun ServerLevel.pumpSwing(player: ServerPlayer, hold: VineHold, x: Double, z: Double) {
    if (x * x + z * z < MIN_WISH * MIN_WISH) return
    val size = player.hitboxMass() * gravity() * PUMP_SHARE
    queueVineForce(dimensionId, hold.particle, Vector3d(x * size, 0.0, z * size))
}

/** Deletes the hold particles that a save kept; a hold does not outlive its session. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.deleteStrayHoldParticles() {
    val world = shipObjectWorld
    val stray = try {
        world.allParticles.getInDimension(dimensionId).filter { it.userTag == HOLD_PARTICLE_TAG }
    } catch (e: UnsupportedOperationException) {
        LOG.debug("Woggak Vine: particles are not available", e)
        return
    }
    for (particle in stray) if (!world.deleteParticle(particle.id)) LOG.warn("Woggak Vine hold particle {} was already removed", particle.id.value)
}
