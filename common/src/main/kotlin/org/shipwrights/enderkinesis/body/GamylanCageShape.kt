package org.shipwrights.enderkinesis.body

import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.shape.CompoundBodyShapeData
import org.valkyrienskies.mod.common.vsCore

/** Compound collision shape for a Ga'Mylan cage crystal — a hexagonal
 *  bipyramid that's tight against the rendered crystal mesh.
 *
 *  Geometry mirrors [org.shipwrights.enderkinesis.client.GamylanCageRenderer]:
 *  rx is the equatorial hex CIRCUMRADIUS (vertex distance from the
 *  axis), midH is the half-height of the middle prism, tipLen is the
 *  pyramid height of each terminus.
 *
 *  Decomposition (10 children):
 *   - 6 middle prism slabs at Y = 0, each a box of `(apothem, 2·midH,
 *     rx)` at `apothem/2` outward along one hex face and yawed by
 *     `-β` so its outer face passes through the two adjacent hex
 *     vertices. Union covers the equator hex prism exactly.
 *   - 2 spheres per tip (4 total): a large one at the pyramid base
 *     tangent to the equator plane and to all six hex faces of the
 *     pyramid (`r0 = α·tipLen / (1+α)`, `α = apothem/slantLen`), plus
 *     a smaller one tangent to the first and inscribed in the
 *     remaining cap (`r1 = r0·q`, `q = (1−α)/(1+α)`).
 *
 *  Boxes cannot taper — any box-only decomposition ends up as either
 *  a step-cylinder (stacked hex prisms) or an hourglass (tilted
 *  parallelograms), neither of which captures the pyramid shape. The
 *  taper comes from spheres; two per tip is what the mesh actually
 *  reads as (a big base sphere and a smaller tip sphere), so the
 *  collision matches what the player sees.
 */
object GamylanCageShape {

    /** Minimum length on any dimension — degenerate captures still get
     *  a meaningful collision footprint. */
    private const val MIN_DIM: Double = 0.6

    /** Small clearance (blocks) between the captured entity's culling
     *  AABB and the crystal shell — kept in lockstep with the renderer's
     *  own `PADDING`. Public so [org.shipwrights.enderkinesis.body.SoderShape]
     *  can invert the shape-dims → mob-size math without duplicating
     *  the constant.
     *
     *  Sizing already includes the XZ-corner-to-corner diagonal (see
     *  [fromCullingBox]) so a tight non-clipping value is enough here —
     *  we don't need to inflate further to cover asymmetric mobs. */
    const val PADDING: Double = 0.15

    /** Small absolute inflation (blocks) applied to every collision
     *  primitive's extents — keeps the rendered crystal mesh a hair
     *  inside the body's collision hull, so the visual quads don't
     *  Z-fight terrain when the body comes to rest on the ground.
     *  Imperceptible at gameplay distance; ~3 cm. */
    private const val COLLISION_INFLATION: Double = 0.03

    private val SQRT3_OVER_2: Double = Math.sqrt(3.0) / 2.0

    /** Derive crystal-shell radii from a captured entity's culling
     *  AABB. `rx` (hex circumradius) is sized to the XZ corner-to-corner
     *  diagonal so the crystal envelope contains the mob regardless of
     *  which XZ axis is wider or how it's rotated inside the cage;
     *  `midH` uses the AABB's Y size. */
    fun fromCullingBox(xSize: Double, ySize: Double, zSize: Double): Vector3d {
        val diagonalXZ = Math.sqrt(xSize * xSize + zSize * zSize)
        val halfW = (diagonalXZ * 0.5 + PADDING).coerceAtLeast(MIN_DIM)
        val halfH = (ySize * 0.5 + PADDING).coerceAtLeast(MIN_DIM)
        return Vector3d(halfW, halfH, halfW)
    }

    /** Pack the dims as a wire-friendly Vector3d: (rx, midH, tipLen). */
    fun dims(rx: Double, midH: Double, tipLen: Double): Vector3d = Vector3d(rx, midH, tipLen)

    /** Conservative bounding sphere radius around the whole crystal —
     *  useful for broad-phase culling and client raytrace. The crystal's
     *  furthest point from the body centre is each apex tip at distance
     *  `midH + tipLen` along ±Y, or each equator vertex at distance `rx`
     *  in the XZ plane. Includes the collision inflation so the sphere
     *  still envelops the inflated compound. */
    fun boundingRadius(dims: Vector3d): Double {
        val tip = dims.y + dims.z + 2.0 * COLLISION_INFLATION
        val rx = dims.x + COLLISION_INFLATION
        return Math.max(tip, rx)
    }

    /** Number of tapering spheres per pyramid tip. Two — one big base
     *  sphere, one smaller tip sphere — is what the visible mesh
     *  actually reads as; anything past that shrinks by another factor
     *  of `q` and is invisible in collisions at typical crystal
     *  scales. */
    private const val TIP_SPHERE_COUNT: Int = 2

    fun newCompoundShape(dims: Vector3d): CompoundBodyShapeData {
        // Inflated dims for collision (see [COLLISION_INFLATION]).
        // Inflating in absolute units keeps the offset constant under
        // every entity size — small captures get the same Z-clear gap
        // as large ones.
        val rx = dims.x + COLLISION_INFLATION
        val midH = dims.y + COLLISION_INFLATION
        val tipLen = dims.z + COLLISION_INFLATION
        val apothem = rx * SQRT3_OVER_2
        val prismH = 2.0 * midH

        val children = ArrayList<CompoundBodyShapeData.Child>(6 + 2 * TIP_SPHERE_COUNT)

        // Middle prism — 6 axis-aligned box slabs at Y = 0, one per
        // hex face. The outer face of each slab passes through the two
        // adjacent hex vertices, so the union covers the equator hex
        // prism exactly.
        val middleSlabSize = Vector3d(apothem, prismH, rx)
        for (i in 0 until 6) {
            val beta = Math.PI / 6.0 + i * Math.PI / 3.0
            val rot = Quaterniond().rotateY(-beta)
            val pos = Vector3d(
                0.5 * apothem * Math.cos(beta),
                0.0,
                0.5 * apothem * Math.sin(beta),
            )
            children.add(
                vsCore.newCompoundBodyShapeChild(
                    vsCore.newBoxBodyShape(Vector3d(middleSlabSize)),
                    pos,
                    rot,
                    1.0,
                    Vector3d(),
                ),
            )
        }

        // Tip sphere stack — inscribed geometric-series spheres along
        // the apex axis. `α = apothem/slantLen` is the sine of the
        // angle between the apex axis and each pyramid face's inward
        // normal. `r0` is the largest sphere, tangent to both the
        // equator plane and all six hex faces of the pyramid; each
        // subsequent sphere shrinks by `q` and sits tangent to the
        // previous one, inscribed in the remaining pyramid cap.
        val alpha = apothem / Math.sqrt(apothem * apothem + tipLen * tipLen)
        val q = (1.0 - alpha) / (1.0 + alpha)
        val r0 = alpha * tipLen / (1.0 + alpha)
        for (sign in intArrayOf(+1, -1)) {
            val baseY = sign * midH
            var ri = r0
            for (i in 0 until TIP_SPHERE_COUNT) {
                val zi = tipLen - ri / alpha
                val y = baseY + sign * zi
                children.add(
                    vsCore.newCompoundBodyShapeChild(
                        vsCore.newSphereBodyShape(ri),
                        Vector3d(0.0, y, 0.0),
                        Quaterniond(),
                        1.0,
                        Vector3d(),
                    ),
                )
                ri *= q
            }
        }

        return vsCore.newCompoundBodyShape(children)
    }
}
