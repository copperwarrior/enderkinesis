package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.SpellGeom.easeOut
import org.shipwrights.enderkinesis.client.SpellGeom.smooth
import org.shipwrights.enderkinesis.dimension.WeheloPocket
import org.shipwrights.enderkinesis.entity.WeheloTearEntity
import org.shipwrights.enderkinesis.item.WeheloBlade
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKSounds
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Wehelo tears in the world, and the grey, eyed fog inside a pocket. Render thread only. */
object WeheloPocketClient {

    private val LOG = org.slf4j.LoggerFactory.getLogger("EnderkinesisWeheloPocket")

    /** [dir] is fixed in the world; the distance is kept from the camera, so walking at the eyes never closes on them. */
    private class Eyes(val dir: Vec3, val rest: Double, val born: Long, val life: Int, val blinkAt: Int)

    private val eyes = ArrayList<Eyes>()
    private var ambience: PocketLoop? = null
    private var grayscale: EffectInstance? = null
    // A broken shader must not log one warning each frame.
    private var grayscaleFailed = false

    fun init() {
        ClientTickEvent.CLIENT_POST.register { mc -> tickEyes(mc.level) }
    }

    fun close() {
        grayscale?.close()
        grayscale = null
        grayscaleFailed = false
    }

    /** Called from `LevelRendererBubblePassMixin` at the TAIL of `LevelRenderer.renderLevel`, after the other screen passes. */
    @JvmStatic
    fun renderGrayscale() {
        val mc = Minecraft.getInstance()
        if (mc.level?.dimension() != WeheloPocket.LEVEL_KEY) return
        val effect = grayscale ?: loadGrayscale(mc) ?: return
        // RefractionPass copies from the bound read framebuffer; the GUI pass may leave another one bound.
        mc.mainRenderTarget.bindWrite(true)
        RefractionPass.run(effect)
    }

    private fun loadGrayscale(mc: Minecraft): EffectInstance? {
        if (grayscaleFailed) return null
        return try {
            EffectInstance(mc.resourceManager, "wehelo_pocket_grayscale").also { grayscale = it }
        } catch (t: Throwable) {
            grayscaleFailed = true
            LOG.warn("Failed to load Wehelo pocket grayscale shader program", t)
            null
        }
    }

    fun render(pose: PoseStack, buffers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val camera = mc.gameRenderer.mainCamera
        val view = SpellView(Vec3(camX, camY, camZ), Vec3(camera.leftVector), Vec3(camera.upVector))
        val time = level.gameTime + partialTick
        val tears = level.getEntities(EKEntities.WEHELO_TEAR.get(), AABB(view.cam, view.cam).inflate(TEAR_RANGE)) { true }
        val inPocket = level.dimension() == WeheloPocket.LEVEL_KEY && eyes.isNotEmpty()
        if (tears.isEmpty() && !inPocket) return
        pose.pushPose()
        pose.translate(-camX, -camY, -camZ)
        val matrix = pose.last().pose()
        if (tears.isNotEmpty()) renderTears(buffers, matrix, view, tears, time, partialTick)
        if (inPocket) renderEyes(buffers, matrix, view, time)
        pose.popPose()
    }

    private fun renderTears(
        buffers: MultiBufferSource, matrix: Matrix4f, view: SpellView, tears: List<WeheloTearEntity>, time: Float, partialTick: Float,
    ) {
        for (tear in tears) {
            val (open, amount) = phase(tear, partialTick)
            WeheloRiftDraw.draw(
                buffers, matrix, view, centre(tear), WeheloTearEntity.HEIGHT.toDouble(), WeheloBlade.RIFT_RGB, tear.id,
                open, amount, time,
            )
        }
    }

    private fun centre(tear: WeheloTearEntity): Vec3 = tear.position().add(0.0, WeheloTearEntity.HEIGHT * 0.5, 0.0)

    private fun phase(tear: WeheloTearEntity, partialTick: Float): Pair<Double, Double> {
        val age = tear.tickCount + partialTick
        val open = easeOut((age / TEAR_OPEN_TICKS).coerceIn(0f, 1f).toDouble())
        val seal = 1.0 - smooth(((age - (WeheloTearEntity.LIFE_TICKS - TEAR_SEAL_TICKS)) / TEAR_SEAL_TICKS).toDouble())
        return open to open * seal
    }

