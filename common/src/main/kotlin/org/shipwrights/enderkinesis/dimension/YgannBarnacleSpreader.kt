package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.chunk.LevelChunk
import org.shipwrights.enderkinesis.block.YgannBarnacleBlock
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Ships travelling the Ygann Abyss pick up barnacles: every [ATTACH_INTERVAL] each
 * loaded ship rolls [ATTACH_CHANCE] to sprout one on a random exterior hull face
 * (solid block with a sturdy face against air), capped at [MAX_PER_SHIP].
 */
object YgannBarnacleSpreader {

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register { level ->
            if (level.dimension() != YgannAbyss.LEVEL_KEY) return@register
            if (level.gameTime % ATTACH_INTERVAL != 0L) return@register
            val dimId = level.dimensionId
            for (ship in level.shipObjectWorld.loadedShips) {
                if (ship.chunkClaimDimension != dimId) continue
                if (level.random.nextFloat() > ATTACH_CHANCE) continue
                tryAttach(level, ship)
            }
        }
    }

    private fun tryAttach(level: ServerLevel, ship: org.valkyrienskies.core.api.ships.Ship) {
        val aabb = ship.shipAABB ?: return
        // Only ships that are at least MIN_SHIP_AXIS blocks long in some axis — small
        // skiffs don't collect barnacles (and wouldn't survive their drag).
        val lenX = aabb.maxX() - aabb.minX() + 1
        val lenY = aabb.maxY() - aabb.minY() + 1
        val lenZ = aabb.maxZ() - aabb.minZ() + 1
        if (Math.max(lenX, Math.max(lenY, lenZ)) < MIN_SHIP_AXIS) return
        if (countBarnacles(level, ship) >= MAX_PER_SHIP) return
        val rng = level.random
        val cursor = BlockPos.MutableBlockPos()
        repeat(ATTEMPTS) {
            cursor.set(
                aabb.minX() + rng.nextInt(aabb.maxX() - aabb.minX() + 1),
                aabb.minY() + rng.nextInt(aabb.maxY() - aabb.minY() + 1),
                aabb.minZ() + rng.nextInt(aabb.maxZ() - aabb.minZ() + 1),
            )
            // `getBlockState` loads a chunk that is not loaded, on the server thread.
            if (!isChunkReady(level, cursor)) return@repeat
            val support = level.getBlockState(cursor)
            if (support.isAir) return@repeat
            val face = Direction.values()[rng.nextInt(6)]
            val spot = cursor.relative(face)
            if (!isChunkReady(level, spot)) return@repeat
            if (!level.getBlockState(spot).isAir) return@repeat
            if (!support.isFaceSturdy(level, cursor, face)) return@repeat
            val state = EKBlocks.YGANN_BARNACLE.get().defaultBlockState()
                .setValue(YgannBarnacleBlock.FACING, face)
            val placed = spot.immutable()
            if (!level.setBlockAndUpdate(placed, state)) return@repeat
            // onPlace can run before the BE exists in the setBlock flow — stamp here.
            (level.getBlockEntity(placed) as? YgannBarnacleBlockEntity)?.initBorn(level)
            return
        }
    }

    private fun isChunkReady(level: ServerLevel, pos: BlockPos): Boolean =
        level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) != null

    private fun countBarnacles(level: ServerLevel, ship: org.valkyrienskies.core.api.ships.Ship): Int {
        val aabb = ship.shipAABB ?: return 0
        var count = 0
        for (cx in (aabb.minX() shr 4)..(aabb.maxX() shr 4)) {
            for (cz in (aabb.minZ() shr 4)..(aabb.maxZ() shr 4)) {
                val chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: continue
                for (be in chunk.blockEntities.values) {
                    if (be is YgannBarnacleBlockEntity) count++
                }
            }
        }
        return count
    }

    /** Roll cadence (30 s) and per-roll odds — an hour in Ygann averages a few. */
    private const val ATTACH_INTERVAL = 600L
    private const val ATTACH_CHANCE = 0.35f
    private const val MAX_PER_SHIP = 5
    private const val ATTEMPTS = 24
    /** Ships must be at least this many blocks long in some axis to collect barnacles. */
    private const val MIN_SHIP_AXIS = 8
}
