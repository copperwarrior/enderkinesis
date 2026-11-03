package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.animal.Sheep
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.DyeableLeatherItem
import net.minecraft.world.item.HoneycombItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ConcretePowderBlock
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.WeatheringCopper
import net.minecraft.world.level.block.entity.BannerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleLimbNetwork
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.toWorldCoordinates
import java.util.WeakHashMap

/** Incantation of the River as a pressure washer: it cleans the block, item or sheep the jet stays on. Each wash
 *  needs the jet held on the same target for a while, like mining progress. Server thread only. */
object RiverWash {
    /** Ticks on one block before each wash step. */
    private const val BLOCK_WASH_TICKS = 4

    /** Ticks on one item or sheep before each wash. */
    private const val ENTITY_WASH_TICKS = 6

    /** Ticks between tries at the barnacle limbs near the impact. */
    private const val BARNACLE_TICKS = 5
    private const val BARNACLE_SEARCH = 2
    private const val BARNACLE_REACH_SQR = 0.6 * 0.6
    private const val LIMB_SAMPLE_PX = 2.0

    private class Progress(var key: Long, var ticks: Int)

    // Weak keys, so a player who left is not held.
    private val progress = WeakHashMap<Player, Progress>()

    private val DEMOSS: Map<Block, Block> by lazy {
        mapOf(
            Blocks.MOSSY_COBBLESTONE to Blocks.COBBLESTONE,
            Blocks.MOSSY_COBBLESTONE_STAIRS to Blocks.COBBLESTONE_STAIRS,
            Blocks.MOSSY_COBBLESTONE_SLAB to Blocks.COBBLESTONE_SLAB,
            Blocks.MOSSY_COBBLESTONE_WALL to Blocks.COBBLESTONE_WALL,
            Blocks.MOSSY_STONE_BRICKS to Blocks.STONE_BRICKS,
            Blocks.MOSSY_STONE_BRICK_STAIRS to Blocks.STONE_BRICK_STAIRS,
            Blocks.MOSSY_STONE_BRICK_SLAB to Blocks.STONE_BRICK_SLAB,
            Blocks.MOSSY_STONE_BRICK_WALL to Blocks.STONE_BRICK_WALL,
        )
    }

    private val TO_DIRT: Set<Block> by lazy {
        setOf(Blocks.GRASS_BLOCK, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.DIRT_PATH, Blocks.FARMLAND, Blocks.COARSE_DIRT)
    }

    /** Growth that washes off with its drops: on the hit face, or in the cell in front of it. */
    private val GROWTH: Set<Block> by lazy {
        setOf(
            Blocks.VINE, Blocks.GLOW_LICHEN, Blocks.SCULK_VEIN, Blocks.SNOW, Blocks.COBWEB, Blocks.HANGING_ROOTS,
            Blocks.MOSS_CARPET, Blocks.MOSS_BLOCK,
        )
    }

    /** Call each tick of a River cast with the jet's hit. */
    fun wash(level: ServerLevel, player: Player, jet: IncantationOfTheRiverItem.Jet) {
        val entity = jet.entity
        val block = jet.block
        val key = when {
            entity != null -> entity.id.toLong() or ENTITY_KEY_BIT
            block != null -> block.blockPos.asLong()
            else -> return
        }
        val p = progress.getOrPut(player) { Progress(key, 0) }
        if (p.key != key) {
            p.key = key
            p.ticks = 0
        }
        p.ticks++
        if (entity != null) {
            if (p.ticks % ENTITY_WASH_TICKS == 0) washEntity(level, entity)
            return
        }
        if (block == null) return
        if (p.ticks % BARNACLE_TICKS == 0) washBarnacles(level, block)
        if (!player.mayBuild()) return
        if (p.ticks % BLOCK_WASH_TICKS != 0) return
        // Offsets stay in the hit's frame, so ship blocks work too.
        val front = block.blockPos.relative(block.direction)
        washBlock(level, player, block.blockPos)
        washBlock(level, player, front)
    }

    private const val ENTITY_KEY_BIT = 1L shl 62

    private fun washBlock(level: ServerLevel, player: Player, pos: BlockPos) {
        if (!level.mayInteract(player, pos)) return
        val state = level.getBlockState(pos)
        val block = state.block
        when {
            block in GROWTH -> level.destroyBlock(pos, true, player)
            block in DEMOSS -> swap(level, pos, state, DEMOSS.getValue(block).withPropertiesOf(state))
            block in TO_DIRT -> swap(level, pos, state, Blocks.DIRT.defaultBlockState())
            else -> scrapeCopper(level, pos, state)
        }
    }

