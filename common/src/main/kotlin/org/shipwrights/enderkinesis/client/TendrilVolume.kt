package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.shaders.Uniform
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView

/** [wild] is the writhe amplitude in blocks; 0 for a calm tendril. */
class TendrilCurve(val p0: Vector3d, val p1: Vector3d, val p2: Vector3d, val wild: Double = 0.0, val seed: Double = 0.0) {
    /** The calm curve at [s] in 0..1, without the writhe. */
    fun pointAt(s: Double, out: Vector3d): Vector3d {
        val k = 1.0 - s
        return out.set(
            k * k * p0.x + 2.0 * k * s * p1.x + s * s * p2.x,
            k * k * p0.y + 2.0 * k * s * p1.y + s * s * p2.y,
            k * k * p0.z + 2.0 * k * s * p1.z + s * s * p2.z,
        )
    }

    /** Where the tube shows at [s], for a frame whose origin is [p0]. [scratch] is overwritten. */
    fun writhedPointAt(s: Double, out: Vector3d, scratch: Vector3d): Vector3d {
        pointAt(s, out)
        if (wild <= 0.0) return out
        // The shader warps the sample point, so the tube shows at the curve minus the warp.
        writhe(out.x - p0.x, out.y - p0.y, out.z - p0.z, seed, TendrilVolume.writhePhase(), scratch)
        return out.sub(scratch.mul(wild * Math.sin(Math.PI * s)))
    }

    /** The shader's writhe field (wohlon_tendril_volume.fsh), in frame space; whole multiples of the phase only. */
    private fun writhe(px: Double, py: Double, pz: Double, seed: Double, phase: Double, out: Vector3d) {
        out.set(
            Math.sin(py * 0.55 + pz * 0.31 + phase + seed) + 0.5 * Math.sin(pz * 1.3 - 2.0 * phase + seed * 1.7),
            Math.sin(px * 0.47 - pz * 0.29 - phase + seed * 2.3) + 0.5 * Math.sin(px * 1.1 + 2.0 * phase + seed * 0.6),
            Math.sin(px * 0.39 + py * 0.53 + phase + seed * 3.1) + 0.5 * Math.sin(py * 1.2 - 2.0 * phase + seed * 1.3),
        ).div(1.5)
    }
}

class TendrilStyle(val radius: Float, val from: FloatArray, val to: FloatArray)

/** Box space of a group of tendrils: [origin] in the frame of [toWorld] (null for the world frame). */
class TendrilFrame(val origin: Vector3d, var toWorld: Matrix4dc? = null)

/** Draws `wohlon_tendril_volume` tubes. Call [begin], then [draw] for each tube, then [end]. Render thread only. */
object TendrilVolume {

    private const val FLOW_RATE = 6.0
    private const val WRITHE_RATE = 1.4

    private val view = VolumeView()
    private val effect = VolumeEffect("wohlon_tendril_volume")
    private var uniforms: Uniforms? = null
    private var active: EffectInstance? = null
    private val localToViewD = Matrix4d()
    private val viewToLocal = Matrix4d()
    private val localToView = Matrix4f()
    private val camLocal = Vector3d()
    private val boxMin = Vector3d()
    private val boxMax = Vector3d()
    private val viewMin = Vector3d()
    private val viewMax = Vector3d()
    private var drawn = 0

    /** The shared writhe wave phase, in radians; the CPU copy of the shader's `Writhe`. */
    fun writhePhase(): Double = (System.nanoTime() * 1e-9 * WRITHE_RATE) % (Math.PI * 2.0)

    /** False when no tube can draw this frame. */
    fun begin(poseStack: PoseStack, projection: Matrix4f, camera: Camera): Boolean {
        if (IrisCompat.isShaderPackInUse()) return false
        val eff = effect.get() ?: return false
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = Minecraft.getInstance().mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        u.pushFrame(eff, main.width, main.height)
        u.flow.set(((System.nanoTime() * 1e-9 * FLOW_RATE) % (Math.PI * 2.0)).toFloat())
        u.writhe.set(writhePhase().toFloat())
        active = eff
        drawn = 0
        return true
    }

