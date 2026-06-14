package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingClientRegistry
import org.shipwrights.enderkinesis.item.TomeBeamPalette
import org.shipwrights.enderkinesis.item.TomePulseProfile
import org.shipwrights.enderkinesis.item.TomePulseProfiles

/**
 * World-space renderer for the orb-network beam — a chain of glowing pulses that animate
 * from SEND to RECEIVE. Runs once per frame from the level renderer's post-translucent hook
 * (Fabric: `WorldRenderEvents.AFTER_TRANSLUCENT`; Forge: `RenderLevelStageEvent.Stage.
 * AFTER_TRANSLUCENT_BLOCKS`).
 *
 * Two-phase pipeline:
 *  1. **Collect** — walk every loaded orb, gather `(send, tome, recv)` triples from both its
 *     outgoing and incoming maps, dedup'd against a canonical orb-pair-plus-tome triple so
 *     each link contributes exactly once when both endpoints are loaded. Group the triples
 *     into bins keyed by the **unordered orb pair**, with the SEND end captured on first
 *     visit so pulses know which way to flow. The same pair carries every tome's colour in
 *     a sorted list, so multi-tome links produce a single beam whose pulses sweep through
 *     each linkage's colour in turn rather than overlapping as separate beams.
 *  2. **Render** — for each bin, build a quad strip sampled along its length. Each sample
 *     carries an envelope-modulated alpha **and** width, and a colour taken from the bin's
 *     palette list at a position swept across the active pulse. The result reads as a row
 *     of bright dots travelling along the beam — each dot wider and brighter at its
 *     centre, fading and thinning at its edges — with each dot internally sweeping through
 *     every tome's colour as a smooth gradient.
 *
 * Endpoints fall back to [Vec3.atCenterOf] of the block pos when only one end's chunk is
 * loaded — the beam keeps a meaningful endpoint and stays visible from the loaded side.
 */
object OrbBeamLineRenderer {

    /** Halo half-width at a pulse leading edge — the beam's widest point. Thin overall;
     *  the modulation comes from pulses passing through. */
    private const val HALO_HALF_WIDTH_MAX: Double = 0.07

    /** Halo half-width at a pulse trough — the always-on baseline thread between pulses. */
    private const val HALO_HALF_WIDTH_MIN: Double = 0.005

    /** Core half-widths — thinner everywhere so the bright thread reads inside the halo. */
    private const val CORE_HALF_WIDTH_MAX: Double = 0.022
    private const val CORE_HALF_WIDTH_MIN: Double = 0.001

    /** Halo alpha at full brightness. Dim so it reads as a glow envelope around the core. */
    private const val HALO_ALPHA: Float = 0.55f

    /** Core alpha at full brightness. High so the pulse head reads as a bright point. */
    private const val CORE_ALPHA: Float = 0.95f

    /** Core colour — near-white with a slight cool tint. Constant regardless of tome
     *  palette so the core thread reads the same on every beam. */
    private const val CORE_R: Int = 240
    private const val CORE_G: Int = 245
    private const val CORE_B: Int = 255

    /** Fallback halo colour for tomes that didn't register a palette entry. */
    private const val DEFAULT_PALETTE: Int = 0xC8D8FF

    /** Baseline distance between successive pulse crests along the beam, in blocks. The
     *  per-tome [TomePulseProfile.frequency] multiplier divides this — a frequency of 2.0
     *  yields half the baseline spacing (denser pulses). */
    private const val BASE_PULSE_SPACING: Double = 1.8

    /** Baseline pulse-travel speed along the beam, in blocks per second. The per-tome
     *  [TomePulseProfile.progression] multiplier scales this. */
    private const val BASE_PULSE_SPEED: Double = 3.0

    /** Hard floor on per-train pulse length (blocks). Without it, high-frequency tomes
     *  (Signal/Pulling/Pushing at full POWER reach frequency 2.5 → 0.72-block pulses)
     *  collapse into tiny dots whose endcap caps half the pulse. Clamping the spacing —
     *  which IS the per-pulse length — keeps every pulse readable as a comet head + tail
     *  even at peak density. Tomes that aren't at the cap (frequency < 1.5) keep their
     *  natural length. */
    private const val MIN_PULSE_SPACING: Double = 1.2

    /** Hard floor on per-train pulse speed (blocks/s). Without it, the slowest profiles
     *  (Ventriloquism/Spring/Signal idle at progression 0.5 → 1.5 b/s) crawl across the
     *  beam slowly enough that the pulse cadence reads as a stutter rather than flow.
     *  This floor bumps anything below ~0.8 progression up to a watchable minimum without
     *  affecting the faster profiles. */
    private const val MIN_PULSE_SPEED: Double = 2.4

