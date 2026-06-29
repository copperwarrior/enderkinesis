package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.physics.GerstnerOcean
import org.shipwrights.enderkinesis.physics.LatticeCatchZone
import org.shipwrights.enderkinesis.physics.LatticeRegistry
import org.shipwrights.enderkinesis.physics.Wake
import org.shipwrights.enderkinesis.physics.WakeSource
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Wave-displaced translucent mesh that paints each active crepusculite lattice's virtual sea.
 * Layered on top of the existing particle system rather than replacing it — flip [enabled] to
 * `false` (or skip the renderAll registration in the client inits) to fully fall back to the
 * particle-only render path. The lattice block-entity / physics / particle code is untouched.
 *
 * **Visual composition:**
 *  1. **Twilight gradient.** Per-vertex colour interpolates the existing OceanParticle palette
 *     by wave height — deep ender-green (#032620) at troughs, ender purple at the rest, foam
 *     green at crests. The wave's shape reads as colour as well as geometry.
 *  2. **Fresnel rim glow.** At grazing view angles, vertices brighten and white-shift, so the
 *     sea reads as a glowing slab from the side and a faint translucent skin from above.
 *  3. **Phantom under-mesh.** A second mesh ~0.2 blocks below the surface, with phase-offset
 *     waves and reduced amplitude/alpha, fakes optical depth without being volumetric.
 *
 * Drawn through the additive [OrbBeamLineRenderType.BEAM_LINE] so overlapping layers brighten
 * and the visual matches the rest of the mod's glowing-additive vocabulary. No fragment shader
 * required — all the magic lives in the per-vertex colour/alpha.
 */
object CrepusculiteLatticeMeshRenderer {

    /** Master toggle. False ⇒ no mesh; the existing particle path is unaffected either way. */
    @JvmField
    var enabled: Boolean = true

    /** Two-tier LOD so waves stay visible at distance without exploding vertex count.
     *
     *   - **Fine tier** at 0.75-block step, 0–48 blocks from the camera. Resolves capillary
     *     detail and Q≈0.90 sharp peaks at full quality.
     *   - **Coarse tier** at 3-block step, 48–200 blocks from the camera. Captures the main
     *     swell components (λ ≥ 13 blocks); capillary detail is below the new Nyquist limit
     *     and aliases away, but the swell + mid-chop reads cleanly. Roughly the same vertex
     *     budget as the fine tier despite covering ~16× the area, because step is 4× coarser.
     *
     *  Per-tile distance cull on the coarse tier excludes any tile whose centre is within
     *  the fine tier's outer radius, so the two passes don't overlap and re-add via the
     *  additive render type. */
    private const val FINE_MESH_STEP: Double = 0.75
    private const val FINE_RADIUS_BLOCKS: Double = 48.0

    private const val COARSE_MESH_STEP: Double = 3.0
    private const val COARSE_RADIUS_BLOCKS: Double = 200.0

    /** Phantom under-mesh y-offset. Small enough to keep parallax subtle; large enough to
     *  resolve as a separate layer when viewed from the side. */
    private const val PHANTOM_Y_OFFSET: Double = 0.20
    private const val PHANTOM_AMP_SCALE: Double = 0.85
    private const val PHANTOM_TIME_OFFSET_TICKS: Double = 8.0
    private const val PHANTOM_X_OFFSET: Double = 0.7
    private const val PHANTOM_Z_OFFSET: Double = 0.4
    private const val PHANTOM_ALPHA_SCALE: Float = 0.55f


    /** Deep ender-green (#032620). Used for wave troughs. */
    private const val DEEP_R: Float = 0.0118f
    private const val DEEP_G: Float = 0.1490f
    private const val DEEP_B: Float = 0.1255f

    /** Ender purple. Mid-range — the dominant body colour. */
    private const val ENDER_R: Float = 0.52f
    private const val ENDER_G: Float = 0.20f
    private const val ENDER_B: Float = 0.66f

    /** Foam green. Crests only. */
    private const val FOAM_R: Float = 0.30f
    private const val FOAM_G: Float = 1.00f
    private const val FOAM_B: Float = 0.55f


    /** Base alpha at head-on (cosθ = 1). View-angle falloff scales this down at grazing. */
    private const val TOP_BASE_ALPHA: Float = 0.50f

    /** Lerp factor toward white at full grazing — preserves the rim-glow *look* (silhouette
     *  edges read whiter) without the alpha boost that was driving the additive over-bright. */
    private const val FRESNEL_WHITE_SHIFT: Float = 0.30f

    /** Soft edge falloff at the zone's elliptical perimeter. Keeps the rectangular grid from
     *  showing a hard outer edge. */
    private const val FALLOFF_START: Double = 0.80

    /** View-angle alpha falloff exponent for the **top** layer. Higher → faster drop-off at
     *  grazing. The earlier "peak alpha drop" used wave height as the variable — but the
     *  user-perceived over-bright is at the **viewing angle**, where many wave fragments
     *  stack additively along the line of sight. Scaling alpha by `cosθ^k` makes each
     *  fragment contribute less when its surface is edge-on to the camera (where stacking
     *  is worst), and full strength when head-on (where stacking is minimal). */
    private const val TOP_VIEW_FALLOFF_POWER: Float = 0.7f

    /** Same idea for the phantom layer, but **stronger** — at grazing, the phantom is
     *  optically "behind" the top layer in screen space anyway, so heavy falloff there
     *  removes the double-add without losing the depth illusion at head-on viewing. */
    private const val PHANTOM_VIEW_FALLOFF_POWER: Float = 1.8f

    /** Floor on the view-angle factor so true edge-on surfaces don't completely vanish. */
    private const val MIN_VIEW_FACTOR: Float = 0.05f

    private val tmpNormal = Vector3d()

    fun renderAll(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraX: Double, cameraY: Double, cameraZ: Double,
        partialTicks: Float,
    ) {
        if (!enabled) return
        val level = Minecraft.getInstance().level ?: return
        val entries = LatticeRegistry.entries(level)
        if (entries.isEmpty()) return

        val matrix = poseStack.last().pose()
        val consumer = bufferSource.getBuffer(OrbBeamLineRenderType.BEAM_LINE)
        val time = level.gameTime.toDouble() + partialTicks.toDouble()

        // Build the wake-source list once per frame from VS2's loaded-ship enumeration.
        // Each source is a snapshot of (centre, velocity direction, speed, hull half-extents).
        // Both client and server can build identical lists from the same VS2-synced state, so
        // calling `Wake.heightAt(...)` with this list gives a deterministic answer without
        // any lattice-side network sync.
        val wakeSources = collectWakeSources(level)

        for ((be, zone) in entries) {
            // The lattice ocean stays on main FB even when its host ship is cloaked.
            // The concealment refraction shader uses `background = mainFB(originalUV)`
            // for the inside-bubble composite, so a lattice rendered to main FB is
            // preserved at full intensity all the way through the cloak transition —
            // `mixFactor = silhouette * shipSample.a * fade` reaches zero at full
            // cloak (fade = 1 - progress = 0), at which point the lattice is the
            // sole contributor to the pixel. Routing the lattice through the
            // concealed-FB pipeline (BEAM_LINE_CONCEALED) instead made it inherit the
            // ship's fade-out curve and read as "extremely dim during transition".
            val ship = level.getShipManagingPos(be.blockPos) as? ClientShip

            // Two-tier LOD: fine mesh near the camera, coarse mesh further out. Each
            // tier is rendered for both top and phantom layers. Coarse tier excludes
            // the inner radius (the fine tier's area) via per-tile distance cull so
            // they don't overlap.
            renderLayer(consumer, matrix, zone, ship, time, cameraX, cameraY, cameraZ, wakeSources,
                phantom = false, meshStep = FINE_MESH_STEP,
                outerRadius = FINE_RADIUS_BLOCKS, innerCullRadius = 0.0)
            renderLayer(consumer, matrix, zone, ship, time, cameraX, cameraY, cameraZ, wakeSources,
                phantom = true, meshStep = FINE_MESH_STEP,
                outerRadius = FINE_RADIUS_BLOCKS, innerCullRadius = 0.0)
            renderLayer(consumer, matrix, zone, ship, time, cameraX, cameraY, cameraZ, wakeSources,
                phantom = false, meshStep = COARSE_MESH_STEP,
                outerRadius = COARSE_RADIUS_BLOCKS, innerCullRadius = FINE_RADIUS_BLOCKS)
            renderLayer(consumer, matrix, zone, ship, time, cameraX, cameraY, cameraZ, wakeSources,
                phantom = true, meshStep = COARSE_MESH_STEP,
                outerRadius = COARSE_RADIUS_BLOCKS, innerCullRadius = FINE_RADIUS_BLOCKS)
        }
    }

    /** Walk VS2's loaded ships in [level], building a [WakeSource] per ship that's actually
     *  moving (above [Wake]'s MIN_SPEED threshold). Velocity is converted from VS2's
     *  blocks/SECOND units to the wake math's blocks/TICK convention. Hull half-extents are
     *  the ship's local-AABB extents projected onto the velocity axis (`Σ |ext · dir|`) —
     *  exact for axis-aligned hulls, a conservative upper bound otherwise; visually fine
     *  for first-cut wake. Empty list when there are no eligible ships → wake math no-ops. */
    private fun collectWakeSources(level: net.minecraft.client.multiplayer.ClientLevel): List<WakeSource> {
        val ships = level.shipObjectWorld.loadedShips
        if (ships.isEmpty()) return emptyList()
        val out = ArrayList<WakeSource>()
        for (ship in ships) {
            val cs = ship as? ClientShip ?: continue
            val aabb = cs.shipAABB ?: continue                          // *ship-local* AABB
            val vel = cs.velocity                                       // blocks/SECOND, world
            val vxPerSec = vel.x()
            val vzPerSec = vel.z()
            val speed3dPerSec = Math.sqrt(vxPerSec * vxPerSec + vzPerSec * vzPerSec)
            if (speed3dPerSec < 0.01) continue                          // stationary; skip
            val speed = speed3dPerSec * (1.0 / 20.0)                    // → blocks/TICK
            val dirX = vxPerSec / speed3dPerSec
            val dirZ = vzPerSec / speed3dPerSec
            // Hull half-extents projected onto the velocity axis MUST happen in the ship's
            // local frame — projecting ship-LOCAL extents onto a WORLD direction is correct
            // only for an unrotated ship, since otherwise the AABB's axes don't align with
            // world. Transform the velocity into local frame first, then project the local
            // AABB. `transformDirection` skips translation, so unit-scale ships preserve
            // magnitude (≈ 1).
            val localDir = org.joml.Vector3d(dirX, 0.0, dirZ)
            cs.renderTransform.worldToShip.transformDirection(localDir)
            val absLocalDirX = Math.abs(localDir.x)
            val absLocalDirZ = Math.abs(localDir.z)
            val xExt = (aabb.maxX() - aabb.minX() + 1).toDouble()
            val zExt = (aabb.maxZ() - aabb.minZ() + 1).toDouble()
            val halfLength = (xExt * absLocalDirX + zExt * absLocalDirZ) * 0.5
            val halfWidth  = (xExt * absLocalDirZ + zExt * absLocalDirX) * 0.5
            val pos = cs.renderTransform.positionInWorld                // interpolated visual XZ
            out.add(WakeSource(
                centerX = pos.x(),
                centerZ = pos.z(),
                dirX = dirX,
                dirZ = dirZ,
                speed = speed,
                halfLength = halfLength,
                halfWidth = halfWidth,
            ))
        }
        return out
    }

    private fun renderLayer(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        zone: LatticeCatchZone,
        ship: ClientShip?,
        time: Double,
        cameraX: Double, cameraY: Double, cameraZ: Double,
        wakeSources: List<WakeSource>,
        phantom: Boolean,
        meshStep: Double,
        outerRadius: Double,
        innerCullRadius: Double,
    ) {
        val level = Minecraft.getInstance().level ?: return
        // Window: intersect the zone's world XZ extents with a camera-centred radius. Then
        // **snap to a fixed world grid** at `meshStep` spacing — without this, tiny camera
        // movement shifts every tile position, which makes hull-boundary tiles toggle
        // between "inside" / "outside" the cull mask and produces flicker.
        val rawMinX = max(zone.cx - zone.ax, cameraX - outerRadius)
        val rawMaxX = min(zone.cx + zone.ax, cameraX + outerRadius)
        val rawMinZ = max(zone.cz - zone.az, cameraZ - outerRadius)
        val rawMaxZ = min(zone.cz + zone.az, cameraZ + outerRadius)
        if (rawMaxX - rawMinX < meshStep || rawMaxZ - rawMinZ < meshStep) return
        // floor/ceil to grid multiples so tile centres land at world coords that don't move
        // when the camera moves.
        val minX = Math.floor(rawMinX / meshStep) * meshStep
        val maxX = Math.ceil(rawMaxX / meshStep) * meshStep
        val minZ = Math.floor(rawMinZ / meshStep) * meshStep
        val maxZ = Math.ceil(rawMaxZ / meshStep) * meshStep

        val stepsX = max(2, ((maxX - minX) / meshStep).toInt())
        val stepsZ = max(2, ((maxZ - minZ) / meshStep).toInt())
        val dx = meshStep
        val dz = meshStep

        // Inner cull radius squared — used per-tile to skip tiles inside the previous LOD
        // tier's region (so the coarse tier doesn't double-draw atop the fine tier).
        val innerCullSq = innerCullRadius * innerCullRadius

        val vw = stepsX + 1
        val vh = stepsZ + 1
        val n = vw * vh
        val px = DoubleArray(n); val py = DoubleArray(n); val pz = DoubleArray(n)
        val nr = FloatArray(n); val ng = FloatArray(n); val nb = FloatArray(n); val na = FloatArray(n)

        // Phantom layer: y-offset, amplitude-scaled, phase-offset wave so it parallaxes against
        // the top layer. Time and XZ offsets shift the wave's "view" so the same world XZ samples
        // a different point in the wave function — produces interior current without a second
        // wave field.
        val layerY = if (phantom) -PHANTOM_Y_OFFSET else 0.0
        val layerAmpScale = if (phantom) PHANTOM_AMP_SCALE else 1.0
        val layerTime = if (phantom) time + PHANTOM_TIME_OFFSET_TICKS else time
        val xOff = if (phantom) PHANTOM_X_OFFSET else 0.0
        val zOff = if (phantom) PHANTOM_Z_OFFSET else 0.0
        val layerAlphaScale = if (phantom) PHANTOM_ALPHA_SCALE else 1.0f

        // Cloak fade: linearly attenuate vertex alpha by (1 − progress) for ship-mounted
        // lattices. At progress 0 the ocean reads at full intensity; at progress 1 it's
        // fully invisible. The additive render type means the fade reads as a smooth
        // dim-out rather than a hard cull, matching how the cloaked ship blocks fade.
        // Querying [ClientShipCloakingState] inside the renderer keeps the lookup
        // out of the per-vertex inner loop — `cloakFade` is constant across the layer.
        val cloakFade: Float = if (ship != null) {
            val progress = ClientShipCloakingState.getProgress(ship.id)
            (1f - progress).coerceIn(0f, 1f)
        } else 1f
        if (cloakFade <= 0f) return   // host fully cloaked — skip the whole layer

        val intensity = zone.waveIntensity * layerAmpScale

        // **Colour** uses a *smoothed* height field — only the dominant swell waves, not the
        // mid-chop and capillary components that drive geometric detail. Adjacent vertices on
        // the same long-wavelength swell land at similar `t01` values, so the GPU's per-quad
        // colour interpolation produces a clean gradient instead of the muddy mid-tone smear
        // that the full 16-wave colour sampling was producing (high-frequency colour variation
        // between neighbours interpolated linearly across a triangle reads as visual noise).
        // Geometry below still uses the full sharp [heightAt].
        val swellMax = max(1e-6, GerstnerOcean.swellMaxAmplitude * intensity)
        val invTwoSwellMax = 1.0 / (2.0 * swellMax)

        // Layer-specific view-angle falloff exponent (phantom drops harder at grazing).
        val viewFalloffPower = if (phantom) PHANTOM_VIEW_FALLOFF_POWER else TOP_VIEW_FALLOFF_POWER

        for (iz in 0..stepsZ) {
            val z = minZ + iz * dz
            for (ix in 0..stepsX) {
                val x = minX + ix * dx
                val h = GerstnerOcean.heightAt(x + xOff, z + zOff, layerTime, intensity)
                // Ship-driven wake: bow bunching ahead of moving ships + stern V-pattern
                // behind. Sums contributions from every nearby ship deterministically
                // (math is a pure function of (x, z, t, sources) — client/server agree
                // without networking). Phantom layer gets the same wake — the under-mesh's
                // parallax against the surface carries through naturally.
                val wakeH = Wake.heightAt(x + xOff, z + zOff, layerTime, wakeSources)
                val worldY = zone.waterLineY + layerY + h + wakeH
                val idx = iz * vw + ix
                px[idx] = x
                py[idx] = worldY
                pz[idx] = z

                // (1) Twilight gradient: deep → ender → foam by **swell-normalised** height.
                //     Swell-only sampling keeps adjacent-vertex colours close even when their
                //     full heights diverge (sharp peaks vs flat troughs), so triangle-edge
                //     colour interpolation stays clean.
                val swellH = GerstnerOcean.swellHeightAt(x + xOff, z + zOff, layerTime, intensity)
                val t01 = ((swellH + swellMax) * invTwoSwellMax).coerceIn(0.0, 1.0).toFloat()
                var cr: Float; var cg: Float; var cb: Float
                if (t01 < 0.5f) {
                    val u = t01 * 2f
                    cr = lerp(DEEP_R, ENDER_R, u)
                    cg = lerp(DEEP_G, ENDER_G, u)
                    cb = lerp(DEEP_B, ENDER_B, u)
                } else {
                    val u = (t01 - 0.5f) * 2f
                    cr = lerp(ENDER_R, FOAM_R, u)
                    cg = lerp(ENDER_G, FOAM_G, u)
                    cb = lerp(ENDER_B, FOAM_B, u)
                }

                // (2) View-angle factor. `cosθ = dot(normal, viewDir)`. Head-on = 1, edge-on = 0.
                //     Used both to drive the colour rim-white shift (preserved) AND to *reduce*
                //     alpha at grazing — counteracts the additive over-bright the user sees at
                //     glancing angles, where many wave fragments stack in line of sight.
                GerstnerOcean.normalAt(x + xOff, z + zOff, layerTime, intensity, tmpNormal)
                val vx = cameraX - x
                val vy = cameraY - worldY
                val vz = cameraZ - z
                val vLen = sqrt(vx * vx + vy * vy + vz * vz)
                val cosTheta = if (vLen > 1e-9) {
                    (tmpNormal.x * vx + tmpNormal.y * vy + tmpNormal.z * vz) / vLen
                } else 1.0
                // `abs(cosθ)` so the wave is equally visible from above AND below — the surface
                // is two-sided (no backface culling in the render state). Without abs, viewing
                // from underneath gives a negative dot product → clamped to 0 → near-zero alpha
                // (after MIN_VIEW_FACTOR floor), which reads as if the back face is hidden.
                val cosThetaF = Math.abs(cosTheta).coerceIn(0.0, 1.0).toFloat()
                val grazing = 1f - cosThetaF                                  // 0 = head-on, 1 = grazing
                val rimR = lerp(cr, 1f, grazing * FRESNEL_WHITE_SHIFT)
                val rimG = lerp(cg, 1f, grazing * FRESNEL_WHITE_SHIFT)
                val rimB = lerp(cb, 1f, grazing * FRESNEL_WHITE_SHIFT)

                // View-angle alpha falloff. `cosθ^power` is 1 head-on, drops smoothly to
                // `MIN_VIEW_FACTOR` at grazing. Phantom uses a higher power so it falls off
                // faster, eliminating the screen-space double-add at angles.
                val viewAlphaFactor = max(MIN_VIEW_FACTOR, cosThetaF.pow(viewFalloffPower))

                // Soft edge: radial dimming over the outer 20 % of the zone ellipse.
                val rxN = (x - zone.cx) / zone.ax
                val rzN = (z - zone.cz) / zone.az
                val r = sqrt(rxN * rxN + rzN * rzN)
                val edge = when {
                    r >= 1.0 -> 0f
                    r > FALLOFF_START ->
                        ((1.0 - (r - FALLOFF_START) / (1.0 - FALLOFF_START)).coerceIn(0.0, 1.0)).toFloat()
                    else -> 1f
                }

                // Hull-footprint cull at the **vertex** — pass the lattice's *static*
                // `zone.waterLineY` so the cull pattern doesn't oscillate with wave motion.
                // Per-vertex wave-displaced Y would otherwise fold into ship-local Z (on
                // rolled/pitched ships) via the rotation's off-diagonal terms by
                // `±maxAmp · sin(roll)` *every frame*, which is the flicker. The lateral-
                // interpolation leak that vertex-Y was trying to address is now structurally
                // gone — the fade lives in EMPTY_NEAR_HULL (outside the hull), so a small
                // static lookup offset on tilted ships can't bleed alpha across the hull
                // into an interior cell.
                // Smoothed bilinear-sampled hull factor. The discrete state-bin lookup was
                // snapping alpha values whenever a vertex crossed a cache-cell boundary —
                // and since vertices cross cell boundaries every frame as the ship moves,
                // that produced the per-frame flicker ("quads immediately become cutout, and
                // vice versa, permutated"). Bilinear at the sub-cell offset makes the alpha
                // vary continuously with motion. Hull-side cells (BOUNDARY / INTERIOR /
                // ENCLOSED) still all map to 0 inside the sampler, so any 2×2 sample
                // entirely inside the ship still gives 0 — no lateral leak across the hull.
                val hullFactor = if (ship != null)
                    CrepusculiteLatticeFootprintCache.alphaAt(level, ship, x, zone.waterLineY, z) else 1f

                val alpha = TOP_BASE_ALPHA * viewAlphaFactor * edge * layerAlphaScale * hullFactor * cloakFade
                nr[idx] = rimR; ng[idx] = rimG; nb[idx] = rimB
                na[idx] = alpha.coerceIn(0f, 1f)
            }
        }

        // QUADS mode — 4 verts per tile, CCW from above; render type has NO_CULL so winding
        // doesn't matter. Skip tiles whose four corners are all alpha=0 (outside the
        // elliptical falloff OR fully inside the hull interior). Per-vertex 3-state hull
        // cull above gives a soft boundary that meets the hull silhouette: interior columns
        // → alpha 0, boundary columns → partial alpha, empty columns → full alpha.
        for (iz in 0 until stepsZ) {
            for (ix in 0 until stepsX) {
                val i00 = iz * vw + ix
                val i10 = iz * vw + (ix + 1)
                val i11 = (iz + 1) * vw + (ix + 1)
                val i01 = (iz + 1) * vw + ix
                if (na[i00] + na[i10] + na[i11] + na[i01] <= 0.001f) continue

                // LOD inner-cull: skip tiles whose centre is inside the previous tier's
                // outer radius. The fine tier passes `innerCullRadius = 0` so this is a
                // no-op; the coarse tier passes the fine tier's `outerRadius` so it draws
                // only the ring outside the fine mesh.
                if (innerCullSq > 0.0) {
                    val tcx = (px[i00] + px[i11]) * 0.5 - cameraX
                    val tcz = (pz[i00] + pz[i11]) * 0.5 - cameraZ
                    if (tcx * tcx + tcz * tcz < innerCullSq) continue
                }

                emit(consumer, matrix,
                    px[i00] - cameraX, py[i00] - cameraY, pz[i00] - cameraZ,
                    nr[i00], ng[i00], nb[i00], na[i00])
                emit(consumer, matrix,
                    px[i10] - cameraX, py[i10] - cameraY, pz[i10] - cameraZ,
                    nr[i10], ng[i10], nb[i10], na[i10])
                emit(consumer, matrix,
                    px[i11] - cameraX, py[i11] - cameraY, pz[i11] - cameraZ,
                    nr[i11], ng[i11], nb[i11], na[i11])
                emit(consumer, matrix,
                    px[i01] - cameraX, py[i01] - cameraY, pz[i01] - cameraZ,
                    nr[i01], ng[i01], nb[i01], na[i01])
            }
        }
    }

    private fun emit(
        consumer: VertexConsumer, matrix: Matrix4f,
        x: Double, y: Double, z: Double,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        consumer.vertex(matrix, x.toFloat(), y.toFloat(), z.toFloat())
            .color(
                (r * 255f).toInt().coerceIn(0, 255),
                (g * 255f).toInt().coerceIn(0, 255),
                (b * 255f).toInt().coerceIn(0, 255),
                (a * 255f).toInt().coerceIn(0, 255),
            )
            .endVertex()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
