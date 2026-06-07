package org.shipwrights.enderkinesis.blockentity

import com.mojang.logging.LogUtils
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.CrepusculiteLatticeBlock
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import org.shipwrights.enderkinesis.physics.CrepusculiteLatticeForceInducer
import org.shipwrights.enderkinesis.physics.GerstnerOcean
import org.shipwrights.enderkinesis.physics.LatticeCatchZone
import org.shipwrights.enderkinesis.physics.LatticeRegistry
import org.shipwrights.enderkinesis.physics.OceanDepth
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKParticles
import org.valkyrienskies.mod.api.isBlockInShipyard
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

class CrepusculiteLatticeBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.CREPUSCULITE_LATTICE.get(), pos, state) {

    /** Rescan the ship's blocks at most this often (server ticks); a full scan is expensive. */
    private var rescanCooldown = 0

    /** Ship-local block triples (x,y,z flattened). VS2 ships routinely roll/invert, so the
     *  ship's Y axis must never be collapsed — world up is the only valid vertical. */
    private var shipBlocks: IntArray = IntArray(0)

    /** Subset of [shipBlocks] with at least one empty neighbour — only these can touch water. */
    private var exteriorBlocks: IntArray = IntArray(0)

    /** Previous-tick depth below the wavy surface, keyed by ship-local position so the splash
     *  trigger survives ship translation/rotation. */
    private val blockPrevDepth = Long2DoubleOpenHashMap()

    /** Lowest / highest solid Y per ship-local (x,z) column. Enclosure (dry interior room, not
     *  floatable) requires a solid block both above AND below the entity in its own column —
     *  intrinsic to block layout, valid at any ship orientation. */
    private val colMinY = Long2IntOpenHashMap().apply { defaultReturnValue(Int.MAX_VALUE) }
    private val colMaxY = Long2IntOpenHashMap().apply { defaultReturnValue(Int.MIN_VALUE) }

    /**
     * The virtual ocean's sea level — the lattice's world Y. Captured continuously while the
     * lattice is a *free world block* (the only time its true world position is unambiguous) and
     * persisted in NBT. VS2's [ShipAssembler] copies block-entity NBT through assembly
     * (`saveWithFullMetadata()` → `load`), so the freshly-built ship's lattice already knows the
     * correct sea level on its very first tick — instead of reading a not-yet-settled ship
     * transform and freezing a y≈0 value.
     */
    private var seaLevel: Double? = null

    /**
     * The dimension [seaLevel] was captured in (id string), persisted alongside it. A cross-
     * dimension ship teleport recreates this BE in the new dimension from NBT, so a runtime
     * "last dimension" field would always start null and never detect the change — only the
     * *persisted* capture dimension reveals that the stored [seaLevel] is now stale.
     */
    private var seaLevelDim: String? = null

    /**
     * Nudge this lattice's sea level by [delta] blocks (the Guide to Stratus: right-click raises,
     * left-click lowers). Mutates the captured [seaLevel] directly, so the adjustment is
     * intentionally **transient** — deactivating the lattice (redstone 15) or a cross-dimension
     * teleport nulls [seaLevel] and re-captures it at the lattice's current position, discarding
     * the edit. Takes effect on the next [serverTick] (which re-pushes the level to the inducer).
     * Returns the new sea level, or null if it isn't captured yet (lattice inactive / not on a
     * ship), in which case nothing changes.
     */
    fun nudgeSeaLevel(delta: Double): Double? {
        val next = (seaLevel ?: return null) + delta
        seaLevel = next
        setChanged()
        return next
    }

    override fun setRemoved() {
        this.level?.let { LatticeRegistry.unregister(it, this) }
        super.setRemoved()
    }

    /**
     * Common per-tick entry — runs on *both* sides. Computes this lattice's current
     * [LatticeCatchZone] (world ellipsoid + sea level + Gerstner params) and publishes it to
     * [LatticeRegistry] so the [CrepusculiteLatticeFluidProbe] can read it without doing any
     * ship transforms per entity. On the server side, then runs the existing [serverTick]
     * (force application, particles, effect bookkeeping). On the client side, only the
     * publish runs — that's enough to give the client mixin the same-tick "in water" answer.
     */
    fun commonTick(level: Level, pos: BlockPos, state: BlockState) {
        publishZone(level, pos)
        if (level is ServerLevel) serverTick(level, pos, state)
    }