    /** Amplitude of the arrhythmic phase warp (units of pulse-cycles). Pushed high enough
     *  that the warp derivative comes close to (but stays under) the monotonicity bound
     *  `PULSE_SPEED / PULSE_SPACING = 1.67`, so the cadence at any fixed observer swings
     *  almost the full range from "pulses overtake each other" down to "pulses crawl". */
    private const val PHASE_WARP_AMP: Double = 0.55

    /** Two incommensurate angular frequencies (radians per second; cycle periods ≈ 5.7 s
     *  and 2.7 s respectively). Cycle periods are short enough that an observer sees the
     *  cadence visibly shift within a few seconds of watching, rather than waiting through
     *  a 15-second slow swell. The frequencies aren't a rational ratio, so the resulting
     *  pulse train never repeats. */
    private const val PHASE_WARP_FREQ_A: Double = 1.1
    private const val PHASE_WARP_FREQ_B: Double = 2.3

    /** Baseline peak perpendicular offset (blocks) at the centre of a pulse's path, at
     *  cohesion = 0. The per-tome [TomePulseProfile.cohesion] scales this down — cohesion=1
     *  yields zero wander (pulses stay perfectly on-axis); cohesion=0 yields the full
     *  baseline. Tapered by `sin(π · L / len)` so pulses always sit on-axis at the orbs
     *  themselves and arc off-axis through the middle of their journey. */
    private const val BASE_WANDER_AMP: Double = 0.4

    /** Slow per-pulse angle drift amplitude (radians) — a single pulse's offset
     *  direction sweeps slightly as it travels, never locks in. */
    private const val WANDER_DRIFT_AMP: Double = 0.4

    /** Slow angular frequency (rad/s) of the per-pulse drift. */
    private const val WANDER_DRIFT_FREQ: Double = 0.55

    /** Per-pulse direction angle in [0, 2π) plus a slow time drift. Knuth's multiplicative
     *  hash on the pulse index (XOR-mixed with a per-train seed) gives a deterministic but
     *  well-distributed angle, so adjacent pulses fly off in clearly different directions
     *  AND different trains (per-tome, per-direction) don't lockstep their offsets even at
     *  the same K. */
    private inline fun pulseAngleFor(pulseIndex: Long, angleSeed: Long, t: Double): Double {
        val hashed = ((pulseIndex xor angleSeed) * 2654435761L) and 0xFFFFFFFFL
        val baseAngle = hashed.toDouble() / 4294967296.0 * (2.0 * Math.PI)
        val drift = WANDER_DRIFT_AMP * Math.sin(t * WANDER_DRIFT_FREQ + pulseIndex * 0.7)
        return baseAngle + drift
    }

    /** Vertex samples generated per pulse along the **trailing body** (renderTail → renderLead).
     *  The eased-sawtooth alpha curve gets one quad strip across this range. */
    private const val SAMPLES_PER_PULSE: Int = 12

    /** Extra samples generated **past** the pulse's leading edge to form a half-circle
     *  endcap. Without these, the bright tip of a pulse renders as a flat quad — the
     *  consecutive-beam design used to hide that because the next pulse's tail visually
     *  continued the strip, but per-pulse independent rendering reveals the truncation.
     *  Four cap samples is enough to read as rounded at the typical halo width. */
    private const val CAP_SAMPLES: Int = 4

    /** Length (blocks) of the endcap past `renderLead`. A half-circle of radius equal to
     *  the halo's max half-width gives an aspect-correct rounded tip — the cap looks like
     *  the leading hemisphere of a glowing capsule, matching the comet metaphor. */
    private const val CAP_LENGTH: Double = 0.07

    /** Combined scratch-array size for main body + endcap samples. */
    private const val SAMPLES_PER_PULSE_WITH_CAP: Int = SAMPLES_PER_PULSE + CAP_SAMPLES

    /** Squared distance (blocks²) from the camera within which an endpoint counts as "in
     *  range" for beam rendering. Matches the default [net.minecraft.client.renderer.
     *  blockentity.BlockEntityRenderer.getViewDistance] of 64 blocks that the orb's haze
     *  BER uses, so the beam fades out at the same distance the orb itself stops drawing.
     *  A beam renders if **either** endpoint is in range — that way a beam to a far-off
     *  orb still draws as long as the near orb is visible, which is what players actually
     *  expect when standing next to one end. */
    private const val ENDPOINT_VIEW_DISTANCE_SQR: Float = 64f * 64f

