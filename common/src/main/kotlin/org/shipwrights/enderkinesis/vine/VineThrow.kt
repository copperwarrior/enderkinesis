package org.shipwrights.enderkinesis.vine

import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.chains.ChainEnd
import org.valkyrienskies.core.api.chains.ChainEndpoint
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ChainReelControl
import org.valkyrienskies.core.api.chains.ChainStatus
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.particles.ParticleContactOther
import org.valkyrienskies.core.api.particles.ParticleEvent
import org.valkyrienskies.core.api.particles.ParticleEventKind
import org.valkyrienskies.core.api.particles.ParticleEventSettings
import org.valkyrienskies.core.api.particles.ParticleId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** S2C to the thrower: chain id and node; a node of 0 or more is a thrown hold that the server started there. */
val WOGGAK_VINE_THROWN: ResourceLocation = EnderkinesisMod.id("woggak_vine/thrown")
internal const val THROW_IN_FLIGHT = -1
internal const val THROW_ENDED = -2

/** Tip speed in m/s of a full bow draw: one block per tick. */
private const val MAX_THROW_SPEED = 20.0
/** A shorter draw than this bow power throws nothing, as for a bow. */
internal const val MIN_THROW_POWER = 0.1f
/** The first two links of a throw; under [VINE_TARGET_SPACING], so the new chain has exactly two links. */
private const val FIRST_LINK = 0.39
private const val FIRST_LINKS = 2
/** VS default: 3 physics ticks per game tick. The reel pays at most one node per physics tick. */
private const val PHYSICS_TICKS_PER_SECOND = 60.0
/** The reel credit must reach one spacing in one physics tick; a rate of exactly spacing/dt can fall a hair short. */
private const val REEL_RATE_MARGIN = 1.05
/** Newtons on the node next to the anchor while a reel pays out. */
internal const val PAYOUT_REEL_FORCE = 1000.0
private const val HAND_FORWARD = 0.5
private const val HAND_SIDE = 0.3
private const val HAND_DROP = 0.25
/** The tip probe reaches this far past the tip, as the tip particle stops one radius short of a block face. */
private const val TIP_PROBE = 0.3
/** A contact event probes this far each way along its normal for the block. */
private const val CONTACT_PROBE = 0.3
/** Pay-out time cap: a tip that stops without a hit ends the throw. */
private const val MAX_PAYOUT_TICKS = 200
/** After the near end lets go, the tip can still stick for this long; after that the throw is a miss. */
private const val FREE_FLIGHT_TICKS = 20
/** createChain is queued to the physics thread; a chain that is not active by then ends the throw. */
private const val MAX_PENDING_TICKS = 40
private const val LENGTH_EPS = 1e-9
private const val MIN_TIP_MOVE = 1e-4
/** The tip does not stick this near the hand, so it cannot catch on the thrower, its feet or its ship deck. */
private const val MIN_STICK_REACH = 1.5

/** A thrown vine in flight. The reel at the start pays out at the hand until the tip sticks or the whole length is out. */
@Suppress("LongParameterList") // The fixed settings of one throw, set once at the throw.
internal class VineThrow(
    val player: UUID,
    val vineId: Long,
    val chain: ChainId,
    val hand: InteractionHand,
    val items: Int,
    val creative: Boolean,
    val spacing: Double,
    val fullTarget: Double,
    val reelRate: Double,
    var target: Double,
) {
    /** The tip node, with contact events on; null until the chain is active. */
    var tip: ParticleId? = null
    val previousTip = Vector3d()
    val lastDir = Vector3d()
    /** The first contact of the tip from the physics events, for the next tick. */
    var contact: VinePoint? = null
    /** True when the near end let go of the hand. */
    var released = false
    var ticks = 0
    var freeTicks = 0
}

/**
 * The first links of a vine that a reel at its start pays out, from [start] along the unit [dir], with the spacing that
 * VS gives them. A node of a placed vine weighs one spacing of vine; VS splits the mass over nodes, one more than links.
 */
