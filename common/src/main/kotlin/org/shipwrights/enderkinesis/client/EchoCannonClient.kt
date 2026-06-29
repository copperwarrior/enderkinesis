package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.network.EchoCannonNetwork
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Client-side renderer + packet receiver for [EchoCannonNetwork.FIRE].
 *
 *  Each active fire holds the polyline trace (already reflected against
 *  Aegis boxes server-side) plus its remaining fade ticks. Per game
 *  tick the receiver spawns sparse `SONIC_BOOM` particles along every
 *  segment so the beam reads as a warden screech; per render frame it
 *  draws a thin blue-green wireframe outline + an additive bright-cyan
 *  interior glow (using [AegisRenderType.FACE_GLOW] for additive blend)
 *  around each segment with alpha that linearly decays to zero over
 *  [FADE_TICKS]. When the alpha hits zero the entry is dropped.
 *
 *  The wireframe is intentionally lighter than [SunderingClient]'s box —
 *  single layer, no rotation, no face glow on the outer faces. The
 *  cannon is a one-shot, so the visual reads as "afterimage of the
 *  shot" fading rather than a held ramp.
 */
object EchoCannonClient {

    /** Total fade window in client ticks. 10 ticks = 0.5 s per spec. */
    private const val FADE_TICKS: Int = 10
    /** SONIC_BOOM particles emitted per block of beam length per tick.
     *  Sparse on purpose — the particle is large and bright; a few
     *  per metre reads as a beam, more reads as a wall of screech. */
    private const val PARTICLES_PER_BLOCK: Double = 0.25
    /** Half-width of the wireframe cross-section. Thinner than the
     *  initial draft because the cannon is a sniper beam, not a wide
     *  cone. */
    private const val BOX_HALF_WIDTH: Double = 0.10
    /** Half-width of the additive interior glow. Sits inside the
     *  wireframe so the outline frames the bright cyan core. */
    private const val GLOW_HALF_WIDTH: Double = 0.07
    /** Outline RGB — cooler, slightly desaturated blue-green so the
     *  wireframe reads as a rim around the bright cyan interior. */
    private const val EDGE_R: Int = 0x4A
    private const val EDGE_G: Int = 0xE0
    private const val EDGE_B: Int = 0xC4
    /** Interior RGB — bright cyan (sculk sensor active palette), pushed
     *  through the [AegisRenderType.FACE_GLOW] additive shader so the
     *  beam's centre actually glows rather than just being a darker
     *  shade of the outline. */
    private const val GLOW_R: Int = 0x6F
    private const val GLOW_G: Int = 0xFF
    private const val GLOW_B: Int = 0xE5

    private data class Fire(
        val cannon: BlockPos,
        /** Host ship id, or 0 if world-placed. */
        val shipId: Long,
        /** Endpoints in ship-local coords if [shipId] != 0, world if not. */
        val segments: List<EchoCannonNetwork.Segment>,
        var ticksRemaining: Int,
    )

    private val active: CopyOnWriteArrayList<Fire> = CopyOnWriteArrayList()

