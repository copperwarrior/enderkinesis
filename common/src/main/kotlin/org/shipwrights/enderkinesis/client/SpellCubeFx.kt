package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector4f
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.spell.Element
import kotlin.math.abs
import kotlin.math.max

/**
 * World cubes draw before the hand and fade where the scene is nearer. Hand cubes draw after the hand, with no depth test.
 * Only the nearest [MAX] cubes take effect in each pass.
 */
object SpellCubeFx {
    private const val MAX = 8
    /** The nearest [MAX] come from up to this many cubes each frame. */
    private const val KEEP = 64
    private val CUBE_UNIFORMS = Array(MAX) { "Cube$it" }

    private class Cube {
        var x = 0f
        var y = 0f
        var radius = 1f
        var element = 0
        var depth = 0f
    }

    // Render thread only.
    private class Batch {
        val cubes = Array(KEEP) { Cube() }
        var size = 0

        // Selection of the nearest MAX; bounded by KEEP × MAX.
        fun keepNearest() {
            for (i in 0 until minOf(MAX, size)) {
                var best = i
                for (j in i + 1 until size) if (cubes[j].depth < cubes[best].depth) best = j
                val t = cubes[i]; cubes[i] = cubes[best]; cubes[best] = t
            }
        }
    }

    // World cubes keep their eye-space centre until the frame's VolumeView exists to project them.
    private val pendingX = FloatArray(KEEP)
    private val pendingY = FloatArray(KEEP)
    private val pendingZ = FloatArray(KEEP)
    private val pendingHalf = FloatArray(KEEP)
    private val pendingElement = IntArray(KEEP)
    private var pendingCount = 0

    private val world = Batch()
    private val hand = Batch()
    private val view = VolumeView()
    private val invPose = Matrix4f()
    private val proj = Matrix4f()
    private val scratch = Vector4f()
    private val clip = Vector4f()
    private var viewCaptured = false

    private var effect = newEffect()
    private var loaded: EffectInstance? = null

    private fun newEffect() = VolumeEffect("spell_cube_fx")

    /** False outside the level pass, for example in the inventory player picture. Those cubes have no place on the view. */
    @JvmStatic
    var drawingLevel = false
        private set

    /** Call as `GameRenderer.renderLevel` starts. */
    @JvmStatic
    fun beginLevel() {
        drawingLevel = true
        viewCaptured = false
    }

    /** Call at `LevelRenderer.renderLevel` TAIL. */
    @JvmStatic
    fun captureView(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (IrisCompat.isRenderingShadowPass()) return
        val pose = poseStack.last().pose()
        val cam = camera.position
        view.update(projection, pose, cam.x, cam.y, cam.z)
        invPose.set(pose).invert()
        viewCaptured = true
    }

    /** [p] is in the space of [m]. [inHand] is true for a cube in the local first-person hand. */
    fun record(m: Matrix4f, p: Vec3, half: Double, element: Element, inHand: Boolean) {
        if (!drawingLevel) return
        // A shader pack's shadow pass draws through the light's projection, not the eye's.
        if (IrisCompat.isRenderingShadowPass()) return
        val eye = scratch.set(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f)
        m.transform(eye)
        RenderSystem.getModelViewMatrix().transform(eye)
        if (inHand) {
            // The hand has its own projection, which only exists while the hand draws.
            if (hand.size < KEEP) project(hand, RenderSystem.getProjectionMatrix(), eye, half, element.ordinal + 1)
            return
        }
        if (pendingCount >= KEEP) return
        pendingX[pendingCount] = eye.x
        pendingY[pendingCount] = eye.y
        pendingZ[pendingCount] = eye.z
        pendingHalf[pendingCount] = half.toFloat()
        pendingElement[pendingCount] = element.ordinal + 1
        pendingCount++
    }

    private fun project(batch: Batch, projection: Matrix4fc, eye: Vector4f, half: Double, element: Int) {
        val target = Minecraft.getInstance().mainRenderTarget
        val w = target.width.toFloat(); val h = target.height.toFloat()
        projection.transform(clip.set(eye))
        if (clip.w <= 0.01f) return
        val x = (clip.x / clip.w * 0.5f + 0.5f) * w
        val y = (clip.y / clip.w * 0.5f + 0.5f) * h
        // Four times the cube, with a minimum so that far cubes keep a visible effect.
        projection.transform(clip.set(eye.x, eye.y + max(half * 4, 0.14).toFloat(), eye.z, 1f))
        val radius = max(abs((clip.y / clip.w * 0.5f + 0.5f) * h - y), 4f)
        if (x + radius < 0f || x - radius > w || y + radius < 0f || y - radius > h) return
        // The cube is in the depth buffer too. Thus the effect fades only where the scene is nearer than the cube front.
        val c = batch.cubes[batch.size++]
        c.x = x; c.y = y; c.radius = radius; c.element = element
        c.depth = max(-eye.z - (half * 2 + 0.15).toFloat(), 0.001f)
    }

    /** Call after the world and before the hand. */
    @JvmStatic
    fun renderWorld(time: Float) {
        if (pendingCount == 0) return
        if (viewCaptured) {
            // The pose holds the camera rotation only, so viewProj × pose⁻¹ is the eye-space projection.
            proj.set(view.viewProj).mul(invPose)
            for (i in 0 until pendingCount) {
                scratch.set(pendingX[i], pendingY[i], pendingZ[i], 1f)
                project(world, proj, scratch, pendingHalf[i].toDouble(), pendingElement[i])
            }
        }
        pendingCount = 0
        pass(world, time, depthTest = true)
    }

    /** Call after the hand. It also does the world cubes that remain, for frames with no hand pass. */
    @JvmStatic
    fun render(time: Float) {
        renderWorld(time)
        pass(hand, time, depthTest = false)
        drawingLevel = false
    }

    private fun pass(batch: Batch, time: Float, depthTest: Boolean) {
        if (batch.size == 0) return
        val eff = effect.get()
        loaded = eff
        if (eff != null) {
            if (batch.size > MAX) batch.keepNearest()
            // The JSON blend replaces the frame from a colour copy, and drawOverMain has no colour copy.
            RefractionPass.run(eff) { push(it, batch, time, depthTest) }
        }
        batch.size = 0
    }

    private fun push(eff: EffectInstance, batch: Batch, time: Float, depthTest: Boolean) {
        eff.safeGetUniform("Time").set(time)
        eff.safeGetUniform("Count").set(minOf(batch.size, MAX))
        eff.safeGetUniform("DepthTest").set(if (depthTest) 1 else 0)
        view.pushDepthParams(eff)
        for (i in 0 until MAX) {
            val c = if (i < batch.size) batch.cubes[i] else null
            eff.safeGetUniform(CUBE_UNIFORMS[i]).set(c?.x ?: 0f, c?.y ?: 0f, c?.radius ?: 1f, (c?.element ?: 0).toFloat())
        }
        eff.safeGetUniform("Depths0").set(depthAt(batch, 0), depthAt(batch, 1), depthAt(batch, 2), depthAt(batch, 3))
        eff.safeGetUniform("Depths1").set(depthAt(batch, 4), depthAt(batch, 5), depthAt(batch, 6), depthAt(batch, 7))
    }

    private fun depthAt(batch: Batch, i: Int) = if (i < batch.size) batch.cubes[i].depth else 0f

    fun close() {
        // VolumeEffect has no close, so drop it for a fresh one that loads the new resources.
        loaded?.close()
        loaded = null
        effect = newEffect()
        pendingCount = 0
        world.size = 0
        hand.size = 0
    }
}
