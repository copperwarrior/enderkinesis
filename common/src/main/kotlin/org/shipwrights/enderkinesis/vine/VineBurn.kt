package org.shipwrights.enderkinesis.vine

import dev.architectury.event.CompoundEventResult
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.stats.Stats
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.FlintAndSteelItem
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.floor
import kotlin.math.min

/** Ticks for a fire front to cross one link (0.4 blocks), so fire runs 1 block a second. */
internal const val VINE_BURN_STEP_TICKS = 8L
/** Fronts on one vine; the client rejects a sync with more. */
const val MAX_VINE_BURN_FRONTS = 16
private const val FLAME_SCAN_TICKS = 10L
/** Cells are one block and links 0.4, so every other node still finds each cell the rope passes through. */
private const val FLAME_SCAN_NODE_STEP = 2
private const val BLOCK_FIRE_CHANCE = 0.3f
private const val ENTITY_FIRE_SECONDS = 8
private const val ENTITY_FIRE_DAMAGE = 1f
/** Distance past the rope surface that still counts as touching a block. */
private const val TOUCH_MARGIN = 0.06
private const val SHIP_SEARCH_MARGIN = 1.0

/** Fire on [link] that burns it away at [due]; then a front moves on toward the start (-1), the end (+1), or both (0). */
internal class VineBurnFront(val link: Int, val dir: Int, val due: Long)

internal val NO_SAVED_BURNS = IntArray(0)
private const val SAVED_BURN_INTS = 3

/** These fronts as (link, direction, ticks to the next step) triples, for a save. */
internal fun List<VineBurnFront>.saveBurns(now: Long): IntArray {
    if (isEmpty()) return NO_SAVED_BURNS
    val out = IntArray(size * SAVED_BURN_INTS)
    for (i in indices) {
        val front = this[i]
        out[i * SAVED_BURN_INTS] = front.link
        out[i * SAVED_BURN_INTS + 1] = front.dir
        out[i * SAVED_BURN_INTS + 2] = (front.due - now).coerceIn(0L, VINE_BURN_STEP_TICKS).toInt()
    }
    return out
}

internal fun validSavedBurns(saved: IntArray): Boolean =
    saved.size % SAVED_BURN_INTS == 0 && saved.size / SAVED_BURN_INTS <= MAX_VINE_BURN_FRONTS

/**
 * Puts the [saved] fronts back on [vine] at game time [now]. With a [run], [vine] is that run of the saved vine: only
 * fronts on its links come back, counted from the run start.
 */
internal fun WoggakVineData.restoreBurns(vine: WoggakVine, saved: IntArray, now: Long, run: IntRange?) {
    if (!validSavedBurns(saved)) return
    var i = 0
    while (i < saved.size) {
        val link = saved[i]
        val dir = saved[i + 1]
        val left = saved[i + 2].toLong().coerceIn(0L, VINE_BURN_STEP_TICKS)
        i += SAVED_BURN_INTS
        if (dir !in -1..1) continue
        val local = if (run == null) link else link - run.first
        if (local < 0 || (run != null && link + 1 > run.last)) continue
        if (vine.burns.any { it.link == local && it.dir == dir }) continue
        addFront(vine, VineBurnFront(local, dir, now + left))
    }
}

/** The burning vines and server-thread scratch; not saved. */
internal class VineBurns {
    val burning = ArrayList<WoggakVine>()
    var positions = DoubleArray(0)
    val pos = BlockPos.MutableBlockPos()
    val firePos = BlockPos.MutableBlockPos()
    val ships = ArrayList<Ship>()
    val box = AABBd()
    val probe = Vector3d()
    val probe2 = Vector3d()
    /** Game time of each player's last flint-and-steel light, so one click lights once. */
    val lastLight = HashMap<UUID, Long>()
}

internal fun registerVineBurns() {
    InteractionEvent.RIGHT_CLICK_ITEM.register { player, hand ->
        if (lightWoggakVine(player, hand)) CompoundEventResult.interruptTrue(player.getItemInHand(hand)) else CompoundEventResult.pass()
    }
    // The vine is not in the crosshair hit, so without this the flint lights the block behind it.
    InteractionEvent.RIGHT_CLICK_BLOCK.register { player, hand, _, _ ->
        if (lightWoggakVine(player, hand)) EventResult.interruptTrue() else EventResult.pass()
    }
}

