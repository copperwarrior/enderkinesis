package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.EnchantedBookItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Tome of Disintegration behavior — fires a destructive beam from the SEND orb toward each
 * linked RECEIVE orb. Each beam, per tick:
 *
 *  1. Walks the SEND→RECEIVE ray and **finds the first breakable block**. Accumulates a
 *     per-block mining counter on it; when the counter reaches the block's no-tool destroy
 *     time, the block is destroyed and its drops are queued for dispatch. Like the Create
 *     drill, only the first block in the ray is chewed at a time — once it's gone, the
 *     next tick advances to the next block.
 *  2. Damages every [LivingEntity] whose bounding box clips the unblocked segment of the
 *     beam (from SEND to the first solid block, or all the way to RECV if the path is
 *     clear). Damage is `level.damageSources().magic()` at [DAMAGE_PER_TICK] hp / tick.
 *  3. Vacuums up any [ItemEntity] in the beam's bounding box (mob drops, knocked-out
 *     items, whatever) and queues them for dispatch — so killing a mob in the beam
 *     funnels its loot into the receiver alongside the block drops.
 *
 * The drop queue is then drained by the standard [ItemMoverTomeOrbBehavior] dispatch
 * machinery — same round-robin, in-flight visual flights, bounce-back, item-aware
 * receiver filtering, and filter priority as Transportation / Vacuum. The Disintegration-
 * specific bits are confined to [runBeam] and the queue-backed pull/peek/pushBack hooks.
 *
 * Blocks are mined with a virtual netherite pickaxe, so the loot tables produce the
 * normal "well-tooled" drops (cobblestone from stone, ingots from ores, etc.) rather than
 * the empty-handed no-tool drops.
 */
object DisintegrationTomeOrbBehavior : ItemMoverTomeOrbBehavior() {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_disintegration")

    private val LOG = LogUtils.getLogger()

    /** HP damaged per beam-tick per [LivingEntity] in the unblocked segment. 2.0 = 1 heart
     *  per tick — fast enough that mobs caught in a beam die quickly, slow enough that
     *  walking through briefly is survivable. */
    private const val DAMAGE_PER_TICK: Float = 2.0f

    /** Inflate radius (blocks) for entity-line intersection. Entities within this distance
     *  of the beam line get hit — wider than zero so mobs adjacent to the beam still take
     *  damage instead of needing to be exactly on the line. */
    private const val ENTITY_DAMAGE_RADIUS: Double = 0.5

    /** Inflate radius (blocks) for vacuuming dropped items in the beam AABB. */
    private const val ITEM_VACUUM_RADIUS: Double = 1.0

    /** Cylinder radius around the beam centre line — sets the **drill bit size**, not
     *  the mining depth. Each tick the beam still chews only the leading slice (closest
     *  unmined blocks to SEND); the radius just decides how wide that slice is:
     *    - 0.5 ≈ axis-aligned 1×1 (single column — original single-block drill)
     *    - 1.0 ≈ axis-aligned + cross (5 blocks per slice)
     *    - 1.2 ≈ axis-aligned + cross with corner reach (default)
     *    - 1.5 ≈ axis-aligned 3×3 (9 blocks per slice)
     *    - 2.5 ≈ 5×5
     *  Tune freely; per-tick mining cost scales with the slice size, not beam length. */
    private const val BLOCK_BEAM_RADIUS: Double = 1.2

    /** Thickness (in beam units) of the leading mining slice. 1.0 ≈ one block along the
     *  beam — for axis-aligned beams this is exactly one perpendicular slice of blocks;
     *  for diagonal beams it may capture a few near-leading-edge blocks together, which
     *  is the right behaviour since "slice" is fuzzy off-axis anyway.
     *
     *  Within a slice, blocks are mined **one at a time** in a reverse-spiral order
     *  (outermost ring first, then inward, consistent CCW rotation within each ring). The
     *  drill advances to the next slice once every block in the current slice is gone. */
    private const val SLICE_THICKNESS: Double = 1.0

    /** Taper length (blocks) at each orb. The beam's local radius ramps linearly from
     *  [BEAM_TIP_RADIUS] at the orb to [BLOCK_BEAM_RADIUS] over this distance, giving
     *  the beam a tapered profile (narrow at the tips, full-width in the middle) instead
     *  of a flat cylinder. Blocks right next to either orb still aren't mined unless
     *  they sit within [BEAM_TIP_RADIUS] of the centre line. */
    private const val BEAM_TAPER_BLOCKS: Double = 1.0

