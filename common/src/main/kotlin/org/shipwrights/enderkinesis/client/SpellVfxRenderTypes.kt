package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Render types for spell visuals. Light is drawn additively with
 *  [AegisRenderType.FACE_GLOW]; dark volumes (Gravity, Void) need ordinary blending, which
 *  [SHADE] gives: position-colour, translucent, depth-tested, no depth write, both faces.
 *
 *  Textured geometry (ice shards, obsidian cubes, flashes) uses vanilla textures through the
 *  `rendertype_spell_sprite` shader, which lights them by the world as particles are lit:
 *  [sprite] for translucent faces, [solid] for opaque ones (which write depth, so a cube's
 *  far side never shows through), [glow] for additive light. Each is made once per texture.
 *  Draw textured geometry only once [isReady]. */
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

    /** A Sunfire beam's shells of flowing noise light, additive (`rendertype_spell_sunfire`).
     *  Draw only once [isSunfireReady]. */
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

    /** Sunfire's Halo: a sphere of shimmering light, bright at its rim, additive
     *  (`rendertype_spell_aura`). Draw only once [isAuraReady]. */
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

    private var cloakShader: ShaderInstance? = null
    private var cloakDarkShader: ShaderInstance? = null

    /** Whose cloak [CLOAK] or [CLOAK_DARK] draws next: its wearer's feet, from the camera. Set
     *  it, emit that wearer's cylinder, then end the batch. */
    var cloakFeet: Vec3 = Vec3.ZERO

    /** The elements' cloaks of light (flame, frost, lightning, poison): a ring round its wearer,
     *  additive (`rendertype_spell_cloak`). Draw only once [isCloakReady]. */
    val CLOAK: RenderType = cloakType("cloak", RenderStateShard.LIGHTNING_TRANSPARENCY) { cloakShader }

    /** The dark cloaks (blood, void): the same ring, drawn over what's behind it
     *  (`rendertype_spell_cloak_dark`). Draw only once [isCloakReady]. */
    val CLOAK_DARK: RenderType = cloakType("cloak_dark", RenderStateShard.TRANSLUCENT_TRANSPARENCY) { cloakDarkShader }

    fun isCloakReady(): Boolean = cloakShader != null && cloakDarkShader != null

    private var blackSunShader: ShaderInstance? = null

    /** Whose Black Hole Sun [BLACK_SUN] draws next: its middle, from the camera. Set it, emit its
     *  cube, then end the batch. */
    var blackSunCentre: Vec3 = Vec3.ZERO

    /** Black Hole Sun: a black sphere in a disk of churning fire with a halo round it, marched
     *  through per pixel as the cloaks are, the world behind it pulled a little toward it from a
     *  copy of the screen ([SceneCopy]) (`rendertype_spell_black_sun`). Draw only once
     *  [isBlackSunReady]. */
    val BLACK_SUN: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_spell_black_sun",
        DefaultVertexFormat.POSITION_TEX_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard {
                blackSunShader?.also {
                    setViewToWorld(it)
                    setTicks(it)
                    it.safeGetUniform("SunCentre").set(blackSunCentre.x.toFloat(), blackSunCentre.y.toFloat(), blackSunCentre.z.toFloat())
                    // The march stops at the scene, so it reads the depth drawn so far; and it shows
                    // the world behind it pulled toward it, from a copy of the screen drawn so far.
                    it.setSampler("DepthSampler", Minecraft.getInstance().mainRenderTarget.depthTextureId)
                    it.setSampler("SceneSampler", SceneCopy.copy())
                }
            })
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            // The shader stops at the scene's depth itself, as the cloaks do.
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )

    fun isBlackSunReady(): Boolean = blackSunShader != null

    private fun cloakType(name: String, transparency: RenderStateShard.TransparencyStateShard, shader: () -> ShaderInstance?): RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_spell_$name",
        DefaultVertexFormat.POSITION_TEX_COLOR,
        VertexFormat.Mode.QUADS,
        16384,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard {
                shader()?.also {
                    setViewToWorld(it)
                    setTicks(it)
                    it.safeGetUniform("CloakFeet").set(cloakFeet.x.toFloat(), cloakFeet.y.toFloat(), cloakFeet.z.toFloat())
                    // The march stops at the scene, so it reads the depth drawn so far.
                    it.setSampler("DepthSampler", Minecraft.getInstance().mainRenderTarget.depthTextureId)
                }
            })
            .setTransparencyState(transparency)
            // The shader stops at the scene's depth itself, as the gas field does.
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )

    private var gasFieldShader: ShaderInstance? = null

    /** What [GAS_FIELD] draws next: the gas volume's least corner (camera-relative), its size in
     *  blocks, how its layers are tiled in the texture [textureId] ([texSize] square), the
     *  camera's world position, and how far from the gas last built's to what's built now to
     *  show it ([blend]). Set it, emit the volume's box, then end the batch. */
    class GasFieldDraw(
        val origin: Vec3, val w: Int, val h: Int, val d: Int, val cols: Int, val texSize: Int, val textureId: Int, val camera: Vec3,
        val blend: Float,
    )
    var gasField: GasFieldDraw? = null

    /** Poison's gas field: a volume marched through per pixel (`rendertype_spell_gas_field`),
     *  see [GasFieldClient]. Draw only once [isGasFieldReady]. */
    val GAS_FIELD: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_spell_gas_field",
        DefaultVertexFormat.POSITION,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard {
                gasFieldShader?.also { shader ->
                    setViewToWorld(shader)
                    setTicks(shader)
                    // The march stops at the scene, so it reads the depth drawn so far (water's
                    // included: this is drawn after translucent blocks).
                    shader.setSampler("DepthSampler", Minecraft.getInstance().mainRenderTarget.depthTextureId)
                    gasField?.let { f ->
                        shader.setSampler("FieldSampler", f.textureId)
                        shader.safeGetUniform("FieldBlend").set(f.blend)
                        shader.safeGetUniform("FieldOrigin").set(f.origin.x.toFloat(), f.origin.y.toFloat(), f.origin.z.toFloat())
                        shader.safeGetUniform("FieldSize").set(f.w.toFloat(), f.h.toFloat(), f.d.toFloat())
                        shader.safeGetUniform("FieldCols").set(f.cols.toFloat())
                        shader.safeGetUniform("FieldTexSize").set(f.texSize.toFloat())
                        shader.safeGetUniform("CameraPos").set(f.camera.x.toFloat(), f.camera.y.toFloat(), f.camera.z.toFloat())
                    }
                }
            })
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            // The shader stops at the scene's depth itself; only the faces the camera looks out
            // through are drawn, so each pixel marches once.
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setCullState(RenderStateShard.NO_CULL)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )

    fun isGasFieldReady(): Boolean = gasFieldShader != null

    /** Vertices reach these shaders turned to the camera; this turns them back to world axes.
     *  As the Orb of Potential works it out: `Camera.rotation()` lacks the half turn of yaw that
     *  `LevelRenderer.renderLevel` applies. */
    private fun setViewToWorld(shader: ShaderInstance) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val yaw = Math.toRadians(-camera.yRot.toDouble() - 180.0).toFloat()
        val pitch = Math.toRadians(-camera.xRot.toDouble()).toFloat()
        shader.safeGetUniform("ViewToWorld").set(Matrix3f().rotationY(yaw).rotateX(pitch))
    }

    /** The time these shaders flow by ([SpellClock]): vanilla's GameTime wraps once a day, and
     *  the noise would jump there. */
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

    /** Translucent faces whose texture is recoloured by its brightness, so they take the vertex
     *  colour: white amethyst. Draw only once [isGrayReady]. */
    fun gray(texture: ResourceLocation): RenderType = grays.getOrPut(texture) {
        textured("gray", texture, RenderStateShard.TRANSLUCENT_TRANSPARENCY, RenderStateShard.COLOR_WRITE, sort = true) { grayShader }
    }

    fun isGrayReady(): Boolean = grayShader != null

    private var softGlowShader: ShaderInstance? = null
    private val softGlows = HashMap<ResourceLocation, RenderType>()

    /** Additive light that fades out where the scene is just behind it, rather than being cut
     *  off where it passes into it (`rendertype_spell_soft_glow`): it reads the depth drawn so
     *  far, and does its own depth test with it. Draw only once [isSoftGlowReady], after
     *  whatever it should sink into. */
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

    /** [softGlow]'s dark counterpart: translucent rather than additive, for shadow and smoke that
     *  fade into whatever they touch (`rendertype_spell_soft_shade`, the same shader blended over
     *  the scene). Draw only once [isSoftGlowReady]. */
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

    /** Opaque faces over blocks that come and go texel by texel rather than fading
     *  (`rendertype_spell_dissolve`): the vertex colour's red is a seed for which texels go
     *  first, its alpha how much is there ([dissolveColour]). Draw only once [isDissolveReady]. */
    fun dissolve(texture: ResourceLocation): RenderType = dissolves.getOrPut(texture) {
        textured("dissolve", texture, RenderStateShard.NO_TRANSPARENCY, RenderStateShard.COLOR_DEPTH_WRITE, sort = false) { dissolveShader }
    }

    fun isDissolveReady(): Boolean = dissolveShader != null

    /** The colour to give a [dissolve] face: [seed] picks its pattern, [amount] how much shows. */
    fun dissolveColour(seed: Int): Int = ((seed and 0xFF) shl 16) or 0xFFFF

    /** The grey sprite shader, for drawing white amethyst outside the world (the HUD). */
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

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            // The atlases are rebuilt on a reload too.
            SpellArt.invalidate()
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_sprite",
                    DefaultVertexFormat.PARTICLE,
                )
            ) { instance -> spriteShader = instance }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_sprite_gray",
                    DefaultVertexFormat.PARTICLE,
                )
            ) { instance -> grayShader = instance }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_soft_glow",
                    DefaultVertexFormat.PARTICLE,
                )
            ) { instance -> softGlowShader = instance }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_soft_shade",
                    DefaultVertexFormat.PARTICLE,
                )
            ) { instance -> softShadeShader = instance }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_sunfire",
                    DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
                )
            ) { instance -> sunfireShader = instance }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_aura",
                    DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
                )
            ) { instance -> auraShader = instance }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_cloak",
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                )
            ) { instance -> cloakShader = instance }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_cloak_dark",
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                )
            ) { instance -> cloakDarkShader = instance }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_black_sun",
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                )
            ) { instance -> blackSunShader = instance }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_gas_field",
                    DefaultVertexFormat.POSITION,
                )
            ) { instance -> gasFieldShader = instance }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_glint",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                )
            ) { instance -> SpellGlint.registerShader(instance) }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_death",
                    DefaultVertexFormat.NEW_ENTITY,
                )
            ) { instance -> DeathStyles.registerShader(instance) }
            sink.registerShader(
                ShaderInstance(
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_spell_dissolve",
                    DefaultVertexFormat.PARTICLE,
                )
            ) { instance -> dissolveShader = instance }
        }
    }
}
