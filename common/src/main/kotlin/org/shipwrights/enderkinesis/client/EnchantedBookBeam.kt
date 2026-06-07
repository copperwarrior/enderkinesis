package org.shipwrights.enderkinesis.client

import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Reusable enchanted-book bezier beam — generalisation of the Wylland Tome's beam.
 *
 * A [BeamPath] is the live, mutable description of one beam: a quadratic bezier from [start]
 * through [control] to [end], a cone [radius] at the start that linearly tapers to zero at the
 * end, and a [flowRate] (Δt per particle tick) that controls how fast each particle traverses
 * the curve. All four geometry fields are `@Volatile` so the particle thread sees consistent
 * snapshots while the client-game thread updates them.
 *
 * A [BeamPath] is registered in [BeamRegistry] under a numeric id. Each
 * [EnchantedBookBeamParticle] carries that id encoded in its spawn parameters, looks up the
 * path each tick, and re-evaluates its world position from the *current* curve — so the
 * particles follow a moving beam (player turning, ship drifting) instead of trailing on a
 * stale segment captured at spawn time.
 *
 * The registry holds *all* live beams in the client. Consumers:
 *  - The Wylland Tome registers exactly one path per local player while a grab is active.
 *  - The Tome of Signal's orb network registers one path per (send orb → receiver) pair while
 *    both orbs are loaded.
 *
 * Clearing a path (or removing it from the registry) makes every bound particle vanish on its
 * next tick, so beams snap off cleanly with no lingering ghosts.
 */
class BeamPath(
    @Volatile var start: Vec3? = null,
    @Volatile var control: Vec3? = null,
    @Volatile var end: Vec3? = null,
    @Volatile var radius: Double = 0.0,
    @Volatile var flowRate: Double = 0.15,
    /** ARGB-ish 0xRRGGBB packed accent colour, or null for "no accent — all particles render
     *  in the default white-ish wash". Decided once at particle spawn time (see
     *  [EnchantedBookBeamParticle.init]) so individual glyphs commit to one colour for their
     *  whole lifetime — switching mid-flight would read as flicker. */
    @Volatile var accentColor: Int? = null,
    /** Per-particle probability (`0.0..1.0`) of using [accentColor] instead of the default
     *  white wash. Ignored when [accentColor] is null. 0.20 = 20% of glyphs in the beam. */
    @Volatile var accentChance: Double = 0.0,
    /** Per-particle opacity (`0..1`). The default 0.4 matches the Wylland Tome's light-haze
     *  feel; cross-orb beams use higher values for visibility against distant terrain. */
    @Volatile var alpha: Float = 0.4f,
    /** Cone profile along the beam — controls how [radius] varies with `t`. */
    @Volatile var profile: BeamProfile = BeamProfile.LINEAR_TAPER,
) {
    fun clear() {
        start = null
        control = null
        end = null
    }
}

/**
 * Radial profile of a beam — how far each glyph offsets from the bezier curve as `t` advances.
 *
 *  - [LINEAR_TAPER] — full [BeamPath.radius] at the start, linearly tapers to zero at the end.
 *    Reads as a sweeping cone, fat where it leaves the source, pinpoint where it touches the
 *    target. Wylland Tome uses this.
 *  - [HOURGLASS] — full radius at both ends, pinching to zero in the middle. Reads as a
 *    two-way connection where both endpoints are equally significant. Cross-orb beams use this.
 */
enum class BeamProfile { LINEAR_TAPER, HOURGLASS }

/**
 * Process-wide registry of active beams. Keys are opaque [Long] ids handed out via [allocate]
 * or chosen by the caller (the Wylland Tome uses [PLAYER_BEAM_ID], the orb network derives
 * ids from the (send-pos, receiver-pos) pair).
 */
object BeamRegistry {
    /** Reserved id for the Wylland Tome's player-bound beam — there is at most one. */
    const val PLAYER_BEAM_ID: Long = 0L

    private val paths = ConcurrentHashMap<Long, BeamPath>()
    private val nextId = AtomicLong(1L shl 32)  // start above any deterministic id space

    /** Allocate a fresh id when the caller doesn't have a stable derivation. */
    fun allocate(): Long = nextId.getAndIncrement()

    /** Install (or replace) the path at [id], returning the path so the caller can update it. */
    fun put(id: Long, path: BeamPath): BeamPath {
        paths[id] = path
        return path
    }

    /** Get the path at [id], or null if no longer registered (bound particles vanish in this case). */
    fun get(id: Long): BeamPath? = paths[id]

    /** Remove the path at [id]; bound particles vanish on their next tick. */
    fun remove(id: Long): BeamPath? = paths.remove(id)

    /** All currently-registered ids. Snapshot — safe to iterate. */
    fun ids(): Set<Long> = paths.keys.toSet()
}