    /**
     * Compute the world-space catch zone for this lattice and publish it to the registry.
     *
     *  Failure modes that *unregister* (so the probe doesn't keep a stale zone):
     *   - ship resolves to null (BE in shipyard chunk but ship gone, or in world with no
     *     managing ship — the latter happens briefly during cross-dim teleport).
     *   - shipAABB is null (ship has no contributing blocks yet).
     *   - sea level not yet captured (BE hasn't been free-world ticked or had it stamped).
     *   - inflated ellipsoid extents collapse to non-positive (degenerate ship).
     */
    private fun publishZone(level: Level, pos: BlockPos) {
        val ship = level.getLoadedShipManagingPos(pos)
        if (ship == null) { LatticeRegistry.unregister(level, this); return }
        val sb = ship.shipAABB
        if (sb == null) { LatticeRegistry.unregister(level, this); return }
        val waterLineY = seaLevel
        if (waterLineY == null) { LatticeRegistry.unregister(level, this); return }

        // World AABB of the ship: 8 shipyard-corner transforms → bounding world box.
        val s2w = ship.transform.shipToWorld
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        val corner = Vector3d()
        for (cxI in intArrayOf(sb.minX(), sb.maxX() + 1)) {
            for (cyI in intArrayOf(sb.minY(), sb.maxY() + 1)) {
                for (czI in intArrayOf(sb.minZ(), sb.maxZ() + 1)) {
                    corner.set(cxI.toDouble(), cyI.toDouble(), czI.toDouble())
                    s2w.transformPosition(corner)
                    if (corner.x < minX) minX = corner.x; if (corner.x > maxX) maxX = corner.x
                    if (corner.y < minY) minY = corner.y; if (corner.y > maxY) maxY = corner.y
                    if (corner.z < minZ) minZ = corner.z; if (corner.z > maxZ) maxZ = corner.z
                }
            }
        }
        val cx = (minX + maxX) * 0.5; val cy = (minY + maxY) * 0.5; val cz = (minZ + maxZ) * 0.5
        val ax = (maxX - minX) * 0.5 * AABB_INFLATE + AABB_MARGIN
        val ay = (maxY - minY) * 0.5 * AABB_INFLATE + AABB_MARGIN
        val az = (maxZ - minZ) * 0.5 * AABB_INFLATE + AABB_MARGIN
        if (ax <= 0.0 || ay <= 0.0 || az <= 0.0) { LatticeRegistry.unregister(level, this); return }

        // Wave params: server reads the inducer's live values; client doesn't have the
        // inducer attachment (it's a server-only VS2 attachment), so it falls back to a
        // sensible default. This only affects the *push magnitude* — the in-zone check
        // ignores wave params entirely.
        val waveTime: Double
        val waveIntensity: Double
        if (level is ServerLevel) {
            // Re-look up the ship through the ServerLevel-typed extension so the receiver
            // has the [LoadedServerShip] type with getAttachment. The base-Level overload
            // we used above returns the wider [Ship] interface which doesn't expose it.
            val serverShip = level.getLoadedShipManagingPos(pos)
            val inducer = serverShip?.getAttachment(CrepusculiteLatticeForceInducer::class.java)
            waveTime = inducer?.waveTime ?: level.gameTime.toDouble()
            waveIntensity = inducer?.waveIntensity ?: 1.0
        } else {
            waveTime = level.gameTime.toDouble()
            waveIntensity = 1.0
        }

        LatticeRegistry.publish(
            level, this,
            LatticeCatchZone(cx, cy, cz, ax, ay, az, waterLineY, waveTime, waveIntensity),
        )
    }

    /**
     * Stamp the sea level directly (the lattice's world Y), used by the geode worldgen feature: a
     * generated lattice is assembled into a ship before its block entity ever ticks as a free
     * world block, so [serverTick] never gets to record it. Set here at creation it persists
     * through VS2 assembly via [saveAdditional]/[load] like the player-built case. Only sets if
     * not already known, so it never clobbers a real captured value.
     */
    fun captureSeaLevel(worldY: Double) {
        if (seaLevel == null) {
            seaLevel = worldY
            setChanged()
        }
    }

