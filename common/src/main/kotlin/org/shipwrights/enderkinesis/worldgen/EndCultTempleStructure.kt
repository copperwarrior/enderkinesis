package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.mixin.StructureTemplateAccessor
import org.shipwrights.enderkinesis.registry.EKStructures

// Jigsaw samples the surface at the bounding-box centre, 13 blocks from the tower door, so slopes bury the door.
class EndCultTempleStructure(
    settings: StructureSettings,
    internal val pool: Holder<StructureTemplatePool>,
) : Structure(settings) {
    internal val mySettings: StructureSettings = settings

    /** Per template column (x + z * sizeX): lowest template layer when it is solid ground-level base, else -1. */
    private val baseBottoms = AtomicReference<FoundationColumns?>()

    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        val chunkPos = context.chunkPos()
        val startPos = BlockPos(chunkPos.minBlockX, 0, chunkPos.minBlockZ)
        val inner = JigsawPlacement.addPieces(
            context, pool, Optional.empty(), MAX_DEPTH, startPos, false, Optional.empty(), MAX_DISTANCE_FROM_CENTER,
        ).orElse(null) ?: return Optional.empty()

        val pieces = inner.piecesBuilder.build().pieces()
        val start = pieces.firstOrNull() as? PoolElementStructurePiece ?: return Optional.empty()
        val surfaceY = approachSurfaceY(context, start)
        if (surfaceY <= context.heightAccessor().minBuildHeight) return Optional.empty()

        val dy = surfaceY - (start.position.y + WALK_LAYER)
        pieces.forEach { it.move(0, dy, 0) }
        val door = toWorld(start, BlockPos(DOOR_X, WALK_LAYER, DOOR_Z))
        return Optional.of(GenerationStub(door) { builder -> pieces.forEach(builder::addPiece) })
    }

    private fun approachSurfaceY(context: GenerationContext, start: PoolElementStructurePiece): Int {
        var maxY = Int.MIN_VALUE
        for (x in APPROACH_MIN_X..APPROACH_MAX_X) {
            for (z in APPROACH_MIN_Z..APPROACH_MAX_Z) {
                val pos = toWorld(start, BlockPos(x, 0, z))
                val y = context.chunkGenerator().getFirstFreeHeight(
                    pos.x, pos.z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState(),
                )
                maxY = maxOf(maxY, y)
            }
        }
        return maxY
    }

    override fun afterPlace(
        level: WorldGenLevel,
        structureManager: StructureManager,
        generator: ChunkGenerator,
        random: RandomSource,
        chunkBox: BoundingBox,
        chunkPos: ChunkPos,
        pieces: PiecesContainer,
    ) {
        val start = pieces.pieces().firstOrNull() as? PoolElementStructurePiece ?: return
        val columns = foundationColumns(level) ?: return
        val bases = ArrayList<BlockPos>()
        val baseColumns = HashSet<Long>()
        val ceilings = HashMap<Long, Int>()
        for (z in 0 until columns.sizeZ) {
            for (x in 0 until columns.sizeX) {
                val i = x + z * columns.sizeX
                if (columns.lowest[i] == Int.MAX_VALUE) continue
                val cell = toWorld(start, BlockPos(x, columns.lowest[i], z))
                ceilings[ChunkPos.asLong(cell.x, cell.z)] = cell.y - 1
                if (columns.bottoms[i] >= 0) {
                    bases.add(cell)
                    baseColumns.add(ChunkPos.asLong(cell.x, cell.z))
                }
            }
        }
        if (bases.isEmpty()) return

        val box = start.boundingBox
        val cursor = BlockPos.MutableBlockPos()
        for (x in maxOf(chunkBox.minX(), box.minX() - SKIRT_RADIUS)..minOf(chunkBox.maxX(), box.maxX() + SKIRT_RADIUS)) {
            for (z in maxOf(chunkBox.minZ(), box.minZ() - SKIRT_RADIUS)..minOf(chunkBox.maxZ(), box.maxZ() + SKIRT_RADIUS)) {
                val key = ChunkPos.asLong(x, z)
                val ceiling = ceilings[key]
                if (key in baseColumns && ceiling != null) {
                    fillDown(level, cursor.set(x, ceiling, z))
                    continue
                }
                val base = highestBase(bases, x, z) ?: continue
                val d = distance(base, x, z)
                var top = base.y - 1 - d - random.nextInt(2)
                if (ceiling != null) top = minOf(top, ceiling)
                if (openAbove(level, cursor, x, z, top, base.y - 1)) fillDown(level, cursor.set(x, top, z))
            }
        }
    }

    /** The base whose skirt reaches highest over this column: one block lower per block of distance. */
    private fun highestBase(bases: List<BlockPos>, x: Int, z: Int): BlockPos? {
        var best: BlockPos? = null
        var bestTop = Int.MIN_VALUE
        for (base in bases) {
            val d = distance(base, x, z)
            if (d > SKIRT_RADIUS) continue
            if (base.y - 1 - d > bestTop) {
                bestTop = base.y - 1 - d
                best = base
            }
        }
        return best
    }

    private fun distance(base: BlockPos, x: Int, z: Int): Int {
        val dx = base.x - x
        val dz = base.z - z
        return Mth.ceil(Mth.sqrt((dx * dx + dz * dz).toFloat()))
    }

    // A skirt grows down from the surface; a start point under terrain (thin island edge, cave) must not fill.
    private fun openAbove(level: WorldGenLevel, cursor: BlockPos.MutableBlockPos, x: Int, z: Int, top: Int, baseTop: Int): Boolean {
        for (y in top + 1..baseTop) {
            val state = level.getBlockState(cursor.set(x, y, z))
            if (!state.isAir && !state.canBeReplaced()) return false
        }
        return true
    }

    // Over void there is no ground within reach; fill nothing so no stalk hangs below the island.
    private fun fillDown(level: WorldGenLevel, cursor: BlockPos.MutableBlockPos) {
        val top = cursor.y
        var bottom = top
        while (true) {
            if (top - bottom >= MAX_FOUNDATION_DEPTH || bottom < level.minBuildHeight) return
            val state = level.getBlockState(cursor.setY(bottom))
            if (!state.isAir && !state.canBeReplaced()) break
            bottom--
        }
        val endStone = Blocks.END_STONE.defaultBlockState()
        for (y in bottom + 1..top) level.setBlock(cursor.setY(y), endStone, 2)
    }

    private fun foundationColumns(level: WorldGenLevel): FoundationColumns? {
        baseBottoms.get()?.let { return it }
        val template = level.level.server.structureManager.get(TEMPLATE_ID).orElse(null) ?: return null
        val computed = FoundationColumns.of(template)
        baseBottoms.compareAndSet(null, computed)
        return computed
    }

    // SinglePoolElement rotates about the template origin (pivot ZERO).
    private fun toWorld(piece: PoolElementStructurePiece, local: BlockPos): BlockPos =
        piece.position.offset(StructureTemplate.transform(local, Mirror.NONE, piece.rotation, BlockPos.ZERO))

    override fun type(): StructureType<*> = EKStructures.END_CULT_TEMPLE.get()

    /** [lowest]: lowest template cell of any kind per column (MAX_VALUE = void); [bottoms]: solid base layer or -1. */
    private class FoundationColumns(val sizeX: Int, val sizeZ: Int, val lowest: IntArray, val bottoms: IntArray) {
        companion object {
            // Only columns whose lowest template cell is solid and at or below the floor; eaves and air stay unfilled.
            fun of(template: StructureTemplate): FoundationColumns {
                val size = template.size
                val lowest = IntArray(size.x * size.z) { Int.MAX_VALUE }
                val solid = BooleanArray(size.x * size.z)
                val blocks = (template as StructureTemplateAccessor).`enderkinesis$getPalettes`()[0].blocks()
                for (info in blocks) {
                    val i = info.pos().x + info.pos().z * size.x
                    if (info.pos().y < lowest[i]) {
                        lowest[i] = info.pos().y
                        solid[i] = !info.state().isAir && !info.state().`is`(Blocks.STRUCTURE_BLOCK)
                    }
                }
                val bottoms = IntArray(lowest.size) { i ->
                    if (solid[i] && lowest[i] < WALK_LAYER) lowest[i] else -1
                }
                return FoundationColumns(size.x, size.z, lowest, bottoms)
            }
        }
    }

    companion object {
        private val TEMPLATE_ID = ResourceLocation(EnderkinesisMod.MOD_ID, "end_cult_temple")

        /** Template layer that the player walks on in the tower doorway (floor is layer 12). */
        private const val WALK_LAYER = 13
        private const val DOOR_X = 29
        private const val DOOR_Z = 10

        /** Template cells outside the -Z doorstep; terrain here must not rise above [WALK_LAYER]. */
        private const val APPROACH_MIN_X = 27
        private const val APPROACH_MAX_X = 31
        private const val APPROACH_MIN_Z = 6
        private const val APPROACH_MAX_Z = 9

        private const val MAX_FOUNDATION_DEPTH = 48
        private const val SKIRT_RADIUS = 12

        /** JigsawPlacement adds no pieces at all, not even the start piece, when maxDepth is 0. */
        private const val MAX_DEPTH = 1
        private const val MAX_DISTANCE_FROM_CENTER = 80

        val CODEC: Codec<EndCultTempleStructure> =
            RecordCodecBuilder.create<EndCultTempleStructure> { builder ->
                builder.group(
                    StructureSettings.CODEC.forGetter { it.mySettings },
                    StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter { it.pool },
                ).apply(builder, ::EndCultTempleStructure)
            }
    }
}
