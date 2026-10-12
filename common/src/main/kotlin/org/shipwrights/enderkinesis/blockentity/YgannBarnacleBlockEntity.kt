package org.shipwrights.enderkinesis.blockentity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.mod.common.getShipManagingPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import net.minecraft.core.BlockPos

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

    /** Renderer-side tentacle path cache (client only). Paths are full-length,
     *  geometry-derived; the renderer reveals them by [growthPx]. Refreshed
     *  periodically so hull edits eventually reshape the colony. */
    @Transient var clientPaths: List<Any>? = null
    @Transient var clientMorph: MutableList<Any>? = null
    @Transient var clientPathsBuiltAt: Long = Long.MIN_VALUE
    /** Game time of the last client walk, and the count of waypoints that had no support at
     *  that time. A waypoint at a hull edge can fail the support test from the start. */
    @Transient var clientPathsWalkedAt: Long = 0L
    @Transient var clientUnsupportedBaseline: Int = 0

    // Server thread only.
    @Transient private var serverPaths: List<org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BPath>? = null
    @Transient private var serverPathsBuiltAt: Long = 0L

    fun initBorn(level: Level) {
        if (level.isClientSide) return
        if (bornTime < 0) {
            bornTime = level.gameTime
            seed = Mth.getSeed(blockPos.x, blockPos.y, blockPos.z)
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
        if (level.isClientSide) return
        cachedGrowthFactor = maturity(level.gameTime, 0f)   // drag reads this on the phys thread
        if (level.gameTime % DAMAGE_INTERVAL_TICKS != 0L) return
        val server = level as? net.minecraft.server.level.ServerLevel ?: return
        val growth = growthPx(server.gameTime, 0f)
        if (growth <= MIN_REVEAL_PX) return
        val ship = server.getShipManagingPos(pos)

        // One walk is about 60,000 block reads, so first find a possible victim. A tendril cannot
        // be farther from the barnacle than its revealed arc.
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

        val boxes = org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.collisionBoxes(
            pos, serverPaths(server, pos), growth,
        )
        if (boxes.isEmpty()) return

        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (b in boxes) {
            if (b.minX < minX) minX = b.minX; if (b.maxX > maxX) maxX = b.maxX
            if (b.minY < minY) minY = b.minY; if (b.maxY > maxY) maxY = b.maxY
            if (b.minZ < minZ) minZ = b.minZ; if (b.maxZ > maxZ) maxZ = b.maxZ
        }
        // Query region in WORLD space (boxes are shipyard coords on a ship).
        val worldQuery = if (ship != null) {
            val c = org.joml.Vector3d()
            var wminX = Double.MAX_VALUE; var wminY = Double.MAX_VALUE; var wminZ = Double.MAX_VALUE
            var wmaxX = -Double.MAX_VALUE; var wmaxY = -Double.MAX_VALUE; var wmaxZ = -Double.MAX_VALUE
            for (cx in doubleArrayOf(minX, maxX)) for (cy in doubleArrayOf(minY, maxY)) for (cz in doubleArrayOf(minZ, maxZ)) {
                c.set(cx, cy, cz); ship.shipToWorld.transformPosition(c)
                if (c.x < wminX) wminX = c.x; if (c.x > wmaxX) wmaxX = c.x
                if (c.y < wminY) wminY = c.y; if (c.y > wmaxY) wmaxY = c.y
                if (c.z < wminZ) wminZ = c.z; if (c.z > wmaxZ) wmaxZ = c.z
            }
            net.minecraft.world.phys.AABB(wminX, wminY, wminZ, wmaxX, wmaxY, wmaxZ)
        } else {
            net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ)
        }.inflate(DAMAGE_REACH)

        val src = server.damageSources().cactus()
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
            val entBox = net.minecraft.world.phys.AABB(c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz)
            if (boxes.any { it.inflate(DAMAGE_REACH).intersects(entBox) }) {
                e.hurt(src, DAMAGE_AMOUNT)
            }
        }
    }

    /** The walk result, kept for [SERVER_WALK_REFRESH_TICKS] so a hull edit reshapes the damage
     *  boxes after a short time. The position offset spreads the walks of a colony over ticks. */
    private fun serverPaths(
        server: net.minecraft.server.level.ServerLevel, pos: BlockPos,
    ): List<org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BPath> {
        val now = server.gameTime
        val cached = serverPaths
        val refresh = SERVER_WALK_REFRESH_TICKS + (pos.asLong() and SERVER_WALK_STAGGER_MASK)
        if (cached != null && now - serverPathsBuiltAt in 0 until refresh) return cached
        val facing = blockState.getValue(org.shipwrights.enderkinesis.block.YgannBarnacleBlock.FACING)
        val walked = org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.walk(server, pos, seed, facing)
        serverPaths = walked
        serverPathsBuiltAt = now
        return walked
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
        val r = Vector3d(mx - com.x(), my - com.y(), mz - com.z())
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

    fun ageTicks(now: Long): Long = if (bornTime < 0) 0L else (now - bornTime).coerceAtLeast(0L)

    /** CONTINUOUS growth clock (plane pixels of revealed arc) — fractional, so tips
     *  visibly creep. Measured from each tentacle's own root; branches offset by their
     *  branch-arc, so a limb only sprouts once the parent has grown past it. */
    fun growthPx(now: Long, partial: Float): Double =
        ((ageTicks(now) + partial) * PX_PER_TICK * DEBUG_GROWTH_SPEED).coerceAtMost(MAX_ARC_PX)

    /** Body maturity 0..1 — drives the bulb's size and its knob milestones. */
    fun maturity(now: Long, partial: Float): Double =
        ((ageTicks(now) + partial) * DEBUG_GROWTH_SPEED / FULL_GROWTH_TICKS).coerceIn(0.0, 1.0)

    /** Staged 0..1 size level of the centre node (grows in discrete swelling stages). */
    fun nodeSizeLevel(now: Long, partial: Float): Double = sizeLevelFor(maturity(now, partial))

    fun sizeLevelFor(maturity: Double): Double {
        val m = maturity.coerceIn(0.0, 1.0) * BODY_STAGES
        val stage = Math.floor(m).coerceAtMost(BODY_STAGES - 1.0)
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
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        bornTime = if (tag.contains(NBT_BORN)) tag.getLong(NBT_BORN) else -1L
        seed = tag.getLong(NBT_SEED)
        clientPaths = null
        clientMorph = null
        serverPaths = null
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
        /** Age limit of the server walk cache, plus a per-position offset of 0..63 ticks. */
        const val SERVER_WALK_REFRESH_TICKS = 200L
        const val SERVER_WALK_STAGGER_MASK = 63L
        /** Per-barnacle drag: velocity-decay rate per second at full growth (mass-relative). */
        const val DRAG_RATE = 0.035
        /** DEBUG: growth-rate multiplier; set to 1.0 for shipping cadence. */
        const val DEBUG_GROWTH_SPEED = 1.0
        /** A single tentacle's natural length (px) before it terminates. */
        const val TENTACLE_LEN_PX = 130.0
    }
}