    fun serverTick(level: Level, pos: BlockPos, state: BlockState) {
        if (level !is ServerLevel) return

        val curDimId = level.dimension().location().toString()
        val ship = level.getLoadedShipManagingPos(pos)

        // A stored sea level is tagged with the dimension it was captured in. First sighting →
        // adopt the current dimension. A mismatch means the ship was cross-dimension teleported,
        // so the sea level is stale: forget it and tear down the inducer so the virtual ocean
        // re-derives its level at the new world position (rebuilt below from the ship transform).
        if (seaLevel != null) {
            if (seaLevelDim == null) {
                seaLevelDim = curDimId
                setChanged()
            } else if (seaLevelDim != curDimId) {
                seaLevel = null
                seaLevelDim = null
                setChanged()
                ship?.removeAttachment(CrepusculiteLatticeForceInducer::class.java)
            }
        }
        if (ship == null) {
            // A block in the shipyard whose ship just isn't resolved this tick is NOT a free
            // world block — its `pos` is shipyard coords (y≈128). Recording that would clobber
            // the real sea level (stamped at assembly / captured while genuinely in the world).
            if (level.isBlockInShipyard(pos)) return
            // Genuine free world block: its world Y *is* the sea level. Record so it carries
            // through assembly intact.
            val here = pos.y + 0.5
            if (seaLevel != here || seaLevelDim != curDimId) {
                seaLevel = here
                seaLevelDim = curDimId
                setChanged()
            }
            return
        }

        // Analogue redstone scales the virtual fluid density; 0 density = fully disabled.
        val power = state.getValue(BlockStateProperties.POWER)
        val density = CrepusculiteLatticeBlock.densityFor(power)
        if (density <= 0.0) {
            // Deactivating clears the water line so the *next* activation re-captures it at the
            // lattice's current world position ("the water line is where the lattice activates").
            if (seaLevel != null) {
                seaLevel = null
                seaLevelDim = null
                setChanged()
            }
            ship.removeAttachment(CrepusculiteLatticeForceInducer::class.java)
            return
        }

        // Prefer the pre-assembly sea level. Fall back to the ship transform only if it was never
        // captured (e.g. built directly in the shipyard), and don't lock in an implausible value
        // from a not-yet-settled transform — retry next tick instead.
        val waterLine = seaLevel ?: run {
            val world = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            ship.transform.shipToWorld.transformPosition(world)
            if (world.y() < level.minBuildHeight) return
            // Lock in (and persist) the re-derived level, tagged with this dimension, so it's
            // stable and a later cross-dimension teleport is detected as stale again.
            val y = world.y()
            seaLevel = y
            seaLevelDim = curDimId
            setChanged()
            y
        }

        val inducer = ship.getOrPutAttachment(CrepusculiteLatticeForceInducer::class.java) {
            CrepusculiteLatticeForceInducer(waterLineY = waterLine)
        }

        // A ship can have many lattices, but only one drives the inducer + particles + entity
        // float. Authority is claimed by whichever lattice ticks first after the inducer is
        // (re)created; once set, other lattices on the same ship become passive followers. If
        // the authority lattice stops ticking (block broken, chunk unloaded) its `tickedAt`
        // timestamp goes stale and another lattice can claim — see
        // [CrepusculiteLatticeForceInducer.AUTHORITY_STALE_TICKS].
        //
        // Gate is keyed on BE *identity*, not [BlockPos]. VS2's assembly path can leave
        // multiple BE instances registered for the same shipyard position, and a pos-keyed
        // gate (`currentAuthority == blockPos`) lets every duplicate pass — each sees its own
        // pos and claims — so each emits the full particle set, scans, splashes, and floats.
        // Reference equality (`===`) picks exactly one BE; the others fall through the
        // early-return below.
        val currentAuthority = inducer.authorityHolder
        val authorityStale = currentAuthority != null && (
            currentAuthority.isRemoved ||
                level.gameTime - inducer.authorityTickedAt > CrepusculiteLatticeForceInducer.AUTHORITY_STALE_TICKS
        )
        if (!(currentAuthority == null || currentAuthority === this || authorityStale)) return
        inducer.authorityHolder = this
        inducer.authorityTickedAt = level.gameTime

        // Re-push the (possibly player-adjusted) sea level each tick; the inducer caches it from
        // creation, so a Guide-to-Stratus nudge wouldn't otherwise reach the physics/particles.
        inducer.waterLineY = waterLine

        // Analogue-redstone-controlled fluid density (0 → 1500, 15 → 0).
        inducer.fluidDensity = density

        // Shared clock so the physics wave matches the client visual wave exactly.
        inducer.waveTime = level.gameTime.toDouble()

        // Depth-driven wave intensity: wave plane height vs the world heightmap beneath the
        // lattice's current world column (the physics thread can't read the level itself).
        val lw = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        ship.transform.shipToWorld.transformPosition(lw)
        // Ygann's Abyss is totally void — the heightmap reads as min-build-height everywhere,
        // so the normal depth-from-terrain calc would saturate to max intensity. Skip the
        // calc and use a flat dimension-wide intensity instead.
        inducer.waveIntensity = if (level.dimension() == YgannAbyss.LEVEL_KEY) {
            YGANN_ABYSS_WAVE_INTENSITY
        } else {
            // Shared depth metric: heightmap in open dims, downward floor scan under a ceiling
            // (Nether's roof heightmap would give one flat intensity dimension-wide).
            val terrainY = OceanDepth.terrainY(
                level, Math.floor(lw.x).toInt(), Math.floor(lw.z).toInt(), inducer.waterLineY,
            )
            GerstnerOcean.intensityFor(inducer.waterLineY, terrainY)
        }

        // Per-block buoyancy needs the ship's solid blocks, but the Physics thread can't read the
        // world — capture them here (server thread). Throttled: scanning the ship's whole AABB is
        // O(volume) and must not run every tick.
        if (rescanCooldown-- <= 0) {
            rescanCooldown = RESCAN_INTERVAL_TICKS
            scanShipBlocks(level, ship.shipAABB)?.let { scanned ->
                inducer.setBlocks(scanned)
                shipBlocks = scanned
                exteriorBlocks = computeExterior(scanned)
                blockPrevDepth.clear()   // block set changed — restart crossing history
                colMinY.clear(); colMaxY.clear()
                var bi = 0
                while (bi < scanned.size) {
                    val k = colKey(scanned[bi], scanned[bi + 2])  // ship-local (x, z)
                    val by = scanned[bi + 1]
                    if (by < colMinY.get(k)) colMinY.put(k, by)
                    if (by > colMaxY.get(k)) colMaxY.put(k, by)
                    bi += 3
                }
            }
        }

        // Server-authoritative virtual-ocean particles, budgeted + clustered. Particles whose
        // candidate position falls inside the ship's column footprint are culled, and the
        // budget is re-spent outside the hull instead — so the visible particle count stays
        // the same regardless of where the ship sits in the ellipsoid.
        if (level.gameTime % PARTICLE_INTERVAL_TICKS == 0L) {
            val densityFactor =
                (density / CrepusculiteLatticeForceInducer.DEFAULT_FLUID_DENSITY).coerceIn(0.0, 1.0)
            emitOceanParticles(
                level, ship.shipAABB,
                ship.transform.shipToWorld, ship.transform.worldToShip,
                inducer.waterLineY, densityFactor,
            )
        }

        // Per-block splash when an exterior hull block pierces the water.
        emitSplash(level, ship, inducer.waterLineY, inducer.waveTime, inducer.waveIntensity)

        // Free-floating entities in the ship's volume bob on the virtual ocean.
        floatEntities(level, ship, inducer.waterLineY, inducer.waveTime, inducer.waveIntensity)
    }

