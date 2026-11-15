package org.shipwrights.enderkinesis.vine

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.physics.physicsStepSeconds
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.util.blockLoaded
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.chains.ChainEnd
import org.valkyrienskies.core.api.chains.ChainEndpoint
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ChainReelControl
import org.valkyrienskies.core.api.chains.PhysChain
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.particles.ParticleId
import org.valkyrienskies.core.api.particles.ParticleStateBuffer
import org.valkyrienskies.core.api.particles.ParticleVectorInput
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** S2C: a throw starts; each client flies the same arc. */
val WOGGAK_VINE_THROW_START: ResourceLocation = EnderkinesisMod.id("woggak_vine/throw_start")
/** S2C: a throw ended; the chain id is 0 when no vine was made. */
val WOGGAK_VINE_THROW_END: ResourceLocation = EnderkinesisMod.id("woggak_vine/throw_end")

/** Tip speed in m/s of a full bow draw. */
private const val MAX_THROW_SPEED = 40.0
/** A shorter draw than this many ticks throws nothing, as for a trident; bow power passes 0.1 in one tick, so a tap would throw. */
internal const val MIN_THROW_DRAW_TICKS = 10
/** Vanilla arrow drag and gravity per tick, so the tip flies the arc of an arrow. */
internal const val THROW_DRAG = 0.99
internal const val THROW_GRAVITY = 0.05
/** Rule 2 cap; the length ends every real flight sooner, as the fall speed goes to gravity over drag. */
internal const val MAX_FLIGHT_STEPS = 400
/** The link of a default reel seed and the shortest throw spacing; under [VINE_TARGET_SPACING], so a default seed has two links. */
internal const val FIRST_LINK = 0.39
internal const val FIRST_LINKS = 2
/** VS default: 3 physics ticks per game tick. The reel pays at most one node per physics tick. */
private const val PHYSICS_TICKS_PER_SECOND = 60.0
/** The reel credit must reach one spacing in one physics tick; a rate of exactly spacing/dt can fall a hair short. */
private const val REEL_RATE_MARGIN = 1.05
/** A throw's node spacing pays out this much faster than the tip flies, so the reel keeps up with the tip. */
private const val PAYOUT_SPEED_MARGIN = 1.1
/** Newtons on the node next to the hand while the reel pays out. */
private const val PAYOUT_REEL_FORCE = 1000.0
/** Puts a seed's create spacing over its link, so VS makes exactly the seed's links. */
private const val SEED_SPACING_MARGIN = 1.0 + 1e-6
/** Rule 2 backstop: a landed throw whose physics release never comes ends from the server after this. */
private const val MAX_SETTLE_TICKS = 100
private const val HAND_FORWARD = 0.5
private const val HAND_SIDE = 0.3
private const val HAND_DROP = 0.25
private const val LENGTH_EPS = 1e-9
/** A shorter path step than this merges into the next one, so no chain link has no length. */
private const val MIN_PATH_STEP = 1e-3
/** At a hit, each node keeps this share of its speed over the anchor, so the taut rope does not whip. */
private const val LANDING_KEEP = 0.35
/** A landed vine gets this much more rest than its node path, so it does not spring back from a stretch. */
private const val LANDING_SLACK = 0.05
/** VS gives every link rest/links; this caps the extra rest that makes the longest link unstretched too. */
private const val MAX_LANDING_SLACK = 0.25
/** Relative speed decay rate per second right after landing; it fades to 0 over [SETTLE_DAMP_SECONDS]. */
private const val SETTLE_DAMPING = 5.0
private const val SETTLE_DAMP_SECONDS = 1.0
/** Rule 2: the settle watch ends after this, settled or not. */
private const val SETTLE_WATCH_SECONDS = 3.0
/** m/s; a landed vine whose fastest node moves slower than this over the anchor counts as settled. */
private const val SETTLED_SPEED = 0.5

/**
 * The tip of a thrown vine and its path from the hand, in world coordinates; the server and each client fly it alike.
 * [velocity] is in blocks per tick.
 */
