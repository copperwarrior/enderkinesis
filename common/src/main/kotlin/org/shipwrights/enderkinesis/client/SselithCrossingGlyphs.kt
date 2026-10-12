package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.ParticleStatus
import net.minecraft.client.gui.screens.ReceivingLevelScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.NoRenderParticle
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min

/** Emits a full glyph effect from one server packet. [emit] returns false while its target is not ready. */
class SselithCrossingSeedParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    private val emit: () -> Boolean,
) : NoRenderParticle(level, x, y, z) {

    override fun tick() {
        // Particles age behind the dimension loading screen, so the traveller would not see the effect.
        if (Minecraft.getInstance().screen is ReceivingLevelScreen) return
        if (emit() || age++ >= TARGET_WAIT_TICKS) remove()
    }

    private companion object {
        const val TARGET_WAIT_TICKS = 40
    }
}

private fun scaledCount(full: Int): Int = when (Minecraft.getInstance().options.particles().get()) {
    ParticleStatus.ALL -> full
    ParticleStatus.DECREASED -> full / 2
    ParticleStatus.MINIMAL -> 0
}

/** Centre of the arrival storm, drawn as a sun like the head of the Archive. It trails [target] for a short time. */
class SselithStormSunParticle(
    level: ClientLevel,
    private val target: Entity,
) : Particle(level, target.x, target.y + HEIGHT_ABOVE_TARGET, target.z) {

    private val corner = Vector3f()

    val centerX: Double get() = x
    val centerY: Double get() = y
    val centerZ: Double get() = z

    init {
        lifetime = LIFETIME_TICKS
        hasPhysics = false
    }

    override fun tick() {
        xo = x
        yo = y
        zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }
        if (age >= FOLLOW_TICKS || target.isRemoved) return
        // The rate falls to zero, so the centre slows to a stop.
        val follow = FOLLOW_PER_TICK * (1.0 - age / FOLLOW_TICKS.toDouble())
        setPos(
            x + (target.x - x) * follow,
            y + (target.y + HEIGHT_ABOVE_TARGET - y) * follow,
            z + (target.z - z) * follow,
        )
    }

    // CUSTOM gives no open buffer and no blend, so this draws its own additive quad. Vanilla sun.png has no alpha.
    override fun render(buffer: VertexConsumer, camera: Camera, partialTick: Float) {
        val cam = camera.position
        val cx = (Mth.lerp(partialTick.toDouble(), xo, x) - cam.x).toFloat()
        val cy = (Mth.lerp(partialTick.toDouble(), yo, y) - cam.y).toFloat()
        val cz = (Mth.lerp(partialTick.toDouble(), zo, z) - cam.z).toFloat()
        val time = age + partialTick
        val half = SUN_HALF * (1f + BREATHE_AMOUNT * Mth.sin(time * BREATHE_RAD_PER_TICK))
        val alpha = (Mth.clamp(min(time / FADE_IN_TICKS, (lifetime - time) / FADE_OUT_TICKS), 0f, 1f) * 255f).toInt()

        RenderSystem.setShader { GameRenderer.getPositionTexColorShader() }
        RenderSystem.setShaderTexture(0, SUN_TEXTURE)
        RenderSystem.enableBlend()
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
        RenderSystem.depthMask(false)
        val tesselator = Tesselator.getInstance()
        val builder = tesselator.builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        vertex(builder, camera, cx, cy, cz, -half, -half, 1f, 1f, alpha)
        vertex(builder, camera, cx, cy, cz, -half, half, 1f, 0f, alpha)
        vertex(builder, camera, cx, cy, cz, half, half, 0f, 0f, alpha)
        vertex(builder, camera, cx, cy, cz, half, -half, 0f, 1f, alpha)
        tesselator.end()
        RenderSystem.depthMask(true)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
    }

    @Suppress("LongParameterList") // One vertex needs its centre, offset, uv, and alpha.
    private fun vertex(
        builder: BufferBuilder, camera: Camera,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, u: Float, v: Float, alpha: Int,
    ) {
        corner.set(dx, dy, 0f).rotate(camera.rotation()).add(cx, cy, cz)
        builder.vertex(corner.x().toDouble(), corner.y().toDouble(), corner.z().toDouble())
            .uv(u, v)
            .color(SUN_RED, SUN_GREEN, SUN_BLUE, alpha)
            .endVertex()
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.CUSTOM

    private companion object {
        const val HEIGHT_ABOVE_TARGET = 3.0
        const val FOLLOW_PER_TICK = 0.2
        const val FOLLOW_TICKS = 20
        const val LIFETIME_TICKS = 100
        const val FADE_IN_TICKS = 8f
        const val FADE_OUT_TICKS = 20f

        // Same texture, size, tint, and breathing as ArchiveRenderer.renderHeadGlow.
        val SUN_TEXTURE = ResourceLocation("textures/environment/sun.png")
        const val SUN_HALF = 0.7f
        const val SUN_RED = 255
        const val SUN_GREEN = 240
        const val SUN_BLUE = 100
        const val BREATHE_AMOUNT = 0.08f
        const val BREATHE_RAD_PER_TICK = 0.12f
    }
}