    /**
     * Safety net: anything that falls off the ship lands on the virtual ocean instead of the void,
     * so players/mobs can get back aboard. Every tick we sweep the **whole ocean ellipse** (not
     * just the ship AABB — a jumper arcs out past the hull), and for an airborne, non-deck entity
     * whose feet dip under the Gerstner surface we *gently* arrest its descent and let it bob at
     * the surface. It is deliberately soft and additive — it damps falling and adds a small
     * submersion lift rather than slamming a velocity — and it never touches a flying/elytra/riding
     * player, so creative flight is left alone. No horizontal wave advection — the catcher never
     * pushes an entity sideways, so drifting off the ocean's edge stays possible (it just isn't
     * forced). Players are client-authoritative, so the (gentle) result is delivered with a
     * motion packet.
     *
     * While a living entity is actively floating it is given the **Crepuscular Floatation**
     * effect; if it then transitions into a gated area (onto the deck, out of the ellipse, onto
     * ground) it keeps floating for the effect's remaining [GRACE_TICKS] grace window instead of
     * dropping the instant it crosses the boundary.
     */
    private fun floatEntities(
        level: ServerLevel,
        ship: org.valkyrienskies.core.api.ships.LoadedServerShip,
        waterLineY: Double, waveTime: Double, waveIntensity: Double,
    ) {
        // Ship-local AABB → world AABB (transform the 8 corners).
        val s = ship.shipAABB ?: return
        val toWorld = ship.transform.shipToWorld
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        val corner = Vector3d()
        for (cxI in intArrayOf(s.minX(), s.maxX() + 1)) {
            for (cyI in intArrayOf(s.minY(), s.maxY() + 1)) {
                for (czI in intArrayOf(s.minZ(), s.maxZ() + 1)) {
                    corner.set(cxI.toDouble(), cyI.toDouble(), czI.toDouble())
                    toWorld.transformPosition(corner)
                    if (corner.x < minX) minX = corner.x; if (corner.x > maxX) maxX = corner.x
                    if (corner.y < minY) minY = corner.y; if (corner.y > maxY) maxY = corner.y
                    if (corner.z < minZ) minZ = corner.z; if (corner.z > maxZ) maxZ = corner.z
                }
            }
        }
        // Same inflated ellipsoid the visual ocean uses — the float area is its whole *volume*
        // below the wave plane (matching the deep particle field), not just a slab at the surface.
        val cx = (minX + maxX) * 0.5; val cy = (minY + maxY) * 0.5; val cz = (minZ + maxZ) * 0.5
        val ax = (maxX - minX) * 0.5 * AABB_INFLATE + AABB_MARGIN
        val ay = (maxY - minY) * 0.5 * AABB_INFLATE + AABB_MARGIN
        val az = (maxZ - minZ) * 0.5 * AABB_INFLATE + AABB_MARGIN
        if (ax <= 0.0 || ay <= 0.0 || az <= 0.0) return
        val dyN = (waterLineY - cy) / ay
        if (dyN * dyN >= 1.0) return                          // water line outside the ellipsoid

        // Query the full ellipsoid volume (down to its floor), up past the surface so a fast
        // faller is still caught the tick it crosses the plane.
        val box = AABB(
            cx - ax, cy - ay, cz - az,
            cx + ax, Math.max(maxY, waterLineY) + GerstnerOcean.maxAmplitude + ENTITY_QUERY_PAD,
            cz + az,
        )
        val effect = EKEffects.CREPUSCULAR_FLOATATION.get()
        val toShip = ship.transform.worldToShip
        val local = Vector3d()

        for (entity in level.getEntities(null as Entity?, box) { it.isAlive }) {
            val living = entity as? LivingEntity
            // Intentional flight/riding hard-stops floating at once (never fight creative flight);
            // also drops any lingering grace so it can't keep tugging a now-flying player.
            if ((entity is Player && (entity.isSpectator || entity.abilities.flying)) ||
                (living != null && living.isFallFlying) ||
                entity.isPassenger
            ) {
                living?.removeEffect(effect)
                continue
            }

            val ex = entity.x
            val ey = entity.y
            val ez = entity.z

            // Is the entity *actively* in the virtual ocean this tick? Volume-relative: inside the
            // 3D ellipsoid, below the wave plane, unsupported (`!onGround` — VS2 sets onGround=true
            // for entities standing on ship blocks, so deck-standers/dry-floor entities are
            // excluded), and *not enclosed inside the ship*. Enclosure is judged in the ship's own
            // block space: transform the entity into ship-local coords and check its column — it
            // is enclosed (a dry interior room, don't float) only if sandwiched between ship
            // structure both above and below. Below the keel, above the deck, or outside the
            // footprint are all open water → float. (No geometric world hull cutout — that wrongly
            // blocked under-ship/flooded spaces; no VS2 air-pocket/dragging reliance.)
            val nx = (ex - cx) / ax
            val ny = (ey - cy) / ay
            val nz = (ez - cz) / az
            val insideEllipsoid = nx * nx + ny * ny + nz * nz <= 1.0
            val surfaceY = waterLineY + GerstnerOcean.heightAt(ex, ez, waveTime, waveIntensity)
            val feetY = entity.boundingBox.minY
            val submerged = feetY < surfaceY

            local.set(ex, ey, ez)
            toShip.transformPosition(local)
            val ck = colKey(Math.floor(local.x).toInt(), Math.floor(local.z).toInt())
            val cMax = colMaxY.get(ck)
            val enclosed = cMax != Int.MIN_VALUE && run {              // ship blocks in this column
                val ly = Math.floor(local.y).toInt()
                ly > colMinY.get(ck) && ly < cMax                      // solid above AND below
            }
            val inRegion = !entity.onGround() && insideEllipsoid && submerged && !enclosed

            // Top up the grace timer while actively floating. Living entities only — MobEffects
            // don't apply to items/boats/etc, which just float in-region with no grace.
            if (inRegion && living != null) {
                val cur = living.getEffect(effect)
                if (cur == null || cur.duration < GRACE_REFRESH_BELOW) {
                    living.addEffect(MobEffectInstance(effect, GRACE_TICKS, 0, false, false, true))
                }
            }

            // Float if in the region, or still inside the grace window after leaving it for a
            // gated area — but only ever push while genuinely submerged (never launch something
            // that's above the surface; grace just persists until it dips back or expires).
            val hasGrace = living != null && living.hasEffect(effect)
            if (!submerged || !(inRegion || hasGrace)) continue

            val sub = (surfaceY - feetY).coerceIn(0.0, 1.0)
            val mv = entity.deltaMovement

            if (entity is Player) {
                // Players: don't touch velocity from the server. With the [EntityMixin] /
                // [LocalPlayerMixin] water-fake in place, vanilla water travel + the
                // client-side [LocalPlayerMixin.applySwimThrust] hook own the player's
                // motion. Sending a `ClientboundSetEntityMotionPacket` every tick (which
                // is what the old override path did) snapped the client's smoothly
                // integrated velocity back to the server's stale view each tick — that
                // was the "incredible drag" feel. We still clear fall distance so a
                // long fall into the fluid doesn't deal damage on re-touching ground.
                entity.fallDistance = 0f
                continue
            }

            // Non-player: keep the original gentle bob — damp descent, small submersion
            // lift, light vertical wave bob. Capped low so items/boats can't launch.
            var by = if (mv.y < 0.0) mv.y * SINK_DAMP else mv.y
            by += RISE_PER_SUB * sub
            by += GerstnerOcean.heightAt(ex, ez, waveTime + 1.0, waveIntensity).let { next ->
                (next - GerstnerOcean.heightAt(ex, ez, waveTime, waveIntensity)) * WAVE_BOB
            }
            val vy = by.coerceIn(-MAX_SINK, MAX_RISE)
            val vx = mv.x * HORI_DRAG
            val vz = mv.z * HORI_DRAG

            if (vy == mv.y && vx == mv.x && vz == mv.z) continue   // nothing to apply
            entity.deltaMovement = Vec3(vx, vy, vz)
            entity.fallDistance = 0f
            entity.hurtMarked = true                  // tracker broadcasts the new motion
        }
    }

