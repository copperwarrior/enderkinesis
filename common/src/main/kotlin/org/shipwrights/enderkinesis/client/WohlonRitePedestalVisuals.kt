package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.WohlonCandleRites
import org.shipwrights.enderkinesis.entity.WohlonRitePedestal
import java.util.WeakHashMap

/** The pedestal chalice: tendrils rise from the candle flame and flare out to hold the sacrifice. Render thread only. */
object WohlonRitePedestalVisuals {

    private const val TENDRILS = 5
    private const val MAX_QUEUED = 8
    private const val FLAME_Y = 0.45
    private const val STEM_RADIUS = 0.08
    private const val CRADLE_RADIUS = 0.3
    private const val STEM_HEIGHT = 0.95
    private const val TWIST = 0.9
    private const val WILD = 0.06
    private const val FEED_RADIUS = 0.05f
    private const val FEED_WILD = 0.4
    private const val FEED_ARC = 1.5
    private const val FEED_SWAY = 0.7
    private const val FEED_DRIFT_RATE = 0.35
    private const val FEED_SWAY_RATE = 0.5
    private const val FEED_STRENGTH = 1f
    private const val FEED_GROW_TICKS = 30f
    // Used until the ghost's shape is ready: the cradle lift plus about half a staff.
    private const val FEED_END_Y = WohlonRitePedestal.CRADLE_HEIGHT + 0.75
    private const val FEED_END_RADIUS = 0.12

    private val STYLE = TendrilStyle(0.07f, WohlonRitualTendrils.CANDLE_COLOUR, WohlonRitualTendrils.HEART_COLOUR)

    /** A candle tendril; its control and end points drift over the ghost each frame, like smoke. */
    private class Feed(val curve: TendrilCurve, val style: TendrilStyle, val phase: Double)

    private class Shape(val frame: TendrilFrame, val curves: List<TendrilCurve>) {
        /** Null until the gem is in; empty when no gem candle ring matches. */
        var feed: List<Feed>? = null
        var feedStartTick = 0
    }

    private val span = DoubleArray(2)

    private val shapes = WeakHashMap<WohlonRitePedestal, Shape>()
    private val queuedShapes = arrayOfNulls<Shape>(MAX_QUEUED)
    private val queuedReach = DoubleArray(MAX_QUEUED)
    private val queuedStrength = FloatArray(MAX_QUEUED)
    private val queuedFeedReach = DoubleArray(MAX_QUEUED)
    private var queued = 0

    /** Called from the entity renderer; the volume pass at the end of the frame draws the queue. */
    fun queue(pedestal: WohlonRitePedestal, partialTick: Float) {
        if (queued >= MAX_QUEUED) return
        val shape = shapes.getOrPut(pedestal) { build(pedestal) }
        // The entity pass can run twice in a frame, as with a shadow pass.
        for (i in 0 until queued) if (queuedShapes[i] === shape) return
        val t = pedestal.tickCount + partialTick
        val rise = (t / WohlonRitePedestal.RISE_TICKS).coerceIn(0f, 1f)
        val closing = pedestal.closingTick
        queuedShapes[queued] = shape
        queuedReach[queued] = 1.0 - (1.0 - rise) * (1.0 - rise)
        queuedStrength[queued] = if (closing < 0) 1f else (1f - (t - closing) / WohlonRitePedestal.CLOSE_TICKS).coerceIn(0f, 1f)
        queuedFeedReach[queued] = feedReach(pedestal, shape, t)
        queued++
    }

    private fun build(pedestal: WohlonRitePedestal): Shape {
        val x = pedestal.x
        val y = pedestal.y
        val z = pedestal.z
        val curves = List(TENDRILS) { i ->
            val a = i * Math.PI * 2.0 / TENDRILS
            TendrilCurve(
                Vector3d(x + Math.cos(a) * 0.05, y + FLAME_Y, z + Math.sin(a) * 0.05),
                Vector3d(x + Math.cos(a + TWIST * 0.5) * STEM_RADIUS, y + STEM_HEIGHT, z + Math.sin(a + TWIST * 0.5) * STEM_RADIUS),
                Vector3d(x + Math.cos(a + TWIST) * CRADLE_RADIUS, y + WohlonRitePedestal.CRADLE_HEIGHT, z + Math.sin(a + TWIST) * CRADLE_RADIUS),
                wild = WILD,
                seed = pedestal.id * 7.31 + i * 13.7,
            )
        }
        return Shape(TendrilFrame(Vector3d(x, y + WohlonRitePedestal.CRADLE_HEIGHT * 0.5, z)), curves)
    }

