package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import org.shipwrights.enderkinesis.network.EchoCannonNetwork
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.util.SculkSpread
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * Echo Cannon BE. Owns the charge-up timer and the single-shot fire
 * sequence:
 *
 *  - **Idle.** Block has no charge active. `chargeTicks = -1`.
 *  - **Charging.** Triggered by a rising redstone edge. `chargeTicks`
 *    counts up from 0 to [chargeTickTarget] (= [CHARGE_TICKS] ±1, so a
 *    bank of cannons fed off the same clock doesn't fire in perfect
 *    lockstep). The warden's `WARDEN_SONIC_CHARGE` sound plays on tick
 *    0; the charge is **committed** once started — losing the
 *    redstone signal mid-charge no longer cancels, so a one-tick pulse
 *    is enough to fire.
 *  - **Fire.** On the tick `chargeTicks` reaches [chargeTickTarget]:
 *    trace the beam, damage entities, call [SculkSpread.start] at the
 *    hit, play `WARDEN_SONIC_BOOM` and broadcast the trace.
 *  - **Cooldown.** [COOLDOWN_TICKS] = 5 s. The muzzle puffs smoke
 *    every few ticks during cooldown so the cannon visibly vents
 *    before it can fire again.
 */
class EchoCannonBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.ECHO_CANNON.get(), pos, state), Commandable {

    /** Staff trigger — start a charge exactly like a redstone rising edge
     *  (same cooldown / already-charging gates); the committed charge then
     *  fires through the normal [serverTick] sequence. */
    override fun executeStaffCommand(
        player: net.minecraft.world.entity.player.Player,
        hostShip: org.valkyrienskies.core.api.ships.Ship?,
        contextStack: net.minecraft.world.item.ItemStack,
    ) {
        startCharge()
    }

    // The cannon fires along its facing — the command is positionless, so Shulkonaut
    // orders start a charge regardless of the aim's shape (same rising-edge gates).
    override fun executeAutoCommand(
        target: net.minecraft.world.entity.Entity,
        hostShip: org.valkyrienskies.core.api.ships.Ship?,
    ) {
        startCharge()
    }

    override fun executeAutoCommandAt(
        targetPos: net.minecraft.world.phys.Vec3,
        targetAnchor: net.minecraft.core.BlockPos?,
        hostShip: org.valkyrienskies.core.api.ships.Ship?,
    ) {
        startCharge()
    }

    private fun startCharge() {
        if (cooldownTicks > 0 || chargeTicks >= 0) return
        chargeTicks = 0
    }

    /** -1 = idle, 0..[chargeTickTarget]-1 = charging, ≥[chargeTickTarget]
     *  = ready-to-fire this tick (consumed in `serverTick`). */
    private var chargeTicks: Int = -1
    /** Per-shot charge target. Re-rolled at the start of each charge
     *  to [CHARGE_TICKS] ±1, so neighbouring cannons fed from a shared
     *  clock don't all hit "fire" the same tick. */
    private var chargeTickTarget: Int = CHARGE_TICKS
    /** Down-counter after a successful shot. */
    private var cooldownTicks: Int = 0
    /** Mirror of the redstone signal so the BE knows when an edge fires. */
    private var poweredCached: Boolean = false

    /** Game-tick this cannon last fired on — reads as "when to start the
     *  barrel-recoil animation" on the client. Synced via [getUpdateTag] /
     *  [getUpdatePacket] so a client that just entered range sees the tail
     *  end of an in-progress recoil correctly. [NO_RECOIL] = no recoil
     *  animation (idle / never fired). */
    var clientRecoilStartTick: Long = NO_RECOIL
        private set

    /** Game-tick this cannon started its charge cycle on — reads as "when
     *  to start the barrel-forward push" on the client. Cleared to
     *  [NO_CHARGE] on fire so the BER stops interpolating charge-forward
     *  once the recoil takes over. */
    var clientChargeStartTick: Long = NO_CHARGE
        private set

    fun onNeighborSignal(powered: Boolean) {
        if (powered == poweredCached) return
        poweredCached = powered
        if (!powered) {
            // Falling edge — DO NOT cancel an in-progress charge. The
            // cannon commits to the shot the moment it starts charging,
            // so a single-tick pulse fires it. We still mirror the
            // signal state so the next rising edge can re-trigger
            // after the cooldown.
            return
        }
        if (cooldownTicks > 0 || chargeTicks >= 0) return
        chargeTicks = 0
    }

