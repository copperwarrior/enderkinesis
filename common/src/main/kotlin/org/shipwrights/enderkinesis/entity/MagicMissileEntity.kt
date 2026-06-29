package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.boss.EnderDragonPart
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/**
 * Magic Missile — homing projectile fired by the Magic Missile Launcher block. Locks onto
 * the nearest hostile mob or player that *isn't* currently being dragged by [homeShipId]
 * (so a turret on your ship doesn't shoot your gunners). On contact it fires a small
 * explosion ([EXPLOSION_RADIUS] = 1.0, no fire) — the visual and audio of that explosion
 * is the trail's pink firework burst, layered over vanilla's explosion damage and block
 * effects. Sword-breakable like a vanilla shulker bullet. Flies straight at firework
 * speed — no cardinal-axis snap.
 *
 * Reflection: if [getOwner] flips to a [Player] (the Staff of Aegis shield re-assigns owner on
 * reflect), [homeShipId] re-derives from that player's currently-dragging ship — so a deflected
 * missile now hunts whoever the *original* shooter was loyal to.
 */
class MagicMissileEntity(type: EntityType<out MagicMissileEntity>, level: Level) :
    Projectile(type, level) {

    /** Reflection-tracker: server-side cache of the owner uuid last seen. When this changes
     *  (Aegis reassigned us), we re-derive [homeShipId] from the new owner's host ship. */
    private var lastOwnerUuidHash: Int = 0

    /** Cached target. Re-resolved every [TARGET_REFRESH_TICKS] ticks; in between we just steer
     *  toward the cached entity so the search cost is amortised. */
    private var cachedTargetId: Int = -1
    private var ticksUntilTargetRefresh: Int = 0

    /** Persistent jitter state in the perpendicular plane to the missile's heading.
     *  Decayed each tick by [NOISE_DECAY] and pushed by a small random kick of magnitude
     *  [NOISE_KICK] — the noise vector then *drifts* smoothly rather than jumping fresh
     *  every tick. Steady-state RMS magnitude comes out to roughly [NOISE_AMPLITUDE].
     *  Server-side only (the chaos lives on the server, the client just interpolates the
     *  resulting position snapshots). */
    private var noiseU: Double = 0.0
    private var noiseV: Double = 0.0

    /** Unreachability watchdog. Counts ticks during which the missile has no valid
     *  target — no entity in scan range, locked entity dead/gone, locked entity
     *  teleported. While there *is* a valid target the counter resets every tick. */
    private var unreachableTicks: Int = 0

    /** Last seen position of the locked target entity, used to detect teleports.
     *  When the entity's position jumps more than [TELEPORT_DISTANCE_SQ] between
     *  ticks the lock is dropped and the missile treats itself as targetless. */
    private var lastLockedTargetPos: Vec3? = null

    /** Fixed-point target in world coords. When non-null, overrides the entity-target
     *  search — the missile steers toward this point instead of homing on the nearest
     *  hostile. Used by the Staff of Command launcher dispatch where the staff's
     *  gaze raycast picks the point. Server-side only; the client interpolates
     *  positions and doesn't need to know the target. */
    var targetPos: Vec3? = null

    /** Locked entity target — used by Staff-of-Command dispatch when the gaze raycast
     *  lands on an entity. The missile homes exclusively on this entity for as long as
     *  it's alive; if the entity dies or despawns the missile continues straight (no
     *  fallback to nearest-target search). Not persisted across reloads — runtime
     *  entity ids are session-scoped, and a mid-flight reload is rare enough to accept
     *  losing the lock. */
    var lockedTargetEntityId: Int = -1

    /** Optional `BlockPos` the staff's gaze raycast landed on. The missile flies to
     *  the matching world `Vec3` via [targetPos] regardless; this field exists *only*
     *  so the target's air-check can be done against the correct coordinate space.
     *  VS2's `level.clip` returns shipyard-space [BlockHitResult.getBlockPos] for ship
     *  blocks, and `level.getBlockState` routes shipyard-pos lookups through VS2's
     *  ship chunks — so looking up this stored blockPos is correct in both cases. */
    var targetBlockPos: BlockPos? = null

    init {
        noPhysics = true                                    // we own our own movement
    }

    constructor(level: Level, x: Double, y: Double, z: Double) :
        this(EKEntities.MAGIC_MISSILE.get(), level) {
        this.setPos(x, y, z)
    }

    override fun defineSynchedData() {
        entityData.define(HOME_SHIP_DATA, Long.MIN_VALUE)
    }

    /** Home ship id — entities dragged by this ship are ignored for targeting. `Long.MIN_VALUE`
     *  means "no home" (treats every entity as a valid target). Synced to client for renderer
     *  diagnostics if ever needed; the entity itself doesn't reference its home ship after spawn
     *  beyond the target filter. */
    var homeShipId: Long
        get() = entityData.get(HOME_SHIP_DATA)
        set(value) {
            entityData.set(HOME_SHIP_DATA, value)
        }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        if (homeShipId != NO_SHIP) tag.putLong(NBT_HOME_SHIP, homeShipId)
        targetPos?.let {
            tag.putDouble(NBT_TARGET_X, it.x)
            tag.putDouble(NBT_TARGET_Y, it.y)
            tag.putDouble(NBT_TARGET_Z, it.z)
        }
        targetBlockPos?.let { tag.putLong(NBT_TARGET_BLOCK, it.asLong()) }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        homeShipId = if (tag.contains(NBT_HOME_SHIP)) tag.getLong(NBT_HOME_SHIP) else NO_SHIP
        targetPos = if (tag.contains(NBT_TARGET_X)) {
            Vec3(tag.getDouble(NBT_TARGET_X), tag.getDouble(NBT_TARGET_Y), tag.getDouble(NBT_TARGET_Z))
        } else null
        targetBlockPos = if (tag.contains(NBT_TARGET_BLOCK)) BlockPos.of(tag.getLong(NBT_TARGET_BLOCK)) else null
    }

    override fun tick() {
        super.tick()                                                  // baseTick → xOld/yOld/zOld

        // --- Server-side steering + reflection handoff.
        if (!level().isClientSide) {
            // Reflection detection. Aegis reassigns owner; pick up its new home ship.
            val owner = this.owner
            val ownerHash = owner?.uuid?.hashCode() ?: 0
            if (ownerHash != lastOwnerUuidHash) {
                lastOwnerUuidHash = ownerHash
                if (owner is Player) {
                    homeShipId = currentlyDraggingShipId(owner) ?: NO_SHIP
                }
            }

            // Pick a target point. Order of precedence:
            //   1. [lockedTargetEntityId]  — staff-dispatched entity lock; tracks the
            //      entity as it moves, no fallback if the entity dies.
            //   2. [targetPos]             — staff-dispatched point target; fixed coord.
            //   3. Entity scan             — default nearest-hostile homing.
            val rawLockedEntity = if (lockedTargetEntityId >= 0)
                level().getEntity(lockedTargetEntityId)?.takeIf { it.isAlive } else null
            // Teleport detection: if the locked entity's position jumped more than
            // [TELEPORT_DISTANCE_SQ] between ticks (ender pearl, /tp, etc.), drop the
            // lock entirely. The missile no longer has a target — the unreachability
            // counter starts ticking up.
            val lockedEntity = if (rawLockedEntity != null) {
                val prev = lastLockedTargetPos
                val curr = rawLockedEntity.position()
                lastLockedTargetPos = curr
                if (prev != null && curr.distanceToSqr(prev) > TELEPORT_DISTANCE_SQ) {
                    lockedTargetEntityId = -1
                    lastLockedTargetPos = null
                    null
                } else rawLockedEntity
            } else {
                lastLockedTargetPos = null
                null
            }
            val targetCentre: Vec3? = if (lockedEntity != null) {
                lockedEntity.position().add(0.0, lockedEntity.bbHeight * 0.5, 0.0)
            } else if (targetPos != null && !level().getBlockState(
                    // Prefer the recorded raycast blockPos — for ship blocks that's in
                    // shipyard space, which `level.getBlockState` correctly routes through
                    // VS2's ship chunks. Falling back to `BlockPos.containing(targetPos)`
                    // only when the missile came in via NBT load without a stored blockPos.
                    targetBlockPos ?: BlockPos.containing(targetPos)
                ).isAir) {
                // Staff-aimed point. Treat the aim as invalid when the block at that
                // position is air — the wall/floor the staff was pointed at is gone now
                // (mined, exploded, etc.), so the missile has nothing left to hit there.
                targetPos
            } else {
                // Refresh target if cached one is gone, dead, friendly, or stale. Cached id can
                // point at a Monster/Player OR another MagicMissileEntity, so we re-validate
                // against the unified [isValidTarget] predicate every tick.
                var target: Entity? = if (cachedTargetId >= 0) {
                    level().getEntity(cachedTargetId)?.takeIf { it.isAlive && isValidTarget(it) }
                } else null
                if (ticksUntilTargetRefresh <= 0 || target == null) {
                    target = findNearestTarget()
                    cachedTargetId = target?.id ?: -1
                    ticksUntilTargetRefresh = TARGET_REFRESH_TICKS
                }
                ticksUntilTargetRefresh--
                target?.position()?.add(0.0, target.bbHeight * 0.5, 0.0)
            }

            // Unreachability watchdog. Counts ticks during which the missile has no
            // valid target — no entity in scan range, locked entity died/disappeared,
            // locked entity teleported. While a target IS present the counter resets
            // every tick, so a missile actively chasing something never times out.
            if (targetCentre == null) {
                unreachableTicks++
                if (unreachableTicks >= UNREACHABLE_KILL_TICKS) {
                    fizzle()
                    return
                }
            } else {
                unreachableTicks = 0
            }

            // Steer. Slow-down-when-turning gives the target a fighting chance: the missile
            // sheds speed proportional to how far off-axis its desired heading is. Aligned
            // → full speed; perpendicular → MIN_SPEED_FACTOR · SPEED.
            val velocity = deltaMovement
            val currentDir = if (velocity.lengthSqr() > 1e-6) velocity.normalize() else null
            val newVelocity = if (targetCentre != null && currentDir != null) {
                val toTarget = targetCentre.subtract(this.position())
                val toTargetN =
                    if (toTarget.lengthSqr() > 1e-6) toTarget.normalize() else currentDir
                // Inside [TURN_WIDEN_RADIUS] of the target, linearly scale the toward
                // blend down — the missile widens its turn radius near the target
                // instead of corkscrewing on a tight orbit. Outside the radius the
                // blend is vanilla; at point-blank the heading is frozen.
                val approach = (1.0 - toTarget.length() / TURN_WIDEN_RADIUS).coerceIn(0.0, 1.0)
                val toward = STEER_BLEND_TOWARD * (1.0 - approach)
                val keep = 1.0 - toward
                val blendedDir = currentDir.scale(keep)
                    .add(toTargetN.scale(toward))
                    .normalize()
                // Speed scales with heading-vs-desired alignment so a hard turn slows the
                // missile rather than barrel-rolling around the target.
                val align = currentDir.dot(toTargetN).coerceIn(-1.0, 1.0)
                val speed = SPEED * (MIN_SPEED_FACTOR + (1.0 - MIN_SPEED_FACTOR) * Math.max(0.0, align))
                blendedDir.scale(speed)
            } else if (currentDir != null) {
                currentDir.scale(SPEED)
            } else {
                velocity
            }

            // Gradual perpendicular jitter — Ornstein-Uhlenbeck-style random walk in the
            // 2D plane perpendicular to the heading. The persistent (noiseU, noiseV)
            // state is decayed by [NOISE_DECAY] each tick and pushed by a small uniform
            // random kick of total range [NOISE_KICK]. Result is noise that drifts
            // smoothly between ticks rather than jumping fresh on each one — wobble
            // reads as a gentle wandering flight rather than a high-frequency jitter.
            // Perpendicular-only on purpose: a parallel component would fight the
            // heading-vs-desired speed scaling that steers the missile.
            //
            // When the missile has a target, scale the effective noise vector down by
            // [TARGETING_NOISE_SCALE] so the homing logic isn't drowned out by the
            // wobble. The OU walk itself keeps wandering at full magnitude — only the
            // amount injected into the velocity drops — so when the target dies or
            // moves out of LOS the missile resumes the full chaos without a ramp-up
            // delay.
            val noisedVelocity = if (newVelocity.lengthSqr() > 1e-6) {
                val dir = newVelocity.normalize()
                val ref = if (Math.abs(dir.y) < 0.95) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
                val perpRight = dir.cross(ref).normalize()
                val perpUp = perpRight.cross(dir)

                noiseU = noiseU * NOISE_DECAY + (random.nextDouble() - 0.5) * NOISE_KICK
                noiseV = noiseV * NOISE_DECAY + (random.nextDouble() - 0.5) * NOISE_KICK

                val noiseScale = if (targetCentre != null) TARGETING_NOISE_SCALE else 1.0
                val noise = perpRight.scale(noiseU * noiseScale)
                    .add(perpUp.scale(noiseV * noiseScale))
                newVelocity.add(noise)
            } else newVelocity

            // Block avoidance: ray-cast the upcoming flight segment with `level.clip`; if
            // it actually hits a block, probe up/down/left/right of the hit and pick the
            // clear direction most aligned with the target. Cave-safe — only the line of
            // flight is checked, not the column heightmap, so flying through caverns
            // doesn't trigger a climb-out-of-the-cave response.
            val avoidedVelocity = noisedVelocity.add(blockAvoidance(position(), noisedVelocity, targetCentre))

            // Speed chaos: per-tick ±[SPEED_CHAOS_AMPLITUDE] random jitter on the velocity
            // magnitude, direction preserved. Makes the streak read as alive rather than
            // a perfectly-paced ruler line; jitter is independent each tick so it doesn't
            // accumulate into drift.
            val chaosOffset = (random.nextDouble() - 0.5) * 2.0 * SPEED_CHAOS_AMPLITUDE
            val mag = avoidedVelocity.length()
            deltaMovement = if (mag > 1.0e-6) {
                avoidedVelocity.scale(((mag + chaosOffset).coerceAtLeast(0.0)) / mag)
            } else avoidedVelocity
        }

        // --- Both sides: move along current `deltaMovement` so client interpolation is
        //     smooth at the same tick rate. Without this, the client only sees server
        //     position snapshots and the visual stutters between them.
        // The hit predicate is otherwise open — whatever the missile runs into stops it —
        // but **friendly missiles phase through**. Two missiles sharing a home ship (or
        // owner) are on the same side, so colliding them serves no purpose and just
        // produces friendly-fire detonations mid-formation. `isFriendlyMissile` returns
        // true when the other missile shares `homeShipId` (excluding the no-ship sentinel)
        // or the same owner uuid; same predicate the targeting filter already uses, so
        // a missile that's been *excluded as a target* is also *not collidable*.
        val hit = ProjectileUtil.getHitResultOnMoveVector(this) { e ->
            !(e is MagicMissileEntity && isFriendlyMissile(e))
        }
        if (hit.type != HitResult.Type.MISS && !level().isClientSide) {
            onHit(hit)
        }
        if (!isRemoved) {
            move(MoverType.SELF, deltaMovement)
        }
    }

    override fun onHitBlock(hit: BlockHitResult) {
        super.onHitBlock(hit)
        if (homeShipFizzle(hit.blockPos)) return
        detonate(hit.location)
    }

    override fun onHitEntity(hit: EntityHitResult) {
        super.onHitEntity(hit)
        // A target that's currently being dragged by our home ship counts as "on the home
        // ship" — a friendly gunner standing on the deck, a passenger in a cabin. Friendly
        // fire shouldn't blow them up; fizzle and discard instead.
        if (homeShipId != NO_SHIP && isDraggedByHomeShip(hit.entity)) {
            fizzle()
            return
        }
        // Vanilla shulker-bullet damage on direct mob/player hit: mobProjectile source with
        // 4.0 damage (2 hearts). The owner is forwarded when it's a LivingEntity so kill
        // attribution lands on the shooter rather than on the missile itself; vanilla
        // tolerates a null owner here, so we forward whatever cast yields. EnderDragonPart
        // is not a LivingEntity but its hurt() forwards to the parent dragon, so we accept
        // it as a damage target alongside LivingEntity.
        if (!level().isClientSide) {
            val target = hit.entity
            if (target is LivingEntity || target is EnderDragonPart) {
                val ownerLiving = this.owner as? LivingEntity
                target.hurt(
                    damageSources().mobProjectile(this, ownerLiving),
                    DIRECT_HIT_DAMAGE,
                )
            }
        }
        detonate(hit.location)
    }

    /** If [blockPos] (in **shipyard coords** when VS2's clip routed the hit through a ship
     *  block — see memory `vs2-clip-mixed-frames`) is a block on our home ship, fizzle and
     *  return true. Otherwise no-op and return false so the caller can proceed to detonate. */
    private fun homeShipFizzle(blockPos: net.minecraft.core.BlockPos): Boolean {
        if (homeShipId == NO_SHIP) return false
        val ship = level().getShipManagingPos(blockPos) ?: return false
        if (ship.id != homeShipId) return false
        fizzle()
        return true
    }

    /** Friendly-fire termination — soft shulker-bullet "hurt" sound + discard. No
     *  explosion, no damage. Server-only because the client never executes detonate either. */
    private fun fizzle() {
        if (level().isClientSide) return
        level().playSound(
            null, this.x, this.y, this.z,
            SoundEvents.SHULKER_BULLET_HURT, SoundSource.NEUTRAL,
            0.8f, 1.4f,
        )
        discard()
    }

    /** Detonate at the hit point. Position is read from the `BlockHitResult` /
     *  `EntityHitResult.location` rather than from `this.x/y/z` (which is the pre-move
     *  start-of-tick coord, behind the actual point of impact). When the hit's block-pos
     *  resolves to a VS2 ship's chunk claim, the hit coord that VS2 returned is in
     *  **shipyard** space — `ProjectileUtil.getHitResultOnMoveVector` uses a shipyard-
     *  framed `.location` for ship-block hits — and we transform through the ship's
     *  `shipToWorld` so the explosion lands at the actual world impact point. */
    private fun detonate(hitPos: Vec3) {
        if (level().isClientSide) return

        val hitBlock = BlockPos.containing(hitPos.x, hitPos.y, hitPos.z)
        val shipAtHitBlock = level().getShipManagingPos(hitBlock)
        val worldPos = if (shipAtHitBlock != null) {
            val v = Vector3d(hitPos.x, hitPos.y, hitPos.z)
            shipAtHitBlock.shipToWorld.transformPosition(v)
            Vec3(v.x, v.y, v.z)
        } else {
            hitPos
        }

        spawnDetonationBurst(worldPos.x, worldPos.y, worldPos.z)
        quietExplosion(worldPos)
        discard()
    }

    /** Mob-interaction explosion at [worldPos], minus the BOOM and the smoke — the
     *  firework burst is the visual/audio. Bypasses [Level.explode] so we can skip
     *  the [net.minecraft.network.protocol.game.ClientboundExplodePacket] broadcast
     *  whose handler is what plays vanilla's explosion sound and spawns the
     *  EXPLOSION/EXPLOSION_EMITTER particles client-side. [Explosion.explode] still
     *  applies entity damage, sculk gameEvent, non-player knockback, and gathers the
     *  affected blocks; [Explosion.finalizeExplosion] with `spawnParticles = false`
     *  breaks blocks (the local-sound call inside it is a server-side no-op). Player
     *  knockback that vanilla would normally carry in the explode packet is
     *  forwarded directly via [ClientboundSetEntityMotionPacket]. */
    private fun quietExplosion(worldPos: Vec3) {
        val server = level() as? ServerLevel ?: return
        val griefing = server.gameRules.getBoolean(GameRules.RULE_MOBGRIEFING)
        val interaction = if (griefing) Explosion.BlockInteraction.DESTROY else Explosion.BlockInteraction.KEEP

        // Vanilla `Explosion.explode` adds knockback hardcoded for TNT-sized blasts
        // (`d10 * direction`, up to ~1 block/tick magnitude at point-blank). For a
        // 1-block-radius missile that ends up launching entities much further than the
        // damage justifies. Snapshot pre-explosion velocities, run the explosion, then
        // scale the knockback delta down to [EXPLOSION_KNOCKBACK_SCALE].
        val damageHalfExtent = EXPLOSION_RADIUS * 2.0
        val damageBox = AABB(
            worldPos.x - damageHalfExtent, worldPos.y - damageHalfExtent, worldPos.z - damageHalfExtent,
            worldPos.x + damageHalfExtent, worldPos.y + damageHalfExtent, worldPos.z + damageHalfExtent,
        )
        val preVelocities = server.getEntities(this, damageBox).associateWith { it.deltaMovement }

        val explosion = Explosion(
            server, this, null, null,
            worldPos.x, worldPos.y, worldPos.z,
            EXPLOSION_RADIUS, false, interaction,
        )
        explosion.explode()
        explosion.finalizeExplosion(false)

        // Scale only the explosion-induced delta, leaving the entity's pre-existing
        // motion untouched (so a sprinting mob doesn't suddenly slow down).
        for ((entity, preVel) in preVelocities) {
            val delta = entity.deltaMovement.subtract(preVel)
            if (delta.lengthSqr() > 1.0e-9) {
                entity.setDeltaMovement(preVel.add(delta.scale(EXPLOSION_KNOCKBACK_SCALE)))
            }
        }

        for ((player, _) in explosion.hitPlayers) {
            if (player is ServerPlayer) {
                player.connection.send(ClientboundSetEntityMotionPacket(player))
            }
        }
    }

    /** Vanilla `FireworkParticles$Starter`'s BURST dispatch (`m_106793_`),
     *  generalised so the positive-only directional bias rides the missile's
     *  [deltaMovement] axis instead of vanilla's hardcoded `+Y`. Vanilla's
     *  algorithm is the special case where the axis is `+Y` — when [axis] is
     *  `(0, 1, 0)` the formula here reduces to it exactly.
     *
     *  Per spark: `velocity = delta * 0.5  +  axis * along  +
     *                            perp1 * (gaussian()*0.15 + sharedScatter1) +
     *                            perp2 * (gaussian()*0.15 + sharedScatter2)`
     *  where `along = nextDouble() * 0.5` (positive only — the "burst" outward
     *  push) and the two shared scatters are drawn once per call (vanilla's
     *  `d5`/`d7`). Density is halved from vanilla — 35 sparks vs. 70.
     *
     *  Sparks are [EKParticles.MISSILE_BURST_SPARK] — a 1:1 port of vanilla
     *  `SparkParticle` (extends `SimpleAnimatedParticle`, gravity 0.1, friction
     *  0.91, second-half alpha + 20%-per-tick colour approach), tinted with the
     *  trail's OUTLINE → GLOW pink palette on the ender-swirl sprite atlas. */
    private fun spawnDetonationBurst(x: Double, y: Double, z: Double) {
        val server = level() as? ServerLevel ?: return
        val rng = random
        val pitch = 0.95f + rng.nextFloat() * 0.1f
        server.playSound(
            null, x, y, z,
            EKSounds.MISSILE_BURST_BLAST.get(), SoundSource.AMBIENT,
            BURST_SOUND_VOLUME, pitch,
        )

        // Vanilla `FireworkParticles.Starter` always spawns a single FLASH at the
        // burst centre alongside the sparks (`colors[0]` tint). Match that.
        server.sendParticles(EKParticles.missileBurstFlash(), x, y, z, 1, 0.0, 0.0, 0.0, 0.0)

        val delta = deltaMovement
        // Bias axis = unit delta. Fall back to +Y if the missile is somehow
        // stationary at impact so the burst still has a well-defined orientation.
        val axis = if (delta.lengthSqr() > 1.0e-12) delta.normalize() else Vec3(0.0, 1.0, 0.0)
        // Pick a reference vector that's not near-parallel to axis so axis × ref is
        // well-conditioned even for vertical-flying missiles.
        val ref = if (Math.abs(axis.y) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val perp1 = axis.cross(ref).normalize()
        val perp2 = axis.cross(perp1)

        val sharedScatter1 = rng.nextGaussian() * BURST_SHARED_OFFSET_SIGMA
        val sharedScatter2 = rng.nextGaussian() * BURST_SHARED_OFFSET_SIGMA
        val sparkType = EKParticles.missileBurstSpark()
        repeat(BURST_SPARK_COUNT) {
            val along = rng.nextDouble() * BURST_DIR_BIAS
            val s1 = rng.nextGaussian() * BURST_SPREAD_SIGMA + sharedScatter1
            val s2 = rng.nextGaussian() * BURST_SPREAD_SIGMA + sharedScatter2
            val vx = delta.x * BURST_DELTA_FACTOR + axis.x * along + perp1.x * s1 + perp2.x * s2
            val vy = delta.y * BURST_DELTA_FACTOR + axis.y * along + perp1.y * s1 + perp2.y * s2
            val vz = delta.z * BURST_DELTA_FACTOR + axis.z * along + perp1.z * s1 + perp2.z * s2
            server.sendParticles(sparkType, x, y, z, 0, vx, vy, vz, BURST_VELOCITY_SCALE)
        }
    }

    /** Sword-breakable. Any damage from an attack (sword, projectile, etc.) discards us
     *  without exploding — vanilla shulker bullet behaviour. Indirect damage (fall, void,
     *  …) is ignored.
     *
     *  **Explosions are normally ignored**: a salvo that detonates its lead missile
     *  shouldn't chain-cancel every follower mid-flight, and a friendly AoE (Aegis
     *  sundering, Echo cannon) shouldn't shred passing friendly missiles either. The
     *  one exception is **enemy missile explosions** — if the detonator is a
     *  [MagicMissileEntity] with a different [homeShipId], the blast damages and
     *  destroys this missile. That makes counter-missile fire a viable tactic: a
     *  ship's launcher salvo can intercept incoming hostile missiles by detonating
     *  near them. Two missiles from the same ship (same `homeShipId`) — or two
     *  world-mounted missiles both at [NO_SHIP] — still skip the damage since they
     *  count as the same "side." */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        if (source.`is`(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
            val attacker = source.entity
            val fromEnemyMissile = attacker is MagicMissileEntity &&
                attacker.homeShipId != this.homeShipId
            if (!fromEnemyMissile) return false
        }
        if (!level().isClientSide && !isRemoved) {
            playSound(net.minecraft.sounds.SoundEvents.SHULKER_BULLET_HURT, 1.0f, 1.0f)
            markHurt()
            discard()
            return true
        }
        return false
    }

    override fun isPickable(): Boolean = !isRemoved

    /** Don't slow / push the missile via the normal entity rules. */
    override fun isNoGravity(): Boolean = true

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean = distance < 64.0 * 64.0


    /** Per-tick velocity correction so the missile clears or goes around blocks actually
     *  in its path. Cave-safe: only the line of flight is sampled, not the column above
     *  (the heightmap fails in caves because it reports the surface above the cave).
     *
     *  Algorithm:
     *  1. Ray-cast [TERRAIN_LOOKAHEAD_TICKS]-ticks-worth of velocity forward with
     *     `level.clip`. If the ray clears (no block in line of sight), return zero.
     *  2. Otherwise sample blockMotion at four points around the hit:
     *     up/down (Y ± [TERRAIN_PROBE_DISTANCE]) and left/right (perp ± distance).
     *  3. Score each clear direction by alignment with the toward-target vector and
     *     pick the highest-scoring one. Push velocity in that direction, capped at
     *     [MAX_AVOIDANCE_RISE] (vertical) or [MAX_AVOIDANCE_LATERAL] (horizontal).
     *  4. If every direction is also blocked, return zero — there's nowhere to detour.
     *
     *  Disabled within [AVOIDANCE_COMMIT_RADIUS] of the target — commit to the final
     *  approach rather than detouring around the target itself. VS2 ships aren't in the
     *  world block index from this entity's perspective, so this is open-world avoidance. */
    private fun blockAvoidance(currentPos: Vec3, velocity: Vec3, targetCentre: Vec3?): Vec3 {
        if (velocity.lengthSqr() < 1.0e-6) return Vec3.ZERO
        if (targetCentre != null && currentPos.distanceTo(targetCentre) < AVOIDANCE_COMMIT_RADIUS) return Vec3.ZERO

        val horiz = Vec3(velocity.x, 0.0, velocity.z)
        val horizLen = horiz.length()
        if (horizLen < 1.0e-6) return Vec3.ZERO
        val forward = horiz.scale(1.0 / horizLen)
        // Perpendicular in the horizontal plane, "right" of forward (CW around +Y).
        val perp = Vec3(-forward.z, 0.0, forward.x)

        val futurePos = currentPos.add(velocity.scale(TERRAIN_LOOKAHEAD_TICKS.toDouble()))
        val hit = level().clip(
            net.minecraft.world.level.ClipContext(
                currentPos, futurePos,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                this,
            ),
        )
        if (hit.type == HitResult.Type.MISS) return Vec3.ZERO

        val probe = hit.location
        val upClear = !isBlocked(probe.add(0.0, TERRAIN_PROBE_DISTANCE, 0.0))
        val downClear = !isBlocked(probe.subtract(0.0, TERRAIN_PROBE_DISTANCE, 0.0))
        val leftClear = !isBlocked(probe.subtract(perp.scale(TERRAIN_PROBE_DISTANCE)))
        val rightClear = !isBlocked(probe.add(perp.scale(TERRAIN_PROBE_DISTANCE)))

        // Pick the clear direction most aligned with the toward-target vector so the
        // detour stays as close to the intended trajectory as possible.
        val toward = if (targetCentre != null) {
            val v = targetCentre.subtract(currentPos)
            if (v.lengthSqr() > 1.0e-6) v.normalize() else forward
        } else forward

        var bestScore = Double.NEGATIVE_INFINITY
        var bx = 0.0; var by = 0.0; var bz = 0.0; var bMag = 0.0
        if (upClear) {
            val s = toward.y
            if (s > bestScore) { bestScore = s; bx = 0.0; by = 1.0; bz = 0.0; bMag = MAX_AVOIDANCE_RISE }
        }
        if (downClear) {
            val s = -toward.y
            if (s > bestScore) { bestScore = s; bx = 0.0; by = -1.0; bz = 0.0; bMag = MAX_AVOIDANCE_RISE }
        }
        if (leftClear) {
            val s = -perp.dot(toward)
            if (s > bestScore) { bestScore = s; bx = -perp.x; by = 0.0; bz = -perp.z; bMag = MAX_AVOIDANCE_LATERAL }
        }
        if (rightClear) {
            val s = perp.dot(toward)
            if (s > bestScore) { bestScore = s; bx = perp.x; by = 0.0; bz = perp.z; bMag = MAX_AVOIDANCE_LATERAL }
        }
        if (bestScore == Double.NEGATIVE_INFINITY) return Vec3.ZERO
        return Vec3(bx * bMag, by * bMag, bz * bMag)
    }

    private fun isBlocked(pos: Vec3): Boolean =
        level().getBlockState(BlockPos.containing(pos.x, pos.y, pos.z)).blocksMotion()

    /** Find the nearest valid target — hostile mob, player, or non-friendly magic missile —
     *  within [TARGET_SEARCH_RADIUS]. Two scoped class scans keep the chunked entity index
     *  fast-path (broad `Entity::class.java` would disable it). Closest-by-distSqr wins. */
    private fun findNearestTarget(): Entity? {
        val box = AABB(
            this.x - TARGET_SEARCH_RADIUS, this.y - TARGET_SEARCH_RADIUS, this.z - TARGET_SEARCH_RADIUS,
            this.x + TARGET_SEARCH_RADIUS, this.y + TARGET_SEARCH_RADIUS, this.z + TARGET_SEARCH_RADIUS,
        )
        var best: Entity? = null
        var bestDistSqr = Double.MAX_VALUE
        for (e in level().getEntitiesOfClass(LivingEntity::class.java, box)) {
            if (!isValidTarget(e)) continue
            val d = e.distanceToSqr(this)
            if (d < bestDistSqr) { bestDistSqr = d; best = e }
        }
        for (e in level().getEntitiesOfClass(MagicMissileEntity::class.java, box)) {
            if (!isValidTarget(e)) continue
            val d = e.distanceToSqr(this)
            if (d < bestDistSqr) { bestDistSqr = d; best = e }
        }
        return best
    }

    /** Unified target filter — used for live cache validation and for the scan filter.
     *  Hostile mobs (every [Enemy] — covers Monster subclasses plus EnderDragon, Wither,
     *  Phantom, Ghast, Slime, etc. that extend Mob directly without going through Monster)
     *  and players are valid if not dragged by our home ship; another missile is valid if
     *  not friendly (different home ship AND different owner). */
    private fun isValidTarget(entity: Entity): Boolean {
        if (entity === this || entity === this.owner) return false
        if (!entity.isAlive) return false
        return when (entity) {
            is MagicMissileEntity -> !isFriendlyMissile(entity)
            is Enemy, is Player -> entity is LivingEntity && !isDraggedByHomeShip(entity)
            else -> false
        }
    }

    /** Another magic missile counts as "friendly" — and thus ignored as a target — when it
     *  shares our [homeShipId] (excluding the no-ship sentinel, which would otherwise have
     *  every world-fired missile treat every other world-fired missile as friendly) OR the
     *  same owner uuid. World-fired vs. world-fired missiles from *different* players still
     *  target each other; world-fired vs. ship-fired only friendly-match if the world-fired
     *  one is also from the ship's pilot. */
    private fun isFriendlyMissile(other: MagicMissileEntity): Boolean {
        if (other === this) return true
        if (this.homeShipId != NO_SHIP && other.homeShipId == this.homeShipId) return true
        val myOwnerUuid = this.owner?.uuid
        val otherOwnerUuid = other.owner?.uuid
        if (myOwnerUuid != null && myOwnerUuid == otherOwnerUuid) return true
        return false
    }

    /** True if [entity]'s VS2 dragging-ship is [homeShipId]. **World-context short-circuit:**
     *  if [homeShipId] is [NO_SHIP] (missile spawned from a world-mounted dispenser or a
     *  player firing it while not standing on a ship), the IEntityDraggingInformationProvider
     *  lookup is skipped entirely and the answer is always `false` — i.e. every nearby
     *  player + hostile mob is a valid target with no ship-membership filtering. */
    private fun isDraggedByHomeShip(entity: Entity): Boolean {
        if (homeShipId == NO_SHIP) return false
        val info = (entity as? IEntityDraggingInformationProvider)?.draggingInformation
        val shipId = info?.lastShipStoodOn ?: return false
        return shipId == homeShipId
    }

    private fun currentlyDraggingShipId(entity: Entity): Long? {
        val info = (entity as? IEntityDraggingInformationProvider)?.draggingInformation
        return info?.lastShipStoodOn
    }

    companion object {
        const val NO_SHIP: Long = Long.MIN_VALUE

        private const val NBT_HOME_SHIP = "HomeShipId"
        private const val NBT_TARGET_X = "TargetX"
        private const val NBT_TARGET_Y = "TargetY"
        private const val NBT_TARGET_Z = "TargetZ"
        private const val NBT_TARGET_BLOCK = "TargetBlock"

        /** Blocks/tick. Vanilla firework rocket cruises at ~1.2; we use 1.5 (30 blocks/s) so
         *  the missile reads as a fast streak, not a lazy shulker bullet. */
        const val SPEED: Double = 1.5

        /** Per-tick magnitude of the speed chaos — adds a uniform random value in
         *  [-`amp`, +`amp`] blocks/tick to the velocity magnitude each tick (direction
         *  preserved). Independent samples don't accumulate into drift, so the missile
         *  jitters around its nominal speed rather than wandering off it. */
        private const val SPEED_CHAOS_AMPLITUDE: Double = 0.01

        /** Search radius (blocks) for the target scan. Bigger than the missile's straight-line
         *  range per tick so it can re-acquire if the current target moves out of cone. */
        private const val TARGET_SEARCH_RADIUS: Double = 32.0

        /** How often to re-pick the target. Once every half-second keeps the swarm-vs-strafe
         *  behaviour stable without thrashing the entity scan. */
        private const val TARGET_REFRESH_TICKS: Int = 10

        /** Explosion radius. Reduced from the original 2.0 — at this size the missile is a
         *  precise dart, not a creeper, so a successful hit is worth dodging without being
         *  area-of-effect punishing. */
        private const val EXPLOSION_RADIUS: Float = 1.0f

        /** Multiplier applied to the explosion-induced velocity delta on each affected
         *  entity. Vanilla's knockback formula is calibrated for TNT-sized blasts and
         *  punches entities ~1 block/tick at point-blank for any radius; we want a
         *  small dart-blast to nudge, not launch. */
        private const val EXPLOSION_KNOCKBACK_SCALE: Double = 0.3

        /** Direct-hit damage on a mob or player. Matches vanilla shulker bullet (2 hearts).
         *  The explosion's mob damage is *in addition* to this — a direct hit is meant to be
         *  punishing relative to a near-miss that only catches the AoE. */
        private const val DIRECT_HIT_DAMAGE: Float = 4.0f

        /** Sparser still — small ender-coloured pop, not a vanilla curtain. */
        private const val BURST_SPARK_COUNT: Int = 20

        /** Multiplier applied to the spawn velocity via [ServerLevel.sendParticles]'s
         *  `speed` parameter. With `count = 0` the client packet handler multiplies
         *  the (vx, vy, vz) components by this — one knob for tightening the cone
         *  without having to rescale every base constant. */
        private const val BURST_VELOCITY_SCALE: Double = 0.5

        /** Vanilla scales the firework's delta movement by 0.5 before adding scatter. */
        private const val BURST_DELTA_FACTOR: Double = 0.5

        /** Per-spark perpendicular-plane scatter sigma (`gaussian() * 0.15`). */
        private const val BURST_SPREAD_SIGMA: Double = 0.15

        /** Per-call shared perpendicular-plane Gaussian offset (`gaussian() * 0.05`).
         *  Drawn once per burst — gives the whole cone a small random recoil so
         *  back-to-back detonations don't superimpose. */
        private const val BURST_SHARED_OFFSET_SIGMA: Double = 0.05

        /** Outward push along the bias axis: `nextDouble() * 0.5`, positive-only. In
         *  vanilla this axis is hardcoded `+Y`; we rotate it to ride the missile's
         *  movement direction. */
        private const val BURST_DIR_BIAS: Double = 0.5

        /** Volume passed to [EKSounds.MISSILE_BURST_BLAST]. Matches vanilla `Explosion`'s
         *  volume of 4.0 — variable-range × 4 ≈ 64-block audible radius, identical to
         *  the falloff of `entity.generic.explode`. (Vanilla `FireworkParticles$Starter`
         *  uses 20 to read at server-wide range; we want a localised explosion feel.) */
        private const val BURST_SOUND_VOLUME: Float = 4.0f

        /** Per-tick heading-blend split. `KEEP` weight on the current direction, `TOWARD`
         *  weight on the desired-to-target direction. ~7-tick half-life on heading change. */
        private const val STEER_BLEND_KEEP: Double = 0.85
        private const val STEER_BLEND_TOWARD: Double = 0.15

        /** Inside this distance from the target, the toward-blend is linearly scaled
         *  down to zero — turn radius widens on approach instead of corkscrewing. */
        private const val TURN_WIDEN_RADIUS: Double = 3.0

        /** How many ticks ahead the block-avoidance probe ray-casts. At [SPEED] = 1.5
         *  blocks/tick, 8 ticks = 12 blocks of lead — far enough to start detouring
         *  before plowing into a wall, close enough that the detour reads as reactive. */
        private const val TERRAIN_LOOKAHEAD_TICKS: Int = 8

        /** How far up/down/left/right of the ray-hit point the directional probes sample.
         *  Wide enough to find the edge of a typical wall/ceiling, narrow enough that a
         *  small notch in the obstacle still picks a side cleanly. */
        private const val TERRAIN_PROBE_DISTANCE: Double = 4.0

        /** Upper bound on the per-tick vertical velocity the avoidance can inject. ~40%
         *  of [SPEED]; aggressive enough to clear walls that appear close, soft enough
         *  that a glancing obstacle doesn't fling the missile straight up. */
        private const val MAX_AVOIDANCE_RISE: Double = 0.6

        /** Upper bound on the per-tick horizontal velocity the avoidance can inject.
         *  Matched to [MAX_AVOIDANCE_RISE] so no axis dominates the detour. */
        private const val MAX_AVOIDANCE_LATERAL: Double = 0.6

        /** Distance from the target inside which avoidance is disabled. The final
         *  approach commits to the line — better to clip a corner than swerve off the
         *  intended path right at impact range. Bigger than [TURN_WIDEN_RADIUS] so the
         *  turn-radius widen takes effect first, then avoidance shuts off. */
        private const val AVOIDANCE_COMMIT_RADIUS: Double = 8.0

        /** How long the missile is allowed to fly without a valid target before
         *  [fizzle] kicks in. 10 seconds at 20 tps. A missile that has any valid
         *  target this tick (live locked entity, fixed point, or any hostile in
         *  scan range) resets the counter; one with no target accumulates. */
        private const val UNREACHABLE_KILL_TICKS: Int = 200

        /** Squared inter-tick position jump that flags the locked entity as having
         *  teleported. 8 blocks² ≈ entity moved more than ~3 blocks in one tick —
         *  bigger than any natural motion (sprint + knockback ≈ 1 block/tick), small
         *  enough to catch ender pearls and `/tp` reliably. */
        private const val TELEPORT_DISTANCE_SQ: Double = 8.0 * 8.0

        /** Minimum speed factor when the missile is turning hard (heading perpendicular or
         *  worse vs. desired). Linearly scales toward `1.0` as the heading aligns. 0.35 →
         *  perpendicular-turning missile cruises at 35 % of [SPEED]. */
        private const val MIN_SPEED_FACTOR: Double = 0.35

        /** Indicative steady-state RMS magnitude (blocks/tick) of the perpendicular
         *  jitter under the OU process. Not used directly — [NOISE_DECAY] and
         *  [NOISE_KICK] together determine actual magnitude; this is the design target
         *  the two constants were tuned against (~6 % of [SPEED]). */
        private const val NOISE_AMPLITUDE: Double = 0.09

        /** Per-tick persistence factor for the OU jitter random walk. Each tick's noise
         *  is `noise·NOISE_DECAY + random·NOISE_KICK`, so a higher value (closer to 1)
         *  means slower drift, more gradual. At 0.92 the noise carries ~92 % of its
         *  previous value forward each tick — half-life ~8 ticks (~0.4 s at 20 Hz). */
        private const val NOISE_DECAY: Double = 0.92

        /** Per-tick random-kick range for the OU jitter random walk. Each tick adds a
         *  uniform random in `[-NOISE_KICK/2, +NOISE_KICK/2]` to each of `noiseU` and
         *  `noiseV`. Tuned against [NOISE_DECAY] so the steady-state RMS noise magnitude
         *  is roughly [NOISE_AMPLITUDE]. */
        private const val NOISE_KICK: Double = 0.08

        /** Multiplier applied to the noise vector when the missile has a target. The
         *  OU state still walks at full magnitude, but only this fraction of it gets
         *  fed into the velocity each tick — so the homing logic isn't shouted down by
         *  the wobble. 0.4 → noise contribution is 40 % of untargeted; the targeted
         *  flight reads as "still drunk but committed." */
        private const val TARGETING_NOISE_SCALE: Double = 0.4

        private val HOME_SHIP_DATA: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(MagicMissileEntity::class.java, EntityDataSerializers.LONG)
    }
}
