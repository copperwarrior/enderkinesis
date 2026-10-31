package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.Uniform
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.joml.Matrix4f
import org.joml.Vector3fc
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView

/** A faint golden light in the writing plane of each Oarscribe glyph, which fades with the glyph. Render thread only. */
object OarscribeGlyphLight {
    private const val CAPACITY = 256
    private const val MAX_LIGHTS_PER_FRAME = 64
    private const val VIEW_DISTANCE_SQR = 64.0 * 64.0

    /** Same as the glyph particle. */
    private const val LIFETIME_TICKS = 100f

    private const val THICKNESS = 0.12f
    private const val HALF_WIDTH = 0.5f

    // Face-on, the light adds Emission × Thickness × √π, about 0.1.
    private const val EMISSION = 0.5f

    // Holds the Gaussian out to more than 3 radii.
    private const val BOX_HALF_HEIGHT = 0.4f

    // The warm Sselith yellow of the glyphs.
    private const val RED = 1.0f
    private const val GREEN = 0.8f
    private const val BLUE = 0.3f

    private val view = VolumeView()
    private val effect = VolumeEffect("oarscribe_glyph_light")
    private var uniforms: Uniforms? = null
    private val localToView = Matrix4f()

    // A ring of the newest glyphs. The oldest is overwritten when it is full.
    private val posX = DoubleArray(CAPACITY)
    private val posY = DoubleArray(CAPACITY)
    private val posZ = DoubleArray(CAPACITY)
    private val axes = FloatArray(CAPACITY * 6)
    private val bornAt = FloatArray(CAPACITY)
    private var next = 0
    private var count = 0

    /** [face] is out of the writing plane and [line] runs along it; both are world-frame unit vectors. */
    fun add(x: Double, y: Double, z: Double, face: Vector3fc, line: Vector3fc) {
        val level = Minecraft.getInstance().level ?: return
        posX[next] = x
        posY[next] = y
        posZ[next] = z
        val a = next * 6
        axes[a] = line.x()
        axes[a + 1] = line.y()
        axes[a + 2] = line.z()
        axes[a + 3] = face.x()
        axes[a + 4] = face.y()
        axes[a + 5] = face.z()
        bornAt[next] = (level.gameTime % TIME_WRAP_TICKS).toFloat()
        next = (next + 1) % CAPACITY
        count = minOf(count + 1, CAPACITY)
    }

    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera, partialTick: Float) {
        if (count == 0 || IrisCompat.isShaderPackInUse()) return
        val level = Minecraft.getInstance().level ?: return
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = Minecraft.getInstance().mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        u.pushFrame(main.width, main.height)
        val now = (level.gameTime % TIME_WRAP_TICKS) + partialTick
        var drawn = 0
        var live = 0
        for (k in 0 until count) {
            val i = Math.floorMod(next - 1 - k, CAPACITY)
            val age = (now - bornAt[i] + TIME_WRAP_TICKS) % TIME_WRAP_TICKS
            // Newest first, so the first one too old ends the live part of the ring.
            if (age >= LIFETIME_TICKS) break
            live++
            if (drawn >= MAX_LIGHTS_PER_FRAME || !pushGlyph(u, i, 1f - age / LIFETIME_TICKS)) continue
            if (drawn == 0) {
                VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
                VolumeProxy.draw(eff)
            } else {
                u.uploadGlyph()
                VolumeProxy.drawApplied()
            }
            drawn++
        }
        count = live
        if (drawn > 0) VolumeProxy.end(main, eff)
    }

    /** False when the glyph is out of range. */
    private fun pushGlyph(u: Uniforms, i: Int, fade: Float): Boolean {
        val sx = posX[i] - view.camX
        val sy = posY[i] - view.camY
        val sz = posZ[i] - view.camZ
        if (sx * sx + sy * sy + sz * sz > VIEW_DISTANCE_SQR) return false
        val a = i * 6
        // X along the line, Y out of the plane, Z = X × Y across the glyph.
        val xx = axes[a]
        val xy = axes[a + 1]
        val xz = axes[a + 2]
        val yx = axes[a + 3]
        val yy = axes[a + 4]
        val yz = axes[a + 5]
        val zx = xy * yz - xz * yy
        val zy = xz * yx - xx * yz
        val zz = xx * yy - xy * yx
        localToView.set(
            xx, xy, xz, 0f,
            yx, yy, yz, 0f,
            zx, zy, zz, 0f,
            sx.toFloat(), sy.toFloat(), sz.toFloat(), 1f,
        )
        u.localToView?.set(localToView)
        u.camLocal?.set(
            -(sx * xx + sy * xy + sz * xz).toFloat(),
            -(sx * yx + sy * yy + sz * yz).toFloat(),
            -(sx * zx + sy * zy + sz * zz).toFloat(),
        )
        u.fade?.set(fade)
        return true
    }

    // Keeps the float game time small. The lifetime is far shorter, so a wrap only drops lights early once.
    private const val TIME_WRAP_TICKS = 24_000L

    private class Uniforms(private val eff: EffectInstance) {
        val localToView: Uniform? = eff.getUniform("LocalToView")
        val camLocal: Uniform? = eff.getUniform("CamLocal")
        val fade: Uniform? = eff.getUniform("Fade")

        // The program stays bound between glyphs, so only these change after the first apply().
        fun uploadGlyph() {
            localToView?.upload()
            camLocal?.upload()
            fade?.upload()
        }

        fun pushFrame(width: Int, height: Int) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            eff.safeGetUniform("ScreenSize").set(width.toFloat(), height.toFloat())
            eff.safeGetUniform("BoxMin").set(-1f, -BOX_HALF_HEIGHT, -HALF_WIDTH)
            eff.safeGetUniform("BoxMax").set(1f, BOX_HALF_HEIGHT, HALF_WIDTH)
            eff.safeGetUniform("Colour").set(RED, GREEN, BLUE)
            eff.safeGetUniform("Emission").set(EMISSION)
            eff.safeGetUniform("Thickness").set(THICKNESS)
            eff.safeGetUniform("HalfWidth").set(HALF_WIDTH)
        }
    }
}
