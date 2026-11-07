package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.event.events.client.ClientReloadShadersEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import org.joml.Matrix4f
import org.joml.Quaternionf
import kotlin.math.cos
import kotlin.math.sin
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.Sureibjin
import org.shipwrights.enderkinesis.item.MundaneIncubation
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL14
import java.util.function.Supplier

// A starburst billboard at the chest of the inventory model: white core, tinted bloom, many tapered rays.
object IncubationGlow {
    private const val CHEST = 0.62f
    // The burst is inside the chest on the body axis. For the depth fade it sits the torso half-depth plus the fade in
    // front of the axis, so it shines at full strength through the torso and fades out over a nearer arm.
    private const val TORSO_HALF_DEPTH = 2f / 16f
    // In blocks: the burst goes from none to full over this distance in front of the model surface.
    private const val FADE_DISTANCE = 1.5f / 16f
    private const val DEPTH_LIFT = TORSO_HALF_DEPTH + FADE_DISTANCE
    private const val ENTITY_Z = 50f
    private const val BODY_YAW_OFFSET = 180f

    // Sizes in blocks at full progress. The model is 1.8 blocks tall.
    private const val CORE_RADIUS = 0.1f
    private const val BLOOM_RADIUS = 0.4f
    // The torso is 0.25 to each side of the axis and the arms reach 0.5, so most rays pass the body outline.
    private const val MIN_RAY_LENGTH = 0.35f
    private const val MAX_RAY_LENGTH = 0.8f
    // Long spikes, spread evenly around the circle, so none stands alone.
    private const val SPIKES = 6
    private const val MIN_SPIKE_LENGTH = 1.1f
    private const val MAX_SPIKE_LENGTH = 1.4f
    // Of the even spacing, how far a spike may turn from its place.
    private const val SPIKE_JITTER = 0.25f
    // Half-widths at the base. Below about a pixel a ray goes dim and broken.
    private const val RAY_WIDTH = 0.025f
    private const val SPIKE_WIDTH = 0.035f
    private const val MIN_RAYS = 24
    private const val MAX_RAYS = 48
    // The burst at no progress is this part of its full size.
    private const val START_GROWTH = 0.35f

    private const val CORE_ALPHA = 1f
    private const val BLOOM_ALPHA = 0.55f
    private const val RAY_ALPHA = 0.8f
    private const val SPIKE_ALPHA = 1f
    // How far the core and the ray bases go from the tint toward white.
    private const val CORE_WHITE = 0.85f
    private const val RAY_WHITE = 0.5f
    private const val TWINKLE = 0.25f
    private const val TWINKLE_PER_SECOND = 3f
    private const val TURN_DEGREES_PER_SECOND = 2f

    private const val SEGMENTS = 24
    private val COS = FloatArray(SEGMENTS + 1) { cos(it * Mth.TWO_PI / SEGMENTS) }
    private val SIN = FloatArray(SEGMENTS + 1) { sin(it * Mth.TWO_PI / SEGMENTS) }

    private val OPAQUE = BlendMode()
    private val SHADER = Supplier<ShaderInstance?> { burstShader }

    // Render thread only. Null until the shader loads, and after a failed reload.
    private var burstShader: ShaderInstance? = null
    // Render thread only: our copy of the scene depth, made once and sized again with the window.
    private var depthCopy = -1
    private var depthCopyBox: Any = -1
    private var copyWidth = 0
    private var copyHeight = 0

    // Render thread only: scratch matrix.
    private val chest = Matrix4f()

