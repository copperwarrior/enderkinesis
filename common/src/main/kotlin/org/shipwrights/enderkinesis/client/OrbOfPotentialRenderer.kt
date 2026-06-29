package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.Util
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.world.phys.AABB
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.shipwrights.enderkinesis.body.ClientOrbRegistry
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Client-side renderer for Orb of Potential VS Bodies.
 *
 *  Invoked from [org.shipwrights.enderkinesis.mixin.LevelRendererOrbRenderMixin]'s
 *  `renderLevel` TAIL hook each frame. Iterates the marker block-
 *  entities held in [FractalProjectorBlockEntity.ClientOrbs] — those
 *  are the only client-side fingerprints of orb bodies — and for each
 *  looks up the live [ClientVsBody] via `level.shipObjectWorld
 *  .allBodies.getById(...)`, then draws the existing fractal sphere +
 *  additive halo at the body's [BodyTransform.toWorld] position with
 *  the body's rotation.
 *
 *  Uniforms (`MeshToWorld`, `WordLetters`) vary per orb, so each orb
 *  is drawn in its own `endBatch` to flush GL state with the right
 *  values. The fractal shader's other uniforms (`ViewToWorld`,
 *  `GlobalTime`) are frame-constant and set once per call.
 *
 *  Replaces the legacy BER (`FractalProjectorRenderer`) which is now
 *  unregistered; the block has no visible component of its own.
 */
object OrbOfPotentialRenderer {

    private const val SECONDS_WRAP_MS: Long = 100_000_000L
    private const val SPHERE_RADIUS: Float = 1.5f
    private const val HALO_RADIUS: Float = 2.0f
    private const val SPHERE_SLICES: Int = 32
    private const val SPHERE_STACKS: Int = 16
    private const val VERTEX_STRIDE: Int = 6

    private val SPHERE_VERTICES: FloatArray = buildUvSphere(SPHERE_RADIUS)
    private val HALO_VERTICES: FloatArray = buildUvSphere(HALO_RADIUS)

    // Scratch reused across orbs per frame so we're not re-allocating
    // a 48-element float[] every call. `fillWordLetters` output depends
    // only on `timeSec`, so the value is computed ONCE at the top of
    // `render()` and the same uniform is used for every orb that
    // frame (rather than re-filling per orb as the old path did).
    private val WORD_LETTERS = FloatArray(GLYPH_SLOTS * MAX_WORD_LEN)
    private const val GLYPH_SLOTS: Int = 6
    private const val MAX_WORD_LEN: Int = 8

