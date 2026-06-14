package org.shipwrights.enderkinesis.entity

import net.minecraft.util.Mth

/** Shared path math for the Cataloger tome-summon flourish. Kept off the client class so the
 *  server goal can call it without client-only imports. Server passes integer tick offset;
 *  client adds a partialTicks fraction. */
object CatalogerTomePath {

    enum class Phase { OUT, DWELL, IN }

    /** Peak height of the parabolic arc above the straight-line path
     *  between the bookshelf and the cataloger's hold-point. */
    private const val ARC_PEAK_HEIGHT = 0.35

    /** Amplitude (blocks) and period (ticks) of the gentle Y bob while
     *  the tome dwells open in front of the cataloger. */
    private const val DWELL_BOB_AMP = 0.05
    private const val DWELL_BOB_PERIOD = 60.0

    /** Distance along the cataloger's HEAD-gaze vector at which the
     *  tome dwells — far enough to read like the head is looking at
     *  a book held out in front of it, not pressed against its nose.
     *  Anchored to the head's facing direction (yaw + pitch), so as
     *  the head turns, the hold-point swings with it. */
    const val HOLD_FORWARD = 1.1

    /** Vertical offset (blocks) relative to the eye for the dwell
     *  hold-point. Negative drops the book toward the chin so the
     *  cataloger reads down at it — feels less like "object in your
     *  face" than the previous brow-level position. */
    const val HOLD_RISE = -0.2

    /** Distance (blocks) along the shelf's face vector at which the
     *  quadratic-Bezier mid-control point sits, and the orientation
     *  fade-out range. The book leaves the shelf moving perpendicular
     *  to the face for this distance, then the path curves smoothly
     *  toward the hold-point over the rest of the flight. Same range
     *  used by the orientation blend so the two stay coupled. */
    const val BOOKSHELF_FADE_DISTANCE = 1.5

    fun phaseOf(elapsed: Float): Phase {
        val out = Cataloger.TOME_OUTBOUND_TICKS.toFloat()
        val dwellEnd = out + Cataloger.TOME_DWELL_TICKS.toFloat()
        return when {
            elapsed < out -> Phase.OUT
            elapsed < dwellEnd -> Phase.DWELL
            else -> Phase.IN
        }
    }

    /** Linear 0..1 share of the current phase; easing is applied by
     *  the caller as needed (position lerp, tumble cumulative, etc.). */
    fun progressInPhase(phase: Phase, elapsed: Float): Float = when (phase) {
        Phase.OUT -> (elapsed / Cataloger.TOME_OUTBOUND_TICKS).coerceIn(0f, 1f)
        Phase.DWELL ->
            ((elapsed - Cataloger.TOME_OUTBOUND_TICKS) / Cataloger.TOME_DWELL_TICKS)
                .coerceIn(0f, 1f)
        Phase.IN ->
            ((elapsed - Cataloger.TOME_OUTBOUND_TICKS - Cataloger.TOME_DWELL_TICKS)
                / Cataloger.TOME_INBOUND_TICKS).coerceIn(0f, 1f)
    }

    /** Cubic smoothstep ease. f(0)=0, f(1)=1, f'(0)=f'(1)=0. */
    fun easeInOut(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** World-space hold-point in front of the cataloger's HEAD at
     *  head height, along the head's gaze vector. The book moves with
     *  the head: as the cataloger looks around (yaw) or tilts up/down
     *  (pitch), the hold-point swings to match.
     *
     *  [eyeY] is the absolute world Y of the eye (caller pulls
     *  `entity.getEyeY(partialTicks)` on the client and `entity.eyeY`
     *  on the server). [headYawDeg] and [headPitchDeg] are the head's
     *  yaw and pitch in degrees (use `yHeadRot` / `xRot` lerped by
     *  `partialTicks` on the client; raw on the server). */
    fun holdPoint(
        ex: Double, eyeY: Double, ez: Double,
        headYawDeg: Float, headPitchDeg: Float,
    ): Triple<Double, Double, Double> {
        val yawRad = headYawDeg * (Math.PI / 180.0)
        val pitchRad = headPitchDeg * (Math.PI / 180.0)
        val cosPitch = Math.cos(pitchRad)
        val fx = -Math.sin(yawRad) * cosPitch
        val fy = -Math.sin(pitchRad)
        val fz = Math.cos(yawRad) * cosPitch
        return Triple(
            ex + fx * HOLD_FORWARD,
            eyeY + fy * HOLD_FORWARD + HOLD_RISE,
            ez + fz * HOLD_FORWARD,
        )
    }

    /**
     * Tome world position for the current frame.
     *
     * Flight phases use a **quadratic Bezier** whose mid-control point
     * sits [BOOKSHELF_FADE_DISTANCE] blocks along the source / return
     * shelf's face vector — direction from the shelf to the cataloger
     * eye `(eyeX, eyeY, eyeZ)`. That makes the curve tangent to the
     * shelf face at the shelf endpoint, so the book exits straight
     * perpendicular to the face and then curves smoothly toward the
     * hold-point over the rest of the flight (and the mirror on
     * inbound). The Bezier provides a single continuous curve from
     * shelf to hold; there's no sharp turn at any blend boundary.
     *
     * A small parabolic Y bump ([ARC_PEAK_HEIGHT] · 4t(1−t)) layers on
     * top to give the path some vertical interest when the shelf and
     * hold-point are at the same height — the Bezier alone is otherwise
     * straight in Y for that case.
     */
    fun computePosition(
        phase: Phase, phaseProgress: Float,
        sourceX: Double, sourceY: Double, sourceZ: Double,
        returnX: Double, returnY: Double, returnZ: Double,
        holdX: Double, holdY: Double, holdZ: Double,
        eyeX: Double, eyeY: Double, eyeZ: Double,
    ): Triple<Double, Double, Double> {
        return when (phase) {
            Phase.OUT -> {
                val t = easeInOut(phaseProgress).toDouble()
                val (mx, my, mz) = shelfFaceControlPoint(
                    sourceX, sourceY, sourceZ, eyeX, eyeY, eyeZ,
                )
                bezierPathWithArc(
                    sourceX, sourceY, sourceZ, mx, my, mz, holdX, holdY, holdZ, t,
                )
            }
            Phase.DWELL -> {
                val dwellElapsed = phaseProgress * Cataloger.TOME_DWELL_TICKS
                val bob = Math.sin(dwellElapsed * 2.0 * Math.PI / DWELL_BOB_PERIOD) * DWELL_BOB_AMP
                Triple(holdX, holdY + bob, holdZ)
            }
            Phase.IN -> {
                val t = easeInOut(phaseProgress).toDouble()
                val (mx, my, mz) = shelfFaceControlPoint(
                    returnX, returnY, returnZ, eyeX, eyeY, eyeZ,
                )
                bezierPathWithArc(
                    holdX, holdY, holdZ, mx, my, mz, returnX, returnY, returnZ, t,
                )
            }
        }
    }

    /** Mid-control point [BOOKSHELF_FADE_DISTANCE] blocks along the
     *  face vector from the shelf toward the cataloger eye. Acts as
     *  the Bezier P1 for both outbound (where it's the second control
     *  point) and inbound (where it's also the second), making the
     *  curve tangent to the shelf face at the shelf endpoint. */
    private fun shelfFaceControlPoint(
        shelfX: Double, shelfY: Double, shelfZ: Double,
        targetX: Double, targetY: Double, targetZ: Double,
    ): Triple<Double, Double, Double> {
        val dx = targetX - shelfX
        val dy = targetY - shelfY
        val dz = targetZ - shelfZ
        val len = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.0001) return Triple(shelfX, shelfY, shelfZ)
        val scale = BOOKSHELF_FADE_DISTANCE / len
        return Triple(
            shelfX + dx * scale,
            shelfY + dy * scale,
            shelfZ + dz * scale,
        )
    }

