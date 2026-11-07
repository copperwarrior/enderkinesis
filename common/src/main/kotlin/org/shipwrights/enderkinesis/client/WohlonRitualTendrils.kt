package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.WohlonRitualNetwork
import org.shipwrights.enderkinesis.block.WohlonRitualTimeline
import org.shipwrights.enderkinesis.util.ClassWarmup

/**
 * Ritual ignite: a tendril rises from each candle to the Heart, then tendrils run from the Heart
 * out to the visible tip of each root arm. Volume tubes plus motes. Client thread only.
 */
object WohlonRitualTendrils {

    private const val MAX_RITUALS = 4
    private const val MAX_TIPS = 16
    private const val MAX_CANDLES = 64

    private const val RISE = WohlonRitualTimeline.RISE
    private const val RISE_STAGGER = WohlonRitualTimeline.RISE_STAGGER
    private const val SPREAD = WohlonRitualTimeline.SPREAD
    private const val SPREAD_STAGGER = WohlonRitualTimeline.SPREAD_STAGGER
    private const val FADE = WohlonRitualTimeline.FADE

    private const val RISE_RADIUS = 0.14f
    private const val SPREAD_RADIUS = 0.22f
    // The root tendrils writhe: blocks of bend for each block of length.
    private const val WILD_PER_BLOCK = 0.09
    private const val WILD_MAX = 2.5
    private const val CANDLE_FLAME_Y = 0.5
    private const val HEART_Y = 2.5

    internal val CANDLE_COLOUR = floatArrayOf(0.50f, 0.80f, 1.00f)
    internal val HEART_COLOUR = floatArrayOf(0.00f, 1.00f, 1.00f)
    internal val ROOT_COLOUR = floatArrayOf(0.557f, 0.831f, 0.784f)

    private val RISE_STYLE = TendrilStyle(RISE_RADIUS, CANDLE_COLOUR, HEART_COLOUR)
    private val SPREAD_STYLE = TendrilStyle(SPREAD_RADIUS, HEART_COLOUR, ROOT_COLOUR)

    private class Tendril(val curve: TendrilCurve, val delay: Double)

    private class Ritual(val candle: BlockPos, sacrifice: Vector3d, candles: List<BlockPos>, val igniteNanos: Long) {
        val heart = Vector3d(candle.x + 0.5, candle.y + HEART_Y, candle.z + 0.5)
        val frame = TendrilFrame(heart)
        val candleCount = candles.size
        val rise: List<Tendril> = candles.mapIndexed { i, offset ->
            riseTendril(Vector3d(candle.x + offset.x + 0.5, candle.y + offset.y + CANDLE_FLAME_Y, candle.z + offset.z + 0.5), i)
        } + riseTendril(sacrifice, candles.size)

        private fun riseTendril(start: Vector3d, index: Int) =
            Tendril(TendrilCurve(start, riseControl(start, heart), Vector3d(heart)), index * RISE_STAGGER)
        var spread: List<Tendril> = emptyList()
        var spreadStart = Double.NaN
        var spreadHeard = false

        fun fadeStart(): Double =
            if (spreadStart.isNaN()) WohlonRitualTimeline.ROOTS_TIMEOUT else WohlonRitualTimeline.fadeStart(spreadStart)
    }

    private val rituals = ArrayList<Ritual>()

