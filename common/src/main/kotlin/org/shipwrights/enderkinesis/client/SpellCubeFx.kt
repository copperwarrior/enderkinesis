package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import org.shipwrights.enderkinesis.spell.Element
import java.util.function.IntSupplier
import kotlin.math.abs
import kotlin.math.max

/**
 * Each element's effect on the screen round its cubes ([SpellCore]): Heat's haze rising; Cold's
 * frosted-glass scatter and chill; Growth's ripples and soft green; Purity's star glare; Gravity's
 * gentle lens and darkening; Chaos's glitching slices and colour split; Blood's red pulse with its
 * heartbeat; Sunfire's radiating ripple and golden flare; Poison's sickly drained green; Void's
 * dark, writhing tendrils round a hole that drains colour (`spell_cube_fx`).
 *
 * Each cube drawn this frame [record]s where it lands on screen, through the projection it is
 * drawn with (the world's or the first-person hand's), and how far it is from the eye. Cubes in
 * the world are passed through the effects once the world is drawn, before the hand
 * ([renderWorld]): the effect reads the world's depth and fades wherever the scene is nearer than
 * the cube, so a cube behind a wall changes nothing on the wall. Cubes in your own hand go after
 * the hand ([render]); nothing is ever in front of those.
 *
 * Only [MAX] cubes a pass take effect: those off the screen are left out, and the nearest are
 * kept, so it's the far ones that drop away.
 */
object SpellCubeFx {
    private const val MAX = 8
    /** Up to this many are kept a frame, before the nearest [MAX] are picked. */
    private const val KEEP = 64
    private class Cube(val x: Float, val y: Float, val radius: Float, val element: Int, val depth: Float)

    // Render thread only.
    private val world = ArrayList<Cube>()
    private val hand = ArrayList<Cube>()
    @Volatile private var effect: EffectInstance? = null
    @Volatile private var loadFailed = false
    private val LOG: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("EnderkinesisSpellCubeFx")

    /** Whether the level (the world, then the hand) is being drawn: cubes drawn anywhere else, as
     *  in the inventory's picture of the player, have no place on the view. */
    @JvmStatic
    var drawingLevel = false
        private set

    /** As `GameRenderer.renderLevel` starts. */
    @JvmStatic
    fun beginLevel() {
        drawingLevel = true
    }

    /** A cube of [element] at [p] in [m]'s space, [half] its half-width, was drawn this frame;
     *  [inHand] if it's in your own first-person hand. */
    fun record(m: Matrix4f, p: Vec3, half: Double, element: Element, inHand: Boolean) {
        if (!drawingLevel) return
        // A shader pack's shadow pass draws through the light's projection, not the eye's.
        if (IrisCompat.isRenderingShadowPass()) return
        val list = if (inHand) hand else world
        if (list.size >= KEEP) return
        val target = Minecraft.getInstance().mainRenderTarget
        val w = target.width.toFloat(); val h = target.height.toFloat()
        val proj = RenderSystem.getProjectionMatrix()
        val view = Vector4f(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f)
        m.transform(view)
        RenderSystem.getModelViewMatrix().transform(view)
        val clip = proj.transform(Vector4f(view))
        if (clip.w <= 0.01f) return
        val x = (clip.x / clip.w * 0.5f + 0.5f) * w
        val y = (clip.y / clip.w * 0.5f + 0.5f) * h
        // How big the effect is on screen: four times the cube, but never vanishingly small.
        val edge = proj.transform(Vector4f(view.x, view.y + max(half * 4, 0.14).toFloat(), view.z, 1f))
        val radius = max(abs((edge.y / edge.w * 0.5f + 0.5f) * h - y), 4f)
        // Off the screen, it would only take a place.
        if (x + radius < 0f || x - radius > w || y + radius < 0f || y - radius > h) return
        // The scene must be nearer than the cube's front, with room for the cube itself, which
        // is drawn into the depth too, before the effect gives way.
        val depth = max(-view.z - (half * 2 + 0.15).toFloat(), 0.001f)
        list += Cube(x, y, radius, element.ordinal + 1, depth)
    }

    /** Once the world is drawn, before the hand: the effects round the world's cubes, faded
     *  where the scene is nearer than them. */
    @JvmStatic
    fun renderWorld(time: Float) {
        if (world.isEmpty()) return
        val mc = Minecraft.getInstance()
        val depth = mc.mainRenderTarget.depthTextureId
        // The world's projection's depth terms (`GameRenderer.getProjectionMatrix`), for turning
        // the depth buffer back into distance.
        val far = mc.gameRenderer.depthFar; val near = 0.05f
        pass(world, time, IntSupplier { depth }, -(far + near) / (far - near), -2f * far * near / (far - near))
    }

    /** After the hand is drawn: the effects round your hand's cubes, and any world ones not
     *  done yet (when the hand isn't drawn, nothing cleared the world's depth). */
    @JvmStatic
    fun render(time: Float) {
        renderWorld(time)
        pass(hand, time, null, 0f, 0f)
        drawingLevel = false
    }

    private fun pass(cubes: ArrayList<Cube>, time: Float, depth: IntSupplier?, depthA: Float, depthB: Float) {
        if (cubes.isEmpty()) return
        val loaded = ensureLoaded(Minecraft.getInstance())
        if (loaded != null) {
            if (cubes.size > MAX) cubes.sortBy { it.depth }
            val samplers = if (depth != null) listOf("DepthSampler" to depth) else emptyList()
            RefractionPass.run(loaded, samplers) { eff ->
                eff.safeGetUniform("Time").set(time)
                eff.safeGetUniform("Count").set(minOf(cubes.size, MAX))
                eff.safeGetUniform("DepthTest").set(if (depth != null) 1 else 0)
                eff.safeGetUniform("DepthA").set(depthA)
                eff.safeGetUniform("DepthB").set(depthB)
                for (i in 0 until MAX) {
                    val c = cubes.getOrNull(i)
                    eff.safeGetUniform("Cube$i").set(c?.x ?: 0f, c?.y ?: 0f, c?.radius ?: 1f, (c?.element ?: 0).toFloat())
                }
                fun d(i: Int) = cubes.getOrNull(i)?.depth ?: 0f
                eff.safeGetUniform("Depths0").set(d(0), d(1), d(2), d(3))
                eff.safeGetUniform("Depths1").set(d(4), d(5), d(6), d(7))
            }
        }
        cubes.clear()
    }

    private fun ensureLoaded(mc: Minecraft): EffectInstance? {
        effect?.let { return it }
        if (loadFailed) return null
        return try {
            EffectInstance(mc.resourceManager, "spell_cube_fx").also { effect = it }
        } catch (t: Throwable) {
            loadFailed = true
            LOG.warn("Failed to load the spell cube effects shader program", t)
            null
        }
    }

    fun close() {
        effect?.close()
        effect = null
        loadFailed = false
        world.clear()
        hand.clear()
    }
}
