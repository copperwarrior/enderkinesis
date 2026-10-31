package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexBuffer
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod

/** The aurora band across the Wohlon sky, drawn in the sky pass after the stars. Render thread only. */
object WohlonSkyBand {

    // Seconds; the shader Time runs up and back down inside this, as a wrap to zero would jump.
    private const val TIME_PERIOD_SEC = 3000.0

    /** Tilt of the band crest from the zenith toward the south-east. */
    private const val TILT_DEG = 30.0

    // MC axes: +X east, +Z south. The band runs from the NE horizon to the SW horizon.
    private val AXIS_A = Vector3f(1f, 0f, -1f).normalize()
    private val AXIS_B: Vector3f = run {
        val tilt = Math.toRadians(TILT_DEG).toFloat()
        val southEast = Vector3f(1f, 0f, 1f).normalize()
        Vector3f(0f, 1f, 0f).mul(Mth.cos(tilt)).add(southEast.mul(Mth.sin(tilt))).normalize()
    }
    private val NORMAL: Vector3f = Vector3f(AXIS_A).cross(AXIS_B).normalize()

    private var shader: ShaderInstance? = null
    private val OPAQUE_BLEND = BlendMode()
    private val modelView = Matrix4f()

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_wohlon_sky_band",
                    DefaultVertexFormat.POSITION,
                ),
            ) { instance -> shader = instance }
        }
    }

    @JvmStatic
    fun render(poseStack: PoseStack, level: ClientLevel, partialTick: Float) {
        val sh = shader ?: return
        // Same cross-fade as the stars: the Wohlon sky elements fade in over blend 0.5 → 1.
        val blend = WohlonBiomeSkyState.currentBlend
        val strength = (2f * blend - 1f).coerceAtMost(1f) * (1f - level.getRainLevel(partialTick))
        if (strength <= 0f) return

        val phase = (System.nanoTime() * 1e-9) % (2.0 * TIME_PERIOD_SEC)
        val time = if (phase <= TIME_PERIOD_SEC) phase else 2.0 * TIME_PERIOD_SEC - phase
        sh.safeGetUniform("Time").set(time.toFloat())
        sh.safeGetUniform("Strength").set(strength)
        sh.safeGetUniform("BandAxisA").set(AXIS_A.x, AXIS_A.y, AXIS_A.z)
        sh.safeGetUniform("BandAxisB").set(AXIS_B.x, AXIS_B.y, AXIS_B.z)
        sh.safeGetUniform("BandNormal").set(NORMAL.x, NORMAL.y, NORMAL.z)

        RenderSystem.enableBlend()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        val mesh = SkySphereMesh.get()
        modelView.set(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose())
        mesh.bind()
        mesh.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), sh)
        VertexBuffer.unbind()
        // The shader leaves its additive mode as BlendMode.lastApplied; see VolumeComposite.restore.
        OPAQUE_BLEND.apply()
        RenderSystem.disableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
    }
}