    /**
     * Per-exterior-block splash. A VS2 ship has no forward direction and is freely rolled, so the
     * trigger is purely world-vertical: transform each exterior block to its current world
     * position and watch its depth below the Gerstner surface. An above→below crossing (tracked
     * per stable ship-local block identity, so ship motion/rotation never aliases it) means that
     * block just pierced the water → splash, scaled by how hard it entered.
     */
    /** Returns the number of surface crossings detected (and thus splash bursts emitted) this
     *  tick. Used for diagnostic logging in [serverTick]. */
    private fun emitSplash(
        level: ServerLevel,
        ship: org.valkyrienskies.core.api.ships.LoadedServerShip,
        waterLineY: Double, waveTime: Double, waveIntensity: Double,
    ): Int {
        val ext = exteriorBlocks
        if (ext.isEmpty()) return 0
        val rng = level.random
        val w = Vector3d()
        val nrm = Vector3d()
        val wv = Vector3d()
        val shipToWorld = ship.transform.shipToWorld
        val posW = ship.transform.positionInWorld
        val vl = ship.velocity
        val om = ship.angularVelocity
        val kOx = blockPos.x; val kOy = blockPos.y; val kOz = blockPos.z
        var emits = 0
        // The Gerstner surface can only deviate from the flat waterline by at most
        // [GerstnerOcean.maxAmplitude] × intensity. Any exterior block whose world Y is more
        // than that (plus a small safety margin) above OR below the waterline can't possibly be
        // mid-crossing this tick, so the precise per-wave [GerstnerOcean.heightAt] (6 sins) is
        // overkill — we use the flat-water depth instead and skip the trig. For a typical hull
        // that's only the deck planks and keel near the water; the vast majority of hull blocks
        // (interior, far above, far below) fall outside the band and short-circuit. Per spark
        // this loop was 7% of the server thread; pre-filtering removes most of that.
        //
        // The previous-depth tracker still gets updated with the flat-water depth on the skip
        // path, so a later tick that re-enters the band sees a coherent `prev` and detects the
        // crossing correctly.
        val band = GerstnerOcean.maxAmplitude * waveIntensity + SPLASH_BAND_SAFETY

        var idx = 0
        while (idx < ext.size && emits < MAX_SPLASH_EMITS) {
            val bx = ext[idx]; val by = ext[idx + 1]; val bz = ext[idx + 2]; idx += 3

            w.set(bx + 0.5, by + 0.5, bz + 0.5)
            shipToWorld.transformPosition(w)
            val key = blockKey(bx - kOx, by - kOy, bz - kOz)
            val flatDepth = waterLineY - w.y           // > 0 → block under flat water

            if (Math.abs(flatDepth) > band) {
                // Outside the wave band — can't be crossing the surface this tick. Stash the
                // flat depth as the next-tick `prev` so a return to the band stays coherent.
                blockPrevDepth.put(key, flatDepth)
                continue
            }

            val surfaceY = waterLineY + GerstnerOcean.heightAt(w.x, w.z, waveTime, waveIntensity)
            val depth = surfaceY - w.y            // > 0 → block under the surface
            val prev = if (blockPrevDepth.containsKey(key)) blockPrevDepth.get(key) else depth
            blockPrevDepth.put(key, depth)

            if (prev <= 0.0 && depth > 0.0) {
                // The collision: how fast the block closed on the surface this tick.
                val plunge = (depth - prev).coerceAtLeast(0.0)

                // Block's own world velocity at this point: v_lin + ω × r.
                // Block's world velocity at this point: v_lin + ω × r. VS gives these in
                // blocks/SECOND; particles (and the Gerstner velocity) are blocks/TICK — convert.
                val relX = w.x - posW.x(); val relY = w.y - posW.y(); val relZ = w.z - posW.z()
                val pvx = (vl.x() + (om.y() * relZ - om.z() * relY)) * SEC_TO_TICK
                val pvy = (vl.y() + (om.z() * relX - om.x() * relZ)) * SEC_TO_TICK
                val pvz = (vl.z() + (om.x() * relY - om.y() * relX)) * SEC_TO_TICK

                // Realistic collision: relative velocity of block vs the moving water, reflected
                // about the surface. Tangential flow is preserved (the sheet runs along the wave),
                // only the closing (normal) part rebounds; the water's own motion carries it. The
                // wave angle only enters through this decomposition, so it no longer dominates.
                GerstnerOcean.normalAt(w.x, w.z, waveTime, waveIntensity, nrm)
                GerstnerOcean.velocityAt(w.x, w.z, waveTime, waveIntensity, wv)
                val rvx = pvx - wv.x; val rvy = pvy - wv.y; val rvz = pvz - wv.z
                val vn = rvx * nrm.x + rvy * nrm.y + rvz * nrm.z   // closing speed (< 0 = into water)
                // splash = water vel + tangential(rel) − R·normal(rel)
                val sX = wv.x + (rvx - vn * nrm.x) - SPLASH_RESTITUTION * vn * nrm.x
                val sY = wv.y + (rvy - vn * nrm.y) - SPLASH_RESTITUTION * vn * nrm.y
                val sZ = wv.z + (rvz - vn * nrm.z) - SPLASH_RESTITUTION * vn * nrm.z

                val nRequested = (1 + (plunge * 4.0).toInt()).coerceAtMost(SPLASH_MAX)
                // Progressive global cap: each crossing gets a share of the world-wide
                // splash bucket. As the bucket fills (lots of ships splashing at once),
                // every crossing's droplet count tapers linearly toward zero — keeps a
                // visible splash on each event rather than nuking later emitters entirely.
                val n = org.shipwrights.enderkinesis.physics.SplashBudget.allocate(nRequested)
                repeat(n) {
                    val velX = sX * SPLASH_GAIN + (rng.nextDouble() - 0.5) * SPLASH_RAND
                    val velY = sY * SPLASH_GAIN + SPLASH_BASE_UP + rng.nextDouble() * SPLASH_RAND
                    val velZ = sZ * SPLASH_GAIN + (rng.nextDouble() - 0.5) * SPLASH_RAND
                    level.sendParticles(
                        EKParticles.splash(),
                        w.x, w.y, w.z,             // the keel/water contact, not the wave crest
                        0, velX, velY, velZ, 1.0,
                    )
                }
                emits++
            }
        }
        return emits
    }

