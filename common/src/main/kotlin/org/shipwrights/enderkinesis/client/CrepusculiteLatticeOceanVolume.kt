package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import java.util.function.IntSupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.joml.FrustumIntersection
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL30
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeTextures
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.physics.GerstnerOcean
import org.shipwrights.enderkinesis.physics.LatticeCatchZone

/**
 * Volumetric body of the lattice ocean: one full-screen pass for each zone, drawn before the
 * wave mesh. The top is the wave mesh: its retained buffers go top-down into a height map
 * first, and each march step of the shader tests its point against that height. Below the
 * surface, the volume has straight walls in the mesh shore outline from
 * [CrepusculiteLatticeShoreMap], and its density fades to zero at the deepest level.
 * Render thread only.
 */
object CrepusculiteLatticeOceanVolume {
    /** Fog density for each block of water on the view ray. */
    private const val DENSITY = 0.04f
    private const val MAX_OPACITY = 0.9f

    /** How far a dyed ocean goes from its tint to its dark shade at the bottom. */
    private const val DYED_DEPTH_SHADE = 0.5f
    private const val MAX_ZONES_PER_FRAME = 8

    private const val HEIGHT_MAP_SIZE = 256

    private const val CULL_MARGIN = 2.0
    private val CULL_PLANES = FrustumIntersection.PLANE_MASK_NX or FrustumIntersection.PLANE_MASK_PX or
        FrustumIntersection.PLANE_MASK_NY or FrustumIntersection.PLANE_MASK_PY or FrustumIntersection.PLANE_MASK_NZ

    /** Room above the largest wave sum, in blocks: with a ship wake in the level, and with none. */
    private const val WAKE_MARGIN = 12.0
    private const val NO_WAKE_MARGIN = 1.0

    private val effect = VolumeEffect(
        "lattice_ocean_volume",
        "HeightMap" to IntSupplier { heightTex },
        "ShoreMap" to IntSupplier { shoreMap?.textureId ?: -1 },
        "HullMask" to IntSupplier { mask?.textureId ?: -1 },
    )
    private var active = false
    private var stateSet = false
    private var zonesDrawn = 0

    private var heightFbo = -1
    private var heightTex = -1
    private var heightDepthTex = -1

    private val view = VolumeView()
    private val frustum = FrustumIntersection()
    private val maskMatD = Matrix4d()
    private val maskMatF = Matrix4f()

    private var zone: LatticeCatchZone? = null
    private var zoneFade = 0f
    private var shoreMap: CrepusculiteLatticeShoreMap.ShoreMap? = null
    private var depthRadius = 0f
    private var heightRange = 1f
    private var mask: CrepusculiteLatticeHullMask.Mask? = null

    private class Uniforms(val effect: EffectInstance) {
        val zoneCenter = effect.safeGetUniform("ZoneCenter")
        val zoneRadii = effect.safeGetUniform("ZoneRadii")
        val zoneYaw = effect.safeGetUniform("ZoneYaw")
        val waterY = effect.safeGetUniform("WaterY")
        val heightRange = effect.safeGetUniform("HeightRange")
        val topColor = effect.safeGetUniform("TopColor")
        val deepColor = effect.safeGetUniform("DeepColor")
        val density = effect.safeGetUniform("Density")
        val opacity = effect.safeGetUniform("Opacity")
        val zoneFade = effect.safeGetUniform("ZoneFade")
        val maskSize = effect.safeGetUniform("MaskSize")
        val maskMat = effect.safeGetUniform("MaskMat")
    }

    private var uniforms: Uniforms? = null

