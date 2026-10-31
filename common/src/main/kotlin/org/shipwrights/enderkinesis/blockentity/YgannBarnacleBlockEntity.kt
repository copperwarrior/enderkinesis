package org.shipwrights.enderkinesis.blockentity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.mod.common.getShipManagingPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.damagesource.DamageType
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleLimbLimits
import org.shipwrights.enderkinesis.dimension.YgannBarnacleSpreader

/**
 * Growth state for the Ygann Barnacle: birth time + deterministic seed. Age derives from
 * gameTime, and the tentacle layout is a pure function of (seed, hull geometry). The server
 * ticker ([tick]) gives tendril damage; the renderer draws the same layout.
 */
@OptIn(PhysTickOnly::class, VsBeta::class)
class YgannBarnacleBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.YGANN_BARNACLE.get(), pos, state),
    BlockEntityPhysicsListener {

    @Volatile override lateinit var dimension: DimensionId
    /** Growth (maturity 0..1) cached on the game tick for the phys thread's drag. */
    @Volatile private var cachedGrowthFactor: Double = 0.0

    private var bornTime: Long = -1L
    var seed: Long = 0L
        private set

    // Player cuts, by limb id. Synced to clients.
    private val cuts = YgannBarnacleLimbLimits(YgannBarnacleGeometry.MAX_TENTACLES, PX_PER_TICK)
    private var flinchTime: Long = NO_TIME
    /** The id of the limb that flinches (with its branches), or 0 when the whole colony flinches. */
    var flinchLimb: Int = 0
        private set
    /** Where (path-local px) [flinchLimb] was cut; the flinch ripples from there to the core. */
    var flinchArc: Double = 0.0
        private set
    // Limbs that a hull change made longer creep into the new length. Each side keeps its own; not synced.
    @Transient private val hullCreep = YgannBarnacleLimbLimits(YgannBarnacleGeometry.MAX_TENTACLES, PX_PER_TICK)

    // Client only: the renderer's walk, its morphing limbs, and the time of the last hull check.
    @Transient var clientWalk: YgannBarnacleGeometry.Walk? = null
    /** A walk that the client's walk queue finished; the renderer adopts it on its next frame. */
    @Transient var clientPendingWalk: YgannBarnacleGeometry.Walk? = null
    @Transient var clientWalkQueued: Boolean = false
    /** Block-local bounds of everything the renderer draws, for culling and limb picks; null before a walk. */
    @Transient var clientBounds: net.minecraft.world.phys.AABB? = null
    @Transient var clientMorph: MutableList<Any>? = null
    @Transient var clientHullCheckedAt: Long = Long.MIN_VALUE
    /** Increments when a sync brings new cuts, so the renderer can dissolve the cut-off parts. */
    @Transient var clientCutsVersion: Int = 0
    @Transient var clientCutsSeen: Int = 0
    /** The renderer's limb reveals from its last frame, and that frame's game time. */
    @Transient val clientLastBase = DoubleArray(YgannBarnacleGeometry.MAX_TENTACLES)
    @Transient var clientLastBaseAt: Long = Long.MIN_VALUE
    @Transient val clientScratch = DoubleArray(YgannBarnacleGeometry.MAX_TENTACLES)
    @Transient val clientOldScratch = DoubleArray(YgannBarnacleGeometry.MAX_TENTACLES)
    @Transient val clientReveals = DoubleArray(YgannBarnacleGeometry.MAX_TENTACLES)

    // Server thread only.
    @Transient private var serverWalk: YgannBarnacleGeometry.Walk? = null
    @Transient private var serverHullCheckedAt: Long = 0L
    @Transient private val serverReveals = DoubleArray(YgannBarnacleGeometry.MAX_TENTACLES)
    @Transient private val serverOldReveals = DoubleArray(YgannBarnacleGeometry.MAX_TENTACLES)
    @Transient private var lastCutTime: Long = NO_TIME
    @Transient private val serverBounds = DoubleArray(6)
    // Physics thread only; the force and position vectors stay fresh, since VS2 keeps those references.
    @Transient private val physR = Vector3d()
    /** The body stage at the last tick, or -1 before the first tick after a load. */
    @Transient private var lastStage: Int = -1

    fun initBorn(level: Level) {
        if (level.isClientSide) return
        if (bornTime < 0) {
            bornTime = level.gameTime
            seed = level.random.nextLong()
            setChanged()
            level.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
    }

    /** Server ticker: barnacle tendrils are a HAZARD — living entities intersecting a
     *  revealed tendril box take periodic damage (sharp encrusted spines). Works on
     *  ships too: the tendril boxes are in the barnacle's own (shipyard) frame, so
     *  entities are transformed into that frame before the box test. */
    fun tick(level: Level, pos: BlockPos) {
        if (bornTime < 0) {
            if (!level.isClientSide) initBorn(level)
            return
        }
        if (level.isClientSide) {
            if (level.gameTime % PULSE_PERIOD_TICKS == 0L) {
                org.shipwrights.enderkinesis.client.YgannBarnacleClientSounds.pulse(level, pos)
            }
            return
        }
        cachedGrowthFactor = maturity(level.gameTime, 0f)   // drag reads this on the phys thread
        val server = level as? net.minecraft.server.level.ServerLevel ?: return
        playSwell(server, pos)
        if (cachedGrowthFactor >= 1.0) YgannBarnacleSpreader.tickMature(server, pos)
        if (level.gameTime % DAMAGE_INTERVAL_TICKS != 0L) return
        val growth = growthPx(server.gameTime, 0f)
        if (growth <= MIN_REVEAL_PX) return
        val ship = server.getShipManagingPos(pos)
        // Keep the walk current without a victim too, so a hull change starts the creep on time.
        val paths = serverPaths(server, pos)

        // A tendril cannot be farther from the barnacle than its revealed arc.
        val centre = org.joml.Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        ship?.shipToWorld?.transformPosition(centre)
        val reach = growth / PX_PER_BLOCK + 1.0
        val nearby = server.getEntitiesOfClass(
            net.minecraft.world.entity.LivingEntity::class.java,
            net.minecraft.world.phys.AABB(
                centre.x - reach, centre.y - reach, centre.z - reach,
                centre.x + reach, centre.y + reach, centre.z + reach,
            ),
        )
        if (nearby.isEmpty()) return

        limbReveals(paths, server.gameTime, 0f, serverReveals)
        val bounds = serverBounds
        if (!YgannBarnacleGeometry.limbBoxBounds(pos, paths, serverReveals, bounds)) return
        val minX = bounds[0]; val minY = bounds[1]; val minZ = bounds[2]
        val maxX = bounds[3]; val maxY = bounds[4]; val maxZ = bounds[5]
        // Query region in WORLD space (boxes are shipyard coords on a ship).
        val worldQuery = if (ship != null) {
            val c = org.joml.Vector3d()
            var wminX = Double.MAX_VALUE; var wminY = Double.MAX_VALUE; var wminZ = Double.MAX_VALUE
            var wmaxX = -Double.MAX_VALUE; var wmaxY = -Double.MAX_VALUE; var wmaxZ = -Double.MAX_VALUE
            for (corner in 0 until 8) {
                c.set(
                    if ((corner and 1) == 0) minX else maxX,
                    if ((corner and 2) == 0) minY else maxY,
                    if ((corner and 4) == 0) minZ else maxZ,
                )
                ship.shipToWorld.transformPosition(c)
                if (c.x < wminX) wminX = c.x; if (c.x > wmaxX) wmaxX = c.x
                if (c.y < wminY) wminY = c.y; if (c.y > wmaxY) wmaxY = c.y
                if (c.z < wminZ) wminZ = c.z; if (c.z > wmaxZ) wmaxZ = c.z
            }
            net.minecraft.world.phys.AABB(wminX, wminY, wminZ, wmaxX, wmaxY, wmaxZ)
        } else {
            net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ)
        }.inflate(DAMAGE_REACH)

        val src = server.damageSources().source(DAMAGE_TYPE)
        val c = org.joml.Vector3d()
        for (e in nearby) {
            if (!worldQuery.intersects(e.boundingBox)) continue
            // Test the entity's whole body against the (thin, surface-hugging) tendril
            // boxes — a single centre point sits a block above a floor tendril and
            // always misses. The box keeps its own size around its centre in the barnacle's
            // (shipyard) frame: the enclosing box of the rotated corners is too large on a
            // rotated ship.
            val bb = e.boundingBox
            c.set((bb.minX + bb.maxX) * 0.5, (bb.minY + bb.maxY) * 0.5, (bb.minZ + bb.maxZ) * 0.5)
            ship?.worldToShip?.transformPosition(c)
            val hx = bb.xsize * 0.5; val hy = bb.ysize * 0.5; val hz = bb.zsize * 0.5
            val hit = YgannBarnacleGeometry.limbBoxesHit(
                pos, paths, serverReveals, DAMAGE_REACH, c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz,
            )
            if (hit && e.hurt(src, DAMAGE_AMOUNT)) {
                server.playSound(null, e.x, e.y, e.z, EKSounds.YGANN_BARNACLE_BURN.get(), SoundSource.BLOCKS, 1f, 1f)
            }
        }
    }

    private fun playSwell(level: net.minecraft.server.level.ServerLevel, pos: BlockPos) {
        val stage = bodyStage(maturity(level.gameTime, 0f))
        if (lastStage >= 0 && stage != lastStage) {
            level.playSound(null, pos, EKSounds.YGANN_BARNACLE_SWELL.get(), SoundSource.BLOCKS, 1f, 1f)
        }
        lastStage = stage
    }

    /** The walk result. Every [SERVER_WALK_REFRESH_TICKS] it checks the hull cells that the walk
     *  read, and walks again only if one changed. The position offset spreads a colony's checks. */
    fun serverPaths(
        server: net.minecraft.server.level.ServerLevel, pos: BlockPos,
    ): List<YgannBarnacleGeometry.BPath> {
        val now = server.gameTime
        val cached = serverWalk
        val refresh = SERVER_WALK_REFRESH_TICKS + (pos.asLong() and SERVER_WALK_STAGGER_MASK)
        if (cached != null && now - serverHullCheckedAt in 0 until refresh) return cached.paths
        serverHullCheckedAt = now
        if (cached != null && !cached.hull.changed(server)) return cached.paths
        val facing = blockState.getValue(org.shipwrights.enderkinesis.block.YgannBarnacleBlock.FACING)
        val walked = YgannBarnacleGeometry.walk(server, pos, seed, facing)
        if (adoptWalk(cached, walked, now, serverOldReveals, serverReveals)) sync(server)
        serverWalk = walked
        return walked.paths
    }

    /** Moves from [old] to [new] after a hull change: a limb that the change made longer creeps into
     *  its new length from its old one. Fills [oldOut] and [newOut] with the reveals of
     *  the old and new paths. True when a cut was removed because its limb is gone. */
    fun adoptWalk(
        old: YgannBarnacleGeometry.Walk?, new: YgannBarnacleGeometry.Walk, now: Long,
        oldOut: DoubleArray, newOut: DoubleArray,
    ): Boolean {
        if (old != null) {
            limbReveals(old.paths, now, 0f, oldOut)
            limbReveals(new.paths, now, 0f, newOut)
            for (j in new.paths.indices) {
                val id = new.paths[j].id
                val i = old.paths.indexOfFirst { it.id == id }
                val before = if (i < 0) 0.0 else Math.max(0.0, oldOut[i])
                if (newOut[j] > before + CREEP_MIN_PX) hullCreep.set(id, before, now)
            }
        }
        hullCreep.retain(new.paths)
        val pruned = cuts.retain(new.paths)
        limbReveals(new.paths, now, 0f, newOut)
        return pruned
    }

    /** Positional drag: a force opposing the water flow the barnacle sees, applied at
     *  the barnacle's own mount point (so it bleeds the ship's drift AND resists spin),
     *  scaled by growth. Mass-relative — like the mod's other velocity dampers — so the
     *  per-barnacle deceleration rate is the same regardless of ship size. */
    override fun physTick(physShip: PhysShip?, physLevel: PhysLevel) {
        val ship = physShip ?: return
        val growth = cachedGrowthFactor
        if (growth <= 0.0 || ship.isStatic || ship.mass <= 0.0) return
        val v = ship.velocity
        val w = ship.angularVelocity
        val com = ship.transform.positionInShip
        val mx = blockPos.x + 0.5; val my = blockPos.y + 0.5; val mz = blockPos.z + 0.5
        // Velocity at the mount point in world frame: v_com + ω × r_world.
        val r = physR.set(mx - com.x(), my - com.y(), mz - com.z())
        ship.transform.shipToWorldRotation.transform(r)
        val vpX = v.x() + w.y() * r.z - w.z() * r.y
        val vpY = v.y() + w.z() * r.x - w.x() * r.z
        val vpZ = v.z() + w.x() * r.y - w.y() * r.x
        val k = -DRAG_RATE * growth * ship.mass
        ship.applyWorldForceToModelPos(
            Vector3d(vpX * k, vpY * k, vpZ * k),
            Vector3d(mx, my, mz),
        )
    }

    /** The revealed arc (path-local px) of each limb, into [out]: the growth clock, less a cut or a
     *  hull-change creep that is still growing. A branch never reaches past its parent's tip, so a cut
     *  parent hides its branches until it grows back past them. */
    fun limbReveals(paths: List<YgannBarnacleGeometry.BPath>, now: Long, partial: Float, out: DoubleArray) {
        require(out.size >= paths.size) { "reveal array too small" }
        val growth = growthPx(now, partial)
        val t = now + partial.toDouble()
        for (i in paths.indices) {
            val path = paths[i]
            var r = Math.min(growth - path.startArc, path.totalPx)
            r = Math.min(r, cuts.limit(path.id, t))
            r = Math.min(r, hullCreep.limit(path.id, t))
            if (path.parent in 0 until i) r = Math.min(r, out[path.parent] - path.parentArc)
            out[i] = r
        }
    }

    /** Ticks since the flinch started at time [t], or -1 when no flinch is in progress. */
    fun flinchAge(t: Double): Double {
        if (flinchTime == NO_TIME) return -1.0
        val age = t - flinchTime
        val length = if (flinchLimb == 0) FLINCH_TICKS else RIPPLE_TICKS
        return if (age < 0.0 || age >= length) -1.0 else age
    }

    /** Server: a player hit this barnacle's core. */
    fun flinch(level: Level) {
        if (level.isClientSide) return
        val now = level.gameTime
        if (flinchTime != NO_TIME && now - flinchTime in 0 until FLINCH_RETRIGGER_TICKS) return
        flinchTime = now
        flinchLimb = 0
        flinchArc = 0.0
        sync(level)
        level.playSound(null, blockPos, EKSounds.YGANN_BARNACLE_FLINCH.get(), SoundSource.BLOCKS, 1f, 1f)
    }

    /** Server: cut limb [pathIndex] at [arcPx]. False when the limb has no growth there. */
    fun cutLimb(level: net.minecraft.server.level.ServerLevel, pathIndex: Int, arcPx: Double): Boolean {
        val now = level.gameTime
        if (lastCutTime != NO_TIME && now - lastCutTime in 0 until CUT_COOLDOWN_TICKS) return false
        val paths = serverPaths(level, blockPos)
        if (pathIndex !in paths.indices) return false
        limbReveals(paths, now, 0f, serverReveals)
        if (arcPx < 0.0 || arcPx >= serverReveals[pathIndex]) return false
        cuts.set(paths[pathIndex].id, arcPx, now)
        lastCutTime = now
        flinchTime = now
        flinchLimb = paths[pathIndex].id
        flinchArc = arcPx
        sync(level)
        return true
    }

    /** The death notice's limb reveals (server). */
    fun deathReveals(level: net.minecraft.server.level.ServerLevel): DoubleArray {
        val paths = serverPaths(level, blockPos)
        val out = DoubleArray(paths.size)
        limbReveals(paths, level.gameTime, 0f, out)
        return out
    }

    private fun sync(level: Level) {
        setChanged()
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    fun ageTicks(now: Long): Long = if (bornTime < 0) 0L else (now - bornTime).coerceAtLeast(0L)

    /** CONTINUOUS growth clock (plane pixels of revealed arc) — fractional, so tips
     *  visibly creep. Measured from each tentacle's own root; branches offset by their
     *  branch-arc, so a limb only sprouts once the parent has grown past it. */
    fun growthPx(now: Long, partial: Float): Double =
        ((ageTicks(now) + partial) * PX_PER_TICK).coerceAtMost(MAX_ARC_PX)

    /** Body maturity 0..1 — drives the bulb's size and its knob milestones. */
    fun maturity(now: Long, partial: Float): Double =
        ((ageTicks(now) + partial) / FULL_GROWTH_TICKS).coerceIn(0.0, 1.0)

    /** Staged 0..1 size level of the centre node (grows in discrete swelling stages). */
    fun nodeSizeLevel(now: Long, partial: Float): Double = sizeLevelFor(maturity(now, partial))

    private fun bodyStage(maturity: Double): Int =
        Math.floor(maturity.coerceIn(0.0, 1.0) * BODY_STAGES).coerceAtMost(BODY_STAGES - 1.0).toInt()

    fun sizeLevelFor(maturity: Double): Double {
        val m = maturity.coerceIn(0.0, 1.0) * BODY_STAGES
        val stage = bodyStage(maturity).toDouble()
        val within = smoothStep(((m - stage) / STAGE_SWELL_FRACTION).coerceIn(0.0, 1.0))
        return ((stage + within) / BODY_STAGES).coerceIn(0.0, 1.0)
    }

    /** Current centre-node half-width and height (pixels) — the render bulb AND the
     *  block outline shape both scale from these. */
    fun nodeHalfWidthPx(now: Long, partial: Float): Double =
        BODY_MIN_R_PX + (BODY_MAX_R_PX - BODY_MIN_R_PX) * nodeSizeLevel(now, partial)
    fun nodeHeightPx(now: Long, partial: Float): Double =
        BODY_MIN_H_PX + (BODY_MAX_H_PX - BODY_MIN_H_PX) * nodeSizeLevel(now, partial)

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putLong(NBT_BORN, bornTime)
        tag.putLong(NBT_SEED, seed)
        cuts.save(tag, NBT_CUT)
        tag.putLong(NBT_FLINCH, flinchTime)
        tag.putInt(NBT_FLINCH_LIMB, flinchLimb)
        tag.putDouble(NBT_FLINCH_ARC, flinchArc)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        bornTime = if (tag.contains(NBT_BORN)) tag.getLong(NBT_BORN) else -1L
        val newSeed = tag.getLong(NBT_SEED)
        // A cut or flinch sync keeps the seed; only a new seed needs a new walk.
        if (newSeed != seed) {
            seed = newSeed
            clientWalk = null
            clientPendingWalk = null
            clientBounds = null
            clientMorph = null
            serverWalk = null
            hullCreep.clear()
        }
        if (cuts.load(tag, NBT_CUT)) clientCutsVersion++
        flinchTime = if (tag.contains(NBT_FLINCH)) tag.getLong(NBT_FLINCH) else NO_TIME
        flinchLimb = tag.getInt(NBT_FLINCH_LIMB)
        flinchArc = tag.getDouble(NBT_FLINCH_ARC)
    }

    override fun setRemoved() {
        super.setRemoved()
        val l = level
        if (l != null && l.isClientSide) {
            val walk = clientPendingWalk ?: clientWalk
            if (walk != null && !clientWalkQueued) {
                org.shipwrights.enderkinesis.client.YgannBarnacleDeathRenderer.rememberWalk(
                    blockPos, seed, walk, if (clientPendingWalk != null) l.gameTime else clientHullCheckedAt, l.gameTime,
                )
            }
        }
    }

    override fun getUpdateTag(): CompoundTag {
        // Backstop for placement paths where onPlace ran before the BE existed.
        level?.let { if (!it.isClientSide && bornTime < 0) initBorn(it) }
        return saveWithoutMetadata()
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)

    companion object {
        private const val NBT_BORN = "EkBorn"
        private const val NBT_SEED = "EkSeed"
        private const val NBT_CUT = "EkCut"
        private const val NBT_FLINCH = "EkFlinch"
        private const val NBT_FLINCH_LIMB = "EkFlinchLimb"
        private const val NBT_FLINCH_ARC = "EkFlinchArc"
        private const val NO_TIME = Long.MIN_VALUE
        /** Flinch: full length (ticks), and the shortest gap between flinches. */
        const val FLINCH_TICKS = 40.0
        /** A cut's ripple: long enough to run from the farthest tip (about 390 px) to the core. */
        const val RIPPLE_TICKS = 70.0
        const val FLINCH_RETRIGGER_TICKS = 10L
        /** The light pulse leaves the core once in each period. */
        const val PULSE_PERIOD_TICKS = 40L
        /** Shortest gap (ticks) between two cuts on one barnacle. */
        const val CUT_COOLDOWN_TICKS = 4L
        /** A hull change must lengthen a limb by more than this (px) to start a creep. */
        const val CREEP_MIN_PX = 0.5
        /** Centre node: discrete size stages, swell fraction per stage, and the min→max
         *  half-width/height (pixels). Shared by the renderer bulb + block shape. */
        const val BODY_STAGES = 4.0
        const val STAGE_SWELL_FRACTION = 0.55
        const val BODY_MIN_R_PX = 2.0
        const val BODY_MAX_R_PX = 6.5
        const val BODY_MIN_H_PX = 2.5
        const val BODY_MAX_H_PX = 7.0
        private fun smoothStep(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }

        /** Full colony (mature bulb + tentacles fully creeping + branches) after
         *  ~13 minutes. MAX_ARC_PX is the growth clock's reach — long enough to reveal
         *  the deepest branch. */
        const val FULL_GROWTH_TICKS = 16000.0
        const val MAX_ARC_PX = 520.0
        const val PX_PER_TICK = MAX_ARC_PX / FULL_GROWTH_TICKS
        /** Tendril damage cadence (ticks), reach past the box surface (blocks), and
         *  the bite per hit (half-hearts). */
        const val DAMAGE_INTERVAL_TICKS = 10L
        const val DAMAGE_REACH = 0.15
        const val DAMAGE_AMOUNT = 2.0f
        /** Arc pixels in one block, and the arc below which no damage box is revealed. */
        const val PX_PER_BLOCK = 16.0
        const val MIN_REVEAL_PX = 1.0
        /** Interval of the server hull check, plus a per-position offset of 0..15 ticks. */
        const val SERVER_WALK_REFRESH_TICKS = 20L
        const val SERVER_WALK_STAGGER_MASK = 15L
        /** Per-barnacle drag: velocity-decay rate per second at full growth (mass-relative). */
        const val DRAG_RATE = 0.035
        val DAMAGE_TYPE: ResourceKey<DamageType> =
            ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("ygann_barnacle"))
    }
}
