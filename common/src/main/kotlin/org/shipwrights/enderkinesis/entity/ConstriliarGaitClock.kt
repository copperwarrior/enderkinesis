package org.shipwrights.enderkinesis.entity

import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc

/**
 * Gait phase clock: a crawl (one leg at a time, three always down) at low speed, a
 * diagonal trot above. The server runs it and syncs [encoded]; both gaits read their
 * lift-off windows from the synced value, so the drawn feet and the force feet step
 * together. Encoded: -1 stopped, `phase` crawl, `1 + phase` trot.
 */
internal class ConstriliarGaitClock {

    private var phase = 0.0
    private var trot = false
    private var running = false

    /** Stopped unless the legs carry the body and it moves. */
    fun tick(speedPerTick: Double, carrying: Boolean) {
        running = carrying && speedPerTick > IDLE_SPEED
        if (!running) return
        trot = if (trot) speedPerTick > CRAWL_BELOW else speedPerTick > TROT_ABOVE
        phase = (phase + ConstriliarGait.swingRate(speedPerTick) * window(trot)) % 1.0
    }

    fun encoded(): Float = if (!running) STOPPED else phase.toFloat() + if (trot) 1f else 0f

    companion object {

        const val STOPPED = -1f

        // Leg order 0 FR, 1 FL, 2 BL, 3 BR. Crawl runs BL → FL → BR → FR.
        private val CRAWL_OFFSET = doubleArrayOf(0.75, 0.25, 0.0, 0.5)
        private val TROT_OFFSET = doubleArrayOf(0.0, 0.5, 0.0, 0.5)

        /** Speeds in blocks/tick. Crawl strides grow with speed, so trot before they
         *  pass the leg's reach. */
        private const val IDLE_SPEED = 0.02
        private const val TROT_ABOVE = 0.06
        private const val CRAWL_BELOW = 0.045

        /** A foot this far from home steps in its window; less, it skips the beat. */
        internal const val SETTLE_SQ = 0.15 * 0.15

        /** Lift-off may start in this first share of the window: at least one tick at
         *  the fastest swing, so a window is never missed. */
        private const val START_SHARE = 0.5

        /** Raibert gain k = √(h/g) in ticks, for a ride height of about 0.5. */
        private const val RAIBERT_K_TICKS = 4.5
        private const val RAIBERT_FEEDBACK_MAX = 0.4

        /** The crawl shifts the body for the next lifter this share of a cycle early. */
        private const val SHIFT_LEAD = 0.1

        private fun window(trot: Boolean): Double = if (trot) 0.5 else 0.25

        private fun isTrot(gait: Float): Boolean = gait >= 1f

        private fun phaseOf(gait: Float): Double = (gait - if (isTrot(gait)) 1f else 0f).toDouble()

        private fun legPhase(leg: Int, phase: Double, trot: Boolean): Double {
            val offset = if (trot) TROT_OFFSET[leg] else CRAWL_OFFSET[leg]
            return ((phase - offset) % 1.0 + 1.0) % 1.0
        }

        /** Whether [leg] may lift now. [swinging] reports the other legs. */
        internal inline fun mayStep(leg: Int, errSq: Double, gait: Float, swinging: (Int) -> Boolean): Boolean {
            if (errSq > ConstriliarGait.FORCE_STEP_SQ) return true
            if (gait >= 0f) return errSq > SETTLE_SQ && inStartWindow(leg, gait)
            return errSq > ConstriliarGait.STEP_TRIGGER_SQ && ConstriliarGait.otherPairPlanted(leg) { !swinging(it) }
        }

        /** Trot: only the leg's own diagonal pair may be in the air. Crawl: one leg at a time. */
        internal inline fun supportAllows(leg: Int, gait: Float, swinging: (Int) -> Boolean): Boolean {
            if (isTrot(gait)) return ConstriliarGait.otherPairPlanted(leg) { !swinging(it) }
            for (other in 0..3) if (other != leg && swinging(other)) return false
            return true
        }

        internal fun inStartWindow(leg: Int, gait: Float): Boolean {
            val trot = isTrot(gait)
            return legPhase(leg, phaseOf(gait), trot) < START_SHARE * window(trot)
        }

        /** Crawl only: the leg that lifts next, [SHIFT_LEAD] ahead. -1 otherwise. */
        fun nextCrawlLifter(gait: Float): Int {
            if (gait < 0f || isTrot(gait)) return -1
            val ahead = (phaseOf(gait) + SHIFT_LEAD) % 1.0
            for (leg in 0..3) if (legPhase(leg, ahead, false) < window(false)) return leg
            return -1
        }

        /**
         * Touchdown lead in the surface plane, into [out]. Running: Raibert,
         * `v·T_stance/2 + k·(v − v_cmd)`; with no [desiredPerTick] (the client) the
         * feedback term is zero. Stopped: the fixed lead of the distance-trigger gait.
         */
        fun footLead(gait: Float, move: Vec3, desiredPerTick: Vector3dc?, up: Vector3dc, out: Vector3d): Vector3d {
            out.set(move.x, move.y, move.z)
            out.sub(up.x() * out.dot(up), up.y() * out.dot(up), up.z() * out.dot(up))
            if (gait < 0f) return out.mul(ConstriliarGait.STEP_LEAD_TICKS)
            val trot = isTrot(gait)
            val swingTicks = 1.0 / ConstriliarGait.swingRate(move.length())
            val stanceTicks = swingTicks * (1.0 - window(trot)) / window(trot)
            val fx = out.x; val fy = out.y; val fz = out.z
            out.mul(stanceTicks * 0.5)
            if (desiredPerTick == null) return out
            var ex = fx - desiredPerTick.x(); var ey = fy - desiredPerTick.y(); var ez = fz - desiredPerTick.z()
            val along = ex * up.x() + ey * up.y() + ez * up.z()
            ex -= up.x() * along; ey -= up.y() * along; ez -= up.z() * along
            var scale = RAIBERT_K_TICKS
            val len = Math.sqrt(ex * ex + ey * ey + ez * ez) * scale
            if (len > RAIBERT_FEEDBACK_MAX) scale *= RAIBERT_FEEDBACK_MAX / len
            return out.add(ex * scale, ey * scale, ez * scale)
        }
    }
}