/** True when a flint and steel use was at a vine; it lights the link unless the link is wet or burns already. */
@OptIn(GameTickOnly::class)
private fun lightWoggakVine(player: Player, hand: InteractionHand): Boolean {
    val stack = player.getItemInHand(hand)
    val item = stack.item
    if (item !is FlintAndSteelItem) return false
    val level = player.level() as? ServerLevel ?: return false
    val data = level.woggakVines
    val burns = data.burns
    // A use on a block and a use in the air can both reach the server for one click.
    if (burns.lastLight[player.uuid] == level.gameTime) return true
    val pick = level.pickWoggakVine(player) ?: return false
    burns.lastLight[player.uuid] = level.gameTime
    (player as? ServerPlayer)?.swing(hand, true)
    val nodes = burns.copyPositions(pick.chain)
    if (nodes < 2) return true
    level.collectShips(burns, burns.positions, nodes)
    if (!level.igniteLink(data, pick.vine, pick.link, burns.positions)) return true
    level.playSound(
        null, pick.hit.x, pick.hit.y, pick.hit.z, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1f, level.random.nextFloat() * 0.4f + 0.8f,
    )
    player.awardStat(Stats.ITEM_USED.get(item))
    stack.hurtAndBreak(1, player) { it.broadcastBreakEvent(hand) }
    return true
}

@OptIn(GameTickOnly::class)
internal fun ServerLevel.tickVineBurns(data: WoggakVineData) {
    scanForFlames(data)
    val burns = data.burns
    val chains = shipObjectWorld.allChains
    var i = 0
    // The pieces of a burn step join the list at its end and wait for the next tick.
    var count = burns.burning.size
    while (i < count) {
        val vine = burns.burning[i]
        if (data.vine(vine.id) !== vine || vine.burns.isEmpty()) {
            burns.burning.removeAt(i)
            count--
            continue
        }
        i++
        val chain = chains.getById(vine.chain)
        if (chain == null || chain.status.lifecycle != ChainLifecycle.ACTIVE) continue
        val nodes = burns.copyPositions(chain)
        if (nodes < 2) continue
        burnTouching(vine, burns, nodes)
        if (vine.burns.any { it.due <= gameTime }) stepBurn(data, vine, chain)
    }
}

/** Burns away each due link of [vine] in one split; [passBurns] moves the fronts on to the pieces. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.stepBurn(data: WoggakVineData, vine: WoggakVine, chain: ServerChain) {
    val fronts = vine.burns
    var due = 0
    for (k in fronts.indices) if (fronts[k].due <= gameTime) due++
    val cuts = IntArray(due)
    var j = 0
    for (k in fronts.indices) if (fronts[k].due <= gameTime) cuts[j++] = fronts[k].link
    if (!splitWoggakVine(vine, chain, false, moreCuts = cuts)) {
        vine.burns = emptyList()
        return
    }
    data.remove(vine)
    data.anchorsDirty = true
    data.jointsDirty = true
    data.setDirty()
}

/**
 * Moves [vine]'s fire fronts onto the split [pieces]; [positions] are its nodes before the split. A due front whose
 * link broke burned that link away, and moves on one link. A front whose link broke before it was due goes out, and a
 * front that reaches a wet link stops.
 */
internal fun ServerLevel.passBurns(vine: WoggakVine, pieces: List<SplitPiece>, broken: Set<Int>, positions: DoubleArray) {
    val data = woggakVines
    collectShips(data.burns, positions, positions.size / 3)
    val half = vine.width * 0.5
    val next = gameTime + VINE_BURN_STEP_TICKS
    val fronts = vine.burns
    for (f in fronts.indices) {
        val front = fronts[f]
        if (front.link !in broken) {
            data.placeFront(pieces, broken, positions, front.link, front.dir, front.due, fresh = false, level = this)
            continue
        }
        if (front.due > gameTime) continue
        igniteBlockAt(data.burns, positions, front.link, half)
        if (front.dir <= 0) data.placeFront(pieces, broken, positions, front.link - 1, -1, next, fresh = true, level = this)
        if (front.dir >= 0) data.placeFront(pieces, broken, positions, front.link + 1, 1, next, fresh = true, level = this)
    }
}

/** Puts a front on the piece that holds old link [link], unless that link broke or, for a [fresh] front, is wet. */
@Suppress("LongParameterList") // The pieces and old state, then one front.
private fun WoggakVineData.placeFront(
    pieces: List<SplitPiece>, broken: Set<Int>, positions: DoubleArray, link: Int, dir: Int, due: Long, fresh: Boolean, level: ServerLevel,
) {
    if (link < 0 || link in broken) return
    val piece = pieces.firstOrNull { link >= it.run.first && link + 1 <= it.run.last } ?: return
    if (fresh && level.linkWet(burns, positions, link)) return
    val local = link - piece.run.first
    val target = piece.vine
    if (target.burns.size >= MAX_VINE_BURN_FRONTS || target.burns.any { it.link == local && it.dir == dir }) return
    addFront(target, VineBurnFront(local, dir, due))
}

private fun WoggakVineData.addFront(vine: WoggakVine, front: VineBurnFront) {
    if (vine.burns.isEmpty()) burns.burning.add(vine)
    vine.burns = vine.burns + front
}