    fun render(
        poseStack: PoseStack, @Suppress("UNUSED_PARAMETER") partialTick: Float,
        camera: Camera, projectionMatrix: Matrix4f,
    ) {
        if (!FractalProjectorRenderTypes.isReady()) return
        val fractalShader = FractalProjectorRenderTypes.getFractalShader() ?: return
        val haloShader = FractalProjectorRenderTypes.getHaloShader() ?: return

        val orbs = ClientOrbRegistry.snapshot()
        if (orbs.isEmpty()) return

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val bufferSource = mc.renderBuffers().bufferSource()
        val cameraPos = camera.position
        // Dim guard. The orbs leak across dims after the player visits
        // Sselith (the per-dim S2C clear / replace doesn't always reach
        // the client before the renderer is on its feet for the new dim),
        // so we belt-and-suspenders the visual: skip any body whose VS2
        // `dimension` field doesn't match the level we're rendering. VS2
        // uses the long registry id (`minecraft:dimension:<ns>:<path>`),
        // which `level.dimensionId` returns, so the equality check works
        // verbatim against `body.dimension`.
        val currentDimId = level.dimensionId

        // Build the culling frustum from the current pose's modelview
        // and the level renderer's projection. `prepare(camera…)` sets
        // the camera offset so `isVisible(worldAABB)` accepts world-
        // space AABBs directly. One allocation per frame is fine — the
        // hot work is the per-orb isVisible check, not the construction.
        val frustum = Frustum(poseStack.last().pose(), projectionMatrix)
            .also { it.prepare(cameraPos.x, cameraPos.y, cameraPos.z) }

        // Match vanilla `Entity.shouldRenderAtSqrDistance`:
        // `bbSize · 64 · entityDistanceScaling`. Orb diameter is the
        // bbSize, so this scales with the player's entity-render-
        // distance setting just like any other entity.
        val entityScale = mc.options.entityDistanceScaling().get()
        val cullDistance = SPHERE_RADIUS * 2.0 * 64.0 * entityScale
        val cullDistanceSqr = cullDistance * cullDistance

        // Frame-constant uniforms (same value for every orb).
        val timeSec = ((Util.getMillis() % SECONDS_WRAP_MS) / 1000.0).toFloat()
        fractalShader.safeGetUniform("GlobalTime").set(timeSec)
        // ViewToWorld: same derivation as the legacy BER. MC bakes the
        // camera rotation into the pose stack so `vViewPos` arrives in
        // view space; this matrix rotates it back. The +180 yaw is
        // necessary because `Camera.rotation()` doesn't include the
        // flip that `LevelRenderer.renderLevel` applies on the way in.
        val yawRad = Math.toRadians(-camera.yRot.toDouble() - 180.0).toFloat()
        val pitchRad = Math.toRadians(-camera.xRot.toDouble()).toFloat()
        val viewToWorld = Matrix3f().rotationY(yawRad).rotateX(pitchRad)
        fractalShader.safeGetUniform("ViewToWorld")?.set(viewToWorld)
        haloShader.safeGetUniform("ViewToWorld")?.set(viewToWorld)
        // WordLetters depends only on `timeSec` (no per-orb seed), so
        // fill once and reuse the uniform for every orb this frame.
        fillWordLetters(timeSec, WORD_LETTERS)
        fractalShader.safeGetUniform("WordLetters")?.set(WORD_LETTERS)

        for (bodyId in orbs) {
            if (bodyId == 0L) continue
            val body = level.shipObjectWorld.allBodies.getById(bodyId) as? ClientVsBody ?: continue
            if (body.dimension != currentDimId) continue

            val transform = body.renderTransform
            val bodyPos = transform.toWorld.transformPosition(Vector3d())
            if (!bodyPos.isFinite) continue

            // Distance cull — matches vanilla's entity-render-distance
            // behaviour so the orb scales with the player's setting.
            val dx = bodyPos.x - cameraPos.x
            val dy = bodyPos.y - cameraPos.y
            val dz = bodyPos.z - cameraPos.z
            if (dx * dx + dy * dy + dz * dz > cullDistanceSqr) continue

            // Frustum cull — drop orbs whose halo-radius AABB doesn't
            // intersect the view. `HALO_RADIUS` (the larger of the two
            // meshes) gives a conservative bound that covers both the
            // sphere and the halo passes.
            val r = HALO_RADIUS.toDouble()
            val aabb = AABB(
                bodyPos.x - r, bodyPos.y - r, bodyPos.z - r,
                bodyPos.x + r, bodyPos.y + r, bodyPos.z + r,
            )
            if (!frustum.isVisible(aabb)) continue

            // Per-orb uniforms.
            val rotQuatF = Quaternionf(transform.rotation)
            val meshToWorld = Matrix3f().set(rotQuatF)
            fractalShader.safeGetUniform("MeshToWorld")?.set(meshToWorld)
            haloShader.safeGetUniform("MeshToWorld")?.set(meshToWorld)

            // Per-orb fractal type — derived from the anchor on the
            // client when the orb's sync packet arrives (see
            // [FractalProjectorBlock.computeFractalType]). Stable
            // across reloads because it's purely positional.
            val fractalType = org.shipwrights.enderkinesis.body.ClientOrbRegistry
                .fractalTypeFor(bodyId) ?: 0

            poseStack.pushPose()
            poseStack.translate(
                bodyPos.x - cameraPos.x,
                bodyPos.y - cameraPos.y,
                bodyPos.z - cameraPos.z,
            )
            poseStack.mulPose(rotQuatF)

            val matrix = poseStack.last().pose()
            emitSphere(
                SPHERE_VERTICES,
                bufferSource.getBuffer(FractalProjectorRenderTypes.FRACTAL_SPHERE),
                matrix, fractalType,
            )
            // Flush so the next orb's MeshToWorld uniform applies cleanly.
            bufferSource.endBatch(FractalProjectorRenderTypes.FRACTAL_SPHERE)

            emitSphere(
                HALO_VERTICES,
                bufferSource.getBuffer(FractalProjectorRenderTypes.HALO_SPHERE),
                matrix, fractalType,
            )
            bufferSource.endBatch(FractalProjectorRenderTypes.HALO_SPHERE)

            poseStack.popPose()
        }
    }

    private fun emitSphere(
        vertices: FloatArray, consumer: VertexConsumer, matrix: Matrix4f, fractalType: Int,
    ) {
        var i = 0
        while (i < vertices.size) {
            val px = vertices[i]
            val py = vertices[i + 1]
            val pz = vertices[i + 2]
            val nx = vertices[i + 3]
            val ny = vertices[i + 4]
            val nz = vertices[i + 5]
            consumer
                .vertex(matrix, px, py, pz)
                // Color.a carries fractal type for the shader's dispatcher
                // (same encoding the legacy BER used). RGB unused.
                .color(0, 0, 0, fractalType)
                .normal(nx, ny, nz)
                .endVertex()
            i += VERTEX_STRIDE
        }
    }

