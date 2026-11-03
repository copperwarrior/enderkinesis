package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.shaders.Uniform
import com.mojang.blaze3d.vertex.PoseStack
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.Util
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView

/**
 * Glow volume along each orb link: one hourglass tube on the glyph particle curve that holds both orbs.
 * The link colours share the cross-section in equal angular parts, with gradients between them,
 * and the parts turn slowly about the beam axis. An emissive halo envelope wraps the beam and both
 * orbs. Render thread only.
 */
object OrbLinkVolume {
    private const val MAX_BEAMS_PER_FRAME = 32
    private const val MAX_ACCENTS = 8

    // A beam shorter than the sag has no well-defined curve parameter along its chord.
    private const val MIN_BEAM_LENGTH = 2.0 * OrbOfLinkingClient.BEAM_SAG
    // Holds the 10 px haze cube (half-diagonal 5√3/16 ≈ 0.54) with its corners at 72% of the radius.
    private const val ENVELOPE_RADIUS = 0.75f
    private const val WAIST_RADIUS = 0.3f
    private const val DENSITY = 1.6f
    // Side-on, an emissive Gaussian adds emission × radius × √π at its axis: about half here.
    private const val HALO_RADIUS = 0.15f
    private const val HALO_EMISSION = 1.7f

    // Halo radius at the orbs: the haze-cube corners (0.54 from the centre) sit at a quarter of its peak.
    private const val HALO_END_RADIUS = 0.45f

    // Glow brightness at the tube edge; it rises to 1 at the axis.
    private const val EDGE_BRIGHTNESS = 0.35f
    private const val OPACITY = 0.6f

    /** Turn rate of the colour parts about the beam axis, in radians per second. */
    private const val SPIN_RATE = 0.6
    private const val TAU = Math.PI * 2.0

    private val view = VolumeView()
    private val effect = VolumeEffect("orb_link_volume")
    private var uniforms: Uniforms? = null

    private val localToView = Matrix4f()
    private val accentsLo = Matrix4f()
    private val accentsHi = Matrix4f()
    private val accentScratch = FloatArray(MAX_ACCENTS * 4)
    private val accentKinds = arrayOfNulls<ResourceLocation>(MAX_ACCENTS)

    // Links at each orb this frame. Each beam adds its share of the halo at the orb, so the
    // halo does not add up to white on an orb with many links.
    private val orbLinks = Long2IntOpenHashMap()

    // Called at renderLevel TAIL, after clouds and weather: the volume writes no depth, so anything
    // drawn after it would paint over it.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val bins = OrbBeamLineRenderer.visibleBins
        if (bins.isEmpty() || IrisCompat.isShaderPackInUse()) return
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = Minecraft.getInstance().mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        u.pushFrame(main.width, main.height)
        val tSec = Util.getMillis() / 1000.0
        orbLinks.clear()
        for (bin in bins) {
            orbLinks.addTo(bin.sendPos.asLong(), 1)
            orbLinks.addTo(bin.recvPos.asLong(), 1)
        }