    private val pushUniforms: (EffectInstance) -> Unit = { eff ->
        val z = checkNotNull(zone)
        val u = uniforms?.takeIf { it.effect === eff } ?: Uniforms(eff).also { uniforms = it }
        view.pushInvViewProj(eff)
        val waterY = (z.waterLineY - view.camY).toFloat()
        u.zoneCenter.set((z.cx - view.camX).toFloat(), waterY, (z.cz - view.camZ).toFloat())
        u.zoneRadii.set(z.ax.toFloat(), depthRadius, z.az.toFloat())
        u.zoneYaw.set(z.yawCos.toFloat(), z.yawSin.toFloat())
        u.waterY.set(waterY)
        u.heightRange.set(heightRange)
        u.topColor.set(z.tintR, z.tintG, z.tintB)
        // An ocean with no dye goes to ender green at the bottom. A dyed ocean stays near its tint.
        val shade = if (z.dyed) DYED_DEPTH_SHADE else 1f
        u.deepColor.set(
            z.tintR + (z.tintDeepR - z.tintR) * shade,
            z.tintG + (z.tintDeepG - z.tintG) * shade,
            z.tintB + (z.tintDeepB - z.tintB) * shade,
        )
        u.density.set(DENSITY)
        u.opacity.set(MAX_OPACITY)
        u.zoneFade.set(zoneFade)
        val m = mask
        if (m == null) {
            u.maskSize.set(0, 0, 0)
        } else {
            u.maskMat.set(maskMatF)
            u.maskSize.set(m.texW, m.texH, m.texD)
        }
    }

    /** Call one time before the zones of a frame. */
    fun begin(poseStack: PoseStack, cameraX: Double, cameraY: Double, cameraZ: Double) {
        active = false
        stateSet = false
        zonesDrawn = 0
        if (IrisCompat.isShaderPackInUse() || effect.get() == null) return
        view.update(RenderSystem.getProjectionMatrix(), poseStack.last().pose(), cameraX, cameraY, cameraZ)
        frustum.set(view.viewProj, false)
        active = true
    }

