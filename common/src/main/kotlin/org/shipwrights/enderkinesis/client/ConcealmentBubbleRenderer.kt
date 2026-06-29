package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import com.mojang.math.Axis
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import org.joml.Matrix4dc
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector4f
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.shipObjectWorld

/** Renders each cloaked ship's bubble — an **ellipsoid circumscribed around the
 *  ship-local AABB** (the eight AABB corners lie on the ellipsoid surface; the entire
 *  ship volume is contained within) — to [ConcealmentMaskFB]. The bubble is generated
 *  in *shipyard space* and transformed vertex-by-vertex through
 *  `renderTransform.shipToWorld`, so it follows the ship's orientation exactly instead
 *  of a world-axis-aligned box.
 *
 *  The mask FB has its OWN depth attachment, seeded each pass with a blit of main FB
 *  depth. Bubbles depth-test against that seeded depth (so terrain / walls / hills
 *  in front of the cloaked ship block the mask write at that pixel) AND write their
 *  own depth as they draw — so when multiple cloaked ships' bubbles overlap in screen
 *  space, the closest bubble at each pixel wins and that ship's per-pixel cloak
 *  progress is what the post-shader sees there. Without the depth WRITE, a back
 *  bubble's progress could leak into the front bubble's silhouette (and vice versa
 *  via the previous GL_MAX blend), producing a wrong fade/refraction at overlaps.
 *
 *  Hooked from a `LevelRenderer.renderLevel` TAIL inject — by that point every chunk
 *  layer + every entity has populated the main depth buffer, so the occlusion test is
 *  authoritative. The post-effect runs next and refracts the masked pixels.
 *
 *  Vertex color's red channel carries the **cloak progress** for that ship, so the
 *  single-pass post-shader reads per-pixel intensity from the mask. */
object ConcealmentBubbleRenderer {

    /** Half-axis scale so the ellipsoid is circumscribed around the ship-local AABB —
     *  the eight corners of the AABB land exactly on the ellipsoid surface. Derived from
     *  `1/a² + 1/b² + 1/c² = 1` with uniform `a = b = c = √3`. */
    private val CIRCUMSCRIBE: Double = Math.sqrt(3.0)

    /** UV-sphere tessellation. 12×20 quads = 240 quads per ship. Smoothness vs. fill
     *  cost trade-off — at typical viewing distances 12 latitudes already reads as
     *  curved rather than faceted. */
    private const val LAT_STEPS = 12
    private const val LON_STEPS = 20

    /** Last frame's dominant cloak's screen-space centre (UV) and progress, written by
     *  this renderer and read by [ConcealmentPostEffect] to drive the radial-ripple
     *  shader. "Dominant" = ship with the highest cloak progress this frame (so during a
     *  release the ripple still emanates from the releasing ship). One-frame stale,
     *  which is fine for a ship that isn't moving at warp. */
    @JvmField @Volatile var lastShipCenterU: Float = 0.5f
    @JvmField @Volatile var lastShipCenterV: Float = 0.5f
    @JvmField @Volatile var lastProgress: Float = 0f

    /** Called from [org.shipwrights.enderkinesis.mixin.LevelRendererBubblePassMixin]
     *  at the TAIL of `LevelRenderer.renderLevel`. */
    @JvmStatic
    fun renderBubbles(camera: Camera, projectionMatrix: Matrix4f) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val ships = ClientShipCloakingState.activeShipIds()
        if (ships.isEmpty()) return

        val cam = camera.position

        // Build a clean view matrix from the camera — the renderLevel TAIL injection
        // point isn't a guaranteed matrix state, so set our own.
        val pose = PoseStack()
        pose.mulPose(Axis.XP.rotationDegrees(camera.xRot))
        pose.mulPose(Axis.YP.rotationDegrees(camera.yRot + 180f))
        val view = Matrix4f(pose.last().pose())