    private fun buildUvSphere(radius: Float): FloatArray {
        val quadCount = SPHERE_SLICES * SPHERE_STACKS
        val out = FloatArray(quadCount * 6 * VERTEX_STRIDE)
        var idx = 0
        for (stack in 0 until SPHERE_STACKS) {
            val phi1 = Math.PI * stack / SPHERE_STACKS
            val phi2 = Math.PI * (stack + 1) / SPHERE_STACKS
            val sinPhi1 = Math.sin(phi1).toFloat()
            val cosPhi1 = Math.cos(phi1).toFloat()
            val sinPhi2 = Math.sin(phi2).toFloat()
            val cosPhi2 = Math.cos(phi2).toFloat()
            for (slice in 0 until SPHERE_SLICES) {
                val theta1 = 2.0 * Math.PI * slice / SPHERE_SLICES
                val theta2 = 2.0 * Math.PI * (slice + 1) / SPHERE_SLICES
                val cosT1 = Math.cos(theta1).toFloat()
                val sinT1 = Math.sin(theta1).toFloat()
                val cosT2 = Math.cos(theta2).toFloat()
                val sinT2 = Math.sin(theta2).toFloat()
                val nx1 = sinPhi1 * cosT1; val ny1 = cosPhi1; val nz1 = sinPhi1 * sinT1
                val nx2 = sinPhi2 * cosT1; val ny2 = cosPhi2; val nz2 = sinPhi2 * sinT1
                val nx3 = sinPhi2 * cosT2; val ny3 = cosPhi2; val nz3 = sinPhi2 * sinT2
                val nx4 = sinPhi1 * cosT2; val ny4 = cosPhi1; val nz4 = sinPhi1 * sinT2
                idx = emit(out, idx, radius, nx1, ny1, nz1)
                idx = emit(out, idx, radius, nx2, ny2, nz2)
                idx = emit(out, idx, radius, nx3, ny3, nz3)
                idx = emit(out, idx, radius, nx1, ny1, nz1)
                idx = emit(out, idx, radius, nx3, ny3, nz3)
                idx = emit(out, idx, radius, nx4, ny4, nz4)
            }
        }
        return out
    }

    private fun emit(out: FloatArray, idx: Int, radius: Float, nx: Float, ny: Float, nz: Float): Int {
        out[idx]     = nx * radius
        out[idx + 1] = ny * radius
        out[idx + 2] = nz * radius
        out[idx + 3] = nx
        out[idx + 4] = ny
        out[idx + 5] = nz
        return idx + VERTEX_STRIDE
    }

    // Per-slot Sselith words sent to the fractal shader's WordLetters
    // uniform. Word selection mirrors TomeSummonOverlay's cycle-based
    // hash so the SAME vocabulary cycles through both effects. Words
    // are picked once per cycle (≈ 7-second window per slot); the
    // shader uses the same cycle index for its great-circle drift.

    private val SSELITH_WORDS: Array<String> = arrayOf(
        "aevkh", "aevkhegh", "aevocht", "aevust", "akh", "akkumulaskhun",
        "ambiensth", "amielegh", "amielkarn", "ankessthri", "ankhelfish",
        "apprentikh", "arbalistkh", "armkhvel", "augukh", "autoskave",
        "avakenelk", "azhtek", "banankh", "barokvekh", "ontnemkh",
        "kwevkust", "kwevegh", "morontkr", "ontpartkhust", "yghann",
        "yghannmornkt", "zhe", "kre", "vel", "mor", "ont", "toch",
        "vraestmorocht", "skarn", "schest", "moroch", "kelkargh",
        "elksvel", "thauntakh", "morakht", "vraestakh", "kwevakht",
    )

    private fun fillWordLetters(timeSec: Float, out: FloatArray) {
        for (slot in 0 until GLYPH_SLOTS) {
            val gid = slot.toFloat()
            val lifeJitter = 0.75f + starHash1(gid, 99f) * 0.5f
            val lifeSec = 7f * lifeJitter
            val phaseSec = starHash1(gid, 100f) * lifeSec
            val cycle = Math.floor(((timeSec + phaseSec) / lifeSec).toDouble()).toLong()
            val seed = slot.toLong() xor (cycle * 0x9E3779B97F4A7C15UL.toLong())
            val wordHash = splitMix64(seed)
            val wordIdx = (((wordHash ushr 48) and 0xFFFL).toInt())
                .mod(SSELITH_WORDS.size)
            val word = SSELITH_WORDS[wordIdx]
            val base = slot * MAX_WORD_LEN
            for (i in 0 until MAX_WORD_LEN) {
                out[base + i] = if (i < word.length) word[i].code.toFloat() else 0f
            }
        }
    }

    /** GLSL `starHash1` mirror — must match
     *  `fract(sin(dot(c, vec2(127.1, 311.7))) * 43758.5453)` exactly so
     *  CPU- and GPU-derived cycle indices agree. */
    private fun starHash1(x: Float, y: Float): Float {
        val d = (x * 127.1f + y * 311.7f).toDouble()
        val s = Math.sin(d) * 43758.5453
        return (s - Math.floor(s)).toFloat()
    }

    private fun splitMix64(seed: Long): Long {
        var h = seed
        h = (h xor (h ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        h = (h xor (h ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return h xor (h ushr 31)
    }

}