    fun init() {
        // The first ritual of a session loaded these on its frame.
        ClassWarmup.load(Tendril::class.java, TendrilCurve::class.java, Ritual::class.java, WohlonMoteParticle::class.java, WohlonRitualTimeline::class.java)
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WohlonRitualNetwork.S2C_IGNITE) { buf, _ ->
            val candle = BlockPos.of(buf.readLong())
            val sacrifice = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val candles = List(buf.readVarInt().coerceIn(0, MAX_CANDLES)) { BlockPos.of(buf.readLong()) }
            Minecraft.getInstance().execute { onIgnite(candle, sacrifice, candles) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WohlonRitualNetwork.S2C_ROOTS) { buf, _ ->
            val portal = BlockPos.of(buf.readLong())
            val count = buf.readVarInt().coerceIn(0, MAX_TIPS)
            val tips = LongArray(count) { buf.readLong() }
            Minecraft.getInstance().execute { onRoots(portal, tips) }
        }
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() = rituals.clear()

    private fun onIgnite(candle: BlockPos, sacrifice: Vector3d, candles: List<BlockPos>) {
        if (rituals.size >= MAX_RITUALS) rituals.removeAt(0)
        rituals.add(Ritual(candle.immutable(), sacrifice, candles, System.nanoTime()))
    }

    private fun onRoots(portal: BlockPos, tips: LongArray) {
        val ritual = rituals.firstOrNull { it.candle == portal.below(2) } ?: return
        if (!ritual.spreadStart.isNaN()) return
        val random = Minecraft.getInstance().level?.random ?: return
        ritual.spread = tips.map { packed ->
            val tip = BlockPos.of(packed)
            val end = Vector3d(tip.x + 0.5, tip.y + 0.5, tip.z + 0.5)
            val length = ritual.heart.distance(end)
            val curve = TendrilCurve(
                Vector3d(ritual.heart), spreadControl(ritual.heart, end, random), end,
                wild = minOf(WILD_MAX, WILD_PER_BLOCK * length) * (0.7 + 0.6 * random.nextDouble()),
                seed = random.nextDouble() * 100.0,
            )
            Tendril(curve, random.nextDouble() * SPREAD_STAGGER)
        }
        ritual.spreadStart = WohlonRitualTimeline.spreadStart(ageSec(ritual), ritual.candleCount)
    }

    private fun ageSec(ritual: Ritual): Double = (System.nanoTime() - ritual.igniteNanos) * 1e-9

    // Arcs up and out past the candle ring, so the eight tendrils read apart.
    private fun riseControl(start: Vector3d, heart: Vector3d): Vector3d {
        val ox = start.x - heart.x
        val oz = start.z - heart.z
        val len = maxOf(Math.sqrt(ox * ox + oz * oz), 1.0e-3)
        return Vector3d((start.x + heart.x) * 0.5 + ox / len * 0.6, (start.y + heart.y) * 0.5 + 1.6, (start.z + heart.z) * 0.5 + oz / len * 0.6)
    }

    // An arc out, then down to the root; each one bows to the side and rises by its own amount.
    private fun spreadControl(heart: Vector3d, end: Vector3d, random: net.minecraft.util.RandomSource): Vector3d {
        val dx = end.x - heart.x
        val dz = end.z - heart.z
        val dist = Math.sqrt(dx * dx + dz * dz)
        val side = (random.nextDouble() - 0.5) * 0.7
        val sideX = if (dist > 1.0e-3) -dz / dist * side * dist else 0.0
        val sideZ = if (dist > 1.0e-3) dx / dist * side * dist else 0.0
        val rise = (0.12 + 0.25 * random.nextDouble()) * dist + 1.5 + 2.5 * random.nextDouble()
        return Vector3d(heart.x + dx * 0.45 + sideX, maxOf(heart.y, end.y) + rise, heart.z + dz * 0.45 + sideZ)
    }

    private fun reach(age: Double, phaseStart: Double, duration: Double, tendril: Tendril): Double {
        val x = ((age - phaseStart - tendril.delay) / duration).coerceIn(0.0, 1.0)
        // Ease out: fast from the source, slowing as it reaches the target.
        return 1.0 - (1.0 - x) * (1.0 - x)
    }

    private fun strength(ritual: Ritual, age: Double): Float =
        (1.0 - ((age - ritual.fadeStart()) / FADE).coerceIn(0.0, 1.0)).toFloat()

    private fun tick(level: ClientLevel) {
        if (rituals.isEmpty()) return
        val iter = rituals.iterator()
        while (iter.hasNext()) {
            val ritual = iter.next()
            val age = ageSec(ritual)
            if (age > ritual.fadeStart() + FADE) {
                iter.remove()
                continue
            }
            if (age > ritual.fadeStart()) continue
            if (!ritual.spreadHeard && age >= ritual.spreadStart) {
                ritual.spreadHeard = true
                WohlonRitualSounds.playSpread(ritual.heart)
            }
            for (t in ritual.rise) spawnMote(level, t, reach(age, 0.0, RISE, t), CANDLE_COLOUR, HEART_COLOUR)
            if (!ritual.spreadStart.isNaN()) {
                for (t in ritual.spread) spawnMote(level, t, reach(age, ritual.spreadStart, SPREAD, t), HEART_COLOUR, ROOT_COLOUR)
            }
        }
    }

    // A growing tendril trails motes behind its tip; a grown one sends them along its full length.
    private fun spawnMote(level: ClientLevel, tendril: Tendril, reach: Double, from: FloatArray, to: FloatArray) {
        if (reach <= 0.0) return
        val random = level.random
        val grown = reach >= 1.0
        if (grown && random.nextInt(3) != 0) return
        val s0 = if (grown) 0.0 else maxOf(0.0, reach - 0.15)
        val s1 = reach
        val jx = (random.nextDouble() - 0.5) * 0.25
        val jy = (random.nextDouble() - 0.5) * 0.25
        val jz = (random.nextDouble() - 0.5) * 0.25
        val colour = FloatArray(3)
        val mix = s1.toFloat()
        for (i in 0 until 3) colour[i] = Mth.lerp(mix, from[i], to[i])
        val curve = tendril.curve
        val bend = Vector3d()
        WohlonMoteParticle.spawn(level, { u, out ->
            curve.writhedPointAt(s0 + (s1 - s0) * u, out, bend)
            out.add(jx, jy, jz)
        }, if (grown) 20 else 10, colour, 0.06f)
    }

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (rituals.isEmpty() || !TendrilVolume.begin(poseStack, projection, camera)) return
        for (ritual in rituals) {
            val age = ageSec(ritual)
            val str = strength(ritual, age)
            if (str <= 0f) continue
            for (t in ritual.rise) {
                TendrilVolume.draw(ritual.frame, t.curve, reach(age, 0.0, RISE, t), str, RISE_STYLE)
            }
            if (ritual.spreadStart.isNaN()) continue
            for (t in ritual.spread) {
                TendrilVolume.draw(ritual.frame, t.curve, reach(age, ritual.spreadStart, SPREAD, t), str, SPREAD_STYLE)
            }
        }
        TendrilVolume.end()
    }
}