    /**
     * Is the world-space point `(wx, wy, wz)` inside the ship's column footprint? Uses the
     * per-column min/max-Y bookkeeping built during [scanShipBlocks]: the point counts as
     * "inside" when its ship-local Y falls between the lowest and highest solid block of its
     * column. That's a *column-extent* check rather than per-voxel — it culls particles
     * within the hull's external silhouette, including interior cavities, which is what we
     * want for the visual fluid (you shouldn't see the ocean inside the ship's hold). Reuses
     * the caller's [scratch] vector so the per-particle path doesn't allocate.
     */
    private fun insideShipColumn(
        worldToShip: org.joml.Matrix4dc,
        scratch: Vector3d,
        wx: Double, wy: Double, wz: Double,
    ): Boolean {
        scratch.set(wx, wy, wz)
        worldToShip.transformPosition(scratch)
        val lx = Math.floor(scratch.x).toInt()
        val lz = Math.floor(scratch.z).toInt()
        val ck = colKey(lx, lz)
        val cMax = colMaxY.get(ck)
        if (cMax == Int.MIN_VALUE) return false                 // no blocks in this column
        val cMin = colMinY.get(ck)
        val ly = Math.floor(scratch.y).toInt()
        return ly in cMin..cMax
    }

    /** Returns the total particle count this emission sent (surface clusters + deep singles), or
     *  0 if the early-out path was taken. Used for diagnostic logging in [serverTick]. */
    private fun emitOceanParticles(
        level: ServerLevel,
        box: org.joml.primitives.AABBic?,
        shipToWorld: org.joml.Matrix4dc,
        worldToShip: org.joml.Matrix4dc,
        waterLineY: Double,
        densityFactor: Double,
    ): Int {
        if (box == null || densityFactor <= 0.0) return 0

        // World-space AABB of the ship (8 transformed corners of the local AABB).
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        val v = Vector3d()
        for (i in 0 until 8) {
            v.set(
                (if (i and 1 == 0) box.minX() else box.maxX() + 1).toDouble(),
                (if (i and 2 == 0) box.minY() else box.maxY() + 1).toDouble(),
                (if (i and 4 == 0) box.minZ() else box.maxZ() + 1).toDouble(),
            )
            shipToWorld.transformPosition(v)
            if (v.x < minX) minX = v.x; if (v.x > maxX) maxX = v.x
            if (v.y < minY) minY = v.y; if (v.y > maxY) maxY = v.y
            if (v.z < minZ) minZ = v.z; if (v.z > maxZ) maxZ = v.z
        }

        val cx = (minX + maxX) * 0.5
        val cy = (minY + maxY) * 0.5
        val cz = (minZ + maxZ) * 0.5
        val ax = (maxX - minX) * 0.5 * AABB_INFLATE + AABB_MARGIN
        val ay = (maxY - minY) * 0.5 * AABB_INFLATE + AABB_MARGIN
        val az = (maxZ - minZ) * 0.5 * AABB_INFLATE + AABB_MARGIN
        if (ax <= 0.0 || ay <= 0.0 || az <= 0.0) return 0

        // Skip emission unless a player is near the ocean ellipsoid (distance to the ellipsoid,
        // not its centre, so a big ship's far edge isn't wrongly culled).
        val rax = ax + PARTICLE_PLAYER_RANGE
        val ray = ay + PARTICLE_PLAYER_RANGE
        val raz = az + PARTICLE_PLAYER_RANGE
        val seen = level.players().any { p ->
            val dx = (p.x - cx) / rax
            val dyp = (p.y - cy) / ray
            val dz = (p.z - cz) / raz
            dx * dx + dyp * dyp + dz * dz <= 1.0
        }
        if (!seen) return 0

        val dy = (waterLineY - cy) / ay
        if (dy * dy >= 1.0) return 0 // water line above/below the ellipsoid
        val s = Math.sqrt(1.0 - dy * dy)
        val exA = ax * s
        val exZ = az * s

        // Per-emission point count: roughly one per block of ellipse circumference, scaled by
        // power-derived density, bounded above by SURFACE_POINT_CAP so a huge ship (or a ship
        // with a far-out rotated AABB) doesn't dump thousands of particles per tick on clients.
        val count = Math.round((exA + exZ).coerceIn(8.0, SURFACE_POINT_CAP) * densityFactor).toInt()
        if (count <= 0) return 0
        val rng = level.random
        // Retry budget: if a candidate position lands inside the ship's column footprint, we
        // re-roll until we find an outside position (so the user-visible particle count is
        // unaffected by how much of the ellipsoid the ship occupies). Bounded so a fully-
        // enclosed ellipsoid doesn't spin forever.
        val localProbe = Vector3d()
        var emitted = 0
        var attempts = 0
        val attemptCap = count * INSIDE_HULL_RETRY_BUDGET
        while (emitted < count && attempts < attemptCap) {
            attempts++
            val r = Math.pow(rng.nextDouble(), CENTER_BIAS)
            val th = rng.nextDouble() * (2.0 * Math.PI)
            val px = cx + exA * r * Math.cos(th)
            val pz = cz + exZ * r * Math.sin(th)
            if (insideShipColumn(worldToShip, localProbe, px, waterLineY, pz)) continue
            level.sendParticles(
                EKParticles.ocean(),
                px, waterLineY, pz,
                SURFACE_CLUSTER, SURFACE_SPREAD, 0.0, SURFACE_SPREAD, 0.0,
            )
            emitted++
        }

        val deepCount = count / 3
        val yTop = Math.min(waterLineY, cy + ay)
        val yBot = cy - ay
        var deepEmitted = 0
        if (yTop > yBot) {
            var deepAttempts = 0
            val deepAttemptCap = deepCount * INSIDE_HULL_RETRY_BUDGET
            while (deepEmitted < deepCount && deepAttempts < deepAttemptCap) {
                deepAttempts++
                val py = yBot + rng.nextDouble() * (yTop - yBot)
                val q = (py - cy) / ay
                val sy = Math.sqrt((1.0 - q * q).coerceAtLeast(0.0))
                if (sy <= 0.0) continue
                val r = Math.pow(rng.nextDouble(), CENTER_BIAS)
                val th = rng.nextDouble() * (2.0 * Math.PI)
                val px = cx + ax * sy * r * Math.cos(th)
                val pz = cz + az * sy * r * Math.sin(th)
                if (insideShipColumn(worldToShip, localProbe, px, py, pz)) continue
                level.sendParticles(
                    EKParticles.oceanDeep(),
                    px, py, pz,
                    1, 0.0, 0.0, 0.0, 0.0,
                )
                deepEmitted++
            }
        }
        return emitted * SURFACE_CLUSTER + deepEmitted
    }