    /** Wax comes off first, then one oxidation stage at each step, as an axe does. */
    private fun scrapeCopper(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val unwaxed = HoneycombItem.WAX_OFF_BY_BLOCK.get()[state.block]
        if (unwaxed != null) {
            level.setBlock(pos, unwaxed.withPropertiesOf(state), Block.UPDATE_ALL_IMMEDIATE)
            level.levelEvent(null, LevelEvent.PARTICLES_WAX_OFF, pos, 0)
            level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos)
            return
        }
        WeatheringCopper.getPrevious(state).ifPresent { previous ->
            level.setBlock(pos, previous, Block.UPDATE_ALL_IMMEDIATE)
            level.levelEvent(null, LevelEvent.PARTICLES_SCRAPE, pos, 0)
            level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos)
        }
    }

    private fun swap(level: ServerLevel, pos: BlockPos, old: BlockState, new: BlockState) {
        level.setBlock(pos, new, Block.UPDATE_ALL)
        level.levelEvent(null, LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(old))
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos)
    }

    private fun washEntity(level: ServerLevel, entity: Entity) {
        val washed = when (entity) {
            is ItemEntity -> washItem(entity)
            is Sheep -> if (entity.color != DyeColor.WHITE) {
                entity.color = DyeColor.WHITE
                true
            } else {
                false
            }
            else -> false
        }
        if (washed) {
            level.sendParticles(ParticleTypes.SPLASH, entity.x, entity.y + entity.bbHeight * 0.5, entity.z, 12, 0.2, 0.2, 0.2, 0.1)
        }
    }

    /** The cauldron wash, plus concrete powder setting to concrete. */
    private fun washItem(entity: ItemEntity): Boolean {
        val stack = entity.item
        val item = stack.item
        if (item is DyeableLeatherItem && item.hasCustomColor(stack)) {
            item.clearColor(stack)
            entity.item = stack
            return true
        }
        if (BannerBlockEntity.getPatternCount(stack) > 0) {
            val copy = stack.copy()
            BannerBlockEntity.removeLastPattern(copy)
            entity.item = copy
            return true
        }
        val block = Block.byItem(item)
        if (block is ShulkerBoxBlock && block.color != null) {
            val plain = ItemStack(Items.SHULKER_BOX, stack.count)
            if (stack.hasTag()) plain.tag = stack.tag?.copy()
            entity.item = plain
            return true
        }
        if (block is ConcretePowderBlock) {
            val id = BuiltInRegistries.BLOCK.getKey(block)
            val concrete = BuiltInRegistries.BLOCK.get(ResourceLocation(id.namespace, id.path.removeSuffix("_powder")))
            if (concrete === Blocks.AIR) return false
            entity.item = ItemStack(concrete, stack.count)
            return true
        }
        return false
    }

    /** Cuts the barnacle limb nearest the impact, as a left-click would. VS2: the hit's blockPos is in shipyard
     *  space and its location in world space, so the location goes to the ship's frame first. */
    private fun washBarnacles(level: ServerLevel, hit: BlockHitResult) {
        val ship = level.getShipManagingPos(hit.blockPos)
        val impact = if (ship != null) {
            val v = ship.transform.worldToShip.transformPosition(Vector3d(hit.location.x, hit.location.y, hit.location.z))
            Vec3(v.x, v.y, v.z)
        } else {
            hit.location
        }
        val centre = hit.blockPos
        val cell = BlockPos.MutableBlockPos()
        for (dx in -BARNACLE_SEARCH..BARNACLE_SEARCH) for (dy in -BARNACLE_SEARCH..BARNACLE_SEARCH) for (dz in -BARNACLE_SEARCH..BARNACLE_SEARCH) {
            cell.setWithOffset(centre, dx, dy, dz)
            val be = level.getBlockEntity(cell) as? YgannBarnacleBlockEntity ?: continue
            if (cutNearest(level, be, impact)) return
        }
    }

    private fun cutNearest(level: ServerLevel, be: YgannBarnacleBlockEntity, impact: Vec3): Boolean {
        val pos = be.blockPos
        val paths = be.serverPaths(level, pos)
        var bestPath = -1
        var bestArc = 0.0
        var bestSqr = BARNACLE_REACH_SQR
        for ((i, path) in paths.withIndex()) {
            var arc = 0.0
            val total = path.totalPx
            while (arc < total) {
                val d = YgannBarnacleGeometry.limbPoint(pos, path, arc).distanceToSqr(impact)
                if (d < bestSqr) {
                    bestSqr = d
                    bestPath = i
                    bestArc = arc
                }
                arc += LIMB_SAMPLE_PX
            }
        }
        if (bestPath < 0) return false
        val point = level.toWorldCoordinates(YgannBarnacleGeometry.limbPoint(pos, paths[bestPath], bestArc))
        return YgannBarnacleLimbNetwork.cut(level, be, bestPath, bestArc, point)
    }
}