    private fun tickEyes(level: ClientLevel?) {
        if (level == null || level.dimension() != WeheloPocket.LEVEL_KEY) {
            eyes.clear()
            return
        }
        keepAmbience()
        val now = level.gameTime
        eyes.removeIf { now - it.born >= it.life }
        if (eyes.size >= MAX_EYES || Random.nextFloat() >= EYE_CHANCE) return
        val angle = Random.nextDouble() * Math.PI * 2
        val rise = EYE_RISE_MIN + Random.nextDouble() * (EYE_RISE_MAX - EYE_RISE_MIN)
        val dir = Vec3(cos(angle), rise, sin(angle)).normalize()
        val rest = EYE_REST_MIN + Random.nextDouble() * (EYE_REST_MAX - EYE_REST_MIN)
        val life = EYE_LIFE_MIN + Random.nextInt(EYE_LIFE_RANGE)
        val blinkAt = EYE_EMERGE + Random.nextInt(life - 2 * EYE_EMERGE - EYE_BLINK)
        eyes += Eyes(dir, rest, now, life, blinkAt)
        // The eyes are past the fog, too far to hear, so the sound comes from their direction close by.
        val ear = Minecraft.getInstance().player?.eyePosition ?: return
        val at = ear.add(dir.scale(EYE_SOUND_DISTANCE))
        level.playLocalSound(at.x, at.y, at.z, EKSounds.WEHELO_EYE.get(), SoundSource.AMBIENT, EYE_SOUND_VOLUME, 1f, false)
    }

    private fun keepAmbience() {
        val sounds = Minecraft.getInstance().soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        if (ambience?.let { !it.isStopped && sounds.isActive(it) } == true) return
        ambience = PocketLoop().also(sounds::play)
    }

    private class PocketLoop : AbstractTickableSoundInstance(EKSounds.WEHELO_POCKET_LOOP.get(), SoundSource.AMBIENT, SoundInstance.createUnseededRandom()) {
        init {
            looping = true
            delay = 0
            volume = POCKET_VOLUME
            relative = true
            attenuation = SoundInstance.Attenuation.NONE
        }

        override fun tick() {
            if (Minecraft.getInstance().level?.dimension() != WeheloPocket.LEVEL_KEY) stop()
        }
    }

    private fun renderEyes(buffers: MultiBufferSource, matrix: Matrix4f, view: SpellView, time: Float) {
        // Additive and unlit, as vanilla emissive eyes.
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (eye in eyes) {
            val age = time - eye.born
            if (age >= eye.blinkAt && age < eye.blinkAt + EYE_BLINK) continue
            // Out of the fog and back: hidden past the fog end, at rest inside it.
            val near = smooth(age / EYE_EMERGE) * smooth((eye.life - age) / EYE_EMERGE)
            val distance = EYE_HIDDEN + (eye.rest - EYE_HIDDEN) * near
            val rgb = eyeRgb(distance)
            val centre = view.cam.add(eye.dir.scale(distance))
            for (side in EYE_SIDES) square(glow, matrix, view, centre.add(view.left.scale(side * EYE_GAP * 0.5)), rgb)
        }
    }

    /** The same smoothstep as vanilla linear fog. Emissive eyes fog to black, as vanilla's eyes shader does. */
    fun eyeRgb(distance: Double): Int {
        val fog = smooth(((distance - FOG_START) / (FOG_END - FOG_START)).toFloat())
        val grey = (255 * (1f - fog)).toInt()
        return (grey shl 16) or (grey shl 8) or grey
    }

    private fun square(c: com.mojang.blaze3d.vertex.VertexConsumer, m: Matrix4f, view: SpellView, at: Vec3, rgb: Int) {
        val l = view.left.scale(EYE_HALF); val u = view.up.scale(EYE_HALF)
        SpellShapes.quad(c, m, at.add(l).add(u), at.subtract(l).add(u), at.subtract(l).subtract(u), at.add(l).subtract(u), rgb, 1f, 1f, 1f, 1f)
    }

    private const val TEAR_RANGE = 96.0
    private const val TEAR_OPEN_TICKS = 6f
    private const val TEAR_SEAL_TICKS = 16f
    const val FOG_GREY = 0.18f
    const val FOG_START = 0.0f
    const val FOG_END = WeheloPocket.FOG_END

    private const val MAX_EYES = 3
    private const val EYE_SOUND_DISTANCE = 2.0
    private const val EYE_SOUND_VOLUME = 0.4f
    private const val POCKET_VOLUME = 0.6f
    /** Chance each tick that a new pair opens: about one each 8 s. */
    private const val EYE_CHANCE = 0.006f
    /** Fractions of the fog end: deep in the fog, never clear of it. */
    private const val EYE_REST_MIN = FOG_END * 0.65
    private const val EYE_REST_MAX = FOG_END * 0.85
    /** Past the fog end: an eye here is pure fog colour. */
    private const val EYE_HIDDEN = FOG_END + 2.0
    /** Rise of the eye direction; low, so the floor does not hide them. */
    private const val EYE_RISE_MIN = -0.02
    private const val EYE_RISE_MAX = 0.1
    private const val EYE_LIFE_MIN = 80
    private const val EYE_LIFE_RANGE = 80
    private const val EYE_EMERGE = 25
    private const val EYE_BLINK = 3
    const val EYE_GAP = 0.34
    const val EYE_HALF = 0.05
    private val EYE_SIDES = doubleArrayOf(-1.0, 1.0)
}