    /** Radius (blocks) at the two beam endpoints (the orb tips). The taper interpolates
     *  linearly from this value at the orb to [BLOCK_BEAM_RADIUS] one block in. 0.3 ≈
     *  the orb itself — only a near-line column is eligible right at the tip, opening up
     *  to the full slice further down the beam. */
    private const val BEAM_TIP_RADIUS: Double = 0.3

    /** Ticks-per-hardness scaling for block mining. Hardness 1.5 (stone) → 45 ticks =
     *  ~2.25 s. Matches the order-of-magnitude of vanilla no-tool mining. */
    private const val MINING_TICKS_PER_HARDNESS: Float = 30.0f

    /** Ray-walk sample spacing. 4 samples per block guarantees we hit every voxel the line
     *  passes through without over-counting. */
    private const val RAY_SAMPLES_PER_BLOCK: Double = 4.0

    /** Virtual "tool" passed to [Block.getDrops] so loot tables produce the tooled drops
     *  rather than the empty-handed nothing-from-stone result. A fresh netherite pickaxe
     *  is the universal "tier any block needs" instance. */
    private val VIRTUAL_TOOL: ItemStack = ItemStack(Items.NETHERITE_PICKAXE)

    /** Per-orb state — base mover bookkeeping (cursor + in-flight pending) plus the drop
     *  queue and per-block mining progress that Disintegration needs. */
    class DisintegrationState : MoverState() {
        /** Items waiting to be dispatched. Fed by [runBeam], drained by [pullSourceStack]. */
        val dropQueue: ArrayDeque<ItemStack> = ArrayDeque()
        /** Accumulated mining ticks per block currently being chewed. Cleared on destroy. */
        val mining: MutableMap<BlockPos, Float> = HashMap()
    }

    override fun createState(): Any = DisintegrationState()

    override fun serverTick(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {
        val state = sendBe.tomeState(this) as? DisintegrationState ?: return
        runBeam(level, sendBe, state)
        super.serverTick(level, sendBe)
    }

    // -----------------------------------------------------------------------------------------
    // Beam tick

    private fun runBeam(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, state: DisintegrationState) {
        val sendCentre = sendBe.worldCenter() ?: return
        val sendPos = sendBe.blockPos
        val receivers = sendBe.outgoingPeers(tomeKind)
        if (receivers.isEmpty()) return
        // The orb's enchanted book modulates every per-tick effect — damage bonuses,
        // fire, knockback, looting, mining speed, drop enchants. Empty if no book is
        // bound, in which case everything falls back to the unenchanted defaults.
        val book = sendBe.book
        // SEND-side filter — applied alongside the RECEIVE-side filter
        // for every block mined and every entity damaged. Cached on
        // the BE; re-parsed only when the filter slot changes.
        val sendFilter = sendBe.orbFilter()
        val sendShip = level.getShipManagingPos(sendPos)
        for (receiverPos in receivers) {
            val recvBe = level.getBlockEntity(receiverPos) as? OrbOfLinkingBlockEntity ?: continue
            val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, receiverPos) ?: continue
            val recvShip = level.getShipManagingPos(receiverPos)
            val recvFilter = recvBe.orbFilter()
            // Same-ship case: both orbs on the same body. Iterating the cylinder in WORLD
            // coords would re-rasterise the beam every tick as the ship rotates, drifting
            // which ship-local blocks get hit. Iterating in the ship's local frame keeps
            // the beam's path through ship blocks rotation-invariant — the local beam
            // endpoints are fixed (orb blockPos centres in shipyard coords) regardless of
            // how the ship is oriented in the world. Damage and vacuum still run in world
            // frame because entities and item entities live there.
            val coShip = if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) sendShip else null
            val worldBeamEnd: Vec3
            if (coShip != null) {
                val localFrom = worldToLocal(coShip, sendCentre)
                val localTo = worldToLocal(coShip, recvCentre)
                val localBeamEnd = findBeamEnd(level, localFrom, localTo, sendPos, receiverPos, emptyList())
                mineCylinder(level, state, localFrom, localBeamEnd, sendPos, receiverPos, book, emptyList(),
                    sendFilter, recvFilter)
                worldBeamEnd = localToWorld(coShip, localBeamEnd)
            } else {
                val shipsInRange = shipsOverlappingBeam(level, sendCentre, recvCentre)
                worldBeamEnd = findBeamEnd(level, sendCentre, recvCentre, sendPos, receiverPos, shipsInRange)
                mineCylinder(level, state, sendCentre, worldBeamEnd, sendPos, receiverPos, book, shipsInRange,
                    sendFilter, recvFilter)
            }
            damageEntitiesAlong(level, sendCentre, worldBeamEnd, state, book, sendFilter, recvFilter)
            vacuumDropsInBeam(level, sendCentre, worldBeamEnd, state)
        }