    /**
     * Binds the height map for [zone] and clears it. When this returns true, the caller draws
     * the wave mesh buffers of the zone with the surface shader and [heightMatrix], and then
     * calls [draw].
     */
    fun beginHeightMap(zone: LatticeCatchZone, hasWake: Boolean): Boolean {
        if (!active || zonesDrawn >= MAX_ZONES_PER_FRAME) return false
        // The bottom is at the lowest point of the zone ellipsoid.
        val depth = zone.waterLineY - (zone.cy - zone.ay)
        if (depth <= 0.0) return false
        // A low top bound keeps the march out of the air above the waves.
        val margin = if (hasWake) WAKE_MARGIN else NO_WAKE_MARGIN
        // The Stokes term of the wave sum grows with the square of the intensity.
        val intensity = zone.waveIntensity
        val range = (GerstnerOcean.maxAmplitude * intensity * Math.max(1.0, intensity) + margin).toFloat()
        if (!inView(zone, depth, range.toDouble())) return false
        depthRadius = depth.toFloat()
        heightRange = range
        ensureHeightTarget()
        stateSet = true
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, heightFbo)
        GlStateManager._viewport(0, 0, HEIGHT_MAP_SIZE, HEIGHT_MAP_SIZE)
        RenderSystem.depthMask(true)
        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.clearColor(0f, 0f, 0f, 0f)
        RenderSystem.clearDepth(1.0)
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX)
        // Seen from above, the nearest fragment is the highest of the wave layers.
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(GL11.GL_LEQUAL)
        RenderSystem.disableCull()
        RenderSystem.disableBlend()
        return true
    }

    // The far plane is not tested: a march can reach past it. The camera can be in the water.
    private fun inView(zone: LatticeCatchZone, depth: Double, range: Double): Boolean {
        val minX = zone.cx - zone.boundX - CULL_MARGIN - view.camX
        val maxX = zone.cx + zone.boundX + CULL_MARGIN - view.camX
        val minY = zone.waterLineY - depth - CULL_MARGIN - view.camY
        val maxY = zone.waterLineY + range + CULL_MARGIN - view.camY
        val minZ = zone.cz - zone.boundZ - CULL_MARGIN - view.camZ
        val maxZ = zone.cz + zone.boundZ + CULL_MARGIN - view.camZ
        if (minX <= 0.0 && maxX >= 0.0 && minY <= 0.0 && maxY >= 0.0 && minZ <= 0.0 && maxZ >= 0.0) return true
        val result = frustum.intersectAab(
            minX.toFloat(), minY.toFloat(), minZ.toFloat(), maxX.toFloat(), maxY.toFloat(), maxZ.toFloat(),
            CULL_PLANES,
        )
        return result == FrustumIntersection.INSIDE || result == FrustumIntersection.INTERSECT
    }

    /**
     * Model-view matrix that puts a mesh buffer with the given origin into the height map of
     * [zone]: clip x and y are the zone ellipse coordinates, and clip z is the height above the
     * waterline over the height range, with up as near. Use an identity projection.
     */
    fun heightMatrix(zone: LatticeCatchZone, originX: Double, originY: Double, originZ: Double, out: Matrix4f): Matrix4f {
        val dx = originX - zone.cx
        val dz = originZ - zone.cz
        val xx = (zone.yawCos / zone.ax).toFloat()
        val xz = (-zone.yawSin / zone.ax).toFloat()
        val zx = (zone.yawSin / zone.az).toFloat()
        val zz = (zone.yawCos / zone.az).toFloat()
        return out.set(
            xx, zx, 0f, 0f,
            0f, 0f, -1f / heightRange, 0f,
            xz, zz, 0f, 0f,
            ((zone.yawCos * dx - zone.yawSin * dz) / zone.ax).toFloat(),
            ((zone.yawSin * dx + zone.yawCos * dz) / zone.az).toFloat(),
            (-(originY - zone.waterLineY) / heightRange).toFloat(),
            1f,
        )
    }

    /** Draws the volume of [zone]. Call only after [beginHeightMap] returned true. */
    fun draw(
        zone: LatticeCatchZone, cloakFade: Float, dyeDim: Float,
        shoreMap: CrepusculiteLatticeShoreMap.ShoreMap,
        mask: CrepusculiteLatticeHullMask.Mask?, worldToShip: Matrix4dc?,
    ) {
        val eff = effect.get() ?: return
        this.zone = zone
        this.zoneFade = cloakFade * dyeDim
        this.shoreMap = shoreMap
        this.mask = if (worldToShip == null) null else mask
        if (mask != null && worldToShip != null) {
            // Camera-relative world -> shipyard -> mask cell. Doubles: shipyard coordinates are large.
            maskMatD.translation(-mask.texMinX.toDouble(), -mask.texMinY.toDouble(), -mask.texMinZ.toDouble())
                .mul(worldToShip)
                .translate(view.camX, view.camY, view.camZ)
            maskMatF.set(maskMatD)
        }
        VolumeComposite.drawOverMain(Minecraft.getInstance().mainRenderTarget, eff, pushUniforms)
        zonesDrawn++
    }

    /** Call one time after the zones of a frame. Restores the state that the zone passes changed. */
    fun end() {
        zone = null
        mask = null
        shoreMap = null
        if (!stateSet) return
        stateSet = false
        VolumeComposite.restore(Minecraft.getInstance().mainRenderTarget)
    }

    // R = height over the height range, G = 1 where the mesh has a fragment. With linear
    // filtering, R / G is the height at a point that is part mesh and part empty.
    private fun ensureHeightTarget() {
        if (heightFbo != -1) return
        heightTex = GlStateManager._genTexture()
        VolumeTextures.allocate(
            heightTex, GL30.GL_RG16F, HEIGHT_MAP_SIZE, HEIGHT_MAP_SIZE, GL30.GL_RG, GL11.GL_FLOAT,
            GL11.GL_LINEAR, clampToEdge = true,
        )
        heightDepthTex = GlStateManager._genTexture()
        VolumeTextures.allocate(
            heightDepthTex, GL14.GL_DEPTH_COMPONENT24, HEIGHT_MAP_SIZE, HEIGHT_MAP_SIZE,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, GL11.GL_NEAREST,
        )
        heightFbo = GlStateManager.glGenFramebuffers()
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, heightFbo)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, heightTex, 0)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, heightDepthTex, 0)
    }
}