/** One glyph of the arrival storm: a flat disc that turns about the vertical axis of [center], faster near the eye. */
class SselithStormGlyphParticle(
    level: ClientLevel,
    private val center: SselithStormSunParticle,
    private val heightOffset: Double,
    private var radius: Double,
    private var angle: Float,
) : SselithGlyphParticle(level, center.centerX, center.centerY + heightOffset, center.centerZ) {

    init {
        lifetime = MIN_LIFETIME_TICKS + random.nextInt(LIFETIME_SPREAD_TICKS)
        quadSize *= SIZE_SCALE
        xd = 0.0
        yd = 0.0
        zd = 0.0
        place()
        xo = x
        yo = y
        zo = z
        setAlpha(0f)
    }

    override fun tick() {
        xo = x
        yo = y
        zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }
        radius = max(EYE_RADIUS, radius - INFLOW_PER_TICK)
        angle -= (EYE_SPIN_RAD_PER_TICK * EYE_RADIUS / radius).toFloat()
        place()
        setAlpha(Mth.clamp(min(age / FADE_IN_TICKS, (lifetime - age) / FADE_OUT_TICKS), 0f, 1f))
    }

    private fun place() = setPos(
        center.centerX + radius * Mth.cos(angle),
        center.centerY + heightOffset,
        center.centerZ + radius * Mth.sin(angle),
    )

    // The provider reads the target entity id from the xd argument. SimpleParticleType has no data channel.
    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle {
            val targetId = xd.toInt()
            // Other clients can get this packet before the packet that adds the traveller.
            return SselithCrossingSeedParticle(level, x, y, z) {
                val target = level.getEntity(targetId) ?: return@SselithCrossingSeedParticle false
                spawnStorm(level, target)
                true
            }
        }

        private fun spawnStorm(level: ClientLevel, target: Entity) {
            val count = scaledCount(GLYPH_COUNT)
            if (count == 0) return
            val rng = level.random
            val engine = Minecraft.getInstance().particleEngine
            val center = SselithStormSunParticle(level, target)
            engine.add(center)
            repeat(count) { i ->
                val radius = EYE_RADIUS + rng.nextDouble() * (OUTER_RADIUS - EYE_RADIUS)
                val arm = (i % ARMS) * Mth.TWO_PI / ARMS
                val angle = arm + ARM_TWIST_RAD_PER_BLOCK * radius.toFloat() + rng.nextGaussian().toFloat() * ARM_SCATTER_RAD
                val glyph = SselithStormGlyphParticle(
                    level, center, (rng.nextDouble() - 0.5) * THICKNESS, radius, angle,
                )
                glyph.pickSprite(sprites)
                engine.add(glyph)
            }
        }
    }

    private companion object {
        const val GLYPH_COUNT = 220
        const val ARMS = 3
        const val EYE_RADIUS = 1.0
        const val OUTER_RADIUS = 5.0
        const val THICKNESS = 0.8
        const val ARM_TWIST_RAD_PER_BLOCK = 0.55f
        const val ARM_SCATTER_RAD = 0.3f
        const val EYE_SPIN_RAD_PER_TICK = 0.3
        const val INFLOW_PER_TICK = 0.01
        const val SIZE_SCALE = 1.5f
        const val MIN_LIFETIME_TICKS = 70
        const val LIFETIME_SPREAD_TICKS = 30
        const val FADE_IN_TICKS = 8f
        const val FADE_OUT_TICKS = 20f
    }
}

/** One glyph of the departure aura: it turns around [target], moves outward, and fades. */
class SselithAuraGlyphParticle(
    level: ClientLevel,
    private val target: Entity,
    private val startAngle: Float,
    private val height: Double,
) : SselithGlyphParticle(level, target.x, target.y + height, target.z) {

    init {
        lifetime = MIN_LIFETIME_TICKS + random.nextInt(LIFETIME_SPREAD_TICKS)
        xd = 0.0
        yd = 0.0
        zd = 0.0
        place()
        xo = x
        yo = y
        zo = z
    }

    override fun tick() {
        xo = x
        yo = y
        zo = z
        if (age++ >= lifetime || target.isRemoved) {
            remove()
            return
        }
        place()
        setAlpha(1f - age / lifetime.toFloat())
    }

    private fun place() {
        val t = age / lifetime.toFloat()
        val radius = Mth.lerp(1f - (1f - t) * (1f - t), INNER_RADIUS, OUTER_RADIUS)
        val angle = startAngle + age * SPIN_RAD_PER_TICK
        setPos(
            target.x + radius * Mth.cos(angle),
            target.y + height + RISE * t,
            target.z + radius * Mth.sin(angle),
        )
    }

    // The provider reads the target entity id from the xd argument. SimpleParticleType has no data channel.
    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle {
            val targetId = xd.toInt()
            // Other clients can get this packet before the packet that adds the traveller.
            return SselithCrossingSeedParticle(level, x, y, z) {
                val target = level.getEntity(targetId) ?: return@SselithCrossingSeedParticle false
                spawnAura(level, target)
                true
            }
        }

        private fun spawnAura(level: ClientLevel, target: Entity) {
            val rng = level.random
            val engine = Minecraft.getInstance().particleEngine
            repeat(scaledCount(GLYPH_COUNT)) {
                val glyph = SselithAuraGlyphParticle(
                    level, target, rng.nextFloat() * Mth.TWO_PI, rng.nextDouble() * target.bbHeight,
                )
                glyph.pickSprite(sprites)
                engine.add(glyph)
            }
        }
    }

    private companion object {
        const val GLYPH_COUNT = 90
        const val INNER_RADIUS = 0.6f
        const val OUTER_RADIUS = 3.0f
        const val SPIN_RAD_PER_TICK = 0.25f
        const val RISE = 0.6
        const val MIN_LIFETIME_TICKS = 40
        const val LIFETIME_SPREAD_TICKS = 20
    }
}