    // Render thread only. Below 0 when no stone incubates.
    private var progress = -1f
    private var colour = 0

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_incubation_burst",
                    DefaultVertexFormat.POSITION_COLOR,
                ),
            ) { instance -> burstShader = instance }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, MundaneIncubation.S2C_STATE) { buf, _ ->
            val p = buf.readFloat()
            val c = buf.readInt()
            Minecraft.getInstance().execute {
                progress = p
                colour = c
                MundaneIncubation.clientIncubating = p >= 0f
            }
        }
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
            progress = -1f
            MundaneIncubation.clientIncubating = false
        }
    }

    @JvmStatic
    fun render(graphics: GuiGraphics, x: Int, y: Int, scale: Int, pose: Quaternionf, entity: LivingEntity) {
        if (progress < 0f || entity !== Minecraft.getInstance().player) return
        // The incubation pauses in the dream; the glow comes back on the wake.
        if (entity.level().dimension() == Sureibjin.LEVEL_KEY) return
        val shader = burstShader ?: return
        // renderEntityInInventory's stack, then LivingEntityRenderer.setupRotations' body yaw; the model front is -Z.
        val s = scale.toFloat()
        chest.set(graphics.pose().last().pose()).translate(x.toFloat(), y.toFloat(), ENTITY_Z).scale(s, s, -s)
            .rotate(pose).rotateY(Mth.DEG_TO_RAD * (BODY_YAW_OFFSET - entity.yBodyRot))
            .translate(0f, entity.bbHeight * CHEST, 0f)
        // The burst faces the screen. GUI +Z is toward the viewer.
        val cx = chest.m30()
        val cy = chest.m31()
        val cz = chest.m32() + s * DEPTH_LIFT
        val size = s * (START_GROWTH + (1f - START_GROWTH) * progress)
        val seconds = (Util.getMillis() % PERIOD_MS) / MS_PER_SECOND
        val turn = Mth.DEG_TO_RAD * TURN_DEGREES_PER_SECOND * seconds

        begin(shader, s * FADE_DISTANCE)
        val buffer = Tesselator.getInstance().builder
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)
        disc(buffer, cx, cy, cz, BLOOM_RADIUS * size, 0f, BLOOM_ALPHA)
        val rays = MIN_RAYS + Math.round((MAX_RAYS - MIN_RAYS) * progress)
        for (i in 0 until rays) {
            val angle = turn + Mth.TWO_PI * (i + hash(i, 1) - 0.5f) / rays
            val length = size * (MIN_RAY_LENGTH + (MAX_RAY_LENGTH - MIN_RAY_LENGTH) * hash(i, 2))
            ray(buffer, cx, cy, cz, angle, length, size * RAY_WIDTH * (0.5f + hash(i, 3)), RAY_ALPHA, seconds, i)
        }
        for (i in 0 until SPIKES) {
            val index = MAX_RAYS + i
            val angle = turn + Mth.TWO_PI * (i + SPIKE_JITTER * (2f * hash(index, 1) - 1f)) / SPIKES
            val length = size * (MIN_SPIKE_LENGTH + (MAX_SPIKE_LENGTH - MIN_SPIKE_LENGTH) * hash(index, 2))
            ray(buffer, cx, cy, cz, angle, length, size * SPIKE_WIDTH, SPIKE_ALPHA, seconds, index)
        }
        disc(buffer, cx, cy, cz, CORE_RADIUS * size, CORE_WHITE, CORE_ALPHA)
        BufferUploader.drawWithShader(buffer.end())
        end()
    }

    // A tapered ray from the centre: [alpha] at the base, nothing at the tip, with its own twinkle.
    @Suppress("LongParameterList") // Flat floats, so the hot path makes no vectors.
    private fun ray(
        buffer: BufferBuilder, cx: Float, cy: Float, cz: Float, angle: Float, length: Float, halfWidth: Float,
        alpha: Float, seconds: Float, index: Int,
    ) {
        val phase = Mth.TWO_PI * (seconds * TWINKLE_PER_SECOND + hash(index, 4))
        val lit = alpha * (1f - TWINKLE * (0.5f + 0.5f * Mth.sin(phase)))
        val dx = Mth.cos(angle)
        val dy = Mth.sin(angle)
        vertex(buffer, cx - dy * halfWidth, cy + dx * halfWidth, cz, RAY_WHITE, lit)
        vertex(buffer, cx + dy * halfWidth, cy - dx * halfWidth, cz, RAY_WHITE, lit)
        vertex(buffer, cx + dx * length, cy + dy * length, cz, 0f, 0f)
    }

    // A round soft spot: [white] and [alpha] at the centre, fading to nothing at [radius].
    @Suppress("LongParameterList") // Flat floats, so the hot path makes no vectors.
    private fun disc(
        buffer: BufferBuilder, cx: Float, cy: Float, cz: Float, radius: Float, white: Float, alpha: Float,
    ) {
        for (i in 0 until SEGMENTS) {
            vertex(buffer, cx, cy, cz, white, alpha)
            vertex(buffer, cx + COS[i] * radius, cy + SIN[i] * radius, cz, 0f, 0f)
            vertex(buffer, cx + COS[i + 1] * radius, cy + SIN[i + 1] * radius, cz, 0f, 0f)
        }
    }

    // The tint, taken [white] of the way to white.
    private fun vertex(buffer: BufferBuilder, x: Float, y: Float, z: Float, white: Float, alpha: Float) {
        val r = (colour shr 16 and 0xFF) / 255f
        val g = (colour shr 8 and 0xFF) / 255f
        val b = (colour and 0xFF) / 255f
        buffer.vertex(x.toDouble(), y.toDouble(), z.toDouble())
            .color(r + (1f - r) * white, g + (1f - g) * white, b + (1f - b) * white, alpha).endVertex()
    }

    // Shader colour, kept to put back after the draw.
    private val oldColour = FloatArray(4)

    // Set all state the burst reads: a pass before the GUI may leave any of it changed.
    private fun begin(shader: ShaderInstance, fade: Float) {
        RenderSystem.getShaderColor().copyInto(oldColour)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.disableCull()
        // A non-opaque BlendMode left by a post effect makes the next core shader turn blending off.
        OPAQUE.apply()
        RenderSystem.enableBlend()
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD)
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
        // The shader reads the model depth and does its own soft depth test, so no GL depth test or write.
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        copyDepth()
        shader.setSampler("DepthSampler", depthCopyBox)
        shader.safeGetUniform("FadeDistance").set(fade)
        RenderSystem.setShader(SHADER)
    }

    // Sampling the depth texture of the bound target is a GL feedback loop: the read can miss the model's depth writes
    // (on AMD/Mesa, compressed depth), which flickers. A copy into a separate texture resolves it.
    private fun copyDepth() {
        val main = Minecraft.getInstance().mainRenderTarget
        if (depthCopy == -1) {
            depthCopy = TextureUtil.generateTextureId()
            depthCopyBox = depthCopy
        }
        GlStateManager._bindTexture(depthCopy)
        if (main.width != copyWidth || main.height != copyHeight) {
            copyWidth = main.width
            copyHeight = main.height
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            GlStateManager._texImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, copyWidth, copyHeight, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null,
            )
        }
        // Reads the depth of the bound read framebuffer, the main target.
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, copyWidth, copyHeight)
    }

    private fun end() {
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
        RenderSystem.enableCull()
        RenderSystem.setShaderColor(oldColour[0], oldColour[1], oldColour[2], oldColour[3])
    }

    // A fixed random number in [0, 1) for each ray and draw, so a ray keeps its place and shape.
    private fun hash(index: Int, draw: Int): Float {
        var h = index * HASH_RAY + draw * HASH_DRAW + HASH_SEED
        h = (h xor (h ushr 16)) * HASH_MIX
        h = h xor (h ushr 13)
        return (h ushr 8) / HASH_RANGE
    }

    // Twinkle and turn both repeat whole within this period, so the wrap does not jump.
    private const val PERIOD_MS = 3_600_000L
    private const val MS_PER_SECOND = 1000f
    private const val HASH_RAY = -0x61c88647
    private const val HASH_DRAW = 0x27d4eb2d
    private const val HASH_SEED = 432
    private const val HASH_MIX = 0x45d9f3b
    private const val HASH_RANGE = 16_777_216f
}
