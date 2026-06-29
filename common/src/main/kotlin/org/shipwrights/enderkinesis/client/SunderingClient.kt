package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import org.shipwrights.enderkinesis.item.SunderingManager
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKParticles

/** Client-side renderer for the Staff of Sundering.
 *
 *  Two hooks:
 *   - **Per-tick** ([ClientTickEvent.CLIENT_LEVEL_POST]) spawns every
 *     particle the staff produces — stage-1 ender wisps
 *     ([SunderingBeamParticle], vanilla portal sprite, warm-orange tint,
 *     constant forward velocity) and stage-2+ fire ring / stage-4 spiral
 *     fire particles ([SunderingFireParticle], vanilla flame sprite, no
 *     motion, ~5-tick lifetime). The rings' visible rotation comes from
 *     the per-tick respawn at advancing angular slot positions —
 *     `baseAngle = gameTime × rate × direction`, so each tick the spawn
 *     positions shift by `rate` rad, and the short-lived particles' tail
 *     reads as an orbit.
 *   - **Per-frame** ([renderAll]) draws the **custom box beam** that
 *     replaces the beacon. The box's perpendicular half-extents scale with
 *     `t23` (so it grows radially outward from the centre ray), its
 *     cross-section is rectangular (so the per-frame rotation around the
 *     beam axis is visibly readable rather than masked by 90° symmetry),
 *     and it's rendered aegis-shield-style — wireframe outline + 4
 *     trapezoidal face-glow strips per face. */
object SunderingClient {

    /** Face-glow colour. Stays orange #f3a02f across all stages; only the
     *  wireframe shifts toward white at stage 4. */
    private val ORANGE_R: Int = 0xF3
    private val ORANGE_G: Int = 0xA0
    private val ORANGE_B: Int = 0x2F
    private val WHITE_R: Int = 0xFF
    private val WHITE_G: Int = 0xFD
    private val WHITE_B: Int = 0xF1

    private const val PARTICLE_BEAM_MAX_PER_TICK: Int = 12
    private const val PARTICLE_SPEED_PER_TICK: Double = 0.22
    private const val PARTICLE_BEAM_SPREAD: Double = 0.10

    /** Number of angular slots spawned per tick per ring. With 5-tick
     *  particle lifetime this yields a steady-state cluster equal to the
     *  slot count × lifetime around the ring, with the leading slot at
     *  the current angle and the oldest slot `5 × rate` rad behind. */
    private const val INNER_RING_SLOTS: Int = 14
    private const val OUTER_RING_SLOTS: Int = 22
    private const val INNER_RING_RADIUS: Double = 0.45
    private const val OUTER_RING_RADIUS: Double = 0.80
    /** Angular speed in rad/tick — bumped over the prior pass: the previous
     *  0.22 / 0.16 read as too languid; these (~0.4 / 0.32) give ~1.3 / 1.0
     *  rev/sec on the inner / outer ring, clearly readable rotation without
     *  being a strobe blur. */
    private const val INNER_RING_RATE: Double = 0.40
    private const val OUTER_RING_RATE: Double = 0.32

    /** Per-spawn size multipliers passed via the [SunderingFireParticle]
     *  `vx`-slot smuggle. Inner < outer (per design ask: inner-ring
     *  particles smaller than outer-ring). Spiral sits between the two. */
    private const val INNER_RING_PARTICLE_SIZE: Double = 0.30
    private const val OUTER_RING_PARTICLE_SIZE: Double = 0.50
    private const val SPIRAL_PARTICLE_SIZE: Double = 0.40

    /** Radius of the SGA glyph ring — sits between the inner CW fire ring
     *  ([INNER_RING_RADIUS]) and the outer CCW fire ring
     *  ([OUTER_RING_RADIUS]) so all three rings read as concentric layers. */
    private const val GLYPH_RING_RADIUS: Double = 0.62

    /** Angular speed (rad/tick). Sign is negated in the spawn / track math
     *  so the ring orbits **counterclockwise** (opposite the inner CW
     *  ring, same direction as the outer CCW one). */
    private const val GLYPH_RING_RATE: Double = 0.25

    /** SGA sprite indices for "SUNDER" — `a=0, b=1, …, z=25`. */
    private val SUNDER_LETTERS: IntArray = intArrayOf(18, 20, 13, 3, 4, 17)   // S, U, N, D, E, R

    /** Forward push (along the beam) for the **inner CW fire ring**. Shifts
     *  the ring centre slightly past the beam origin so the inner ring and
     *  the glyph ring don't share the same orbit plane. */
    private const val INNER_RING_FORWARD_OFFSET: Double = 0.2

    /** Forward push (along the beam) for the **SUNDER glyph ring**. Half
     *  the inner-ring offset, so the glyph ring sits between the origin
     *  and the inner fire ring along the beam axis. */
    private const val GLYPH_RING_FORWARD_OFFSET: Double = 0.1

    /** Distance along the beam between spiral spawn slots. Larger spacing
     *  keeps the particle count manageable at full beam length. */
    private const val SPIRAL_SPACING: Double = 1.0
    private const val SPIRAL_RADIUS: Double = 0.32
    private const val SPIRAL_TURNS_PER_BLOCK: Double = 0.18
    /** Bumped over the prior 0.15 — the spiral now corkscrews at roughly
     *  the same rate as the inner ring. */
    private const val SPIRAL_ROT_RATE: Double = 0.35

    /** Max perpendicular half-extent. **Square cross-section** (used for
     *  both axes) so the box scales equally in both perpendicular
     *  directions outward from the centre ray — the prior rectangular
     *  cross-section was lopsided. */
    private const val BOX_HALF_WIDTH: Double = 0.08

    /** Angular speed of the box's perpendicular axes around the beam axis
     *  (rad/tick). Square cross-section's 4-fold symmetry hides 90 °
     *  jumps, but oblique-view silhouette width still oscillates over the
     *  rotation cycle so the spin reads at most viewing angles. */
    private const val BOX_ROT_RATE: Double = 0.12

    private const val BOX_GLOW_OUTER_ALPHA: Int = 110

    /** Strip inset distance — **perpendicular to the strip's edge**, in
     *  absolute world units. Fraction of [BOX_HALF_WIDTH] so it scales with
     *  the box's perpendicular half-extent. Previous centroid-based lerp
     *  produced strips whose perpendicular thickness was effectively zero
     *  on long faces (the lerp pointed mostly down the beam axis), making
     *  the glow vanish on the main body. */
    private const val BOX_GLOW_INSET_DISTANCE: Double = BOX_HALF_WIDTH * 0.8

