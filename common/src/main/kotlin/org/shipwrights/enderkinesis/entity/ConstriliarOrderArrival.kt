package org.shipwrights.enderkinesis.entity

import net.minecraft.core.Direction

/** The go-to order's arrival rule, kept apart from the entity so a unit test can run it. */
internal object ConstriliarOrderArrival {
    const val RADIUS = 1.25

    /** Arrival at a face order: how far out from the face, and how far along it. */
    const val FACE_OUT = 1.5
    const val ALONG = 1.25

    /** Lets a body that used up its route drift onto the spot before the order fails. */
    const val SPENT_GRACE_TICKS = 40L

    const val NOT_SPENT = Long.MIN_VALUE

    /** A body at ([px], [py], [pz]) is at the spot ([tx], [ty], [tz]): within [RADIUS] for a floor order, or on the
     *  [face] side of the spot and near along it for a face order. [slack] widens both bands. */
    @Suppress("LongParameterList") // Two unboxed points keep the per-tick call free of allocation.
    fun arrived(
        px: Double, py: Double, pz: Double, tx: Double, ty: Double, tz: Double, face: Direction?, slack: Double = 0.0,
    ): Boolean {
        val dx = px - tx
        val dy = py - ty
        val dz = pz - tz
        val d2 = dx * dx + dy * dy + dz * dz
        if (face == null) return d2 <= (RADIUS + slack) * (RADIUS + slack)
        val along = ALONG + slack
        val out = dx * face.stepX + dy * face.stepY + dz * face.stepZ
        if (out < -slack || out > FACE_OUT + slack) return false
        return d2 - out * out <= along * along
    }

    /** True when a whole route ran out at [spentSince] with the body off the spot, and the grace ended by [now]. */
    fun spentRouteFails(spentSince: Long, now: Long): Boolean =
        spentSince != NOT_SPENT && now - spentSince >= SPENT_GRACE_TICKS
}
