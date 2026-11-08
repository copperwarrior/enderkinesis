package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.ParticleStatus
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import org.shipwrights.enderkinesis.ritual.Conflagration
import kotlin.math.sqrt

/**
 * The Ritual of Conflagration's fire: a wall of flame that rings the rite and goes out with the fire wave, and a small
 * whirl at the centre. The flames a tick are fixed, not grown with the ring, so the cost stays flat; the flames get
 * bigger as the ring widens to keep the wall closed. Client thread only.
 */
object FireWhirlwind {

    private const val WALL_FLAMES_PER_TICK = 36f
    private const val CORE_FLAMES_PER_TICK = 6f
    private const val WALL_HEIGHT = 2.5f
    private const val SMOKE_CHANCE = 0.4f
    private const val RAMP_IN_TICKS = 6f
    // The fire winds down over this after the wave ends.
    private const val WIND_DOWN_TICKS = 40
    // Past this from the camera nothing is spawned, as vanilla culls far particles.
    private const val SPAWN_RANGE = 64.0
    private val FLAME_SPRITE = ResourceLocation("minecraft", "flame")

    private class Whirl(val center: BlockPos, val radius: Int, val ticks: Int) {
        var age = 0

        // As the server wave: the reach grows evenly over the ticks.
        fun front(): Float = radius * (age.toFloat() / ticks).coerceAtMost(1f)

        val speed: Float get() = if (age < ticks) radius.toFloat() / ticks else 0f
    }

