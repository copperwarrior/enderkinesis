package org.shipwrights.enderkinesis.body

import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-thread bridge between Wylland Tome grabs (server-main) and
 * the orb body's spring force application (phys thread).
 *
 *  When the player starts grabbing an orb, the Tome's begin-grab path
 *  calls [beginGrab]. Each server tick, the Tome refreshes the
 *  grab's target world position via [updateTarget] — the player's
 *  eye + look-direction × grab-distance. Each phys tick,
 *  [org.shipwrights.enderkinesis.body.OrbGravityCanceller] reads
 *  [targetFor] for every orb in the per-dimension registry and adds
 *  a spring force pulling the body's center toward the target. The
 *  Tome's release path calls [release].
 *
 *  No ordering / locking concerns: maps are [ConcurrentHashMap];
 *  reads on the phys thread tolerate a one-tick stale target. */
object OrbGrabState {

    /** player UUID -> the bodyId they're holding. */
    private val grabsByPlayer: MutableMap<UUID, Long> = ConcurrentHashMap()
    /** bodyId -> world target position. Updated each server tick from
     *  the holder's aim ray. */
    private val targetsByBody: MutableMap<Long, Vector3d> = ConcurrentHashMap()
    /** bodyId -> world-frame target rotation. Initialised from the
     *  body's current rotation at grab time; updated by the Tome's
     *  rotate / roll inputs while the grab is active. */
    private val idealRotByBody: MutableMap<Long, Quaterniond> = ConcurrentHashMap()
    /** bodyId -> body's position relative to the player's eye in the
     *  player-look basis at grab time: x = right, y = up (world-up
     *  projected perpendicular to look), z = forward (along look).
     *  Reconstructed in world space each server tick by recomposing
     *  with the player's CURRENT look basis — so the orb stays at the
     *  same on-screen offset as the player turns, with no
     *  jump-forward at grab start regardless of how off-centre the
     *  player clicked. */
    private val grabOffsetByBody: MutableMap<Long, Vector3d> = ConcurrentHashMap()

    fun beginGrab(player: UUID, bodyId: Long, initialRotation: Quaterniondc, grabOffsetLocal: Vector3dc) {
        if (bodyId == 0L) return
        if (grabsByPlayer.putIfAbsent(player, bodyId) == null) {
            idealRotByBody[bodyId] = Quaterniond(initialRotation)
            grabOffsetByBody[bodyId] = Vector3d(grabOffsetLocal)
        }
    }

    fun release(player: UUID) {
        val bodyId = grabsByPlayer.remove(player) ?: return
        if (grabsByPlayer.values.none { it == bodyId }) {
            targetsByBody.remove(bodyId)
            idealRotByBody.remove(bodyId)
            grabOffsetByBody.remove(bodyId)
        }
    }

    fun grabOffsetFor(bodyId: Long): Vector3dc? = grabOffsetByBody[bodyId]

    /** Apply a scroll-wheel delta to the forward (along-look) component
     *  of the grab offset, clamped to the Tome's grab-distance window.
     *  The right / up components stay fixed, so the orb's perpendicular
     *  offset from the cursor is preserved. */
    fun adjustGrabForward(bodyId: Long, delta: Double, min: Double, max: Double) {
        grabOffsetByBody.compute(bodyId) { _, existing ->
            val cur = existing ?: Vector3d(0.0, 0.0, 8.0)
            cur.z = (cur.z + delta).coerceIn(min, max)
            cur
        }
    }

    /** Apply [delta] on the left of this player's grabbed orb's ideal
     *  rotation. Delta is a small world-frame rotation accumulated by
     *  the Tome's mouse / scroll input; same composition convention
     *  as the ship grab. No-op when the player isn't holding an orb. */
    fun composeIdealRotation(player: UUID, delta: Quaterniondc) {
        val bodyId = grabsByPlayer[player] ?: return
        idealRotByBody.compute(bodyId) { _, existing ->
            val cur = existing ?: Quaterniond()
            Quaterniond(delta).mul(cur).normalize()
        }
    }

    fun idealRotationFor(bodyId: Long): Quaterniondc? = idealRotByBody[bodyId]

    /** Returns the body id this player is currently grabbing, or null. */
    fun grabFor(player: UUID): Long? = grabsByPlayer[player]

    /** Iterate (playerUuid, bodyId) pairs so the server tick can
     *  refresh each grab's target world position. */
    fun forEachGrab(action: (UUID, Long) -> Unit) {
        grabsByPlayer.forEach { (p, b) -> action(p, b) }
    }

    fun updateTarget(bodyId: Long, target: Vector3dc) {
        targetsByBody.compute(bodyId) { _, existing ->
            (existing ?: Vector3d()).set(target)
        }
    }

    fun targetFor(bodyId: Long): Vector3dc? = targetsByBody[bodyId]
}
