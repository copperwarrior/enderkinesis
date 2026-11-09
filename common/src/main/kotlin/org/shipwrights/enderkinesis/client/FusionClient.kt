package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import org.shipwrights.enderkinesis.item.FusionManager
import org.shipwrights.enderkinesis.item.FusionNetwork
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.valkyrienskies.core.api.util.GameTickOnly

@OptIn(GameTickOnly::class)
object FusionClient {

    private const val COLOR: Int = 0x7B0AFB
    private const val FILL_ALPHA: Float = 18f
    private const val RIM_ALPHA: Float = 150f
    /** Bubble size at the staff head, as a fraction of [FusionManager.RADIUS]. */
    private const val THROW_START_SCALE: Double = 0.05
    private const val ARC_SAMPLES: Int = 32
    private const val STALE_TICKS: Long = 30L
    private const val LAT_STEPS: Int = 24
    private const val LON_STEPS: Int = 48
    private const val STREAMS: Int = 6
    private const val PARTICLES_PER_STREAM: Int = 2
    private const val STREAM_SPIN: Double = 0.05
    private const val TIP_FORWARD: Double = 1.1
    private const val TIP_SIDE: Double = 0.45
    private const val TIP_DROP: Double = 0.25
    private const val HUM_FADE_OUT_TICKS: Int = 5
    private const val PULL_FADE_TICKS: Int = 20
    private const val COLLAPSE_TICKS: Float = 8f
    private const val TETHER_STALE_TICKS: Long = 10L
    private const val TETHER_PARTICLES: Int = 3
    private const val BURST_PARTICLES: Int = 48
    private const val BURST_RADIUS: Double = 1.0

    private val COLOR_VECTOR = Vector3f(((COLOR shr 16) and 0xFF) / 255f, ((COLOR shr 8) and 0xFF) / 255f, (COLOR and 0xFF) / 255f)
    private val DUST = DustParticleOptions(COLOR_VECTOR, 1f)
    private val BURST_DUST = DustParticleOptions(COLOR_VECTOR, 1.6f)

    /** Nearest blocks of two neighbor ships, each in its own shipyard frame. */
    private class Tether(val shipA: Long, val a: Vector3d, val shipB: Long, val b: Vector3d)

    private class View(
        val surface: Vec3, val center: Vec3, val throwFrom: Vec3, val startTick: Long, var seenTick: Long,
        var pulling: Boolean,
    ) {
        /** Arc length of the throw curve at each of [ARC_SAMPLES] + 1 even parameter steps. */
        val arc: DoubleArray = DoubleArray(ARC_SAMPLES + 1)
        /** Set when the cast ends. Its loops then fade out, and the bubble collapses. */
        var removed: Boolean = false
        var releasedTick: Long = -1L
        var pullLoop: PullLoop? = null
        var tethers: List<Tether> = emptyList()
        var tethersTick: Long = 0L
    }

    // Client thread only.
    private val views = Int2ObjectOpenHashMap<View>()
    private val flight = Vector3d()
    private val tetherA = Vector3d()
    private val tetherB = Vector3d()
    private val gridAlpha = IntArray((LAT_STEPS + 1) * (LON_STEPS + 1))

    /** Unit-sphere grid, [lat][lon] flattened, xyz per point. */
    private val unitSphere: FloatArray by lazy {
        val out = FloatArray((LAT_STEPS + 1) * (LON_STEPS + 1) * 3)
        var i = 0
        for (lat in 0..LAT_STEPS) {
            val phi = Math.PI * lat / LAT_STEPS
            for (lon in 0..LON_STEPS) {
                val theta = 2.0 * Math.PI * lon / LON_STEPS
                out[i++] = (sin(phi) * cos(theta)).toFloat()
                out[i++] = cos(phi).toFloat()
                out[i++] = (sin(phi) * sin(theta)).toFloat()
            }
        }
        out
    }