    private fun scanShipBlocks(level: ServerLevel, box: org.joml.primitives.AABBic?): IntArray? {
        if (box == null) return null
        val volume = (box.maxX() - box.minX() + 1).toLong() *
            (box.maxY() - box.minY() + 1).toLong() *
            (box.maxZ() - box.minZ() + 1).toLong()
        if (volume > MAX_SCAN_VOLUME) {
            LOG.warn("[EK] lattice: ship AABB volume {} exceeds scan cap {}; buoyancy disabled for this ship", volume, MAX_SCAN_VOLUME)
            return IntArray(0)
        }

        val out = ArrayList<Int>()
        val cursor = BlockPos.MutableBlockPos()
        for (x in box.minX()..box.maxX()) {
            for (y in box.minY()..box.maxY()) {
                for (z in box.minZ()..box.maxZ()) {
                    if (!level.getBlockState(cursor.set(x, y, z)).isAir) {
                        out.add(x); out.add(y); out.add(z)
                    }
                }
            }
        }
        return out.toIntArray()
    }

    /** Solid blocks with ≥1 empty 6-neighbour — the only ones that can ever contact the water. */
    private fun computeExterior(scanned: IntArray): IntArray {
        val ox = blockPos.x; val oy = blockPos.y; val oz = blockPos.z
        val solid = LongOpenHashSet(scanned.size / 3)
        var i = 0
        while (i < scanned.size) {
            solid.add(blockKey(scanned[i] - ox, scanned[i + 1] - oy, scanned[i + 2] - oz)); i += 3
        }
        val out = ArrayList<Int>()
        i = 0
        while (i < scanned.size) {
            val x = scanned[i]; val y = scanned[i + 1]; val z = scanned[i + 2]; i += 3
            val dx = x - ox; val dy = y - oy; val dz = z - oz
            if (!solid.contains(blockKey(dx + 1, dy, dz)) ||
                !solid.contains(blockKey(dx - 1, dy, dz)) ||
                !solid.contains(blockKey(dx, dy + 1, dz)) ||
                !solid.contains(blockKey(dx, dy - 1, dz)) ||
                !solid.contains(blockKey(dx, dy, dz + 1)) ||
                !solid.contains(blockKey(dx, dy, dz - 1))
            ) {
                out.add(x); out.add(y); out.add(z)
            }
        }
        return out.toIntArray()
    }

    /** Stable 64-bit id for a ship-local block, relative to the (fixed) lattice position. */
    private fun blockKey(dx: Int, dy: Int, dz: Int): Long {
        val a = (dx + KEY_BIAS).toLong() and KEY_MASK
        val b = (dy + KEY_BIAS).toLong() and KEY_MASK
        val c = (dz + KEY_BIAS).toLong() and KEY_MASK
        return (a shl 42) or (b shl 21) or c
    }

    /** Ship-local (x, z) → 64-bit column key (for [colMinY] / [colMaxY]). */
    private fun colKey(x: Int, z: Int): Long =
        (x.toLong() and 0xffffffffL) or (z.toLong() shl 32)

    fun onRemoved(level: Level, pos: BlockPos) {
        if (level !is ServerLevel) return
        val ship = level.getLoadedShipManagingPos(pos) ?: run {
            LOG.debug("[EK] lattice broken at {} but no ship manages this pos — nothing to tear down", pos)
            return
        }
        val inducer = ship.getAttachment(CrepusculiteLatticeForceInducer::class.java) ?: run {
            LOG.debug("[EK] lattice broken at {} (ship {}) but inducer was never attached", pos, ship.id)
            return
        }
        // Determine whether any OTHER lattice is still alive on the ship. The straightforward
        // proof is "is `pos` the authority and no follower has claimed in the stale window?"
        // — i.e. either we're the only lattice that's ticked recently, or no lattice has.
        //
        // Bug we're closing: previously this only removed the attachment when
        // `inducer.authorityHolder` was us. That left the inducer wedged on the ship in two
        // real cases:
        //   1. The lattice was broken on the very first game tick after chunk load, before it
        //      had a chance to run its `serverTick` and claim authority — the holder ref was
        //      still null, the identity check failed, the inducer stayed attached, and the
        //      ship's physTick kept applying buoyancy at the last-known waterline forever
        //      (with `blocks` empty, no per-block forces, but the geode also never falls).
        //   2. Authority drifted to a follower across a tick boundary, then the *broken*
        //      lattice's reference no longer matched.
        // For a single-lattice ship like a geode either of these manifests as "I broke the
        // lattice and the ship didn't fall." The robust fix is to always drop the attachment
        // here; if a follower remains, its next `serverTick` will `getOrPutAttachment` a fresh
        // inducer and claim authority — at most one game tick of dropped buoyancy.
        val previousAuthority = inducer.authorityHolder
        ship.removeAttachment(CrepusculiteLatticeForceInducer::class.java)
        LOG.debug(
            "[EK] lattice broken at {} (ship {}); inducer released (prev authority = {})",
            pos, ship.id, previousAuthority?.blockPos,
        )
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        seaLevel?.let { tag.putDouble(NBT_SEA_LEVEL, it) }
        seaLevelDim?.let { tag.putString(NBT_SEA_LEVEL_DIM, it) }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        seaLevel = if (tag.contains(NBT_SEA_LEVEL)) tag.getDouble(NBT_SEA_LEVEL) else null
        seaLevelDim = if (tag.contains(NBT_SEA_LEVEL_DIM)) tag.getString(NBT_SEA_LEVEL_DIM) else null
    }