    /** Hard sanity cap on rendered beam length. The number of visible pulses per beam is
     *  `len / PULSE_SPACING`, and each pulse emits a fixed quad budget — so total vertex
     *  count scales linearly with `len`. Without a cap, a runaway endpoint (a VS2 ship
     *  drifted to absurd world coords, a stale link to a destroyed orb whose pos was
     *  corrupted, etc.) can make a single beam allocate millions of pulses' worth of
     *  vertices in one frame and overflow the BufferBuilder past 2 GB → OOM crash.
     *
     *  Runtime link distance is capped at [org.shipwrights.enderkinesis.blockentity.
     *  OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE] = 64; anything past a generous multiple
     *  of that is malformed and we just don't render it. */
    private const val MAX_RENDERED_BEAM_LENGTH: Float = 256f

    /** Arrhythmic phase warp evaluated at the **advected time** `t − L / v` — the wall-
     *  clock moment when the pulse currently at position L was released from the SEND
     *  end. Because each pulse carries its own warp value (not the one at the current
     *  observation time), pulses released at different times have shifted phases, which
     *  makes the time interval between pulses passing a fixed point genuinely vary.
     *
     *  Math note: a spatial-only warp `f(L)` would *look* like irregular spacing along the
     *  beam at any single moment, but at a fixed point P the phase rate is still
     *  `dphase/dt = −v/s` exactly (the warp term `f(P)` is constant in t), so the cadence
     *  there stays uniform. Replacing `f(L)` with `f(t − L/v)` adds a `−f'·(−1)` term to
     *  `dphase/dt` whose sign and magnitude oscillates with time — that's the arrhythmia.
     *
     *  Monotonicity (no pulses overtaking each other at any fixed point) requires
     *  `|f'(t − L/v)| < v/s = speed/spacing` for whichever per-train values are in play.
     *  With the baseline values the derivative magnitude maxes out around 0.5, well under
     *  the baseline bound of 1.67 cycles/s — per-tome scaling preserves the margin. */
    private inline fun phaseWarp(advectedTime: Double): Double {
        return PHASE_WARP_AMP * (
            Math.sin(advectedTime * PHASE_WARP_FREQ_A) +
                0.55 * Math.sin(advectedTime * PHASE_WARP_FREQ_B + 1.3)
            )
    }

    /** Baseline alpha at pulse troughs — the always-on thin thread between pulses. */
    private const val BASELINE_ALPHA: Float = 0.15f

    /** Eased-sawtooth pulse curve with a **circular leading edge**: alpha rises slowly
     *  through the trailing tail of each pulse, then climbs along a quarter-circle profile
     *  to the bright peak at the very leading edge, then drops sharply as the cycle wraps
     *  into the next pulse. The curve is `1 − √(1 − u²)` for u ∈ [0, 1] — concave with
     *  zero slope at u=0 and vertical slope at u=1, which is the defining shape of a
     *  circular cap.
     *
     *  At a fixed point along the beam over time, this reads as a comet effect: long dim
     *  fading → tiny dark gap → BRIGHT FLASH as the next pulse's leading edge arrives →
     *  smooth fade behind it → repeat. */
    private inline fun pulseCurve(u: Double): Double {
        val c = u.coerceIn(0.0, 1.0)
        return 1.0 - Math.sqrt(1.0 - c * c)
    }

    /** Vertex samples per block of beam length. Sets how smoothly the per-vertex alpha
     *  and width curves approximate the envelope. */
    private const val SAMPLES_PER_BLOCK: Double = 8.0

    /** Floor on sample count so short beams still get a visible pulse curve. */
    private const val MIN_SAMPLES: Int = 16

    /** Cap on sample count so a 64-block link doesn't allocate hundreds of segments. */
    private const val MAX_SAMPLES: Int = 256

    /** Dedup set — one entry per (orb-pair, tome) triple per frame so a link isn't counted
     *  twice when both endpoints are loaded and each side walks its own outgoing/incoming. */
    private val seenTriples: MutableSet<TripleKey> = HashSet()

    /** One bin per unordered orb pair. Multiple tomes linking the same pair land in one
     *  bin, contributing their palette colour to the bin's gradient list. */
    private val pairBins: MutableMap<PairKey, BeamBin> = HashMap()

    /** Reusable scratch — sample alphas (0..1). Resized on demand. */
    private var sampleAlphas: FloatArray = FloatArray(0)

    /** Reusable scratch — per-sample halo half-width in world units. */
    private var sampleHaloWidths: FloatArray = FloatArray(0)

    /** Reusable scratch — per-sample core half-width in world units. */
    private var sampleCoreWidths: FloatArray = FloatArray(0)

    /** Reusable scratch — per-sample world position with spiral helix displacement
     *  applied. The segment-emit loop reads from these so vertices land on the curved
     *  helix path, not the straight axis. */
    private var samplePosX: FloatArray = FloatArray(0)
    private var samplePosY: FloatArray = FloatArray(0)
    private var samplePosZ: FloatArray = FloatArray(0)