    fun init() {
        FusionRenderTypes.init()
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, FusionNetwork.S2C_BUBBLE) { buf, _ ->
            val wielderId = buf.readVarInt()
            val surface = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val center = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val pulling = buf.readBoolean()
            Minecraft.getInstance().execute { receive(wielderId, surface, center, pulling) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, FusionNetwork.S2C_TETHERS) { buf, _ ->
            val wielderId = buf.readVarInt()
            val tethers = List(buf.readVarInt()) {
                Tether(buf.readLong(), readVector(buf), buf.readLong(), readVector(buf))
            }
            Minecraft.getInstance().execute { receiveTethers(wielderId, tethers) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, FusionNetwork.S2C_WELD) { buf, _ ->
            val point = readVector(buf)
            Minecraft.getInstance().execute { burst(point) }
        }
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel(::tick))
    }

    fun clearSession() {
        views.values.forEach { it.removed = true }
        views.clear()
    }

    private fun receive(wielderId: Int, surface: Vec3, center: Vec3, pulling: Boolean) {
        val level = Minecraft.getInstance().level ?: return
        val now = level.gameTime
        val view = views.get(wielderId)
        if (view != null && view.center == center && view.releasedTick < 0L) {
            view.seenTick = now
            view.pulling = pulling
        } else {
            view?.removed = true
            val throwFrom = (level.getEntity(wielderId) as? Player)?.let(::staffTip) ?: surface
            val created = View(surface, center, throwFrom, now, now, pulling).also(::measureArc)
            views.put(wielderId, created)
            Minecraft.getInstance().soundManager.play(HumLoop(level, created))
        }
    }

    private fun receiveTethers(wielderId: Int, tethers: List<Tether>) {
        val view = views.get(wielderId) ?: return
        view.tethers = tethers
        view.tethersTick = Minecraft.getInstance().level?.gameTime ?: return
    }

