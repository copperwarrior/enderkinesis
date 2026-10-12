package org.shipwrights.enderkinesis.remnant

/** The mutable state motion needs — implemented by both the authoritative
 *  server blob and the client's predicted copy, so both run the *same*
 *  integrator rather than two hand-synced transcriptions of it. */
interface RemnantBody {
    var x: Double
    var y: Double
    var z: Double
    var vx: Double
    var vy: Double
    var vz: Double
    var radius: Double
    var onSurface: Boolean

    /** Ticks spent off a surface, bled back down on contact rather than
     *  cleared. Drives how readily the particle grips. */
    var airborneTicks: Int

    /** Seeping through solid geometry because it could not get round it.
     *  Shared state, not a server-only decision: the client predicts with
     *  the same integrator, and would spend every tick shoving the particle
     *  back out of a block the server is deliberately holding it inside. */
    var seeping: Boolean

    /** Ticks this particle has been settled. Drives how often it bothers to
     *  probe the world again; not persisted, since waking early is harmless. */
    var restTicks: Int

    /** Ticks left of a rebound, during which the particle does not grip or drag on the surface it left. */
    var reboundTicks: Int

    /** Unit normal of the surface this particle rests on, or zero when
     *  airborne. Drives adhesion and the tangential slide. */
    var normalX: Double
    var normalY: Double
    var normalZ: Double
}

/**
 * Shared particle integrator — adhesion, gravity, and surface collision.
 *
 * **Collision is against the real collision shapes**, not the block grid.
 * Contact comes from a smoothed signed-distance field over the actual
 * voxel shapes nearby ([SurfaceProbe]), so slabs, stairs, fences, and
 * panes are hit where they really are — and, just as importantly, the
 * normal it returns rotates *continuously* around edges and corners
 * instead of snapping to one of six axis directions, so the mass slides
 * round an edge instead of catching on it.
 *
 * **Motion is surface-relative.** Once in contact, velocity is split into
 * the component into the surface (removed) and the component along it
 * (kept, with friction). The mass therefore *slides along* whatever it is
 * touching — down the slope of a stair, around the curve of an edge —
 * rather than driving into it and stopping.
 *
 * Deliberately one implementation used by both sides: the client predicts
 * between syncs, and prediction is only useful if it reproduces the server
 * exactly.
 */
object IrreverantRemnantMotion {

    /** Result of probing the world's collision geometry near a point. */
    class Contact {
        /** Signed distance to the surface union — negative inside. */
        @JvmField var distance: Double = 0.0
        @JvmField var nx: Double = 0.0
        @JvmField var ny: Double = 0.0
        @JvmField var nz: Double = 0.0
        @JvmField var valid: Boolean = false
    }

    /** Samples the smoothed distance field at a point, writing into [out].
     *  Implemented per side so each can use its own level handle. */
    fun interface SurfaceProbe {
        fun probe(x: Double, y: Double, z: Double, range: Double, out: Contact)
    }

    const val GRAVITY = 0.018
    const val MAX_FALL_SPEED = 0.35

    /** Gravity retained while stuck. Tiny — the mass creeps rather than falls. */
    const val ADHERED_GRAVITY_FACTOR = 0.10

    /** Pull holding a stuck particle against its surface, so the mass clings
     *  to walls and overhangs instead of peeling off. Deliberately gentle:
     *  only particles actually touching feel it, and too much drags the
     *  whole body down into a single flat layer. */
    const val ADHESION_PULL = 0.022

    /**
     * Fraction of along-surface velocity kept per tick while in contact.
     *
     * Heavy. A particle that has settled should be reluctant to move at all,
     * so the mass has to pull itself free a bit at a time rather than
     * gliding — that reluctance, against weak cohesion, is what stretches a
     * moving body out into a trail instead of sliding it as one piece.
     */
    const val SLIDE_FRICTION = 0.55

    /** Airborne damping — barely any, so a falling drip still falls. */
    const val AIR_DRAG = 0.96

    /** How far past its own radius a particle still feels a surface to
     *  cling to. */
    const val ADHESION_MARGIN = 0.28