    fun serverTick(level: Level, pos: BlockPos, state: BlockState) {
        if (level !is ServerLevel) return
        if (cooldownTicks > 0) {
            // Smoke-vent particles at the muzzle during cooldown so the
            // cannon visibly "smokes" between shots. Cadence keeps the
            // count manageable per tick even with multiple cannons.
            if (cooldownTicks % COOLDOWN_SMOKE_PERIOD == 0) {
                spawnCooldownSmoke(level, pos, state)
            }
            cooldownTicks--
            return
        }
        if (chargeTicks < 0) return

        if (chargeTicks == 0) {
            // Edge-trigger the charge SFX and pick this shot's exact
            // target tick (CHARGE_TICKS ± 1).
            chargeTickTarget = CHARGE_TICKS + (level.random.nextInt(3) - 1)
            level.playSound(
                null, pos, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.BLOCKS,
                1.5f, 1.0f,
            )
            // Stamp the charge-start tick and push it to nearby clients so the
            // BER starts easing the barrel forward on this same server tick.
            clientChargeStartTick = level.gameTime
            markChangedAndSync()
        }
        spawnChargeParticles(level, pos, state)
        chargeTicks++

        if (chargeTicks >= chargeTickTarget) {
            fire(level, pos, state)
            chargeTicks = -1
            cooldownTicks = COOLDOWN_TICKS
            // Stamp the recoil-start tick and push it to nearby clients so the
            // BER can start the barrel-recoil animation right away. Chunk-mesh
            // resync from vanilla only happens on chunk load — without this
            // active sync the animation would only fire for players who
            // enter range after firing.
            clientRecoilStartTick = level.gameTime
            // Charge phase is over — clear its start tick so the BER stops
            // adding the charge-forward offset while recoil is running.
            clientChargeStartTick = NO_CHARGE
            markChangedAndSync()
        }
    }

    private fun markChangedAndSync() {
        setChanged()
        val lvl = level as? ServerLevel ?: return
        val pkt = ClientboundBlockEntityDataPacket.create(this) ?: return
        lvl.chunkSource.chunkMap
            .getPlayers(ChunkPos(blockPos), false)
            .forEach { it.connection.send(pkt) }
    }

    /** Smoke vent at the cannon's muzzle during the cooldown. Uses
     *  vanilla [ParticleTypes.SMOKE] for a generic "venting" look —
     *  same particle vanilla dispensers / pistons use after firing.
     *  Spawn position is the world-space focal (front of the emitter
     *  face), so on a ship the smoke vents from the correct spot
     *  even while the host rotates. */
    private fun spawnCooldownSmoke(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val (muzzle, dir) = computeWorldOriginAndDir(level, pos, state, MUZZLE_OFFSET)
        val rng = level.random
        for (i in 0 until COOLDOWN_SMOKE_PER_PUFF) {
            // Slight scatter perpendicular to the muzzle direction so
            // the puff isn't a perfect line.
            val jx = (rng.nextDouble() - 0.5) * 0.1
            val jy = (rng.nextDouble() - 0.5) * 0.1
            val jz = (rng.nextDouble() - 0.5) * 0.1
            // Slow outward drift along the cannon's facing.
            val driftSpeed = 0.03 + rng.nextDouble() * 0.02
            level.sendParticles(
                ParticleTypes.SMOKE,
                muzzle.x + jx, muzzle.y + jy, muzzle.z + jz,
                0,
                dir.x * driftSpeed, dir.y * driftSpeed, dir.z * driftSpeed, 1.0,
            )
        }
    }

