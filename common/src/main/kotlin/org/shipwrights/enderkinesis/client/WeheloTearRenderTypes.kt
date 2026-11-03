package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.lwjgl.opengl.GL14
import java.util.function.IntSupplier

// Both read the scene depth and do their own depth test, so they fade where a surface is just behind. Draw after [isReady].
object WeheloTearRenderTypes {

    private val LOG = org.slf4j.LoggerFactory.getLogger("EnderkinesisWeheloTear")

    private var spaceShader: ShaderInstance? = null
    private var glowShader: ShaderInstance? = null

    /** Vertex rgb tints the space; vertex alpha is how much space shows. */
    val SPACE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_wehelo_tear_space",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        2048,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { spaceShader?.also(::bindDepth) })
            // As the vanilla end portal: nebula in slot 0, star layers in slot 1.
            .setTextureState(
                RenderStateShard.MultiTextureStateShard.builder()
                    .add(ResourceLocation("textures/environment/end_sky.png"), false, false)
                    .add(ResourceLocation("textures/entity/end_portal.png"), false, false)
                    .build()
            )
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )

    // MAX, not add: where a crack meets a lip, or the loop folds at a tip, the strips merge instead of piling up light.
    private val MAX_GLOW = RenderStateShard.TransparencyStateShard(
        "${EnderkinesisMod.MOD_ID}_max_glow",
        {
            RenderSystem.enableBlend()
            RenderSystem.blendEquation(GL14.GL_MAX)
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE)
        },
        {
            // Vanilla never sets the equation, so every later blend would stay MAX.
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD)
            RenderSystem.disableBlend()
            RenderSystem.defaultBlendFunc()
        },
    )

    // Render thread only. Colour only: the glow shader does its own depth test against the main depth.
    private var glowTarget: TextureTarget? = null
    private var composite: EffectInstance? = null
    private var compositeFailed = false

    private val GLOW_TARGET_OUTPUT = RenderStateShard.OutputStateShard(
        "${EnderkinesisMod.MOD_ID}_wehelo_glow_target",
        { glowTarget?.bindWrite(false) },
        { Minecraft.getInstance().mainRenderTarget.bindWrite(false) },
    )

    /** Straight onto the main target: the fallback when the strips cannot go through [GLOW_OFFSCREEN]. */
    val GLOW: RenderType = glowType("glow", RenderStateShard.MAIN_TARGET)

    /** Into the glow target, where MAX merges the strips; [compositeGlow] then adds them onto the scene. */
    val GLOW_OFFSCREEN: RenderType = glowType("glow_offscreen", GLOW_TARGET_OUTPUT)

    private fun glowType(name: String, output: RenderStateShard.OutputStateShard): RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_wehelo_tear_$name",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        2048,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { glowShader?.also(::bindDepth) })
            .setTransparencyState(MAX_GLOW)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(output)
            .createCompositeState(false),
    )

    /** Sizes and clears the glow target. False when the composite program is not usable. */
    fun beginOffscreenGlow(): Boolean {
        val mc = Minecraft.getInstance()
        if (loadComposite(mc) == null) return false
        val main = mc.mainRenderTarget
        val target = glowTarget ?: TextureTarget(main.width, main.height, false, Minecraft.ON_OSX).also { glowTarget = it }
        if (target.width != main.width || target.height != main.height) target.resize(main.width, main.height, Minecraft.ON_OSX)
        target.setClearColor(0f, 0f, 0f, 0f)
        target.clear(Minecraft.ON_OSX)
        main.bindWrite(false)
        return true
    }

    /** Adds the glow target onto the main target. Call after the [GLOW_OFFSCREEN] batch has ended. */
    fun compositeGlow() {
        val mc = Minecraft.getInstance()
        val effect = composite ?: return
        val target = glowTarget ?: return
        val main = mc.mainRenderTarget
        main.bindWrite(false)
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        effect.setSampler("DiffuseSampler", IntSupplier { target.colorTextureId })
        // The glow core shader leaves its max blend as BlendMode.lastApplied. Both are non-opaque, so without
        // this reset BlendMode.apply skips enableBlend and the composite paints over the scene.
        BlendMode().apply()
        RefractionPass.drawQuad(effect, main.width, main.height) {}
        // The program's add blend stays BlendMode.lastApplied; hand the cache back opaque, or the next core shader drops blending.
        BlendMode().apply()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
    }

    private fun loadComposite(mc: Minecraft): EffectInstance? {
        composite?.let { return it }
        if (compositeFailed) return null
        return try {
            EffectInstance(mc.resourceManager, "wehelo_tear_glow_composite").also { composite = it }
        } catch (t: Throwable) {
            compositeFailed = true
            LOG.warn("Failed to load the Wehelo tear glow composite; rims draw straight onto the scene", t)
            null
        }
    }

    fun close() {
        composite?.close()
        composite = null
        compositeFailed = false
        glowTarget?.destroyBuffers()
        glowTarget = null
    }

    fun isReady(): Boolean = spaceShader != null && glowShader != null

    private fun bindDepth(shader: ShaderInstance) =
        shader.setSampler("DepthSampler", Minecraft.getInstance().mainRenderTarget.depthTextureId)

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID), "rendertype_wehelo_tear_space", DefaultVertexFormat.POSITION_COLOR)
            ) { instance -> spaceShader = instance }
            sink.registerShader(
                ShaderInstance(namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID), "rendertype_wehelo_tear_glow", DefaultVertexFormat.POSITION_COLOR)
            ) { instance -> glowShader = instance }
        }
    }
}
