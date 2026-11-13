package org.shipwrights.enderkinesis.vine

import java.util.UUID
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.joml.Vector3d
import org.shipwrights.enderkinesis.util.hitboxMass
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.particles.ParticleId
import org.valkyrienskies.core.api.particles.VsParticleCreateData
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.floor
import kotlin.math.max

/** Two packets: a client tick and a server tick are not in step, so one server tick can get two client ticks. */
private const val MAX_PUSHES_PER_TICK = 2
/** Ticks without support before the rider goes: a step over a gap or an onGround flicker keeps it. */
private const val RIDER_GRACE_TICKS = 2L
private const val RIDER_PARTICLE_RADIUS = 0.1
/** userTag of a rider particle ("WogRide"), so a level load can delete the ones that a crash left behind. */
private const val RIDER_PARTICLE_TAG = 0x576F6752696465L
private const val BUDGET_PRUNE_TICKS = 200L

/** A player's mass on the vine it stands on: [particle], joined by [pair] to the support point on [chain]. */
internal class VineRider(val particle: ParticleId, var chain: ChainId, var ids: List<ParticleId>, var link: Int, var pair: JointPair) {
    var supportedTick = 0L
}

private class PushBudget {
    var tick = Long.MIN_VALUE
    var count = 0
}

/** The vine riders and the push packet budget of one level; not saved, because both end with the session. */
internal class VineRiding {
    val riders = HashMap<UUID, VineRider>()
    private val budgets = HashMap<UUID, PushBudget>()
    var strayDeleted = false

    /** True when [player] may push the vines once more in tick [gameTime]. */
    fun takePushBudget(player: UUID, gameTime: Long): Boolean {
        val budget = budgets.getOrPut(player) { PushBudget() }
        if (budget.tick != gameTime) {
            budget.tick = gameTime
            budget.count = 0
        }
        if (budget.count >= MAX_PUSHES_PER_TICK) return false
        budget.count++
        return true
    }

    /** Drops the budgets of players that pushed no vine in the last tick, such as players that left. */
    fun pruneBudgets(gameTime: Long) {
        budgets.values.removeIf { it.tick < gameTime - 1 }
    }
}

/**
 * Puts [player]'s mass on the support point of [contacts], the links under its feet; [by] is the blocked fall in
 * blocks. Returns true when a rider particle now carries the player's weight, so the push gives no weight.
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.rideVine(player: ServerPlayer, data: WoggakVineData, contacts: VineContacts, by: Double): Boolean {
    if (data.isHolding(player) || contacts.size == 0) return false
    var best = 0
    for (c in 1 until contacts.size) if (contacts.weight[c] > contacts.weight[best]) best = c
    val chain = contacts.chain[best] ?: return false
    // The weighted mean place of the contacts on that vine, in node units, so the point moves smoothly over links.
    var sum = 0.0
    var weight = 0.0
    for (c in 0 until contacts.size) {
        if (contacts.chain[c] !== chain) continue
        sum += (contacts.node[c] + contacts.t[c]) * contacts.weight[c]
        weight += contacts.weight[c]
    }
    val place = sum / weight
    val rider = data.riding.riders[player.uuid]
    val ids = if (rider != null && rider.chain == chain.id) rider.ids else chain.nodeIds
    if (ids.size < 2) return false
    val link = floor(place).toInt().coerceIn(0, ids.size - 2)
    val t = (place - link).coerceIn(0.0, 1.0)
    if (rider != null) {
        if (rider.chain != chain.id || rider.link != link) {
            rider.chain = chain.id
            rider.ids = ids
            rider.link = link
            rider.pair = JointPair(rider.particle, ids[link], ids[link + 1])
            data.jointsDirty = true
        }
        rider.pair.weight = t
        rider.supportedTick = gameTime
        return true
    }
    val particle = createRiderParticle(player, data, chain.id.value, link, t, by) ?: return false
    data.riding.riders[player.uuid] = VineRider(particle, chain.id, ids, link, JointPair(particle, ids[link], ids[link + 1]).also {
        it.weight = t
    }).also { it.supportedTick = gameTime }
    data.jointsDirty = true
    return true
}

/** A particle of the player's mass at the support point; it starts at the point's velocity plus the landing fall. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.createRiderParticle(
    player: ServerPlayer, data: WoggakVineData, chainId: Long, link: Int, t: Double, by: Double,
): ParticleId? {
    val position = Vector3d()
    val velocity = Vector3d()
    val next = Vector3d()
    val nextVelocity = Vector3d()
    val collider = data.collider
    if (!collider.nodeState(chainId, link, position, velocity) || !collider.nodeState(chainId, link + 1, next, nextVelocity)) return null
    position.lerp(next, t)
    velocity.lerp(nextVelocity, t)
    // The same landing as the push formula: the fall past the resting step, in m/s.
    velocity.y -= max(0.0, -by - REST_FALL) / SECONDS_PER_TICK
    return try {
        shipObjectWorld.createParticle(VsParticleCreateData(
            dimensionId = dimensionId,
            position = position,
            velocity = velocity,
            radius = RIDER_PARTICLE_RADIUS,
            mass = player.hitboxMass(),
            collisionMask = NO_CONTACTS,
            userTag = RIDER_PARTICLE_TAG,
        )).id
    } catch (e: UnsupportedOperationException) {
        LOG.warn("Woggak Vine: particles are not available", e)
        null
    }
}

/** Removes the riders that left their vine, hold a vine, broke the joint or lost the vine. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.tickVineRiders(data: WoggakVineData) {
    val riding = data.riding
    if (!riding.strayDeleted) {
        riding.strayDeleted = true
        deleteStrayRiderParticles()
    }
    if (gameTime % BUDGET_PRUNE_TICKS == 0L) riding.pruneBudgets(gameTime)
    if (riding.riders.isEmpty()) return
    val iterator = riding.riders.entries.iterator()
    while (iterator.hasNext()) {
        val (uuid, rider) = iterator.next()
        val player = getPlayerByUUID(uuid)
        val chain = shipObjectWorld.allChains.getById(rider.chain)
        val status = chain?.status
        val keep = player != null && player.isAlive && !player.isSpectator && !data.isHolding(player) && !rider.pair.broken &&
            gameTime - rider.supportedTick <= RIDER_GRACE_TICKS && status != null &&
            status.lifecycle == ChainLifecycle.ACTIVE && status.brokenLinks.isEmpty() && rider.link + 1 < status.nodeCount
        if (keep) continue
        iterator.remove()
        data.jointsDirty = true
        if (!shipObjectWorld.deleteParticle(rider.particle)) LOG.warn("Woggak Vine rider particle {} was already removed", rider.particle.value)
    }
}

/** Deletes the rider particles that a save kept; a rider does not outlive its session. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.deleteStrayRiderParticles() {
    val world = shipObjectWorld
    val stray = try {
        world.allParticles.getInDimension(dimensionId).filter { it.userTag == RIDER_PARTICLE_TAG }
    } catch (e: UnsupportedOperationException) {
        LOG.debug("Woggak Vine: particles are not available", e)
        return
    }
    for (particle in stray) if (!world.deleteParticle(particle.id)) LOG.warn("Woggak Vine rider particle {} was already removed", particle.id.value)
}