    private fun feedReach(pedestal: WohlonRitePedestal, shape: Shape, t: Float): Double {
        if (shape.feed == null) {
            if (pedestal.staffResult.isEmpty) return 0.0
            shape.feed = buildFeed(pedestal)
            shape.feedStartTick = pedestal.tickCount
        }
        shape.feed?.let { drift(pedestal, it) }
        return ((t - shape.feedStartTick) / FEED_GROW_TICKS).coerceIn(0f, 1f).toDouble()
    }

    private fun drift(pedestal: WohlonRitePedestal, feed: List<Feed>) {
        val base = pedestal.y + WohlonStaffGhost.BASE_HEIGHT
        if (!WohlonStaffGhost.woodSpan(pedestal.staffResult.item, span)) {
            span[0] = FEED_END_Y - WohlonStaffGhost.BASE_HEIGHT
            span[1] = span[0]
        }
        val sec = System.nanoTime() * 1e-9
        for (f in feed) {
            val p0 = f.curve.p0
            val rate = FEED_DRIFT_RATE * (0.7 + 0.6 * Math.sin(f.phase * 3.1))
            val endY = base + span[0] + (span[1] - span[0]) * (0.5 + 0.5 * Math.sin(sec * rate + f.phase))
            val a = sec * rate * 1.3 + f.phase * 2.0
            f.curve.p2.set(pedestal.x + Math.cos(a) * FEED_END_RADIUS, endY, pedestal.z + Math.sin(a) * FEED_END_RADIUS)
            // The arc sways sideways to the line from the candle, so the tendril curls as it climbs.
            val dx = f.curve.p2.x - p0.x
            val dz = f.curve.p2.z - p0.z
            val len = Math.hypot(dx, dz).coerceAtLeast(1e-3)
            val sway = Math.sin(sec * FEED_SWAY_RATE + f.phase * 1.7) * FEED_SWAY
            f.curve.p1.set(
                (p0.x + f.curve.p2.x) * 0.5 - dz / len * sway,
                maxOf(p0.y, endY) + FEED_ARC * (0.8 + 0.2 * Math.sin(sec * FEED_SWAY_RATE * 0.7 + f.phase)),
                (p0.z + f.curve.p2.z) * 0.5 + dx / len * sway,
            )
        }
    }

    // The client does not know the ring's rotation, so it finds the one whose gem candles stand.
    private fun buildFeed(pedestal: WohlonRitePedestal): List<Feed> {
        val level = pedestal.level()
        val center = BlockPos.containing(pedestal.position())
        val gemSlots = WohlonCandleRites.Rite.STAFF.rotations
            .map { rotation -> rotation.filter { it.candle != Blocks.BLUE_CANDLE } }
            .firstOrNull { slots -> slots.all { level.getBlockState(center.offset(it.dx, 0, it.dz)).`is`(it.candle) } }
            ?: return emptyList()
        val gem = rgb(pedestal.staffColour)
        return gemSlots.mapIndexed { i, slot ->
            val curve = TendrilCurve(
                Vector3d(center.x + slot.dx + 0.5, pedestal.y + FLAME_Y, center.z + slot.dz + 0.5),
                Vector3d(),
                Vector3d(),
                wild = FEED_WILD,
                seed = pedestal.id * 3.17 + i * 19.3,
            )
            Feed(curve, TendrilStyle(FEED_RADIUS, rgb(slot.candle.defaultMapColor().col), gem), i * 2.39 + pedestal.id * 0.61)
        }
    }

    private fun rgb(colour: Int) = floatArrayOf(
        (colour shr 16 and 0xFF) / 255f, (colour shr 8 and 0xFF) / 255f, (colour and 0xFF) / 255f,
    )

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (queued == 0) return
        if (TendrilVolume.begin(poseStack, projection, camera)) {
            for (i in 0 until queued) {
                val shape = queuedShapes[i] ?: continue
                for (curve in shape.curves) TendrilVolume.draw(shape.frame, curve, queuedReach[i], queuedStrength[i], STYLE)
                val feedStrength = queuedStrength[i] * FEED_STRENGTH
                shape.feed?.forEach { TendrilVolume.draw(shape.frame, it.curve, queuedFeedReach[i], feedStrength, it.style) }
            }
            TendrilVolume.end()
        }
        queuedShapes.fill(null)
        queued = 0
    }
}
