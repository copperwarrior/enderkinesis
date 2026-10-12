package org.shipwrights.enderkinesis.body

import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.shape.CompoundBodyShapeData
import org.valkyrienskies.mod.common.vsCore

/** Collision compound for the "Tribulla" easter-egg cage — the
 *  hex bipyramid in [GamylanCageShape] is replaced by a 6-box stack
 *  matching the renderer's soder bottle model. Each child box mirrors
 *  one of the six Blockbench boxes, non-uniformly scaled so the
 *  bottle's bounding volume tracks the captured entity's AABB:
 *  bbWidth drives X & Z, bbHeight drives Y.
 *
 *  Box dims and offsets are kept in lockstep with
 *  [org.shipwrights.enderkinesis.client.SoderRenderer] — both files
 *  read from the same canonical model + Y-flip-and-centre math.
 */
object SoderShape {

    private const val PX_PER_BLOCK: Double = 16.0
    /** Model-pixel offset that puts the BODY-LABEL BOX'S CENTRE at the
     *  body origin. Body box model-Y centre is `-7.5`. See
     *  [org.shipwrights.enderkinesis.client.SoderRenderer]'s identical
     *  constant — both files must agree. */
    private const val Y_CENTRE_PX: Double = 7.5

    /** Natural soder BODY-LABEL BOX dimensions in blocks at scale = 1.
     *  These (not the whole-bottle bounds) drive the fit factor, so the
     *  captured entity's AABB exactly fills the label area. Sized to
     *  the -0.1 visual deform `SoderRenderer` uses for the body box so
     *  the body doesn't clip into the shoulder / base; ride-along
     *  pieces (base, shoulder, neck, cap) keep their own dimensions. */
    private const val BODY_BOX_NATURAL_WIDTH_BLOCKS: Double = 4.8 / PX_PER_BLOCK
    private const val BODY_BOX_NATURAL_HEIGHT_BLOCKS: Double = 6.8 / PX_PER_BLOCK

    /** Floor on the scale factor — degenerate / zero-AABB captures
     *  still get a meaningful soder collision hull. */
    private const val MIN_SCALE: Double = 0.5

    /** Per-axis inflation (blocks) so visual quads sit just inside the
     *  hull and don't Z-fight terrain when the bottle comes to rest —
     *  same trick as [GamylanCageShape.COLLISION_INFLATION]. */
    private const val COLLISION_INFLATION: Double = 0.03

    /** Tuple of (centre-in-model-pixels, size-in-model-pixels) for each
     *  of the soder model's six boxes. Order: base, body, shoulder,
     *  neck, cap collar, cap top. Deformations are pre-applied (the
     *  body's +0.3, the cap collar's -0.1, the cap top's +0.2). */
    private val BOXES: List<Box> = listOf(
        Box(0.0, -2.0, 0.0,    6.0, 4.0, 6.0),    // base
        Box(0.0, -7.5, 0.0,    4.8, 6.8, 4.8),    // body (-0.1 visual deform for clearance)
        Box(0.0, -12.0, 0.0,   6.0, 2.0, 6.0),    // shoulder
        Box(0.0, -14.0, 0.0,   4.0, 2.0, 4.0),    // neck
        Box(0.0, -15.2, 0.0,   1.8, 0.8, 1.8),    // cap collar (-0.1 deform)
        Box(0.0, -16.25, 0.0,  2.4, 1.4, 2.4),    // cap top (+0.2 deform)
    )

    private data class Box(
        val cx: Double, val cy: Double, val cz: Double,
        val sx: Double, val sy: Double, val sz: Double,
    )

    fun newCompoundShape(entityCullSize: Vector3d): CompoundBodyShapeData {
        // Fit the bottle to the captured entity's culling AABB rather
        // than back-computing from the crystal envelope. `entityCullSize`
        // is stored on GamylanCageData at capture time — the crystal now
        // sizes off the XZ diagonal, so any inverse from shapeDims would
        // recover a diagonal, not the mob's actual width.
        val bbWidth = Math.max(entityCullSize.x, entityCullSize.z).coerceAtLeast(0.1)
        val bbHeight = entityCullSize.y.coerceAtLeast(0.1)
        // UNIFORM fit factor — pick whichever axis ratio is larger so
        // the body box contains the entity AABB on both axes, then use
        // that single scale on all three. Keeps the bottle's
        // proportions intact (no vertical stretching).
        val scale = Math.max(
            bbWidth / BODY_BOX_NATURAL_WIDTH_BLOCKS,
            bbHeight / BODY_BOX_NATURAL_HEIGHT_BLOCKS,
        ).coerceAtLeast(MIN_SCALE)
        val xScale = scale
        val yScale = scale

        val children = ArrayList<CompoundBodyShapeData.Child>(BOXES.size)
        val identity = Quaterniond()
        for (box in BOXES) {
            // Convert model pixel centre → body block centre:
            // X / Z unchanged (model already centred on axis); Y flipped
            // (model -Y up) and shifted by Y_CENTRE_PX so the model's
            // mid-point sits at the body origin. Then scale by the fit
            // factor.
            val centreXBlocks = box.cx / PX_PER_BLOCK * xScale
            val centreYBlocks = (-box.cy - Y_CENTRE_PX) / PX_PER_BLOCK * yScale
            val centreZBlocks = box.cz / PX_PER_BLOCK * xScale
            val sizeXBlocks = box.sx / PX_PER_BLOCK * xScale + COLLISION_INFLATION
            val sizeYBlocks = box.sy / PX_PER_BLOCK * yScale + COLLISION_INFLATION
            val sizeZBlocks = box.sz / PX_PER_BLOCK * xScale + COLLISION_INFLATION
            children.add(
                vsCore.newCompoundBodyShapeChild(
                    vsCore.newBoxBodyShape(Vector3d(sizeXBlocks, sizeYBlocks, sizeZBlocks)),
                    Vector3d(centreXBlocks, centreYBlocks, centreZBlocks),
                    identity,
                    1.0,
                    Vector3d(),
                ),
            )
        }
        return vsCore.newCompoundBodyShape(children)
    }

    /** Conservative bounding sphere radius around the soder hull at
     *  the given shape dims — used by broad-phase culling + the
     *  client raytrace. With the body-box-centred origin the bottle
     *  reaches further in `+Y` (cap end) than `-Y` (base end); take
     *  whichever side is larger. */
    fun boundingRadius(entityCullSize: Vector3d): Double {
        val bbWidth = Math.max(entityCullSize.x, entityCullSize.z).coerceAtLeast(0.1)
        val bbHeight = entityCullSize.y.coerceAtLeast(0.1)
        val xScale = (bbWidth / BODY_BOX_NATURAL_WIDTH_BLOCKS).coerceAtLeast(MIN_SCALE)
        val yScale = (bbHeight / BODY_BOX_NATURAL_HEIGHT_BLOCKS).coerceAtLeast(MIN_SCALE)
        // Bottle spans model Y from -16.95 (cap) to 0 (base) — after
        // flip+centre, +Y reach is (16.95 - Y_CENTRE_PX) px and -Y reach
        // is Y_CENTRE_PX px. Max wins for the bounding sphere.
        val maxModelHalfHeightPx = Math.max(16.95 - Y_CENTRE_PX, Y_CENTRE_PX)
        val halfH = maxModelHalfHeightPx / PX_PER_BLOCK * yScale + COLLISION_INFLATION
        // Widest box (base / shoulder) is 6 px across.
        val halfW = (6.0 / PX_PER_BLOCK / 2.0) * xScale + COLLISION_INFLATION
        return Math.max(halfH, halfW)
    }

}
