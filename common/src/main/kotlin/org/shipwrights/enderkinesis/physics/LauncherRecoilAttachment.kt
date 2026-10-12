package org.shipwrights.enderkinesis.physics

import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import java.util.concurrent.ConcurrentLinkedQueue

/** One-shot per-ship impulse queue. Originally the magic missile launcher's
 *  recoil path; also reused by the Echo Cannon's beam-hits-ship knockback.
 *  Queued on the game thread at fire time (world-space anchor + impulse),
 *  drained on the physics thread as a single-tick force at that point — an
 *  off-COM anchor imparts both linear push AND rotation, which is exactly
 *  what a real "shove" feels like. */
class LauncherRecoilAttachment : ShipPhysicsListener {

    private class Recoil(
        val posX: Double, val posY: Double, val posZ: Double,
        val impulseX: Double, val impulseY: Double, val impulseZ: Double,
    )

    @Transient
    private val pending = ConcurrentLinkedQueue<Recoil>()

    fun enqueue(worldPos: Vector3d, impulse: Vector3d) {
        pending.add(Recoil(worldPos.x, worldPos.y, worldPos.z, impulse.x, impulse.y, impulse.z))
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        if (pending.isEmpty()) return
        val transform = physShip.transform
        val comWorld = Vector3d(transform.positionInShip)
        transform.shipToWorld.transformPosition(comWorld)
        while (true) {
            val recoil = pending.poll() ?: break
            // Impulse (N·s) delivered as a one-phys-tick force.
            val force = Vector3d(recoil.impulseX, recoil.impulseY, recoil.impulseZ).mul(PHYS_TPS)
            val rel = Vector3d(recoil.posX, recoil.posY, recoil.posZ).sub(comWorld)
            physShip.applyInvariantForceToPos(force, rel)
        }
    }

    companion object {
        private const val PHYS_TPS = 60.0
    }
}