    /** Reusable scratch — sample colour (RGB packed 0xRRGGBB). */
    private var sampleColours: IntArray = IntArray(0)

    fun renderAll(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        @Suppress("UNUSED_PARAMETER") partialTicks: Float,
    ) {
        val level = Minecraft.getInstance().level ?: return
        seenTriples.clear()
        pairBins.clear()

        for (orb in OrbOfLinkingClientRegistry.sendOrbs(level)) {
            val orbPos = orb.blockPos
            for ((linkKey, _) in orb.allOutgoing()) {
                collect(level, orbPos, linkKey.peerPos, linkKey.tomeKind)
            }
            for ((linkKey, _) in orb.allIncoming()) {
                collect(level, linkKey.peerPos, orbPos, linkKey.tomeKind)
            }
        }
        if (pairBins.isEmpty()) return

        val consumer = bufferSource.getBuffer(OrbBeamLineRenderType.BEAM_LINE)
        val matrix = poseStack.last().pose()
        // Wall-clock: gameTime stalls on server lag and precision drifts after long
        // sessions. Util.getMillis() advances smoothly every frame.
        val tSec = Util.getMillis() / 1000.0
        for (bin in pairBins.values) {
            emitBeam(consumer, matrix, bin, level, cameraX, cameraY, cameraZ, tSec)
        }
    }

    private fun collect(
        level: net.minecraft.client.multiplayer.ClientLevel,
        sendPos: BlockPos, recvPos: BlockPos, tomeKind: ResourceLocation,
    ) {
        val triple = TripleKey.of(sendPos, tomeKind, recvPos)
        if (!seenTriples.add(triple)) return

        val pairKey = PairKey.of(sendPos, recvPos)
        val bin = pairBins.getOrPut(pairKey) {
            // First visit decides direction. The orb direction-commitment rule says all
            // links between a given pair flow the same way, so any subsequent collect for
            // the same pair will use the same (send, recv) ordering.
            val s = OrbClientGeometry.worldCenter(level, sendPos) ?: Vec3.atCenterOf(sendPos)
            val r = OrbClientGeometry.worldCenter(level, recvPos) ?: Vec3.atCenterOf(recvPos)
            BeamBin(
                sendPos = sendPos, recvPos = recvPos,
                sendCentre = s, recvCentre = r,
                timeOffset = timeOffsetFor(pairKey),
            )
        }
        val packed = TomeBeamPalette.colorFor(tomeKind) ?: DEFAULT_PALETTE
        bin.tomes[tomeKind] = packed
    }

    /** Per-pair time offset (seconds) used to decorrelate the arrhythmic warp across
     *  beams. Without this every beam evaluates [phaseWarp] at the same argument, so
     *  adjacent beams visibly pulse in lockstep — the user complaint that prompted this.
     *  Deterministic hash of the orb pair → stable offset that's the same every frame for
     *  the same pair, different across pairs. Range is [0, 100) seconds, large enough vs.
     *  the warp's ~5.7s slowest cycle that two random offsets land in unrelated parts of
     *  the warp curve. */
    private fun timeOffsetFor(pairKey: PairKey): Double {
        val a = pairKey.lo.asLong()
        val b = pairKey.hi.asLong()
        // PCG-style mixing constants — produce a well-distributed hash from two longs.
        val mixed = (a * 6364136223846793005L) xor (b * 1442695040888963407L)
        val unsigned = mixed ushr 11
        return unsigned.toDouble() / (1L shl 53).toDouble() * 100.0
    }