        // Prune mining state for blocks that have been destroyed externally (another beam
        // got there first, or someone broke them by hand). Without this, the map would
        // hold stale BlockPos→ticks entries for blocks that no longer exist.
        if (state.mining.isNotEmpty()) {
            state.mining.entries.removeIf { (pos, _) ->
                val s = level.getBlockState(pos)
                s.isAir || s.getDestroySpeed(level, pos) < 0f
            }
        }
    }

    /** Walk the centre line from [from] to [to] (world coords) looking for the first
     *  unbreakable (hardness < 0) block — the beam is opaque to bedrock-class blocks on
     *  its axis even though the wider mining cylinder will skip-past unbreakable blocks
     *  off-axis. Ship-inclusive: at each centre-line sample we ask [resolveBlockAt] which
     *  block is *effectively* rendered there (ship-preferred, world-fallback). Returns
     *  the centre of the first unbreakable cell, or [to] if the centre line is clear. */
    private fun findBeamEnd(
        level: ServerLevel,
        from: Vec3, to: Vec3,
        skipA: BlockPos, skipB: BlockPos,
        shipsInRange: List<Ship>,
    ): Vec3 {
        val dist = from.distanceTo(to)
        if (dist <= 0.0) return to
        val steps = (dist * RAY_SAMPLES_PER_BLOCK).toInt().coerceAtLeast(1)
        val dx = (to.x - from.x) / steps
        val dy = (to.y - from.y) / steps
        val dz = (to.z - from.z) / steps
        var lastWorldCell: BlockPos? = null
        for (i in 1..steps) {
            val px = from.x + dx * i
            val py = from.y + dy * i
            val pz = from.z + dz * i
            val worldCell = BlockPos.containing(px, py, pz)
            if (worldCell == lastWorldCell) continue
            lastWorldCell = worldCell
            val lookup = resolveBlockAt(level, worldCell.x, worldCell.y, worldCell.z, shipsInRange)
                ?: continue                                    // air at this world cell
            if (lookup.storagePos == skipA || lookup.storagePos == skipB) continue
            if (lookup.hardness < 0f) {
                // Unbreakable on the centre line — beam stops at this world cell.
                return Vec3(worldCell.x + 0.5, worldCell.y + 0.5, worldCell.z + 0.5)
            }
        }
        return to
    }

    /** Wide-drill mining, one block at a time in a reverse-spiral order. Each tick:
     *    1. Scan the cylinder AABB (radius [BLOCK_BEAM_RADIUS] around the SEND→beamEnd
     *       line), collect every (pos, state, hardness, depth, perpendicular distance,
     *       angle around beam axis) for breakable blocks whose centre is within the
     *       cylinder. Off-axis unbreakables are silently skipped — the beam mines
     *       *around* them.
     *    2. Find the leading slice — blocks whose depth is within
     *       [minDepth, minDepth + SLICE_THICKNESS]. This is the drill's working face.
     *    3. Sort the leading slice in **reverse-spiral** order: outermost ring first
     *       (descending perpendicular distance), then counterclockwise around the beam
     *       axis (ascending angle). The drill chews from the outer edge inward, with
     *       consistent rotation within each ring.
     *    4. Mine the *first* block in that sorted order — one tick of progress; destroy
     *       when its `hardness × MINING_TICKS_PER_HARDNESS` counter is met.
     *
     *  When a block is destroyed it drops out of Pass 1's candidate list (Pass 1 skips
     *  air), so the next tick automatically advances to the next block in the spiral.
     *  When every block in the leading slice is gone, the leading slice advances forward
     *  along the beam — Pass 2 naturally finds the next deepest slice as the new leading
     *  edge. The drill marches forward, one block per tick. */
    private fun mineCylinder(
        level: ServerLevel,
        state: DisintegrationState,
        from: Vec3, to: Vec3,
        skipA: BlockPos, skipB: BlockPos,
        book: ItemStack,
        shipsInRange: List<Ship>,
        sendFilter: OrbFilter,
        recvFilter: OrbFilter,
    ) {
        val abx = to.x - from.x
        val aby = to.y - from.y
        val abz = to.z - from.z
        val abLenSq = abx * abx + aby * aby + abz * abz
        if (abLenSq < 1e-12) return                            // degenerate beam
        val abLen = Math.sqrt(abLenSq)
        val radius = BLOCK_BEAM_RADIUS
        val radiusSq = radius * radius

        // Slice-plane 2D basis perpendicular to the beam. Cross-product with a helper
        // vector that's not parallel to the beam; for near-vertical beams we use +X as
        // the helper to avoid degeneracy.
        val dirX = abx / abLen
        val dirY = aby / abLen
        val dirZ = abz / abLen
        val helperX: Double; val helperY: Double; val helperZ: Double
        if (Math.abs(dirY) < 0.9) { helperX = 0.0; helperY = 1.0; helperZ = 0.0 }
        else                      { helperX = 1.0; helperY = 0.0; helperZ = 0.0 }
        // u = normalize(beamDir × helper) — perpendicular to beam.
        var ux = dirY * helperZ - dirZ * helperY
        var uy = dirZ * helperX - dirX * helperZ
        var uz = dirX * helperY - dirY * helperX
        val uLen = Math.sqrt(ux * ux + uy * uy + uz * uz)
        ux /= uLen; uy /= uLen; uz /= uLen
        // v = u × beamDir — perpendicular to both, completes the right-handed slice basis.
        val vx = uy * dirZ - uz * dirY
        val vy = uz * dirX - ux * dirZ
        val vz = ux * dirY - uy * dirX

        val box = AABB(from, to).inflate(radius + 0.5)
        val minX = Math.floor(box.minX).toInt()
        val maxX = Math.floor(box.maxX).toInt()
        val minY = Math.floor(box.minY).toInt()
        val maxY = Math.floor(box.maxY).toInt()
        val minZ = Math.floor(box.minZ).toInt()
        val maxZ = Math.floor(box.maxZ).toInt()

        // Pass 1: collect breakable cylinder-blocks with depth + spiral attrs. Ship-
        // inclusive — for each world cell in the AABB, resolveBlockAt finds the block
        // actually rendered there (ship block via shipToWorld transform if any ship's
        // worldAABB covers the cell, otherwise the world block). The candidate's
        // `pos` is the *storage* coord (shipyard or world) used for getBlockState /
        // getDrops / destroyBlock; the depth and spiral math runs on the world cell
        // centre, so spiral order is consistent across world + ship blocks in the same
        // beam. Dedup by storage pos handles the rare rotated-ship case where multiple
        // world cells project to the same shipyard block.
        val candidates = ArrayList<CylinderCandidate>()
        val seen = HashSet<BlockPos>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val cx = (x + 0.5) - from.x
                    val cy = (y + 0.5) - from.y
                    val cz = (z + 0.5) - from.z
                    val tNorm = (cx * abx + cy * aby + cz * abz) / abLenSq
                    val tClamped = if (tNorm < 0.0) 0.0 else if (tNorm > 1.0) 1.0 else tNorm
                    val perpX = cx - abx * tClamped
                    val perpY = cy - aby * tClamped
                    val perpZ = cz - abz * tClamped
                    val distSq = perpX * perpX + perpY * perpY + perpZ * perpZ
                    // Tapered radius: 0 at either orb, full at one block in, linear
                    // ramp between. Blocks within the taper zone use the local (narrower)
                    // radius; blocks in the middle use the full radius. radiusSq is the
                    // broad-phase cap; the tapered check is the narrow-phase.
                    val depthAlongBeam = tClamped * abLen
                    val localRadius = beamRadiusAt(depthAlongBeam, abLen, radius)
                    val localRadiusSq = localRadius * localRadius
                    if (distSq > localRadiusSq) continue       // outside tapered cylinder
                    if (distSq > radiusSq) continue            // belt-and-braces (≤ above)
                    val lookup = resolveBlockAt(level, x, y, z, shipsInRange) ?: continue
                    if (lookup.storagePos == skipA || lookup.storagePos == skipB) continue
                    if (lookup.hardness < 0f) continue          // off-axis unbreakable: skip
                    if (!seen.add(lookup.storagePos)) continue  // dedup across world cells
                    // SEND + RECEIVE filter gate (both must allow):
                    // - block filter on either side restricts which
                    //   blocks the beam will chew through;
                    // - off-axis blocks excluded by the filter are
                    //   treated like unbreakables — the drill mines
                    //   around them and continues toward the
                    //   receiver instead of stopping cold.
                    if (!sendFilter.matchesBlock(lookup.blockState)) continue
                    if (!recvFilter.matchesBlock(lookup.blockState)) continue
                    val uCoord = perpX * ux + perpY * uy + perpZ * uz
                    val vCoord = perpX * vx + perpY * vy + perpZ * vz
                    candidates.add(CylinderCandidate(
                        lookup.storagePos, lookup.blockState, lookup.hardness,
                        depth = tClamped * abLen,
                        distFromLine = Math.sqrt(distSq),
                        angle = Math.atan2(vCoord, uCoord),
                    ))
                }
            }
        }
        if (candidates.isEmpty()) return

        // Pass 2: leading slice = blocks at min depth ± SLICE_THICKNESS.
        var minDepth = Double.MAX_VALUE
        for (c in candidates) if (c.depth < minDepth) minDepth = c.depth
        val sliceCutoff = minDepth + SLICE_THICKNESS

        // Pass 3: reverse-spiral sort the leading slice, take the first (outermost-CCW)
        // block as this tick's target. The drill chews this one block; once it's gone,
        // next tick the second-outermost in the spiral becomes first.
        val target = candidates
            .filter { it.depth <= sliceCutoff }
            .sortedWith(
                compareByDescending<CylinderCandidate> { it.distFromLine }
                    .thenBy { it.angle }
            )
            .firstOrNull() ?: return

        // Pass 4: mine the target — one tick of progress, destroy when its counter trips.
        // Efficiency speeds up mining: vanilla per-player formula is `level² + 1` added to
        // tool speed (so 1→+2, 2→+5, 3→+10, 4→+17, 5→+26). We divide the base ticks-
        // required by that same factor, which yields the same relative speed-up vs. our
        // baseline (~hardness × 30 ticks at level 0). Block.getDrops sees the same
        // enchanted tool, so Silk Touch and Fortune apply through vanilla loot tables
        // without any extra wiring.
        val enchantedTool = bookAsEnchantedTool(book)
        val efficiencyLevel =
            EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, enchantedTool)
        val efficiencyMultiplier =
            if (efficiencyLevel <= 0) 1.0f
            else 1.0f + (efficiencyLevel * efficiencyLevel + 1).toFloat()
        val ticksRequired = (target.hardness * MINING_TICKS_PER_HARDNESS) / efficiencyMultiplier
        val progressed = state.mining.getOrDefault(target.pos, 0f) + 1f
        if (progressed >= ticksRequired) {
            val drops = Block.getDrops(
                target.blockState, level, target.pos,
                level.getBlockEntity(target.pos), null, enchantedTool,
            )
            state.mining.remove(target.pos)
            level.destroyBlock(target.pos, false)
            for (drop in drops) state.dropQueue.add(drop)
        } else {
            state.mining[target.pos] = progressed
        }
    }

    /** Effective beam radius at a given depth along the SEND→beamEnd line — full
     *  [BLOCK_BEAM_RADIUS] in the middle, linearly ramped from [BEAM_TIP_RADIUS] at each
     *  endpoint over [BEAM_TAPER_BLOCKS]. For beams shorter than `2 × BEAM_TAPER_BLOCKS`,
     *  the two tapers overlap and the radius peaks below full at the midpoint — naturally
     *  so, there isn't room for the full ramp from both ends. */
    private fun beamRadiusAt(depth: Double, beamLength: Double, fullRadius: Double): Double {
        val fromStart = depth
        val fromEnd = beamLength - depth
        val nearer = if (fromStart < fromEnd) fromStart else fromEnd
        val scale = (nearer / BEAM_TAPER_BLOCKS).coerceIn(0.0, 1.0)
        return BEAM_TIP_RADIUS + (fullRadius - BEAM_TIP_RADIUS) * scale
    }

    /** Pass-1 row for [mineCylinder] — a breakable block within the cylinder, with its
     *  cached [BlockState], hardness, depth along the beam from SEND, perpendicular
     *  distance from the beam line, and angle around the beam axis (in the slice plane).
     *  [pos] is the block's *storage* coord — shipyard pos for ship blocks (via VS2's
     *  shipyard chunks), world pos for world blocks. Same coord is what
     *  [Block.getDrops] / [Level.destroyBlock] / [state.mining] all key on. */
    private data class CylinderCandidate(
        val pos: BlockPos,
        val blockState: BlockState,
        val hardness: Float,
        val depth: Double,
        val distFromLine: Double,
        val angle: Double,
    )

    /** Transform a world-frame [Vec3] into a ship's local (shipyard) frame. Used by the
     *  same-ship beam path so iteration runs in coords that don't rotate with the ship. */
    private fun worldToLocal(ship: Ship, world: Vec3): Vec3 {
        val v = Vector3d(world.x, world.y, world.z)
        ship.transform.worldToShip.transformPosition(v)
        return Vec3(v.x, v.y, v.z)
    }

    /** Inverse of [worldToLocal] — ship-local back to world, for hand-off to the
     *  damage / vacuum paths which operate on world-frame entities. */
    private fun localToWorld(ship: Ship, local: Vec3): Vec3 {
        val v = Vector3d(local.x, local.y, local.z)
        ship.transform.shipToWorld.transformPosition(v)
        return Vec3(v.x, v.y, v.z)
    }

    /** Ships whose render volume overlaps the beam's world AABB. The beam's mining /
     *  beam-end logic walks world cells, so any ship-rendered block in the beam needs
     *  to be looked up through the ship's `worldToShip` transform — which means we need
     *  the candidate ship list up front. World AABB is inflated by [BLOCK_BEAM_RADIUS]
     *  + 0.5 to match the broad-phase cell scan. */
    private fun shipsOverlappingBeam(level: ServerLevel, from: Vec3, to: Vec3): List<Ship> {
        val pad = BLOCK_BEAM_RADIUS + 0.5
        val minX = Math.min(from.x, to.x) - pad
        val maxX = Math.max(from.x, to.x) + pad
        val minY = Math.min(from.y, to.y) - pad
        val maxY = Math.max(from.y, to.y) + pad
        val minZ = Math.min(from.z, to.z) - pad
        val maxZ = Math.max(from.z, to.z) + pad
        val result = ArrayList<Ship>()
        for (ship in level.shipObjectWorld.allShips) {
            val sa = ship.worldAABB
            if (sa.maxX() < minX || sa.minX() > maxX) continue
            if (sa.maxY() < minY || sa.minY() > maxY) continue
            if (sa.maxZ() < minZ || sa.minZ() > maxZ) continue
            result.add(ship)
        }
        return result
    }

    /** Find the block effectively rendered at world cell [worldX,worldY,worldZ] —
     *  ship-preferred (ship blocks visually overlay world blocks at the same render
     *  position), world-fallback. Returns the resolved block with its **storage** coord
     *  (shipyard for ship blocks, world for world blocks); the storage coord is the
     *  identity for mining (Block.getDrops / destroyBlock / mining-progress map).
     *
     *  Iteration order through [shipsInRange] is incidental — for a cell covered by
     *  multiple ships' worldAABBs, the first ship whose shipyard cell at the
     *  corresponding position is non-air wins. Returns null when no ship has a block
     *  there and the world block is air. */
    private fun resolveBlockAt(
        level: ServerLevel,
        worldX: Int, worldY: Int, worldZ: Int,
        shipsInRange: List<Ship>,
    ): BlockLookup? {
        val worldCx = worldX + 0.5
        val worldCy = worldY + 0.5
        val worldCz = worldZ + 0.5
        for (ship in shipsInRange) {
            val sa = ship.worldAABB
            if (worldCx < sa.minX() || worldCx > sa.maxX()) continue
            if (worldCy < sa.minY() || worldCy > sa.maxY()) continue
            if (worldCz < sa.minZ() || worldCz > sa.maxZ()) continue
            val shipyard = Vector3d(worldCx, worldCy, worldCz)
            ship.transform.worldToShip.transformPosition(shipyard)
            val shipyardPos = BlockPos.containing(shipyard.x, shipyard.y, shipyard.z)
            val state = level.getBlockState(shipyardPos)
            if (state.isAir) continue                          // ship has open space here
            val hardness = state.getDestroySpeed(level, shipyardPos)
            return BlockLookup(shipyardPos, state, hardness)
        }
        val worldPos = BlockPos(worldX, worldY, worldZ)
        val state = level.getBlockState(worldPos)
        if (state.isAir) return null
        val hardness = state.getDestroySpeed(level, worldPos)
        return BlockLookup(worldPos, state, hardness)
    }

    /** Resolved block at a world cell — its storage [pos] (shipyard or world), state,
     *  and hardness. Returned by [resolveBlockAt]; consumed by [findBeamEnd] for the
     *  bedrock-stop check and by [mineCylinder]'s Pass 1 as a candidate row. */
    private data class BlockLookup(
        val storagePos: BlockPos,
        val blockState: BlockState,
        val hardness: Float,
    )

    /** Damage every living entity within [ENTITY_DAMAGE_RADIUS] of the SEND→beamEnd line.
     *  Enchantment effects pulled from the orb's bound book — promoted into the
     *  `Enchantments` tag of a synthetic stack ([bookAsEnchantedTool]) so vanilla helpers
     *  see them (enchanted books store enchants under `StoredEnchantments`, which the
     *  vanilla [EnchantmentHelper] queries skip):
     *    - **Sharpness / Smite / Bane of Arthropods / Impaling** — combined via
     *      [EnchantmentHelper.getDamageBonus]; returns the highest bonus applicable to
     *      the target's [LivingEntity.getMobType]. Stacked on top of [DAMAGE_PER_TICK].
     *    - **Fire Aspect** — `entity.setSecondsOnFire(level * 4)`, matching vanilla melee.
     *    - **Knockback** — `0.5 × level` velocity away from the beam line, vanilla magnitude.
     *    - **Looting** — set on [DisintegrationLootingOverride] around the [hurt] call so
     *      our mixin into `LootingEnchantFunction.run` injects the level into the death
     *      drops without needing a synthetic killer entity. Exact vanilla parity (uses
     *      vanilla's own per-entry multiplier math). */
    private fun damageEntitiesAlong(
        level: ServerLevel, from: Vec3, to: Vec3,
        state: DisintegrationState, book: ItemStack,
        sendFilter: OrbFilter, recvFilter: OrbFilter,
    ) {
        val box = AABB(from, to).inflate(ENTITY_DAMAGE_RADIUS)
        val damageSource = level.damageSources().magic()
        val enchantedTool = bookAsEnchantedTool(book)
        val fireAspectLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FIRE_ASPECT, enchantedTool)
        val knockbackLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.KNOCKBACK, enchantedTool)
        val lootingLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MOB_LOOTING, enchantedTool)
        val entities = level.getEntities(null as Entity?, box) { it is LivingEntity && it.isAlive }
        for (entity in entities) {
            if (entity !is LivingEntity) continue
            if (!hitsBeamLine(entity.boundingBox, from, to, ENTITY_DAMAGE_RADIUS)) continue
            // SEND + RECEIVE filter gate (both must allow). A filter
            // line passes if any rule matches the entity:
            //  - direct: `minecraft:creeper` / `#minecraft:undead` →
            //    rule names this entity's type / tag.
            //  - drop:   `minecraft:bone` / `#minecraft:wool` →
            //    entity's loot table is known to drop a matching item.
            // Empty filter on either side is a no-op pass.
            if (!sendFilter.matchesEntity(entity, level)) continue
            if (!recvFilter.matchesEntity(entity, level)) continue
            val damageBonus = EnchantmentHelper.getDamageBonus(enchantedTool, entity.mobType)
            val totalDamage = DAMAGE_PER_TICK + damageBonus

            // Wrap hurt() so the looting override is set when vanilla's death drop logic
            // (LivingEntity.die → dropFromLootTable → LootingEnchantFunction.run) runs
            // inside the call stack. The mixin reads the thread-local override and
            // applies it in place of the killer-entity-based lookup that fails for our
            // entity-less beam. Try/finally ensures we don't leak the override into
            // unrelated loot rolls on the same thread.
            DisintegrationLootingOverride.set(lootingLevel)
            val hit: Boolean = try {
                entity.hurt(damageSource, totalDamage)
            } finally {
                DisintegrationLootingOverride.clear()
            }
            if (!hit) continue                                  // i-frames, immune, etc.
            if (fireAspectLevel > 0) entity.setSecondsOnFire(fireAspectLevel * 4)
            if (knockbackLevel > 0) applyKnockback(entity, from, to, knockbackLevel)
        }
    }

    /** Promote the book's `StoredEnchantments` onto a synthetic tool's active
     *  `Enchantments` tag so the vanilla [EnchantmentHelper] queries find them. Enchanted
     *  books keep their enchants under `StoredEnchantments` — vanilla helpers query the
     *  active `Enchantments` tag, so reading enchants directly from a book always returns
     *  level 0. This re-shapes the book into something the vanilla code paths recognise. */
    private fun bookAsEnchantedTool(book: ItemStack): ItemStack {
        if (book.isEmpty) return VIRTUAL_TOOL
        val tool = VIRTUAL_TOOL.copy()
        val stored = EnchantedBookItem.getEnchantments(book)
        if (!stored.isEmpty) tool.orCreateTag.put("Enchantments", stored.copy())
        return tool
    }

    /** Push [entity] horizontally away from the beam line by a vanilla-equivalent knockback
     *  velocity (0.5 × level, applied via [LivingEntity.knockback]). */
    private fun applyKnockback(entity: LivingEntity, from: Vec3, to: Vec3, level: Int) {
        // Closest point on the beam segment to the entity's position.
        val abx = to.x - from.x
        val aby = to.y - from.y
        val abz = to.z - from.z
        val abLenSq = abx * abx + aby * aby + abz * abz
        if (abLenSq < 1e-12) return
        val ex = entity.x - from.x
        val ey = entity.y - from.y
        val ez = entity.z - from.z
        val t = ((ex * abx + ey * aby + ez * abz) / abLenSq).coerceIn(0.0, 1.0)
        val cx = from.x + abx * t
        val cz = from.z + abz * t
        // Horizontal away-vector from the beam line to the entity.
        var dx = entity.x - cx
        var dz = entity.z - cz
        val horizLen = Math.sqrt(dx * dx + dz * dz)
        if (horizLen < 1e-6) {
            // Entity is exactly on the line — pick an arbitrary direction (+X) so we still
            // produce knockback rather than dividing by zero.
            dx = 1.0; dz = 0.0
        } else {
            dx /= horizLen; dz /= horizLen
        }
        // Vanilla LivingEntity.knockback takes (strength, dx, dz) where the *kick* is in
        // the opposite direction to (dx, dz). Negate so the entity moves *away* from the
        // beam, not toward it.
        entity.knockback(0.5 * level, -dx, -dz)
    }


    private fun vacuumDropsInBeam(
        level: ServerLevel, from: Vec3, to: Vec3,
        state: DisintegrationState,
    ) {
        val box = AABB(from, to).inflate(ITEM_VACUUM_RADIUS)
        val items = level.getEntitiesOfClass(ItemEntity::class.java, box) { entity ->
            entity.isAlive && !entity.hasPickUpDelay() && !entity.item.isEmpty
        }
        for (item in items) {
            if (!hitsBeamLine(item.boundingBox, from, to, ITEM_VACUUM_RADIUS)) continue
            state.dropQueue.add(item.item.copy())
            item.discard()
        }
    }

    /** True iff the line segment [from]–[to] passes within [radius] of [box]. The AABB
     *  query from [Level.getEntities] is a *broad-phase* axis-aligned bounding-box test —
     *  for a diagonal beam between arbitrarily-oriented orbs, that bounding box is much
     *  larger than the actual cylindrical beam zone (a 64-block diagonal beam has a
     *  ~262 000 m³ AABB but the real cylinder is ~200 m³). This narrow-phase check
     *  inflates the candidate's bbox by [radius] and asks "does the beam line clip it" —
     *  i.e. *is this entity actually within `radius` of the beam line*, not just within
     *  the line's bounding box. Without this, entities at the AABB corners far from the
     *  beam would be hit/vacuumed even though the beam doesn't visibly pass through them. */
    private fun hitsBeamLine(box: AABB, from: Vec3, to: Vec3, radius: Double): Boolean =
        box.inflate(radius).clip(from, to).isPresent

    // -----------------------------------------------------------------------------------------
    // ItemMover hooks — queue-backed source

    override fun pullSourceStack(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity): ItemStack? {
        val state = sendBe.tomeState(this) as? DisintegrationState ?: return null
        val head = state.dropQueue.firstOrNull() ?: return null
        val one = head.split(1)
        if (head.isEmpty) state.dropQueue.removeFirst()
        return one
    }

    override fun peekSourceStack(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity): ItemStack? {
        val state = sendBe.tomeState(this) as? DisintegrationState ?: return null
        val head = state.dropQueue.firstOrNull() ?: return null
        return head.copy().also { it.count = 1 }
    }

    /** Bounce-back lands at the head of the queue, not in a SEND-side container — the
     *  disintegration tome has no container concept on the SEND side. Re-dispatch picks
     *  it up next tick. */
    override fun pushBackToSource(
        level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, stack: ItemStack,
    ): ItemStack {
        val state = sendBe.tomeState(this) as? DisintegrationState ?: return stack
        state.dropQueue.addFirst(stack.copy())
        return ItemStack.EMPTY
    }

    /** Standard item-aware receiver check, mirroring Transportation: container at the
     *  receiver's active face, room for *this* item, and the RECEIVE-orb filter (if any)
     *  must match. */
    override fun canReceiverAccept(
        level: ServerLevel, recvBe: OrbOfLinkingBlockEntity, stack: ItemStack,
    ): Boolean {
        if (!matchesFilter(recvBe.filter, stack)) return false
        val recvFacing = recvBe.facing
        val destPos = recvBe.blockPos.relative(recvFacing)
        val dest: Container = resolveContainer(level, destPos) ?: return false
        return hasSpaceFor(dest, recvFacing.opposite, stack)
    }
}