    /** Sculk-coloured "suck-in" vortex during the charge phase.
     *  Vanilla [ParticleTypes.SCULK_SOUL] sprayed inward — the focal
     *  point is recomputed in world coords every tick via
     *  [computeWorldOriginAndDir] (which runs `shipToWorld` if the
     *  cannon's on a ship), so each fresh wave of particles spawns at
     *  the cannon's current visual position. In-flight particles
     *  retain their world-space velocity, which on a moving ship will
     *  drift slightly during their 5-tick flight; with the spawn rate
     *  kept low (see [SUCK_PARTICLES_PEAK]) this reads as a converging
     *  cone rather than a stale trail. */
    private fun spawnChargeParticles(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val (focal, _) = computeWorldOriginAndDir(level, pos, state, MUZZLE_OFFSET)
        val rng = level.random
        val progress = chargeTicks.toFloat() / CHARGE_TICKS
        val count = (1 + (SUCK_PARTICLES_PEAK - 1) * progress).toInt().coerceAtLeast(1)
        for (i in 0 until count) {
            val u = rng.nextDouble() * 2.0 - 1.0
            val theta = rng.nextDouble() * 2.0 * Math.PI
            val r = Math.sqrt(1.0 - u * u)
            val ox = r * Math.cos(theta)
            val oy = u
            val oz = r * Math.sin(theta)
            val spawnX = focal.x + ox * SUCK_RADIUS
            val spawnY = focal.y + oy * SUCK_RADIUS
            val spawnZ = focal.z + oz * SUCK_RADIUS
            val vx = (focal.x - spawnX) / SUCK_TRAVEL_TICKS
            val vy = (focal.y - spawnY) / SUCK_TRAVEL_TICKS
            val vz = (focal.z - spawnZ) / SUCK_TRAVEL_TICKS
            level.sendParticles(
                ParticleTypes.SCULK_SOUL,
                spawnX, spawnY, spawnZ,
                0,
                vx, vy, vz, 1.0,
            )
        }
    }

    private fun fire(level: ServerLevel, pos: BlockPos, state: BlockState) {
        // World-space origin + direction. If the cannon is mounted on
        // a VS2 ship, the BlockPos is a shipyard coord and the facing
        // is ship-local — using them raw would aim the beam through
        // the wrong space entirely. [computeWorldOriginAndDir]
        // transforms both into world coords (no-op when the cannon
        // isn't on a ship), so the beam tracer, damage AABB, and
        // sculk-spread call all operate in world coords.
        val (origin, dir) = computeWorldOriginAndDir(level, pos, state, BEAM_ORIGIN_OFFSET)
        // Collect shields from EVERY wielding player. The Sundering
        // tracer's [collectWieldedShields] skips the firing player, but
        // a fixed emplacement has no firing player — using a stand-in
        // (e.g. the first online player) accidentally filters out THAT
        // player's shield, which was the bug behind "ignores Aegis
        // reflection." We build the list manually here.
        val shields = collectAllAegisShields(level)
        // The trace needs an entity only as the collision context. A marker serves when the
        // dimension has no player.
        val tracerPlayer: net.minecraft.world.entity.Entity = level.players().firstOrNull()
            ?: net.minecraft.world.entity.EntityType.MARKER.create(level)
                ?.also { it.setPos(origin.x, origin.y, origin.z) }
            ?: return
        val trace = SunderingBeamTrace.trace(
            level, tracerPlayer,
            origin, dir, RANGE,
            shields,
        )
        if (trace.segments.isEmpty()) return

        // Damage every living entity whose hit-box intersects any segment.
        damageAlongTrace(level, trace.segments)

        // Firing-ship recoil: if the cannon is mounted on a VS2 ship, apply
        // an off-COM impulse at the muzzle in the OPPOSITE direction of the
        // beam. Off-COM = ship translates back and yaws slightly, exactly
        // the "kicked by its own shot" feel a heavy weapon should have.
        applyFiringShipRecoil(level, pos, origin, dir)

        // Sculk catastrophe at the terminal block hit. The tracer hands
        // us a BlockPos when the beam terminated on a solid block; for
        // open-range shots we just splash where the last segment ends.
        val tail = trace.segments.last().end
        val sculkCenter = trace.finalBlockPos ?: BlockPos.containing(tail)
        if (!inSpawnProtection(level, sculkCenter)) SculkSpread.start(level, sculkCenter, totalCharge = 200)

        // Fire SFX + the S2C visual packet.
        level.playSound(
            null, pos, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.BLOCKS,
            1.2f, 1.0f,
        )
        // Transform the world-space trace back into ship-local coords
        // before broadcasting so the client renderer can re-anchor the
        // visual to the ship's live transform each frame. World-placed
        // cannons send shipId = 0 and the unchanged world segments.
        val ship = level.getLoadedShipManagingPos(pos)
        val wireSegs = if (ship != null) {
            val worldToShip = ship.transform.worldToShip
            trace.segments.map { seg ->
                val s = Vector3d(seg.start.x, seg.start.y, seg.start.z)
                val e = Vector3d(seg.end.x, seg.end.y, seg.end.z)
                worldToShip.transformPosition(s)
                worldToShip.transformPosition(e)
                EchoCannonNetwork.Segment(Vec3(s.x, s.y, s.z), Vec3(e.x, e.y, e.z))
            }
        } else {
            trace.segments.map { EchoCannonNetwork.Segment(it.start, it.end) }
        }
        EchoCannonNetwork.broadcastFire(level, pos, ship?.id ?: 0L, wireSegs)
    }

