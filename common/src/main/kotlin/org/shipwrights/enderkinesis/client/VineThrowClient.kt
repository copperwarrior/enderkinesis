package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.player.Player
import org.joml.Vector3d
import org.shipwrights.enderkinesis.vine.ThrowFlight
import org.shipwrights.enderkinesis.vine.VINE_TARGET_SPACING
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_THROW_END
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_THROW_START
import org.shipwrights.enderkinesis.vine.clientVineHold
import org.shipwrights.enderkinesis.vine.handPoint
import org.shipwrights.enderkinesis.vine.stepFlight
import kotlin.math.sqrt

/** Rule 2 backstop: a landed flight hands over to its vine after this many ticks, even with no frame of the let-go. */
private const val MAX_LANDING_TICKS = 40
/** Rule 2 cap on flights at one time; a full list drops the oldest. */
private const val MAX_FLIGHTS = 32
/** A shorter last link than this joins the link before it. */
private const val MIN_LAST_LINK = 0.05
private const val MIN_ROPE = 1e-3
/** The drawn vine goes from the last stand-in shape to the real chain over this time, so the hand-off shows no jump. */
private const val HANDOFF_BLEND_NANOS = 150e6

/**
 * Flies the thrown tips from the server's start packets, and draws a stand-in rope for the whole flight. The real
 * chain grows by a node each physics tick but comes in a few frames a second, so it only shows from its let-go frame.
 */
object VineThrowClient {
    class Flight(val id: Long, val chain: Long, val throwerId: Int, val side: Double, val flight: ThrowFlight) {
        internal val next = Vector3d()
        internal val previousTip = Vector3d(flight.tip)
        /** Set by the server's end packet. */
        internal var serverEnded = false
        /** Set once the vine is drawn at or after its let-go; the stand-in rope stops then. */
        internal var shown = false
        /** The first chain frame tick that shows the let-go, from the end packet. */
        internal var releaseTick = Long.MAX_VALUE
        internal var landingTicks = 0
    }

    val flights = ArrayList<Flight>()
    /** The last [sampleRope] result, xyz per node. */
    var rope = DoubleArray(0)
        private set
    private var raw = DoubleArray(0)
    private val hidden = LongOpenHashSet()
    private val blends = Long2ObjectOpenHashMap<Blend>()

    /** A landed flight's last stand-in nodes, with their arc-length share from the hand. */
    private class Blend(val points: DoubleArray, val count: Int, val start: Long) {
        val along = DoubleArray(count)

        init {
            var total = 0.0
            for (i in 1 until count) {
                total += distance(points, i - 1, i)
                along[i] = total
            }
            if (total > 0.0) for (i in 1 until count) along[i] /= total
        }
    }