    private fun emitBeam(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        bin: BeamBin,
        level: net.minecraft.client.multiplayer.ClientLevel,
        cameraX: Double, cameraY: Double, cameraZ: Double,
        tSec: Double,
    ) {
        // Camera-relative endpoint coords. SEND first, RECV last — forward-train pulses
        // flow start→end; reverse-train pulses (reciprocal tomes) get these swapped.
        val sx = (bin.sendCentre.x - cameraX).toFloat()
        val sy = (bin.sendCentre.y - cameraY).toFloat()
        val sz = (bin.sendCentre.z - cameraZ).toFloat()
        val rx = (bin.recvCentre.x - cameraX).toFloat()
        val ry = (bin.recvCentre.y - cameraY).toFloat()
        val rz = (bin.recvCentre.z - cameraZ).toFloat()

        // BE-render-distance cull — skip beams whose both endpoints are past the orb BER's
        // view distance from the camera. See [ENDPOINT_VIEW_DISTANCE_SQR].
        val sendDistSqr = sx * sx + sy * sy + sz * sz
        val recvDistSqr = rx * rx + ry * ry + rz * rz
        if (sendDistSqr > ENDPOINT_VIEW_DISTANCE_SQR && recvDistSqr > ENDPOINT_VIEW_DISTANCE_SQR) return

        // Beam axis + length.
        var ax = rx - sx; var ay = ry - sy; var az = rz - sz
        val len = Math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
        if (len < 1e-4f) return
        // Sanity cap — see [MAX_RENDERED_BEAM_LENGTH] for why. Also catches non-finite
        // lengths (NaN/Inf compare false, so a `!(len <= max)` form catches both).
        if (!(len <= MAX_RENDERED_BEAM_LENGTH)) return
        ax /= len; ay /= len; az /= len

        // Camera-facing perpendicular at the midpoint — same for forward and reverse trains
        // (midpoint and perpendicular don't depend on the axis direction).
        val mx = (sx + rx) * 0.5f
        val my = (sy + ry) * 0.5f
        val mz = (sz + rz) * 0.5f
        val vx = -mx; val vy = -my; val vz = -mz
        var rrx = ay * vz - az * vy
        var rry = az * vx - ax * vz
        var rrz = ax * vy - ay * vx
        val rlen = Math.sqrt((rrx * rrx + rry * rry + rrz * rrz).toDouble()).toFloat()
        if (rlen < 1e-4f) {
            val (ox, oy, oz) = if (Math.abs(ay) < 0.9f) Triple(0f, 1f, 0f) else Triple(1f, 0f, 0f)
            rrx = ay * oz - az * oy
            rry = az * ox - ax * oz
            rrz = ax * oy - ay * ox
            val rlen2 = Math.sqrt((rrx * rrx + rry * rry + rrz * rrz).toDouble()).toFloat()
            rrx /= rlen2; rry /= rlen2; rrz /= rlen2
        } else {
            rrx /= rlen; rry /= rlen; rrz /= rlen
        }

        // Perpendicular axes (n1, n2) used to project the per-pulse offset direction into
        // 3D. n1/n2 are perpendicular to the axis but not to its direction — so they're
        // shared between forward and reverse trains (which just flip the axis sign).
        val helperX: Float; val helperY: Float; val helperZ: Float
        if (Math.abs(ay) < 0.9f) { helperX = 0f; helperY = 1f; helperZ = 0f }
        else { helperX = 1f; helperY = 0f; helperZ = 0f }
        val helperDot = helperX * ax + helperY * ay + helperZ * az
        var n1x = helperX - helperDot * ax
        var n1y = helperY - helperDot * ay
        var n1z = helperZ - helperDot * az
        val n1len = Math.sqrt((n1x * n1x + n1y * n1y + n1z * n1z).toDouble()).toFloat()
        n1x /= n1len; n1y /= n1len; n1z /= n1len
        val n2x = ay * n1z - az * n1y
        val n2y = az * n1x - ax * n1z
        val n2z = ax * n1y - ay * n1x

        // Resize scratch arrays if needed.
        if (sampleHaloWidths.size < SAMPLES_PER_PULSE_WITH_CAP) {
            sampleAlphas = FloatArray(SAMPLES_PER_PULSE_WITH_CAP)
            sampleColours = IntArray(SAMPLES_PER_PULSE_WITH_CAP)
            sampleHaloWidths = FloatArray(SAMPLES_PER_PULSE_WITH_CAP)
            sampleCoreWidths = FloatArray(SAMPLES_PER_PULSE_WITH_CAP)
            samplePosX = FloatArray(SAMPLES_PER_PULSE_WITH_CAP)
            samplePosY = FloatArray(SAMPLES_PER_PULSE_WITH_CAP)
            samplePosZ = FloatArray(SAMPLES_PER_PULSE_WITH_CAP)
        }

        val tEff = tSec + bin.timeOffset

        // One pulse train per tome (× 2 if reciprocal). Sorted by tome ResourceLocation so
        // the iteration is stable across frames — reciprocal-tome direction labels never
        // swap mid-session.
        val sortedTomes = bin.tomes.entries.asSequence()
            .sortedBy { it.key.toString() }
            .toList()
        for (entry in sortedTomes) {
            val tomeKind = entry.key
            val colour = entry.value
            val profile = TomePulseProfiles.resolve(tomeKind, level, bin.sendPos, bin.recvPos)
            val spacing = (BASE_PULSE_SPACING / profile.frequency.coerceAtLeast(0.05))
                .coerceIn(MIN_PULSE_SPACING, BASE_PULSE_SPACING * 20.0)
            val speed = (BASE_PULSE_SPEED * profile.progression.coerceAtLeast(0.05))
                .coerceIn(MIN_PULSE_SPEED, BASE_PULSE_SPEED * 20.0)
            val wanderAmp = BASE_WANDER_AMP * (1.0 - profile.cohesion.coerceIn(0.0, 1.0))
            val tomeSeed = tomeKind.hashCode().toLong()

            // Forward train: pulses flow SEND → RECV.
            emitTrain(
                consumer, matrix, tEff,
                spacing, speed, wanderAmp, colour, tomeSeed,
                sx, sy, sz, rx, ry, rz, len,
                n1x, n1y, n1z, n2x, n2y, n2z, rrx, rry, rrz,
            )

            // Reverse train (reciprocal tomes only): pulses flow RECV → SEND. Reuses the
            // perpendicular basis since it doesn't care about axis direction; the angle
            // seed is XOR'd with a direction tag so forward and reverse pulses don't
            // overlap visually at the same K.
            if (profile.reciprocal) {
                emitTrain(
                    consumer, matrix, tEff,
                    spacing, speed, wanderAmp, colour, tomeSeed xor REVERSE_ANGLE_TAG,
                    rx, ry, rz, sx, sy, sz, len,
                    n1x, n1y, n1z, n2x, n2y, n2z, rrx, rry, rrz,
                )
            }
        }
    }