    /** Half the side of a particle's cube, in units of radius. */
    const val CUBE_HALF_EXTENT = 1.0

    /** How far a resting cube sinks into its surface, as a fraction of its half-extent. A flush face z-fights with
     *  the block under it. */
    const val BED_FRACTION = 0.1

    /** Resting gap between a particle's centre and the surface. */
    const val CONTACT_RADIUS_FACTOR: Double = CUBE_HALF_EXTENT * (1.0 - BED_FRACTION)

    /** How fast the contact normal follows the surface. Blending rather
     *  than snapping keeps adhesion continuous as a particle crosses an edge. */
    const val NORMAL_BLEND = 0.25

    // For each thread: the server thread and the client thread both integrate in singleplayer.
    private val contactScratch: ThreadLocal<Contact> = ThreadLocal.withInitial { Contact() }

    /**
     * Applies gravity, moves the particle, and resolves contact against the
     * real collision shapes. Steering is NOT included: it needs goals, which
     * live only on the server; the client picks its result up through synced
     * velocity.
     */
    fun integrate(body: RemnantBody, probe: SurfaceProbe) {
        if (body.reboundTicks > 0) body.reboundTicks--
        // A settled particle costs one collision probe per tick to be told
        // it is still settled, and a settled mass is the normal state. It
        // sleeps instead, waking on its own every [REST_RECHECK] ticks so
        // that ground pulled out from under it is still noticed promptly.
        // Neighbour forces are applied before this and raise the speed
        // directly, so anything disturbing the mass wakes it the same tick.
        val restSq = body.vx * body.vx + body.vy * body.vy + body.vz * body.vz
        if (body.onSurface && !body.seeping && restSq < REST_SPEED * REST_SPEED) {
            body.restTicks++
            if (body.restTicks < REST_RECHECK) {
                driftAsleep(body)
                return
            }
            body.restTicks = 0
        } else {
            body.restTicks = 0
        }

        // Buried in rock pulls like clinging to it — a seeping particle
        // creeps toward its goal rather than sinking through the floor.
        val clinging = body.onSurface || body.seeping
        val gravity = if (clinging) GRAVITY * ADHERED_GRAVITY_FACTOR else GRAVITY
        body.vy -= gravity
        if (body.vy < -MAX_FALL_SPEED) body.vy = -MAX_FALL_SPEED

        // Hold against the surface found last tick — clinging to ceilings
        // and overhangs depends on this out-pacing the residual gravity.
        if (body.onSurface && !body.seeping) {
            val grip = grip(body) * ADHESION_PULL
            body.vx -= body.normalX * grip
            body.vy -= body.normalY * grip
            body.vz -= body.normalZ * grip
        }

        // Substepped, because a falling particle covers several times its
        // own contact band in one tick. Move the whole way at once and it
        // lands deep inside geometry, where the NEAREST face of the block it
        // is in can be the far one — so contact resolution ejects it out the
        // other side. That is the clipping through walls: not a missing
        // collision, an overshoot resolved to the wrong side. Anything
        // thinner than the step, like a slab, flips reliably.
        val speed = Math.sqrt(body.vx * body.vx + body.vy * body.vy + body.vz * body.vz)
        // Paced by how thick the particle is along its contact normal, which
        // is what tunnelling actually depends on. NOT by the resting gap:
        // that is signed, and goes negative once a particle beds past its
        // own centre, which inverts the comparison and yields zero steps —
        // no movement and no contact resolution at all.
        val stride = body.radius * CUBE_HALF_EXTENT * SUBSTEP_FRACTION
        val steps = if (stride <= 0.0 || speed <= stride) {
            1
        } else {
            Math.ceil(speed / stride).toInt().coerceIn(1, MAX_SUBSTEPS)
        }
        val fraction = 1.0 / steps
        for (step in 0 until steps) {
            body.x += body.vx * fraction
            body.y += body.vy * fraction
            body.z += body.vz * fraction
            // Damping is per tick, not per substep, or a fast particle would
            // be braked several times as hard as a slow one.
            resolveContact(body, probe, step == steps - 1)
        }
    }

