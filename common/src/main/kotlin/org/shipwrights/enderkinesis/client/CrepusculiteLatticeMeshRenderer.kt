package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceProvider
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
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
import kotlin.math.sqrt

/**
 * Wave-displaced translucent mesh that paints each active crepusculite lattice's virtual sea.
 * Layered on top of the existing particle system rather than replacing it — flip [enabled] to
 * `false` (or skip the renderAll registration in the client inits) to fully fall back to the
 * particle-only render path. The lattice block-entity / physics / particle code is untouched.
 *
 * **Visual composition:**
 *  1. **Twilight gradient.** The colour interpolates the existing OceanParticle palette
 *     by wave height — deep ender-green (#032620) at troughs, ender purple at the rest, foam
 *     green at crests. The wave's shape reads as colour as well as geometry.
 *  2. **Fresnel rim glow.** At grazing view angles, the surface brightens and white-shifts, so the
 *     sea reads as a glowing slab from the side and a faint translucent skin from above.
 *  3. **Phantom under-mesh.** A second mesh ~0.2 blocks below the surface, with phase-offset
 *     waves and reduced amplitude/alpha, fakes optical depth without being volumetric.
 *
 * Drawn through the additive [OrbBeamLineRenderType.BEAM_LINE] so overlapping layers brighten
 * and the visual matches the rest of the mod's glowing-additive vocabulary. Each vertex carries
 * its swell height, normal and base alpha; `rendertype_lattice_ocean` does the colour, the
 * shore and the TV static grain per pixel.
 */
object CrepusculiteLatticeMeshRenderer {
    private val LOG = com.mojang.logging.LogUtils.getLogger()

    /** Master toggle. False ⇒ no mesh; the existing particle path is unaffected either way. */
    @JvmField
    var enabled: Boolean = true

    private var staticShader: ShaderInstance? = null
    private var surfaceShader: ShaderInstance? = null