    companion object {
        private val LOG = LogUtils.getLogger()

        private const val NBT_SEA_LEVEL = "EkSeaLevel"
        private const val NBT_SEA_LEVEL_DIM = "EkSeaLevelDim"

        private const val RESCAN_INTERVAL_TICKS = 40
        private const val MAX_SCAN_VOLUME = 2_000_000L

        /** Fixed wave intensity (blocks of crest-to-trough amplitude) in Ygann's Abyss — the
         *  dimension has no terrain to derive intensity from, so the depth-based calc isn't
         *  meaningful here. 0.3 reads as a gentle, ever-present chop instead of either a flat
         *  pond (intensity 0) or full-storm (intensity 1). */
        private const val YGANN_ABYSS_WAVE_INTENSITY = 0.3

        // 21-bit-per-axis packing for stable per-block keys, biased so offsets from the lattice
        // stay non-negative. Safe because the scan volume cap keeps any axis span < 2^21.
        private const val KEY_BIAS = 1 shl 20
        private const val KEY_MASK = (1L shl 21) - 1L
        private const val AABB_INFLATE = 1.4
        private const val AABB_MARGIN = 4.0

        // --- Fall-catcher buoyancy (deliberately gentle) ---
        /** Extra height (blocks) above the ocean added to the entity query box. */
        private const val ENTITY_QUERY_PAD = 2.0
        /** Downward velocity retained per tick while submerged — soft catch, not a wall. */
        private const val SINK_DAMP = 0.78
        /** Upward accel per tick at full submersion (small — beats gravity only just). */
        private const val RISE_PER_SUB = 0.09
        /** Fraction of the per-tick surface delta a floater follows (gentle vertical bob). */
        private const val WAVE_BOB = 0.5
        /** Clamp on rise/sink speed (blocks/tick): low rise so nothing launches; some dive room. */
        private const val MAX_RISE = 0.12
        private const val MAX_SINK = 0.5
        /** Horizontal-velocity retention each tick — light drift drag, no wave advection. */
        private const val HORI_DRAG = 0.95
        /** Grace period (ticks) an entity keeps floating after leaving the ocean region. */
        private const val GRACE_TICKS = 60
        /** Re-apply the grace effect once its remaining duration drops below this (limits spam). */
        private const val GRACE_REFRESH_BELOW = 50

        // --- Server-authoritative virtual-ocean particles ---
        /** Don't emit unless a player is within this range of the ocean ellipsoid. */
        private const val PARTICLE_PLAYER_RANGE = 96.0
        /** Emit cadence (server ticks). Bumped from 3 → 5 to reduce client-side particle load
         *  on larger naturally-spawned ships (roaming end ship etc.). */
        private const val PARTICLE_INTERVAL_TICKS = 5L
        /** Radial exponent for placement: 0.5 = uniform area, higher = denser toward the ship. */
        private const val CENTER_BIAS = 1.1
        /** Surface particles per emission point (one packet) and their spread (blocks).
         *  Cluster size dropped from 4 → 2 — halves surface particles per tick. */
        private const val SURFACE_CLUSTER = 2
        private const val SURFACE_SPREAD = 0.6
        /** Cap on particle "points" sampled along the surface ellipse per emission. Lowered
         *  from 120 → 60: large ships (an end-ship-sized hull is ≈ 40 here) no longer push
         *  hundreds of surface particles each emit. */
        private const val SURFACE_POINT_CAP = 60.0
        /** Retry budget multiplier per particle. When a candidate position lands inside the
         *  ship's column footprint we re-roll until we find an outside slot; the upper bound
         *  on attempts is `target_count × this`. 4 means up to 75% of the ellipsoid can be
         *  inside-hull before we start dropping particles — comfortable margin for any
         *  reasonably-shaped ship. */
        private const val INSIDE_HULL_RETRY_BUDGET = 4

        /** Max splash droplets from one column crossing, and total splash spawns per tick.
         *  Per-tick emit cap dropped 48 → 20 so a bobbing end-ship hull (lots of exterior blocks
         *  crossing the wave plane simultaneously) doesn't dump hundreds of splash particles. */
        private const val SPLASH_MAX = 5
        private const val MAX_SPLASH_EMITS = 20

        /** Safety margin (blocks) added to the wave-amplitude band that [emitSplash] uses to
         *  decide which exterior blocks need the precise (expensive) `heightAt` sample vs which
         *  can short-circuit on flat-water depth. Sized to absorb sub-tick ship motion and one
         *  tick of wave drift; any block more than this far outside the band can't reach the
         *  surface this tick even at full ship velocity. */
        private const val SPLASH_BAND_SAFETY = 2.0

        /** VS world velocities are per-second; particles are per-tick (20 tps). */
        private const val SEC_TO_TICK = 1.0 / 20.0

        /** Splash = reflected relative velocity (block vs moving water) about the surface. */
        private const val SPLASH_RESTITUTION = 1.0   // closing speed bounce (1 = elastic)
        private const val SPLASH_GAIN = 1.0          // overall scale of the ejected velocity
        private const val SPLASH_BASE_UP = 0.08      // small lift so spray clears the surface
        private const val SPLASH_RAND = 0.12
    }
}
