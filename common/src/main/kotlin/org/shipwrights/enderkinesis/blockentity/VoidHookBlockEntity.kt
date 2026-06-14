package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.VoidHookBlock
import org.shipwrights.enderkinesis.physics.VoidGravityController
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.mod.common.BlockStateInfo
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Always world-mode pull (host ship excluded so a ship-mounted hook drags OTHER ships, not its
 * own carrier). Game-tick snapshots the cone-filtered ship set; phys-tick applies the falloff.
 */
@OptIn(PhysTickOnly::class, VsBeta::class)
class VoidHookBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.VOID_HOOK.get(), pos, state),
    BlockEntityPhysicsListener {

    @Volatile override lateinit var dimension: DimensionId

    @Volatile private var cachedPowerLevel: Int = state.getValue(VoidHookBlock.POWER)
    @Volatile private var cachedHx: Double = pos.x + 0.5
    @Volatile private var cachedHy: Double = pos.y + 0.5
    @Volatile private var cachedHz: Double = pos.z + 0.5
    @Volatile private var cachedFacingX: Double = 0.0
    @Volatile private var cachedFacingY: Double = 0.0
    @Volatile private var cachedFacingZ: Double = 0.0
    @Volatile private var cachedNearbyShipIds: LongArray = EMPTY_IDS

    /** Game-thread only; not saved — survivors of chunk unload become regular 1×1×1 ships. */
    private val trackedAssemblies: HashMap<Long, TrackedAssembly> = HashMap()

    /** `remassed` because `assembleToShip` doesn't always populate mass on single-block chunks;
     *  `BlockStateInfo.remassShip` is VS2's recovery path, called once after promotion. */
    private data class TrackedAssembly(val state: BlockState, var remassed: Boolean = false)

    var clientRotationPhase: Double = 0.0
    var clientLastRenderTime: Double = -1.0

    fun serverTick(level: ServerLevel, pos: BlockPos, state: BlockState) {
        cachedPowerLevel = state.getValue(VoidHookBlock.POWER)
        val facing = state.getValue(VoidHookBlock.FACING)

        // Hook world position — body-frame block centre transformed through the ship's
        // shipToWorld if we're on a ship, otherwise the raw world centre.
        val hostShip = level.getLoadedShipManagingPos(pos)
        val center = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        hostShip?.transform?.shipToWorld?.transformPosition(center)
        cachedHx = center.x
        cachedHy = center.y
        cachedHz = center.z

        // Facing direction in world frame. On a ship the body-local FACING vector gets
        // rotated through shipToWorldRotation so the hook keeps pointing the same
        // *world* direction as the ship rolls/turns.
        val facingLocal = Vector3d(
            facing.stepX.toDouble(),
            facing.stepY.toDouble(),
            facing.stepZ.toDouble(),
        )
        hostShip?.transform?.shipToWorldRotation?.transform(facingLocal)
        cachedFacingX = facingLocal.x
        cachedFacingY = facingLocal.y
        cachedFacingZ = facingLocal.z

        // Snapshot the ships actually inside the cone (precise gate: forward, depth ≤
        // [PULL_RADIUS], radial offset ≤ depth × cone-half-angle-tan). Each in-cone ship
        // also gets its [VoidGravityController] touched so its gravity stays cancelled for
        // the next ~two game ticks — the controller is the *only* place gravity gets
        // cancelled, so a ship that leaves the cone simply stops getting touched and
        // gravity restores on its own after the staleness window expires.
        if (cachedPowerLevel > 0) {
            val hostId = hostShip?.id
            val ids = ArrayList<Long>()
            // Gravity is only cancelled when the cone is aimed somewhat downward — without
            // this, a horizontal or upward-facing hook lets ships hover indefinitely with
            // no in-game reason for the floating. A downward cone needs the cancellation
            // because gravity is fighting the pull at the same time.
            val cancelsGravity = cachedFacingY <= -DOWNWARD_FACING_THRESHOLD
            for (s in level.shipObjectWorld.allShips) {
                if (s.id == hostId) continue                   // never pull our own carrier
                if (!worldAabbInCone(s.worldAABB)) continue
                ids.add(s.id)

                if (!cancelsGravity) continue
                val loaded = level.shipObjectWorld.loadedShips.getById(s.id)
                if (loaded != null) {
                    loaded.getOrPutAttachment(VoidGravityController::class.java) { VoidGravityController() }
                        .touch()
                }
            }
            cachedNearbyShipIds = ids.toLongArray()
            emitConeParticles(level)
            applyConeForcesToEntities(level, cachedPowerLevel / 15.0)
            if (level.random.nextInt(TRACE_INTERVAL_TICKS) == 0) attemptAssemblyTrace(level)
        } else {
            cachedNearbyShipIds = EMPTY_IDS
        }
        checkAssembliesLeftCone(level)
    }

    /** Spawn per-tick PORTAL particles uniformly distributed inside the active cone, each
     *  set to visually converge from its volume position back to the hook over its
     *  lifetime. Portal's vanilla `tick()` is `x = xStart + xd · f` with `f: 1 → 0`, so a
     *  particle spawned at `hook` with velocity `(ox, oy, oz)` materialises at `hook +
     *  offset` and lerps inward to `hook` — the "pulled into the apex" trail we want.
     *  (ReversePortalParticle is the OPPOSITE: `x += xd · f` — sprays outward.) */
    private fun emitConeParticles(level: ServerLevel) {
        val count = (cachedPowerLevel * MAX_PARTICLES_PER_TICK / 15).coerceAtLeast(1)
        // Build a perpendicular basis once per tick — cheap (single helper choice + two
        // cross products), reused for every particle in the burst.
        val fx = cachedFacingX; val fy = cachedFacingY; val fz = cachedFacingZ
        val helperX: Double; val helperY: Double; val helperZ: Double
        if (Math.abs(fy) < 0.95) { helperX = 0.0; helperY = 1.0; helperZ = 0.0 }
        else                     { helperX = 1.0; helperY = 0.0; helperZ = 0.0 }
        var e1x = fy * helperZ - fz * helperY
        var e1y = fz * helperX - fx * helperZ
        var e1z = fx * helperY - fy * helperX
        var len = Math.sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
        e1x /= len; e1y /= len; e1z /= len
        var e2x = fy * e1z - fz * e1y
        var e2y = fz * e1x - fx * e1z
        var e2z = fx * e1y - fy * e1x
        len = Math.sqrt(e2x * e2x + e2y * e2y + e2z * e2z)
        e2x /= len; e2y /= len; e2z /= len

        val rand = level.random
        repeat(count) {
            val depth = rand.nextDouble() * PULL_RADIUS
            // sqrt of a uniform random gives uniform 2D density in the disc — without it
            // particles cluster on the axis and the cone shape doesn't read.
            val radial = depth * CONE_HALF_ANGLE_TAN * Math.sqrt(rand.nextDouble())
            val angle = rand.nextDouble() * 2.0 * Math.PI
            val cosA = Math.cos(angle); val sinA = Math.sin(angle)
            // Offset from the hook (= spawn position of the particle visually).
            val ox = fx * depth + (e1x * cosA + e2x * sinA) * radial
            val oy = fy * depth + (e1y * cosA + e2y * sinA) * radial
            val oz = fz * depth + (e1z * cosA + e2z * sinA) * radial
            // sendParticles with count=0 hands (ox, oy, oz) to the particle as its xd/yd/zd.
            // PortalParticle uses those as the initial offset from its recorded `start`
            // position, lerping back to start over ~60 ticks.
            level.sendParticles(
                ParticleTypes.PORTAL,
                cachedHx, cachedHy, cachedHz,
                0, ox, oy, oz, 1.0,
            )
        }
    }

    /** Fire one ray from the hook into a random point inside the cone. If the ray lands on
     *  a world block (not air, not a ship's block, not unbreakable) and the resistance roll
     *  succeeds, assemble that single block into its own ship — the cone immediately starts
     *  treating it as a target and gravity gets cancelled on it like any other in-cone ship.
     *
     *  Probabilistic chew rather than a deterministic sweep: hard blocks (high
     *  `explosionResistance`) almost never come loose, soft ones do quickly. Bedrock and
     *  the like are excluded by the `destroySpeed < 0` check. */
    private fun attemptAssemblyTrace(level: ServerLevel) {
        val rand = level.random
        val fx = cachedFacingX; val fy = cachedFacingY; val fz = cachedFacingZ
        val helperX: Double; val helperY: Double; val helperZ: Double
        if (Math.abs(fy) < 0.95) { helperX = 0.0; helperY = 1.0; helperZ = 0.0 }
        else                     { helperX = 1.0; helperY = 0.0; helperZ = 0.0 }
        var e1x = fy * helperZ - fz * helperY
        var e1y = fz * helperX - fx * helperZ
        var e1z = fx * helperY - fy * helperX
        var len = Math.sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
        e1x /= len; e1y /= len; e1z /= len
        var e2x = fy * e1z - fz * e1y
        var e2y = fz * e1x - fx * e1z
        var e2z = fx * e1y - fy * e1x
        len = Math.sqrt(e2x * e2x + e2y * e2y + e2z * e2z)
        e2x /= len; e2y /= len; e2z /= len

        val depth = rand.nextDouble() * PULL_RADIUS
        val radial = depth * CONE_HALF_ANGLE_TAN * Math.sqrt(rand.nextDouble())
        val angle = rand.nextDouble() * 2.0 * Math.PI
        val cosA = Math.cos(angle); val sinA = Math.sin(angle)
        val endX = cachedHx + fx * depth + (e1x * cosA + e2x * sinA) * radial
        val endY = cachedHy + fy * depth + (e1y * cosA + e2y * sinA) * radial
        val endZ = cachedHz + fz * depth + (e1z * cosA + e2z * sinA) * radial

        // Push the start just past the hook's front face. `level.clip` reports an immediate
        // hit when the start sits inside a collision shape, so starting at the hook's block
        // centre would always self-hit (and the [pos == blockPos] guard would bail every
        // trace). Offset along facing by 0.51 — clears the hook's AABB by 0.01 and lands
        // inside the immediately-adjacent block, so a block touching the hook face stays
        // assembleable.
        //
        // VS2-mixed `level.clip` also reports ship-block hits with `.blockPos` in shipyard
        // coords; the [isBlockInShipyard] / [getLoadedShipManagingPos] guards below reject
        // those without us needing the explicit `clipIncludeShips` machinery.
        val startX = cachedHx + cachedFacingX * 0.51
        val startY = cachedHy + cachedFacingY * 0.51
        val startZ = cachedHz + cachedFacingZ * 0.51
        val ctx = ClipContext(
            Vec3(startX, startY, startZ),
            Vec3(endX, endY, endZ),
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            null as Entity?,
        )
        val hit = level.clip(ctx)
        if (hit.type != HitResult.Type.BLOCK) return

        val pos = hit.blockPos
        if (pos == blockPos) return                            // ray starts inside us; don't chew our own block
        if (level.getLoadedShipManagingPos(pos) != null) return
        if (level.isBlockInShipyard(pos)) return
        val state = level.getBlockState(pos)
        if (state.isAir) return
        if (state.getDestroySpeed(level, pos) < 0f) return    // unbreakable: bedrock, barriers, …

        val resistance = state.block.explosionResistance.toDouble()
        // Linear roll against resistance scaled by [RESISTANCE_SCALE] — dirt-soft blocks at
        // ~98 % per attempt, stone-tier at ~80 %, blast-resistant blocks (obsidian, reinforced
        // deepslate) at 0 %. Single block per successful trace.
        val chance = (1.0 - resistance / RESISTANCE_SCALE).coerceAtLeast(0.0)
        if (rand.nextDouble() > chance) return

        val ship = ShipAssembler.assembleToShip(level, setOf(pos.immutable()), 1.0) ?: return
        // Remember the assembled ship so [checkAssembliesLeftCone] can turn it back into
        // a vanilla falling block when it drifts out of the cone, and so we can run a
        // one-time remass the first tick the ship is loaded.
        trackedAssemblies[ship.id] = TrackedAssembly(state)
    }

    /** For every ship we've assembled and are still tracking: if it has drifted outside
     *  the cone since last tick AND it's still the original 1×1×1 (player hasn't started
     *  expanding it), convert it to a [FallingBlockEntity] at its current world position
     *  using the block state we saved at assembly time. The ship itself is disassembled
     *  by setting its single block to air; VS2's chunk-empty path garbage-collects the
     *  hollow ship.
     *
     *  Two non-obvious bits to get right:
     *  1. Lookup uses `allShips.getById` rather than `loadedShips.getById`. A ship
     *     created by [ShipAssembler.assembleToShip] this tick is in `allShips`
     *     immediately but `loadedShips` only after VS2's next preTick promotes it; using
     *     `loadedShips` here would always drop the just-assembled ship before we'd ever
     *     get to convert it.
     *  2. The "in cone?" gate is re-evaluated geometrically against the current cached
     *     hook position rather than reused from [cachedNearbyShipIds]. That snapshot is
     *     taken *before* [attemptAssemblyTrace] runs, so on the assembly tick the new
     *     ship isn't in it either — checking the cached set directly would convert the
     *     ship immediately, on the very tick we assembled it.
     *
     *  Ships that grew past 1×1×1 (the player welded blocks onto them) drop out of the
     *  tracker without conversion — they're no longer a "loose chewed block," they're a
     *  real ship now. */
    private fun checkAssembliesLeftCone(level: ServerLevel) {
        if (trackedAssemblies.isEmpty()) return
        val iter = trackedAssemblies.entries.iterator()
        while (iter.hasNext()) {
            val (shipId, entry) = iter.next()
            val ship = level.shipObjectWorld.allShips.getById(shipId)
            if (ship == null) { iter.remove(); continue }           // ship gone already

            // One-shot remass once the ship has promoted to LoadedServerShip. VS2's
            // `assembleToShip` leaves single-block chunks at mass 0; without this they'd
            // never get pulled (the cone-force path early-returns on `mass <= 0`).
            if (!entry.remassed) {
                val loaded = level.shipObjectWorld.loadedShips.getById(shipId)
                if (loaded != null && BlockStateInfo.remassShip(level, loaded)) entry.remassed = true
            }

            // Wait for the remass to succeed before considering conversion. Before mass
            // is computed, `inertiaData.centerOfMass` is zero, so
            // `transform.positionInWorld` reports a garbage position derived from that
            // zero — [isShipInCone] would then false-negative on the cone gate, fire the
            // conversion path, and `level.setBlock(shipyardPos, AIR)` would empty the
            // ship the same tick it was assembled. The remass flag is our "ship is real
            // now" signal.
            if (!entry.remassed) continue

            if (isShipInCone(ship)) continue                        // still chewing it
            val ab = ship.shipAABB
            if (ab == null) { iter.remove(); continue }
            val dx = ab.maxX() - ab.minX()
            val dy = ab.maxY() - ab.minY()
            val dz = ab.maxZ() - ab.minZ()
            if (dx != 1 || dy != 1 || dz != 1) {                    // grew beyond a single block
                iter.remove()
                continue
            }
            convertToFallingBlock(level, ship, entry.state, ab)
            iter.remove()
        }
    }

    /** Geometric cone-membership test against the current cached hook position and
     *  facing. Same shape as [worldAabbInCone] but takes a `Ship`; convenience for the
     *  tracked-assembly path which already has the `Ship` reference rather than the
     *  bare AABB. */
    private fun isShipInCone(ship: org.valkyrienskies.core.api.ships.Ship): Boolean =
        worldAabbInCone(ship.worldAABB)

    /** AABB-vs-cone intersection test. Returns true iff the world-frame AABB overlaps
     *  the cone (apex at the cached hook position, axis along the cached facing, length
     *  `PULL_RADIUS`, half-angle `arctan(CONE_HALF_ANGLE_TAN)`). Combines two cheap tests:
     *
     *   1. **Axis pierces AABB** — slab method on the cone axis ray clipped to
     *      `t ∈ [0, PULL_RADIUS]`. Catches the case where the cone's centre line passes
     *      *through* the AABB but every corner is outside the flare.
     *   2. **Any AABB corner inside the cone** — point-in-cone test on each of the 8
     *      corners. Catches the case where part of the AABB sticks into the flare from
     *      the side without the axis grazing it.
     *
     *  Together these cover the realistic cases. Exotic geometries where neither holds
     *  but the cone still clips a face are vanishingly rare for ship-sized AABBs and the
     *  32-block cone — and would only cause a false-negative (ship not pulled) which is
     *  the safer failure mode. */
    private fun worldAabbInCone(ab: org.joml.primitives.AABBdc): Boolean {
        if (axisIntersectsAabb(ab)) return true
        val tanSq = CONE_HALF_ANGLE_TAN * CONE_HALF_ANGLE_TAN
        for (x in doubleArrayOf(ab.minX(), ab.maxX())) {
            for (y in doubleArrayOf(ab.minY(), ab.maxY())) {
                for (z in doubleArrayOf(ab.minZ(), ab.maxZ())) {
                    val dx = x - cachedHx
                    val dy = y - cachedHy
                    val dz = z - cachedHz
                    val depth = dx * cachedFacingX + dy * cachedFacingY + dz * cachedFacingZ
                    if (depth <= 0.0 || depth > PULL_RADIUS) continue
                    val rx = dx - cachedFacingX * depth
                    val ry = dy - cachedFacingY * depth
                    val rz = dz - cachedFacingZ * depth
                    if (rx * rx + ry * ry + rz * rz <= depth * depth * tanSq) return true
                }
            }
        }
        return false
    }

    /** Slab method: clip the cone's axis segment (apex → apex + facing·PULL_RADIUS)
     *  against the AABB. Returns true iff any portion of the segment lies inside the
     *  AABB. When all three facing components are zero (degenerate cone), the apex itself
     *  is tested for AABB containment. */
    private fun axisIntersectsAabb(ab: org.joml.primitives.AABBdc): Boolean {
        var tMin = 0.0
        var tMax = PULL_RADIUS

        if (Math.abs(cachedFacingX) < 1e-12) {
            if (cachedHx < ab.minX() || cachedHx > ab.maxX()) return false
        } else {
            val invD = 1.0 / cachedFacingX
            val t1 = (ab.minX() - cachedHx) * invD
            val t2 = (ab.maxX() - cachedHx) * invD
            val tLo = Math.min(t1, t2); val tHi = Math.max(t1, t2)
            if (tLo > tMin) tMin = tLo
            if (tHi < tMax) tMax = tHi
            if (tMin > tMax) return false
        }
        if (Math.abs(cachedFacingY) < 1e-12) {
            if (cachedHy < ab.minY() || cachedHy > ab.maxY()) return false
        } else {
            val invD = 1.0 / cachedFacingY
            val t1 = (ab.minY() - cachedHy) * invD
            val t2 = (ab.maxY() - cachedHy) * invD
            val tLo = Math.min(t1, t2); val tHi = Math.max(t1, t2)
            if (tLo > tMin) tMin = tLo
            if (tHi < tMax) tMax = tHi
            if (tMin > tMax) return false
        }
        if (Math.abs(cachedFacingZ) < 1e-12) {
            if (cachedHz < ab.minZ() || cachedHz > ab.maxZ()) return false
        } else {
            val invD = 1.0 / cachedFacingZ
            val t1 = (ab.minZ() - cachedHz) * invD
            val t2 = (ab.maxZ() - cachedHz) * invD
            val tLo = Math.min(t1, t2); val tHi = Math.max(t1, t2)
            if (tLo > tMin) tMin = tLo
            if (tHi < tMax) tMax = tHi
            if (tMin > tMax) return false
        }
        return true
    }

    private fun convertToFallingBlock(
        level: ServerLevel,
        ship: org.valkyrienskies.core.api.ships.Ship,
        state: BlockState,
        shipyardBox: org.joml.primitives.AABBic,
    ) {
        val worldCenter = ship.transform.positionInWorld
        val fallPos = BlockPos.containing(worldCenter.x(), worldCenter.y(), worldCenter.z())
        // `fall(...)` also writes the block's fluid state to `fallPos` in the world — for
        // a non-waterlogged block that's air, which is a no-op against the empty cell the
        // floating ship was visually inhabiting.
        FallingBlockEntity.fall(level, fallPos, state)
        // Strip the single block out of the ship's shipyard chunk; VS2 will GC the
        // now-empty ship on its next pass.
        val shipyardPos = BlockPos(shipyardBox.minX(), shipyardBox.minY(), shipyardBox.minZ())
        level.setBlock(shipyardPos, Blocks.AIR.defaultBlockState(), 3)
    }

    override fun physTick(physShip: PhysShip?, physLevel: PhysLevel) {
        val power = cachedPowerLevel
        if (power <= 0) return
        val powerFraction = power / 15.0
        for (id in cachedNearbyShipIds) {
            val s = physLevel.getShipById(id) ?: continue
            if (s.isStatic || s.mass <= 0.0) continue
            applyConeForces(s, powerFraction)
        }
    }

    /** Game-tick variant of [applyConeForces] for raw [Entity] targets — mobs, items,
     *  players, projectiles. Same cone gate, same axial pull + tangential damping shape,
     *  applied as a `deltaMovement` delta because entities run on the 20 Hz game tick (not
     *  the 60 Hz phys tick). For [ServerPlayer]s we also push a [ClientboundSetEntityMotionPacket]
     *  — the server-side `deltaMovement` is otherwise overwritten by the client's next move
     *  packet, so without this the player feels nothing. */
    private fun applyConeForcesToEntities(level: ServerLevel, powerFraction: Double) {
        val r = PULL_RADIUS
        val aabb = AABB(
            cachedHx - r, cachedHy - r, cachedHz - r,
            cachedHx + r, cachedHy + r, cachedHz + r,
        )
        val entities = level.getEntitiesOfClass(Entity::class.java, aabb)
        if (entities.isEmpty()) return
        val dt = 1.0 / 20.0                              // game-tick seconds
        for (entity in entities) {
            if (!entity.isAlive) continue
            if (entity is ServerPlayer && entity.isCreative) continue
            val ex = entity.x; val ey = entity.y; val ez = entity.z
            val dx = ex - cachedHx
            val dy = ey - cachedHy
            val dz = ez - cachedHz

            val depth = dx * cachedFacingX + dy * cachedFacingY + dz * cachedFacingZ
            if (depth <= 0.0 || depth > PULL_RADIUS) continue

            val rx = dx - cachedFacingX * depth
            val ry = dy - cachedFacingY * depth
            val rz = dz - cachedFacingZ * depth
            val radialSq = rx * rx + ry * ry + rz * rz
            val maxRadial = depth * CONE_HALF_ANGLE_TAN
            if (radialSq > maxRadial * maxRadial) continue

            val depthFrac = depth / PULL_RADIUS
            val depthPower = 1.0 - depthFrac * (1.0 - MIN_DEPTH_POWER)

            val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < 1e-6) continue
            val toHookDirX = -dx / dist
            val toHookDirY = -dy / dist
            val toHookDirZ = -dz / dist

            // Axial pull velocity-delta. Entities are far lighter than ships in feel —
            // a tenth of a g is enough to clearly drag an item or mob across the cone —
            // so this uses a separate, much smaller acceleration than [PULL_ACCEL].
            val pullDeltaV = ENTITY_PULL_ACCEL * powerFraction * depthPower * dt

            val v = entity.deltaMovement
            var newVx = v.x + toHookDirX * pullDeltaV
            var newVy = v.y + toHookDirY * pullDeltaV
            var newVz = v.z + toHookDirZ * pullDeltaV

            // Tangential damping: shave off the perpendicular-to-line part. Clamp to 1.0 so
            // a high damp rate can't *invert* the velocity.
            val vRadial = newVx * toHookDirX + newVy * toHookDirY + newVz * toHookDirZ
            val vTangentX = newVx - toHookDirX * vRadial
            val vTangentY = newVy - toHookDirY * vRadial
            val vTangentZ = newVz - toHookDirZ * vRadial
            val dampFrac = (TANGENT_DAMP_RATE * powerFraction * depthPower * dt).coerceAtMost(1.0)
            newVx -= vTangentX * dampFrac
            newVy -= vTangentY * dampFrac
            newVz -= vTangentZ * dampFrac

            entity.deltaMovement = Vec3(newVx, newVy, newVz)
            entity.hurtMarked = true
            if (entity is ServerPlayer) entity.connection.send(ClientboundSetEntityMotionPacket(entity))
        }
    }

    /** Per-tick cone forces for one ship: axial pull toward the hook apex (the "grab") plus
     *  tangential-velocity damping so a target doesn't sidescroll past us. Gravity is
     *  cancelled separately by [VoidGravityController].
     *
     *  No AABB re-check on the physics thread — `PhysShipImpl.getWorldAABB` throws
     *  `NotImplementedError`, so the AABB-vs-cone gate is game-thread only (it populated
     *  [cachedNearbyShipIds] at the last [serverTick]). The 50 ms staleness window is
     *  fine for gameplay: a ship moving 10 m/s drifts half a block between game ticks,
     *  well within the cone's flare. Force application point is the COM —
     *  `applyInvariantForce` at COM produces clean translation with no induced torque.
     *  Depth used for the strength curve is the COM's depth along the cone axis
     *  *clamped* into `[0, PULL_RADIUS]` so a ship whose COM has briefly drifted just
     *  outside the apex/tip still gets a sensible strength multiplier. */
    private fun applyConeForces(ship: PhysShip, powerFraction: Double) {
        val com = ship.kinematics.position
        val dx = com.x() - cachedHx
        val dy = com.y() - cachedHy
        val dz = com.z() - cachedHz

        val rawDepth = dx * cachedFacingX + dy * cachedFacingY + dz * cachedFacingZ
        val depth = rawDepth.coerceIn(0.0, PULL_RADIUS)

        val depthFrac = depth / PULL_RADIUS
        val depthPower = 1.0 - depthFrac * (1.0 - MIN_DEPTH_POWER)
        val mass = ship.mass

        val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1e-6) return
        val toHookDirX = -dx / dist
        val toHookDirY = -dy / dist
        val toHookDirZ = -dz / dist

        // Axial pull toward the apex. Mass-scaled to a fixed acceleration so big ships and
        // small ships approach the hook at the same rate; depth-scaled with the
        // [MIN_DEPTH_POWER] floor so the far end isn't noticeably weaker than the apex.
        val pullMag = mass * PULL_ACCEL * powerFraction * depthPower
        var fxOut = toHookDirX * pullMag
        var fyOut = toHookDirY * pullMag
        var fzOut = toHookDirZ * pullMag

        // Tangential-velocity damping: oppose the part of velocity perpendicular to the
        // ship→hook line. Same mass-independent decay-rate scaling as before.
        val vel = ship.velocity
        val vRadial = vel.x() * toHookDirX + vel.y() * toHookDirY + vel.z() * toHookDirZ
        val vTangentX = vel.x() - toHookDirX * vRadial
        val vTangentY = vel.y() - toHookDirY * vRadial
        val vTangentZ = vel.z() - toHookDirZ * vRadial
        val dampCoeff = TANGENT_DAMP_RATE * powerFraction * depthPower * mass
        fxOut -= vTangentX * dampCoeff
        fyOut -= vTangentY * dampCoeff
        fzOut -= vTangentZ * dampCoeff

        ship.applyInvariantForce(Vector3d(fxOut, fyOut, fzOut))
    }

    companion object {
        /** Cone-axis pull range (blocks) — the cone's axial length. */
        const val PULL_RADIUS: Double = 32.0

        /** Tangent of the cone's half-angle. `tan(30°) ≈ 0.577` gives a 60°-total cone — at
         *  max depth (32 b) the cone base reaches ~18.5 b of radial offset. Tight enough to
         *  read as directional, wide enough that a target ship doesn't have to be perfectly
         *  on-axis. */
        const val CONE_HALF_ANGLE_TAN: Double = 0.577

        /** How far below horizontal the cone's facing has to be aimed before the hook
         *  cancels gravity on captured ships. `0.5` corresponds to a facing-Y component of
         *  `-0.5`, i.e. > 30° below horizontal — so a level-pointed or upward-aimed hook
         *  leaves gravity on (ships pulled sideways or upward still feel their own weight),
         *  while a hook canted noticeably downward zeroes gravity so the pull isn't
         *  fighting `m·g` at the same time. */
        const val DOWNWARD_FACING_THRESHOLD: Double = 0.5

        /** Floor of the depth-based pull-power curve — the value `depthPower` lerps down
         *  to at `depth == PULL_RADIUS` (the furthest point from the focus). `0.95` means
         *  the cone's far edge feels nearly identical to the apex; the depth scaling exists
         *  only as a faint cue rather than a meaningful falloff. */
        const val MIN_DEPTH_POWER: Double = 0.95

        /** Axial-pull acceleration (m/s²) for **ships** at full redstone power, at the
         *  cone apex. Mass-scaled (so all ship sizes approach at the same rate). The
         *  applied force scales linearly with the redstone signal strength `power/15`
         *  — power 0 → 0 N, power 15 → full strength. At `3 m/s² ≈ 0.3 g` the pull
         *  reads as a very gentle draw. */
        const val PULL_ACCEL: Double = 3.0

        /** Axial-pull acceleration (m/s²) for **entities** (mobs, items, players,
         *  projectiles) at full redstone power. Same `power/15` linear scaling as
         *  [PULL_ACCEL]. Kept much lower than the ship value because entities are vastly
         *  lighter and would otherwise be snatched. */
        const val ENTITY_PULL_ACCEL: Double = 0.3

        /** Exponential-decay rate (1/s) for the tangential-velocity damping at full
         *  redstone power. The applied force is `−rate · (power/15) · depthPower · mass ·
         *  v_tangential` — power 0 → no damp, power 15 → full damp. At full signal and at
         *  the cone's apex that's `1/0.375 ≈ 2.67 s` — a moving target centres on the
         *  hook axis over a few seconds of being inside the cone. */
        const val TANGENT_DAMP_RATE: Double = 0.375

        /** Particle emission count at redstone power 15, scaled linearly down with power.
         *  At power 15 we spawn 10 particles/tick (200/s) which reads as a clearly visible
         *  pull stream without saturating the client's particle budget. */
        const val MAX_PARTICLES_PER_TICK: Int = 10

        /** Average game-tick interval between assembly-trace attempts. `nextInt(20) == 0`
         *  → 5 % per powered tick, so a hook chews at ~1 attempt/second. */
        const val TRACE_INTERVAL_TICKS: Int = 20

        /** Resistance scale for the assembly-roll. Chance per trace is `max(0, 1 −
         *  resistance / SCALE)`. With `30`: dirt (0.5) → ~98 %, stone-tier (6) → ~80 %,
         *  obsidian (1200) → 0 %. */
        const val RESISTANCE_SCALE: Double = 30.0

        private val EMPTY_IDS = LongArray(0)
    }
}
