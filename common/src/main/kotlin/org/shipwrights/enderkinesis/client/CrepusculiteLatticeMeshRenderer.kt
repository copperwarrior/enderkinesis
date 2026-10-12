package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
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
    private val LOG = com.mojang.logging.LogUtils.getLogger()

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
    /** Cached per-pass vertex grid (world-space positions + colours), rebuilt on
     *  alternating frames. Keyed by lattice blockPos × pass index. */
    /** Per-(lattice × pass) retained GPU buffer + worker-side build state. The
     *  worker thread owns `builder` and the scratch arrays; the render thread owns
     *  `vertexBuffer`; hand-off is the `pending` atomic. */
    private class PassBuffer {
        var vertexBuffer: VertexBuffer? = null          // render-thread only
        var builder: BufferBuilder? = null              // worker-thread only
        val pending = java.util.concurrent.atomic.AtomicReference<Prepared?>(null)
        @Volatile var building = false
        var lastBuildNanos = 0L
        var hasData = false
        var originX = 0.0; var originY = 0.0; var originZ = 0.0
        var lastUsedFrame = 0L
        /** Render-thread per-frame gate — false while the host ship is fully cloaked
         *  (builds pause, so the retained buffer would otherwise draw stale). */
        var drawThisFrame = false

        // worker-side vertex grid scratch
        var vw = 0
        var stepsX = 0
        var stepsZ = 0
        var px = DoubleArray(0); var py = DoubleArray(0); var pz = DoubleArray(0)
        var nr = FloatArray(0); var ng = FloatArray(0); var nb = FloatArray(0); var na = FloatArray(0)
        fun ensure(n: Int) {
            if (px.size < n) {
                px = DoubleArray(n); py = DoubleArray(n); pz = DoubleArray(n)
                nr = FloatArray(n); ng = FloatArray(n); nb = FloatArray(n); na = FloatArray(n)
            }
        }
    }

    private class Prepared(
        val rendered: BufferBuilder.RenderedBuffer,
        val originX: Double, val originY: Double, val originZ: Double,
        val vertexCount: Int,
    )

    /** Immutable inputs for one worker-side pass build — everything snapshotted on
     *  the render thread so the worker never touches level/ship state. */
    private class BuildJob(
        val zone: LatticeCatchZone,
        val time: Double,
        val cameraX: Double, val cameraY: Double, val cameraZ: Double,
        val wakeSources: List<WakeSource>,
        val cloakFade: Float,
        val footprint: CrepusculiteLatticeFootprintCache.Snapshot?,
        val phantom: Boolean, val lodFadeIn: Boolean,
        val meshStep: Double, val outerRadius: Double, val innerCullRadius: Double,
    )

    private val passCaches = HashMap<Long, PassBuffer>()
    private var frameCounter = 0L
    private val tmpSwell = DoubleArray(1)
    private val buildExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "EK-lattice-mesh").apply { isDaemon = true }
    }
    /** Worker rebuild cadence — 30 Hz, above the 20 Hz wave field. */
    private const val REBUILD_NANOS = 33_000_000L

    private const val FINE_MESH_STEP: Double = 0.75
    private const val FINE_RADIUS_BLOCKS: Double = 48.0

    private const val COARSE_MESH_STEP: Double = 3.0
    /** Half-width (blocks) of the fine↔coarse crossfade band around [FINE_RADIUS_BLOCKS]. */
    private const val LOD_BLEND_HALF: Double = 6.0
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

    /** Alpha floor for a black-dyed mesh (dye dimming is luminance-linear above it). */
    private const val DYE_DIM_MIN: Float = 0.08f

    /** Lerp factor toward white at full grazing — preserves the rim-glow *look* (silhouette
     *  edges read whiter) without the alpha boost that was driving the additive over-bright. */
    private const val FRESNEL_WHITE_SHIFT: Float = 0.30f

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
    private val authorityByShip = it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.core.BlockPos>()

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

        val time = level.gameTime.toDouble() + partialTicks.toDouble()
        frameCounter++
        if (frameCounter % 256L == 0L) {
            val it = passCaches.values.iterator()
            while (it.hasNext()) {
                val pb = it.next()
                if (frameCounter - pb.lastUsedFrame > 600L && !pb.building && pb.pending.get() == null) {
                    pb.vertexBuffer?.close()
                    pb.builder?.discard()
                    it.remove()
                }
            }
        }

        val wakeSources = collectWakeSources(level)
        val now = System.nanoTime()
        var anyData = false

        // Owner rule: one ocean for each ship, from its highest lattice.
        authorityByShip.clear()
        for ((be, _) in entries) {
            val shipId = level.getShipManagingPos(be.blockPos)?.id ?: continue
            val best = authorityByShip.get(shipId)
            if (best == null || org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity.comparePos(be.blockPos, best) > 0) {
                authorityByShip.put(shipId, be.blockPos)
            }
        }

        for ((be, zone) in entries) {
            // Lattice ocean stays on main FB through cloaking (see BEAM_LINE notes) —
            // the fade is baked into the vertex build via cloakFade.
            val ship = level.getShipManagingPos(be.blockPos) as? ClientShip
            if (ship != null && authorityByShip.get(ship.id) != be.blockPos) continue
            val cloakFade: Float = if (ship != null) {
                (1f - ClientShipCloakingState.getProgress(ship.id)).coerceIn(0f, 1f)
            } else 1f
            val footprint = if (ship != null && cloakFade > 0f) {
                CrepusculiteLatticeFootprintCache.snapshot(level, ship)
            } else null

            val baseKey = be.blockPos.asLong() shl 2
            for (pass in 0..3) {
                val phantom = pass == 1 || pass == 3
                val coarse = pass >= 2
                val pb = passCaches.getOrPut(baseKey + pass) { PassBuffer() }
                pb.lastUsedFrame = frameCounter
                pb.drawThisFrame = cloakFade > 0f

                if (cloakFade > 0f && !pb.building && pb.pending.get() == null &&
                    now - pb.lastBuildNanos >= REBUILD_NANOS
                ) {
                    pb.building = true
                    pb.lastBuildNanos = now
                    val job = BuildJob(
                        zone, time, cameraX, cameraY, cameraZ, wakeSources, cloakFade, footprint,
                        phantom = phantom,
                        lodFadeIn = coarse,
                        meshStep = when {
                            !coarse && !phantom -> FINE_MESH_STEP
                            !coarse -> FINE_MESH_STEP * 2.0
                            !phantom -> COARSE_MESH_STEP
                            else -> COARSE_MESH_STEP * 2.0
                        },
                        outerRadius = if (coarse) COARSE_RADIUS_BLOCKS else FINE_RADIUS_BLOCKS + LOD_BLEND_HALF,
                        innerCullRadius = if (coarse) {
                            FINE_RADIUS_BLOCKS - LOD_BLEND_HALF - COARSE_MESH_STEP
                        } else 0.0,
                    )
                    buildExecutor.execute { buildPass(pb, job) }
                }

                // Upload a finished build (GL — render thread only).
                pb.pending.getAndSet(null)?.let { prep ->
                    if (prep.vertexCount == 0) {
                        prep.rendered.release()
                        pb.hasData = false
                    } else {
                        val vb = pb.vertexBuffer ?: VertexBuffer(VertexBuffer.Usage.DYNAMIC)
                            .also { pb.vertexBuffer = it }
                        vb.bind()
                        vb.upload(prep.rendered)
                        VertexBuffer.unbind()
                        pb.originX = prep.originX
                        pb.originY = prep.originY
                        pb.originZ = prep.originZ
                        pb.hasData = true
                    }
                }
                if (pb.hasData && cloakFade > 0f) anyData = true
            }
        }
        if (!anyData) return

        // Draw all retained buffers directly — additive blending commutes, so drawing
        // ahead of the batched consumers at this stage is order-safe.
        val pose = poseStack.last().pose()
        OrbBeamLineRenderType.BEAM_LINE.setupRenderState()
        val projection = RenderSystem.getProjectionMatrix()
        val shader = RenderSystem.getShader()
        if (shader != null) {
            for (pb in passCaches.values) {
                if (!pb.hasData || !pb.drawThisFrame || pb.lastUsedFrame != frameCounter) continue
                val vb = pb.vertexBuffer ?: continue
                val modelView = Matrix4f(pose).translate(
                    (pb.originX - cameraX).toFloat(),
                    (pb.originY - cameraY).toFloat(),
                    (pb.originZ - cameraZ).toFloat(),
                )
                vb.bind()
                vb.drawWithShader(modelView, projection, shader)
            }
            VertexBuffer.unbind()
        }
        OrbBeamLineRenderType.BEAM_LINE.clearRenderState()
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


    /** WORKER-THREAD pass build: pure math over snapshotted inputs (zone, camera,
     *  wake, footprint) — no level/ship/GL access. Produces a RenderedBuffer with
     *  vertices relative to the zone centre; the render thread uploads and draws it. */
    private fun buildPass(pb: PassBuffer, job: BuildJob) {
        try {
            buildPassInner(pb, job)
        } catch (t: Throwable) {
            LOG.error("[EK] lattice mesh build failed", t)
            pb.pending.set(null)
        } finally {
            pb.building = false
        }
    }

    private fun buildPassInner(pb: PassBuffer, job: BuildJob) {
        val zone = job.zone
        val time = job.time
        val cameraX = job.cameraX
        val cameraY = job.cameraY
        val cameraZ = job.cameraZ
        val wakeSources = job.wakeSources
        val cloakFade = job.cloakFade
        val phantom = job.phantom
        val lodFadeIn = job.lodFadeIn
        val meshStep = job.meshStep
        val outerRadius = job.outerRadius
        val innerCullRadius = job.innerCullRadius
        val originX = zone.cx
        val originY = zone.waterLineY
        val originZ = zone.cz

        // Window: intersect the zone's world XZ extents with a camera-centred radius. Then
        // **snap to a fixed world grid** at `meshStep` spacing — without this, tiny camera
        // movement shifts every tile position, which makes hull-boundary tiles toggle
        // between "inside" / "outside" the cull mask and produces flicker.
        val rawMinX = max(zone.cx - zone.boundX, cameraX - outerRadius)
        val rawMaxX = min(zone.cx + zone.boundX, cameraX + outerRadius)
        val rawMinZ = max(zone.cz - zone.boundZ, cameraZ - outerRadius)
        val rawMaxZ = min(zone.cz + zone.boundZ, cameraZ + outerRadius)
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

        val vw = stepsX + 1
        val vh = stepsZ + 1
        val n = vw * vh
        pb.ensure(n)
        pb.vw = vw
        pb.stepsX = stepsX
        pb.stepsZ = stepsZ
        val px = pb.px; val py = pb.py; val pz = pb.pz
        val nr = pb.nr; val ng = pb.ng; val nb = pb.nb; val na = pb.na

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

        // Dye luminance dims the whole mesh: with additive blending a black tint's
        // BODY is already zero, but the fresnel white-shift and foam lightening kept
        // it clearly visible — scale alpha by tint luminance so black dye reads
        // near-invisible and gray sits where black used to. Un-dyed keeps full look.
        val dyeDim: Float = if (zone.dyed) {
            val luma = 0.2126f * zone.tintR + 0.7152f * zone.tintG + 0.0722f * zone.tintB
            DYE_DIM_MIN + (1f - DYE_DIM_MIN) * luma.coerceIn(0f, 1f)
        } else 1f

        // Hot-loop precomputation. Inside [interiorSafe] the shore math is identity
        // (taper 1, edge 1) — the bulk of the ocean skips every atan2/warp/tendril
        // evaluation. The coarse tier bands the wave spectrum: capillaries (λ ≤ 1.4)
        // are below Nyquist at its 3-block step and were pure aliasing noise.
        val shore = org.shipwrights.enderkinesis.physics.LatticeShore
        val interiorSafe = shore.interiorSafeRadius(zone.ax, zone.az)
        val circumference = Math.PI * (zone.ax + zone.az)
        val waveCount =
            if (meshStep > 1.0) GerstnerOcean.NON_CAPILLARY_COUNT else GerstnerOcean.waveCount
        val bandInner = FINE_RADIUS_BLOCKS - LOD_BLEND_HALF
        val bandOuter = FINE_RADIUS_BLOCKS + LOD_BLEND_HALF
        val bandInnerSq = bandInner * bandInner
        val bandOuterSq = bandOuter * bandOuter
        val flatY = zone.waterLineY + layerY

        for (iz in 0..stepsZ) {
            val z = minZ + iz * dz
            for (ix in 0..stepsX) {
                val x = minX + ix * dx
                val idx = iz * vw + ix
                px[idx] = x
                pz[idx] = z

                // ---- Cheap rejections first: most vertices never reach the wave math.
                val rxN = zone.localNx(x, z)
                val rzN = zone.localNz(x, z)
                val rRaw = sqrt(rxN * rxN + rzN * rzN)
                if (rRaw >= 1.0) {
                    // Beyond the ellipse: taper is exactly 0 there, so flat IS the
                    // true geometry — bordering quads interpolate seamlessly.
                    py[idx] = flatY
                    na[idx] = 0f
                    continue
                }

                // Shore factors — identity inside the safe radius, one shared
                // theta/rDir/warp evaluation (taper + tendril edge) otherwise.
                val taper: Double
                val edge: Float
                if (rRaw <= interiorSafe) {
                    taper = 1.0
                    edge = 1f
                } else {
                    val theta = kotlin.math.atan2(rzN * zone.az, rxN * zone.ax)
                    val rDir = shore.rDirAt(theta, zone.ax, zone.az)
                    val rWarped =
                        rRaw / shore.rimWarpAt(theta, rDir, zone.waveTime, circumference)
                    taper = shore.amplitudeTaper(rWarped)
                    edge = shore.shoreEdgeAt(rWarped, theta, rDir, zone.waveTime, circumference)
                }

                // LOD crossfade — sqrt only inside the blend band; a zero-fade vertex
                // gets cheap swell-only geometry (it borders visible quads) and no
                // colour/normal work.
                val ddx = x - cameraX
                val ddz = z - cameraZ
                val distSq = ddx * ddx + ddz * ddz
                val lodFade: Float = when {
                    distSq <= bandInnerSq -> if (lodFadeIn) 0f else 1f
                    distSq >= bandOuterSq -> if (lodFadeIn) 1f else 0f
                    else -> {
                        val bandT = ((Math.sqrt(distSq) - bandInner) / (2.0 * LOD_BLEND_HALF))
                            .coerceIn(0.0, 1.0)
                        val bandS = (bandT * bandT * (3.0 - 2.0 * bandT)).toFloat()
                        if (lodFadeIn) bandS else 1f - bandS
                    }
                }
                if (lodFade <= 0f || edge <= 0f) {
                    val approxH = GerstnerOcean.swellHeightAt(x + xOff, z + zOff, layerTime, intensity)
                    py[idx] = flatY + approxH * taper
                    na[idx] = 0f
                    continue
                }

                // ---- Full path: fused height+normal (one shared wave loop; coarse
                // tier drops the sub-Nyquist capillaries), wake, colour, view factor.
                val h = GerstnerOcean.sampleHeightNormal(
                    x + xOff, z + zOff, layerTime, intensity, waveCount, tmpNormal, tmpSwell,
                )
                val wakeH = Wake.heightAt(x + xOff, z + zOff, layerTime, wakeSources)
                val worldY = flatY + (h + wakeH) * taper
                py[idx] = worldY

                // Twilight gradient by swell-normalised height — the swell partial
                // falls out of the fused sampler for free.
                val t01 = ((tmpSwell[0] + swellMax) * invTwoSwellMax).coerceIn(0.0, 1.0).toFloat()
                var cr: Float; var cg: Float; var cb: Float
                if (t01 < 0.5f) {
                    val u = t01 * 2f
                    cr = lerp(zone.tintDeepR, zone.tintR, u)
                    cg = lerp(zone.tintDeepG, zone.tintG, u)
                    cb = lerp(zone.tintDeepB, zone.tintB, u)
                } else {
                    val u = (t01 - 0.5f) * 2f
                    cr = lerp(zone.tintR, zone.tintFoamR, u)
                    cg = lerp(zone.tintG, zone.tintFoamG, u)
                    cb = lerp(zone.tintB, zone.tintFoamB, u)
                }

                // View-angle factor from the (already computed) surface normal.
                val vx = cameraX - x
                val vy = cameraY - worldY
                val vz = cameraZ - z
                val vLen = sqrt(vx * vx + vy * vy + vz * vz)
                val cosTheta = if (vLen > 1e-9) {
                    (tmpNormal.x * vx + tmpNormal.y * vy + tmpNormal.z * vz) / vLen
                } else 1.0
                // abs: the surface is two-sided (no backface culling) — equally
                // visible from above and below.
                val cosThetaF = Math.abs(cosTheta).coerceIn(0.0, 1.0).toFloat()
                val grazing = 1f - cosThetaF
                val rimR = lerp(cr, 1f, grazing * FRESNEL_WHITE_SHIFT)
                val rimG = lerp(cg, 1f, grazing * FRESNEL_WHITE_SHIFT)
                val rimB = lerp(cb, 1f, grazing * FRESNEL_WHITE_SHIFT)
                val viewAlphaFactor = max(MIN_VIEW_FACTOR, cosThetaF.pow(viewFalloffPower))

                // Hull-footprint factor: bilinear vs the STATIC waterline (see
                // FootprintCache) so the cull pattern doesn't oscillate with waves.
                val hullFactor = job.footprint?.alphaAt(x, zone.waterLineY, z) ?: 1f

                val alpha = TOP_BASE_ALPHA * viewAlphaFactor * edge * layerAlphaScale * hullFactor * cloakFade * lodFade * dyeDim
                nr[idx] = rimR; ng[idx] = rimG; nb[idx] = rimB
                na[idx] = alpha.coerceIn(0f, 1f)
            }
        }

        // Emit surviving quads into the worker-owned builder, origin-relative.
        val builder = pb.builder ?: BufferBuilder(786432).also { pb.builder = it }
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        val innerCullSq = innerCullRadius * innerCullRadius
        var vertexCount = 0
        for (iz in 0 until stepsZ) {
            for (ix in 0 until stepsX) {
                val i00 = iz * vw + ix
                val i10 = iz * vw + (ix + 1)
                val i11 = (iz + 1) * vw + (ix + 1)
                val i01 = (iz + 1) * vw + ix
                if (na[i00] + na[i10] + na[i11] + na[i01] <= 0.001f) continue
                if (innerCullSq > 0.0) {
                    val tcx = (px[i00] + px[i11]) * 0.5 - cameraX
                    val tcz = (pz[i00] + pz[i11]) * 0.5 - cameraZ
                    if (tcx * tcx + tcz * tcz < innerCullSq) continue
                }
                builder.vertex(px[i00] - originX, py[i00] - originY, pz[i00] - originZ)
                    .color(nr[i00], ng[i00], nb[i00], na[i00]).endVertex()
                builder.vertex(px[i10] - originX, py[i10] - originY, pz[i10] - originZ)
                    .color(nr[i10], ng[i10], nb[i10], na[i10]).endVertex()
                builder.vertex(px[i11] - originX, py[i11] - originY, pz[i11] - originZ)
                    .color(nr[i11], ng[i11], nb[i11], na[i11]).endVertex()
                builder.vertex(px[i01] - originX, py[i01] - originY, pz[i01] - originZ)
                    .color(nr[i01], ng[i01], nb[i01], na[i01]).endVertex()
                vertexCount += 4
            }
        }
        pb.pending.set(Prepared(builder.end(), originX, originY, originZ, vertexCount))
    }


    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
