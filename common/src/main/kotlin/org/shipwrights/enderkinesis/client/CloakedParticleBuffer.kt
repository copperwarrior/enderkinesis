package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.logging.LogUtils
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LightTexture
import org.lwjgl.opengl.GL13

/** Holds cloaked particles between the ParticleEngine capture pass and the
 *  side-FB flush.
 *
 *  WHY this exists separate from CloakBufferSource (which handles entities):
 *  - Entities write WORLD-SPACE vertices through a VertexConsumer at the time
 *    the entity's pose is applied. The vertex data lives in the BufferBuilder
 *    in world space; drawing at TAIL with any ModelViewMatrix that contains
 *    the camera transform gets the right screen positions.
 *  - Particles write CAMERA-RELATIVE vertices via [Particle.render]; the
 *    vertex shader needs ModelViewMatrix == camera-view to land them on
 *    screen. By renderLevel TAIL, ModelViewMatrix has been mutated several
 *    times by intervening passes (item entity, hand, etc.). We MUST replicate
 *    vanilla's matrix preamble exactly: push, multiply in the passed
 *    poseStack's last pose, applyModelViewMatrix. Without this, particles
 *    transform to nonsense positions and don't appear in the side FB.
 *
 *  Mirror vanilla ParticleEngine.render (1.20.1) preamble/epilogue line for
 *  line — see [flushToShipFB]. */
object CloakedParticleBuffer {

    private val LOG = LogUtils.getLogger()

    private val captured: MutableMap<ParticleRenderType, MutableList<Particle>> = HashMap()
    private val builder: BufferBuilder = BufferBuilder(256 * 1024)

    @JvmStatic
    fun capture(particle: Particle) {
        captured.computeIfAbsent(particle.renderType) { ArrayList() }.add(particle)
    }

    @JvmStatic
    fun hasCaptured(): Boolean = captured.isNotEmpty()

    /** Replicates vanilla [net.minecraft.client.particle.ParticleEngine.render]
     *  preamble/epilogue exactly, with the side FB bound during the draws.
     *
     *  [poseStack] must be the world-space pose stack from renderLevel TAIL —
     *  same one vanilla's ParticleEngine.render receives as its first arg.
     *  Its `.last().pose()` is multiplied into the GL ModelViewMatrix stack
     *  for the duration of the flush, then popped. */
    @JvmStatic
    fun flushToShipFB(poseStack: PoseStack, lightTexture: LightTexture, camera: Camera, partialTick: Float) {
        if (captured.isEmpty()) return
        val mc = Minecraft.getInstance()

        lightTexture.turnOnLightLayer()
        RenderSystem.enableDepthTest()
        RenderSystem.activeTexture(GL13.GL_TEXTURE2)
        RenderSystem.activeTexture(GL13.GL_TEXTURE0)
        val mvStack: PoseStack = RenderSystem.getModelViewStack()
        mvStack.pushPose()
        mvStack.mulPoseMatrix(poseStack.last().pose())
        RenderSystem.applyModelViewMatrix()
        try {
            ConcealmentShipFB.bindForDraw()
            try {
                for ((type, list) in captured) {
                    if (type === ParticleRenderType.NO_RENDER || list.isEmpty()) continue
                    RenderSystem.setShader(GameRenderer::getParticleShader)
                    type.begin(builder, mc.textureManager)
                    for (p in list) {
                        try {
                            p.render(builder, camera, partialTick)
                        } catch (t: Throwable) {
                            LOG.warn("[enderkinesis cloak] particle render failed", t)
                        }
                    }
                    val rendered = builder.end() ?: continue
                    BufferUploader.drawWithShader(rendered)
                }
            } finally {
                mc.mainRenderTarget.bindWrite(true)
            }
        } finally {
            mvStack.popPose()
            RenderSystem.applyModelViewMatrix()
            RenderSystem.depthMask(true)
            RenderSystem.disableBlend()
            lightTexture.turnOffLightLayer()
            captured.clear()
        }
    }
}