    /** Register the S2C receiver. Called from `EnderkinesisModClient.init`. */
    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, EchoCannonNetwork.FIRE) { buf, _ ->
            val msg = EchoCannonNetwork.decode(buf)
            Minecraft.getInstance().execute {
                onFireReceived(msg)
            }
        }
    }

    /** Client-tick hook: advance fade timers, spawn screech particles
     *  along every active beam, drop fully-faded entries. Particles
     *  are spawned in WORLD coords each tick using the ship's current
     *  transform, so they appear at the actual world-visible beam
     *  position even as the host ship moves and rotates. */
    fun clientTick() {
        if (active.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: run {
            active.clear()
            return
        }
        val done = ArrayList<Fire>()
        for (fire in active) {
            val worldSegs = transformToWorld(level, fire)
            if (worldSegs == null) {
                // Host ship unloaded — drop the visual.
                done += fire
                continue
            }
            // Vanilla SONIC_BOOM screech — the only particle that
            // actually looks like the warden's beam (60-tick lifetime,
            // 16-frame sprite cycle, slow rise). Each tick of beam life
            // spawns a fresh row at the CURRENT ship-resolved beam
            // position; the particles in flight retain their world
            // anchor, so on a moving ship they trail slightly behind
            // the wireframe but the fresh wave is always at the right
            // place.
            for (seg in worldSegs) {
                val s = seg.start; val e = seg.end
                val len = s.distanceTo(e)
                if (len < 0.001) continue
                val steps = Math.max(1, (len * PARTICLES_PER_BLOCK).toInt())
                for (i in 0 until steps) {
                    val t = (i + 0.5) / steps.toDouble()
                    val px = s.x + (e.x - s.x) * t
                    val py = s.y + (e.y - s.y) * t
                    val pz = s.z + (e.z - s.z) * t
                    level.addParticle(
                        ParticleTypes.SONIC_BOOM,
                        px, py, pz,
                        0.0, 0.0, 0.0,
                    )
                }
            }
            fire.ticksRemaining--
            if (fire.ticksRemaining <= 0) done += fire
        }
        if (done.isNotEmpty()) active.removeAll(done)
    }

    /** Convert a fire's stored segments to current world coordinates.
     *  Returns null if the host ship is no longer loaded on this client. */
    private fun transformToWorld(
        level: net.minecraft.client.multiplayer.ClientLevel, fire: Fire,
    ): List<EchoCannonNetwork.Segment>? {
        if (fire.shipId == 0L) return fire.segments
        val ship = level.shipObjectWorld.allShips.getById(fire.shipId) ?: return null
        val s2w = ship.renderTransform.shipToWorld
        return fire.segments.map { seg ->
            val s = Vector3d(seg.start.x, seg.start.y, seg.start.z)
            val e = Vector3d(seg.end.x, seg.end.y, seg.end.z)
            s2w.transformPosition(s)
            s2w.transformPosition(e)
            EchoCannonNetwork.Segment(Vec3(s.x, s.y, s.z), Vec3(e.x, e.y, e.z))
        }
    }

    /** Per-frame render hook.
     *
     *  CRITICAL: do not interleave the two render types. The
     *  `MultiBufferSource.BufferSource` `getBuffer` implementation
     *  ENDS the previously-active buffer when you ask for a different
     *  one (so consecutive geometry into the same type can be
     *  consolidated). If you cache both consumers and alternate
     *  writes per segment, the second `getBuffer` call finalises the
     *  first buffer behind your back, then the next write into your
     *  stale reference scribbles into a no-longer-active builder —
     *  vertex elements stop advancing in lockstep and `endVertex`
     *  crashes with "Not filled all elements of the vertex." Hence the
     *  two distinct passes: every glow quad first, then every line
     *  edge. */
    fun renderAll(
        pose: PoseStack, consumers: MultiBufferSource,
        camX: Double, camY: Double, camZ: Double, tickDelta: Float,
    ) {
        if (active.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        // Build the world-space segment list per fire once, before
        // either render pass — both passes consume the same data, and
        // the ship transform doesn't change inside a single frame.
        val frameSegs: List<Pair<Float, List<EchoCannonNetwork.Segment>>> = active.mapNotNull { fire ->
            val frac = (fire.ticksRemaining - 1 + (1.0f - tickDelta)) / FADE_TICKS.toFloat()
            val a = frac.coerceIn(0f, 1f)
            if (a <= 0f) return@mapNotNull null
            val segs = transformToWorld(level, fire) ?: return@mapNotNull null
            a to segs
        }
        if (frameSegs.isEmpty()) return

        pose.pushPose()
        pose.translate(-camX, -camY, -camZ)
        val matrix = pose.last().pose()
        val normal = pose.last().normal()

        // Pass 1 — additive cyan interior glow into RenderType.lightning
        // (vanilla POSITION_COLOR additive shader, proven against every
        // BufferBuilder quirk).
        val glowConsumer = consumers.getBuffer(RenderType.lightning())
        for ((alpha01, segs) in frameSegs) {
            val glowAlpha = (255f * alpha01).toInt().coerceIn(0, 255)
            for (seg in segs) drawFilledGlow(glowConsumer, matrix, seg.start, seg.end, glowAlpha)
        }

        // Pass 2 — blue-green wireframe outline. Asking for the lines
        // buffer at this point finalises the lightning buffer cleanly;
        // every quad it received has already been written.
        val lineConsumer = consumers.getBuffer(RenderType.lines())
        for ((alpha01, segs) in frameSegs) {
            val lineAlpha = (255f * alpha01).toInt().coerceIn(0, 255)
            for (seg in segs) drawWireBox(lineConsumer, matrix, normal, seg.start, seg.end, lineAlpha)
        }

        pose.popPose()
    }

    private fun onFireReceived(msg: EchoCannonNetwork.FireMessage) {
        if (msg.segments.isEmpty()) return
        active += Fire(msg.cannon, msg.shipId, msg.segments, FADE_TICKS)
    }

    /** Four-quad hollow tube — the additive [AegisRenderType.FACE_GLOW]
     *  blends the four faces into a bright cyan column with brighter
     *  edges where two faces overlap. Same shape Aegis uses for its
     *  shield's face glow, dropped to a 4-quad rectangle so the
     *  perimeter does the work without needing a triangle strip. */
    private fun drawFilledGlow(
        consumer: VertexConsumer, matrix: Matrix4f,
        start: Vec3, end: Vec3, alpha: Int,
    ) {
        val dir = end.subtract(start)
        val len = dir.length()
        if (len < 0.001) return
        val axis = dir.scale(1.0 / len)
        val (right, up) = perpBasis(axis)
        val rh = GLOW_HALF_WIDTH
        val o00 = right.scale(-rh).add(up.scale(-rh))
        val o10 = right.scale( rh).add(up.scale(-rh))
        val o11 = right.scale( rh).add(up.scale( rh))
        val o01 = right.scale(-rh).add(up.scale( rh))
        // 4 long faces around the tube. POSITION_COLOR vertex format,
        // no normals required by FACE_GLOW.
        quad(consumer, matrix, start.add(o00), start.add(o10), end.add(o10), end.add(o00), alpha)
        quad(consumer, matrix, start.add(o10), start.add(o11), end.add(o11), end.add(o10), alpha)
        quad(consumer, matrix, start.add(o11), start.add(o01), end.add(o01), end.add(o11), alpha)
        quad(consumer, matrix, start.add(o01), start.add(o00), end.add(o00), end.add(o01), alpha)
    }

    private fun quad(
        consumer: VertexConsumer, matrix: Matrix4f,
        a: Vec3, b: Vec3, c: Vec3, d: Vec3, alpha: Int,
    ) {
        consumer.vertex(matrix, a.x.toFloat(), a.y.toFloat(), a.z.toFloat())
            .color(GLOW_R, GLOW_G, GLOW_B, alpha).endVertex()
        consumer.vertex(matrix, b.x.toFloat(), b.y.toFloat(), b.z.toFloat())
            .color(GLOW_R, GLOW_G, GLOW_B, alpha).endVertex()
        consumer.vertex(matrix, c.x.toFloat(), c.y.toFloat(), c.z.toFloat())
            .color(GLOW_R, GLOW_G, GLOW_B, alpha).endVertex()
        consumer.vertex(matrix, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
            .color(GLOW_R, GLOW_G, GLOW_B, alpha).endVertex()
    }

    /** Outline wireframe: 4 long edges + 8 cap edges (the 4 sides of
     *  each end square). Skips end-cap diagonals since the box is so
     *  thin the cap reads as a small square either way. */
    private fun drawWireBox(
        consumer: VertexConsumer, matrix: Matrix4f, normal: org.joml.Matrix3f,
        start: Vec3, end: Vec3, alpha: Int,
    ) {
        val dir = end.subtract(start)
        val len = dir.length()
        if (len < 0.001) return
        val axis = dir.scale(1.0 / len)
        val (right, up) = perpBasis(axis)
        val rh = BOX_HALF_WIDTH
        val o00 = right.scale(-rh).add(up.scale(-rh))
        val o10 = right.scale( rh).add(up.scale(-rh))
        val o11 = right.scale( rh).add(up.scale( rh))
        val o01 = right.scale(-rh).add(up.scale( rh))
        line(consumer, matrix, normal, start.add(o00), end.add(o00), axis, alpha)
        line(consumer, matrix, normal, start.add(o10), end.add(o10), axis, alpha)
        line(consumer, matrix, normal, start.add(o11), end.add(o11), axis, alpha)
        line(consumer, matrix, normal, start.add(o01), end.add(o01), axis, alpha)
        line(consumer, matrix, normal, start.add(o00), start.add(o10), right, alpha)
        line(consumer, matrix, normal, start.add(o10), start.add(o11), up, alpha)
        line(consumer, matrix, normal, start.add(o11), start.add(o01), right.scale(-1.0), alpha)
        line(consumer, matrix, normal, start.add(o01), start.add(o00), up.scale(-1.0), alpha)
        line(consumer, matrix, normal, end.add(o00), end.add(o10), right, alpha)
        line(consumer, matrix, normal, end.add(o10), end.add(o11), up, alpha)
        line(consumer, matrix, normal, end.add(o11), end.add(o01), right.scale(-1.0), alpha)
        line(consumer, matrix, normal, end.add(o01), end.add(o00), up.scale(-1.0), alpha)
    }

    private fun line(
        consumer: VertexConsumer, matrix: Matrix4f, normal: org.joml.Matrix3f,
        a: Vec3, b: Vec3, dir: Vec3, alpha: Int,
    ) {
        val dx = (b.x - a.x).toFloat()
        val dy = (b.y - a.y).toFloat()
        val dz = (b.z - a.z).toFloat()
        consumer.vertex(matrix, a.x.toFloat(), a.y.toFloat(), a.z.toFloat())
            .color(EDGE_R, EDGE_G, EDGE_B, alpha)
            .normal(normal, dx, dy, dz)
            .endVertex()
        consumer.vertex(matrix, b.x.toFloat(), b.y.toFloat(), b.z.toFloat())
            .color(EDGE_R, EDGE_G, EDGE_B, alpha)
            .normal(normal, dx, dy, dz)
            .endVertex()
    }

    private fun perpBasis(axis: Vec3): Pair<Vec3, Vec3> {
        val helper = if (Math.abs(axis.y) < 0.999) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val right = axis.cross(helper).normalize()
        val up = right.cross(axis).normalize()
        return right to up
    }
}
