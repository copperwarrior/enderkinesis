package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.joml.Matrix4dc
import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.events.CollisionEvent
import org.valkyrienskies.core.api.physics.ContactPoint
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.vsApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Sets runes off when a ship touches them. VS2 reports contacts on the physics thread; a
 * contact counts when it lies against a rune's host block, on the face the rune is
 * written on (the contact normal along that face's normal). A ship hovering above a rune
 * or sliding past without touching makes no contact, so it doesn't count.
 *
 * Runes are keyed by host block position in the host's own space (world or shipyard), per
 * dimension. Contacts are queued for the game thread; see [RuneTriggers].
 */
@OptIn(VsBeta::class)
object RuneCollisionRouter {

    /** Dimension → host block position → faces of it that carry a rune. */
    private val runes = ConcurrentHashMap<DimensionId, ConcurrentHashMap<BlockPos, MutableSet<Direction>>>()
    private val contacts = ConcurrentLinkedQueue<Pair<DimensionId, BlockPos>>()

    @Volatile private var registered = false

    fun register() {
        if (registered) return
        registered = true
        vsApi.collisionStartEvent.on { event, _ -> handle(event) }
    }

    fun add(dimension: DimensionId, host: BlockPos, face: Direction) {
        runes.computeIfAbsent(dimension) { ConcurrentHashMap() }
            .computeIfAbsent(host.immutable()) { ConcurrentHashMap.newKeySet() }
            .add(face)
    }

    fun remove(dimension: DimensionId, host: BlockPos, face: Direction) {
        val byHost = runes[dimension] ?: return
        byHost.computeIfPresent(host) { _, faces -> faces.apply { remove(face) }.takeIf { it.isNotEmpty() } }
    }

    /** Game thread: hands over each touched rune's position, once. */
    fun drain(action: (DimensionId, BlockPos) -> Unit) {
        while (true) {
            val (dimension, pos) = contacts.poll() ?: return
            action(dimension, pos)
        }
    }

    private fun handle(event: CollisionEvent) {
        val byHost = runes[event.dimensionId] ?: return
        if (byHost.isEmpty()) return
        val shipA = event.physLevel.getShipById(event.shipIdA)
        val shipB = event.physLevel.getShipById(event.shipIdB)
        val dimension = event.dimensionId
        for (contact in event.contactPoints) {
            if (contact.separation > SEPARATION_EPSILON) continue
            // A ship body's blocks are in shipyard space; the world body has no ship.
            shipA?.let { check(dimension, byHost, contact, it.transform.worldToShip) }
            shipB?.let { check(dimension, byHost, contact, it.transform.worldToShip) }
            if (shipA == null || shipB == null) check(dimension, byHost, contact, null)
        }
    }

    private fun check(
        dimension: DimensionId, byHost: Map<BlockPos, Set<Direction>>, contact: ContactPoint, worldToShip: Matrix4dc?,
    ) {
        val point = Vector3d(contact.position.x(), contact.position.y(), contact.position.z())
        val normal = Vector3d(contact.normal.x(), contact.normal.y(), contact.normal.z())
        if (worldToShip != null) {
            worldToShip.transformPosition(point)
            worldToShip.transformDirection(normal)
        }
        for (sign in SIGNS) {
            val host = BlockPos.containing(
                point.x + sign * normal.x * PROBE_DEPTH,
                point.y + sign * normal.y * PROBE_DEPTH,
                point.z + sign * normal.z * PROBE_DEPTH,
            )
            val faces = byHost[host] ?: continue
            for (face in faces) {
                val n = face.normal
                if (Math.abs(normal.x * n.x + normal.y * n.y + normal.z * n.z) < NORMAL_ALIGNMENT) continue
                // The contact must lie on that face of the host, not the opposite one.
                val outward = (point.x - (host.x + 0.5)) * n.x + (point.y - (host.y + 0.5)) * n.y + (point.z - (host.z + 0.5)) * n.z
                if (outward < FACE_OFFSET) continue
                contacts.add(dimension to host.relative(face))
            }
        }
    }

    /** In singleplayer the JVM lives across worlds. */
    fun clearSession() {
        runes.clear()
        contacts.clear()
    }

    private val SIGNS = doubleArrayOf(-1.0, 1.0)
    private const val PROBE_DEPTH = 0.1
    private const val SEPARATION_EPSILON = 0.05f
    /** |cos| between contact normal and face normal: within ~25°. */
    private const val NORMAL_ALIGNMENT = 0.9
    /** How far out from the host's centre the contact must lie: near its face (0.5). */
    private const val FACE_OFFSET = 0.35
}
