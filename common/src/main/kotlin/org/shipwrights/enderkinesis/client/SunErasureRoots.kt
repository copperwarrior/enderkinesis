package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexBuffer
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.ritual.SunErasure

/** The roots that wind across the Overworld sky to the erased sun. Render thread only. */
object SunErasureRoots {

    private val ROOT_SPRITE = ResourceLocation("block/mangrove_roots_side")
    // The sky goes dark with the erasure; the roots stay just visible against it.
    private const val MIN_BRIGHTNESS = 0.35f
    // Seconds; the shader Time runs up and back down inside this, as a wrap to zero would jump.
    private const val TIME_PERIOD_SEC = 3000.0

    private var shader: ShaderInstance? = null
    private val OPAQUE_BLEND = BlendMode()
    private val modelView = Matrix4f()

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_sun_erasure_roots",
                    DefaultVertexFormat.POSITION,
                ),
            ) { instance -> shader = instance }
        }
    }

    @JvmStatic
    fun render(poseStack: PoseStack, level: ClientLevel, partialTick: Float) {
        if (level.dimension() != Level.OVERWORLD) return
        val sh = shader ?: return
        val time = level.dayTime + partialTick.toDouble()
        val presence = SunErasure.rootPresence(time)
        if (presence <= 0f) return

        // Vanilla turns the sun by -90 about Y, then by timeOfDay about X.
        val angle = level.getTimeOfDay(partialTick) * Mth.TWO_PI
        sh.safeGetUniform("SunDir").set(-Mth.sin(angle), Mth.cos(angle), 0f)
        sh.safeGetUniform("Growth").set(SunErasure.rootGrowth(time))
        sh.safeGetUniform("Presence").set(presence)
        val phase = (System.nanoTime() * 1e-9) % (2.0 * TIME_PERIOD_SEC)
        sh.safeGetUniform("Time").set((if (phase <= TIME_PERIOD_SEC) phase else 2.0 * TIME_PERIOD_SEC - phase).toFloat())
        sh.safeGetUniform("Brightness").set(level.getSkyDarken(partialTick).coerceAtLeast(MIN_BRIGHTNESS))
        val sprite = Minecraft.getInstance().getTextureAtlas(BLOCK_ATLAS).apply(ROOT_SPRITE)
        sh.safeGetUniform("SpriteUV").set(sprite.u0, sprite.v0, sprite.u1, sprite.v1)
        RenderSystem.setShaderTexture(0, BLOCK_ATLAS)

        RenderSystem.enableBlend()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        val mesh = SkySphereMesh.get()
        modelView.set(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose())
        mesh.bind()
        mesh.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), sh)
        VertexBuffer.unbind()
        // The shader leaves its blend as BlendMode.lastApplied; see VolumeComposite.restore.
        OPAQUE_BLEND.apply()
        RenderSystem.disableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
    }
}