        val savedView = Matrix4f(RenderSystem.getModelViewMatrix())
        val savedProj = Matrix4f(RenderSystem.getProjectionMatrix())
        RenderSystem.getModelViewMatrix().set(view)
        RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorting.DISTANCE_TO_ORIGIN)

        ConcealmentMaskFB.bindForWrite()
        // Seed mask FB depth with current main FB depth so bubbles still
        // occlude against terrain. Bubbles will then write their own depth
        // into the mask FB's OWN depth attachment as they draw (main FB
        // depth is untouched).
        ConcealmentMaskFB.copyMainDepth()

        // Bubble draw state: no cull (so being inside the bubble still marks
        // the surrounding world), depth test ≤ (mask only where bubble is in
        // front of / coincident with current depth — terrain OR another
        // bubble's near face), depth WRITES on so front bubbles occlude back
        // bubbles → per-pixel progress carries the closest cloak's value,
        // not max across overlapping bubbles.
        //
        // No blending — depth-test guarantees only one bubble's fragment
        // passes per pixel, so overwrite is correct.
        RenderSystem.disableCull()
        RenderSystem.disableBlend()
        RenderSystem.depthMask(true)
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }

        // **Private** BufferBuilder, NOT the shared `Tesselator.getInstance().builder`.
        // The shared builder is touched by post-effects, vanilla world rendering, the
        // scrying refraction PostChain, and every other code path running this frame —
        // if anyone leaves it in "building" state our `begin()` (or PostPass's) throws
        // "Already building!". Using a private builder guarantees zero interaction with
        // any other render path; we still wrap in try/finally so the state restore at
        // the bottom runs even on a thrown emit.
        val buf = BufferBuilder(256 * 1024)
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        var bufferOpen = true

        var anyDrawn = false
        val v = Vector3d()
        // Track the cloak with the highest progress this frame so we can stash its
        // screen-space centre + progress for the post-effect uniforms below.
        var domProgress = -1f
        var domCenterU = 0.5f
        var domCenterV = 0.5f
        val scratchClip = Vector4f()
        try {
        for (shipId in ships) {
            val progress = ClientShipCloakingState.getProgress(shipId)
            // Linear ramp: warble grows in lockstep with the 5-second cloak timer.
            // Skip the cheap "no cloak active" case so the bubble pass costs nothing
            // when nothing's happening.
            if (progress <= 0.001f) continue
            val intensity = progress
            val ship = (level.shipObjectWorld.allShips.getById(shipId) as? ClientShip) ?: continue
            val aabb = ship.shipAABB ?: continue

            // Ship-local AABB centre + circumscribed half-extents (the shipAABB's max
            // is inclusive on the high side per VS2 convention, so add 1 before halving).
            // Half-extents × √3 so the eight AABB corners lie exactly on the ellipsoid
            // surface and the ship is fully contained.
            val cxL = (aabb.minX() + aabb.maxX() + 1.0) * 0.5
            val cyL = (aabb.minY() + aabb.maxY() + 1.0) * 0.5
            val czL = (aabb.minZ() + aabb.maxZ() + 1.0) * 0.5
            val hxL = (aabb.maxX() + 1 - aabb.minX()) * 0.5 * CIRCUMSCRIBE
            val hyL = (aabb.maxY() + 1 - aabb.minY()) * 0.5 * CIRCUMSCRIBE
            val hzL = (aabb.maxZ() + 1 - aabb.minZ()) * 0.5 * CIRCUMSCRIBE

            val s2w = ship.renderTransform.shipToWorld
            // Mask channels encode PER-PIXEL data so each ship's bubble
            // composites at ITS OWN progress in the shader:
            //   R = this ship's cloak progress (0..1).
            //   G = silhouette indicator (always 1.0 inside this bubble).
            // Overlapping bubbles in screen space are resolved by depth-test
            // (the bubble pass writes depth into the mask FB's OWN depth
            // attachment, LEQUAL test) — the closest bubble at each pixel
            // wins, so R = front bubble's progress and G = 1. The shader
            // recovers per-pixel progress as `R / G` (after blur — division
            // cancels the kernel's coverage weighting at soft edges).
            val r = progress; val g = 1f; val b = 0f; val a = 1f

            // Project the ship's local-AABB centre to screen UV for the ripple origin.
            // worldCentre = s2w · localCentre; subtract camera; transform through the
            // same view+projection we set for the bubble draw above.
            val wcLocal = Vector3d(cxL, cyL, czL)
            s2w.transformPosition(wcLocal)
            scratchClip.set(
                (wcLocal.x - cam.x).toFloat(),
                (wcLocal.y - cam.y).toFloat(),
                (wcLocal.z - cam.z).toFloat(),
                1f,
            )
            view.transform(scratchClip)
            projectionMatrix.transform(scratchClip)
            if (scratchClip.w > 0f && progress > domProgress) {
                domCenterU = (scratchClip.x / scratchClip.w + 1f) * 0.5f
                domCenterV = (scratchClip.y / scratchClip.w + 1f) * 0.5f
                domProgress = progress
            }

            // UV ellipsoid: φ ∈ [0, π] (lat), θ ∈ [0, 2π) (lon). Each (lat, lon) cell
            // is a quad joining 4 corner points generated in shipyard space then
            // transformed through shipToWorld.
            for (lat in 0 until LAT_STEPS) {
                val phi0 = Math.PI * lat / LAT_STEPS
                val phi1 = Math.PI * (lat + 1) / LAT_STEPS
                val sP0 = Math.sin(phi0); val cP0 = Math.cos(phi0)
                val sP1 = Math.sin(phi1); val cP1 = Math.cos(phi1)
                for (lon in 0 until LON_STEPS) {
                    val theta0 = 2.0 * Math.PI * lon / LON_STEPS
                    val theta1 = 2.0 * Math.PI * (lon + 1) / LON_STEPS
                    val sT0 = Math.sin(theta0); val cT0 = Math.cos(theta0)
                    val sT1 = Math.sin(theta1); val cT1 = Math.cos(theta1)
                    // Quad corners in (phi, theta) order: (0,0)(0,1)(1,1)(1,0).
                    emit(buf, v, s2w, cxL, cyL, czL, hxL, hyL, hzL, sP0, cP0, sT0, cT0, cam, r, g, b, a)
                    emit(buf, v, s2w, cxL, cyL, czL, hxL, hyL, hzL, sP0, cP0, sT1, cT1, cam, r, g, b, a)
                    emit(buf, v, s2w, cxL, cyL, czL, hxL, hyL, hzL, sP1, cP1, sT1, cT1, cam, r, g, b, a)
                    emit(buf, v, s2w, cxL, cyL, czL, hxL, hyL, hzL, sP1, cP1, sT0, cT0, cam, r, g, b, a)
                }
            }
            anyDrawn = true
        }

            // Publish dominant cloak's centre & progress so the post-effect's uniforms
            // can drive the ripple from the right point. If no ship rendered (progress
             // all ≤ 0.001), leave the prior values — the shader's threshold check skips
             // those frames anyway.
            if (domProgress >= 0f) {
                lastShipCenterU = domCenterU
                lastShipCenterV = domCenterV
                lastProgress = domProgress
            }

            if (anyDrawn) {
                BufferUploader.drawWithShader(buf.end())
                bufferOpen = false
            } else {
                // Discard the (potentially empty) buffer state so vanilla doesn't try to
                // upload an empty draw.
                buf.discard()
                bufferOpen = false
            }
        } finally {
            // Belt-and-braces: if anything in the loop threw, dump the builder so the
            // next render path (PostPass.process, anyone else using the shared
            // Tesselator) can `begin()` cleanly.
            if (bufferOpen) buf.discard()

            // Restore vanilla state regardless of how we got here.
            RenderSystem.enableCull()
            RenderSystem.enableBlend()
            RenderSystem.depthMask(true)
            ConcealmentMaskFB.unbind()

            RenderSystem.getModelViewMatrix().set(savedView)
            RenderSystem.setProjectionMatrix(savedProj, VertexSorting.DISTANCE_TO_ORIGIN)
        }
    }

    /** Generate one ellipsoid vertex at (φ, θ) in ship-local space, transform to world,
     *  emit in camera-relative coords. `sinP`/`cosP` are the latitude trig values;
     *  `sinT`/`cosT` are longitude. */
    private fun emit(
        buf: com.mojang.blaze3d.vertex.BufferBuilder, scratch: Vector3d, s2w: Matrix4dc,
        cx: Double, cy: Double, cz: Double, hx: Double, hy: Double, hz: Double,
        sinP: Double, cosP: Double, sinT: Double, cosT: Double,
        cam: net.minecraft.world.phys.Vec3, r: Float, g: Float, b: Float, a: Float,
    ) {
        // Standard UV sphere parameterisation: y = cos φ, x = sin φ cos θ, z = sin φ sin θ.
        // Scaled by per-axis half-extents to form the ellipsoid inscribed in the AABB.
        scratch.set(cx + hx * sinP * cosT, cy + hy * cosP, cz + hz * sinP * sinT)
        s2w.transformPosition(scratch)
        buf.vertex(scratch.x - cam.x, scratch.y - cam.y, scratch.z - cam.z)
            .color(r, g, b, a)
            .endVertex()
    }
}