    /** Fraction of [BOX_GLOW_INSET_DISTANCE] occupied by the outline-
     *  adjacent white-tinted band. 0.25 ≈ the outer quarter of each strip
     *  is the white band, the inner three quarters stay orange-to-zero. */
    private const val BOX_GLOW_WHITE_BAND: Double = 0.25

    /** Extra length added to each end of the cross beam, in blocks — it
     *  visibly emerges past the box's start and end caps so the box reads
     *  as having a continuous core that pierces through it. */
    private const val CROSS_BEAM_EXTENSION: Double = 0.5

    /** Perpendicular half-extent of each cross-beam plane (in the rotUp /
     *  rotRight direction). Slightly larger than the box's own half so the
     *  cross planes peek through the box's faces at their outer edges. */
    private const val CROSS_BEAM_PERP: Double = BOX_HALF_WIDTH * 1.4

    /** Fraction of [CROSS_BEAM_PERP] occupied by the outline-adjacent
     *  white-tinted band of the cross-beam gradient. */
    private const val CROSS_BEAM_WHITE_BAND: Double = 0.25

    /** Distance (blocks) the wireframe's start cap is shifted back from
     *  the beam origin. Combined with the per-vertex alpha gradient on
     *  the long sub-edges, this makes the outline "emerge" from the
     *  player's view over the first [WIRE_FADE_BACKUP] blocks of the box
     *  instead of popping in at the origin. */
    private const val WIRE_FADE_BACKUP: Double = 1.0

    /** Symmetric counterpart of [WIRE_FADE_BACKUP] for the **last**
     *  segment's terminus — the wireframe's outer end is shifted forward
     *  past `segEnd` by this distance, with the long-edge vertex alpha
     *  ramping full → 0 along the way. The beam dies out cleanly instead
     *  of popping off at the block hit / range end. */
    private const val WIRE_FADE_FORWARD: Double = 1.0

    /** One block ahead of the eye along the view direction, centred
     *  horizontally (no left/right shift) and dropped slightly below the
     *  eye so the beam emerges from the low centre of the player's view. */
    private const val ORIGIN_FORWARD: Double = 1.0
    private const val ORIGIN_DOWN: Double = 0.20
    private const val ORIGIN_RIGHT: Double = 0.0

    /** True for the ticks between the local player engaging the staff and
     *  the next idle tick. Used to spawn the SUNDER glyph ring once per
     *  wielding session — the 6 glyph particles then survive their session
     *  with `lifetime = Int.MAX_VALUE` and self-remove when wielding ends. */
    private var localGlyphRingActive: Boolean = false

    @JvmStatic
    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level ->
            val localPlayer = Minecraft.getInstance().player
            val localWielding = localPlayer != null && isWielding(localPlayer)

            for (player in level.players()) {
                if (!isWielding(player)) continue
                tickSpawnsFor(level, player)
            }