    /** Probes the real geometry and responds: push out of penetration, drop
     *  the into-surface velocity, and keep the along-surface part. */
    private fun resolveContact(body: RemnantBody, probe: SurfaceProbe, damp: Boolean) {
        val contact = contactScratch.get()
        val contactRadius = body.radius * CONTACT_RADIUS_FACTOR
        // Reach shrinks the longer the particle has been falling, so a drip
        // in free fall stops snagging on ledges it merely passes near.
        val reach = contactRadius + ADHESION_MARGIN * grip(body)
        probe.probe(body.x, body.y, body.z, reach + PROBE_PADDING, contact)
        if (!contact.valid || contact.distance > reach) {
            body.onSurface = false
            // Decayed, not cleared, so a drip that skims a ledge keeps its grip direction.
            body.normalX *= AIRBORNE_NORMAL_DECAY
            body.normalY *= AIRBORNE_NORMAL_DECAY
            body.normalZ *= AIRBORNE_NORMAL_DECAY
            if (body.airborneTicks < MAX_AIRBORNE_TICKS) body.airborneTicks++
            if (damp) {
                body.vx *= AIR_DRAG
                body.vy *= AIR_DRAG
                body.vz *= AIR_DRAG
            }
            return
        }

        if (body.seeping) {
            // Penetration is the point: no push-out, no removal of the
            // into-surface velocity. Only a heavy drag, so the mass oozes
            // through at a fraction of crawling pace.
            body.onSurface = true
            if (damp) {
                body.vx *= SEEP_DRAG
                body.vy *= SEEP_DRAG
                body.vz *= SEEP_DRAG
            }
            return
        }

        // Ease the normal toward the sampled one instead of snapping to it.
        val bx = body.normalX + (contact.nx - body.normalX) * NORMAL_BLEND
        val by = body.normalY + (contact.ny - body.normalY) * NORMAL_BLEND
        val bz = body.normalZ + (contact.nz - body.normalZ) * NORMAL_BLEND
        val blen = Math.sqrt(bx * bx + by * by + bz * bz)
        val nx: Double
        val ny: Double
        val nz: Double
        if (blen > 1.0e-6) {
            nx = bx / blen; ny = by / blen; nz = bz / blen
        } else {
            nx = contact.nx; ny = contact.ny; nz = contact.nz
        }
        body.normalX = nx
        body.normalY = ny
        body.normalZ = nz

        // Push out of any penetration, along the true surface normal.
        val overlap = contactRadius - contact.distance
        if (overlap > 0.0) {
            body.x += nx * overlap
            body.y += ny * overlap
            body.z += nz * overlap
        }

        val into = body.vx * nx + body.vy * ny + body.vz * nz
        // A fast hit rebounds with part of its speed. Adhesion and slide
        // friction would cancel that at once, so for a few ticks the particle
        // neither grips nor drags, and it gets clear of the surface.
        if (into < -BOUNCE_MIN_IMPACT && body.reboundTicks == 0) {
            body.vx -= nx * into * (1.0 + RESTITUTION)
            body.vy -= ny * into * (1.0 + RESTITUTION)
            body.vz -= nz * into * (1.0 + RESTITUTION)
            body.reboundTicks = REBOUND_TICKS
            body.onSurface = false
            return
        }
        // Split velocity about the surface: kill what drives into it, keep
        // what runs along it. This is the slide.
        if (into < 0.0) {
            body.vx -= nx * into
            body.vy -= ny * into
            body.vz -= nz * into
        }
        if (body.reboundTicks > 0) {
            // Leaving after a bounce: still kept out of the surface, but as an airborne particle.
            body.onSurface = false
            if (damp) {
                body.vx *= AIR_DRAG
                body.vy *= AIR_DRAG
                body.vz *= AIR_DRAG
            }
            return
        }
        body.onSurface = true
        // Regained gradually, not on the instant of touching: a drip that has
        // fallen a long way skids before it takes hold, where one that barely
        // left the surface grips again immediately.
        body.airborneTicks = Math.max(0, body.airborneTicks - REGRIP_RATE)
        if (damp) {
            body.vx *= SLIDE_FRICTION
            body.vy *= SLIDE_FRICTION
            body.vz *= SLIDE_FRICTION
        }
    }

