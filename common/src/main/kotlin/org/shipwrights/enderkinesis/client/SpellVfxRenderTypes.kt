package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix3f
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Dark volumes need normal blending, so [SHADE] is not additive as [AegisRenderType.FACE_GLOW] is.
 *  Draw textured types only after [isReady]. */
object SpellVfxRenderTypes {
    val SHADE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_spell_shade",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        1024,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )

    private var spriteShader: ShaderInstance? = null
    private var grayShader: ShaderInstance? = null
    private var sunfireShader: ShaderInstance? = null

    /** Draw only after [isSunfireReady]. */
    val SUNFIRE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_spell_sunfire",
        DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
        VertexFormat.Mode.QUADS,
        4096,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { sunfireShader?.also { setViewToWorld(it); setTicks(it) } })
            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )

    fun isSunfireReady(): Boolean = sunfireShader != null

    private var auraShader: ShaderInstance? = null

    /** Draw only after [isAuraReady]. */
    val AURA: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_spell_aura",
        DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
        VertexFormat.Mode.QUADS,
        8192,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { auraShader?.also { setViewToWorld(it); setTicks(it) } })
            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )

    fun isAuraReady(): Boolean = auraShader != null

    /** `Camera.rotation()` does not have the half turn of yaw that `LevelRenderer.renderLevel` applies.
     *  The Orb of Potential uses the same fix. */
    private fun setViewToWorld(shader: ShaderInstance) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val yaw = Math.toRadians(-camera.yRot.toDouble() - 180.0).toFloat()
        val pitch = Math.toRadians(-camera.xRot.toDouble()).toFloat()
        shader.safeGetUniform("ViewToWorld").set(Matrix3f().rotationY(yaw).rotateX(pitch))
    }

    /** Vanilla GameTime wraps once each day, and the noise would jump there. */
    private fun setTicks(shader: ShaderInstance) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        shader.safeGetUniform("SpellTicks").set(SpellClock.time(level, mc.frameTime))
    }

    private val sprites = HashMap<ResourceLocation, RenderType>()
    private val solids = HashMap<ResourceLocation, RenderType>()
    private val glows = HashMap<ResourceLocation, RenderType>()
    private val grays = HashMap<ResourceLocation, RenderType>()
    private val dissolves = HashMap<ResourceLocation, RenderType>()
    private var dissolveShader: ShaderInstance? = null

    fun isReady(): Boolean = spriteShader != null

    fun sprite(texture: ResourceLocation): RenderType = sprites.getOrPut(texture) {
        textured("sprite", texture, RenderStateShard.TRANSLUCENT_TRANSPARENCY, RenderStateShard.COLOR_WRITE, sort = true)
    }

    fun solid(texture: ResourceLocation): RenderType = solids.getOrPut(texture) {
        textured("solid", texture, RenderStateShard.NO_TRANSPARENCY, RenderStateShard.COLOR_DEPTH_WRITE, sort = false)
    }

    fun glow(texture: ResourceLocation): RenderType = glows.getOrPut(texture) {
        textured("glow", texture, RenderStateShard.LIGHTNING_TRANSPARENCY, RenderStateShard.COLOR_WRITE, sort = false)
    }

    /** Recolours the texture by its brightness, so the vertex colour shows. Draw only after [isGrayReady]. */
    fun gray(texture: ResourceLocation): RenderType = grays.getOrPut(texture) {
        textured("gray", texture, RenderStateShard.TRANSLUCENT_TRANSPARENCY, RenderStateShard.COLOR_WRITE, sort = true) { grayShader }
    }

    fun isGrayReady(): Boolean = grayShader != null

    private var softGlowShader: ShaderInstance? = null
    private val softGlows = HashMap<ResourceLocation, RenderType>()

    /** Reads the scene depth and does its own depth test, so it fades out near the scene.
     *  Draw it after [isSoftGlowReady] and after the geometry it sinks into. */
    fun softGlow(texture: ResourceLocation): RenderType = softGlows.getOrPut(texture) {
        RenderType.create(
            "${EnderkinesisMod.MOD_ID}_spell_soft_glow_${texture.path}",
            DefaultVertexFormat.PARTICLE,
            VertexFormat.Mode.QUADS,
            2048,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.ShaderStateShard {
                    softGlowShader?.also { it.setSampler("DepthSampler", Minecraft.getInstance().mainRenderTarget.depthTextureId) }
                })
                .setTextureState(RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setCullState(RenderStateShard.NO_CULL)
                .setOutputState(RenderStateShard.MAIN_TARGET)
                .createCompositeState(false),
        )
    }

    fun isSoftGlowReady(): Boolean = softGlowShader != null && softShadeShader != null

    private var softShadeShader: ShaderInstance? = null
    private val softShades = HashMap<ResourceLocation, RenderType>()

    /** The translucent variant of [softGlow], for shadow and smoke. Draw only after [isSoftGlowReady]. */
    fun softShade(texture: ResourceLocation): RenderType = softShades.getOrPut(texture) {
        RenderType.create(
            "${EnderkinesisMod.MOD_ID}_spell_soft_shade_${texture.path}",
            DefaultVertexFormat.PARTICLE,
            VertexFormat.Mode.QUADS,
            2048,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.ShaderStateShard {
                    softShadeShader?.also { it.setSampler("DepthSampler", Minecraft.getInstance().mainRenderTarget.depthTextureId) }
                })
                .setTextureState(RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setCullState(RenderStateShard.NO_CULL)
                .setOutputState(RenderStateShard.MAIN_TARGET)
                .createCompositeState(false),
        )
    }

    /** Vertex red is the seed for the texel order, and alpha is how much shows ([dissolveColour]).
     *  Draw only after [isDissolveReady]. */
    fun dissolve(texture: ResourceLocation): RenderType = dissolves.getOrPut(texture) {
        textured("dissolve", texture, RenderStateShard.NO_TRANSPARENCY, RenderStateShard.COLOR_DEPTH_WRITE, sort = false) { dissolveShader }
    }

    fun isDissolveReady(): Boolean = dissolveShader != null

    fun dissolveColour(seed: Int): Int = ((seed and 0xFF) shl 16) or 0xFFFF

    /** For the HUD, outside the world render. */
    fun grayShader(): ShaderInstance? = grayShader

    private fun textured(
        kind: String, texture: ResourceLocation, transparency: RenderStateShard.TransparencyStateShard,
        writeMask: RenderStateShard.WriteMaskStateShard, sort: Boolean, shader: () -> ShaderInstance? = { spriteShader },
    ): RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_spell_${kind}_${texture.path}",
        DefaultVertexFormat.PARTICLE,
        VertexFormat.Mode.QUADS,
        2048,
        false,
        sort,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { shader() })
            .setTextureState(RenderStateShard.TextureStateShard(texture, false, false))
            .setTransparencyState(transparency)
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setWriteMaskState(writeMask)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )

    @Suppress("LongMethod") // Shader registration list: one block for each shader.
    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            // A reload also rebuilds the atlases.
            SpellArt.invalidate()
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_sprite",
                    DefaultVertexFormat.PARTICLE,
                )
            ) { instance -> spriteShader = instance }
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_sprite_gray",
                    DefaultVertexFormat.PARTICLE,
                )
            ) { instance -> grayShader = instance }
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_soft_glow",
                    DefaultVertexFormat.PARTICLE,
                )
            ) { instance -> softGlowShader = instance }
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_soft_shade",
                    DefaultVertexFormat.PARTICLE,
                )
            ) { instance -> softShadeShader = instance }
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_sunfire",
                    DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
                )
            ) { instance -> sunfireShader = instance }
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_aura",
                    DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
                )
            ) { instance -> auraShader = instance }
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_glint",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                )
            ) { instance -> SpellGlint.registerShader(instance) }
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_death",
                    DefaultVertexFormat.NEW_ENTITY,
                )
            ) { instance -> DeathStyles.registerShader(instance) }
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_dissolve",
                    DefaultVertexFormat.PARTICLE,
                )
            ) { instance -> dissolveShader = instance }
        }
    }
}
