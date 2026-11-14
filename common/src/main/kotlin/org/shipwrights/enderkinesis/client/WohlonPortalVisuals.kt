package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.shaders.Uniform
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.block.WohlonnogondoniaPortalManager
import org.shipwrights.enderkinesis.client.volume.ScreenBounds
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeLayer
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView

/** Client-side portal visuals at each synced Wohlon portal: slow drifting motes, a mote beam from a near player, a soft glow, and fog tentacles. */
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

    // Tentacle lean toward the local player, in blocks for a portal of scale 1: none at START, all at FULL.
    private const val LEAN_START = 5.0
    private const val LEAN_FULL = 1.5
    private const val LEAN_RISE_PER_SEC = 1f / 1.5f
    private const val LEAN_FALL_PER_SEC = 1f / 2.5f
    // The longest frame gap the easing counts, so a pause does not jump the lean.
    private const val LEAN_MAX_STEP_SEC = 0.1f
    private const val CHEST_SHARE = 0.6

    // Beam motes from the local player's chest into the Heart, scaled by the same eased lean as the tentacles.
    private const val BEAM_CHANCE = 0.6
    private const val BEAM_SPEED = 2.5
    private const val BEAM_MIN_LIFE = 12
    private const val BEAM_MAX_LIFE = 50
    private const val BEAM_JITTER = 0.15
    private const val BEAM_BOW = 0.25
    private const val BEAM_STOP_SHORT = 0.35

    private val COLOURS = arrayOf(
        floatArrayOf(0.50f, 0.80f, 1.00f),
        floatArrayOf(0.10f, 0.70f, 0.70f),
        floatArrayOf(0.00f, 1.00f, 1.00f),
    )

    private val view = VolumeView()
    private val effect = VolumeEffect("wohlon_portal_volume", "DepthSampler" to VolumeLayer.depthTexture)
    private val fogEffect = VolumeEffect("wohlon_portal_fog", "DepthSampler" to VolumeLayer.depthTexture)
    private val fogBounds = ScreenBounds()
    private var uniforms: Uniforms? = null
    private var fogUniforms: FogUniforms? = null
    private val localToView = Matrix4f()

    // Per-frame scratch for the portals the accessor reports. Render thread only.
    private val drawX = DoubleArray(MAX_VOLUMES)
    private val drawY = DoubleArray(MAX_VOLUMES)
    private val drawZ = DoubleArray(MAX_VOLUMES)
    private val drawScale = FloatArray(MAX_VOLUMES)
    private val drawFade = FloatArray(MAX_VOLUMES)
    private val drawKey = LongArray(MAX_VOLUMES)
    private val drawLean = FloatArray(MAX_VOLUMES)
    private val drawTargetX = FloatArray(MAX_VOLUMES)
    private val drawTargetY = FloatArray(MAX_VOLUMES)
    private val drawTargetZ = FloatArray(MAX_VOLUMES)
    // Eased lean for each portal, by packed position. Render thread only.
    private val leans = Long2FloatOpenHashMap()
    private var leanLevel: ClientLevel? = null
    private var lastFrameNanos = 0L
    private var drawCount = 0
    private var tickLevel: ClientLevel? = null
    private var playerX = 0.0
    private var playerY = 0.0
    private var playerZ = 0.0
    private var playerChestY = 0.0

    private val collect = WohlonPortalSounds.PortalAction { pos, mother ->
        if (drawCount < MAX_VOLUMES) {
            drawX[drawCount] = pos.x + 0.5
            drawY[drawCount] = pos.y + 0.5
            drawZ[drawCount] = pos.z + 0.5
            drawKey[drawCount] = pos.asLong()
            drawScale[drawCount] = if (mother) WohlonnogondoniaPortalManager.MOTHER_VISUAL_SCALE.toFloat() else 1f
            drawCount++
        }
    }

    private val spawnAt = WohlonPortalSounds.PortalAction { pos, mother ->
        val level = tickLevel ?: return@PortalAction
        if (pos.distToCenterSqr(playerX, playerY, playerZ) > PARTICLE_RANGE * PARTICLE_RANGE) return@PortalAction
        val scale = if (mother) WohlonnogondoniaPortalManager.MOTHER_VISUAL_SCALE else 1.0
        spawnMotes(level, pos, scale)
        val lean = leans.get(pos.asLong())
        if (lean > 0f) spawnBeam(level, pos, scale, lean)
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
        playerChestY = player.y + player.bbHeight * CHEST_SHARE
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

    private fun spawnBeam(level: ClientLevel, pos: BlockPos, scale: Double, lean: Float) {
        val random = level.random
        if (random.nextDouble() >= BEAM_CHANCE * lean) return
        val sx = playerX + (random.nextDouble() * 2.0 - 1.0) * BEAM_JITTER
        val sy = playerChestY + (random.nextDouble() * 2.0 - 1.0) * BEAM_JITTER
        val sz = playerZ + (random.nextDouble() * 2.0 - 1.0) * BEAM_JITTER
        val cx = pos.x + 0.5
        val cy = pos.y + 0.5
        val cz = pos.z + 0.5
        val dx = sx - cx
        val dy = sy - cy
        val dz = sz - cz
        val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
        val stop = BEAM_STOP_SHORT * scale
        if (dist <= stop) return
        val ex = cx + dx / dist * stop
        val ey = cy + dy / dist * stop
        val ez = cz + dz / dist * stop
        // A sideways bow, so the stream sways a little and is not a ruler line.
        val flat = Math.sqrt(dx * dx + dz * dz).coerceAtLeast(1.0e-3)
        val bow = (random.nextDouble() * 2.0 - 1.0) * BEAM_BOW * scale
        val bx = -dz / flat * bow
        val bz = dx / flat * bow
        val life = Mth.clamp(((dist - stop) / BEAM_SPEED * 20.0).toInt(), BEAM_MIN_LIFE, BEAM_MAX_LIFE)
        val size = (0.035 * Math.sqrt(scale)).toFloat() * (0.8f + random.nextFloat() * 0.4f)
        WohlonMoteParticle.spawn(level, { t, out ->
            // Leaves the player fast and settles at the Heart, like the gathering motes.
            val k = t * (2.0 - t)
            val b = Math.sin(t * Math.PI)
            out.set(sx + (ex - sx) * k + bx * b, sy + (ey - sy) * k, sz + (ez - sz) * k + bz * b)
        }, life, COLOURS[random.nextInt(COLOURS.size)], size)
    }

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val level = Minecraft.getInstance().level ?: return
        if (IrisCompat.isShaderPackInUse()) {
            // The client tick reads the leans for the beam, so a lean left here would keep the beam on.
            leans.clear()
            return
        }
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
        val now = System.nanoTime()
        updateLeans(level, now)
        if (visible == 0) return
        val tSec = now * 1e-9
        // The fog goes first, so the glow shows through it.
        renderFog(main, tSec)
        renderGlow(main, tSec)
        VolumeLayer.flush(main)
    }

    /** Eases each drawn portal's lean toward the local player's distance and sets the chest target in portal space. */
    private fun updateLeans(level: ClientLevel, now: Long) {
        if (leanLevel !== level) {
            leans.clear()
            leanLevel = level
        }
        val step = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) * 1e-9).toFloat().coerceIn(0f, LEAN_MAX_STEP_SEC)
        lastFrameNanos = now
        val player = Minecraft.getInstance().player
        val pt = Minecraft.getInstance().frameTime
        for (i in 0 until drawCount) {
            var goal = 0f
            if (player != null) {
                val tx = Mth.lerp(pt.toDouble(), player.xo, player.x) - drawX[i]
                val ty = Mth.lerp(pt.toDouble(), player.yo, player.y) + player.bbHeight * CHEST_SHARE - drawY[i]
                val tz = Mth.lerp(pt.toDouble(), player.zo, player.z) - drawZ[i]
                drawTargetX[i] = tx.toFloat()
                drawTargetY[i] = ty.toFloat()
                drawTargetZ[i] = tz.toFloat()
                val scale = drawScale[i]
                val dist = Math.sqrt(tx * tx + ty * ty + tz * tz)
                val k = Mth.clamp(((LEAN_START * scale - dist) / ((LEAN_START - LEAN_FULL) * scale)).toFloat(), 0f, 1f)
                goal = k * k * (3f - 2f * k)
            }
            val key = drawKey[i]
            val current = leans.get(key)
            val eased = if (goal > current) minOf(goal, current + LEAN_RISE_PER_SEC * step)
                else maxOf(goal, current - LEAN_FALL_PER_SEC * step)
            if (eased <= 0f) leans.remove(key) else leans.put(key, eased)
            drawLean[i] = eased
        }
    }

    private fun renderFog(main: RenderTarget, tSec: Double) {
        val eff = fogEffect.get() ?: return
        fogBounds.reset()
        for (i in 0 until drawCount) {
            if (drawFade[i] <= 0f) continue
            val reach = FOG_REACH * drawScale[i]
            val sx = (drawX[i] - view.camX).toFloat()
            val sy = (drawY[i] - view.camY).toFloat()
            val sz = (drawZ[i] - view.camZ).toFloat()
            fogBounds.addBox(view, sx - reach, sy - reach, sz - reach, sx + reach, sy + reach, sz + reach)
        }
        if (fogBounds.isEmpty) return
        val u = fogUniforms ?: FogUniforms(eff).also { fogUniforms = it }
        if (!VolumeLayer.begin(main, view)) return
        val phase = tSec % (2.0 * TIME_PERIOD_SEC)
        u.pushFrame(VolumeLayer.width, VolumeLayer.height, RenderSystem.getShaderFogColor(), (if (phase <= TIME_PERIOD_SEC) phase else 2.0 * TIME_PERIOD_SEC - phase).toFloat())
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
            u.lean.set(drawLean[i])
            u.target.set(drawTargetX[i], drawTargetY[i], drawTargetZ[i])
            if (drawn == 0) {
                VolumeProxy.beginBound()
                VolumeProxy.draw(eff)
            } else {
                u.upload()
                VolumeProxy.drawApplied()
            }
            drawn++
        }
        VolumeProxy.end(main, eff)
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
                if (!VolumeProxy.beginLayer(main, view, eff)) return
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
        val lean: AbstractUniform = eff.safeGetUniform("Lean")
        val target: AbstractUniform = eff.safeGetUniform("Target")
        private val time: AbstractUniform = eff.safeGetUniform("Time")
        private val fogColour: AbstractUniform = eff.safeGetUniform("FogColor")
        private val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        private val perPortal: List<Uniform> =
            listOf(boxMin, boxMax, localToView, camLocal, reach, seed, strength, lean, target).filterIsInstance<Uniform>()

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
