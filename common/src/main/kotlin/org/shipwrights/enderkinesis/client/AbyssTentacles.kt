package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.client.signature.softGlowBuffer
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Writhing Ygann tentacles from the palm into the Incantation of the Abyss cloud: black cores with violet rims. */
object AbyssTentacles {
    const val COUNT = 3
    private const val SEGMENTS = 24

    /** Rim before core, as the Supplication draws glow before shade, so the core darkens the middle of the rim. */
    private const val RIM_RGB = 0x5B22C4
    private const val RIM_ALPHA = 0.7f
    private const val CORE_RGB = 0x07000C
    private const val CORE_ALPHA = 0.97f
    private const val CORE_SHARE = 0.85

    private const val TIP_RADIUS = 0.05
    private const val ROOT_RADIUS = 0.24
    private const val TAPER_POWER = 1.4

    /** The last share of the length fades out inside the cloud. */
    private const val FADE_FROM = 0.8

    private const val BOW = 1.2
    private const val WRITHE = 0.45
    private const val REACH = 1.6
    private const val RAMP_TICKS = 5f

    /** Where each tentacle ends, round the cloud centre, in the cast's side axes. */
    private val AIM_SIDE = doubleArrayOf(1.0, -0.9, 0.3)
    private val AIM_UP = doubleArrayOf(0.6, 0.9, -1.2)
    private val PHASE = DoubleArray(COUNT) { it * 2.1 }

    /** A point on tentacle [k] at share [s] of its length. Shared by the draw and the client motes. */
    fun point(k: Int, s: Double, age: Float, palm: Vec3, centre: Vec3): Vec3 {
        val axis = centre.subtract(palm)
        val (side, up) = SpellGeom.basis(axis.normalize())
        val end = centre.add(side.scale(AIM_SIDE[k] * REACH)).add(up.scale(AIM_UP[k] * REACH))
        val ph = PHASE[k]
        val ctrl = palm.lerp(end, 0.5).add(side.scale(BOW * sin(ph))).add(up.scale(BOW * cos(ph)))
        val a = 1 - s
        val wr = WRITHE * sin(s * Math.PI)
        return palm.scale(a * a).add(ctrl.scale(2 * a * s)).add(end.scale(s * s))
            .add(side.scale(wr * sin(s * 7 - age * 0.22 + ph)))
            .add(up.scale(wr * cos(s * 5.3 - age * 0.18 + ph * 1.7)))
    }

    /** Call in the spell pass: the pose is camera-relative world. */
    @Suppress("LongParameterList") // Same hook signature as the other spell-pass renderers.
    fun render(pose: PoseStack, buffers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, partialTick: Float) {
        val level = Minecraft.getInstance().level ?: return
        val cam = Vec3(camX, camY, camZ)
        var drew = false
        for (player in level.players()) {
            if (!AbyssDepthVolume.isCastingAbyss(player)) continue
            val centre = AbyssDepthVolume.centreOf(player.id) ?: continue
            val palm = IncantationOfWindsClient.castingPalm(player, partialTick).position
            val age = player.ticksUsingItem + partialTick
            val on = minOf(1f, age / RAMP_TICKS)
            val tentacles = List(COUNT) { k -> pieces(k, age, palm, centre, cam) }
            val m = pose.last().pose()
            val glow = softGlowBuffer(buffers, SpellClock.time(level, partialTick)) ?: buffers.getBuffer(AegisRenderType.FACE_GLOW)
            for (t in tentacles) SpellTube.tube(glow, m, Vec3.ZERO, t.pieces, t::radius, RIM_RGB, RIM_ALPHA * on, alphaAt = t::fade)
            val shade = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
            for (t in tentacles) {
                SpellTube.tube(
                    shade, m, Vec3.ZERO, t.pieces, { t.radius(it) * CORE_SHARE }, CORE_RGB, CORE_ALPHA * on,
                    dark = true, alphaAt = t::fade,
                )
            }
            drew = true
        }
        if (drew) (buffers as? MultiBufferSource.BufferSource)?.endLastBatch()
    }

    private class Tentacle(val pieces: List<SpellTube.Piece>, val length: Double) {
        fun radius(dist: Double): Double = TIP_RADIUS + ROOT_RADIUS * (dist / length).coerceIn(0.0, 1.0).pow(TAPER_POWER)
        fun fade(dist: Double): Float = (1.0 - ((dist / length - FADE_FROM) / (1.0 - FADE_FROM)).coerceIn(0.0, 1.0)).toFloat()
    }

    private fun pieces(k: Int, age: Float, palm: Vec3, centre: Vec3, cam: Vec3): Tentacle {
        val out = ArrayList<SpellTube.Piece>(SEGMENTS)
        var prev = point(k, 0.0, age, palm, centre)
        var from = 0.0
        for (i in 1..SEGMENTS) {
            val next = point(k, i.toDouble() / SEGMENTS, age, palm, centre)
            val step = next.subtract(prev)
            val len = step.length()
            if (len > 1.0e-4) {
                out += SpellTube.Piece(prev.subtract(cam), step.scale(1.0 / len), len, from)
                from += len
            }
            prev = next
        }
        return Tentacle(out, from.coerceAtLeast(1.0e-3))
    }
}