    /** Per-direction angle-seed tag — XOR'd into the reverse train's seed so its pulses
     *  pick different wander directions from the forward train at the same pulse index.
     *  Without this, reciprocal beams render two trains that overlap in axis offsets at
     *  every K and read as a single bidirectional flicker instead of two crossing trains. */
    private const val REVERSE_ANGLE_TAG: Long = 0x5A5A5A5A5A5A5A5AL

    /** Render one pulse train along the (sx,sy,sz)→(rx,ry,rz) axis with the given speed,
     *  spacing, wander, colour, and angle seed. Computes the Kmin..Kmax visible-pulse range
     *  from the warped phase function with the per-train spacing/speed plugged in. */
    private fun emitTrain(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        tEff: Double,
        spacing: Double, speed: Double, wanderAmp: Double, colour: Int, angleSeed: Long,
        sx: Float, sy: Float, sz: Float, rx: Float, ry: Float, rz: Float, len: Float,
        n1x: Float, n1y: Float, n1z: Float, n2x: Float, n2y: Float, n2z: Float,
        rrx: Float, rry: Float, rrz: Float,
    ) {
        val timePhase = speed * tEff
        // phase(L, t) = (L − v·t)/s + warp. Pulse K's leading edge at phase = K+1, trailing
        // at phase = K. Visible K: those whose [L_tail, L_lead] intersects [0, len].
        val phaseAt0 = -timePhase / spacing + phaseWarp(tEff)
        val phaseAtLen = (len - timePhase) / spacing + phaseWarp(tEff - len / speed)
        val kMin = Math.floor(Math.min(phaseAt0, phaseAtLen)).toLong() - 1
        val kMax = Math.floor(Math.max(phaseAt0, phaseAtLen)).toLong() + 1

        for (k in kMin..kMax) {
            emitPulse(
                consumer, matrix, k, tEff, timePhase, len,
                sx, sy, sz, rx, ry, rz,
                n1x, n1y, n1z, n2x, n2y, n2z,
                rrx, rry, rrz,
                spacing, speed, wanderAmp, colour, angleSeed,
            )
        }
    }