    /** Upper limit of the shader `Time` uniform, in ticks. Keeps the float hash input small. */
    private const val STATIC_TIME_PERIOD = 512.0

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(
                    namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_lattice_ocean",
                    DefaultVertexFormat.POSITION_COLOR,
                )
            ) { instance -> staticShader = instance }
            sink.registerShader(
                ShaderInstance(
                    namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_lattice_surface",
                    DefaultVertexFormat.POSITION_COLOR,
                )
            ) { instance -> surfaceShader = instance }
        }
    }

    // Same workaround as ScryingRenderTypes: ShaderInstance fails on a namespaced shader name.
    private fun namespaced(delegate: ResourceProvider, namespace: String) =
        ResourceProvider { loc ->
            if (loc.namespace == ResourceLocation.DEFAULT_NAMESPACE) {
                val attempt = delegate.getResource(ResourceLocation(namespace, loc.path))
                if (attempt.isPresent) attempt else delegate.getResource(loc)
            } else {
                delegate.getResource(loc)
            }
        }

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
        var phantom = false
        var hullMask: CrepusculiteLatticeHullMask.Mask? = null
        var zone: LatticeCatchZone? = null
        var worldToShip: org.joml.Matrix4dc? = null
        var shoreSafe = 0f
        var lobe0 = 0f; var lobe1 = 0f; var lobe2 = 0f

        // worker-side vertex grid scratch
        var vw = 0
        var stepsX = 0
        var stepsZ = 0
        var px = DoubleArray(0); var py = DoubleArray(0); var pz = DoubleArray(0)
        var nr = FloatArray(0); var ng = FloatArray(0); var nb = FloatArray(0); var na = FloatArray(0)
        // Mid-height and a flat normal: the bordering quads blend toward these values.
        fun clearVertex(i: Int) {
            nr[i] = 0.5f; ng[i] = 0.5f; nb[i] = 0.5f; na[i] = 0f
        }
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
        val cameraX: Double, val cameraZ: Double,
        val wakeSources: List<WakeSource>,
        val cloakFade: Float,
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

    /** Which of the two wave layers gets the depth fade at blocks: false = the top layer. */
    private const val SOFT_EDGE_ON_PHANTOM = false


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

    private val tmpNormal = Vector3d()
    private val maskMatD = org.joml.Matrix4d()
    private val maskMatF = Matrix4f()
    private val surfaceModelView = Matrix4f()
    private val identity = Matrix4f()
    private val surfacePasses = arrayOfNulls<PassBuffer>(4)
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
            CrepusculiteLatticeHullMask.evict(frameCounter)
            CrepusculiteLatticeShoreMap.evict(frameCounter)
        }

        val wakeSources = collectWakeSources(level)
        val now = System.nanoTime()
        var anyData = false

        CrepusculiteLatticeOceanVolume.begin(poseStack, cameraX, cameraY, cameraZ)

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
            // The cloak fade and the lattice power fade. All layers and the volume use the product.
            val cloakFade: Float = zone.strength * if (ship != null) {
                (1f - ClientShipCloakingState.getProgress(ship.id)).coerceIn(0f, 1f)
            } else 1f
            val hullMask = if (ship != null && cloakFade > 0f) {
                CrepusculiteLatticeHullMask.maskFor(level, ship, frameCounter, time)
            } else null
            val worldToShip = if (hullMask != null) ship?.renderTransform?.worldToShip else null

            val shore = org.shipwrights.enderkinesis.physics.LatticeShore
            val circumference = Math.PI * (zone.ax + zone.az)
            val shoreSafe = shore.interiorSafeRadius(zone.ax, zone.az).toFloat()
            val lobe0 = shore.lobeCount(0, circumference).toFloat()
            val lobe1 = shore.lobeCount(1, circumference).toFloat()
            val lobe2 = shore.lobeCount(2, circumference).toFloat()

            val baseKey = be.blockPos.asLong() shl 2
            for (pass in 0..3) {
                val phantom = pass == 1 || pass == 3
                val coarse = pass >= 2
                val pb = passCaches.getOrPut(baseKey + pass) { PassBuffer() }
                pb.lastUsedFrame = frameCounter
                surfacePasses[pass] = pb
                pb.phantom = phantom
                pb.drawThisFrame = cloakFade > 0f
                pb.hullMask = hullMask
                pb.zone = zone
                pb.worldToShip = worldToShip
                pb.shoreSafe = shoreSafe
                pb.lobe0 = lobe0; pb.lobe1 = lobe1; pb.lobe2 = lobe2

                if (cloakFade > 0f && !pb.building && pb.pending.get() == null &&
                    now - pb.lastBuildNanos >= REBUILD_NANOS
                ) {
                    pb.building = true
                    pb.lastBuildNanos = now
                    val job = BuildJob(
                        zone, time, cameraX, cameraZ, wakeSources, cloakFade,
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

            val shoreMap = if (cloakFade > 0f) {
                CrepusculiteLatticeShoreMap.mapFor(be.blockPos.asLong(), zone, time, frameCounter)
            } else null
            if (shoreMap != null && CrepusculiteLatticeOceanVolume.beginHeightMap(zone, wakeSources.isNotEmpty())) {
                // The top layer and the phantom layer: the higher of the two is the surface.
                for (pb in surfacePasses) drawSurface(zone, pb)
                VertexBuffer.unbind()
                CrepusculiteLatticeOceanVolume.draw(zone, cloakFade, dyeDimOf(zone), shoreMap, hullMask, worldToShip)
            }
        }
        CrepusculiteLatticeOceanVolume.end()
        if (!anyData) return

        // Draw all retained buffers directly — additive blending commutes, so drawing
        // ahead of the batched consumers at this stage is order-safe.
        val pose = poseStack.last().pose()
        OrbBeamLineRenderType.BEAM_LINE.setupRenderState()
        val projection = RenderSystem.getProjectionMatrix()
        // No fallback shader: the vertex colours are packed shader inputs, not colours.
        val shader = staticShader
        if (shader != null) {
            val u = oceanUniforms?.takeIf { it.shader === shader } ?: OceanUniforms(shader).also { oceanUniforms = it }
            val shore = org.shipwrights.enderkinesis.physics.LatticeShore
            // Phases in [-PI, PI]: a float cannot hold game time to a fraction of a tick.
            val drift0 = wrapPhase(shore.driftPhase(0, time))
            val drift1 = wrapPhase(shore.driftPhase(1, time))
            val drift2 = wrapPhase(shore.driftPhase(2, time))
            u.time.set((time % STATIC_TIME_PERIOD).toFloat())
            // Read-only use of the bound depth: the mesh does not write depth. StaffGemGlow does the same.
            shader.setSampler("SceneDepth", Minecraft.getInstance().mainRenderTarget.depthTextureId)
            for (pb in passCaches.values) {
                if (!pb.hasData || !pb.drawThisFrame || pb.lastUsedFrame != frameCounter) continue
                val vb = pb.vertexBuffer ?: continue
                val modelView = Matrix4f(pose).translate(
                    (pb.originX - cameraX).toFloat(),
                    (pb.originY - cameraY).toFloat(),
                    (pb.originZ - cameraZ).toFloat(),
                )
                applyHullMask(shader, u, pb, time)
                applyShore(u, pb, drift0, drift1, drift2)
                applyColour(u, pb, cameraX, cameraY, cameraZ)
                u.softEdge.set(if (pb.phantom == SOFT_EDGE_ON_PHANTOM) 1f else 0f)
                vb.bind()
                vb.drawWithShader(modelView, projection, shader)
            }
            VertexBuffer.unbind()
        }
        OrbBeamLineRenderType.BEAM_LINE.clearRenderState()
    }

    /** One retained wave mesh pass, top-down, into the bound height map. The mesh does not change. */
    private fun drawSurface(zone: LatticeCatchZone, pb: PassBuffer?) {
        if (pb == null || !pb.hasData) return
        val vb = pb.vertexBuffer ?: return
        val shader = surfaceShader ?: return
        CrepusculiteLatticeOceanVolume.heightMatrix(zone, pb.originX, pb.originY, pb.originZ, surfaceModelView)
        vb.bind()
        vb.drawWithShader(surfaceModelView, identity, shader)
    }

    private class OceanUniforms(val shader: ShaderInstance) {
        val time = shader.safeGetUniform("Time")
        val softEdge = shader.safeGetUniform("SoftEdge")
        val maskMat = shader.safeGetUniform("MaskMat")
        val maskSize = shader.safeGetUniform("MaskSize")
        val maskBlend = shader.safeGetUniform("MaskBlend")
        val prevMaskMat = shader.safeGetUniform("PrevMaskMat")
        val prevMaskSize = shader.safeGetUniform("PrevMaskSize")
        val shoreOffset = shader.safeGetUniform("ShoreOffset")
        val zoneYaw = shader.safeGetUniform("ZoneYaw")
        val zoneRadii = shader.safeGetUniform("ZoneRadii")
        val shoreSafe = shader.safeGetUniform("ShoreSafe")
        val shoreLobes = shader.safeGetUniform("ShoreLobes")
        val shoreDrift = shader.safeGetUniform("ShoreDrift")
        val cameraOffset = shader.safeGetUniform("CameraOffset")
        val deepColor = shader.safeGetUniform("DeepColor")
        val tintColor = shader.safeGetUniform("TintColor")
        val foamColor = shader.safeGetUniform("FoamColor")
        val viewFalloff = shader.safeGetUniform("ViewFalloff")
    }

    // A shader reload makes a new ShaderInstance, so the identity test refreshes the handles.
    private var oceanUniforms: OceanUniforms? = null

    private fun applyHullMask(shader: ShaderInstance, u: OceanUniforms, pb: PassBuffer, time: Double) {
        val mask = pb.hullMask
        val worldToShip = pb.worldToShip
        if (mask == null || worldToShip == null) {
            u.maskSize.set(0, 0, 0)
            u.maskBlend.set(1f)
            return
        }
        u.maskMat.set(maskMatrix(pb, worldToShip, mask.texMinX, mask.texMinY, mask.texMinZ))
        u.maskSize.set(mask.texW, mask.texH, mask.texD)
        shader.setSampler("HullMask", mask.textureId)
        val blend = mask.blendAt(time)
        u.maskBlend.set(blend)
        if (blend < 1f) {
            u.prevMaskMat.set(maskMatrix(pb, worldToShip, mask.prevMinX, mask.prevMinY, mask.prevMinZ))
            u.prevMaskSize.set(mask.prevW, mask.prevH, mask.prevD)
            shader.setSampler("PrevHullMask", mask.prevTextureId)
        }
    }

    // The fragment shader draws the shore of the current zone, so the coast moves at the frame rate.
    private fun applyShore(u: OceanUniforms, pb: PassBuffer, drift0: Float, drift1: Float, drift2: Float) {
        val zone = pb.zone ?: return
        u.shoreOffset.set((pb.originX - zone.cx).toFloat(), (pb.originZ - zone.cz).toFloat())
        u.zoneYaw.set(zone.yawCos.toFloat(), zone.yawSin.toFloat())
        u.zoneRadii.set(zone.ax.toFloat(), zone.az.toFloat())
        u.shoreSafe.set(pb.shoreSafe)
        u.shoreLobes.set(pb.lobe0, pb.lobe1, pb.lobe2)
        u.shoreDrift.set(drift0, drift1, drift2)
    }

    private fun applyColour(u: OceanUniforms, pb: PassBuffer, cameraX: Double, cameraY: Double, cameraZ: Double) {
        val zone = pb.zone ?: return
        u.cameraOffset.set(
            (pb.originX - cameraX).toFloat(), (pb.originY - cameraY).toFloat(), (pb.originZ - cameraZ).toFloat(),
        )
        u.deepColor.set(zone.tintDeepR, zone.tintDeepG, zone.tintDeepB)
        u.tintColor.set(zone.tintR, zone.tintG, zone.tintB)
        u.foamColor.set(zone.tintFoamR, zone.tintFoamG, zone.tintFoamB)
        u.viewFalloff.set(if (pb.phantom) PHANTOM_VIEW_FALLOFF_POWER else TOP_VIEW_FALLOFF_POWER)
    }

    private fun wrapPhase(phase: Double): Float = Math.IEEEremainder(phase, 2.0 * Math.PI).toFloat()

    // Mesh position -> world -> shipyard -> mask cell. Doubles: shipyard coordinates are large.
    private fun maskMatrix(pb: PassBuffer, worldToShip: org.joml.Matrix4dc, minX: Int, minY: Int, minZ: Int): Matrix4f {
        maskMatD.translation(-minX.toDouble(), -minY.toDouble(), -minZ.toDouble())
            .mul(worldToShip)
            .translate(pb.originX, pb.originY, pb.originZ)
        return maskMatF.set(maskMatD)
    }

    // Render transforms, not tick transforms: the wake must follow the drawn ship each frame.
    private fun collectWakeSources(level: net.minecraft.client.multiplayer.ClientLevel): List<WakeSource> {
        // A new list each frame: a BuildJob on the worker can still read the last one.
        var out: ArrayList<WakeSource>? = null
        for (ship in level.shipObjectWorld.loadedShips) {
            val cs = ship as? ClientShip ?: continue
            val aabb = cs.shipAABB ?: continue
            val transform = cs.renderTransform
            val source = Wake.sourceOf(aabb, cs.velocity, transform.worldToShip, transform.positionInWorld) ?: continue
            if (out == null) out = ArrayList()
            out.add(source)
        }
        return out ?: emptyList()
    }


    /** WORKER-THREAD pass build: pure math over snapshotted inputs (zone, camera,
     *  wake) — no level/ship/GL access. Produces a RenderedBuffer with
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

        // Dye luminance dims the whole mesh: with additive blending a black tint's
        // BODY is already zero, but the fresnel white-shift and foam lightening kept
        // it clearly visible — scale alpha by tint luminance so black dye reads
        // near-invisible and gray sits where black used to. Un-dyed keeps full look.
        val dyeDim = dyeDimOf(zone)

        // Hot-loop precomputation. Inside [interiorSafe] the shore math is identity
        // (taper 1) — the bulk of the ocean skips every atan2/warp evaluation. The coarse tier bands the wave spectrum: capillaries (λ ≤ 1.4)
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
                    pb.clearVertex(idx)
                    continue
                }

                // The shore edge is per fragment in rendertype_lattice_ocean.fsh, so every vertex
                // in the ellipse keeps its alpha.
                val taper = if (rRaw <= interiorSafe) 1.0 else {
                    val theta = kotlin.math.atan2(rzN * zone.az, rxN * zone.ax)
                    val rDir = shore.rDirAt(theta, zone.ax, zone.az)
                    shore.amplitudeTaper(rRaw / shore.rimWarpAt(theta, rDir, zone.waveTime, circumference))
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
                if (lodFade <= 0f) {
                    val approxH = GerstnerOcean.swellHeightAt(x + xOff, z + zOff, layerTime, intensity)
                    py[idx] = flatY + approxH * taper
                    pb.clearVertex(idx)
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

                // rendertype_lattice_ocean.fsh does the colour per pixel. Gerstner normals point up,
                // so the shader gets y back from x and z.
                nr[idx] = ((tmpSwell[0] + swellMax) * invTwoSwellMax).coerceIn(0.0, 1.0).toFloat()
                ng[idx] = (tmpNormal.x * 0.5 + 0.5).toFloat()
                nb[idx] = (tmpNormal.z * 0.5 + 0.5).toFloat()
                na[idx] = (TOP_BASE_ALPHA * layerAlphaScale * cloakFade * lodFade * dyeDim).coerceIn(0f, 1f)
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


    /** Runs pure math on the mesh worker thread. */
    internal fun runOnWorker(task: Runnable) = buildExecutor.execute(task)

    private fun dyeDimOf(zone: LatticeCatchZone): Float {
        if (!zone.dyed) return 1f
        val luma = 0.2126f * zone.tintR + 0.7152f * zone.tintG + 0.0722f * zone.tintB
        return DYE_DIM_MIN + (1f - DYE_DIM_MIN) * luma.coerceIn(0f, 1f)
    }

}