    /** Origin (just outside the emitter face) + outward unit direction,
     *  both in world coords. If a VS2 ship is managing this block
     *  position, the cannon's shipyard centre is mapped through
     *  `shipToWorld` and its facing vector is rotated through
     *  `shipToWorldRotation`; otherwise we return the raw world coords.
     *  Same pattern [AetherPadBlockEntity] uses for its forward push.
     *
     *  [faceOffset] chooses where along the facing axis the origin sits:
     *  [BEAM_ORIGIN_OFFSET] = block face (0.55) for the beam trace itself,
     *  [MUZZLE_OFFSET] = barrel-front (0.75) for muzzle-attached effects
     *  (charge suck-in particles, cooldown smoke). */
    private fun computeWorldOriginAndDir(
        level: ServerLevel, pos: BlockPos, state: BlockState,
        faceOffset: Double,
    ): Pair<Vec3, Vec3> {
        val facing = state.getValue(DirectionalBlock.FACING)
        val originVec = Vector3d(
            pos.x + 0.5 + facing.stepX * faceOffset,
            pos.y + 0.5 + facing.stepY * faceOffset,
            pos.z + 0.5 + facing.stepZ * faceOffset,
        )
        val dirVec = Vector3d(
            facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble(),
        )
        val ship = level.getLoadedShipManagingPos(pos)
        if (ship != null) {
            ship.transform.shipToWorld.transformPosition(originVec)
            ship.transform.shipToWorldRotation.transform(dirVec)
        }
        return Vec3(originVec.x, originVec.y, originVec.z) to Vec3(dirVec.x, dirVec.y, dirVec.z)
    }

    /** Manual mirror of [SunderingBeamTrace.collectWieldedShields] that
     *  does NOT skip any player. The Sundering version skips the firing
     *  player so their own shield doesn't reflect their own beam back
     *  at them; the cannon has no firing player, so the skip would
     *  accidentally filter out a real shield-wielder. */
    private fun collectAllAegisShields(level: ServerLevel): List<AegisBox.Frame> {
        val out = ArrayList<AegisBox.Frame>()
        for (p in level.players()) {
            if (!p.isUsingItem) continue
            if (p.useItem.item != EKItems.STAFF_OF_AEGIS.get()) continue
            out.add(AegisBox.forPlayer(p, 1f))
        }
        return out
    }