class ThrowFlight(start: Vector3dc, velocity: Vector3dc, val length: Double) {
    val tip = Vector3d(start)
    val velocity = Vector3d(velocity)
    /** Path length from the hand to the tip. */
    var flown = 0.0
        private set
    var steps = 0
        private set
    var ended = false
        private set
    /** The path points from the hand to the tip, xyz; [count] of them. */
    var points = DoubleArray(ceil(length).toInt().coerceIn(2, MAX_FLIGHT_STEPS) * 3)
        private set
    var count = 0
        private set

    init {
        require(length > 0) { "throw length must be positive" }
        add(start.x(), start.y(), start.z())
    }

    /** The next tip point into [out], cut so the path never passes [length]; then drag and gravity act. Returns the move. */
    fun advance(out: Vector3d): Double {
        peek(out)
        val move = out.distance(tip)
        velocity.mul(THROW_DRAG).sub(0.0, THROW_GRAVITY, 0.0)
        return move
    }

    /** The next tip point into [out], cut at [length], without a move. */
    fun peek(out: Vector3d) {
        out.set(tip).add(velocity)
        val move = out.distance(tip)
        val left = length - flown
        if (move > left) out.sub(tip).mul(left / move).add(tip)
    }

    /** Moves the tip [move] along the path to [point]; the flight ends on a [stop], at the full length or at the cap. */
    fun moveTo(point: Vector3dc, move: Double, stop: Boolean) {
        tip.set(point)
        flown += move
        add(point.x(), point.y(), point.z())
        ended = stop || flown >= length - LENGTH_EPS || ++steps >= MAX_FLIGHT_STEPS
    }

    /** Ends the flight where the tip is now. */
    fun end() {
        ended = true
    }

    private fun add(x: Double, y: Double, z: Double) {
        if (count > 0) {
            val k = (count - 1) * 3
            val dx = x - points[k]; val dy = y - points[k + 1]; val dz = z - points[k + 2]
            // A tiny step moves the last point, so the path keeps its exact tip; the hand point stays.
            if (dx * dx + dy * dy + dz * dz < MIN_PATH_STEP * MIN_PATH_STEP) {
                if (count > 1) { points[k] = x; points[k + 1] = y; points[k + 2] = z }
                return
            }
        }
        if (points.size < (count + 1) * 3) points = points.copyOf(points.size * 2)
        val k = count * 3
        points[k] = x; points[k + 1] = y; points[k + 2] = z
        count++
    }
}

/**
 * One tick of [flight]: the tip moves and stops at the first block on its way, leaves too. Entities do not stop it.
 * Returns the block hit, or null. A tip that comes to an unloaded chunk ends there, as a clip would load it.
 */
