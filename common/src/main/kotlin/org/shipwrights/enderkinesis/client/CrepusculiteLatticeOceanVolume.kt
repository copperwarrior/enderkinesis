package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.logging.LogUtils
import java.nio.ByteBuffer
import java.util.function.IntSupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL30
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
    private val LOG = LogUtils.getLogger()

    /** Fog density for each block of water on the view ray. */
    private const val DENSITY = 0.04f
    private const val MAX_OPACITY = 0.9f

    /** How far a dyed ocean goes from its tint to its dark shade at the bottom. */
    private const val DYED_DEPTH_SHADE = 0.5f
    private const val MAX_ZONES_PER_FRAME = 8

    private const val HEIGHT_MAP_SIZE = 256

    /** Room above the largest wave sum, in blocks: with a ship wake in the level, and with none. */
    private const val WAKE_MARGIN = 12.0
    private const val NO_WAKE_MARGIN = 1.0

    private val OPAQUE_BLEND = BlendMode()

    private var effect: EffectInstance? = null
    private var loadFailed = false
    private var active = false
    private var stateSet = false
    private var zonesDrawn = 0

    private var heightFbo = -1
    private var heightTex = -1
    private var heightDepthTex = -1

    private val invViewProj = Matrix4f()
    private val maskMatD = Matrix4d()
    private val maskMatF = Matrix4f()
    private var cameraX = 0.0
    private var cameraY = 0.0
    private var cameraZ = 0.0

    private var zone: LatticeCatchZone? = null
    private var zoneFade = 0f
    private var shoreMap: CrepusculiteLatticeShoreMap.ShoreMap? = null
    private var depthRadius = 0f
    private var heightRange = 1f
    private var mask: CrepusculiteLatticeHullMask.Mask? = null

    private val pushUniforms: (EffectInstance) -> Unit = { eff ->
        val z = checkNotNull(zone)
        eff.safeGetUniform("InvViewProj").set(invViewProj)
        val waterY = (z.waterLineY - cameraY).toFloat()
        eff.safeGetUniform("ZoneCenter").set((z.cx - cameraX).toFloat(), waterY, (z.cz - cameraZ).toFloat())
        eff.safeGetUniform("ZoneRadii").set(z.ax.toFloat(), depthRadius, z.az.toFloat())
        eff.safeGetUniform("ZoneYaw").set(z.yawCos.toFloat(), z.yawSin.toFloat())
        eff.safeGetUniform("WaterY").set(waterY)
        eff.safeGetUniform("HeightRange").set(heightRange)
        eff.safeGetUniform("TopColor").set(z.tintR, z.tintG, z.tintB)
        // An ocean with no dye goes to ender green at the bottom. A dyed ocean stays near its tint.
        val shade = if (z.dyed) DYED_DEPTH_SHADE else 1f
        eff.safeGetUniform("DeepColor").set(
            z.tintR + (z.tintDeepR - z.tintR) * shade,
            z.tintG + (z.tintDeepG - z.tintG) * shade,
            z.tintB + (z.tintDeepB - z.tintB) * shade,
        )
        eff.safeGetUniform("Density").set(DENSITY)
        eff.safeGetUniform("Opacity").set(MAX_OPACITY)
        eff.safeGetUniform("ZoneFade").set(zoneFade)
        val m = mask
        if (m == null) {
            eff.safeGetUniform("MaskSize").set(0, 0, 0)
        } else {
            eff.safeGetUniform("MaskMat").set(maskMatF)
            eff.safeGetUniform("MaskSize").set(m.texW, m.texH, m.texD)
        }
    }

    /** Call one time before the zones of a frame. */
    fun begin(poseStack: PoseStack, cameraX: Double, cameraY: Double, cameraZ: Double) {
        active = false
        stateSet = false
        zonesDrawn = 0
        if (loadFailed || IrisCompat.isShaderPackInUse()) return
        if (effect == null) {
            effect = load()
            if (effect == null) {
                loadFailed = true
                return
            }
        }
        this.cameraX = cameraX
        this.cameraY = cameraY
        this.cameraZ = cameraZ
        // The pose holds only the camera rotation, so the inverse maps clip space to camera-relative world space.
        invViewProj.set(RenderSystem.getProjectionMatrix()).mul(poseStack.last().pose()).invert()
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
        depthRadius = depth.toFloat()
        // A low top bound keeps the march out of the air above the waves.
        val margin = if (hasWake) WAKE_MARGIN else NO_WAKE_MARGIN
        // The Stokes term of the wave sum grows with the square of the intensity.
        val intensity = zone.waveIntensity
        heightRange = (GerstnerOcean.maxAmplitude * intensity * Math.max(1.0, intensity) + margin).toFloat()
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
        val eff = effect ?: return
        val main = Minecraft.getInstance().mainRenderTarget
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        MainColorOnlyTarget.bind(main)
        GlStateManager._viewport(0, 0, main.width, main.height)
        // BlendMode.apply skips all state, enableBlend too, when this effect's mode was the last one applied.
        RenderSystem.enableBlend()
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD)
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE,
        )
        this.zone = zone
        this.zoneFade = cloakFade * dyeDim
        this.shoreMap = shoreMap
        this.mask = if (worldToShip == null) null else mask
        if (mask != null && worldToShip != null) {
            // Camera-relative world -> shipyard -> mask cell. Doubles: shipyard coordinates are large.
            maskMatD.translation(-mask.texMinX.toDouble(), -mask.texMinY.toDouble(), -mask.texMinZ.toDouble())
                .mul(worldToShip)
                .translate(cameraX, cameraY, cameraZ)
            maskMatF.set(maskMatD)
        }
        RefractionPass.drawQuad(eff, main.width, main.height, pushUniforms)
        zonesDrawn++
    }

    /** Call one time after the zones of a frame. Restores the state that the zone passes changed. */
    fun end() {
        zone = null
        mask = null
        shoreMap = null
        if (!stateSet) return
        stateSet = false
        val main = Minecraft.getInstance().mainRenderTarget
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
        RenderSystem.enableCull()
        // Core shaders apply an opaque BlendMode, which turns blending off when the last mode was not
        // opaque; leaving this effect's mode last makes the next opaque draw blend.
        OPAQUE_BLEND.apply()
        RenderSystem.defaultBlendFunc()
        main.bindWrite(false)
        GlStateManager._viewport(0, 0, main.width, main.height)
    }

    // R = height over the height range, G = 1 where the mesh has a fragment. With linear
    // filtering, R / G is the height at a point that is part mesh and part empty.
    private fun ensureHeightTarget() {
        if (heightFbo != -1) return
        heightTex = GlStateManager._genTexture()
        GlStateManager._bindTexture(heightTex)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F, HEIGHT_MAP_SIZE, HEIGHT_MAP_SIZE, 0,
            GL30.GL_RG, GL11.GL_FLOAT, null as ByteBuffer?,
        )
        heightDepthTex = GlStateManager._genTexture()
        GlStateManager._bindTexture(heightDepthTex)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, HEIGHT_MAP_SIZE, HEIGHT_MAP_SIZE, 0,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null as ByteBuffer?,
        )
        heightFbo = GlStateManager.glGenFramebuffers()
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, heightFbo)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, heightTex, 0)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, heightDepthTex, 0)
    }

    private fun load(): EffectInstance? = try {
        EffectInstance(Minecraft.getInstance().resourceManager, "lattice_ocean_volume").also {
            it.setSampler("DepthSampler", IntSupplier { Minecraft.getInstance().mainRenderTarget.depthTextureId })
            it.setSampler("HeightMap", IntSupplier { heightTex })
            it.setSampler("ShoreMap", IntSupplier { shoreMap?.textureId ?: -1 })
            it.setSampler("HullMask", IntSupplier { mask?.textureId ?: -1 })
        }
    } catch (t: Throwable) {
        LOG.warn("Failed to load the lattice ocean volume shader program", t)
        null
    }
}
