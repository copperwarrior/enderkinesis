package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.shaders.Uniform
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.block.WohlonnogondoniaPortalManager
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView

/** Client-side portal visuals at each synced Wohlon portal: slow drifting motes, a soft glow, and fog tentacles. */
object WohlonPortalVisuals {

    // Motes for each portal per tick, as a chance: 0.6 is about 12 a second.
    private const val DRIFT_CHANCE = 0.35
    private const val PLUME_CHANCE = 0.1
    private const val SPHERE_RADIUS = 1.1
    private const val DRIFT_TURNS = 0.35
    private const val DRIFT_LIFE = 50
    private const val PLUME_LIFE = 70
    private const val PLUME_HEIGHT = 2.2
    private const val PLUME_SWAY = 0.12
    private const val PARTICLE_RANGE = 48.0

    private const val VOLUME_RADIUS = 1.6f
    private const val VOLUME_FADE_NEAR = 48.0
    private const val VOLUME_FADE_FAR = 64.0
    private const val MAX_VOLUMES = 16
    // A quarter turn each minute: the aura moves, but nothing reads as a spin.
    private const val SPIN_RATE = 0.026
    private const val BREATH_PERIOD_SEC = 7.0
    private const val TAU = Math.PI * 2.0

    // Fog tentacles: horizontal reach. They curl up or down by up to about the reach.
    private const val FOG_REACH = 3.5f
    private const val FOG_OPACITY = 0.8f
    // A tentacle colour a little lighter than the fog, so it reads against distant fog.
    private const val FOG_LIFT = 1.25f
    private const val TIME_PERIOD_SEC = 4000.0

    private val COLOURS = arrayOf(
        floatArrayOf(0.50f, 0.80f, 1.00f),
        floatArrayOf(0.10f, 0.70f, 0.70f),
        floatArrayOf(0.00f, 1.00f, 1.00f),
    )

    private val view = VolumeView()
    private val effect = VolumeEffect("wohlon_portal_volume")
    private val fogEffect = VolumeEffect("wohlon_portal_fog")
    private var uniforms: Uniforms? = null
    private var fogUniforms: FogUniforms? = null
    private val localToView = Matrix4f()

    // Per-frame scratch for the portals the accessor reports. Render thread only.
    private val drawX = DoubleArray(MAX_VOLUMES)
    private val drawY = DoubleArray(MAX_VOLUMES)
    private val drawZ = DoubleArray(MAX_VOLUMES)
    private val drawScale = FloatArray(MAX_VOLUMES)
    private val drawFade = FloatArray(MAX_VOLUMES)
    private var drawCount = 0
    private var tickLevel: ClientLevel? = null
    private var playerX = 0.0
    private var playerY = 0.0
    private var playerZ = 0.0

    private val collect = WohlonPortalSounds.PortalAction { pos, mother ->
        if (drawCount < MAX_VOLUMES) {
            drawX[drawCount] = pos.x + 0.5
            drawY[drawCount] = pos.y + 0.5
            drawZ[drawCount] = pos.z + 0.5
            drawScale[drawCount] = if (mother) WohlonnogondoniaPortalManager.MOTHER_VISUAL_SCALE.toFloat() else 1f
            drawCount++
        }
    }

    private val spawnAt = WohlonPortalSounds.PortalAction { pos, mother ->
        val level = tickLevel ?: return@PortalAction
        if (pos.distToCenterSqr(playerX, playerY, playerZ) > PARTICLE_RANGE * PARTICLE_RANGE) return@PortalAction
        spawnMotes(level, pos, if (mother) WohlonnogondoniaPortalManager.MOTHER_VISUAL_SCALE else 1.0)
    }

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    private fun tick(level: ClientLevel) {
        val player = Minecraft.getInstance().player ?: return
        tickLevel = level
        playerX = player.x
        playerY = player.y
        playerZ = player.z
        WohlonPortalSounds.forEachPortal(level.gameTime, spawnAt)
        tickLevel = null
    }