    private fun readVector(buf: FriendlyByteBuf): Vector3d = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())

    private fun tick(level: ClientLevel) {
        if (views.isEmpty()) return
        val now = level.gameTime
        for (entry in views.int2ObjectEntrySet()) {
            val view = entry.value
            if (view.releasedTick >= 0L) continue
            if (now - view.seenTick > STALE_TICKS || !isWielding(level.getEntity(entry.intKey))) {
                view.releasedTick = now
                view.removed = true
            }
        }
        views.int2ObjectEntrySet().removeIf { entry ->
            val released = entry.value.releasedTick
            released >= 0L && now - released > COLLAPSE_TICKS
        }
        val sounds = Minecraft.getInstance().soundManager
        for (entry in views.int2ObjectEntrySet()) {
            val view = entry.value
            if (view.releasedTick >= 0L) continue
            val pull = view.pullLoop
            // A level change stops all sounds and does not call stop() on the instance.
            if (view.pulling && (pull == null || pull.isStopped || !sounds.isActive(pull))) {
                view.pullLoop = PullLoop(level, view).also(sounds::play)
            }
            if (now - view.tethersTick <= TETHER_STALE_TICKS) spawnTethers(level, view.tethers)
            val player = level.getEntity(entry.intKey) as? Player ?: continue
            val t = throwProgress(now - view.startTick + 0f)
            spawnStreams(level, player, flightPosition(view, t), flightRadius(t), now)
        }
    }

    /** Dust along the line between the nearest blocks of each neighbor pair. */
    private fun spawnTethers(level: ClientLevel, tethers: List<Tether>) {
        val ships = level.shipObjectWorld.allShips
        val random = level.random
        for (tether in tethers) {
            val shipA = ships.getById(tether.shipA) as? ClientShip ?: continue
            val shipB = ships.getById(tether.shipB) as? ClientShip ?: continue
            val a = shipA.renderTransform.shipToWorld.transformPosition(tetherA.set(tether.a))
            val b = shipB.renderTransform.shipToWorld.transformPosition(tetherB.set(tether.b))
            repeat(TETHER_PARTICLES) { _ ->
                val f = random.nextDouble()
                level.addParticle(DUST, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f, 0.0, 0.0, 0.0)
            }
        }
    }

    /** A puff of dust at a weld seam. Vanilla dust keeps almost none of a start velocity, so the
     *  puff fills a small sphere and does not fly outward. */
    private fun burst(point: Vector3d) {
        val level = Minecraft.getInstance().level ?: return
        val random = level.random
        repeat(BURST_PARTICLES) { _ ->
            val gx = random.nextGaussian()
            val gy = random.nextGaussian()
            val gz = random.nextGaussian()
            val scale = BURST_RADIUS * Math.cbrt(random.nextDouble()) / sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6)
            level.addParticle(BURST_DUST, point.x + gx * scale, point.y + gy * scale, point.z + gz * scale, 0.0, 0.0, 0.0)
        }
    }

    private fun throwProgress(ticks: Float): Double =
        (ticks / FusionManager.THROW_TICKS).toDouble().coerceIn(0.0, 1.0)

    private fun measureArc(view: View) {
        var px = view.throwFrom.x
        var py = view.throwFrom.y
        var pz = view.throwFrom.z
        for (i in 1..ARC_SAMPLES) {
            val p = bezier(view, i.toDouble() / ARC_SAMPLES)
            view.arc[i] = view.arc[i - 1] + sqrt((p.x - px) * (p.x - px) + (p.y - py) * (p.y - py) + (p.z - pz) * (p.z - pz))
            px = p.x
            py = p.y
            pz = p.z
        }
    }

    /** The point at time fraction [t] along the throw, at constant speed. A Bézier parameter
     *  alone is slow on the short rise after the surface. Returns the shared scratch vector. */
    private fun flightPosition(view: View, t: Double): Vector3d {
        val target = t * view.arc[ARC_SAMPLES]
        var i = 0
        while (i < ARC_SAMPLES - 1 && view.arc[i + 1] < target) i++
        val span = view.arc[i + 1] - view.arc[i]
        val frac = if (span > 1e-9) ((target - view.arc[i]) / span).coerceIn(0.0, 1.0) else 0.0
        return bezier(view, (i + frac) / ARC_SAMPLES)
    }

    /** The throw curve: a quadratic Bézier from the staff head, bent by the cast surface, to the
     *  resting center. Returns the shared scratch vector. */
    private fun bezier(view: View, u: Double): Vector3d {
        val a = (1.0 - u) * (1.0 - u)
        val b = 2.0 * (1.0 - u) * u
        val c = u * u
        return flight.set(
            a * view.throwFrom.x + b * view.surface.x + c * view.center.x,
            a * view.throwFrom.y + b * view.surface.y + c * view.center.y,
            a * view.throwFrom.z + b * view.surface.z + c * view.center.z,
        )
    }

    private fun flightRadius(t: Double): Double {
        val e = t * t
        return FusionManager.RADIUS * (THROW_START_SCALE + (1.0 - THROW_START_SCALE) * e)
    }

    private fun staffTip(player: Player): Vec3 {
        // Right-hand vector from yaw, so a straight-up look still has one; flipped for the other hand.
        val side = if ((player.usedItemHand == InteractionHand.MAIN_HAND) == (player.mainArm == HumanoidArm.RIGHT)) 1.0 else -1.0
        val yaw = Math.toRadians(player.yRot.toDouble())
        val look = player.lookAngle
        return Vec3(
            player.x + look.x * TIP_FORWARD - cos(yaw) * TIP_SIDE * side,
            player.eyeY + look.y * TIP_FORWARD - TIP_DROP,
            player.z + look.z * TIP_FORWARD - sin(yaw) * TIP_SIDE * side,
        )
    }

    /** Dust from the staff head to the bubble's rim as seen from the head: the circle where
     *  lines from the head touch the sphere. Inside the bubble, the streams go to random surface points. */
    private fun spawnStreams(level: ClientLevel, player: Player, center: Vector3d, radius: Double, now: Long) {
        if (radius <= 0.0) return
        val tip = staffTip(player)
        val tipX = tip.x
        val tipY = tip.y
        val tipZ = tip.z

        val ux = tipX - center.x
        val uy = tipY - center.y
        val uz = tipZ - center.z
        val dist = sqrt(ux * ux + uy * uy + uz * uz)
        val inside = dist <= radius * 1.05
        // Rim circle: center P = C + u·r²/d, radius ρ = r·√(d² − r²)/d, in the plane normal to u.
        val nx = ux / dist
        val ny = uy / dist
        val nz = uz / dist
        val px = center.x + nx * radius * radius / dist
        val py = center.y + ny * radius * radius / dist
        val pz = center.z + nz * radius * radius / dist
        val rho = if (inside) 0.0 else radius * sqrt(dist * dist - radius * radius) / dist
        // e1 = normalize(n × up), or n × x when n is vertical; e2 = n × e1.
        var e1x = -nz
        var e1y = 0.0
        var e1z = nx
        if (e1x * e1x + e1z * e1z < 1e-6) { e1x = 0.0; e1y = nz; e1z = -ny }
        val e1len = sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
        e1x /= e1len; e1y /= e1len; e1z /= e1len
        val e2x = ny * e1z - nz * e1y
        val e2y = nz * e1x - nx * e1z
        val e2z = nx * e1y - ny * e1x

        val random = level.random
        for (k in 0 until STREAMS) {
            val rimX: Double
            val rimY: Double
            val rimZ: Double
            if (inside) {
                val gx = random.nextGaussian()
                val gy = random.nextGaussian()
                val gz = random.nextGaussian()
                val glen = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6)
                rimX = center.x + gx / glen * radius
                rimY = center.y + gy / glen * radius
                rimZ = center.z + gz / glen * radius
            } else {
                val theta = now * STREAM_SPIN + k * 2.0 * Math.PI / STREAMS
                val c = cos(theta) * rho
                val s = sin(theta) * rho
                rimX = px + e1x * c + e2x * s
                rimY = py + e1y * c + e2y * s
                rimZ = pz + e1z * c + e2z * s
            }
            repeat(PARTICLES_PER_STREAM) { _ ->
                val f = random.nextDouble()
                level.addParticle(DUST, tipX + (rimX - tipX) * f, tipY + (rimY - tipY) * f, tipZ + (rimZ - tipZ) * f, 0.0, 0.0, 0.0)
            }
        }
    }

    /** On the bubble: it follows the throw, then stays at the centre. */
    private class HumLoop(level: ClientLevel, view: View) :
        BubbleLoop(level, view, EKSounds.FUSION_HUM_LOOP.get()) {

        override fun tick() {
            if (!isLive()) {
                fadeOut(HUM_FADE_OUT_TICKS)
                return
            }
            val elapsed = level.gameTime - view.startTick
            moveTo(flightPosition(view, throwProgress(elapsed.toFloat())))
            volume = (elapsed / FusionManager.THROW_TICKS).coerceIn(0f, 1f)
        }
    }

    private class PullLoop(level: ClientLevel, view: View) :
        BubbleLoop(level, view, EKSounds.FUSION_PULL_LOOP.get()) {

        init {
            moveTo(flight.set(view.center.x, view.center.y, view.center.z))
        }

        override fun tick() {
            if (!isLive() || !view.pulling) {
                fadeOut(PULL_FADE_TICKS)
                return
            }
            volume = (volume + 1f / PULL_FADE_TICKS).coerceAtMost(1f)
        }
    }

    private abstract class BubbleLoop(
        protected val level: ClientLevel,
        protected val view: View,
        event: SoundEvent,
    ) : AbstractTickableSoundInstance(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom()) {

        init {
            looping = true
            delay = 0
            volume = 0f
            moveTo(view.throwFrom.let { flight.set(it.x, it.y, it.z) })
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        protected fun isLive(): Boolean = !view.removed && Minecraft.getInstance().level === level

        protected fun fadeOut(ticks: Int) {
            volume -= 1f / ticks
            if (volume <= 0f) stop()
        }

        protected fun moveTo(position: Vector3d) {
            x = position.x
            y = position.y
            z = position.z
        }
    }

    private fun isWielding(entity: Entity?): Boolean =
        entity is Player && entity.isUsingItem && entity.useItem.item == EKItems.STAFF_OF_FUSION.get()

    fun renderAll(pose: PoseStack, consumers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, tickDelta: Float) {
        if (views.isEmpty()) return
        val level = Minecraft.getInstance().level ?: return
        // Without the shader, the bubble draws with no contact glow.
        val consumer = consumers.getBuffer(FusionRenderTypes.bubble() ?: AegisRenderType.FACE_GLOW)
        val matrix = pose.last().pose()
        for (entry in views.int2ObjectEntrySet()) {
            val view = entry.value
            val released = view.releasedTick >= 0L
            val t = throwProgress((if (released) view.releasedTick else level.gameTime) - view.startTick + if (released) 0f else tickDelta)
            val center = flightPosition(view, t)
            var radius = flightRadius(t)
            if (released) {
                // Collapses in place: slowly, then quickly, like the growth.
                val c = ((level.gameTime - view.releasedTick + tickDelta) / COLLAPSE_TICKS).toDouble().coerceIn(0.0, 1.0)
                radius *= 1.0 - c * c
                if (radius <= 0.0) continue
            }
            renderSphere(consumer, matrix, (center.x - camX).toFloat(), (center.y - camY).toFloat(), (center.z - camZ).toFloat(), radius.toFloat())
        }
    }

    /** Alpha rises where the surface turns edge-on to the camera, so the bubble reads as a rim. Each
     *  grid point is shared by four quads, so its alpha is computed once per frame. */
    private fun renderSphere(consumer: VertexConsumer, matrix: Matrix4f, cx: Float, cy: Float, cz: Float, radius: Float) {
        val sphere = unitSphere
        val alphas = gridAlpha
        for (p in alphas.indices) {
            val nx = sphere[p * 3]
            val ny = sphere[p * 3 + 1]
            val nz = sphere[p * 3 + 2]
            val x = cx + nx * radius
            val y = cy + ny * radius
            val z = cz + nz * radius
            val len = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-4f)
            val edge = 1f - abs(nx * x + ny * y + nz * z) / len
            alphas[p] = (FILL_ALPHA + RIM_ALPHA * edge * edge * edge).toInt().coerceIn(0, 255)
        }
        val row = LON_STEPS + 1
        for (lat in 0 until LAT_STEPS) {
            for (lon in 0 until LON_STEPS) {
                val i0 = lat * row + lon
                val i1 = (lat + 1) * row + lon
                vertex(consumer, matrix, i0, cx, cy, cz, radius)
                vertex(consumer, matrix, i0 + 1, cx, cy, cz, radius)
                vertex(consumer, matrix, i1 + 1, cx, cy, cz, radius)
                vertex(consumer, matrix, i1, cx, cy, cz, radius)
            }
        }
    }

    private fun vertex(consumer: VertexConsumer, matrix: Matrix4f, index: Int, cx: Float, cy: Float, cz: Float, radius: Float) {
        val sphere = unitSphere
        consumer.vertex(matrix, cx + sphere[index * 3] * radius, cy + sphere[index * 3 + 1] * radius, cz + sphere[index * 3 + 2] * radius)
            .color((COLOR shr 16) and 0xFF, (COLOR shr 8) and 0xFF, COLOR and 0xFF, gridAlpha[index])
            .endVertex()
    }
}