internal class ReelSeed(start: Vector3dc, dir: Vector3dc) {
    val path: List<Vector3dc> = List(FIRST_LINKS + 1) { i -> Vector3d(dir).mul(FIRST_LINK * i).add(start) }
    // The same sum as VS, so the reel spacing here is the one on the physics thread.
    val length = path.zipWithNext { a, b -> a.distance(b) }.sum()
    val spacing = chainSpacing(length, VINE_TARGET_SPACING)
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

/**
 * The reel target that keeps up with a tip [distance] from the hand: one spacing past it in whole spacings, never
 * under [current] (a lower target reels in) and never over [full].
 */
internal fun payoutTarget(distance: Double, spacing: Double, current: Double, full: Double): Double =
    max(current, min(full, (ceil(distance / spacing - LENGTH_EPS) + 1.0) * spacing))

/** Reel rate in m/s: the throw speed, and at least one node per physics tick. */
internal fun throwReelRate(speed: Double, spacing: Double): Double =
    max(speed, spacing * PHYSICS_TICKS_PER_SECOND * REEL_RATE_MARGIN)

/** Items to give back when only [paid] blocks of a throw of [items] paid out; the vine itself refunds the rest. */
internal fun unpaidItems(items: Int, paid: Double): Int = (items - woggakVineRefund(paid)).coerceAtLeast(0)

/** The hand point: in front of the eye, to the [side] (+1 right arm, -1 left arm) and down a little. */
internal fun handPoint(eye: Vec3, look: Vec3, yawDegrees: Float, side: Double): Vector3d {
    val yaw = Math.toRadians(yawDegrees.toDouble())
    return Vector3d(
        eye.x + look.x * HAND_FORWARD - cos(yaw) * side * HAND_SIDE,
        eye.y + look.y * HAND_FORWARD - HAND_DROP,
        eye.z + look.z * HAND_FORWARD - sin(yaw) * side * HAND_SIDE,
    )
}

private fun ServerPlayer.handPoint(hand: InteractionHand): Vector3d {
    val arm = if (hand == InteractionHand.MAIN_HAND) mainArm else mainArm.opposite
    return handPoint(eyePosition, lookAngle, yRot, if (arm == HumanoidArm.RIGHT) 1.0 else -1.0)
}

internal fun ServerPlayer.sendThrownVine(chain: ChainId, node: Int) =
    NetworkManager.sendToPlayer(this, WOGGAK_VINE_THROWN, FriendlyByteBuf(Unpooled.buffer()).apply {
        writeLong(chain.value)
        writeVarInt(node)
    })

/** Throws the whole [stack] from [hand] at bow [power]; false when no vine was made. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.throwWoggakVine(player: ServerPlayer, hand: InteractionHand, stack: ItemStack, power: Float): Boolean {
    val items = stack.count
    if (items <= 0) return false
    val creative = player.abilities.instabuild
    val start = player.handPoint(hand)
    val look = player.lookAngle
    val dir = Vector3d(look.x, look.y, look.z).normalize()
    val seed = ReelSeed(start, dir)
    val path = seed.path
    val pathLength = seed.length
    val spacing = seed.spacing
    val full = fullReelTarget(throwLength(items), spacing)
    val speed = throwSpeed(power)
    val motion = player.deltaMovement
    val velocity = Vector3d(dir).mul(speed).add(motion.x / SECONDS_PER_TICK, motion.y / SECONDS_PER_TICK, motion.z / SECONDS_PER_TICK)
    val chain = makeVineChain(path, ChainEndpoint.World(Vector3d(start)), ChainEndpoint.Free, velocity, seed.massPerLength) ?: return false
    val rate = throwReelRate(speed, spacing)
    val first = payoutTarget(pathLength, spacing, pathLength, full)
    if (first > pathLength) chain.setReelTarget(ChainReelControl(ChainEnd.START, first, rate, PAYOUT_REEL_FORCE))
    val vine = WoggakVine(newVineId(), chain.id, WoggakVineAnchor(null), WoggakVineAnchor(null))
    addVine(vine, path)
    val flight = VineThrow(player.uuid, vine.id, chain.id, hand, items, creative, spacing, full, rate, first)
    flight.previousTip.set(start)
    flight.lastDir.set(dir)
    woggakVines.throws.put(vine.id, flight)
    player.sendThrownVine(chain.id, THROW_IN_FLIGHT)
    if (!creative) stack.shrink(items)
    return true
}

/** True while [player]'s throw of [chain] flies; the thrower passes through it, so the server takes no push on it. */
internal fun WoggakVineData.isThrowing(player: UUID, chain: Long): Boolean =
    throws.values.any { it.player == player && it.chain.value == chain }

internal fun registerVineThrows() {
    TickEvent.SERVER_PRE.register { server -> server.takeTipContacts() }
}

/** One tick of each throw in flight; a throw that ends lets the anchor pass decide on a fade. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.tickVineThrows(data: WoggakVineData) {
    if (data.throws.isEmpty()) return
    val iterator = data.throws.values.iterator()
    while (iterator.hasNext()) {
        val flight = iterator.next()
        if (stepThrow(data, flight)) continue
        iterator.remove()
        data.anchorsDirty = true
        (getPlayerByUUID(flight.player) as? ServerPlayer)?.sendThrownVine(flight.chain, THROW_ENDED)
    }
}

/** False when the throw ends: the tip stuck, missed, or the vine or thrower is gone. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.stepThrow(data: WoggakVineData, flight: VineThrow): Boolean {
    val vine = data.vine(flight.vineId)
    val chain = shipObjectWorld.allChains.getById(flight.chain)
    if (vine == null || chain == null) return false
    val status = chain.status
    if (status.lifecycle == ChainLifecycle.PENDING) return ++flight.ticks < MAX_PENDING_TICKS || miss(chain, flight)
    if (status.lifecycle != ChainLifecycle.ACTIVE) return false
    status.failure?.let { LOG.warn("Woggak Vine throw {}: {}", flight.chain.value, it) }
    val player = getPlayerByUUID(flight.player) as? ServerPlayer
    val nodes = status.nodeCount
    if (player == null || !player.isAlive || status.failure != null || nodes < 2 || !data.copyThrowNodes(chain, nodes)) {
        return miss(chain, flight)
    }
    val k = (nodes - 1) * 3
    val tip = Vector3d(data.scratch[k], data.scratch[k + 1], data.scratch[k + 2])
    if (flight.tip == null) {
        flight.tip = chain.nodeIds.lastOrNull()?.also { tipEvents(it, true) }
    }
    val hand = player.handPoint(flight.hand)
    val contact = if (tip.distanceSquared(hand) < MIN_STICK_REACH * MIN_STICK_REACH) null else flight.contact ?: rayContact(data, flight, player, tip)
    if (contact != null && stick(data, vine, chain, status, flight, contact, player)) return false
    flight.contact = null
    if (tip.distanceSquared(flight.previousTip) > MIN_TIP_MOVE * MIN_TIP_MOVE) flight.lastDir.set(tip).sub(flight.previousTip).normalize()
    flight.previousTip.set(tip)
    flight.ticks++
    if (flight.released) return ++flight.freeTicks <= FREE_FLIGHT_TICKS || miss(chain, flight)
    // The tip still flies when the whole length is out: the near end lets go, and the vine flies on.
    if (flight.ticks > MAX_PAYOUT_TICKS || status.currentRestLength >= flight.fullTarget - flight.spacing * 1e-6) {
        release(chain, status, flight, player)
        return true
    }
    payOut(chain, flight, hand, tip)
    return true
}

/** Moves the near end to the hand and raises the reel target to keep up with the tip. */
@OptIn(GameTickOnly::class)
private fun payOut(chain: ServerChain, flight: VineThrow, hand: Vector3d, tip: Vector3dc) {
    chain.setEndpoint(ChainEnd.START, ChainEndpoint.World(hand))
    val next = payoutTarget(hand.distance(tip), flight.spacing, flight.target, flight.fullTarget)
    if (next <= flight.target) return
    chain.setReelTarget(ChainReelControl(ChainEnd.START, next, flight.reelRate, PAYOUT_REEL_FORCE))
    flight.target = next
}

/** Lets go of the near end, ends the reel at the paid length, and gives back the items that did not pay out. */
@OptIn(GameTickOnly::class)
private fun release(chain: ServerChain, status: ChainStatus, flight: VineThrow, player: ServerPlayer?) {
    if (flight.released) return
    flight.released = true
    chain.setEndpoint(ChainEnd.START, ChainEndpoint.Free)
    val paid = status.currentRestLength
    // setTargetLength ends the reel; the published rest can be a few nodes behind, so the vine gets that much shorter.
    if (paid.isFinite() && paid > 0) chain.setTargetLength(paid)
    if (flight.creative || player == null) return
    val back = unpaidItems(flight.items, paid)
    if (back <= 0) return
    val stack = ItemStack(EKItems.WOGGAK_VINE.get(), back)
    if (!player.inventory.add(stack)) player.drop(stack, false)
}

/** Ends the throw with no anchor; the anchor pass then starts the fade, which drops the paid items. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.miss(chain: ServerChain, flight: VineThrow): Boolean {
    if (chain.status.lifecycle == ChainLifecycle.ACTIVE) {
        release(chain, chain.status, flight, getPlayerByUUID(flight.player) as? ServerPlayer)
    }
    flight.tip?.let { tipEvents(it, false) }
    return false
}

/** Fixes the tip at [contact]; the thrower that still holds the near end hangs from it. False when [contact] is gone. */
@OptIn(GameTickOnly::class)
@Suppress("LongParameterList") // One call site; the parts are the throw's own state.
private fun ServerLevel.stick(
    data: WoggakVineData, vine: WoggakVine, chain: ServerChain, status: ChainStatus, flight: VineThrow,
    contact: VinePoint, player: ServerPlayer,
): Boolean {
    val (_, endpoint) = attachment(contact) ?: return false
    val holds = !flight.released
    release(chain, status, flight, player)
    if (endpoint != ChainEndpoint.Free) chain.setEndpoint(ChainEnd.END, endpoint)
    vine.end = WoggakVineAnchor(contact)
    data.touch(vine)
    data.anchorsDirty = true
    data.jointsDirty = true
    data.setDirty()
    flight.tip?.let { tipEvents(it, false) }
    if (holds) player.holdThrownVine(chain.id, 0)
    return true
}

@OptIn(GameTickOnly::class)
private fun ServerLevel.tipEvents(tip: ParticleId, on: Boolean) {
    val settings = if (on) ParticleEventSettings(rigidContacts = true, particleContacts = true) else ParticleEventSettings()
    shipObjectWorld.allParticles.getById(tip)?.setEventSettings(settings)
}

@OptIn(GameTickOnly::class)
private fun WoggakVineData.copyThrowNodes(chain: ServerChain, nodes: Int): Boolean {
    if (scratch.size < nodes * 3) scratch = DoubleArray(nodes * 3)
    return chain.copyNodePositions(scratch) == nodes
}

/**
 * The block or vine on the tip's path in the last tick, probed [TIP_PROBE] past the tip. Backs up the contact events,
 * which can miss a fast tip.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.rayContact(data: WoggakVineData, flight: VineThrow, player: ServerPlayer, tip: Vector3dc): VinePoint? {
    val dir = Vector3d(tip).sub(flight.previousTip)
    val moved = dir.length()
    if (moved > MIN_TIP_MOVE) dir.div(moved) else dir.set(flight.lastDir)
    val reach = moved + TIP_PROBE
    val from = Vec3(flight.previousTip.x, flight.previousTip.y, flight.previousTip.z)
    val to = from.add(dir.x * reach, dir.y * reach, dir.z * reach)
    val hit = clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
    val blockT = if (hit.type == HitResult.Type.BLOCK) hit.location.distanceTo(from) else reach
    return vineContact(data, flight.vineId, Vector3d(from.x, from.y, from.z), dir, blockT)
        ?: if (hit.type == HitResult.Type.BLOCK) blockContact(hit) else null
}

/** The nearest link of another unfaded vine on the ray, closer than [maxT], as a joint to its nearer node. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.vineContact(data: WoggakVineData, own: Long, origin: Vector3d, dir: Vector3d, maxT: Double): VinePoint? {
    var bestT = maxT
    var best: VinePoint? = null
    for (vine in data.vines) {
        if (vine.id == own || vine.fadeStart >= 0) continue
        val chain = shipObjectWorld.allChains.getById(vine.chain) ?: continue
        val nodes = chain.status.nodeCount
        if (nodes < 2 || !data.copyThrowNodes(chain, nodes)) continue
        for (link in 0 until nodes - 1) {
            val t = data.scratch.rayHitsSegment(origin, dir, link * 3, bestT)
            if (t < 0) continue
            bestT = t
            val hit = Vector3d(dir).mul(t).add(origin)
            val k = link * 3
            val p = data.scratch
            val near = if (hit.distanceSquared(p[k + 3], p[k + 4], p[k + 5]) < hit.distanceSquared(p[k], p[k + 1], p[k + 2])) link + 1 else link
            best = VinePoint(BlockPos.containing(hit.x, hit.y, hit.z), hit, null, VineJoint(vine.id, near, vine.home))
        }
    }
    return best
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

/** Takes the tip contact events of all throws; one drain serves every level, as VS keeps one event queue. */
@OptIn(GameTickOnly::class)
private fun MinecraftServer.takeTipContacts() {
    var any = false
    for (level in allLevels) if (level.loadedWoggakVines?.throws?.isEmpty() == false) { any = true; break }
    if (!any) return
    val events = try {
        shipObjectWorld.drainParticleEvents().events
    } catch (e: UnsupportedOperationException) {
        LOG.debug("Woggak Vine: particle events are not available", e)
        return
    }
    for (event in events) {
        if (event.kind != ParticleEventKind.CONTACT_START) continue
        for (level in allLevels) {
            val data = level.loadedWoggakVines ?: continue
            for (flight in data.throws.values) {
                if (flight.tip == event.particle && flight.contact == null) flight.contact = level.eventContact(data, flight, event)
            }
        }
    }
}

/** The anchor of a tip contact: a joint on a touched vine node, or the block that the contact normal points into. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.eventContact(data: WoggakVineData, flight: VineThrow, event: ParticleEvent): VinePoint? =
    when (val other = event.other) {
        is ParticleContactOther.Particle -> vineNodeContact(data, flight.vineId, other.id, event.position)
        // Terrain and ships both: the block comes from a short clip into the surface.
        is ParticleContactOther.Body -> surfaceContact(flight, event.position, event.normalTowardParticle)
        null -> null
    }

@OptIn(GameTickOnly::class)
private fun ServerLevel.vineNodeContact(data: WoggakVineData, own: Long, particle: ParticleId, position: Vector3dc): VinePoint? {
    for (vine in data.vines) {
        if (vine.id == own || vine.fadeStart >= 0) continue
        val node = shipObjectWorld.allChains.getById(vine.chain)?.nodeIds?.indexOf(particle) ?: continue
        if (node < 0) continue
        val at = Vector3d(position)
        return VinePoint(BlockPos.containing(at.x, at.y, at.z), at, null, VineJoint(vine.id, node, vine.home))
    }
    return null
}

private fun ServerLevel.surfaceContact(flight: VineThrow, position: Vector3dc, normal: Vector3dc): VinePoint? {
    val player = getPlayerByUUID(flight.player) ?: return null
    val length = sqrt(normal.lengthSquared())
    if (length < MIN_TIP_MOVE) return null
    val s = CONTACT_PROBE / length
    val from = Vec3(position.x() + normal.x() * s, position.y() + normal.y() * s, position.z() + normal.z() * s)
    val to = Vec3(position.x() - normal.x() * s, position.y() - normal.y() * s, position.z() - normal.z() * s)
    val hit = clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
    return if (hit.type == HitResult.Type.BLOCK) blockContact(hit) else null
}