    /** The vanilla test needs a player. A cannon has none, so this is the same area test. */
    private fun inSpawnProtection(level: ServerLevel, pos: BlockPos): Boolean {
        val server = level.server
        if (!server.isDedicatedServer || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return false
        val radius = server.spawnProtectionRadius
        if (radius <= 0) return false
        val spawn = level.sharedSpawnPos
        return maxOf(Math.abs(pos.x - spawn.x), Math.abs(pos.z - spawn.z)) <= radius
    }

    private fun damageAlongTrace(level: ServerLevel, segments: List<org.shipwrights.enderkinesis.item.BeamSegment>) {
        val touched = HashSet<Int>()  // entity ids — don't double-hit on bounces
        val src = level.damageSources().sonicBoom(null)
        for (seg in segments) {
            // Broad-phase: expand the segment's AABB by the hit radius.
            val box = AABB(
                minOf(seg.start.x, seg.end.x), minOf(seg.start.y, seg.end.y), minOf(seg.start.z, seg.end.z),
                maxOf(seg.start.x, seg.end.x), maxOf(seg.start.y, seg.end.y), maxOf(seg.start.z, seg.end.z),
            ).inflate(BEAM_HIT_RADIUS)
            val candidates = level.getEntitiesOfClass(LivingEntity::class.java, box)
            // Beam direction at this segment — used for the knockback push so
            // targets get thrown along the beam, not just "up". Zero-length
            // segments (degenerate bounce) fall back to a null direction; the
            // hit still deals damage but skips knockback.
            val delta = seg.end.subtract(seg.start)
            val len2 = delta.lengthSqr()
            val dir = if (len2 > 1.0e-9) delta.scale(1.0 / kotlin.math.sqrt(len2)) else null
            for (e in candidates) {
                if (e.id in touched) continue
                // Narrow-phase: closest-point distance from the segment
                // ray to the entity's centre.
                if (distancePointToSegment(e.position(), seg.start, seg.end) > BEAM_HIT_RADIUS) continue
                e.hurt(src, BEAM_DAMAGE)
                if (dir != null) applyBeamKnockback(e, dir)
                touched += e.id
            }
        }
    }

    /** Firing-ship recoil. If the cannon block is on a VS2 ship, enqueue
     *  a one-shot impulse at the WORLD-space muzzle in the OPPOSITE
     *  direction of the beam. The queue lives on the ship as a
     *  [LauncherRecoilAttachment] (shared with the magic-missile launcher —
     *  same one-shot off-COM-force pattern) and is drained on the physics
     *  thread as a single-tick force. Off-COM at the muzzle produces both
     *  a backwards translation AND a slight rotation, exactly the kick a
     *  heavy weapon should have. */
    private fun applyFiringShipRecoil(
        level: ServerLevel, pos: BlockPos, origin: Vec3, dir: Vec3,
    ) {
        val ship = level.getLoadedShipManagingPos(pos)
            as? org.valkyrienskies.core.api.ships.LoadedServerShip ?: return
        val impulse = Vector3d(-dir.x, -dir.y, -dir.z).mul(RECOIL_IMPULSE)
        val queue = ship.getOrPutAttachment(
            org.shipwrights.enderkinesis.physics.LauncherRecoilAttachment::class.java
        ) { org.shipwrights.enderkinesis.physics.LauncherRecoilAttachment() }
        queue.enqueue(Vector3d(origin.x, origin.y, origin.z), impulse)
    }

    /** Warden-sonic-style knockback: push the target along the beam direction
     *  (horizontal component from [dir]) plus a fixed upward lift. Scales down
     *  by the target's KNOCKBACK_RESISTANCE. Sets `hurtMarked = true` so the
     *  server sends a SetEntityMotionPacket next tick and the client actually
     *  sees the push instead of the target snapping back to its lerp target. */
    private fun applyBeamKnockback(e: LivingEntity, dir: Vec3) {
        val resist = e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)
        val hScale = KNOCKBACK_HORIZONTAL * (1.0 - resist)
        val vScale = KNOCKBACK_VERTICAL * (1.0 - resist)
        e.deltaMovement = e.deltaMovement.add(
            dir.x * hScale,
            vScale,
            dir.z * hScale,
        )
        e.hurtMarked = true
    }