/** Lights [link] of [vine] unless it is wet or burns already; [p] holds its nodes, and [VineBurns.ships] the ships near them. */
private fun ServerLevel.igniteLink(data: WoggakVineData, vine: WoggakVine, link: Int, p: DoubleArray): Boolean {
    if (vine.burns.size >= MAX_VINE_BURN_FRONTS || vine.burns.any { it.link == link }) return false
    if (linkWet(data.burns, p, link)) return false
    data.addFront(vine, VineBurnFront(link, 0, gameTime + VINE_BURN_STEP_TICKS))
    data.touch(vine)
    return true
}

/** Lights the vines that touch fire or lava, each vine once every [FLAME_SCAN_TICKS] ticks; sleeping vines are not live. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.scanForFlames(data: WoggakVineData) {
    val burns = data.burns
    val chains = shipObjectWorld.allChains
    for (i in 0 until data.vines.size) {
        val vine = data.vines[i]
        if (Math.floorMod(vine.id + gameTime, FLAME_SCAN_TICKS) != 0L) continue
        val chain = chains.getById(vine.chain) ?: continue
        if (chain.status.lifecycle != ChainLifecycle.ACTIVE) continue
        val nodes = burns.copyPositions(chain)
        if (nodes < 2) continue
        collectShips(burns, burns.positions, nodes)
        val half = vine.width * 0.5
        var n = 0
        while (n < nodes) {
            if (nodeTouches(burns, burns.positions, n, half) { it.isFlame() } && igniteLink(data, vine, min(n, nodes - 2), burns.positions)) break
            n = if (n < nodes - 1 && n + FLAME_SCAN_NODE_STEP >= nodes) nodes - 1 else n + FLAME_SCAN_NODE_STEP
        }
    }
}

/** Vanilla fire's entity effect, for each entity that touches a burning link. */
private fun ServerLevel.burnTouching(vine: WoggakVine, burns: VineBurns, nodes: Int) {
    val p = burns.positions
    val r = vine.width * 0.5
    val fronts = vine.burns
    for (f in fronts.indices) {
        val front = fronts[f]
        if (front.link !in 0 until nodes - 1) continue
        val k = front.link * 3
        val a = Vec3(p[k], p[k + 1], p[k + 2])
        val b = Vec3(p[k + 3], p[k + 4], p[k + 5])
        for (entity in getEntities(null as Entity?, AABB(a, b).inflate(r), EntitySelector.NO_SPECTATORS)) {
            val hitBox = entity.boundingBox.inflate(r)
            if (!hitBox.contains(a) && !hitBox.clip(a, b).isPresent) continue
            if (!entity.fireImmune()) {
                entity.remainingFireTicks += 1
                if (entity.remainingFireTicks == 0) entity.setSecondsOnFire(ENTITY_FIRE_SECONDS)
            }
            entity.hurt(damageSources().inFire(), ENTITY_FIRE_DAMAGE)
        }
    }
}

/**
 * With [BLOCK_FIRE_CHANCE], sets fire in the cell of burnt [link] when the link rests on, or lies against, a flammable
 * block. The block under the link comes first, then the nearest side.
 */
private fun ServerLevel.igniteBlockAt(burns: VineBurns, p: DoubleArray, link: Int, half: Double) {
    if (!gameRules.getBoolean(GameRules.RULE_DOFIRETICK) || random.nextFloat() >= BLOCK_FIRE_CHANCE) return
    val k = link * 3
    if (k + 5 >= p.size) return
    val x = (p[k] + p[k + 3]) * 0.5
    val y = (p[k + 1] + p[k + 4]) * 0.5
    val z = (p[k + 2] + p[k + 5]) * 0.5
    val reach = half + TOUCH_MARGIN
    if (lightBeside(burns, x, y, z, 0.0, -reach, 0.0)) return
    val fx = x - floor(x)
    val fz = z - floor(z)
    val toX = min(fx, 1 - fx)
    val toZ = min(fz, 1 - fz)
    if (min(toX, toZ) > reach) return
    if (toX <= toZ) lightBeside(burns, x, y, z, if (fx < 0.5) -reach else reach, 0.0, 0.0)
    else lightBeside(burns, x, y, z, 0.0, 0.0, if (fz < 0.5) -reach else reach)
}

/** Fire at world point (x, y, z) against the block at the offset point, in the world or in a ship's shipyard. */
@Suppress("LongParameterList") // A point and an offset.
private fun ServerLevel.lightBeside(burns: VineBurns, x: Double, y: Double, z: Double, ox: Double, oy: Double, oz: Double): Boolean {
    if (lightIn(burns, x, y, z, x + ox, y + oy, z + oz)) return true
    for (i in burns.ships.indices) {
        val toShip = burns.ships[i].worldToShip
        val at = toShip.transformPosition(x, y, z, burns.probe)
        val against = toShip.transformPosition(x + ox, y + oy, z + oz, burns.probe2)
        if (lightIn(burns, at.x, at.y, at.z, against.x, against.y, against.z)) return true
    }
    return false
}

