package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.joml.primitives.AABBic
import org.shipwrights.enderkinesis.block.YgannBarnacleBlock
import org.shipwrights.enderkinesis.blockentity.QuasarOfPurityBlockEntity
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.core.api.util.GameTickOnly

/**
 * Ships travelling the Ygann Abyss pick up barnacles: every [ATTACH_INTERVAL] each
 * loaded ship rolls [ATTACH_CHANCE] to sprout one on a random exterior hull face
 * (solid block with a sturdy face against air), capped at [MAX_PER_SHIP]. A mature
 * barnacle also seeds new ones near itself ([tickMature]). A Quasar of Purity on the
 * ship stops all new attachment.
 */
@OptIn(GameTickOnly::class)
object YgannBarnacleSpreader {

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register { level ->
            if (level.dimension() != YgannAbyss.LEVEL_KEY) return@register
            if (level.gameTime % ATTACH_INTERVAL != 0L) return@register
            val dimId = level.dimensionId
            for (ship in level.shipObjectWorld.loadedShips) {
                if (ship.chunkClaimDimension != dimId) continue
                if (level.random.nextFloat() > ATTACH_CHANCE) continue
                val aabb = ship.shipAABB ?: continue
                tryAttach(level, ship, BoundingBox(aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ()))
            }
        }
    }

    /** Called each server tick by a mature barnacle. */
    fun tickMature(level: ServerLevel, pos: BlockPos) {
        if ((level.gameTime + (pos.asLong() and SEED_STAGGER_MASK)) % SEED_INTERVAL != 0L) return
        if (level.random.nextFloat() > SEED_CHANCE) return
        val ship = level.getShipManagingPos(pos) ?: return
        val aabb = ship.shipAABB ?: return
        val region = BoundingBox(
            Math.max(aabb.minX(), pos.x - SEED_RADIUS), Math.max(aabb.minY(), pos.y - SEED_RADIUS),
            Math.max(aabb.minZ(), pos.z - SEED_RADIUS), Math.min(aabb.maxX(), pos.x + SEED_RADIUS),
            Math.min(aabb.maxY(), pos.y + SEED_RADIUS), Math.min(aabb.maxZ(), pos.z + SEED_RADIUS),
        )
        tryAttach(level, ship, region)
    }

    /** Tries to put one barnacle on an exterior hull face with its support block in [region]. */
    private fun tryAttach(level: ServerLevel, ship: org.valkyrienskies.core.api.ships.Ship, region: BoundingBox) {
        val aabb = ship.shipAABB ?: return
        // Only ships that are at least MIN_SHIP_AXIS blocks long in some axis — small
        // skiffs don't collect barnacles (and wouldn't survive their drag).
        val lenX = aabb.maxX() - aabb.minX() + 1
        val lenY = aabb.maxY() - aabb.minY() + 1
        val lenZ = aabb.maxZ() - aabb.minZ() + 1
        if (Math.max(lenX, Math.max(lenY, lenZ)) < MIN_SHIP_AXIS) return
        val census = hullCensus(level, ship) ?: return
        if (census.warded || census.barnacles >= MAX_PER_SHIP) return
        val rng = level.random
        val cursor = BlockPos.MutableBlockPos()
        repeat(ATTEMPTS) {
            cursor.set(
                region.minX() + rng.nextInt(region.xSpan),
                region.minY() + rng.nextInt(region.ySpan),
                region.minZ() + rng.nextInt(region.zSpan),
            )
            // `getBlockState` loads a chunk that is not loaded, on the server thread.
            if (!isChunkReady(level, cursor)) return@repeat
            val support = level.getBlockState(cursor)
            if (support.isAir) return@repeat
            val face = Direction.entries[rng.nextInt(6)]
            val spot = cursor.relative(face)
            if (!isChunkReady(level, spot)) return@repeat
            if (!level.getBlockState(spot).isAir) return@repeat
            if (!support.isFaceSturdy(level, cursor, face)) return@repeat
            if (!isExterior(level, aabb, spot, face)) return@repeat
            val state = EKBlocks.YGANN_BARNACLE.get().defaultBlockState()
                .setValue(YgannBarnacleBlock.FACING, face)
            val placed = spot.immutable()
            if (!level.setBlockAndUpdate(placed, state)) return@repeat
            // onPlace can run before the BE exists in the setBlock flow — stamp here.
            (level.getBlockEntity(placed) as? YgannBarnacleBlockEntity)?.initBorn(level)
            level.playSound(null, placed, EKSounds.YGANN_BARNACLE_LATCH, SoundSource.BLOCKS, 1f, 1f)
            return
        }
    }

    /** True when open cells run from [spot] along [face] out of the ship's box, so cabins and holds do not qualify. */
    private fun isExterior(level: ServerLevel, aabb: AABBic, spot: BlockPos, face: Direction): Boolean {
        val cursor = spot.mutable()
        repeat(MAX_EXTERIOR_PROBE) {
            if (cursor.x < aabb.minX() || cursor.x > aabb.maxX() ||
                cursor.y < aabb.minY() || cursor.y > aabb.maxY() ||
                cursor.z < aabb.minZ() || cursor.z > aabb.maxZ()
            ) return true
            if (!isChunkReady(level, cursor)) return false
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty) return false
            cursor.move(face)
        }
        return true
    }

    private fun isChunkReady(level: ServerLevel, pos: BlockPos): Boolean =
        level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) != null

    private data class HullCensus(val barnacles: Int, val warded: Boolean)

    private fun hullCensus(level: ServerLevel, ship: org.valkyrienskies.core.api.ships.Ship): HullCensus? {
        val aabb = ship.shipAABB ?: return null
        var count = 0
        for (cx in (aabb.minX() shr 4)..(aabb.maxX() shr 4)) {
            for (cz in (aabb.minZ() shr 4)..(aabb.maxZ() shr 4)) {
                val chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: continue
                for (be in chunk.blockEntities.values) {
                    if (be is QuasarOfPurityBlockEntity) return HullCensus(count, warded = true)
                    if (be is YgannBarnacleBlockEntity) count++
                }
            }
        }
        return HullCensus(count, warded = false)
    }

    /** Roll cadence (30 s) and per-roll odds — an hour in Ygann averages a few. */
    private const val ATTACH_INTERVAL = 600L
    private const val ATTACH_CHANCE = 0.35f
    private const val MAX_PER_SHIP = 5
    private const val ATTEMPTS = 24
    /** Ships must be at least this many blocks long in some axis to collect barnacles. */
    private const val MIN_SHIP_AXIS = 8
    private const val MAX_EXTERIOR_PROBE = 512
    /** A mature barnacle rolls [SEED_CHANCE] every [SEED_INTERVAL] ticks to seed one within [SEED_RADIUS] blocks. */
    private const val SEED_INTERVAL = 2400L
    private const val SEED_CHANCE = 0.25f
    private const val SEED_RADIUS = 6
    private const val SEED_STAGGER_MASK = 1023L
}