    /** Render a single pulse `k` of one train. The train's per-tome spacing/speed/wander
     *  scales the phase math; the seed differentiates this train's wander directions from
     *  any other concurrent train (other tomes, reverse direction). The pulse's L-range is
     *  computed from the warped phase function, clipped to the beam, sampled at
     *  [SAMPLES_PER_PULSE] points, then capped with a half-circle endcap past the bright
     *  leading edge for a comet-tip silhouette. */
    private fun emitPulse(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        k: Long, tEff: Double, timePhase: Double, len: Float,
        sx: Float, sy: Float, sz: Float, rx: Float, ry: Float, rz: Float,
        n1x: Float, n1y: Float, n1z: Float, n2x: Float, n2y: Float, n2z: Float,
        rrx: Float, rry: Float, rrz: Float,
        spacing: Double, speed: Double, wanderAmp: Double, pulseColour: Int, angleSeed: Long,
    ) {
        // L position of phase = k, accounting for the warp via one Newton step (close
        // enough for visual purposes — the warp varies on multi-second time scales while
        // a pulse traverses its own length in a fraction of a second).
        val tailApprox = k * spacing + timePhase
        val leadApprox = tailApprox + spacing
        val tailL = tailApprox - spacing * phaseWarp(tEff - tailApprox / speed)
        val leadL = leadApprox - spacing * phaseWarp(tEff - leadApprox / speed)
        // Skip pulses entirely off the beam.
        if (leadL < 0.0 || tailL > len) return
        val renderTail = Math.max(0.0, tailL)
        val renderLead = Math.min(len.toDouble(), leadL)
        if (renderLead <= renderTail) return

        // Per-pulse offset direction — mixed with this train's angle seed so different
        // tomes (and the forward vs reverse train of a reciprocal tome) don't share the
        // same per-K wander direction.
        val angle = pulseAngleFor(k, angleSeed, tEff)
        val cosA = Math.cos(angle).toFloat()
        val sinA = Math.sin(angle).toFloat()

        val invSegments = 1.0 / (SAMPLES_PER_PULSE - 1)
        val renderExtent = renderLead - renderTail
        for (i in 0 until SAMPLES_PER_PULSE) {
            val tLocal = i * invSegments
            val L = renderTail + tLocal * renderExtent
            // u within this pulse — uses the **unclipped** tail so a partially-clipped
            // pulse still has the correct alpha/colour curve.
            val u = ((L - tailL) / spacing).coerceIn(0.0, 1.0)
            val pulse = pulseCurve(u)

            // Offset magnitude — tapered by sin(π · L / len) so the pulse sits on-axis
            // at each orb and arcs off-axis through the middle. wanderAmp is this train's
            // per-tome amplitude (BASE * (1 − cohesion)).
            val offsetMag = wanderAmp * Math.sin(Math.PI * (L / len))
            val ox = (offsetMag * (cosA * n1x + sinA * n2x)).toFloat()
            val oy = (offsetMag * (cosA * n1y + sinA * n2y)).toFloat()
            val oz = (offsetMag * (cosA * n1z + sinA * n2z)).toFloat()

            val tGlobal = (L / len).toFloat()
            val baseX = sx + (rx - sx) * tGlobal
            val baseY = sy + (ry - sy) * tGlobal
            val baseZ = sz + (rz - sz) * tGlobal

            samplePosX[i] = baseX + ox
            samplePosY[i] = baseY + oy
            samplePosZ[i] = baseZ + oz
            sampleAlphas[i] = pulse.toFloat()
            sampleHaloWidths[i] = lerp(HALO_HALF_WIDTH_MIN, HALO_HALF_WIDTH_MAX, pulse).toFloat()
            sampleCoreWidths[i] = lerp(CORE_HALF_WIDTH_MIN, CORE_HALF_WIDTH_MAX, pulse).toFloat()
            sampleColours[i] = pulseColour
        }

        // Half-circle endcap past the bright leading edge. Without it, the brightest sample
        // (u=1) is the last quad's outer vertex — the strip cuts off as a flat wall instead
        // of rounding to a point. The cap continues the strip from L=renderLead by up to
        // CAP_LENGTH blocks, with alpha and width both tracing a quarter-circle so the tip
        // closes off as a glowing hemispherical bullet. Only emitted when the pulse's true
        // leading edge sits inside the beam (otherwise the cap would extend past the recv
        // orb); the available extent is clipped by the distance to len.
        val capExtent = if (leadL <= len.toDouble()) Math.min(CAP_LENGTH, len.toDouble() - renderLead) else 0.0
        val capCount = if (capExtent > 1e-6) CAP_SAMPLES else 0
        for (j in 0 until capCount) {
            // capT runs (0, 1] across the cap — j=0 is the first NEW sample, NOT a duplicate
            // of the main strip's last sample at L=renderLead. Quad emit walks main[N-1] →
            // cap[0] → cap[1] … cap[capCount-1], so the seam is implicit.
            val capT = (j + 1).toDouble() / CAP_SAMPLES
            val capFactor = Math.sqrt(1.0 - capT * capT)
            val L = renderLead + capT * capExtent

            val offsetMag = wanderAmp * Math.sin(Math.PI * (L / len))
            val ox = (offsetMag * (cosA * n1x + sinA * n2x)).toFloat()
            val oy = (offsetMag * (cosA * n1y + sinA * n2y)).toFloat()
            val oz = (offsetMag * (cosA * n1z + sinA * n2z)).toFloat()

            val tGlobal = (L / len).toFloat()
            val baseX = sx + (rx - sx) * tGlobal
            val baseY = sy + (ry - sy) * tGlobal
            val baseZ = sz + (rz - sz) * tGlobal

            val idx = SAMPLES_PER_PULSE + j
            samplePosX[idx] = baseX + ox
            samplePosY[idx] = baseY + oy
            samplePosZ[idx] = baseZ + oz
            sampleAlphas[idx] = capFactor.toFloat()
            sampleHaloWidths[idx] = (HALO_HALF_WIDTH_MAX * capFactor).toFloat()
            sampleCoreWidths[idx] = (CORE_HALF_WIDTH_MAX * capFactor).toFloat()
            sampleColours[idx] = pulseColour
        }

        // Walk segments, emit halo + core quads — main strip continues straight into the cap.
        val emitSegments = SAMPLES_PER_PULSE + capCount - 1
        for (i in 0 until emitSegments) {
            val pxs = samplePosX[i]
            val pys = samplePosY[i]
            val pzs = samplePosZ[i]
            val pxe = samplePosX[i + 1]
            val pye = samplePosY[i + 1]
            val pze = samplePosZ[i + 1]
            val alphaS = sampleAlphas[i]
            val alphaE = sampleAlphas[i + 1]
            val cS = sampleColours[i]
            val cE = sampleColours[i + 1]

            val haloAlphaS = (alphaS * HALO_ALPHA * 255f).toInt().coerceIn(0, 255)
            val haloAlphaE = (alphaE * HALO_ALPHA * 255f).toInt().coerceIn(0, 255)
            emitTaperedQuad(consumer, matrix, pxs, pys, pzs, pxe, pye, pze,
                rrx, rry, rrz,
                sampleHaloWidths[i], sampleHaloWidths[i + 1],
                (cS shr 16) and 0xFF, (cS shr 8) and 0xFF, cS and 0xFF, haloAlphaS,
                (cE shr 16) and 0xFF, (cE shr 8) and 0xFF, cE and 0xFF, haloAlphaE)

            val coreAlphaS = (alphaS * CORE_ALPHA * 255f).toInt().coerceIn(0, 255)
            val coreAlphaE = (alphaE * CORE_ALPHA * 255f).toInt().coerceIn(0, 255)
            emitTaperedQuad(consumer, matrix, pxs, pys, pzs, pxe, pye, pze,
                rrx, rry, rrz,
                sampleCoreWidths[i], sampleCoreWidths[i + 1],
                CORE_R, CORE_G, CORE_B, coreAlphaS,
                CORE_R, CORE_G, CORE_B, coreAlphaE)
        }
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    /** Emit a billboarded quad between `(x1,y1,z1)` and `(x2,y2,z2)` with the given
     *  perpendicular `right`, **separate half-widths** at each end (so a pulse-crest
     *  vertex genuinely renders fatter than its trough neighbours), and per-vertex colour
     *  + alpha. CCW winding viewed from the camera. */
    private fun emitTaperedQuad(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        rrx: Float, rry: Float, rrz: Float,
        halfWidthStart: Float, halfWidthEnd: Float,
        rS: Int, gS: Int, bS: Int, alphaS: Int,
        rE: Int, gE: Int, bE: Int, alphaE: Int,
    ) {
        val oxS = rrx * halfWidthStart; val oyS = rry * halfWidthStart; val ozS = rrz * halfWidthStart
        val oxE = rrx * halfWidthEnd;   val oyE = rry * halfWidthEnd;   val ozE = rrz * halfWidthEnd
        consumer.vertex(matrix, x1 + oxS, y1 + oyS, z1 + ozS).color(rS, gS, bS, alphaS).endVertex()
        consumer.vertex(matrix, x2 + oxE, y2 + oyE, z2 + ozE).color(rE, gE, bE, alphaE).endVertex()
        consumer.vertex(matrix, x2 - oxE, y2 - oyE, z2 - ozE).color(rE, gE, bE, alphaE).endVertex()
        consumer.vertex(matrix, x1 - oxS, y1 - oyS, z1 - ozS).color(rS, gS, bS, alphaS).endVertex()
    }

    /** Per-frame state for one unordered orb pair — fixed send/recv endpoints (both as
     *  world Vec3 for rendering and BlockPos for the per-tome profile adjuster lookup)
     *  plus the per-tome palette: each entry is a (kind → packed-RGB) pair that becomes its
     *  own pulse train in [emitBeam]. */
    private class BeamBin(
        val sendPos: BlockPos, val recvPos: BlockPos,
        val sendCentre: Vec3, val recvCentre: Vec3,
        val timeOffset: Double,
    ) {
        val tomes: MutableMap<ResourceLocation, Int> = HashMap()
    }

    /** Canonical unordered pair of orb positions. */
    private data class PairKey(val lo: BlockPos, val hi: BlockPos) {
        companion object {
            fun of(a: BlockPos, b: BlockPos): PairKey =
                if (a.asLong() <= b.asLong()) PairKey(a, b) else PairKey(b, a)
        }
    }

    /** PairKey + tomeKind — guards against counting the same link twice when both
     *  endpoints are loaded and each side iterates its own outgoing/incoming map. */
    private data class TripleKey(val lo: BlockPos, val tome: ResourceLocation, val hi: BlockPos) {
        companion object {
            fun of(a: BlockPos, tome: ResourceLocation, b: BlockPos): TripleKey =
                if (a.asLong() <= b.asLong()) TripleKey(a, tome, b) else TripleKey(b, tome, a)
        }
    }
}