    /**
     * Sleep skips only the collision probe, not the motion. A frozen particle
     * still gains steering velocity with no friction against it, so it woke,
     * lunged, and slept again: a stop-go on every other tick. Into-surface
     * velocity is dropped along the last normal, as contact would.
     */
    private fun driftAsleep(body: RemnantBody) {
        val into = body.vx * body.normalX + body.vy * body.normalY + body.vz * body.normalZ
        if (into < 0.0) {
            body.vx -= body.normalX * into
            body.vy -= body.normalY * into
            body.vz -= body.normalZ * into
        }
        body.x += body.vx
        body.y += body.vy
        body.z += body.vz
        body.vx *= SLIDE_FRICTION
        body.vy *= SLIDE_FRICTION
        body.vz *= SLIDE_FRICTION
    }

    /** Projects a vector onto the plane of a surface normal — used by
     *  steering so a mass drives *along* what it is stuck to rather than
     *  into it. Returns the tangential part in [out] (x, y, z). */
    fun projectOntoSurface(
        vx: Double, vy: Double, vz: Double,
        nx: Double, ny: Double, nz: Double,
        out: DoubleArray,
    ) {
        val into = vx * nx + vy * ny + vz * nz
        out[0] = vx - nx * into
        out[1] = vy - ny * into
        out[2] = vz - nz * into
    }

    /**
     * How much of its grip a particle still has, 1 fresh off a surface down
     * to [GRIP_FLOOR] after a long fall.
     *
     * Goo that has been in the air a while is travelling, not clinging, and
     * should behave that way — a long drip ought to fall past an overhang
     * rather than catch on it, and land with a skid rather than sticking on
     * contact. A constant adhesion made every particle equally eager to grab
     * whatever it passed.
     *
     * Public because it governs every kind of hold, not just contact with a
     * surface: what actually keeps an airborne drip attached is the tension
     * on its parent link, and applying this only to surface adhesion left
     * that at full strength — so the decay was running and changing nothing
     * anybody could see.
     */
    fun grip(body: RemnantBody): Double {
        if (body.airborneTicks <= 0) return 1.0
        val t = body.airborneTicks.toDouble() / GRIP_DECAY_TICKS
        return Math.max(GRIP_FLOOR, 1.0 - t)
    }

    /** Grip decay while airborne, its floor, how fast it is won back on
     *  contact, and the cap that keeps the counter bounded (Rule 2). */
    private const val GRIP_DECAY_TICKS = 24.0
    private const val GRIP_FLOOR = 0.25
    private const val REGRIP_RATE = 3
    private const val MAX_AIRBORNE_TICKS = 200

    /** Bounce: the speed into a surface at which a particle rebounds (crawling is well under it; a fall of
     *  about half a block reaches it), the share of that speed it keeps, and how long it then stays loose. */
    const val BOUNCE_MIN_IMPACT = 0.12
    const val RESTITUTION = 0.4
    private const val REBOUND_TICKS = 4

    /** Extra probe range so the field has geometry to smooth against even
     *  when the particle is right up on a face. */
    private const val PROBE_PADDING = 0.45

    /** Per-tick decay of the contact normal once a particle leaves a surface. */
    private const val AIRBORNE_NORMAL_DECAY = 0.90

    /** Fraction of the contact band a single substep may cover, and the cap
     *  on substeps so a fast particle costs bounded probe work (Rule 2). At
     *  the cap this covers a step of roughly [MAX_FALL_SPEED]. */
    /** Velocity retained per tick while inside solid geometry. Heavy: this
     *  is meant to read as forcing through rock, not swimming. */
    private const val SEEP_DRAG = 0.35

    /** Speed below which a particle in contact counts as settled, and how
     *  long it may sleep before re-probing. */
    const val REST_SPEED = 0.003
    private const val REST_RECHECK = 20

    private const val SUBSTEP_FRACTION = 0.8
    private const val MAX_SUBSTEPS = 4
}