    /** Quadratic Bezier `(1−t)²P0 + 2(1−t)t·P1 + t²·P2`, plus a small
     *  parabolic Y bump so endpoints at equal height still get a
     *  visible arc. */
    private fun bezierPathWithArc(
        p0x: Double, p0y: Double, p0z: Double,
        p1x: Double, p1y: Double, p1z: Double,
        p2x: Double, p2y: Double, p2z: Double,
        t: Double,
    ): Triple<Double, Double, Double> {
        val u = 1.0 - t
        val x = u * u * p0x + 2.0 * u * t * p1x + t * t * p2x
        val y = u * u * p0y + 2.0 * u * t * p1y + t * t * p2y +
            4.0 * ARC_PEAK_HEIGHT * t * (1.0 - t)
        val z = u * u * p0z + 2.0 * u * t * p1z + t * t * p2z
        return Triple(x, y, z)
    }

    /**
     * Yaw + pitch (degrees) the book should adopt at a bookshelf
     * endpoint. Pages-up direction points INTO the shelf (away from
     * the cataloger), so the spine reads as the side facing the
     * reader — exactly how a real book sits on a shelf with the spine
     * label visible.
     *
     * Maths: the rotation chain `Y(-yaw)·X(pitch)·Z(180)·X(-90)`
     * sends baked +Y to `(sin(yaw)·cos(pitch), sin(pitch),
     * -cos(yaw)·cos(pitch))`. We want this to equal `-(face vector)`
     * where face vector = `(target − bookshelf)/|face|`. Substituting
     * gives:
     *  - `yaw = atan2(-dx, dz)` — 180° around the +Y axis from the
     *    "pages-up toward cataloger" formulation.
     *  - `pitch = atan2(dy, sqrt(dx² + dz²))` — unchanged, since the
     *    `Y(180)` flip of yaw negates the +X and +Z components but
     *    not the +Y one.
     */
    fun bookshelfFaceAngles(
        bookshelfX: Double, bookshelfY: Double, bookshelfZ: Double,
        targetX: Double, targetY: Double, targetZ: Double,
    ): Pair<Float, Float> {
        val dx = targetX - bookshelfX
        val dy = targetY - bookshelfY
        val dz = targetZ - bookshelfZ
        val h = Math.sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
        val pitch = if (h > 0.0001) Math.toDegrees(Math.atan2(dy, h)).toFloat() else 0f
        return yaw to pitch
    }

    /** Euclidean distance from a point to a 1×1×1 block's AABB. Zero
     *  when the point is inside the block. Used to switch the
     *  bookshelf-aligned orientation on the moment the tome's centre
     *  crosses the shelf face, regardless of how much of the flight
     *  phase has elapsed. */
    fun distanceToBlockAABB(
        px: Double, py: Double, pz: Double,
        bx: Int, by: Int, bz: Int,
    ): Float {
        val minX = bx.toDouble()
        val minY = by.toDouble()
        val minZ = bz.toDouble()
        val maxX = bx + 1.0
        val maxY = by + 1.0
        val maxZ = bz + 1.0
        val dx = when {
            px < minX -> minX - px
            px > maxX -> px - maxX
            else -> 0.0
        }
        val dy = when {
            py < minY -> minY - py
            py > maxY -> py - maxY
            else -> 0.0
        }
        val dz = when {
            pz < minZ -> minZ - pz
            pz > maxZ -> pz - maxZ
            else -> 0.0
        }
        return Math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }
}