    private val whirls = ArrayList<Whirl>()
    private var whirlLevel: ClientLevel? = null

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, Conflagration.S2C_WHIRLWIND) { buf, _ ->
            val center = BlockPos.of(buf.readLong())
            val radius = buf.readVarInt()
            val ticks = buf.readVarInt()
            Minecraft.getInstance().execute { whirls.add(Whirl(center, radius, ticks)) }
        }
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    private fun tick(level: ClientLevel) {
        if (level !== whirlLevel) {
            whirls.clear()
            whirlLevel = level
        }
        if (whirls.isEmpty()) return
        val minecraft = Minecraft.getInstance()
        // The particle atlas is the texture manager's; Minecraft.getTextureAtlas only knows the model atlases.
        val atlas = minecraft.textureManager.getTexture(PARTICLE_ATLAS) as? TextureAtlas ?: return
        val sprite = atlas.getSprite(FLAME_SPRITE)
        val share = when (minecraft.options.particles().get()) {
            ParticleStatus.ALL -> 1f
            ParticleStatus.DECREASED -> 0.5f
            else -> 0.2f
        }
        val camera = minecraft.gameRenderer.mainCamera.position
        val iterator = whirls.iterator()
        while (iterator.hasNext()) {
            val whirl = iterator.next()
            whirl.age++
            if (whirl.age > whirl.ticks + WIND_DOWN_TICKS) {
                iterator.remove()
                continue
            }
            val reach = SPAWN_RANGE + whirl.radius
            if (camera.distanceToSqr(whirl.center.x + 0.5, whirl.center.y + 0.5, whirl.center.z + 0.5) > reach * reach) continue
            spawn(level, whirl, sprite, share)
        }
    }

    private fun spawn(level: ClientLevel, whirl: Whirl, sprite: TextureAtlasSprite, share: Float) {
        val random = level.random
        val rampIn = (whirl.age / RAMP_IN_TICKS).coerceAtMost(1f)
        val windDown = 1f - ((whirl.age - whirl.ticks).toFloat() / WIND_DOWN_TICKS).coerceIn(0f, 1f)
        val strength = rampIn * windDown * share
        val cx = whirl.center.x + 0.5
        val cy = whirl.center.y.toDouble()
        val cz = whirl.center.z + 0.5
        val front = maxOf(whirl.front(), 1f)
        // The wall's area over the flames alive on it, so each flame covers its share of the wall.
        val alive = WALL_FLAMES_PER_TICK * share * Flame.WALL_LIFE
        val size = sqrt(Mth.TWO_PI * front * WALL_HEIGHT / alive).coerceIn(Flame.MIN_SIZE, Flame.MAX_SIZE)
        repeat(chanceCount(WALL_FLAMES_PER_TICK * strength, random.nextFloat())) {
            Minecraft.getInstance().particleEngine.add(
                Flame(
                    level, cx, cy, cz, sprite,
                    angle = random.nextFloat() * Mth.TWO_PI, radius = front, height = random.nextFloat() * WALL_HEIGHT,
                    outward = whirl.speed, rise = Flame.WALL_RISE, spin = 0f, spread = 0f, size = size, life = Flame.WALL_LIFE,
                ),
            )
        }
        repeat(chanceCount(CORE_FLAMES_PER_TICK * strength, random.nextFloat())) {
            Minecraft.getInstance().particleEngine.add(
                Flame(
                    level, cx, cy + 0.5, cz, sprite,
                    angle = random.nextFloat() * Mth.TWO_PI, radius = Flame.CORE_RADIUS, height = random.nextFloat(),
                    outward = 0f, rise = Flame.CORE_RISE, spin = Flame.CORE_SPIN, spread = Flame.CORE_SPREAD,
                    size = Flame.MIN_SIZE, life = Flame.CORE_LIFE,
                ),
            )
        }
        if (random.nextFloat() < SMOKE_CHANCE * strength) {
            val angle = random.nextFloat() * Mth.TWO_PI
            level.addParticle(
                ParticleTypes.LARGE_SMOKE,
                cx + front * Mth.cos(angle), cy + WALL_HEIGHT.toDouble(), cz + front * Mth.sin(angle), 0.0, 0.08, 0.0,
            )
        }
    }

    // The fraction becomes a chance, so a thin fire still flickers.
    private fun chanceCount(amount: Float, roll: Float): Int = amount.toInt() + if (roll < amount - amount.toInt()) 1 else 0

    /** A flame on a circle round the rite: it rises, turns by [spin] and moves out by [outward] a tick. */
    private class Flame(
        level: ClientLevel,
        private val cx: Double, private val cy: Double, private val cz: Double,
        sprite: TextureAtlasSprite,
        private var angle: Float,
        private val radius: Float,
        private var height: Float,
        private val outward: Float,
        private val rise: Float,
        private val spin: Float,
        private val spread: Float,
        size: Float,
        life: Int,
    ) : TextureSheetParticle(level, cx, cy, cz) {

        init {
            xd = 0.0
            yd = 0.0
            zd = 0.0
            gravity = 0f
            hasPhysics = false
            quadSize = size
            lifetime = life
            setSprite(sprite)
            place()
            xo = x
            yo = y
            zo = z
        }

        override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_OPAQUE

        override fun getLightColor(partialTick: Float): Int = LightTexture.FULL_BRIGHT

        // As a vanilla flame, it shrinks as it ages.
        override fun getQuadSize(partialTick: Float): Float {
            val t = (age + partialTick) / lifetime
            return quadSize * (1f - t * t * 0.5f)
        }

        override fun tick() {
            super.tick()
            if (removed) return
            height += rise
            // Narrow circles turn faster, as the air in a whirl does.
            if (spin != 0f) angle += spin / maxOf(currentRadius(), 0.5f)
            place()
        }

        private fun currentRadius(): Float = radius + outward * age + spread * height

        private fun place() {
            val r = currentRadius()
            setPos(cx + r * Mth.cos(angle), cy + height, cz + r * Mth.sin(angle))
        }

        companion object {
            const val WALL_LIFE = 12
            const val WALL_RISE = 0.06f
            const val MIN_SIZE = 0.25f
            const val MAX_SIZE = 0.9f
            const val CORE_LIFE = 24
            const val CORE_RISE = 0.35f
            const val CORE_SPIN = 0.45f
            const val CORE_SPREAD = 0.15f
            const val CORE_RADIUS = 0.4f
        }
    }
}