internal fun Level.stepFlight(flight: ThrowFlight, entity: Entity, next: Vector3d): BlockHitResult? {
    if (flight.ended) return null
    val move = flight.advance(next)
    if (!blockLoaded(BlockPos.containing(next.x, next.y, next.z))) {
        flight.end()
        return null
    }
    val from = Vec3(flight.tip.x, flight.tip.y, flight.tip.z)
    // COLLIDER, not OUTLINE: leaves have a full collision box, but grass and flowers do not hold an anchor.
    val hit = clip(ClipContext(from, Vec3(next.x, next.y, next.z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
    if (hit.type != HitResult.Type.BLOCK) {
        flight.moveTo(next, move, false)
        return null
    }
    // VS2's clip gives a world location and a shipyard block for a ship hit.
    next.set(hit.location.x, hit.location.y, hit.location.z)
    flight.moveTo(next, from.distanceTo(hit.location), true)
    return hit
}

/**
 * Where the tip of [flight] will be after the next tick, into [out]: the next arc point, or the first block on the way.
 * The physics tip goes there over this tick, so it keeps up with the virtual tip and never enters a block.
 */
internal fun Level.peekFlight(flight: ThrowFlight, entity: Entity, out: Vector3d) {
    flight.peek(out)
    if (!blockLoaded(BlockPos.containing(out.x, out.y, out.z))) return
    val hit = clip(ClipContext(Vec3(flight.tip.x, flight.tip.y, flight.tip.z), Vec3(out.x, out.y, out.z),
        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
    if (hit.type == HitResult.Type.BLOCK) out.set(hit.location.x, hit.location.y, hit.location.z)
}

/**
 * The first [links] links of [link] each of a vine that a reel at its start pays out, from [start] along the unit [dir].
 * A node of a placed vine weighs one spacing of vine; VS splits the mass over nodes, one more than links.
 */
internal class ReelSeed(
    start: Vector3dc, dir: Vector3dc, link: Double = FIRST_LINK, links: Int = FIRST_LINKS, minSpacing: Double = VINE_TARGET_SPACING,
) {
    val path: List<Vector3dc> = List(links + 1) { i -> Vector3d(dir).mul(link * i).add(start) }
    // The same sum as VS, so the reel spacing here is the one on the physics thread.
    val length = path.zipWithNext { a, b -> a.distance(b) }.sum()
    val targetSpacing = max(minSpacing, link * SEED_SPACING_MARGIN)
    val spacing = chainSpacing(length, targetSpacing)
    val massPerLength = MASS_PER_LENGTH * path.size / (path.size - 1)
}

/** Vine length in blocks of a throw of [items] vine items. */
internal fun throwLength(items: Int): Double = items * BLOCKS_PER_ITEM

/** Tip speed in m/s for a bow [power] in 0..1. */
internal fun throwSpeed(power: Float): Double = power.coerceIn(0f, 1f) * MAX_THROW_SPEED

/** Node spacing that VS gives a chain made along a path of [length]: the length over its link count. */
internal fun chainSpacing(length: Double, targetSpacing: Double): Double {
    require(length > 0 && targetSpacing > 0) { "chain length and spacing must be positive" }
    return length / ceil(length / targetSpacing).toInt().coerceAtLeast(1)
}

/** The whole [length] as a reel target: whole spacings, never under the length, so the refund pays all the items. */
internal fun fullReelTarget(length: Double, spacing: Double): Double =
    ceil(length / spacing - LENGTH_EPS).coerceAtLeast(1.0) * spacing

/** Node spacing of a throw at tip [speed] in m/s: one node per physics tick pays out faster than the tip flies. */
internal fun throwSpacing(speed: Double): Double =
    (speed / PHYSICS_TICKS_PER_SECOND * PAYOUT_SPEED_MARGIN).coerceIn(FIRST_LINK, BLOCKS_PER_ITEM)

/** The most whole spacings of a throw of [length] blocks, so the vine never uses more than the thrown items. */
internal fun maxThrowLinks(length: Double, spacing: Double): Double = floor(length / spacing + LENGTH_EPS).coerceAtLeast(1.0)

/** The reel target for a hand-to-tip [distance]: whole spacings, never over the distance, one link at least. */
internal fun throwPayoutTarget(distance: Double, spacing: Double, maxLinks: Double): Double =
    floor(distance / spacing + LENGTH_EPS).coerceIn(1.0, maxLinks) * spacing

/** Reel rate in m/s: the given speed, and at least one node per physics tick. */
internal fun throwReelRate(speed: Double, spacing: Double): Double =
    max(speed, spacing * PHYSICS_TICKS_PER_SECOND * REEL_RATE_MARGIN)

/** The hand point: in front of the eye, to the [side] (+1 right arm, -1 left arm) and down a little. */
internal fun handPoint(eye: Vec3, look: Vec3, yawDegrees: Float, side: Double): Vector3d {
    val yaw = Math.toRadians(yawDegrees.toDouble())
    return Vector3d(
        eye.x + look.x * HAND_FORWARD - cos(yaw) * side * HAND_SIDE,
        eye.y + look.y * HAND_FORWARD - HAND_DROP,
        eye.z + look.z * HAND_FORWARD - sin(yaw) * side * HAND_SIDE,
    )
}

/** +1 when [hand] is in the right arm, -1 when in the left arm. */
internal fun Player.handSide(hand: InteractionHand): Double {
    val arm = if (hand == InteractionHand.MAIN_HAND) mainArm else mainArm.opposite
    return if (arm == HumanoidArm.RIGHT) 1.0 else -1.0
}

/**
 * One game tick of a throw for the physics thread: where the tip and hand go by the end of the tick. The server makes a
 * new one each tick and never changes one. On the [ending] step the tip fixes to [anchor], or flies free when it is null.
 */
internal class ThrowStep(
    val tip: Vector3dc, val hand: Vector3dc, val velocity: Vector3dc, val ending: Boolean, val anchor: ChainEndpoint?,
)

/** The physics side of a throw. The volatile fields cross threads; the others belong to the physics thread. */
internal class ThrowDrive(val dimension: DimensionId, val chain: ChainId, val spacing: Double, val maxLinks: Double,
    val rate: Double, seedTip: Vector3dc, hand: Vector3dc) {
    @Volatile var step: ThrowStep? = null
    /** The paid rest length, set when the physics thread let go of both ends. */
    @Volatile var releasedRest = Double.NaN
    /** The first chain frame tick that shows the let-go; written before [releasedRest]. */
    @Volatile var releaseTick = 0L
    var settle: ThrowSettle? = null
    @Volatile var failure: String? = null
    @Volatile var done = false
    var seen: ThrowStep? = null
    var elapsed = 0.0
    val tipFrom = Vector3d(seedTip)
    val tipNow = Vector3d(seedTip)
    val handFrom = Vector3d(hand)
    val handNow = Vector3d(hand)
    // VS copies an endpoint's position, so one endpoint can be moved and passed again.
    val tipEnd = ChainEndpoint.World(tipNow)
    val handEnd = ChainEndpoint.World(handNow)
    var target = 0.0
    var nodeCount = 0
}

private val throwDrives = ConcurrentHashMap<ChainId, ThrowDrive>()

/** A thrown vine: a virtual tip flies the arc, and the VS chain [chain] trails it from the hand. */
internal class VineThrow(
    val id: Long, val player: UUID, val chain: ChainId, val flight: ThrowFlight, val side: Double, val items: Int,
    val creative: Boolean, val drive: ThrowDrive, val trackedFrom: ChunkPos,
) {
    val next = Vector3d()
    val lead = Vector3d()
    val hand = Vector3d(flight.tip)
    /** The block the tip hit; the vine end fixes there once the physics thread let go. */
    var contact: VinePoint? = null
    var settleTicks = 0
    /** Set once the chain was active; a split or cut after that keeps the paid vine in its pieces. */
    var wasActive = false

    fun publish(tip: Vector3dc, velocity: Vector3dc, ending: Boolean, anchor: ChainEndpoint?) {
        drive.step = ThrowStep(Vector3d(tip), Vector3d(hand), velocity, ending, anchor)
    }
}

@OptIn(VsBeta::class, PhysTickOnly::class)
internal fun registerVineThrows() {
    vsApi.physTickEvent.on { event, _ -> event.world.driveThrows(physicsStepSeconds(event.delta)) }
    LifecycleEvent.SERVER_LEVEL_UNLOAD.register { level ->
        val dimension = level.dimensionId
        throwDrives.values.removeIf { it.dimension == dimension }
    }
    LifecycleEvent.SERVER_STOPPED.register { throwDrives.clear() }
}

/** Throws the whole [stack] from [hand] at bow [power]; the vine pays out from the hand behind the flying tip. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.throwWoggakVine(player: ServerPlayer, hand: InteractionHand, stack: ItemStack, power: Float): Boolean {
    val items = stack.count
    if (items <= 0) return false
    val side = player.handSide(hand)
    val start = handPoint(player.eyePosition, player.lookAngle, player.yRot, side)
    val look = player.lookAngle
    val motion = player.deltaMovement
    val velocity = Vector3d(look.x, look.y, look.z).normalize().mul(throwSpeed(power) * SECONDS_PER_TICK).add(motion.x, motion.y, motion.z)
    val speed = velocity.length() / SECONDS_PER_TICK
    val dir = if (velocity.lengthSquared() > LENGTH_EPS) Vector3d(velocity).normalize() else Vector3d(look.x, look.y, look.z).normalize()
    val spacing = throwSpacing(speed)
    val seed = ReelSeed(start, dir, spacing, 1)
    val chain = makeVineChain(seed.path, ChainEndpoint.World(Vector3d(start)), ChainEndpoint.Free,
        Vector3d(velocity).div(SECONDS_PER_TICK), seed.massPerLength, seed.targetSpacing) ?: return false
    val creative = player.abilities.instabuild
    val flight = ThrowFlight(start, velocity, throwLength(items))
    val drive = ThrowDrive(dimensionId, chain.id, seed.spacing, maxThrowLinks(flight.length, seed.spacing),
        throwReelRate(speed, seed.spacing), seed.path.last(), start)
    val thrown = VineThrow(newVineId(), player.uuid, chain.id, flight, side, items, creative, drive,
        ChunkPos(BlockPos.containing(start.x, start.y, start.z)))
    addVine(WoggakVine(thrown.id, chain.id, WoggakVineAnchor(null), WoggakVineAnchor(null)), seed.path)
    throwDrives[chain.id] = drive
    woggakVines.throws.put(thrown.id, thrown)
    sendThrowStart(thrown, player.id, side)
    if (!creative) stack.shrink(items)
    return true
}

private fun ServerLevel.sendThrowStart(thrown: VineThrow, throwerId: Int, side: Double) {
    val flight = thrown.flight
    val buf = FriendlyByteBuf(Unpooled.buffer()).apply {
        writeLong(thrown.id)
        writeLong(thrown.chain.value)
        writeVarInt(throwerId)
        writeByte(if (side > 0) 1 else -1)
        writeDouble(flight.tip.x); writeDouble(flight.tip.y); writeDouble(flight.tip.z)
        writeDouble(flight.velocity.x); writeDouble(flight.velocity.y); writeDouble(flight.velocity.z)
        writeDouble(flight.length)
    }
    for (player in chunkSource.chunkMap.getPlayers(thrown.trackedFrom, false)) {
        NetworkManager.sendToPlayer(player, WOGGAK_VINE_THROW_START, FriendlyByteBuf(buf.copy()))
    }
}

private fun ServerLevel.sendThrowEnd(thrown: VineThrow, chain: Long) {
    for (player in chunkSource.chunkMap.getPlayers(thrown.trackedFrom, false)) {
        NetworkManager.sendToPlayer(player, WOGGAK_VINE_THROW_END, FriendlyByteBuf(Unpooled.buffer()).apply {
            writeLong(thrown.id)
            writeLong(chain)
            writeLong(thrown.drive.releaseTick)
        })
    }
}

/** One tick of each throw; a throw ends when the physics thread let go of its ends, or its chain is gone. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.tickVineThrows(data: WoggakVineData) {
    if (data.throws.isEmpty()) return
    val iterator = data.throws.values.iterator()
    while (iterator.hasNext()) {
        val thrown = iterator.next()
        if (stepThrow(data, thrown)) continue
        iterator.remove()
        // A released drive stays until the physics thread has settled the landed vine.
        if (thrown.drive.releasedRest.isNaN()) throwDrives.remove(thrown.chain)
        data.anchorsDirty = true
    }
}

/** False when the throw is over. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.stepThrow(data: WoggakVineData, thrown: VineThrow): Boolean {
    val chain = shipObjectWorld.allChains.getById(thrown.chain)
    val lifecycle = chain?.status?.lifecycle
    if (chain == null || lifecycle == ChainLifecycle.REMOVED || lifecycle == ChainLifecycle.FAILED) {
        if (!thrown.creative && !thrown.wasActive) giveBack(thrown, thrown.items)
        sendThrowEnd(thrown, 0L)
        return false
    }
    if (lifecycle == ChainLifecycle.ACTIVE) thrown.wasActive = true
    if (!thrown.flight.ended) {
        fly(thrown)
        return true
    }
    val rest = thrown.drive.releasedRest
    if (!rest.isNaN()) {
        settle(data, thrown, chain, rest)
        return false
    }
    if (thrown.drive.failure == null && ++thrown.settleTicks <= MAX_SETTLE_TICKS) return true
    LOG.warn("Woggak Vine throw {} had no physics release, failure {}; the server lets go", thrown.id, thrown.drive.failure)
    thrown.drive.done = true
    throwDrives.remove(thrown.chain)
    val paid = chain.status.currentRestLength
    chain.setEndpoint(ChainEnd.START, ChainEndpoint.Free)
    chain.setEndpoint(ChainEnd.END, thrown.contact?.let { attachment(it)?.second } ?: ChainEndpoint.Free)
    if (paid.isFinite() && paid > 0) chain.setTargetLength(paid)
    settle(data, thrown, chain, paid)
    return false
}

/** One tick of the virtual tip; its arc and hit test never read the vine. */
private fun ServerLevel.fly(thrown: VineThrow) {
    val flight = thrown.flight
    // The thrower is only the clip's collision context; a thrower that left ends the flight where it is.
    val player = getPlayerByUUID(thrown.player)
    if (player == null) flight.end()
    else thrown.hand.set(handPoint(player.eyePosition, player.lookAngle, player.yRot, thrown.side))
    val hit = player?.let { stepFlight(flight, it, thrown.next) }
    val velocity = Vector3d(flight.velocity).div(SECONDS_PER_TICK)
    if (!flight.ended && player != null) {
        peekFlight(flight, player, thrown.lead)
        thrown.publish(thrown.lead, velocity, false, null)
        return
    }
    val contact = hit?.let { blockContact(it) }
    val anchor = contact?.let { attachment(it) }
    if (contact != null && anchor != null) {
        thrown.contact = contact
        thrown.publish(anchor.first, velocity, true, anchor.second)
    } else {
        thrown.publish(flight.tip, velocity, true, null)
    }
}

/** The physics thread let go at [rest]: fix the end on a hit, pay for the vine, and give back the unused items. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.settle(data: WoggakVineData, thrown: VineThrow, chain: ServerChain, rest: Double) {
    val vine = data.vine(thrown.id)
    val contact = thrown.contact
    if (vine != null && contact != null) {
        vine.end = WoggakVineAnchor(contact)
        data.jointsDirty = true
    }
    val paid = if (rest.isFinite() && rest > 0) rest else 0.0
    val spent = if (paid > 0) min(woggakVineCost(paid), thrown.items) else 0
    if (vine != null && !thrown.creative) vine.reelCredit = validReelCredit(spent * BLOCKS_PER_ITEM - paid)
    vine?.let(data::touch)
    data.setDirty()
    val back = if (thrown.creative) 0 else thrown.items - spent
    giveBack(thrown, back)
    sendThrowEnd(thrown, chain.id.value)
}

/** Gives [count] vine items back to the thrower, or drops them at the tip when the thrower is gone. */
private fun ServerLevel.giveBack(thrown: VineThrow, count: Int) {
    if (count <= 0) return
    val item = EKItems.WOGGAK_VINE.get()
    val player = getPlayerByUUID(thrown.player)
    var left = count
    while (left > 0) {
        val n = min(left, item.maxStackSize)
        val stack = ItemStack(item, n)
        if (player != null) {
            if (!player.inventory.add(stack)) player.drop(stack, false)
        } else if (gameRules.getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            val tip = thrown.flight.tip
            addFreshEntity(ItemEntity(this, tip.x, tip.y, tip.z, stack).apply { setDefaultPickUpDelay() })
        }
        left -= n
    }
}

/** An anchor on the hit block face, snapped as a placed vine end is; VS2's clip gives a shipyard block and a world point. */
private fun ServerLevel.blockContact(hit: BlockHitResult): VinePoint? {
    val pos = hit.blockPos
    if (getBlockState(pos).getCollisionShape(this, pos).isEmpty) return null
    val ship = getShipManagingPos(pos)
    val local = Vector3d(hit.location.x, hit.location.y, hit.location.z)
    if (ship != null) ship.worldToShip.transformPosition(local)
    val snapped = snapToShapeAnchor(pos, hit.direction, Vec3(local.x, local.y, local.z))
    return VinePoint(pos, Vector3d(snapped.x, snapped.y, snapped.z), ship?.id)
}

@OptIn(PhysTickOnly::class)
private fun PhysLevel.driveThrows(dt: Double) {
    if (throwDrives.isEmpty() || !dt.isFinite() || dt <= 0.0) return
    for (drive in throwDrives.values) {
        if (drive.dimension != dimension) continue
        val settle = drive.settle
        if (settle != null) {
            if (!settleThrow(settle, dt)) throwDrives.remove(drive.chain, drive)
            continue
        }
        if (drive.done) continue
        val chain = getChainById(drive.chain) ?: continue
        try {
            driveThrow(chain, drive, dt)
        } catch (e: IllegalArgumentException) {
            drive.failure = e.message ?: e.toString()
            drive.done = true
        } catch (e: IllegalStateException) {
            drive.failure = e.message ?: e.toString()
            drive.done = true
        }
    }
}

/**
 * Pins the tip end to the virtual tip and the start end to the hand, moved along the tick's step, and pays out up to
 * the hand-to-tip distance. The tip goes to the clipped next arc point, so it keeps up with the virtual tip but never
 * goes past a hit.
 */
@OptIn(PhysTickOnly::class)
private fun PhysLevel.driveThrow(chain: PhysChain, drive: ThrowDrive, dt: Double) {
    val step = drive.step ?: return
    if (step !== drive.seen) {
        // From where the ends are now, so a late or early physics tick gives no jump.
        drive.seen = step
        drive.elapsed = 0.0
        drive.tipFrom.set(drive.tipNow)
        drive.handFrom.set(drive.handNow)
    }
    drive.elapsed += dt
    val t = min(drive.elapsed / SECONDS_PER_TICK, 1.0)
    drive.tipFrom.lerp(step.tip, t, drive.tipNow)
    drive.handFrom.lerp(step.hand, t, drive.handNow)
    val nodes = chain.nodeIds
    if (step.ending && t >= 1.0) {
        releaseThrow(chain, drive, step, nodes)
        return
    }
    chain.setEndpoint(ChainEnd.START, drive.handEnd)
    chain.setEndpoint(ChainEnd.END, drive.tipEnd)
    val target = throwPayoutTarget(drive.handNow.distance(drive.tipNow), drive.spacing, drive.maxLinks)
    if (target != drive.target) {
        chain.setReelTarget(ChainReelControl(ChainEnd.START, target, drive.rate, PAYOUT_REEL_FORCE))
        drive.target = target
    }
    // The reel makes a node at rest on the hand; it takes the speed of the node it went in before.
    if (drive.nodeCount in 2 until nodes.size && nodes.size >= 3) {
        getParticleById(nodes[2])?.let { next -> getParticleById(nodes[1])?.setVelocity(next.velocity) }
    }
    drive.nodeCount = nodes.size
}

/**
 * Lets go of the hand and fixes or frees the tip. The rest gets a little slack over the node path, within the thrown
 * items, so the stretched rope does not spring back; a hit also takes most of the node speed over the anchor.
 */
@OptIn(PhysTickOnly::class)
private fun PhysLevel.releaseThrow(chain: PhysChain, drive: ThrowDrive, step: ThrowStep, nodes: List<ParticleId>) {
    val anchor = step.anchor
    chain.setEndpoint(ChainEnd.END, anchor ?: ChainEndpoint.Free)
    if (anchor == null) nodes.lastOrNull()?.let { getParticleById(it)?.setVelocity(step.velocity) }
    chain.setEndpoint(ChainEnd.START, ChainEndpoint.Free)
    val paid = (nodes.size - 1) * drive.spacing
    var rest = paid
    if (nodes.size >= 2) {
        val settle = ThrowSettle(nodes, anchor)
        val energy = settle.read(this)
        val path = settle.pathLength()
        // No link starts stretched, so the first solver step puts no speed into the vine.
        val unstretched = max(path * (1.0 + LANDING_SLACK), settle.longestLink * (nodes.size - 1))
        rest = max(paid, minOf(unstretched, path * (1.0 + MAX_LANDING_SLACK), drive.maxLinks * drive.spacing))
        if (!energy.isNaN() && settle.still) settle.damp(this, 1.0 - LANDING_KEEP) else settle.calmPending = settle.anchored
        drive.settle = settle
    }
    chain.setTargetLength(rest)
    // The let-go acts in the next step, so frames from the one after the last published tick show it.
    drive.releaseTick = chain.status.physicsTick + 1
    drive.done = true
    drive.releasedRest = rest
}

/** One physics tick of a landed vine's calm-down; false once it is over. */
@OptIn(PhysTickOnly::class)
private fun PhysLevel.settleThrow(settle: ThrowSettle, dt: Double): Boolean {
    settle.elapsed += dt
    val energy = settle.read(this)
    if (energy.isNaN()) return false
    if (settle.settledAt.isNaN() && settle.maxSpeed < SETTLED_SPEED) settle.settledAt = settle.elapsed
    val left = 1.0 - settle.elapsed / SETTLE_DAMP_SECONDS
    if (settle.calmPending) {
        settle.calmPending = false
        settle.damp(this, 1.0 - LANDING_KEEP)
    } else if (left > 0.0) {
        settle.damp(this, 1.0 - exp(-SETTLE_DAMPING * left * dt))
    }
    val over = settle.elapsed >= SETTLE_WATCH_SECONDS || (left <= 0.0 && !settle.settledAt.isNaN())
    return !over
}

/**
 * The node states of a landed throw, read and pushed in one batch each physics tick. Speeds are taken over the
 * anchored tip's speed, or over the vine's mean speed when no end is fixed, so the vine keeps its whole motion.
 */
internal class ThrowSettle(nodes: List<ParticleId>, anchor: ChainEndpoint?) {
    val anchored = anchor != null
    /** A world anchor stands still; a body anchor's speed is its tip node's, known only a step after the pin. */
    val still = anchor is ChainEndpoint.World
    var calmPending = false
    private val ids: List<ParticleId> = ArrayList(nodes)
    private val states = ParticleStateBuffer(ids.size)
    // VS copies each impulse into its command buffer at submit, so the vectors can be set again next tick.
    private val impulses = Array(ids.size) { Vector3d() }
    private val inputs: List<ParticleVectorInput> = List(ids.size) { ParticleVectorInput(ids[it], impulses[it]) }
    private val reference = Vector3d()
    var elapsed = 0.0
    var settledAt = Double.NaN
    var maxSpeed = 0.0
        private set
    /** The longest link of the last [pathLength]. */
    var longestLink = 0.0
        private set

    /** Reads the nodes; returns their kinetic energy over the reference speed in J, or NaN when too few are live. */
    @OptIn(PhysTickOnly::class)
    fun read(level: PhysLevel): Double {
        level.copyParticleStates(ids, states)
        val v = states.velocities
        val m = states.masses
        val live = states.valid
        val last = ids.size - 1
        reference.set(0.0)
        if (anchored) {
            if (!still && live[last]) reference.set(v[last * 3], v[last * 3 + 1], v[last * 3 + 2])
        } else {
            var mass = 0.0
            for (i in ids.indices) {
                if (!usable(i)) continue
                reference.add(v[i * 3] * m[i], v[i * 3 + 1] * m[i], v[i * 3 + 2] * m[i])
                mass += m[i]
            }
            if (mass <= 0.0) return Double.NaN
            reference.div(mass)
        }
        var energy = 0.0
        var count = 0
        maxSpeed = 0.0
        for (i in ids.indices) {
            if (!usable(i)) continue
            val k = i * 3
            val dx = v[k] - reference.x; val dy = v[k + 1] - reference.y; val dz = v[k + 2] - reference.z
            val speed2 = dx * dx + dy * dy + dz * dz
            energy += 0.5 * m[i] * speed2
            maxSpeed = max(maxSpeed, sqrt(speed2))
            count++
        }
        return if (count >= 2) energy else Double.NaN
    }

    /** Path length over the live nodes of the last [read]. */
    fun pathLength(): Double {
        val p = states.positions
        var length = 0.0
        var prev = -1
        longestLink = 0.0
        for (i in ids.indices) {
            if (!states.valid[i]) continue
            if (prev >= 0) {
                val dx = p[i * 3] - p[prev * 3]; val dy = p[i * 3 + 1] - p[prev * 3 + 1]; val dz = p[i * 3 + 2] - p[prev * 3 + 2]
                val link = sqrt(dx * dx + dy * dy + dz * dz)
                length += link
                if (i == prev + 1) longestLink = max(longestLink, link)
            }
            prev = i
        }
        return length
    }

    /** Takes [fraction] of each node's speed over the reference away; the anchored tip moves with its anchor. */
    @OptIn(PhysTickOnly::class)
    fun damp(level: PhysLevel, fraction: Double) {
        val v = states.velocities
        val m = states.masses
        val last = ids.size - 1
        for (i in ids.indices) {
            val k = i * 3
            if (!usable(i) || (anchored && i == last)) impulses[i].set(0.0)
            else impulses[i].set(reference.x - v[k], reference.y - v[k + 1], reference.z - v[k + 2]).mul(m[i] * fraction)
        }
        // A stale id (a node cut away since the last read) only skips that node.
        level.applyParticleImpulses(inputs)
    }

    private fun usable(i: Int) = states.valid[i] && states.masses[i].isFinite() && states.masses[i] > 0.0
}