        // One tube for each piece of a beam that Ulder Beam Anchors bend.
        var drawn = 0
        for (bin in bins) {
            for (index in bin.pieces.indices) {
                if (drawn >= MAX_BEAMS_PER_FRAME) break
                if (!pushBeam(u, bin, index, tSec)) continue
                if (drawn == 0) {
                    VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
                    VolumeProxy.draw(eff)
                } else {
                    u.uploadBeam()
                    VolumeProxy.drawApplied()
                }
                drawn++
            }
        }
        if (drawn > 0) VolumeProxy.end(main, eff)
    }

    /** False when the beam is out of range or degenerate. */
    @Suppress("LongMethod") // One linear frame build; splitting it only moves the scratch state around.
    private fun pushBeam(u: Uniforms, bin: OrbBeamLineRenderer.BeamBin, index: Int, tSec: Double): Boolean {
        val piece = bin.pieces[index]
        val start = piece.start
        val end = piece.end
        val sx = start.x - view.camX
        val sy = start.y - view.camY
        val sz = start.z - view.camZ
        val rx = end.x - view.camX
        val ry = end.y - view.camY
        val rz = end.z - view.camZ
        val viewSqr = OrbBeamLineRenderer.ENDPOINT_VIEW_DISTANCE_SQR
        if (sx * sx + sy * sy + sz * sz > viewSqr && rx * rx + ry * ry + rz * rz > viewSqr) return false
        val cx = rx - sx
        val cy = ry - sy
        val cz = rz - sz
        val len = Math.sqrt(cx * cx + cy * cy + cz * cz)
        // The negated form also rejects NaN.
        if (!(len >= MIN_BEAM_LENGTH && len <= OrbBeamLineRenderer.MAX_RENDERED_BEAM_LENGTH)) return false

        // Beam axes: X along the chord, Y toward the sag (world down, without its chord part), Z = X × Y.
        val xx = cx / len
        val xy = cy / len
        val xz = cz / len
        var yx = xy * xx
        var yy = xy * xy - 1.0
        var yz = xy * xz
        var yLen = Math.sqrt(yx * yx + yy * yy + yz * yz)
        if (yLen < 1.0e-3) {
            yx = 1.0 - xx * xx
            yy = -xx * xy
            yz = -xx * xz
            yLen = Math.sqrt(yx * yx + yy * yy + yz * yz)
        }
        yx /= yLen
        yy /= yLen
        yz /= yLen
        val zx = xy * yz - xz * yy
        val zy = xz * yx - xx * yz
        val zz = xx * yy - xy * yx

        // Same control point as OrbOfLinkingClient.updateBeam, relative to the piece start.
        val kx = piece.control.x - start.x
        val ky = piece.control.y - start.y
        val kz = piece.control.z - start.z
        val controlX = (kx * xx + ky * xy + kz * xz).toFloat()
        val controlY = (kx * yx + ky * yy + kz * yz).toFloat()
        val controlZ = (kx * zx + ky * zy + kz * zz).toFloat()

        // The curve's largest offset from the chord is half the control offset, at t = 0.5.
        // Room for the halo caps to fade out before the box edge.
        val radius = maxOf(ENVELOPE_RADIUS, HALO_END_RADIUS * 2f)
        u.boxMin.set(-radius, minOf(0f, controlY * 0.5f) - radius, minOf(0f, controlZ * 0.5f) - radius)
        u.boxMax.set(len.toFloat() + radius, maxOf(0f, controlY * 0.5f) + radius, maxOf(0f, controlZ * 0.5f) + radius)
        localToView.set(
            xx.toFloat(), xy.toFloat(), xz.toFloat(), 0f,
            yx.toFloat(), yy.toFloat(), yz.toFloat(), 0f,
            zx.toFloat(), zy.toFloat(), zz.toFloat(), 0f,
            sx.toFloat(), sy.toFloat(), sz.toFloat(), 1f,
        )
        u.localToView.set(localToView)
        u.camLocal.set(
            -(sx * xx + sy * xy + sz * xz).toFloat(),
            -(sx * yx + sy * yy + sz * yz).toFloat(),
            -(sx * zx + sy * zy + sz * zz).toFloat(),
        )
        u.beamLen.set(len.toFloat())
        u.orbShare.set(1f / orbLinks.get(bin.sendPos.asLong()), 1f / orbLinks.get(bin.recvPos.asLong()))
        u.profileSpan.set(piece.spanStart.toFloat(), piece.spanEnd.toFloat())
        u.control.set(controlX, controlY, controlZ)

        if (!pushAccents(u, bin)) return false
        // Reduced on the CPU in double: the shader keeps only float precision. The pair offset stops
        // neighbouring beams from turning in lockstep.
        u.spin.set((((tSec + bin.timeOffset) * SPIN_RATE) % TAU).toFloat())
        return true
    }

    /** Pushes the link colours of the bin, sorted by tome kind so the parts keep their order. False with no links. */
    private fun pushAccents(u: Uniforms, bin: OrbBeamLineRenderer.BeamBin): Boolean {
        var count = 0
        for (kind in bin.tomes.keys) {
            if (count >= MAX_ACCENTS) break
            var i = count++
            while (i > 0 && checkNotNull(accentKinds[i - 1]) > kind) {
                accentKinds[i] = accentKinds[i - 1]
                i--
            }
            accentKinds[i] = kind
        }
        for (i in 0 until count) {
            val packed = checkNotNull(bin.tomes[accentKinds[i]])
            accentScratch[i * 4] = ((packed ushr 16) and 0xFF) / 255f
            accentScratch[i * 4 + 1] = ((packed ushr 8) and 0xFF) / 255f
            accentScratch[i * 4 + 2] = (packed and 0xFF) / 255f
            accentScratch[i * 4 + 3] = 1f
            accentKinds[i] = null
        }
        accentsLo.set(accentScratch, 0)
        accentsHi.set(accentScratch, 16)
        u.accentsLo.set(accentsLo)
        u.accentsHi.set(accentsHi)
        u.accentCount.set(count)
        return count > 0
    }

    private class Uniforms(private val eff: EffectInstance) {
        val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        val localToView: AbstractUniform = eff.safeGetUniform("LocalToView")
        val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        val beamLen: AbstractUniform = eff.safeGetUniform("BeamLen")
        val control: AbstractUniform = eff.safeGetUniform("Control")
        val accentsLo: AbstractUniform = eff.safeGetUniform("AccentsLo")
        val accentsHi: AbstractUniform = eff.safeGetUniform("AccentsHi")
        val accentCount: AbstractUniform = eff.safeGetUniform("AccentCount")
        val spin: AbstractUniform = eff.safeGetUniform("Spin")
        val orbShare: AbstractUniform = eff.safeGetUniform("OrbShare")
        val profileSpan: AbstractUniform = eff.safeGetUniform("ProfileSpan")
        private val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        private val envelopeRadius: AbstractUniform = eff.safeGetUniform("EnvelopeRadius")
        private val waistRadius: AbstractUniform = eff.safeGetUniform("WaistRadius")
        private val haloRadius: AbstractUniform = eff.safeGetUniform("HaloRadius")
        private val haloEmission: AbstractUniform = eff.safeGetUniform("HaloEmission")
        private val haloEndRadius: AbstractUniform = eff.safeGetUniform("HaloEndRadius")
        private val edgeBrightness: AbstractUniform = eff.safeGetUniform("EdgeBrightness")
        private val density: AbstractUniform = eff.safeGetUniform("Density")
        private val opacity: AbstractUniform = eff.safeGetUniform("Opacity")
        private val perBeam: List<Uniform> = listOf(
            boxMin, boxMax, localToView, camLocal, beamLen, control,
            accentsLo, accentsHi, accentCount, spin, orbShare, profileSpan,
        ).filterIsInstance<Uniform>()

        fun uploadBeam() {
            for (uniform in perBeam) uniform.upload()
        }

        fun pushFrame(width: Int, height: Int) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            screenSize.set(width.toFloat(), height.toFloat())
            envelopeRadius.set(ENVELOPE_RADIUS)
            waistRadius.set(WAIST_RADIUS)
            haloRadius.set(HALO_RADIUS)
            haloEmission.set(HALO_EMISSION)
            haloEndRadius.set(HALO_END_RADIUS)
            edgeBrightness.set(EDGE_BRIGHTNESS)
            density.set(DENSITY)
            opacity.set(OPACITY)
        }
    }
}