    private fun spawnMotes(level: ClientLevel, pos: BlockPos, scale: Double) {
        val random = level.random
        val cx = pos.x + 0.5
        val cy = pos.y + 0.5
        val cz = pos.z + 0.5
        val radius = SPHERE_RADIUS * scale
        val size = (0.035 * Math.sqrt(scale)).toFloat()
        if (random.nextDouble() < DRIFT_CHANCE * scale) {
            val a0 = random.nextDouble() * TAU
            val y0 = (random.nextDouble() * 2.0 - 1.0) * radius
            val r0 = Math.sqrt(maxOf(radius * radius - y0 * y0, 0.0))
            val spin = if (random.nextBoolean()) DRIFT_TURNS else -DRIFT_TURNS
            WohlonMoteParticle.spawn(level, { t, out ->
                // Eases in toward the Heart and stops short of it, so the motes gather, not fall in.
                val k = 1.0 - 0.75 * t * (2.0 - t)
                val a = a0 + spin * TAU * t
                out.set(cx + Math.cos(a) * r0 * k, cy + y0 * k, cz + Math.sin(a) * r0 * k)
            }, DRIFT_LIFE + random.nextInt(20), COLOURS[random.nextInt(COLOURS.size)], size * (0.8f + random.nextFloat() * 0.4f))
        }
        if (random.nextDouble() < PLUME_CHANCE * scale) {
            val a0 = random.nextDouble() * TAU
            val height = PLUME_HEIGHT * scale
            val swayX = Math.cos(a0) * PLUME_SWAY * scale
            val swayZ = Math.sin(a0) * PLUME_SWAY * scale
            WohlonMoteParticle.spawn(level, { t, out ->
                val sway = Math.sin(t * Math.PI * 1.5)
                out.set(cx + swayX * sway, cy + height * t, cz + swayZ * sway)
            }, PLUME_LIFE + random.nextInt(20), COLOURS[random.nextInt(COLOURS.size)], size)
        }
    }

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val level = Minecraft.getInstance().level ?: return
        if (IrisCompat.isShaderPackInUse()) return
        drawCount = 0
        WohlonPortalSounds.forEachPortal(level.gameTime, collect)
        if (drawCount == 0) return
        val main = Minecraft.getInstance().mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        var visible = 0
        for (i in 0 until drawCount) {
            val sx = drawX[i] - view.camX
            val sy = drawY[i] - view.camY
            val sz = drawZ[i] - view.camZ
            val dist = Math.sqrt(sx * sx + sy * sy + sz * sz)
            drawFade[i] = 1f - Mth.clamp(((dist - VOLUME_FADE_NEAR) / (VOLUME_FADE_FAR - VOLUME_FADE_NEAR)).toFloat(), 0f, 1f)
            if (drawFade[i] > 0f) visible++
        }
        if (visible == 0) return
        val tSec = System.nanoTime() * 1e-9
        // The fog goes first, so the glow shows through it.
        renderFog(main, tSec)
        renderGlow(main, tSec)
    }

    private fun renderFog(main: RenderTarget, tSec: Double) {
        val eff = fogEffect.get() ?: return
        val u = fogUniforms ?: FogUniforms(eff).also { fogUniforms = it }
        val phase = tSec % (2.0 * TIME_PERIOD_SEC)
        u.pushFrame(main.width, main.height, RenderSystem.getShaderFogColor(), (if (phase <= TIME_PERIOD_SEC) phase else 2.0 * TIME_PERIOD_SEC - phase).toFloat())
        var drawn = 0
        for (i in 0 until drawCount) {
            if (drawFade[i] <= 0f) continue
            val scale = drawScale[i]
            val reach = FOG_REACH * scale
            u.boxMin.set(-reach, -reach, -reach)
            u.boxMax.set(reach, reach, reach)
            pushPlacement(u.localToView, u.camLocal, i)
            u.reach.set(reach)
            u.seed.set(((drawX[i] * 0.37 + drawZ[i] * 0.61) % TAU).toFloat())
            u.strength.set(drawFade[i] * FOG_OPACITY)
            if (drawn == 0) {
                VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
                VolumeProxy.draw(eff)
            } else {
                u.upload()
                VolumeProxy.drawApplied()
            }
            drawn++
        }
        if (drawn > 0) VolumeProxy.end(main, eff)
    }

    private fun renderGlow(main: RenderTarget, tSec: Double) {
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        u.pushFrame(main.width, main.height)
        val breath = 0.9f + 0.1f * Mth.sin((tSec * TAU / BREATH_PERIOD_SEC % TAU).toFloat())
        var drawn = 0
        for (i in 0 until drawCount) {
            if (drawFade[i] <= 0f) continue
            val radius = VOLUME_RADIUS * drawScale[i]
            u.boxMin.set(-radius, -radius, -radius)
            u.boxMax.set(radius, radius, radius)
            pushPlacement(u.localToView, u.camLocal, i)
            u.radius.set(radius)
            // Reduced in double on the CPU: the shader keeps only float precision.
            val offset = (drawX[i] * 0.37 + drawZ[i] * 0.61) % TAU
            u.spin.set(((tSec * SPIN_RATE + offset) % TAU).toFloat())
            u.strength.set(drawFade[i] * breath)
            if (drawn == 0) {
                VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
                VolumeProxy.draw(eff)
            } else {
                u.upload()
                VolumeProxy.drawApplied()
            }
            drawn++
        }
        if (drawn > 0) VolumeProxy.end(main, eff)
    }

    private fun pushPlacement(localToViewUniform: AbstractUniform, camLocal: AbstractUniform, i: Int) {
        val sx = (drawX[i] - view.camX).toFloat()
        val sy = (drawY[i] - view.camY).toFloat()
        val sz = (drawZ[i] - view.camZ).toFloat()
        localToView.translation(sx, sy, sz)
        localToViewUniform.set(localToView)
        camLocal.set(-sx, -sy, -sz)
    }

    private class FogUniforms(private val eff: EffectInstance) {
        val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        val localToView: AbstractUniform = eff.safeGetUniform("LocalToView")
        val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        val reach: AbstractUniform = eff.safeGetUniform("Reach")
        val seed: AbstractUniform = eff.safeGetUniform("Seed")
        val strength: AbstractUniform = eff.safeGetUniform("Strength")
        private val time: AbstractUniform = eff.safeGetUniform("Time")
        private val fogColour: AbstractUniform = eff.safeGetUniform("FogColor")
        private val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        private val perPortal: List<Uniform> =
            listOf(boxMin, boxMax, localToView, camLocal, reach, seed, strength).filterIsInstance<Uniform>()

        fun upload() {
            for (uniform in perPortal) uniform.upload()
        }

        fun pushFrame(width: Int, height: Int, fog: FloatArray, timeSec: Float) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            screenSize.set(width.toFloat(), height.toFloat())
            time.set(timeSec)
            fogColour.set(
                (fog[0] * FOG_LIFT).coerceAtMost(1f),
                (fog[1] * FOG_LIFT).coerceAtMost(1f),
                (fog[2] * FOG_LIFT).coerceAtMost(1f),
            )
        }
    }

    private class Uniforms(private val eff: EffectInstance) {
        val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        val localToView: AbstractUniform = eff.safeGetUniform("LocalToView")
        val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        val radius: AbstractUniform = eff.safeGetUniform("Radius")
        val spin: AbstractUniform = eff.safeGetUniform("Spin")
        val strength: AbstractUniform = eff.safeGetUniform("Strength")
        private val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        private val perPortal: List<Uniform> =
            listOf(boxMin, boxMax, localToView, camLocal, radius, spin, strength).filterIsInstance<Uniform>()

        fun upload() {
            for (uniform in perPortal) uniform.upload()
        }

        fun pushFrame(width: Int, height: Int) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            screenSize.set(width.toFloat(), height.toFloat())
        }
    }
}