    private fun distancePointToSegment(p: Vec3, a: Vec3, b: Vec3): Double {
        val ab = b.subtract(a)
        val len2 = ab.lengthSqr()
        if (len2 < 1.0e-9) return p.distanceTo(a)
        val t = (p.subtract(a).dot(ab) / len2).coerceIn(0.0, 1.0)
        return p.distanceTo(a.add(ab.scale(t)))
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putInt("Charge", chargeTicks)
        tag.putInt("ChargeTarget", chargeTickTarget)
        tag.putInt("Cooldown", cooldownTicks)
        tag.putBoolean("Powered", poweredCached)
        tag.putLong("RecoilStart", clientRecoilStartTick)
        tag.putLong("ChargeStart", clientChargeStartTick)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        chargeTicks = if (tag.contains("Charge")) tag.getInt("Charge") else -1
        chargeTickTarget = if (tag.contains("ChargeTarget")) tag.getInt("ChargeTarget") else CHARGE_TICKS
        cooldownTicks = tag.getInt("Cooldown")
        poweredCached = tag.getBoolean("Powered")
        clientRecoilStartTick = if (tag.contains("RecoilStart")) tag.getLong("RecoilStart") else NO_RECOIL
        clientChargeStartTick = if (tag.contains("ChargeStart")) tag.getLong("ChargeStart") else NO_CHARGE
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    companion object {
        /** Charge wind-up. Matched to the warden's sonic-boom charge
         *  (vanilla `Warden.SONIC_BOOM_TICK_THRESHOLD` ≈ 50 ticks /
         *  2.5 s), so the same WARDEN_SONIC_CHARGE clip lines up with
         *  the timer and the cannon feels mechanically like a stationary
         *  warden setting up a shot. Each shot is dithered ±1 tick. */
        const val CHARGE_TICKS: Int = 50
        /** Five-second reload after firing. */
        const val COOLDOWN_TICKS: Int = 100
        /** Spawn smoke every N cooldown ticks. 4 ticks ≈ 5 puffs/sec —
         *  steady, readable venting without per-cannon-tick cost
         *  scaling badly when many cannons cool down simultaneously. */
        private const val COOLDOWN_SMOKE_PERIOD: Int = 4
        /** Smoke particles per puff. */
        private const val COOLDOWN_SMOKE_PER_PUFF: Int = 2
        /** Effective beam range in blocks. Long enough to clear a
         *  meaningful courtyard / sightline from the emplacement. */
        const val RANGE: Double = 64.0
        /** Half-width of the entity-hit cylinder. ~1 block radius
         *  catches anything brushing the beam. */
        const val BEAM_HIT_RADIUS: Double = 1.0
        /** Damage per entity along the beam. The user asked for warden
         *  sonic damage but "lower" — vanilla sonic boom deals 10 hp;
         *  6 hp / 3 hearts lands between iron-sword and a fully-charged
         *  crossbow shot. */
        const val BEAM_DAMAGE: Float = 6.0f
        /** Horizontal knockback velocity added along the beam direction —
         *  strong enough to visibly launch a target back a couple of blocks
         *  before friction pulls them down. Vanilla warden sonic-boom uses
         *  0.5; this cannon reads as a slightly harder hit. */
        private const val KNOCKBACK_HORIZONTAL: Double = 0.9
        /** Upward lift added to the knockback so targets get sent into a
         *  brief arc instead of skidding along the floor. */
        private const val KNOCKBACK_VERTICAL: Double = 0.35
        /** Sentinel meaning "no recoil animation is running". Matched by
         *  [EchoCannonRenderer]'s NO_RECOIL constant. */
        const val NO_RECOIL: Long = Long.MIN_VALUE
        /** Sentinel meaning "no charge animation is running". */
        const val NO_CHARGE: Long = Long.MIN_VALUE
        /** Face-offset for the beam trace itself — block face + ε. Keeps
         *  the visible beam anchored at the original position even after
         *  the muzzle geometry moved outboard onto the barrel. */
        private const val BEAM_ORIGIN_OFFSET: Double = 0.55
        /** Face-offset for muzzle-attached effects (charge suck-in,
         *  cooldown smoke): block face + barrel-front protrusion
         *  (0.5 + 4/16 = 0.75). */
        private const val MUZZLE_OFFSET: Double = 0.75
        /** Firing-ship recoil impulse magnitude (N·s). Applied at the world-
         *  space muzzle opposite the beam direction, off-COM, so the ship both
         *  translates backwards AND yaws. Scales inversely with ship mass: a
         *  500 kg skiff gets a ~30 m/s kick, a 10 t galleon gets ~1.5 m/s. At
         *  this magnitude firing feels like firing artillery — small ships get
         *  visibly launched, big ships lurch. */
        private const val RECOIL_IMPULSE: Double = 15000.0
        /** Suck-in vortex outer radius — particles spawn on this sphere
         *  around the cannon's emitter face. */
        private const val SUCK_RADIUS: Double = 1.5
        /** Travel time used to scale each suck-in particle's velocity. */
        private const val SUCK_TRAVEL_TICKS: Double = 5.0
        /** Particle count at the end of the charge ramp. Dropped from
         *  10 → 3 — multiple firing cannons stacked enough particles
         *  per tick to noticeably hitch the client. The custom particle
         *  is heavier than vanilla (ship-transform lookup + ship-local
         *  walk each tick), so we want the per-cannon density low. */
        private const val SUCK_PARTICLES_PEAK: Int = 3
    }
}