    /** True while the real vine of [chain] stays hidden behind a flight's stand-in rope. */
    fun hides(chain: Long): Boolean = hidden.contains(chain)

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOGGAK_VINE_THROW_START) { buf, ctx ->
            val id = buf.readLong()
            val chain = buf.readLong()
            val thrower = buf.readVarInt()
            val side = buf.readByte().toDouble()
            val start = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val velocity = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val length = buf.readDouble()
            require(start.isFinite && velocity.isFinite && length > 0 && length.isFinite()) { "Bad Woggak Vine throw $id" }
            ctx.queue { begin(Flight(id, chain, thrower, side, ThrowFlight(start, velocity, length))) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOGGAK_VINE_THROW_END) { buf, ctx ->
            val id = buf.readLong()
            val chain = buf.readLong()
            val releaseTick = buf.readLong()
            ctx.queue { finish(id, chain, releaseTick) }
        }
    }

    private fun begin(flight: Flight) {
        if (flights.any { it.id == flight.id }) return
        if (flights.size >= MAX_FLIGHTS) drop(flights.removeAt(0))
        flights.add(flight)
        hidden.add(flight.chain)
    }

    private fun finish(id: Long, chain: Long, releaseTick: Long) {
        val flight = flights.firstOrNull { it.id == id } ?: return
        flight.serverEnded = true
        flight.releaseTick = releaseTick
        flight.flight.end()
        val player = Minecraft.getInstance().player
        // The thrower passes through the vine that lands round it, as after a let-go.
        if (chain != 0L && player != null && player.id == flight.throwerId) clientVineHold.startGrace(chain)
    }

    fun clear() {
        flights.clear()
        hidden.clear()
        blends.clear()
    }

    private fun drop(f: Flight) {
        hidden.remove(f.chain)
        WoggakVineRenderer.forgetFlight(f.id)
    }

    private fun handOver(level: ClientLevel, f: Flight) {
        val count = sampleRope(level, f, 1f)
        // Rule 2: a vine that went before it drew leaves its blend; a full map starts again.
        if (blends.size >= MAX_FLIGHTS) blends.clear()
        if (count >= 2) blends.put(f.chain, Blend(rope.copyOf(count * 3), count, System.nanoTime()))
        f.shown = true
        drop(f)
    }

    /**
     * Mixes the last stand-in shape into the [count] drawn nodes of [chain] in [out] for a short time after the hand-off.
     * Each node takes the stand-in point at its own arc-length share, as the two have other node counts.
     */
    fun blendInto(chain: Long, out: DoubleArray, count: Int) {
        if (blends.isEmpty() || count < 2) return
        val b = blends.get(chain) ?: return
        val left = 1.0 - (System.nanoTime() - b.start) / HANDOFF_BLEND_NANOS
        if (left <= 0.0) {
            blends.remove(chain)
            return
        }
        val w = left * left * (3.0 - 2.0 * left)
        var total = 0.0
        for (i in 1 until count) total += distance(out, i - 1, i)
        if (total < MIN_ROPE) return
        var along = 0.0
        var px = out[0]; var py = out[1]; var pz = out[2]
        var j = 0
        for (i in 0 until count) {
            val k = i * 3
            val x = out[k]; val y = out[k + 1]; val z = out[k + 2]
            if (i > 0) {
                val dx = x - px; val dy = y - py; val dz = z - pz
                along += sqrt(dx * dx + dy * dy + dz * dz)
            }
            px = x; py = y; pz = z
            val share = along / total
            // share only grows, so j only moves forward and stays under b.count.
            while (j < b.count - 2 && b.along[j + 1] < share) j++
            val span = b.along[j + 1] - b.along[j]
            val s = if (span > 0.0) ((share - b.along[j]) / span).coerceIn(0.0, 1.0) else 0.0
            val a = j * 3
            for (c in 0 until 3) {
                val target = b.points[a + c] + (b.points[a + 3 + c] - b.points[a + c]) * s
                out[k + c] += (target - out[k + c]) * w
            }
        }
    }

    fun tick(level: ClientLevel) {
        if (flights.isEmpty()) return
        val local = Minecraft.getInstance().player ?: return
        val iterator = flights.iterator()
        while (iterator.hasNext()) {
            val f = iterator.next()
            if (!f.shown && f.serverEnded && WoggakVineRenderer.isDrawable(level, f.chain) &&
                WoggakVineRenderer.showsTick(f.chain, f.releaseTick)
            ) handOver(level, f)
            // The vine comes out of the thrower's hand, so the thrower passes through it while it pays out.
            if (!f.serverEnded && local.id == f.throwerId) clientVineHold.startGrace(f.chain)
            if (!f.shown) {
                f.previousTip.set(f.flight.tip)
                if (!f.flight.ended) level.stepFlight(f.flight, level.getEntity(f.throwerId) ?: local, f.next)
            }
            if (f.serverEnded && (f.shown || ++f.landingTicks > MAX_LANDING_TICKS)) {
                iterator.remove()
                drop(f)
            }
        }
    }

    /**
     * The drawn rope of [f] at [partialTick] into [rope]: from the thrower's hand along the path to the tip, in links of
     * the vine spacing from the hand, so the leaves stay in place as the rope grows. Returns the node count; 0 once the
     * real vine shows.
     */
    fun sampleRope(level: ClientLevel, f: Flight, partialTick: Float): Int {
        if (f.shown) return 0
        val points = f.flight.points
        val n = maxOf(f.flight.count, 2)
        if (raw.size < n * 3) raw = DoubleArray(n * 3)
        val thrower = level.getEntity(f.throwerId) as? Player
        if (thrower != null) {
            val hand = handPoint(
                thrower.getEyePosition(partialTick), thrower.getViewVector(partialTick), thrower.getViewYRot(partialTick), f.side,
            )
            raw[0] = hand.x; raw[1] = hand.y; raw[2] = hand.z
        } else {
            raw[0] = points[0]; raw[1] = points[1]; raw[2] = points[2]
        }
        System.arraycopy(points, 3, raw, 3, (n - 2) * 3)
        val k = (n - 1) * 3
        val t = partialTick.toDouble()
        val tip = f.flight.tip
        raw[k] = f.previousTip.x + (tip.x - f.previousTip.x) * t
        raw[k + 1] = f.previousTip.y + (tip.y - f.previousTip.y) * t
        raw[k + 2] = f.previousTip.z + (tip.z - f.previousTip.z) * t
        return resample(n)
    }

    /** The [n] raw points as nodes [VINE_TARGET_SPACING] apart from the first, and the last point; into [rope]. */
    private fun resample(n: Int): Int {
        var total = 0.0
        for (i in 0 until n - 1) total += segment(i)
        if (total < MIN_ROPE) return 0
        val nodes = (total / VINE_TARGET_SPACING).toInt() + 2
        if (rope.size < nodes * 3) rope = DoubleArray(nodes * 3)
        var count = 0
        var nextAt = 0.0
        var along = 0.0
        for (i in 0 until n - 1) {
            val length = segment(i)
            // Each segment takes the nodes that fall in it; nextAt grows by one spacing per node, so this ends.
            while (nextAt <= along + length && nextAt < total - MIN_LAST_LINK) {
                val s = if (length > 0.0) (nextAt - along) / length else 0.0
                val a = i * 3
                rope[count * 3] = raw[a] + (raw[a + 3] - raw[a]) * s
                rope[count * 3 + 1] = raw[a + 1] + (raw[a + 4] - raw[a + 1]) * s
                rope[count * 3 + 2] = raw[a + 2] + (raw[a + 5] - raw[a + 2]) * s
                count++
                nextAt += VINE_TARGET_SPACING
            }
            along += length
        }
        val last = (n - 1) * 3
        rope[count * 3] = raw[last]; rope[count * 3 + 1] = raw[last + 1]; rope[count * 3 + 2] = raw[last + 2]
        return count + 1
    }

    private fun segment(i: Int): Double = distance(raw, i, i + 1)
}

private fun distance(p: DoubleArray, a: Int, b: Int): Double {
    val dx = p[b * 3] - p[a * 3]; val dy = p[b * 3 + 1] - p[a * 3 + 1]; val dz = p[b * 3 + 2] - p[a * 3 + 2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}