    fun draw(frame: TendrilFrame, curve: TendrilCurve, reach: Double, strength: Float, style: TendrilStyle) {
        val eff = active ?: return
        val u = uniforms ?: return
        if (reach <= 0.0 || strength <= 0f) return
        val o = frame.origin
        val toWorld = frame.toWorld
        if (toWorld == null) {
            localToViewD.translation(o.x - view.camX, o.y - view.camY, o.z - view.camZ)
        } else {
            localToViewD.translation(-view.camX, -view.camY, -view.camZ).mul(toWorld).translate(o.x, o.y, o.z)
        }
        curveBox(o, curve, style.radius * 3f + curve.wild.toFloat())
        localToViewD.transformAab(boxMin, boxMax, viewMin, viewMax)
        if (!view.boxVisible(
                viewMin.x.toFloat(), viewMin.y.toFloat(), viewMin.z.toFloat(),
                viewMax.x.toFloat(), viewMax.y.toFloat(), viewMax.z.toFloat(),
            )
        ) return
        localToView.set(localToViewD)
        viewToLocal.set(localToViewD).invertAffine().getTranslation(camLocal)
        pushCurve(u, o, curve)
        u.localToView.set(localToView)
        u.camLocal.set(camLocal.x.toFloat(), camLocal.y.toFloat(), camLocal.z.toFloat())
        u.reach.set(reach.toFloat())
        u.radius.set(style.radius)
        u.strength.set(strength)
        u.wild.set(curve.wild.toFloat())
        u.seed.set(curve.seed.toFloat())
        u.colourStart.set(style.from[0], style.from[1], style.from[2])
        u.colourEnd.set(style.to[0], style.to[1], style.to[2])
        val main = Minecraft.getInstance().mainRenderTarget
        if (drawn == 0) {
            VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
            VolumeProxy.draw(eff)
        } else {
            u.upload()
            VolumeProxy.drawApplied()
        }
        drawn++
    }

    fun end() {
        val eff = active ?: return
        active = null
        if (drawn > 0) VolumeProxy.end(Minecraft.getInstance().mainRenderTarget, eff)
    }

    // Float math, so the box the shader gets is the same one the cull tests.
    private fun curveBox(o: Vector3d, c: TendrilCurve, margin: Float) {
        boxMin.set(
            ((minOf(c.p0.x, c.p1.x, c.p2.x) - o.x).toFloat() - margin).toDouble(),
            ((minOf(c.p0.y, c.p1.y, c.p2.y) - o.y).toFloat() - margin).toDouble(),
            ((minOf(c.p0.z, c.p1.z, c.p2.z) - o.z).toFloat() - margin).toDouble(),
        )
        boxMax.set(
            ((maxOf(c.p0.x, c.p1.x, c.p2.x) - o.x).toFloat() + margin).toDouble(),
            ((maxOf(c.p0.y, c.p1.y, c.p2.y) - o.y).toFloat() + margin).toDouble(),
            ((maxOf(c.p0.z, c.p1.z, c.p2.z) - o.z).toFloat() + margin).toDouble(),
        )
    }

    private fun pushCurve(u: Uniforms, o: Vector3d, c: TendrilCurve) {
        u.boxMin.set(boxMin.x.toFloat(), boxMin.y.toFloat(), boxMin.z.toFloat())
        u.boxMax.set(boxMax.x.toFloat(), boxMax.y.toFloat(), boxMax.z.toFloat())
        u.p0.set((c.p0.x - o.x).toFloat(), (c.p0.y - o.y).toFloat(), (c.p0.z - o.z).toFloat())
        u.p1.set((c.p1.x - o.x).toFloat(), (c.p1.y - o.y).toFloat(), (c.p1.z - o.z).toFloat())
        u.p2.set((c.p2.x - o.x).toFloat(), (c.p2.y - o.y).toFloat(), (c.p2.z - o.z).toFloat())
    }

    private class Uniforms(eff: EffectInstance) {
        val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        val localToView: AbstractUniform = eff.safeGetUniform("LocalToView")
        val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        val p0: AbstractUniform = eff.safeGetUniform("P0")
        val p1: AbstractUniform = eff.safeGetUniform("P1")
        val p2: AbstractUniform = eff.safeGetUniform("P2")
        val reach: AbstractUniform = eff.safeGetUniform("Reach")
        val radius: AbstractUniform = eff.safeGetUniform("Radius")
        val strength: AbstractUniform = eff.safeGetUniform("Strength")
        val flow: AbstractUniform = eff.safeGetUniform("Flow")
        val wild: AbstractUniform = eff.safeGetUniform("Wild")
        val seed: AbstractUniform = eff.safeGetUniform("Seed")
        val writhe: AbstractUniform = eff.safeGetUniform("Writhe")
        val colourStart: AbstractUniform = eff.safeGetUniform("ColourStart")
        val colourEnd: AbstractUniform = eff.safeGetUniform("ColourEnd")
        private val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        // Flow and Writhe are set one time for each frame, before the first draw applies the program.
        private val perTendril: List<Uniform> = listOf(
            boxMin, boxMax, localToView, camLocal, p0, p1, p2, reach, radius, strength, colourStart, colourEnd, wild, seed,
        ).filterIsInstance<Uniform>()

        fun upload() {
            for (uniform in perTendril) uniform.upload()
        }

        fun pushFrame(eff: EffectInstance, width: Int, height: Int) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            screenSize.set(width.toFloat(), height.toFloat())
        }
    }
}
