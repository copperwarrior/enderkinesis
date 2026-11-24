package org.shipwrights.enderkinesis.client

import org.shipwrights.enderkinesis.block.QuiescentCradleFrame
import org.shipwrights.enderkinesis.blockentity.QuiescentCradleBlockEntity
import kotlin.math.exp
import kotlin.math.max

/** Client thread only. The eased look of one Quiescent Cradle, stepped each client tick and lerped each frame. */
class QuiescentCradleView {

    /** Shown gimbal angles; the dragged ring takes the drag, the others ease toward the server angles. */
    val angles = DoubleArray(QuiescentCradleFrame.RINGS)
    val prevAngles = DoubleArray(QuiescentCradleFrame.RINGS)

    /** Per ring, 1 while grabbed, fading after. */
    val glow = FloatArray(QuiescentCradleFrame.RINGS)

    /** 0 held in place, 1 letters fully loose. */
    var looseness = 0f
        private set
    var prevLooseness = 0f
        private set

    /** Scale of the inner two circles: 1 held, [OPEN_SCALE] released. */
    var open = 1f
        private set
    var prevOpen = 1f
        private set

    /** The ring the local player drags, or -1. */
    var dragRing = -1

    /** The ring under the local player's crosshair, or -1. */
    var hoverRing = -1

    /** True while the ship marker is under the local player's crosshair. */
    var markerHover = false

    /** 1 just after a reset, fading with the ring glow. */
    var markerFlash = 0f
        private set

    /** A reset: every ring and the marker light at once, then fade. */
    fun flashReset() {
        glow.fill(1f)
        markerFlash = 1f
    }

    /** Where the grip knob sits on [hoverRing] or [dragRing]: an angle in that ring's own plane, so it turns with the ring. */
    var knobAngle = 0.0

    /** Glyph layouts of the big circles, rebuilt by the renderer when a radius changes. */
    val layouts = arrayOfNulls<QuiescentCradleGlyphs.CircleLayout>(QuiescentCradleFrame.RINGS)

    /** The layout a radius change replaced; it fades out while [layouts] fades in, so the circles never pop. */
    val oldLayouts = arrayOfNulls<QuiescentCradleGlyphs.CircleLayout>(QuiescentCradleFrame.RINGS)

    /** Per ring, 0 just after a layout change to 1 when only the new layout shows. */
    val blend = FloatArray(QuiescentCradleFrame.RINGS) { 1f }
    val prevBlend = FloatArray(QuiescentCradleFrame.RINGS) { 1f }

    /** Shown circle radii, easing toward the radii of the held or last-held ship. */
    val shownRadius = DoubleArray(QuiescentCradleFrame.RINGS)
    val prevShownRadius = DoubleArray(QuiescentCradleFrame.RINGS)

    /** Render thread. Called when [ring] gets a new layout and its old one must fade out. */
    fun startBlend(ring: Int) {
        blend[ring] = 0f
        prevBlend[ring] = 0f
    }

    private var primed = false

    fun tick(cradle: QuiescentCradleBlockEntity) {
        val holding = cradle.holding
        if (!primed) {
            // A cradle that loads in shows its state at once, with no release animation.
            primed = true
            looseness = if (holding) 0f else 1f
            open = if (holding) 1f else OPEN_SCALE
            for (ring in angles.indices) {
                angles[ring] = cradle.angles[ring]
                shownRadius[ring] = QuiescentCradleFrame.circleRadius(ring, cradle.circleSphere)
            }
        }
        tickRadii(cradle)
        prevLooseness = looseness
        prevOpen = open
        System.arraycopy(angles, 0, prevAngles, 0, angles.size)
        open += ((if (holding) 1f else OPEN_SCALE) - open) * OPEN_EASE
        looseness += ((if (holding) 0f else 1f) - looseness) * (if (holding) GATHER_EASE else LOOSEN_EASE)
        for (ring in angles.indices) {
            if (ring == dragRing) {
                glow[ring] = 1f
                continue
            }
            angles[ring] += QuiescentCradleFrame.wrap(cradle.angles[ring] - angles[ring]) * TURN_EASE
            glow[ring] = max(0f, glow[ring] - GLOW_FADE)
        }
        markerFlash = max(0f, markerFlash - GLOW_FADE)
    }

    private fun tickRadii(cradle: QuiescentCradleBlockEntity) {
        System.arraycopy(shownRadius, 0, prevShownRadius, 0, shownRadius.size)
        System.arraycopy(blend, 0, prevBlend, 0, blend.size)
        for (ring in shownRadius.indices) {
            shownRadius[ring] += (QuiescentCradleFrame.circleRadius(ring, cradle.circleSphere) - shownRadius[ring]) * OPEN_EASE
            if (oldLayouts[ring] == null) continue
            blend[ring] += (1f - blend[ring]) * OPEN_EASE
            if (blend[ring] > BLEND_DONE) {
                oldLayouts[ring] = null
                blend[ring] = 1f
            }
        }
    }

    private companion object {
        const val BLEND_DONE = 0.995f
        const val OPEN_SCALE = 1.35f
        const val TICK_SECONDS = 0.05

        // Per-tick fractions of the demo's per-second rates.
        val OPEN_EASE = (1.0 - exp(-2.0 * TICK_SECONDS)).toFloat()
        val GATHER_EASE = (1.0 - exp(-2.5 * TICK_SECONDS)).toFloat()
        val LOOSEN_EASE = (1.0 - exp(-0.7 * TICK_SECONDS)).toFloat()
        val TURN_EASE = 1.0 - exp(-3.2 * TICK_SECONDS)
        const val GLOW_FADE = 0.035f
    }
}