            // SUNDER glyph ring — spawn EXACTLY ONCE per wielding session for
            // the local player, on the idle → wielding transition. The
            // glyph particles live until the player stops wielding (self-
            // removal in their tick), so the ring is a stable 6-particle
            // cluster rather than the per-tick respawning trail the fire
            // rings use.
            if (localWielding && !localGlyphRingActive && localPlayer != null) {
                spawnGlyphRing(level, localPlayer)
                localGlyphRingActive = true
            } else if (!localWielding) {
                localGlyphRingActive = false
            }
        })
    }

    @JvmStatic
    fun renderAll(
        pose: PoseStack, consumers: MultiBufferSource,
        camX: Double, camY: Double, camZ: Double, tickDelta: Float,
    ) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        for (player in level.players()) {
            if (isWielding(player)) renderBoxFor(pose, consumers, camX, camY, camZ, player, tickDelta, level)
        }
    }

    private fun isWielding(player: Player): Boolean {
        if (!player.isUsingItem) return false
        return player.useItem.item == EKItems.STAFF_OF_SUNDERING.get()
    }

    /** Public wielding check used by [SunderingGlyphParticle.tick] to
     *  decide whether to keep tracking the local player's beam tip. */
    @JvmStatic
    fun isLocalWieldingSundering(player: Player): Boolean = isWielding(player)

    /** Live world-space position of one slot on the SUNDER glyph ring,
     *  evaluated against [player]'s current beam tip + view direction at
     *  the given [tickDelta]. Called from
     *  [SunderingGlyphParticle.tick] every game tick so the letter stays
     *  pinned to the rotating ring as the player turns. */
    @JvmStatic
    fun glyphRingPosition(player: Player, slotIndex: Int, tickDelta: Float): Vec3 {
        val viewVec = player.getViewVector(tickDelta).normalize()
        // Forward push along the beam puts the glyph ring just ahead of the
        // raw beam origin so it doesn't share its orbit plane with the
        // inner fire ring.
        val centre = beamOrigin(player, viewVec, tickDelta).add(viewVec.scale(GLYPH_RING_FORWARD_OFFSET))
        val (right, up) = perpBasis(viewVec)
        val gameTime = player.level().gameTime.toDouble() + tickDelta.toDouble()
        // Sign negated → counterclockwise, opposite the inner CW fire ring.
        val baseAngle = -gameTime * GLYPH_RING_RATE
        val theta = baseAngle + slotIndex * (2.0 * Math.PI / SUNDER_LETTERS.size)
        val cos = Math.cos(theta); val sin = Math.sin(theta)
        return Vec3(
            centre.x + (right.x * cos + up.x * sin) * GLYPH_RING_RADIUS,
            centre.y + (right.y * cos + up.y * sin) * GLYPH_RING_RADIUS,
            centre.z + (right.z * cos + up.z * sin) * GLYPH_RING_RADIUS,
        )
    }

    /** One spawn per slot per tick. The particle's tick() will animate the
     *  letter around the orbit; each new spawn keeps the slot populated
     *  with fresh particles so the alpha envelope stays bright. */
    private fun spawnGlyphRing(level: ClientLevel, player: Player) {
        for (i in SUNDER_LETTERS.indices) {
            val pos = glyphRingPosition(player, i, 1f)
            level.addParticle(
                EKParticles.sunderingGlyphParticle(),
                pos.x, pos.y, pos.z,
                // vx = slot index, vy = SGA sprite index.
                i.toDouble(), SUNDER_LETTERS[i].toDouble(), 0.0,
            )
        }
    }

    private data class SubStages(val t01: Float, val t12: Float, val t23: Float, val t34: Float)

    private fun subStages(elapsed: Float): SubStages {
        val s = SunderingManager.STAGE_TICKS.toFloat()
        return SubStages(
            t01 = (elapsed / s).coerceIn(0f, 1f),
            t12 = ((elapsed - s) / s).coerceIn(0f, 1f),
            t23 = ((elapsed - 2f * s) / s).coerceIn(0f, 1f),
            t34 = ((elapsed - 3f * s) / s).coerceIn(0f, 1f),
        )
    }

    /** Per-tick spawn for all four sub-stage particle layers. */
    /** Polyline tracer call for the client — feeds the same shared
     *  [SunderingBeamTrace.trace] both [SunderingManager] and the
     *  rendering paths use, with the client's shield set (wielded
     *  shields + this client's [AegisClient] debug boxes). */
    private fun computeBeamTrace(level: ClientLevel, player: Player, origin: Vec3, viewVec: Vec3): BeamTrace {
        val shields = ArrayList<AegisBox.Frame>()
        shields.addAll(SunderingBeamTrace.collectWieldedShields(level, player))
        shields.addAll(AegisClient.snapshotDebugBoxes())
        return SunderingBeamTrace.trace(level, player, origin, viewVec, SunderingManager.effectiveRange(player), shields)
    }

    private fun tickSpawnsFor(level: ClientLevel, player: Player) {
        val elapsed = SunderingManager.effectiveElapsed(player, player.useItem).toFloat()
        val sub = subStages(elapsed)

        val viewVec = player.getViewVector(1f).normalize()
        val origin = beamOrigin(player, viewVec, 1f)
        val trace = computeBeamTrace(level, player, origin, viewVec)
        if (trace.segments.isEmpty()) return
        val firstSeg = trace.segments[0]
        val totalLen = trace.segments.sumOf { it.length }
        if (totalLen < 0.05) return

        val (firstRight, firstUp) = perpBasis(firstSeg.direction)
        val gameTime = level.gameTime.toDouble()

        val beamDensity = Math.min(sub.t01, 1f - sub.t23)
        if (beamDensity > 0f) {
            for (seg in trace.segments) {
                val (sr, su) = perpBasis(seg.direction)
                spawnStage1Particles(level, seg.start, seg.direction, sr, su, seg.length, beamDensity)
            }
        }

        if (sub.t12 > 0f) {
            val innerRingCentre = firstSeg.start.add(firstSeg.direction.scale(INNER_RING_FORWARD_OFFSET))
            spawnFireRing(
                level, innerRingCentre, firstRight, firstUp,
                radius = INNER_RING_RADIUS, slots = INNER_RING_SLOTS,
                rate = INNER_RING_RATE, clockwise = true,
                density = sub.t12, gameTime = gameTime,
                particleSize = INNER_RING_PARTICLE_SIZE,
            )
        }

        if (sub.t23 > 0f) {
            spawnFireRing(
                level, firstSeg.start, firstRight, firstUp,
                radius = OUTER_RING_RADIUS, slots = OUTER_RING_SLOTS,
                rate = OUTER_RING_RATE, clockwise = false,
                density = sub.t23, gameTime = gameTime,
                particleSize = OUTER_RING_PARTICLE_SIZE,
            )
        }

        if (sub.t34 > 0f) {
            val visibleTotal = totalLen * sub.t34.toDouble()
            var accumulated = 0.0
            for (seg in trace.segments) {
                if (accumulated >= visibleTotal) break
                val visibleInSeg = Math.min(seg.length, visibleTotal - accumulated)
                if (visibleInSeg <= 0.0) break
                val visibleFraction = (visibleInSeg / seg.length).coerceIn(0.0, 1.0)
                val (sr, su) = perpBasis(seg.direction)
                spawnFireSpiral(
                    level, seg.start, seg.direction, sr, su,
                    fullLength = seg.length, visibleFraction = visibleFraction,
                    gameTime = gameTime, particleSize = SPIRAL_PARTICLE_SIZE,
                )
                accumulated += seg.length
            }
        }
    }

    private fun spawnStage1Particles(
        level: ClientLevel, origin: Vec3, viewVec: Vec3,
        right: Vec3, up: Vec3, length: Double, density: Float,
    ) {
        val count = (PARTICLE_BEAM_MAX_PER_TICK * density).toInt()
        if (count == 0) return
        val r = level.random
        val vx = viewVec.x * PARTICLE_SPEED_PER_TICK
        val vy = viewVec.y * PARTICLE_SPEED_PER_TICK
        val vz = viewVec.z * PARTICLE_SPEED_PER_TICK
        for (i in 0 until count) {
            val along = r.nextDouble() * length
            val theta = r.nextDouble() * (2.0 * Math.PI)
            val offset = r.nextDouble() * PARTICLE_BEAM_SPREAD
            val cos = Math.cos(theta); val sin = Math.sin(theta)
            val px = origin.x + viewVec.x * along + (right.x * cos + up.x * sin) * offset
            val py = origin.y + viewVec.y * along + (right.y * cos + up.y * sin) * offset
            val pz = origin.z + viewVec.z * along + (right.z * cos + up.z * sin) * offset
            level.addParticle(EKParticles.sunderingBeamParticle(), px, py, pz, vx, vy, vz)
        }
    }

    /** Spawn one fire particle at each of the [slots] orbital slot positions
     *  for one tick. The slots' base angle advances with [gameTime] × [rate],
     *  so the spawn positions shift by `rate` rad/tick — and because
     *  [SunderingFireParticle] is stationary with a 5-tick lifetime, the
     *  trail of recently-spawned particles forms a visible rotation arc
     *  spanning ~`5 × rate` rad behind the leading edge. Probabilistic per-
     *  slot gate on [density] ramps the ring's apparent density in / out at
     *  stage transitions. */
    private fun spawnFireRing(
        level: ClientLevel, centre: Vec3, right: Vec3, up: Vec3,
        radius: Double, slots: Int, rate: Double, clockwise: Boolean,
        density: Float, gameTime: Double, particleSize: Double,
    ) {
        val r = level.random
        val sign = if (clockwise) 1.0 else -1.0
        val baseAngle = gameTime * rate * sign
        for (i in 0 until slots) {
            if (r.nextFloat() > density) continue                // density-gated spawn
            val theta = baseAngle + i * (2.0 * Math.PI / slots)
            val cos = Math.cos(theta); val sin = Math.sin(theta)
            val px = centre.x + (right.x * cos + up.x * sin) * radius
            val py = centre.y + (right.y * cos + up.y * sin) * radius
            val pz = centre.z + (right.z * cos + up.z * sin) * radius
            // `vx` slot smuggles the size — see SunderingFireParticle.Provider.
            level.addParticle(
                EKParticles.sunderingFireParticle(),
                px, py, pz, particleSize, 0.0, 0.0,
            )
        }
    }

    /** Spawn fire particles along the helix in `[0, fullLength × visibleFraction]`.
     *  Helix angle at distance `along` is `along × turns_per_block × 2π + gameTime × rate`,
     *  so the spiral appears to rotate around the beam axis between ticks. */
    private fun spawnFireSpiral(
        level: ClientLevel, origin: Vec3, viewVec: Vec3,
        right: Vec3, up: Vec3, fullLength: Double, visibleFraction: Double,
        gameTime: Double, particleSize: Double,
    ) {
        val visibleLen = fullLength * visibleFraction
        if (visibleLen <= 0.0) return
        val slotCount = Math.max(1, (visibleLen / SPIRAL_SPACING).toInt())
        for (i in 0..slotCount) {
            val along = i * SPIRAL_SPACING
            if (along > visibleLen) break
            val theta = along * SPIRAL_TURNS_PER_BLOCK * (2.0 * Math.PI) + gameTime * SPIRAL_ROT_RATE
            val cos = Math.cos(theta); val sin = Math.sin(theta)
            val px = origin.x + viewVec.x * along + (right.x * cos + up.x * sin) * SPIRAL_RADIUS
            val py = origin.y + viewVec.y * along + (right.y * cos + up.y * sin) * SPIRAL_RADIUS
            val pz = origin.z + viewVec.z * along + (right.z * cos + up.z * sin) * SPIRAL_RADIUS
            level.addParticle(
                EKParticles.sunderingFireParticle(),
                px, py, pz, particleSize, 0.0, 0.0,
            )
        }
    }

    /** Per-frame box-beam draw. Box length is always full; perpendicular
     *  half-extents scale with `t23` so the box "blooms" outward from the
     *  centre ray; its perpendicular axes spin around the beam direction
     *  with `gameTime × BOX_ROT_RATE`. Rectangular cross-section (X ≠ Y)
     *  makes the spin visible.
     *
     *  Face glow stays orange the whole time; wireframe colour lerps
     *  orange → off-white via `t34` — that's the "only outline shifts to
     *  white" requirement. */
    private fun renderBoxFor(
        pose: PoseStack, consumers: MultiBufferSource,
        camX: Double, camY: Double, camZ: Double,
        player: Player, tickDelta: Float, level: ClientLevel,
    ) {
        val elapsedF = SunderingManager.effectiveElapsed(player, player.useItem).toFloat() + tickDelta
        val sub = subStages(elapsedF)
        if (sub.t23 <= 0f) return

        val viewVec = player.getViewVector(tickDelta).normalize()
        val origin = beamOrigin(player, viewVec, tickDelta)
        val trace = computeBeamTrace(level, player, origin, viewVec)
        if (trace.segments.isEmpty()) return

        pose.pushPose()
        pose.translate(-camX, -camY, -camZ)
        val matrix = pose.last().pose()
        val normal = pose.last().normal()
        val gameTime = level.gameTime.toDouble() + tickDelta.toDouble()

        val lastIdx = trace.segments.lastIndex
        for ((idx, seg) in trace.segments.withIndex()) {
            if (seg.length < 0.05) continue
            renderBoxSegment(
                matrix, normal, consumers, seg, sub,
                isFirst = (idx == 0), isLast = (idx == lastIdx), gameTime = gameTime,
            )
        }

        pose.popPose()
    }

    /** Render one polyline segment as a complete sundering box: face glow
     *  + cross beam + wireframe outline, all sharing the per-segment
     *  perpendicular basis (rotated around the segment's own axis by
     *  `gameTime × BOX_ROT_RATE`). Only [isFirst] segments get the
     *  `WIRE_FADE_BACKUP` backward fade-in, and only [isLast] segments
     *  get the forward [CROSS_BEAM_EXTENSION] past the box end. */
    private fun renderBoxSegment(
        matrix: Matrix4f, normal: org.joml.Matrix3f,
        consumers: MultiBufferSource,
        seg: BeamSegment, sub: SubStages,
        isFirst: Boolean, isLast: Boolean, gameTime: Double,
    ) {
        val segDir = seg.direction
        val (right, up) = perpBasis(segDir)
        val phi = gameTime * BOX_ROT_RATE
        val cosPhi = Math.cos(phi); val sinPhi = Math.sin(phi)
        val rotRight = Vec3(
            right.x * cosPhi + up.x * sinPhi,
            right.y * cosPhi + up.y * sinPhi,
            right.z * cosPhi + up.z * sinPhi,
        )
        val rotUp = Vec3(
            -right.x * sinPhi + up.x * cosPhi,
            -right.y * sinPhi + up.y * cosPhi,
            -right.z * sinPhi + up.z * cosPhi,
        )

        val half = BOX_HALF_WIDTH * sub.t23.toDouble()

        // 4 perpendicular cross-section layers. Reflected (non-first /
        // non-last) ends collapse to zero-length fade regions so the
        // visual reads as a clean kink at each reflection point.
        //
        //   outerStart  innerStart           innerEnd  outerEnd
        //     |           |                     |        |
        //     α 0 ====== α full ===== α full ===== α 0
        //         backward          main           forward
        //         fade               (full)        fade
        val outerStart = if (isFirst) seg.start.subtract(segDir.scale(WIRE_FADE_BACKUP)) else seg.start
        val innerStart = seg.start
        val innerEnd = seg.end
        val outerEnd = if (isLast) seg.end.add(segDir.scale(WIRE_FADE_FORWARD)) else seg.end
        val outerStartCorners = buildPlanarCorners(outerStart, rotRight, rotUp, half)
        val innerStartCorners = buildPlanarCorners(innerStart, rotRight, rotUp, half)
        val innerEndCorners = buildPlanarCorners(innerEnd, rotRight, rotUp, half)
        val outerEndCorners = buildPlanarCorners(outerEnd, rotRight, rotUp, half)

        val glowAlpha = (BOX_GLOW_OUTER_ALPHA * sub.t23).toInt().coerceIn(0, 255)
        if (glowAlpha > 0) {
            val (outerR, outerG, outerB) = lerpRgb(
                ORANGE_R, ORANGE_G, ORANGE_B,
                WHITE_R, WHITE_G, WHITE_B,
                sub.t34,
            )
            val glowConsumer = consumers.getBuffer(AegisRenderType.FACE_GLOW)
            renderBoxFaceGlowWithFade(
                glowConsumer, matrix,
                outerStartCorners, innerStartCorners, innerEndCorners, outerEndCorners,
                outerR, outerG, outerB,
                ORANGE_R, ORANGE_G, ORANGE_B,
                glowAlpha,
            )
            renderCentreCrossBeamSegment(
                glowConsumer, matrix,
                seg.start, segDir, seg.length, rotRight, rotUp,
                outerR, outerG, outerB,
                ORANGE_R, ORANGE_G, ORANGE_B,
                glowAlpha,
                extendBackward = isFirst, extendForward = isLast,
            )
        }

        val (wireR, wireG, wireB) = lerpRgb(
            ORANGE_R, ORANGE_G, ORANGE_B,
            WHITE_R, WHITE_G, WHITE_B,
            sub.t34,
        )
        val wireAlpha = (255 * sub.t23).toInt().coerceIn(0, 255)
        if (wireAlpha > 0) {
            val lineConsumer = consumers.getBuffer(RenderType.lines())
            renderWireframeWithFade(
                lineConsumer, matrix, normal,
                outerStartCorners, innerStartCorners, innerEndCorners, outerEndCorners,
                wireR, wireG, wireB, wireAlpha,
            )
        }
    }

    /** Four corners of a perpendicular cross-section square at [centre],
     *  using the same `(sx, sy)` → bit-(0, 1) indexing as [buildBoxCorners].
     *  No `sz` axis: this is one slice, the caller stitches multiple slices
     *  together for the full box. */
    private fun buildPlanarCorners(
        centre: Vec3, right: Vec3, up: Vec3, half: Double,
    ): Array<Vec3> = arrayOf(
        Vec3(centre.x - right.x * half - up.x * half, centre.y - right.y * half - up.y * half, centre.z - right.z * half - up.z * half),
        Vec3(centre.x + right.x * half - up.x * half, centre.y + right.y * half - up.y * half, centre.z + right.z * half - up.z * half),
        Vec3(centre.x - right.x * half + up.x * half, centre.y - right.y * half + up.y * half, centre.z - right.z * half + up.z * half),
        Vec3(centre.x + right.x * half + up.x * half, centre.y + right.y * half + up.y * half, centre.z + right.z * half + up.z * half),
    )

    /** Subdivided wireframe — 4 long edges, each split into three sub-
     *  edges: backward fade (α 0 → full), main (α full → full), forward
     *  fade (α full → 0). For internal reflection joins (non-first or
     *  non-last segments) the corresponding outer corner sits on top of
     *  the inner corner, so the fade sub-edge has zero length and renders
     *  nothing. Cap edges are deliberately omitted — start / end caps
     *  would be α 0 anyway, and mid caps at reflection points would
     *  produce spurious bright rings. */
    private fun renderWireframeWithFade(
        consumer: VertexConsumer, matrix: Matrix4f, normal: org.joml.Matrix3f,
        outerStartCorners: Array<Vec3>, innerStartCorners: Array<Vec3>,
        innerEndCorners: Array<Vec3>, outerEndCorners: Array<Vec3>,
        r: Int, g: Int, bcol: Int, fullAlpha: Int,
    ) {
        for (j in 0 until 4) {
            // Sub-edge 1 — backward fade.
            drawLine(consumer, matrix, normal, outerStartCorners[j], innerStartCorners[j],
                r, g, bcol, 0, fullAlpha)
            // Sub-edge 2 — main (full α throughout).
            drawLine(consumer, matrix, normal, innerStartCorners[j], innerEndCorners[j],
                r, g, bcol, fullAlpha, fullAlpha)
            // Sub-edge 3 — forward fade.
            drawLine(consumer, matrix, normal, innerEndCorners[j], outerEndCorners[j],
                r, g, bcol, fullAlpha, 0)
        }
    }

    /** Emit one line primitive with potentially different per-vertex alpha
     *  at each endpoint — the per-vertex variation is what produces the
     *  fade gradient on each long sub-edge. */
    private fun drawLine(
        consumer: VertexConsumer, matrix: Matrix4f, normal: org.joml.Matrix3f,
        p0: Vec3, p1: Vec3,
        r: Int, g: Int, bcol: Int, alpha0: Int, alpha1: Int,
    ) {
        val dx = (p1.x - p0.x).toFloat()
        val dy = (p1.y - p0.y).toFloat()
        val dz = (p1.z - p0.z).toFloat()
        val invLen = 1f / Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat().coerceAtLeast(1e-4f)
        val nx = dx * invLen; val ny = dy * invLen; val nz = dz * invLen
        consumer.vertex(matrix, p0.x.toFloat(), p0.y.toFloat(), p0.z.toFloat())
            .color(r, g, bcol, alpha0).normal(normal, nx, ny, nz).endVertex()
        consumer.vertex(matrix, p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat())
            .color(r, g, bcol, alpha1).normal(normal, nx, ny, nz).endVertex()
    }

    /** Indices into [buildPlanarCorners]'s 4-corner array of the planar
     *  perpendicular cross-section. Each pair identifies the two corners on
     *  one long face of the box: planar-0 = (-X,-Y), planar-1 = (+X,-Y),
     *  planar-2 = (-X,+Y), planar-3 = (+X,+Y). The 4 long faces are: -X,
     *  +X, -Y, +Y. */
    private val LONG_FACE_PLANAR_PAIRS: Array<IntArray> = arrayOf(
        intArrayOf(0, 2),                                       // -X face: corners at (-X,-Y), (-X,+Y)
        intArrayOf(1, 3),                                       // +X face: corners at (+X,-Y), (+X,+Y)
        intArrayOf(0, 1),                                       // -Y face: corners at (-X,-Y), (+X,-Y)
        intArrayOf(2, 3),                                       // +Y face: corners at (-X,+Y), (+X,+Y)
    )

    /** Face glow with **per-vertex alpha fade** at both ends. Each long
     *  face is rendered as three sub-segments — backward fade, main, and
     *  forward fade — sharing four perpendicular cross-section layers
     *  (outerStart, innerStart, innerEnd, outerEnd). For internal
     *  reflection joins (non-first or non-last segments) the
     *  corresponding fade layers collapse onto the segment endpoints, so
     *  the fade region is zero-length and renders nothing.
     *
     *  All four cap-direction short-edge strips are deliberately omitted
     *  — they would either be α 0 anyway or would render a spurious
     *  perpendicular ring at the polyline's internal reflection joins.
     *  The beam's terminus is closed cleanly by the α-fade alone, no
     *  explicit end cap face needed. */
    private fun renderBoxFaceGlowWithFade(
        consumer: VertexConsumer, matrix: Matrix4f,
        outerStartCorners: Array<Vec3>, innerStartCorners: Array<Vec3>,
        innerEndCorners: Array<Vec3>, outerEndCorners: Array<Vec3>,
        outerR: Int, outerG: Int, outerB: Int,
        innerR: Int, innerG: Int, innerB: Int,
        alpha: Int,
    ) {
        for (face in LONG_FACE_PLANAR_PAIRS) {
            val pa = face[0]; val pb = face[1]
            // Sub-segment 1 — backward fade (α 0 → α full).
            renderLongFaceSegment(
                consumer, matrix,
                outerStartCorners[pa], outerStartCorners[pb],
                innerStartCorners[pa], innerStartCorners[pb],
                startAlpha = 0, endAlpha = alpha,
                skipStartShort = true, skipEndShort = true,
                outerR, outerG, outerB, innerR, innerG, innerB,
            )
            // Sub-segment 2 — main (α full → α full).
            renderLongFaceSegment(
                consumer, matrix,
                innerStartCorners[pa], innerStartCorners[pb],
                innerEndCorners[pa], innerEndCorners[pb],
                startAlpha = alpha, endAlpha = alpha,
                skipStartShort = true, skipEndShort = true,
                outerR, outerG, outerB, innerR, innerG, innerB,
            )
            // Sub-segment 3 — forward fade (α full → α 0).
            renderLongFaceSegment(
                consumer, matrix,
                innerEndCorners[pa], innerEndCorners[pb],
                outerEndCorners[pa], outerEndCorners[pb],
                startAlpha = alpha, endAlpha = 0,
                skipStartShort = true, skipEndShort = true,
                outerR, outerG, outerB, innerR, innerG, innerB,
            )
        }
    }

    /** One long-face sub-segment — a rectangular face quad bounded by
     *  4 corners. Renders up to 4 trapezoidal glow strips around the
     *  perimeter, with per-vertex α support on the two long-edge strips so
     *  the gradient lerps `startAlpha → endAlpha` along the beam axis.
     *
     *  Each strip's inner / middle offsets are computed **perpendicular to
     *  the strip's own edge, in the face's plane**, not toward the face
     *  centroid — the centroid-lerp approach makes long-thin faces collapse
     *  their strips to invisible slivers because the centroid is mostly
     *  along the beam axis, so most of the inset distance goes there
     *  instead of into the perpendicular thickness that matters visually. */
    private fun renderLongFaceSegment(
        consumer: VertexConsumer, matrix: Matrix4f,
        startA: Vec3, startB: Vec3, endA: Vec3, endB: Vec3,
        startAlpha: Int, endAlpha: Int,
        skipStartShort: Boolean, skipEndShort: Boolean,
        outerR: Int, outerG: Int, outerB: Int,
        innerR: Int, innerG: Int, innerB: Int,
    ) {
        val cx = (startA.x + startB.x + endA.x + endB.x) * 0.25
        val cy = (startA.y + startB.y + endA.y + endB.y) * 0.25
        val cz = (startA.z + startB.z + endA.z + endB.z) * 0.25
        val centroid = Vec3(cx, cy, cz)
        // Strip 1 (start short edge: startA → startB).
        if (!skipStartShort) {
            emitPerpendicularStrip(consumer, matrix,
                startA, startB, centroid,
                outerR, outerG, outerB, innerR, innerG, innerB,
                startAlpha, startAlpha)
        }
        // Strip 2 (long edge: startB → endB), α gradient startAlpha → endAlpha.
        emitPerpendicularStrip(consumer, matrix,
            startB, endB, centroid,
            outerR, outerG, outerB, innerR, innerG, innerB,
            startAlpha, endAlpha)
        // Strip 3 (end short edge: endB → endA).
        if (!skipEndShort) {
            emitPerpendicularStrip(consumer, matrix,
                endB, endA, centroid,
                outerR, outerG, outerB, innerR, innerG, innerB,
                endAlpha, endAlpha)
        }
        // Strip 4 (long edge: endA → startA), α gradient endAlpha → startAlpha.
        emitPerpendicularStrip(consumer, matrix,
            endA, startA, centroid,
            outerR, outerG, outerB, innerR, innerG, innerB,
            endAlpha, startAlpha)
    }

    /** Per-segment centre cross beam — two perpendicular gradient planes
     *  through the segment's axis (one in [rotUp], one in [rotRight]),
     *  each rendered as 4 half-planes (±rotUp, ±rotRight away from the
     *  axis) with the same 3-zone outer-white / middle-orange / inner-zero
     *  gradient as the face glow.
     *
     *  Up to three sub-segments along the beam axis:
     *  - **Backward fade** (only when [extendBackward]) — from
     *    `segStart − (WIRE_FADE_BACKUP + CROSS_BEAM_EXTENSION)` to
     *    `segStart`, α 0 → full.
     *  - **Main** — `segStart` → `segEnd`, full α throughout.
     *  - **Forward fade** (only when [extendForward]) — from `segEnd` to
     *    `segEnd + (WIRE_FADE_FORWARD + CROSS_BEAM_EXTENSION)`, α full → 0.
     *
     *  Reflected joins (`!extendBackward` / `!extendForward`) skip the
     *  corresponding fade sub-segment so the cross beam meets its neighbor
     *  segment cleanly at the bounce point. */
    private fun renderCentreCrossBeamSegment(
        consumer: VertexConsumer, matrix: Matrix4f,
        segStart: Vec3, segDir: Vec3, segLength: Double,
        rotRight: Vec3, rotUp: Vec3,
        outerR: Int, outerG: Int, outerB: Int,
        innerR: Int, innerG: Int, innerB: Int,
        alpha: Int,
        extendBackward: Boolean, extendForward: Boolean,
    ) {
        val backOffset = if (extendBackward) WIRE_FADE_BACKUP + CROSS_BEAM_EXTENSION else 0.0
        val forwardOffset = if (extendForward) WIRE_FADE_FORWARD + CROSS_BEAM_EXTENSION else 0.0
        val beamOuterStart = segStart.subtract(segDir.scale(backOffset))
        val fadeBackBoundary = segStart                              // α reaches full here on the start side
        val fadeForwardBoundary = segStart.add(segDir.scale(segLength))   // = segEnd — α starts dropping here on the end side
        val beamOuterEnd = fadeForwardBoundary.add(segDir.scale(forwardOffset))
        val perpDirs = arrayOf(
            rotUp,
            Vec3(-rotUp.x, -rotUp.y, -rotUp.z),
            rotRight,
            Vec3(-rotRight.x, -rotRight.y, -rotRight.z),
        )
        for (perpDir in perpDirs) {
            if (extendBackward) {
                // Backward fade: beamOuterStart α 0 → fadeBackBoundary α full.
                renderCrossPlaneSegment(
                    consumer, matrix, beamOuterStart, fadeBackBoundary, perpDir,
                    startAlpha = 0, endAlpha = alpha,
                    outerR, outerG, outerB, innerR, innerG, innerB,
                )
            }
            // Main: fadeBackBoundary → fadeForwardBoundary, full α.
            renderCrossPlaneSegment(
                consumer, matrix, fadeBackBoundary, fadeForwardBoundary, perpDir,
                startAlpha = alpha, endAlpha = alpha,
                outerR, outerG, outerB, innerR, innerG, innerB,
            )
            if (extendForward) {
                // Forward fade: fadeForwardBoundary α full → beamOuterEnd α 0.
                renderCrossPlaneSegment(
                    consumer, matrix, fadeForwardBoundary, beamOuterEnd, perpDir,
                    startAlpha = alpha, endAlpha = 0,
                    outerR, outerG, outerB, innerR, innerG, innerB,
                )
            }
        }
    }

    /** One half-plane of the cross beam — rectangular gradient from the
     *  centre axis ([segStart] / [segEnd]) outward along [perpDir] by
     *  [CROSS_BEAM_PERP]. Two quads form the 3-zone gradient (outer-white,
     *  middle-orange, axis-zero). Per-vertex α at each end so the plane
     *  can fade across the WIRE_FADE_BACKUP region. */
    private fun renderCrossPlaneSegment(
        consumer: VertexConsumer, matrix: Matrix4f,
        segStart: Vec3, segEnd: Vec3, perpDir: Vec3,
        startAlpha: Int, endAlpha: Int,
        outerR: Int, outerG: Int, outerB_: Int,
        innerR: Int, innerG: Int, innerB_: Int,
    ) {
        val outerDist = CROSS_BEAM_PERP
        val midDist = CROSS_BEAM_PERP * (1.0 - CROSS_BEAM_WHITE_BAND)
        val outerStart = Vec3(segStart.x + perpDir.x * outerDist, segStart.y + perpDir.y * outerDist, segStart.z + perpDir.z * outerDist)
        val outerEnd = Vec3(segEnd.x + perpDir.x * outerDist, segEnd.y + perpDir.y * outerDist, segEnd.z + perpDir.z * outerDist)
        val midStart = Vec3(segStart.x + perpDir.x * midDist, segStart.y + perpDir.y * midDist, segStart.z + perpDir.z * midDist)
        val midEnd = Vec3(segEnd.x + perpDir.x * midDist, segEnd.y + perpDir.y * midDist, segEnd.z + perpDir.z * midDist)
        // Quad 1 — outer band: outer colour → middle colour, per-vertex α.
        consumer.vertex(matrix, outerStart.x.toFloat(), outerStart.y.toFloat(), outerStart.z.toFloat())
            .color(outerR, outerG, outerB_, startAlpha).endVertex()
        consumer.vertex(matrix, outerEnd.x.toFloat(), outerEnd.y.toFloat(), outerEnd.z.toFloat())
            .color(outerR, outerG, outerB_, endAlpha).endVertex()
        consumer.vertex(matrix, midEnd.x.toFloat(), midEnd.y.toFloat(), midEnd.z.toFloat())
            .color(innerR, innerG, innerB_, endAlpha).endVertex()
        consumer.vertex(matrix, midStart.x.toFloat(), midStart.y.toFloat(), midStart.z.toFloat())
            .color(innerR, innerG, innerB_, startAlpha).endVertex()
        // Quad 2 — middle band → axis: orange, α full → α 0 at axis.
        consumer.vertex(matrix, midStart.x.toFloat(), midStart.y.toFloat(), midStart.z.toFloat())
            .color(innerR, innerG, innerB_, startAlpha).endVertex()
        consumer.vertex(matrix, midEnd.x.toFloat(), midEnd.y.toFloat(), midEnd.z.toFloat())
            .color(innerR, innerG, innerB_, endAlpha).endVertex()
        consumer.vertex(matrix, segEnd.x.toFloat(), segEnd.y.toFloat(), segEnd.z.toFloat())
            .color(innerR, innerG, innerB_, 0).endVertex()
        consumer.vertex(matrix, segStart.x.toFloat(), segStart.y.toFloat(), segStart.z.toFloat())
            .color(innerR, innerG, innerB_, 0).endVertex()
    }

    /** 3-zone strip whose inner / middle vertices are offset
     *  **perpendicular to the outer edge in the face's plane** by
     *  [BOX_GLOW_INSET_DISTANCE]. The perpendicular direction is
     *  derived by projecting `(centroid - outerA)` onto the plane
     *  perpendicular to the edge and normalising — that always points
     *  into the face's interior regardless of which edge we're working
     *  on, so the same code handles every strip on every face. */
    private fun emitPerpendicularStrip(
        consumer: VertexConsumer, matrix: Matrix4f,
        outerA: Vec3, outerB: Vec3, faceCentroid: Vec3,
        outerR: Int, outerG: Int, outerB_: Int,
        innerR: Int, innerG: Int, innerB_: Int,
        alphaA: Int, alphaB: Int,
    ) {
        // Inward direction = the component of (centroid - outerA) that is
        // perpendicular to the edge, normalised.
        val edx = outerB.x - outerA.x
        val edy = outerB.y - outerA.y
        val edz = outerB.z - outerA.z
        val eLenSq = edx * edx + edy * edy + edz * edz
        if (eLenSq < 1e-12) return
        val eLen = Math.sqrt(eLenSq)
        val edxN = edx / eLen; val edyN = edy / eLen; val edzN = edz / eLen
        val tcx = faceCentroid.x - outerA.x
        val tcy = faceCentroid.y - outerA.y
        val tcz = faceCentroid.z - outerA.z
        val dot = tcx * edxN + tcy * edyN + tcz * edzN
        val inX = tcx - dot * edxN
        val inY = tcy - dot * edyN
        val inZ = tcz - dot * edzN
        val inLenSq = inX * inX + inY * inY + inZ * inZ
        if (inLenSq < 1e-12) return                              // degenerate face
        val inLen = Math.sqrt(inLenSq)
        val inwardX = inX / inLen; val inwardY = inY / inLen; val inwardZ = inZ / inLen
        val midDist = BOX_GLOW_INSET_DISTANCE * BOX_GLOW_WHITE_BAND
        val fullDist = BOX_GLOW_INSET_DISTANCE
        val middleA = Vec3(outerA.x + inwardX * midDist, outerA.y + inwardY * midDist, outerA.z + inwardZ * midDist)
        val middleB = Vec3(outerB.x + inwardX * midDist, outerB.y + inwardY * midDist, outerB.z + inwardZ * midDist)
        val innerA = Vec3(outerA.x + inwardX * fullDist, outerA.y + inwardY * fullDist, outerA.z + inwardZ * fullDist)
        val innerB = Vec3(outerB.x + inwardX * fullDist, outerB.y + inwardY * fullDist, outerB.z + inwardZ * fullDist)
        emitFadeStrip(consumer, matrix,
            outerA, outerB, middleB, middleA, innerB, innerA,
            outerR, outerG, outerB_, innerR, innerG, innerB_,
            alphaA, alphaB)
    }

    /** 3-zone strip with per-vertex α on each end. Same two-quad
     *  layout as the old `emitStrip`, but the outer-edge and middle-edge
     *  vertices carry independent α at the A-end and B-end so the strip
     *  can lerp its visibility along its length. Inner edge stays at α 0
     *  regardless. */
    private fun emitFadeStrip(
        consumer: VertexConsumer, matrix: Matrix4f,
        outerA: Vec3, outerB: Vec3, middleB: Vec3, middleA: Vec3, innerB: Vec3, innerA: Vec3,
        outerR: Int, outerG: Int, outerB_: Int,
        innerR: Int, innerG: Int, innerB_: Int,
        alphaA: Int, alphaB: Int,
    ) {
        // Quad 1 — outer band: outer colour → middle colour, per-vertex α.
        consumer.vertex(matrix, outerA.x.toFloat(), outerA.y.toFloat(), outerA.z.toFloat())
            .color(outerR, outerG, outerB_, alphaA).endVertex()
        consumer.vertex(matrix, outerB.x.toFloat(), outerB.y.toFloat(), outerB.z.toFloat())
            .color(outerR, outerG, outerB_, alphaB).endVertex()
        consumer.vertex(matrix, middleB.x.toFloat(), middleB.y.toFloat(), middleB.z.toFloat())
            .color(innerR, innerG, innerB_, alphaB).endVertex()
        consumer.vertex(matrix, middleA.x.toFloat(), middleA.y.toFloat(), middleA.z.toFloat())
            .color(innerR, innerG, innerB_, alphaA).endVertex()
        // Quad 2 — orange-to-zero band: middle colour, per-vertex α → α 0 inner.
        consumer.vertex(matrix, middleA.x.toFloat(), middleA.y.toFloat(), middleA.z.toFloat())
            .color(innerR, innerG, innerB_, alphaA).endVertex()
        consumer.vertex(matrix, middleB.x.toFloat(), middleB.y.toFloat(), middleB.z.toFloat())
            .color(innerR, innerG, innerB_, alphaB).endVertex()
        consumer.vertex(matrix, innerB.x.toFloat(), innerB.y.toFloat(), innerB.z.toFloat())
            .color(innerR, innerG, innerB_, 0).endVertex()
        consumer.vertex(matrix, innerA.x.toFloat(), innerA.y.toFloat(), innerA.z.toFloat())
            .color(innerR, innerG, innerB_, 0).endVertex()
    }

    private fun lerpToward(p: Vec3, cx: Double, cy: Double, cz: Double, t: Double): Vec3 = Vec3(
        p.x + (cx - p.x) * t,
        p.y + (cy - p.y) * t,
        p.z + (cz - p.z) * t,
    )

    private fun lerpRgb(
        r1: Int, g1: Int, b1: Int,
        r2: Int, g2: Int, b2: Int,
        t: Float,
    ): Triple<Int, Int, Int> {
        val tc = t.coerceIn(0f, 1f)
        return Triple(
            (r1 + (r2 - r1) * tc).toInt(),
            (g1 + (g2 - g1) * tc).toInt(),
            (b1 + (b2 - b1) * tc).toInt(),
        )
    }

    private fun beamOrigin(player: Player, viewVec: Vec3, tickDelta: Float): Vec3 {
        val eye = player.getEyePosition(tickDelta)
        val flatLen = Math.sqrt(viewVec.x * viewVec.x + viewVec.z * viewVec.z)
        val rx: Double; val rz: Double
        if (flatLen < 1e-4) { rx = 1.0; rz = 0.0 } else { rx = -viewVec.z / flatLen; rz = viewVec.x / flatLen }
        return Vec3(
            eye.x + viewVec.x * ORIGIN_FORWARD + rx * ORIGIN_RIGHT,
            eye.y + viewVec.y * ORIGIN_FORWARD - ORIGIN_DOWN,
            eye.z + viewVec.z * ORIGIN_FORWARD + rz * ORIGIN_RIGHT,
        )
    }

    private fun perpBasis(viewVec: Vec3): Pair<Vec3, Vec3> {
        val helper = if (Math.abs(viewVec.y) < 0.95) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val right = viewVec.cross(helper).normalize()
        val up = right.cross(viewVec).normalize()
        return right to up
    }
}