@Suppress("LongParameterList") // Two points in one frame.
private fun ServerLevel.lightIn(burns: VineBurns, fx: Double, fy: Double, fz: Double, sx: Double, sy: Double, sz: Double): Boolean {
    val fire = burns.firePos.set(Mth.floor(fx), Mth.floor(fy), Mth.floor(fz))
    val solid = burns.pos.set(Mth.floor(sx), Mth.floor(sy), Mth.floor(sz))
    if (fire == solid) return false
    if (stateAt(solid)?.ignitedByLava() != true || stateAt(fire)?.isAir != true) return false
    val state = BaseFireBlock.getState(this, fire)
    if (!state.canSurvive(this, fire)) return false
    return setBlock(fire.immutable(), state, Block.UPDATE_ALL_IMMEDIATE)
}

private fun ServerLevel.linkWet(burns: VineBurns, p: DoubleArray, link: Int): Boolean {
    val k = link * 3
    if (k + 5 >= p.size) return false
    return anyCell(burns, p[k], p[k + 1], p[k + 2]) { it.isWet() } ||
        anyCell(burns, p[k + 3], p[k + 4], p[k + 5]) { it.isWet() }
}

/** True when node [n]'s cell, or the cell just under the rope there, passes [test]. */
private inline fun ServerLevel.nodeTouches(burns: VineBurns, p: DoubleArray, n: Int, half: Double, test: (BlockState) -> Boolean): Boolean {
    val k = n * 3
    return anyCell(burns, p[k], p[k + 1], p[k + 2], test) || anyCell(burns, p[k], p[k + 1] - half - TOUCH_MARGIN, p[k + 2], test)
}

/** True when the cell at world (x, y, z) passes [test], in the world or in a ship of [VineBurns.ships]. */
private inline fun ServerLevel.anyCell(burns: VineBurns, x: Double, y: Double, z: Double, test: (BlockState) -> Boolean): Boolean {
    val world = stateAt(burns.pos.set(Mth.floor(x), Mth.floor(y), Mth.floor(z)))
    if (world != null && test(world)) return true
    for (i in burns.ships.indices) {
        val local = burns.ships[i].worldToShip.transformPosition(x, y, z, burns.probe)
        val state = stateAt(burns.pos.set(Mth.floor(local.x), Mth.floor(local.y), Mth.floor(local.z)))
        if (state != null && test(state)) return true
    }
    return false
}

/** Null when the chunk is not loaded: Level.getBlockState blocks the server thread until a pending chunk loads. */
private fun ServerLevel.stateAt(pos: BlockPos): BlockState? {
    if (isOutsideBuildHeight(pos)) return null
    return chunkSource.getChunkNow(SectionPos.blockToSectionCoord(pos.x), SectionPos.blockToSectionCoord(pos.z))?.getBlockState(pos)
}

private fun BlockState.isFlame(): Boolean = `is`(BlockTags.FIRE) || fluidState.`is`(FluidTags.LAVA)

private fun BlockState.isWet(): Boolean = fluidState.`is`(FluidTags.WATER)

/** The ships near the [nodes] in [p], for the cell checks of one vine. */
private fun ServerLevel.collectShips(burns: VineBurns, p: DoubleArray, nodes: Int) {
    burns.ships.clear()
    if (nodes < 1) return
    val box = burns.box
    box.minX = p[0]; box.minY = p[1]; box.minZ = p[2]
    box.maxX = p[0]; box.maxY = p[1]; box.maxZ = p[2]
    for (n in 1 until nodes) box.union(p[n * 3], p[n * 3 + 1], p[n * 3 + 2])
    box.minX -= SHIP_SEARCH_MARGIN; box.minY -= SHIP_SEARCH_MARGIN; box.minZ -= SHIP_SEARCH_MARGIN
    box.maxX += SHIP_SEARCH_MARGIN; box.maxY += SHIP_SEARCH_MARGIN; box.maxZ += SHIP_SEARCH_MARGIN
    for (ship in getShipsIntersecting(box)) burns.ships.add(ship)
}

/** Copies [chain]'s node positions into [VineBurns.positions]; returns the node count, or 0 on a failed copy. */
@OptIn(GameTickOnly::class)
private fun VineBurns.copyPositions(chain: ServerChain): Int {
    val nodes = chain.status.nodeCount
    if (nodes < 2) return 0
    if (positions.size < nodes * 3) positions = DoubleArray(nodes * 3)
    return if (chain.copyNodePositions(positions) == nodes) nodes else 0
}
