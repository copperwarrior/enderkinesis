package org.shipwrights.enderkinesis.dimension

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.logging.LogUtils
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.decoration.PaintingVariant
import net.minecraft.world.entity.decoration.PaintingVariants
import net.minecraft.world.level.ChunkPos
import org.shipwrights.enderkinesis.registry.EKPoiTypes
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.WallSignBlock
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import org.shipwrights.enderkinesis.registry.EKItems
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.block.state.properties.WallSide
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender

/**
 * Sselith's Repertory chunk generator. Currently in **maze-only** mode — bookshelf
 * wireframe cubes are gated off via [ENABLE_CUBES] while the maze layout is being
 * iterated; the cube path / chain code paths remain ready to switch back on.
 *
 *  ## Maze layout
 *
 *  Space is divided into [MAZE_CELL_X] × [MAZE_CELL_Y] × [MAZE_CELL_Z] cells. Each cell
 *  has a [MAZE_DENSITY_PERCENT] % chance of holding a node — a 5×5 polished-deepslate
 *  floor at a random `(x, y, z)` within the cell. Each existing node connects to its
 *  `+X` and `+Z` cell neighbours **if** those neighbours also have nodes, producing a
 *  real graph rather than detached fragments.
 *
 *  ### Connection geometry
 *
 *  For A → B along axis `+X` (B east of A):
 *
 *  1. **X-leg** — polished-deepslate path at `Y = A.y`, `Z = A.z + 2`, running from
 *     A's east edge `(A.x + 5)` to the X column above B's floor centre `(B.x + 2)`.
 *  2. **Z-leg** — polished-deepslate path at `Y = A.y`, `X = B.x + 2`, bridging
 *     `Z = A.z + 2` to `Z = B.z + 2`.
 *  3. **Vertical leg** — `minecraft:ladder` column at `(B.x + 2, *, B.z + 2)` spanning
 *     `Y = A.y` (top, where Z-leg ends) down/up to `Y = B.y` (bottom, B's floor).
 *
 *  Same logic mirrored for `+Z`. Connections capped at [MAX_CONNECT_Y_DIFF] vertical
 *  blocks so ladders never run wildly long.
 *
 *  ## Path
 *
 *  A 2-wide polished-deepslate cross-path at Y=0 with a 1-block air gap on each side
 *  cuts through everything (priority above maze and cube). Floor of the path lies at
 *  the absolute Y=0; the void opens immediately below.
 *
 *  ## World extent
 *
 *  [WORLD_HEIGHT] = 256, [MIN_Y] = -128.
 */
class SselithRepertoryChunkGenerator(
    biomeSource: BiomeSource,
) : ChunkGenerator(biomeSource) {

    override fun codec(): Codec<out ChunkGenerator> = CODEC

    /** Per-chunk queue of `(pos, poiTypeHolder)` pairs to register with the
     *  dimension's `PoiManager`. Populated in [fillFromNoise] right after
     *  `chunk.setBlockState(...)` (direct ChunkAccess writes bypass
     *  `Level.onBlockStateChange`, the path that normally fires `PoiManager.add`).
     *  Flushed in [spawnOriginalMobs], which **defers the actual `poiManager.add`
     *  calls onto the server thread** via `MinecraftServer.execute` — same as
     *  vanilla's `ServerLevel.onBlockStateChange`, because `PoiManager` (and
     *  the underlying `SectionStorage.dirty` `LongLinkedOpenHashSet`) is NOT
     *  thread-safe. Mutating it from a chunk-gen worker thread corrupts the
     *  dirty set and the server's POI tick later crashes with
     *  `ArrayIndexOutOfBoundsException` inside `LongLinkedOpenHashSet.fixPointers`.
     *
     *  The `Holder<PoiType>` is captured at queue time (worker-safe — `PoiTypes
     *  .forState` is a `Map.get` against a populated static map) so the
     *  server-thread task doesn't need to re-read the block state. */
    private val pendingPoiRegistrations: java.util.concurrent.ConcurrentHashMap<
        ChunkPos,
        MutableList<Pair<BlockPos, net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType>>>
    > = java.util.concurrent.ConcurrentHashMap()

    override fun fillFromNoise(
        executor: Executor,
        blender: Blender,
        randomState: RandomState,
        structureManager: StructureManager,
        chunk: ChunkAccess,
    ): CompletableFuture<ChunkAccess> {
        // Per-chunk wall-time instrumentation — opt-in via JVM arg
        // `-Denderkinesis.chunkProfile=true`. Inlined here (not a
        // separate method) so it doesn't add a call frame to profiles
        // or defeat JIT inlining of the hot path below.
        val profileStart = if (CHUNK_PROFILE) System.nanoTime() else 0L
        val chunkX0 = chunk.pos.minBlockX
        val chunkZ0 = chunk.pos.minBlockZ
        val minY = chunk.minBuildHeight
        val maxY = chunk.maxBuildHeight
        val span = maxY - minY
        // Touch the WG heightmaps so ProtoChunk creates them — once they
        // exist on the chunk, ChunkAccess.setBlockState will update them
        // automatically (so we don't double-update from the inner loop).
        chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG)
        chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG)
        val mutable = BlockPos.MutableBlockPos()
        // Starting tileYMod / cellY at i=0 — both are stepped incrementally
        // inside the inner loop instead of computing Math.floorMod/floorDiv
        // every iteration.
        val startTileY = Math.floorMod(minY, MAZE_CELL_Y)
        val startCellY = Math.floorDiv(minY, MAZE_CELL_Y)

        // Pre-compute pass 1 (paths) for the chunk plus a 2-block border in
        // every horizontal direction. Pass 2 (outline) queries up to 2 cells
        // out, so this border keeps every query inside the cache → all those
        // path lookups become O(1) array reads instead of repeated maze
        // traversals.
        val cacheSize = 20  // 16 chunk + 2-block border on each side
        val cache = Array(cacheSize) { Array(cacheSize) { arrayOfNulls<BlockState>(span) } }
        val caches = ChunkCaches()
        activeCaches.set(caches)
        try {
            // Shared cell-window bounds for the node + skipMask caches.
            // ±3 cells beyond the chunk + 2-block border: paintChunkInto
            // iterates ±1 cells around the chunk, stairwellAllowed scans
            // ±2 cells around each painted cell, so worst-case
            // pickConnections reaches ±3 from the chunk. (Was ±2 before
            // the stairwellAllowed widening — sufficient for the old
            // ±1 stairwell scan, but undersized for the new ±2.)
            val cellMinX = Math.floorDiv(chunkX0 - 2 - GRID_OFFSET, MAZE_CELL_X) - 3
            val cellMaxX = Math.floorDiv(chunkX0 + 17 - GRID_OFFSET, MAZE_CELL_X) + 3
            val cellMinY = Math.floorDiv(minY, MAZE_CELL_Y) - 3
            val cellMaxY = Math.floorDiv(minY + span - 1, MAZE_CELL_Y) + 3
            val cellMinZ = Math.floorDiv(chunkZ0 - 2 - GRID_OFFSET, MAZE_CELL_Z) - 3
            val cellMaxZ = Math.floorDiv(chunkZ0 + 17 - GRID_OFFSET, MAZE_CELL_Z) + 3
            val nWx = cellMaxX - cellMinX + 1
            val nWy = cellMaxY - cellMinY + 1
            val nWz = cellMaxZ - cellMinZ + 1

            // Build the platformOriginX/Z caches first — both nodeCache
            // and skipMask call into them.
            run {
                val originX = IntArray(nWx) { i -> computePlatformOriginX(cellMinX + i) }
                val originZ = IntArray(nWz) { i -> computePlatformOriginZ(cellMinZ + i) }
                caches.originXCache = originX
                caches.originXBase = cellMinX
                caches.originZCache = originZ
                caches.originZBase = cellMinZ
            }

            // Lazy memos for the two heaviest pure-function helpers.
            // stairwellAllowed: shared by 3 paint sites for same (cell, vIdx).
            // connectionBBox: called from stairwellAllowed's inner loop
            // plus several paint sites — memoised result is a shared
            // read-only IntArray of 6 bounds.
            caches.stairwellAllowedCache = Long2ByteOpenHashMap()
            caches.connectionBBoxCache = Long2ObjectOpenHashMap()

            // Build the MazeNode? cache next — everything below
            // (skipMask, paintChunkInto, decor, lectern map…) calls
            // `mazeNodeAt` and benefits from a ready-built cache.
            run {
                val nodes = arrayOfNulls<MazeNode>(nWx * nWy * nWz)
                for (cy in 0 until nWy) {
                    for (cz in 0 until nWz) {
                        val rowBase = (cy * nWz + cz) * nWx
                        for (cx in 0 until nWx) {
                            nodes[rowBase + cx] = computeMazeNodeAt(
                                cellMinX + cx, cellMinY + cy, cellMinZ + cz,
                            )
                        }
                    }
                }
                caches.nodeCache = nodes
                caches.nodeBaseX = cellMinX
                caches.nodeBaseY = cellMinY
                caches.nodeBaseZ = cellMinZ
                caches.nodeWidthX = nWx
                caches.nodeWidthY = nWy
                caches.nodeWidthZ = nWz
            }

            // Build the skip-mask cache. computePickKey calls into
            // preferredStairwell / pathwaySkipMask which both go
            // through mazeNodeAt — so the nodeCache populated above
            // services them.
            run {
                val masks = IntArray(nWx * nWy * nWz)
                for (cy in 0 until nWy) {
                    for (cz in 0 until nWz) {
                        val rowBase = (cy * nWz + cz) * nWx
                        for (cx in 0 until nWx) {
                            masks[rowBase + cx] = computePickKey(
                                cellMinX + cx, cellMinY + cy, cellMinZ + cz,
                            )
                        }
                    }
                }
                caches.skipMaskCache = masks
                caches.skipMaskBaseX = cellMinX
                caches.skipMaskBaseY = cellMinY
                caches.skipMaskBaseZ = cellMinZ
                caches.skipMaskWidthX = nWx
                caches.skipMaskWidthY = nWy
                caches.skipMaskWidthZ = nWz
            }
            // Every eligible cell hosts a structure now: a rare one on
            // the [SSELITH_GLOBE_PERCENT]% gate, otherwise one of the
            // 7×7 normal platforms. Only cells with an upward connection
            // skip the structure (they fall through to the hardcoded
            // 5×5 platform paint). List is typically non-empty per
            // chunk; the per-position bitmap below makes lookups O(1).
            // Built once here, queried by decor + connection paint.
            run {
                val placements = ArrayList<StructurePlacement>(0)
                for (cellX in cellMinX..cellMaxX) {
                    for (cellY in cellMinY..cellMaxY) {
                        for (cellZ in cellMinZ..cellMaxZ) {
                            val type = replacementTypeAt(cellX, cellY, cellZ) ?: continue
                            val node = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                            placements.add(StructurePlacement(node, type))
                        }
                    }
                }
                // OBSERVATORY placements come from this dedicated
                // (cellX, cellZ) scan, not the per-cell main loop.
                // Two reasons: (1) the column's bottom cellY can be
                // anywhere from −5 to TOP_CELL_Y-1, so the cellMinY
                // window almost never covers ALL columns' bottoms; (2)
                // the observatory's 244-block vertical bbox covers
                // chunks far above the bottom cell that wouldn't see
                // cellY=bottom in their main-scan range. Adding the
                // placement here keeps every chunk's observatory
                // bitmap / paint consistent regardless of where the
                // chunk sits in the Y axis.
                for (cellX in cellMinX..cellMaxX) {
                    for (cellZ in cellMinZ..cellMaxZ) {
                        if (!hasObservatoryColumnAt(cellX, cellZ)) continue
                        val bottomY = observatoryBottomCellY(cellX, cellZ) ?: continue
                        // `bottomY` is the GLOBAL minimum cellY, not the
                        // column's own lowest node — so `mazeNodeAt(cellX,
                        // bottomY, cellZ)` may be null when this column
                        // happens to start its platform stack a few cellYs
                        // higher. Look up ANY node in the column to copy
                        // the platform origin's `nx` / `nz`, then build a
                        // synthetic anchor at `bottomY * MAZE_CELL_Y`.
                        var sourceNode: MazeNode? = null
                        for (cy in bottomY until TOP_CELL_Y) {
                            val n = mazeNodeAt(cellX, cy, cellZ) ?: continue
                            sourceNode = n
                            break
                        }
                        if (sourceNode == null) continue
                        val anchor = MazeNode(
                            sourceNode.nx, bottomY * MAZE_CELL_Y, sourceNode.nz, 0,
                        )
                        placements.add(StructurePlacement(anchor, StructureReplacement.OBSERVATORY))
                    }
                }
                caches.replacementsInRange = placements
                // Top-cellY axial-arch NBT placements. Enumerated BEFORE
                // the structure-coverage bitmap below so we can fold each
                // arch's footprint into [structInsideBits] in the same
                // pass — that way [isPathFloor] returns false at NBT
                // material positions and the maze decor pass doesn't
                // paint procedural wall outlines / slab outlines / lecterns
                // on top of the NBT's own walls + slabs + lamps. Mirrors
                // the [gapArchwayBlock] parity gate so we never list a
                // column the procedural pass wouldn't fire on; sweeps the
                // chunk's full cache window so an arch whose 1-block
                // perpendicular position lands at the cache border is
                // still picked up.
                val axialList = ArrayList<AxialArchPlacement>(0)
                run {
                    val archX0 = chunkX0 - 2
                    val archX1 = chunkX0 + 17
                    val archZ0 = chunkZ0 - 2
                    val archZ1 = chunkZ0 + 17
                    val spanZLo = GAP_COLUMN_NEG_Z
                    val spanZHi = GAP_COLUMN_NEG_Z + AXIAL_ARCH_DIM_Z - 1
                    if (spanZHi >= archZ0 && spanZLo <= archZ1) {
                        for (wx in archX0..archX1) {
                            if (wx in PATH_RANGE || wx == 0) continue
                            if (positiveMod(wx, GAP_COLUMN_INTERVAL) != 0) continue
                            val cellY = topAxialArchCellY(wx) ?: continue
                            axialList.add(AxialArchPlacement(
                                alongAxisIsZ = true, archCoord = wx, cellY = cellY,
                            ))
                        }
                    }
                    val spanXLo = GAP_COLUMN_NEG_X
                    val spanXHi = GAP_COLUMN_NEG_X + AXIAL_ARCH_DIM_Z - 1
                    if (spanXHi >= archX0 && spanXLo <= archX1) {
                        for (wz in archZ0..archZ1) {
                            if (wz in PATH_RANGE || wz == 0) continue
                            if (positiveMod(wz, GAP_COLUMN_INTERVAL) != 0) continue
                            val cellY = topAxialArchCellY(wz) ?: continue
                            axialList.add(AxialArchPlacement(
                                alongAxisIsZ = false, archCoord = wz, cellY = cellY,
                            ))
                        }
                    }
                    caches.axialArchesInRange = axialList
                }
                // Bitmap window dims — populated unconditionally so every
                // bitmap consumer ([isInsideStructureReplacement],
                // [isInsideRingDecoration], [isInsidePathOverlay]) reads
                // the same base / width / span regardless of which bits
                // are actually allocated for this chunk.
                caches.structBitsBaseX = chunkX0 - 2
                caches.structBitsBaseY = minY
                caches.structBitsBaseZ = chunkZ0 - 2
                caches.structBitsWidthX = cacheSize
                caches.structBitsWidthY = span
                caches.structBitsWidthZ = cacheSize
                // Build per-chunk structure-coverage bitmaps so
                // [isInsideStructureReplacement] / [isStructureReplacement
                // OuterWall] become single bit-test lookups instead of
                // iterating placements + 6 comparisons per query. Cover
                // the chunk's cache window (cacheSize × span × cacheSize)
                // — every block-position the paint pass might ask about
                // lives inside this window.
                if (placements.isNotEmpty() || axialList.isNotEmpty()) {
                    val bitsBaseX = chunkX0 - 2
                    val bitsBaseY = minY
                    val bitsBaseZ = chunkZ0 - 2
                    val bitsWidthX = cacheSize
                    val bitsWidthY = span
                    val bitsWidthZ = cacheSize
                    val nBits = bitsWidthX * bitsWidthY * bitsWidthZ
                    val insideBits = java.util.BitSet(nBits)
                    val outerBits = java.util.BitSet(nBits)
                    for (placement in placements) {
                        val node = placement.node
                        val type = placement.type
                        val cornerX = node.nx + type.cornerOffsetX
                        val cornerZ = node.nz + type.cornerOffsetZ
                        val cornerY = node.ny + type.cornerOffsetY
                        val sx = maxOf(cornerX, bitsBaseX)
                        val sy = maxOf(cornerY, bitsBaseY)
                        val sz = maxOf(cornerZ, bitsBaseZ)
                        val ex = minOf(cornerX + type.dimX, bitsBaseX + bitsWidthX)
                        val ey = minOf(cornerY + type.dimY, bitsBaseY + bitsWidthY)
                        val ez = minOf(cornerZ + type.dimZ, bitsBaseZ + bitsWidthZ)
                        if (sx >= ex || sy >= ey || sz >= ez) continue
                        // Outer wall: the four side faces (perpendicular to
                        // X and Z), Y boundaries excluded — matches
                        // [isStructureReplacementOuterWall]'s semantics.
                        val outerXLo = cornerX
                        val outerXHi = cornerX + type.dimX - 1
                        val outerZLo = cornerZ
                        val outerZHi = cornerZ + type.dimZ - 1
                        for (wx in sx until ex) {
                            val lx = wx - bitsBaseX
                            for (wy in sy until ey) {
                                val ly = wy - bitsBaseY
                                val rowBase = (lx * bitsWidthY + ly) * bitsWidthZ
                                for (wz in sz until ez) {
                                    val lz = wz - bitsBaseZ
                                    val idx = rowBase + lz
                                    insideBits.set(idx)
                                    if (wx == outerXLo || wx == outerXHi ||
                                        wz == outerZLo || wz == outerZHi
                                    ) outerBits.set(idx)
                                }
                            }
                        }
                    }
                    // Axial-arch NBT footprints share the inside-bitmap so
                    // [isPathFloor] returns false at NBT positions —
                    // without this, the maze decor pass sees NBT material
                    // (deepslate_tiles / slabs / stairs / walls / lamps)
                    // as path floor and paints procedural wall outlines +
                    // slab outlines + lectern decor on top of the NBT's
                    // own walls / lamps / chain. Footprint dims mirror
                    // [paintAxialArchInto]: 1 along the arch's
                    // perpendicular axis, AXIAL_ARCH_DIM_Z along the long
                    // axis, AXIAL_ARCH_DIM_Y in Y. NOT added to
                    // [outerBits] — the axial arch has no outer-wall
                    // concept (it's not a cell-replacement).
                    for (arch in axialList) {
                        val cornerY = arch.cellY * MAZE_CELL_Y + AXIAL_ARCH_CELL_Y_OFFSET
                        val cornerX: Int
                        val cornerZ: Int
                        val dimX: Int
                        val dimZ: Int
                        if (arch.alongAxisIsZ) {
                            cornerX = arch.archCoord
                            cornerZ = GAP_COLUMN_NEG_Z
                            dimX = AXIAL_ARCH_DIM_X
                            dimZ = AXIAL_ARCH_DIM_Z
                        } else {
                            cornerX = GAP_COLUMN_NEG_X
                            cornerZ = arch.archCoord
                            dimX = AXIAL_ARCH_DIM_Z
                            dimZ = AXIAL_ARCH_DIM_X
                        }
                        val sx = maxOf(cornerX, bitsBaseX)
                        val sy = maxOf(cornerY, bitsBaseY)
                        val sz = maxOf(cornerZ, bitsBaseZ)
                        val ex = minOf(cornerX + dimX, bitsBaseX + bitsWidthX)
                        val ey = minOf(cornerY + AXIAL_ARCH_DIM_Y, bitsBaseY + bitsWidthY)
                        val ez = minOf(cornerZ + dimZ, bitsBaseZ + bitsWidthZ)
                        if (sx >= ex || sy >= ey || sz >= ez) continue
                        for (wx in sx until ex) {
                            val lx = wx - bitsBaseX
                            for (wy in sy until ey) {
                                val ly = wy - bitsBaseY
                                val rowBase = (lx * bitsWidthY + ly) * bitsWidthZ
                                for (wz in sz until ez) {
                                    insideBits.set(rowBase + (wz - bitsBaseZ))
                                }
                            }
                        }
                    }
                    caches.structInsideBits = insideBits
                    caches.structOuterWallBits = outerBits
                }

                // Corridor coverage bitmap — built FIRST (before the ring
                // bits and before paint) so the ring paint can refuse
                // to mark / write at positions a corridor will run
                // through. This is the only way to keep rings and
                // paths physically isolated: corridors would otherwise
                // punch through the ring, leaving "doorways" into the
                // ring's interior and confusing the carpet / wall
                // logic at the gap positions.
                run {
                    val bitsBaseX = chunkX0 - 2
                    val bitsBaseY = minY
                    val bitsBaseZ = chunkZ0 - 2
                    val bitsWidthX = cacheSize
                    val bitsWidthY = span
                    val bitsWidthZ = cacheSize
                    val nBits = bitsWidthX * bitsWidthY * bitsWidthZ
                    val corridorBits = java.util.BitSet(nBits)
                    var anyCorridor = false
                    for (cellX in cellMinX..cellMaxX) {
                        for (cellY in cellMinY..cellMaxY) {
                            for (cellZ in cellMinZ..cellMaxZ) {
                                val a = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                                for (vIdx in pickConnections(cellX, cellY, cellZ)) {
                                    val v = CONN_VECTORS[vIdx]
                                    val nCellX = cellX + v.dx
                                    val nCellY = cellY + v.dy
                                    val nCellZ = cellZ + v.dz
                                    val b = mazeNodeAt(nCellX, nCellY, nCellZ) ?: continue
                                    // Mirror [paintConnectionPick]'s skip rules
                                    // exactly — without these, corridorBits
                                    // marks bboxes for connections that the
                                    // procedural paint silently drops, cutting
                                    // rings where no corridor actually exists.
                                    if (isStairwellVec(vIdx) && !stairwellAllowed(cellX, cellY, cellZ, vIdx)) continue
                                    if (v.dy != 0 &&
                                        (isStructureReplacementNode(cellX, cellY, cellZ) ||
                                         isStructureReplacementNode(nCellX, nCellY, nCellZ))
                                    ) continue
                                    val bbox = connectionBBoxCtx(caches, cellX, cellY, cellZ, vIdx)
                                    // Narrow per-painted-block marking
                                    // only sets the floor center cell —
                                    // for 1-wide corridors that leaves
                                    // the two wall outlines at Z =
                                    // corridorZ ± 1 (or X = corridorX ±
                                    // 1) unmarked, so rings paint right
                                    // where the decor pass wants to lay
                                    // slabs / walls / totems / lanterns
                                    // and the ring blocks survive on
                                    // top. Expand perpendicular by 1
                                    // for both widths: 1-wide gets its
                                    // walls covered, 3-wide gets its
                                    // 5-wide claim (3 floor + 1 wall
                                    // each side) covered same as before.
                                    val expandX = if (v.dx == 0 && v.dz != 0) 1 else 0
                                    val expandZ = if (v.dx != 0 && v.dz == 0) 1 else 0
                                    val sx = maxOf(bbox[0] - expandX, bitsBaseX)
                                    val sy = maxOf(bbox[2], bitsBaseY)
                                    val sz = maxOf(bbox[4] - expandZ, bitsBaseZ)
                                    val ex = minOf(bbox[1] + 1 + expandX, bitsBaseX + bitsWidthX)
                                    val ey = minOf(bbox[3] + 1, bitsBaseY + bitsWidthY)
                                    val ez = minOf(bbox[5] + 1 + expandZ, bitsBaseZ + bitsWidthZ)
                                    if (sx >= ex || sy >= ey || sz >= ez) continue
                                    // Walk the bbox, but only mark
                                    // positions where the connection
                                    // paint pass would actually place a
                                    // block (floor / stair tread /
                                    // undertread / ladder column) PLUS
                                    // the two blocks of player headroom
                                    // above each painted block. The
                                    // previous all-cells fill marked
                                    // the entire bbox volume; the bare
                                    // `connectionBlockForVector == null
                                    // → skip` version went the other
                                    // way and missed the headroom
                                    // entirely, so ring decorations
                                    // could land in the 2-block air
                                    // gap above a stair tread and
                                    // block the player from walking up
                                    // the flight. Marking
                                    // `Y..Y+2` per painted block
                                    // restores the traversability
                                    // without re-flooding the
                                    // headroom of the *whole* bbox.
                                    // Per painted block, mark:
                                    //   - that block's (X, Y, Z) and
                                    //     `headroom` Y blocks above
                                    //     (covers player headroom on
                                    //     stair treads / floor cells).
                                    //   - perpendicular ±expand
                                    //     (`expand = 1` for 3-wide
                                    //     corridors) — covers the
                                    //     1-block-thick wall outline
                                    //     the decor pass paints
                                    //     flanking the 3-wide floor.
                                    // Stair treads need 3 Y of
                                    // headroom (auto-jump from a
                                    // lower tread momentarily lifts
                                    // the player's head one block
                                    // higher than the tread's
                                    // standing head block), pathway
                                    // floors only need 2.
                                    val isStairConn = isStairwellVec(vIdx)
                                    val headroom = if (isStairConn) 3 else 2
                                    for (wx in sx until ex) {
                                        for (wy in sy until ey) {
                                            for (wz in sz until ez) {
                                                if (connectionBlockForVector(wx, wy, wz, a, b, v) == null) continue
                                                val lx = wx - bitsBaseX
                                                val lz = wz - bitsBaseZ
                                                val yClampHi = minOf(wy + headroom, bitsBaseY + bitsWidthY - 1)
                                                for (yy in wy..yClampHi) {
                                                    val ly = yy - bitsBaseY
                                                    for (dlx in -expandX..expandX) {
                                                        val nlx = lx + dlx
                                                        if (nlx !in 0 until bitsWidthX) continue
                                                        for (dlz in -expandZ..expandZ) {
                                                            val nlz = lz + dlz
                                                            if (nlz !in 0 until bitsWidthZ) continue
                                                            corridorBits.set(
                                                                (nlx * bitsWidthY + ly) * bitsWidthZ + nlz,
                                                            )
                                                        }
                                                    }
                                                }
                                                anyCorridor = true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (anyCorridor) caches.corridorBits = corridorBits
                }

                // Ring coverage bitmap — built AFTER corridor bits so ring
                // positions a corridor will run through stay UNMARKED
                // (and unpainted by the ring pass). Otherwise corridors
                // visually punch through rings, creating openings into
                // ring territory and breaking the carpet / wall logic
                // at the gap positions. Marks ONLY positions where the
                // loaded ring NBT actually places a block; air interior
                // and corridor-occupied cells stay unset.
                run {
                    val bitsBaseX = chunkX0 - 2
                    val bitsBaseY = minY
                    val bitsBaseZ = chunkZ0 - 2
                    val bitsWidthX = cacheSize
                    val bitsWidthY = span
                    val bitsWidthZ = cacheSize
                    val nBits = bitsWidthX * bitsWidthY * bitsWidthZ
                    val ringBits = java.util.BitSet(nBits)
                    var anyRing = false
                    for (cellX in cellMinX..cellMaxX) {
                        for (cellY in cellMinY..cellMaxY) {
                            for (cellZ in cellMinZ..cellMaxZ) {
                                val node = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                                val roll = positiveMod(
                                    mazeHash(cellX, cellY, cellZ, RING_TYPE_HASH_SALT), RING_STYLE_COUNT,
                                )
                                if (roll < RingDecoration.entries.size) {
                                    val type = RingDecoration.entries[roll]
                                    val blocks = RING_BLOCKS[type] ?: continue
                                    val cornerX = node.nx + type.cornerOffsetX
                                    val cornerY = node.ny
                                    val cornerZ = node.nz + type.cornerOffsetZ
                                    val sx = maxOf(cornerX, bitsBaseX)
                                    val sy = maxOf(cornerY, bitsBaseY)
                                    val sz = maxOf(cornerZ, bitsBaseZ)
                                    val ex = minOf(cornerX + type.dimX, bitsBaseX + bitsWidthX)
                                    val ey = minOf(cornerY + type.dimY, bitsBaseY + bitsWidthY)
                                    val ez = minOf(cornerZ + type.dimZ, bitsBaseZ + bitsWidthZ)
                                    if (sx >= ex || sy >= ey || sz >= ez) continue
                                    for (wx in sx until ex) {
                                        val dx = wx - cornerX
                                        val lx = wx - bitsBaseX
                                        val xPlane = blocks[dx]
                                        for (wz in sz until ez) {
                                            val dz = wz - cornerZ
                                            val lz = wz - bitsBaseZ
                                            for (wy in sy until ey) {
                                                val dy = wy - cornerY
                                                if (xPlane[dy][dz] == null) continue
                                                // Refuse to mark / paint where a
                                                // corridor will run — keep rings
                                                // and paths physically isolated.
                                                if (isInsideCorridorBbox(wx, wy, wz)) continue
                                                val ly = wy - bitsBaseY
                                                ringBits.set((lx * bitsWidthY + ly) * bitsWidthZ + lz)
                                                anyRing = true
                                            }
                                        }
                                    }
                                } else {
                                    // Procedural ring — mark slab + froglight
                                    // positions across the maze cell's 49×49
                                    // floor footprint at node.ny.
                                    val cellOriginX = node.nx - GRID_OFFSET
                                    val cellOriginZ = node.nz - GRID_OFFSET
                                    val wy = node.ny
                                    if (wy !in bitsBaseY until bitsBaseY + bitsWidthY) continue
                                    val ly = wy - bitsBaseY
                                    val maxCell = MAZE_CELL_X - 1
                                    for (cx in 0..maxCell) {
                                        val wx = cellOriginX + cx
                                        if (wx !in bitsBaseX until bitsBaseX + bitsWidthX) continue
                                        val edgeX = minOf(cx, maxCell - cx)
                                        val lx = wx - bitsBaseX
                                        for (cz in 0..maxCell) {
                                            val wz = cellOriginZ + cz
                                            if (wz !in bitsBaseZ until bitsBaseZ + bitsWidthZ) continue
                                            val edgeZ = minOf(cz, maxCell - cz)
                                            val edge = minOf(edgeX, edgeZ)
                                            val hasBlock = (edgeX == LIBRARY_FROGLIGHT_INSET && edgeZ == LIBRARY_FROGLIGHT_INSET) ||
                                                edge == 3 || edge == 8 || edge == 13
                                            if (!hasBlock) continue
                                            // Skip the slot where a corridor will run.
                                            if (isInsideCorridorBbox(wx, wy, wz)) continue
                                            val lz = wz - bitsBaseZ
                                            ringBits.set((lx * bitsWidthY + ly) * bitsWidthZ + lz)
                                            anyRing = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (anyRing) caches.ringInsideBits = ringBits
                }
            }
            // Cell-centric paint: enumerate maze cells touching the cache window and paint
            // each cell's geometry directly into the cache. Path cache stays null during
            // paint so paintChunkInto's own helpers don't accidentally read a half-built
            // window.
            paintChunkInto(cache, chunkX0 - 2, chunkZ0 - 2, minY, cacheSize, span)
            // Enable cache; subsequent pathBlockAt calls (from decorBlockAt /
            // isWallOutline) hit it.
            caches.pathCache = cache
            caches.pathBaseX = chunkX0 - 2
            caches.pathBaseZ = chunkZ0 - 2
            caches.pathBaseY = minY
            caches.pathSpan = span

            // Per-platform wall lecterns — precomputed once into a Long-
            // keyed map so decorBlockAt's lectern queries (one self-check
            // per wall position via [lecternWallAt]) are O(1) hash
            // lookups instead of cell-window scans each.
            caches.lecterns = buildLecternMap(chunkX0, chunkZ0, minY, span)

            // NBT path SALTING — runs BEFORE the decor pass so [decorBlockAt]'s
            // [isPathFloor] check sees salt floors and fires wall decor
            // above them (the user's pipeline: rings → platforms → paths
            // → custom paths → wall decorations on those blocks). Each
            // salt tile only commits if its full footprint is clear of
            // platforms, cell walls, structure replacements, the axial
            // strip, and any stair / ladder / trapdoor already placed
            // by the procedural pathway pass.
            run {
                val baseX = chunkX0 - 2
                val baseZ = chunkZ0 - 2
                val maxX = baseX + cacheSize - 1
                val maxY = minY + span - 1
                val maxZ = baseZ + cacheSize - 1
                val minCellX = Math.floorDiv(baseX - GRID_OFFSET, MAZE_CELL_X) - 1
                val maxCellX = Math.floorDiv(maxX - GRID_OFFSET, MAZE_CELL_X) + 1
                val minCellY = Math.floorDiv(minY, MAZE_CELL_Y) - 1
                val maxCellY = Math.floorDiv(maxY, MAZE_CELL_Y) + 1
                val minCellZ = Math.floorDiv(baseZ - GRID_OFFSET, MAZE_CELL_Z) - 1
                val maxCellZ = Math.floorDiv(maxZ - GRID_OFFSET, MAZE_CELL_Z) + 1
                for (cellX in minCellX..maxCellX) {
                    for (cellY in minCellY..maxCellY) {
                        for (cellZ in minCellZ..maxCellZ) {
                            for (vIdx in pickConnections(cellX, cellY, cellZ)) {
                                paintPathSaltInto(cache, baseX, baseZ, minY, cacheSize, span,
                                    cellX, cellY, cellZ, vIdx)
                            }
                        }
                    }
                }
            }

            // Pre-compute decor (slabs / walls / lanterns) into a separate
            // cache so the cube pass can test against wall outlines and the
            // column loop can avoid recomputing decor per chunk column.
            val decorCache = buildDecorCache(
                cache, chunkX0 - 2, chunkZ0 - 2, minY, cacheSize, span,
            )

            // Wall block update — walks every WallBlock in pathCache
            // and re-derives east/west/north/south/up from actual cache
            // neighbours.
            recomputeAllWalls(cache, chunkX0 - 2, chunkZ0 - 2, minY, cacheSize, span)

            // Frame each pathway/stairwell opening through a cell wall with
            // a 5-tall doorway (deepslate-tile jambs + polished-deepslate
            // slab lintel). Lives in its own cache so it overrides the
            // outline decor at the wall but doesn't trigger headroom
            // clearing above the slab.
            val frameCache = buildWallFrameCache(
                cache, chunkX0 - 2, chunkZ0 - 2, minY, cacheSize, span,
            )

            // Decorative library features (floating shelves, towers, walls,
            // furniture islands, reading nooks, chandeliers, tome shrines,
            // wall-corners, spiral pillars, lectern gardens, totems). Stored
            // in a separate cache; anchor-in-column ownership means each
            // feature is painted by exactly one chunk and fits inside its
            // column — no slicing.
            val featureCache: Array<Array<Array<BlockState?>>>? =
                if (ENABLE_FEATURES) {
                    val exclBaseX = chunkX0 - CUBE_EXCL_MARGIN
                    val exclBaseZ = chunkZ0 - CUBE_EXCL_MARGIN
                    val excl = buildCubeExclude(exclBaseX, exclBaseZ, minY, CUBE_EXCL_SIZE, span)
                    val fc = Array(cacheSize) { Array(cacheSize) { arrayOfNulls<BlockState>(span) } }
                    paintFeaturesInto(
                        fc, excl, exclBaseX, exclBaseZ, CUBE_EXCL_SIZE,
                        chunkX0 - 2, chunkZ0 - 2, minY, cacheSize, span, chunkX0, chunkZ0,
                    )
                    fc
                } else null

            for (localX in 0..15) {
                for (localZ in 0..15) {
                    val worldX = chunkX0 + localX
                    val worldZ = chunkZ0 + localZ
                    val onStrip = isPathStrip(worldX, worldZ)

                    val pathColumn = cache[localX + 2][localZ + 2]
                    val decorColumn = decorCache[localX + 2][localZ + 2]
                    val featureColumn = featureCache?.get(localX + 2)?.get(localZ + 2)
                    val frameColumn = frameCache[localX + 2][localZ + 2]
                    // Default fill: every position not claimed by a
                    // path/decor/feature reads from LIBRARY_CELL — the
                    // 49×24×49 procedural pattern that matches our maze cell
                    // grid (a cell hosts exactly one of our platforms at its
                    // floor). Tiles infinitely via Math.floorMod. Axial
                    // strip and path-headroom positions override the cell to
                    // AIR so corridors stay walkable.
                    //
                    // Library quadrants are shifted [LIBRARY_QUADRANT_SHIFT]
                    // blocks away from the origin on each axis, leaving an
                    // empty band immediately past the axial paths.
                    // Positive-side shift subtracts one EXTRA block so the
                    // gap-facing cell wall (tileXMod=0 / tileZMod=0) lands
                    // on the first out-of-gap worldX/Z instead of inside
                    // the gap. The negative side already exposes its wall
                    // naturally because effX=-1 wraps to tileXMod=maxX
                    // (the east/south wall position) via floorMod.
                    val effX = when {
                        worldX > LIBRARY_QUADRANT_SHIFT -> worldX - LIBRARY_QUADRANT_SHIFT - 1
                        worldX < -LIBRARY_QUADRANT_SHIFT -> worldX + LIBRARY_QUADRANT_SHIFT
                        else -> 0
                    }
                    val effZ = when {
                        worldZ > LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT ->
                            worldZ - LIBRARY_QUADRANT_SHIFT - POSITIVE_Z_EXTRA_SHIFT - 1
                        worldZ < -LIBRARY_QUADRANT_SHIFT -> worldZ + LIBRARY_QUADRANT_SHIFT
                        else -> 0
                    }
                    val inLibraryGap =
                        worldX in -LIBRARY_QUADRANT_SHIFT..LIBRARY_QUADRANT_SHIFT ||
                            worldZ in -LIBRARY_QUADRANT_SHIFT..(LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT)
                    val tileXMod = Math.floorMod(effX, MAZE_CELL_X)
                    val tileZMod = Math.floorMod(effZ, MAZE_CELL_Z)
                    val edgeX = Math.min(tileXMod, MAZE_CELL_X - 1 - tileXMod)
                    val edgeZ = Math.min(tileZMod, MAZE_CELL_Z - 1 - tileZMod)
                    val libCellX = Math.floorDiv(effX, MAZE_CELL_X)
                    val libCellZ = Math.floorDiv(effZ, MAZE_CELL_Z)
                    var tileYMod = startTileY
                    var cellY = startCellY
                    for (i in 0 until span) {
                        // Read from caches inline — no temp column array.
                        var block: BlockState? = pathColumn[i]
                        if (block == null) block = frameColumn[i]
                        if (block == null) block = decorColumn[i]
                        if (block == null && featureColumn != null) block = featureColumn[i]
                        val skipTopCell = cellY >= TOP_CELL_Y
                        if (block == null) {
                            // Headroom above a path block: 2 blocks for any
                            // walkable path, plus 1 extra block above stair
                            // blocks so an ascending stairwell has enough
                            // clearance for the player's jump arc + head
                            // (and so library/wall blocks don't crowd the
                            // stair from above).
                            val headroom = (i >= 1 && pathColumn[i - 1] != null) ||
                                (i >= 2 && pathColumn[i - 2] != null) ||
                                (i >= 3 && pathColumn[i - 3]?.`is`(Blocks.POLISHED_DEEPSLATE_STAIRS) == true)
                            block = when {
                                headroom || skipTopCell -> AIR
                                onStrip ->
                                    archChainFroglightAt(worldX, minY + i, worldZ, cellY)
                                        ?: gapArchwayBlock(worldX, tileYMod, worldZ, cellY)
                                        ?: AIR
                                inLibraryGap ->
                                    if (isGapColumn(worldX, worldZ)) DEEPSLATE_TILES
                                    else archChainFroglightAt(worldX, minY + i, worldZ, cellY)
                                        ?: gapArchwayBlock(worldX, tileYMod, worldZ, cellY)
                                        ?: AIR
                                else -> LIBRARY_CELL[tileYMod][tileZMod][tileXMod]
                                    ?: archwayLadderAt(libCellX, cellY, libCellZ, tileXMod, tileYMod, tileZMod)
                            }
                        }
                        // Carpet runner on corridor centerlines (yellow/white
                        // 2-2-2 pattern). Only places where this position is
                        // empty AND the block immediately below is a
                        // POLISHED_DEEPSLATE corridor floor (not a stair, not
                        // a platform). SUPPRESSED:
                        //   * inside any structure-replacement footprint,
                        //   * one block above one (so polished-deepslate roofs
                        //     don't get carpeted — [carpetBlockAt]'s
                        //     DEEPSLATE_TILES sentinel doesn't fire on roof
                        //     palettes),
                        //   * one block above a ring decoration (ring NBTs
                        //     occasionally use full polished-deepslate which
                        //     would otherwise trigger the runner on top).
                        // Skip carpet if the position or the block below is
                        // inside a ring, structure replacement, or salt
                        // footprint — those regions own their own (or no)
                        // floor decor.
                        if ((block == null || block === AIR) &&
                            !isInsideStructureReplacement(worldX, minY + i, worldZ) &&
                            !isInsideStructureReplacement(worldX, minY + i - 1, worldZ) &&
                            !isInsideRingDecoration(worldX, minY + i - 1, worldZ) &&
                            !isInsideSalt(worldX, minY + i, worldZ) &&
                            !isInsideSalt(worldX, minY + i - 1, worldZ)
                        ) {
                            val carpet = carpetBlockAt(
                                cache, chunkX0 - 2, chunkZ0 - 2, minY, cacheSize, span,
                                worldX, minY + i, worldZ,
                            )
                            if (carpet != null) block = carpet
                        }
                        if (block == null || block === AIR) {
                            // Step the cell-Y counters even on skip so they
                            // stay in sync with `i`.
                            tileYMod++
                            if (tileYMod == MAZE_CELL_Y) { tileYMod = 0; cellY++ }
                            continue
                        }
                        val y = minY + i
                        mutable.set(worldX, y, worldZ)
                        // setBlockState updates every heightmap registered on
                        // the chunk (incl. OCEAN_FLOOR_WG and WORLD_SURFACE_WG
                        // primed above), so we don't manually update them.
                        chunk.setBlockState(mutable, block, false)
                        if (block.block in EKPoiTypes.POI_TARGET_BLOCK_SET) {
                            // Resolve the POI holder on the worker thread — `PoiTypes
                            // .forState` is a static `Map.get`, safe to call concurrently.
                            // Captured here so the deferred server-thread task in
                            // `spawnOriginalMobs` doesn't need to re-read the block state.
                            PoiTypes.forState(block).ifPresent { holder ->
                                pendingPoiRegistrations
                                    .computeIfAbsent(chunk.pos) { ArrayList() }
                                    .add(mutable.immutable() to holder)
                            }
                        }
                        if (block.`is`(Blocks.BAMBOO_WALL_SIGN)) {
                            attachFloorSign(chunk, mutable, block, y)
                        }
                        tileYMod++
                        if (tileYMod == MAZE_CELL_Y) { tileYMod = 0; cellY++ }
                    }
                }
            }
            // Apply any block-entity NBT the structure replacements carry
            // (lectern books, banner patterns, sign text, etc.). The main
            // loop above only writes block STATES; without this pass the
            // BEs are auto-created with default fields, and the user sees
            // "broken until block update" — empty lecterns, blank signs.
            applyStructureBlockEntities(chunk)
        } finally {
            // Always clear so the worker thread doesn't carry a stale
            // reference to this chunk's caches into the next job.
            activeCaches.remove()
            if (CHUNK_PROFILE) recordChunkProfile(System.nanoTime() - profileStart)
        }

        return CompletableFuture.completedFuture(chunk)
    }

    /** Accumulate one chunk's wall-time into the global profiler and
     *  log a rolling histogram every [CHUNK_PROFILE_LOG_INTERVAL]
     *  chunks. Lock-free (atomics) since C2ME calls fillFromNoise from
     *  many worker threads at once. Only invoked when [CHUNK_PROFILE]
     *  is enabled. */
    private fun recordChunkProfile(nanos: Long) {
        PROF_NANOS.addAndGet(nanos)
        // max via CAS loop
        var curMax = PROF_MAX_NANOS.get()
        while (nanos > curMax && !PROF_MAX_NANOS.compareAndSet(curMax, nanos)) {
            curMax = PROF_MAX_NANOS.get()
        }
        // log-ish bucket by milliseconds: <0.5, <1, <2, <4, <8, <16, <32, ≥32
        val ms = nanos / 1_000_000.0
        val bucket = when {
            ms < 0.5 -> 0; ms < 1.0 -> 1; ms < 2.0 -> 2; ms < 4.0 -> 3
            ms < 8.0 -> 4; ms < 16.0 -> 5; ms < 32.0 -> 6; else -> 7
        }
        PROF_BUCKETS.incrementAndGet(bucket)
        val n = PROF_CHUNKS.incrementAndGet()
        if (n % CHUNK_PROFILE_LOG_INTERVAL == 0L) {
            val totalNanos = PROF_NANOS.get()
            val maxMs = PROF_MAX_NANOS.get() / 1_000_000.0
            val meanMs = (totalNanos.toDouble() / n) / 1_000_000.0
            val hist = (0 until 8).joinToString(" ") { PROF_BUCKETS.get(it).toString() }
            PROFILE_LOG.info(
                "[chunkProfile] chunks={} mean={}ms max={}ms " +
                    "buckets[<.5 <1 <2 <4 <8 <16 <32 >=32]={}",
                n,
                String.format("%.3f", meanMs),
                String.format("%.2f", maxMs),
                hist,
            )
        }
    }

    // C2ME runs `fillFromNoise` on many chunks concurrently against the same
    // ChunkGenerator instance. Per-chunk scratch must NOT live on the generator — that
    // clobbers across worker threads and lets thread A's helpers read thread B's chunk
    // window (silent corruption + occasional AIOOBE / NPE).
    //
    // [ChunkCaches] bundles the path block cache, its base/span, the
    // pre-computed pick-connections skip mask, and its base/width. The
    // ThreadLocal is set on `fillFromNoise` entry and **always removed
    // on exit** so worker threads don't leak references to old chunks.
    //
    // Helpers that need the cache (`pathBlockAt`, `pickConnections`)
    // read the ThreadLocal once into a local val at the top of the
    // method, so the actual hot-path is a couple of field reads on a
    // stack-local object — no synchronisation, no allocation.
    private val activeCaches: ThreadLocal<ChunkCaches?> = ThreadLocal()

    /** Per-chunk scratch passed between cache-aware helpers via the
     *  [activeCaches] ThreadLocal. `var pathCache` because it's null
     *  during the path-build itself (pre-paint) and populated after. */
    private class ChunkCaches {
        var pathCache: Array<Array<Array<BlockState?>>>? = null
        var pathBaseX: Int = 0
        var pathBaseZ: Int = 0
        var pathBaseY: Int = 0
        var pathSpan: Int = 0

        var skipMaskCache: IntArray? = null
        var skipMaskBaseX: Int = 0
        var skipMaskBaseY: Int = 0
        var skipMaskBaseZ: Int = 0
        var skipMaskWidthX: Int = 0
        var skipMaskWidthY: Int = 0
        var skipMaskWidthZ: Int = 0

        /** Map of `packLecternKey(wx, wy, wz)` → lectern BlockState for
         *  every per-platform wall lectern in the chunk's decor window
         *  (chunk + a 1-block border so [lecternWallAt] hits lecterns
         *  in the next chunk over for decor-candidate self-checks).
         *  Populated before [buildDecorCache] so each candidate's
         *  lectern lookup is an O(1) hash read. */
        var lecterns: Long2ObjectOpenHashMap<BlockState>? = null


        /** Flat boolean array, indexed by `(lx*size + lz)*span + ly`,
         *  set to true wherever [isWallOutline] returned true for a
         *  candidate position during [buildDecorCache]'s precompute
         *  pass. Lets the per-position wall-connectivity check in
         *  [decorBlockAt] read 4 neighbour bits instead of recomputing
         *  [isWallOutline] (3 pathBlockAt + 4 isPathFloor) for each. */
        var wallBits: BooleanArray? = null
        var wallBitsBaseX: Int = 0
        var wallBitsBaseZ: Int = 0
        var wallBitsBaseY: Int = 0
        var wallBitsCacheSize: Int = 0
        var wallBitsSpan: Int = 0

        /** Per-chunk [MazeNode]? cache. `mazeNodeAt(cx, cy, cz)` is called
         *  from `paintChunkInto`, `buildWallFrameCache`, `buildCubeExclude`,
         *  `paintConnectionPick`, `buildLecternMap`, plus indirectly via
         *  `mazeBlockAt` / `preferredStairwell` / `pathwaySkipMask`.
         *  Precomputing the cell-window once collapses every subsequent
         *  call to an array read. Out-of-window calls fall back to a
         *  fresh `mazeNodeBaseExists` check. */
        var nodeCache: Array<MazeNode?>? = null
        var nodeBaseX: Int = 0
        var nodeBaseY: Int = 0
        var nodeBaseZ: Int = 0
        var nodeWidthX: Int = 0
        var nodeWidthY: Int = 0
        var nodeWidthZ: Int = 0

        /** Per-chunk [platformOriginX] / [platformOriginZ] result caches.
         *  Indexed by `cellX - originXBase` / `cellZ - originZBase` using
         *  the same widened cell window as [nodeCache]. The functions
         *  themselves are pure of cellX / cellZ — second profile run
         *  showed them at 1772 + 548 self-time inside fillFromNoise
         *  because every connectionBBox / mazeNodeAt / lecternWallAt
         *  call recomputed them. */
        var originXCache: IntArray? = null
        var originXBase: Int = 0
        var originZCache: IntArray? = null
        var originZBase: Int = 0

        /** Memoised [stairwellAllowed] result per (cell, vIdx). Pure
         *  function of its args but called from 3 paint sites
         *  (paintConnectionPick, paintWallFramesForPick, mazeBlockAt)
         *  for the same (cell, vIdx) — typically 3× redundant work per
         *  stairwell. Profile (post-vec-lookup-table) had it at 2980
         *  self / 14700 inclusive, the single biggest chunkgen hot
         *  spot. Sentinel: byte `0` = miss, `1` = allowed, `2` = blocked. */
        var stairwellAllowedCache: Long2ByteOpenHashMap? = null

        /** Memoised [connectionBBox] result per (cell, vIdx). Returns
         *  a shared read-only IntArray of length 6: `[xLo, xHi, yLo,
         *  yHi, zLo, zHi]`. Hot — called from stairwellAllowed +
         *  every paint pass; bbox is pure of (cell, vIdx). */
        var connectionBBoxCache: Long2ObjectOpenHashMap<IntArray>? = null

        /** Structure-replacement placements (Sselith Globe / Gallery /
         *  Mosaic / Shelves / Enchant — see [StructureReplacement]) whose
         *  footprint could intersect this chunk's painting / decoration
         *  window. Each entry carries the maze node + the picked type so
         *  the footprint check, outer-wall check, and the painting
         *  post-process can all look up the per-type dimensions
         *  uniformly. Queried by [isInsideStructureReplacement],
         *  [isStructureReplacementOuterWall], and the connection-paint
         *  doorway carver. Empty list ⇒ no replacements in this chunk's
         *  range, fast-path returns false. */
        var replacementsInRange: List<StructurePlacement> = emptyList()

        /** Cross-axial-path arches at the top of each arch column whose
         *  NBT footprint could intersect this chunk's painting window.
         *  Painted in [paintChunkInto] step 5 (after axial path) and
         *  consulted by [gapArchwayBlock] / [archChainFroglightAt] so
         *  the procedural arch shoulders / chain don't peek through
         *  the NBT replacement at top-cellY arch positions. */
        var axialArchesInRange: List<AxialArchPlacement> = emptyList()

        /** Per-chunk bitmaps for the structure-replacement coverage,
         *  filled from [replacementsInRange] once at the top of
         *  [fillFromNoise]. Indexed by `(lx * structBitsWidthY + ly)
         *  * structBitsWidthZ + lz` where `lx = wx - structBitsBaseX`,
         *  etc. Lookups in [isInsideStructureReplacement] / [is
         *  StructureReplacementOuterWall] become a single bit-test
         *  instead of iterating the placement list and doing 6
         *  comparisons per placement per block-position query.
         *  Spark report showed the linear scan cost at ~28s cum on
         *  the worker pool. */
        var structInsideBits: java.util.BitSet? = null
        var structOuterWallBits: java.util.BitSet? = null
        var structBitsBaseX: Int = 0
        var structBitsBaseY: Int = 0
        var structBitsBaseZ: Int = 0
        var structBitsWidthX: Int = 0
        var structBitsWidthY: Int = 0
        var structBitsWidthZ: Int = 0

        /** Per-position bitmap of [RingDecoration] cells (only the slots
         *  where the NBT actually places a block — air positions stay
         *  unset). Built alongside [structInsideBits] and shares its
         *  base / width dimensions. Used by [isPathFloor] to exclude
         *  ring blocks from triggering perimeter wall / slab decor —
         *  doing this material-agnostically avoids the brittle per-block
         *  exclusion list when a new ring NBT uses a different palette. */
        var ringInsideBits: java.util.BitSet? = null

        /** Per-position bitmap covering every horizontal-pathway corridor
         *  floor (vertical ladders / stairwells excluded). Used as one
         *  of the four "valid path-type" inputs to [isPathFloor]'s
         *  positive-whitelist check. */
        var corridorBits: java.util.BitSet? = null

        /** Per-position bitmap of [PathSegment] (NBT-salt) floor cells
         *  that actually got written to the cache. Marked from
         *  [paintPathSaltInto]. The other input to [isPathFloor]'s
         *  positive-whitelist check — wall decor fires above salt
         *  floors but not above bare NBT walls or air pockets above. */
        var saltBits: java.util.BitSet? = null

    }


    /** Single-position lookup combining both passes — used by `getBaseColumn` /
     *  biome lookups. Chunk gen itself does the two passes explicitly to amortise
     *  outline-neighbour reads. */
    private fun blockAt(wx: Int, wy: Int, wz: Int): BlockState? =
        pathBlockAt(wx, wy, wz) ?: decorBlockAt(wx, wy, wz)


    /** All "path" blocks the player can walk on or interact with — corridor
     *  floors, stairs, ladders, trapdoors, backing walls, chiseled ladder-floor,
     *  platform floors, axial path floor. Walls are intentionally NOT placed
     *  here; pass 2 derives them from the outline of these blocks.
     *
     *  Cache-aware: hits the per-chunk pass-1 cache when one is active. */
    private fun pathBlockAt(wx: Int, wy: Int, wz: Int): BlockState? {
        // Read the per-chunk cache from the ThreadLocal once. Outside
        // fillFromNoise (e.g. getBaseColumn during structure placement)
        // this is null and we fall through to the fresh-compute path.
        val ctx = activeCaches.get()
        val cache = ctx?.pathCache
        if (cache != null) {
            val lx = wx - ctx.pathBaseX
            val lz = wz - ctx.pathBaseZ
            val ly = wy - ctx.pathBaseY
            if (lx in 0 until cache.size && lz in 0 until cache[0].size && ly in 0 until ctx.pathSpan) {
                return cache[lx][lz][ly]
            }
            // Out of cache window → fall through to fresh compute.
        }
        val mazeResult = mazeBlockAt(wx, wy, wz)
        // Trapdoors win absolutely so the platform pass below can't overwrite them.
        if (mazeResult != null && mazeResult.block is TrapDoorBlock) return mazeResult
        // Platform floor beats any corridor block that would otherwise land on
        // the platform area.
        val platformFloor = mazePlatformFloorAt(wx, wy, wz)
        if (platformFloor != null) return platformFloor
        // Other corridor blocks.
        if (mazeResult != null) return mazeResult
        // Axial path floor (with chiseled markers every N in the centre row).
        if (wy == PATH_Y && isPathStrip(wx, wz)) return axialPathFloorAt(wx, wz)
        return null
    }

    /** Axial path's Y=0 floor block — chiseled marker every
     *  [AXIAL_PATH_CHISEL_INTERVAL] blocks in the centre column of each axis,
     *  otherwise polished deepslate. */
    private fun axialPathFloorAt(wx: Int, wz: Int): BlockState {
        val xAxisCentre = wz == 0 && positiveMod(wx, AXIAL_PATH_CHISEL_INTERVAL) == 0
        val zAxisCentre = wx == 0 && positiveMod(wz, AXIAL_PATH_CHISEL_INTERVAL) == 0
        return if (xAxisCentre || zAxisCentre) CHISELED_DEEPSLATE else POLISHED_DEEPSLATE
    }

    /** Platform layer — just the 5×5 floor at the cell's `ny`. Perimeter walls
     *  + headroom are no longer placed here; the outline pass derives them. */
    private fun mazePlatformFloorAt(wx: Int, wy: Int, wz: Int): BlockState? {
        // Range is widened by [LIBRARY_QUADRANT_SHIFT] on each side so the
        // ±5 per-quadrant platform shift can't push a candidate cell outside
        // the search window (the inner check on node.nx/nz still filters
        // exactly).
        val minCellX = Math.floorDiv(wx - GRID_OFFSET - FLOOR_SIZE - LIBRARY_QUADRANT_SHIFT, MAZE_CELL_X)
        val maxCellX = Math.floorDiv(wx - GRID_OFFSET + LIBRARY_QUADRANT_SHIFT + 1, MAZE_CELL_X)
        val minCellZ = Math.floorDiv(wz - GRID_OFFSET - FLOOR_SIZE - LIBRARY_QUADRANT_SHIFT, MAZE_CELL_Z)
        val maxCellZ = Math.floorDiv(wz - GRID_OFFSET + LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT + 1, MAZE_CELL_Z)
        val minCellY = Math.floorDiv(wy, MAZE_CELL_Y)
        val maxCellY = minCellY
        for (cellX in minCellX..maxCellX) {
            for (cellY in minCellY..maxCellY) {
                for (cellZ in minCellZ..maxCellZ) {
                    val node = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                    if (wy == node.ny &&
                        wx in node.nx until (node.nx + FLOOR_SIZE) &&
                        wz in node.nz until (node.nz + FLOOR_SIZE)
                    ) {
                        val dx = wx - node.nx
                        val dz = wz - node.nz
                        return when {
                            // Centre block — chiseled by default. Trapdoors
                            // from vertical connections override this via
                            // pathBlockAt's trapdoor-first priority.
                            dx == 2 && dz == 2 -> CHISELED_DEEPSLATE
                            // 3×3 ring around the centre.
                            dx in 1..3 && dz in 1..3 -> DEEPSLATE_TILES
                            // Outer ring.
                            else -> POLISHED_DEEPSLATE
                        }
                    }
                }
            }
        }
        return null
    }


    /** Iterate every maze cell whose geometry could land inside the cache
     *  window and paint its blocks directly into `cache`. Replaces the old
     *  per-position `pathBlockAt` cache-fill loop, which was the dominant
     *  worldgen cost. */
    private fun paintChunkInto(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
    ) {
        val maxX = baseX + cacheSize - 1
        val maxZ = baseZ + cacheSize - 1
        val maxY = baseY + span - 1

        // Cell range. A connection can extend up to one full cell in any
        // direction from its source, so we widen the range by ±1 cell.
        val minCellX = Math.floorDiv(baseX - GRID_OFFSET, MAZE_CELL_X) - 1
        val maxCellX = Math.floorDiv(maxX - GRID_OFFSET, MAZE_CELL_X) + 1
        val minCellY = Math.floorDiv(baseY, MAZE_CELL_Y) - 1
        val maxCellY = Math.floorDiv(maxY, MAZE_CELL_Y) + 1
        val minCellZ = Math.floorDiv(baseZ - GRID_OFFSET, MAZE_CELL_Z) - 1
        val maxCellZ = Math.floorDiv(maxZ - GRID_OFFSET, MAZE_CELL_Z) + 1

        // 0. Ring decorations — per-maze-cell pick of one of [RING_STYLE_COUNT]
        //    options. NBT indices paint the authored 43×N×43 NBT centred on the platform;
        //    the procedural sentinel paints the concentric-slab + corner-froglight pattern
        //    centred on the maze cell (NOT the library tile — library is offset by
        //    LIBRARY_QUADRANT_SHIFT). Painted FIRST so structure / corridor / platform /
        //    axial paint cleanly overwrites the ring centre and the corridor lanes that
        //    cross it.
        for (cellX in minCellX..maxCellX) {
            for (cellY in minCellY..maxCellY) {
                for (cellZ in minCellZ..maxCellZ) {
                    val node = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                    val roll = positiveMod(
                        mazeHash(cellX, cellY, cellZ, RING_TYPE_HASH_SALT), RING_STYLE_COUNT,
                    )
                    if (roll < RingDecoration.entries.size) {
                        paintRingDecorationInto(
                            cache, baseX, baseZ, baseY, cacheSize, span,
                            node, RingDecoration.entries[roll],
                        )
                    } else {
                        paintProceduralRingInto(
                            cache, baseX, baseZ, baseY, cacheSize, span, node,
                        )
                    }
                }
            }
        }
        // 1. Structure replacements FIRST — rare NBT structures (globe,
        //    gallery, mosaic, shelves, enchant, viewing platform,
        //    specimen sculk, the OBSERVATORY column) and the 7×7
        //    "normal" platforms. Drives off `replacementsInRange`
        //    rather than re-querying `replacementTypeAt` per cell —
        //    that's the single source of truth, and the supplementary
        //    observatory scan in the chunk setup adds placements the
        //    per-cell picker doesn't (the per-cell path returns null
        //    for every cell in an observatory column). Painted before
        //    corridors so corridor paint can carve doorways through
        //    the structure's outer wall layer.
        run {
            val placements = activeCaches.get()?.replacementsInRange ?: emptyList()
            for (placement in placements) {
                paintStructureReplacementInto(
                    cache, baseX, baseZ, baseY, cacheSize, span,
                    placement.node, placement.type,
                )
            }
        }
        // 2. Connections (corridors). Platform paint below overwrites any
        //    corridor block that lands on the platform area; corridor paint
        //    here freely overwrites a structure replacement's outer wall
        //    blocks at corridor crossings (carving doorways), but the
        //    structure interior is preserved.
        for (cellX in minCellX..maxCellX) {
            for (cellY in minCellY..maxCellY) {
                for (cellZ in minCellZ..maxCellZ) {
                    val node = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                    for (vIdx in pickConnections(cellX, cellY, cellZ)) {
                        paintConnectionPick(cache, baseX, baseZ, baseY, cacheSize, span,
                            node, cellX, cellY, cellZ, vIdx)
                    }
                }
            }
        }
        // 3. Platforms — overwrites corridor/stair blocks that landed on
        //    the platform area, preserving the centre/ring pattern.
        //    Trapdoors placed by vertical connections are preserved.
        //    SKIPS nodes that host a structure replacement — the
        //    structure brings its own floor.
        for (cellX in minCellX..maxCellX) {
            for (cellY in minCellY..maxCellY) {
                for (cellZ in minCellZ..maxCellZ) {
                    val node = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                    if (hasStructureReplacementAt(cellX, cellY, cellZ)) continue
                    paintPlatformInto(cache, baseX, baseZ, baseY, cacheSize, span, node)
                }
            }
        }
        // 4. Axial path floor — overwrites any stair/corridor block that
        //    crosses the central strip at Y=0, so the walkway stays
        //    uninterrupted. Platforms never intersect the strip (GRID_OFFSET
        //    keeps them at x≥6), so they're unaffected.
        paintAxialPathInto(cache, baseX, baseZ, baseY, cacheSize, span)
        // 5. Cross-axial top-arch NBT replacements. Stamps
        //    `sselith_axial_arch.nbt` at every column's topmost
        //    procedural arch in the chunk's window — overwrites no
        //    earlier-pass blocks at the worldX_arch / worldZ_arch
        //    column slice the procedural arch already owns, and
        //    [gapArchwayBlock] / [archChainFroglightAt] short-circuit
        //    at these columns so the column-fill draws AIR (instead of
        //    the procedural shoulder / chain) in the arch-footprint
        //    positions the NBT leaves empty.
        run {
            val placements = activeCaches.get()?.axialArchesInRange ?: emptyList()
            for (placement in placements) {
                paintAxialArchInto(
                    cache, baseX, baseZ, baseY, cacheSize, span, placement,
                )
            }
        }
        // 6. Cell-wall battlement caps — top row of the topmost cellY
        //    only. Adds an inward-facing deepslate-brick stair along
        //    each library-cell edge (Half.BOTTOM, so the step rises
        //    from the cell interior), a DEEPSLATE_TILES post at each
        //    corner, and a DEEPSLATE_TILES + polished-deepslate
        //    outer-corner stair ring one block above the cell ceiling
        //    (in the otherwise-air cellY=TOP_CELL_Y row). Painted into
        //    pathCache so the column-fill picks them up even at
        //    cellY=TOP_CELL_Y where `skipTopCell` would normally
        //    force AIR.
        paintCellTopBattlementInto(cache, baseX, baseZ, baseY, cacheSize, span)
        // Wall-block update pass now runs OUTSIDE [paintChunkInto] —
        // [fillFromNoise] calls [paintPathSaltInto] after the decor
        // pass, and the wall recompute fires AFTER the salt so any
        // NBT-salt wall blocks get their east/west/north/south/up
        // re-derived from the actual cache neighbours.
    }

    /** Walk every cache cell; for each [WallBlock], rebuild its
     *  east/west/north/south/up properties using vanilla
     *  [WallBlock.updateShape] / [WallBlock.shouldRaisePost] /
     *  [WallBlock.makeWallState] semantics on the cached neighbours.
     *  Idempotent — re-running on already-correct walls produces the
     *  same state. */
    private fun recomputeAllWalls(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
    ) {
        for (lx in 0 until cacheSize) {
            val wx = baseX + lx
            val xPlane = cache[lx]
            for (lz in 0 until cacheSize) {
                val wz = baseZ + lz
                val col = xPlane[lz]
                for (ly in 0 until span) {
                    val state = col[ly] ?: continue
                    if (state.block !is WallBlock) continue
                    val wy = baseY + ly
                    // Vanilla calls `connectsTo(neighbourState,
                    // neighbourState.isFaceSturdy(level, neighbourPos,
                    // <directionFromNeighbourToWall>), <directionFromNeighbour
                    // ToWall>)`. We mirror that by passing the direction
                    // FROM the wall TO the neighbour and letting
                    // [wallConnectsToNeighbourAt] flip it.
                    val east  = wallConnectsToNeighbourAt(
                        cache, lx + 1, lz, ly, cacheSize, span,
                        wx + 1, wy, wz, Direction.EAST,
                    )
                    val west  = wallConnectsToNeighbourAt(
                        cache, lx - 1, lz, ly, cacheSize, span,
                        wx - 1, wy, wz, Direction.WEST,
                    )
                    val north = wallConnectsToNeighbourAt(
                        cache, lx, lz - 1, ly, cacheSize, span,
                        wx, wy, wz - 1, Direction.NORTH,
                    )
                    val south = wallConnectsToNeighbourAt(
                        cache, lx, lz + 1, ly, cacheSize, span,
                        wx, wy, wz + 1, Direction.SOUTH,
                    )
                    val above = neighbourBlockStateAt(
                        cache, lx, lz, ly + 1, cacheSize, span,
                        wx, wy + 1, wz,
                    )
                    val up = shouldRaisePostVanilla(above, east, west, north, south)
                    col[ly] = state
                        .setValue(WallBlock.EAST_WALL,  if (east)  WallSide.LOW else WallSide.NONE)
                        .setValue(WallBlock.WEST_WALL,  if (west)  WallSide.LOW else WallSide.NONE)
                        .setValue(WallBlock.NORTH_WALL, if (north) WallSide.LOW else WallSide.NONE)
                        .setValue(WallBlock.SOUTH_WALL, if (south) WallSide.LOW else WallSide.NONE)
                        .setValue(WallBlock.UP, up)
                }
            }
        }
    }

    /** Vanilla [WallBlock.connectsTo] adapted to our chunk-gen context.
     *  [fromWallToNeighbour] is the direction FROM the wall TO the
     *  neighbour — e.g. when querying the EAST side connection, pass
     *  `Direction.EAST`. The neighbour's face FACING the wall is the
     *  opposite. Returns true exactly when vanilla would render a LOW
     *  or TALL wall side toward the neighbour:
     *    - Neighbour is in [BlockTags.WALLS] (always connect)
     *    - Neighbour is an [IronBarsBlock]
     *    - Neighbour is a [FenceGateBlock] whose facing is perpendicular
     *      to the wall direction (so the gate sits "across" the wall)
     *    - Else the neighbour is NOT [Block.isExceptionForConnection]
     *      AND the neighbour has a sturdy collision face on the side
     *      facing the wall.
     *
     *  Sturdiness is computed against [EmptyBlockGetter.INSTANCE] /
     *  [BlockPos.ZERO] — vanilla's per-state cache short-circuits any
     *  level access for blocks whose face-shape is deterministic from
     *  the state alone (the overwhelming majority), and the rest fall
     *  back to a static collision-shape query that's fine with the
     *  dummy getter at worldgen time. */
    private fun wallConnectsToNeighbour(
        neighbour: BlockState, fromWallToNeighbour: Direction,
    ): Boolean {
        if (neighbour.isAir) return false
        if (neighbour.`is`(BlockTags.WALLS)) return true
        val block = neighbour.block
        if (block is IronBarsBlock) return true
        if (block is FenceGateBlock &&
            FenceGateBlock.connectsToDirection(neighbour, fromWallToNeighbour)
        ) return true
        if (Block.isExceptionForConnection(neighbour)) return false
        val faceFacingWall = fromWallToNeighbour.opposite
        return neighbour.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, faceFacingWall)
    }

    /** Connection check against the cell at `(lx, ly, lz)` of the chunk's
     *  pathCache. Inside the cache window we read the actual cached
     *  state (null → no neighbour, treat as air); outside the window we
     *  predict via the same outline / outer-wall predicates that the
     *  decor pass uses, conservatively yielding a wall-equivalent so
     *  cross-chunk-boundary walls keep their LOW connections. */
    private fun wallConnectsToNeighbourAt(
        cache: Array<Array<Array<BlockState?>>>,
        lx: Int, lz: Int, ly: Int, cacheSize: Int, span: Int,
        wx: Int, wy: Int, wz: Int, fromWallToNeighbour: Direction,
    ): Boolean {
        val state = neighbourBlockStateAt(cache, lx, lz, ly, cacheSize, span, wx, wy, wz)
        return state != null && wallConnectsToNeighbour(state, fromWallToNeighbour)
    }

    /** Resolve the BlockState that should be queried for a wall's
     *  neighbour at `(lx, ly, lz)`. Inside the cache window we read
     *  whatever pathCache holds; if the cache slot is empty we still
     *  consult the precomputed wallBits so the wall recompute can see
     *  the decor wall that's about to land at that slot — without
     *  that fallback, salt / structure / axial-arch walls (all in
     *  pathCache) report NONE on every face that's about to butt
     *  against a procedural maze decor wall (which lives in decorCache,
     *  i.e. null in pathCache), and the seam reads as a stub. Outside
     *  the cache window we additionally fall back to the salt and
     *  structure-outer-wall predicates so cross-chunk-boundary walls
     *  carry their LOW connection. */
    private fun neighbourBlockStateAt(
        cache: Array<Array<Array<BlockState?>>>,
        lx: Int, lz: Int, ly: Int, cacheSize: Int, span: Int,
        wx: Int, wy: Int, wz: Int,
    ): BlockState? {
        if (lx in 0 until cacheSize && lz in 0 until cacheSize && ly in 0 until span) {
            val cached = cache[lx][lz][ly]
            if (cached != null) return cached
            // Cache empty in-window: maze decor wall could still land
            // here (the decor pass writes to a different cache). The
            // structure / salt predicates aren't useful in-window
            // because those NBT volumes have already been painted into
            // pathCache by this point — a null cache slot inside a
            // salt / structure bbox is a genuine carved-air position,
            // not pending material.
            if (wallOutlineAt(wx, wy, wz)) return DEEPSLATE_BRICK_WALL_DEFAULT
            return null
        }
        // Outside the cache window — the neighbouring chunk hasn't been
        // painted here yet, so predict from every procedural predicate
        // that knows what'll land at that worldgen position.
        if (wallOutlineAt(wx, wy, wz)) return DEEPSLATE_BRICK_WALL_DEFAULT
        if (isStructureReplacementOuterWall(wx, wy, wz)) return DEEPSLATE_BRICKS
        if (isInsideSalt(wx, wy, wz)) return DEEPSLATE_BRICKS
        return null
    }

    /** Vanilla [WallBlock.shouldRaisePost] adapted to our four-bool
     *  connection inputs. The vanilla method also folds TALL/LOW into
     *  its decision; we always emit LOW connections from the chunkgen
     *  paint passes, so the "both TALL" early-exit never fires here. */
    private fun shouldRaisePostVanilla(
        above: BlockState?, east: Boolean, west: Boolean, north: Boolean, south: Boolean,
    ): Boolean {
        if (above != null && above.block is WallBlock &&
            above.getValue(WallBlock.UP) == true
        ) return true
        val nNone = !north
        val sNone = !south
        val eNone = !east
        val wNone = !west
        if (nNone && sNone && eNone && wNone) return true
        if (nNone != sNone) return true
        if (wNone != eNone) return true
        // We never emit TALL, so the "both TALL → no post" branch is
        // unreachable. Fall through to vanilla's coverage check.
        if (above == null || above.isAir) return false
        if (above.`is`(BlockTags.WALL_POST_OVERRIDE)) return true
        // POST_TEST coverage: vanilla checks whether the above block's
        // collision-shape DOWN-face covers the centre post test shape.
        // For any full-cube above (deepslate_tiles, polished_deepslate,
        // etc.) the DOWN face is full and the test passes; for slabs,
        // stairs, chains, lanterns, etc. it fails. A face-sturdy DOWN
        // is a close-enough proxy without re-implementing the test.
        return above.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.DOWN)
    }

    private fun paintAxialPathInto(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
    ) {
        val floorLy = PATH_Y - baseY
        val head1Ly = PATH_Y + 1 - baseY  // player feet
        val head2Ly = PATH_Y + 2 - baseY  // player head
        for (lx in 0 until cacheSize) {
            val wx = baseX + lx
            val col = cache[lx]
            for (lz in 0 until cacheSize) {
                val wz = baseZ + lz
                if (!isPathStrip(wx, wz)) continue
                if (floorLy in 0 until span) {
                    col[lz][floorLy] = axialPathFloorAt(wx, wz)
                }
                // Clear stair/corridor blocks at player body height so the
                // axial walkway stays unobstructed where other connections
                // cross the central strip.
                if (head1Ly in 0 until span) col[lz][head1Ly] = null
                if (head2Ly in 0 until span) col[lz][head2Ly] = null
            }
        }
    }

    /** Cell-wall battlement caps sitting ABOVE the world's topmost
     *  cellY — never replaces a tile of the cell wall itself. Three
     *  Y rows, all in the otherwise-AIR `cellY = TOP_CELL_Y` zone
     *  that `skipTopCell` would normally force AIR:
     *
     *   - `capY = TOP_CELL_Y * MAZE_CELL_Y` — one block above the
     *     topmost cell's ceiling (i.e. one above the wall's top row
     *     of bookshelves / DEEPSLATE_BRICKS). Corners get
     *     `DEEPSLATE_TILES`; remaining wall edges get an
     *     outward-facing `DEEPSLATE_BRICK_STAIRS` (Half.BOTTOM) so
     *     the parapet steps face outside the cell.
     *   - `crownY = capY + 1` — second DEEPSLATE_TILES row at the
     *     corners only, so the corner posts stack two tiles tall.
     *   - `finialY = capY + 2` — exactly four corner stairs per
     *     cell, one perched directly on top of each two-tile corner
     *     post. Shape = `OUTER_LEFT` / `OUTER_RIGHT` so the stair's
     *     solid corner extends outward into the diagonal cell corner.
     *
     *  Skipped on the axial strip and inside the library gap, where
     *  the library-cell pattern doesn't fire either. Painted into
     *  pathCache so the cellY = TOP_CELL_Y rows survive the
     *  `skipTopCell` AIR forcing in the column-fill loop. */
    private fun paintCellTopBattlementInto(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
    ) {
        val capY = TOP_CELL_Y * MAZE_CELL_Y
        val crownY = capY + 1
        val finialY = capY + 2
        val capLy = capY - baseY
        val crownLy = crownY - baseY
        val finialLy = finialY - baseY
        if (capLy !in 0 until span &&
            crownLy !in 0 until span &&
            finialLy !in 0 until span
        ) return
        val maxX = MAZE_CELL_X - 1
        val maxZ = MAZE_CELL_Z - 1
        for (lx in 0 until cacheSize) {
            val wx = baseX + lx
            val xPlane = cache[lx]
            for (lz in 0 until cacheSize) {
                val wz = baseZ + lz
                if (isPathStrip(wx, wz)) continue
                val inLibraryGap =
                    wx in -LIBRARY_QUADRANT_SHIFT..LIBRARY_QUADRANT_SHIFT ||
                        wz in -LIBRARY_QUADRANT_SHIFT..(LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT)
                if (inLibraryGap) continue
                val effX = when {
                    wx > LIBRARY_QUADRANT_SHIFT -> wx - LIBRARY_QUADRANT_SHIFT - 1
                    wx < -LIBRARY_QUADRANT_SHIFT -> wx + LIBRARY_QUADRANT_SHIFT
                    else -> 0
                }
                val effZ = when {
                    wz > LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT ->
                        wz - LIBRARY_QUADRANT_SHIFT - POSITIVE_Z_EXTRA_SHIFT - 1
                    wz < -LIBRARY_QUADRANT_SHIFT -> wz + LIBRARY_QUADRANT_SHIFT
                    else -> 0
                }
                val tileX = Math.floorMod(effX, MAZE_CELL_X)
                val tileZ = Math.floorMod(effZ, MAZE_CELL_Z)
                val isXWall = tileX == 0 || tileX == maxX
                val isZWall = tileZ == 0 || tileZ == maxZ
                val isCorner = isXWall && isZWall
                val isEdge = isXWall || isZWall
                if (!isEdge) continue
                val col = xPlane[lz]
                if (capLy in 0 until span) {
                    val capBlock: BlockState? = when {
                        isCorner -> DEEPSLATE_TILES
                        tileX == 0 -> DEEPSLATE_BRICK_STAIRS_BOTTOM_WEST
                        tileX == maxX -> DEEPSLATE_BRICK_STAIRS_BOTTOM_EAST
                        tileZ == 0 -> DEEPSLATE_BRICK_STAIRS_BOTTOM_NORTH
                        tileZ == maxZ -> DEEPSLATE_BRICK_STAIRS_BOTTOM_SOUTH
                        else -> null
                    }
                    if (capBlock != null) col[capLy] = capBlock
                }
                if (crownLy in 0 until span && isCorner) {
                    col[crownLy] = DEEPSLATE_TILES
                }
                // Four corner stairs per cell, one atop each two-tile corner post. BOTH
                // N/S facing and OUTER_LEFT/RIGHT shape must flip in pairs — swapping only
                // N leaves the E/W shape extending into the wrong diagonal.
                if (finialLy in 0 until span && isCorner) {
                    val finialBlock: BlockState = when {
                        tileX == 0 && tileZ == 0 ->
                            POLISHED_DEEPSLATE_STAIRS_BOTTOM_NORTH_OUTER_LEFT
                        tileX == maxX && tileZ == 0 ->
                            POLISHED_DEEPSLATE_STAIRS_BOTTOM_NORTH_OUTER_RIGHT
                        tileX == 0 && tileZ == maxZ ->
                            POLISHED_DEEPSLATE_STAIRS_BOTTOM_SOUTH_OUTER_RIGHT
                        else ->
                            POLISHED_DEEPSLATE_STAIRS_BOTTOM_SOUTH_OUTER_LEFT
                    }
                    col[finialLy] = finialBlock
                }
            }
        }
    }

    /** True when `(wx, wy, wz)` sits inside the battlement paint pass's
     *  footprint — the three Y rows above the topmost cellY's ceiling,
     *  excluding axial-strip and library-gap positions and limited to
     *  library-cell perimeter (wall-edge) tiles. Used by [isPathFloor]
     *  to suppress the procedural wall / slab decor pass on top of the
     *  battlement caps. */
    private fun isInsideCellTopBattlement(wx: Int, wy: Int, wz: Int): Boolean {
        val capY = TOP_CELL_Y * MAZE_CELL_Y
        if (wy < capY || wy > capY + 2) return false
        if (isPathStrip(wx, wz)) return false
        if (wx in -LIBRARY_QUADRANT_SHIFT..LIBRARY_QUADRANT_SHIFT ||
            wz in -LIBRARY_QUADRANT_SHIFT..(LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT)
        ) return false
        val effX = when {
            wx > LIBRARY_QUADRANT_SHIFT -> wx - LIBRARY_QUADRANT_SHIFT - 1
            wx < -LIBRARY_QUADRANT_SHIFT -> wx + LIBRARY_QUADRANT_SHIFT
            else -> 0
        }
        val effZ = when {
            wz > LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT ->
                wz - LIBRARY_QUADRANT_SHIFT - POSITIVE_Z_EXTRA_SHIFT - 1
            wz < -LIBRARY_QUADRANT_SHIFT -> wz + LIBRARY_QUADRANT_SHIFT
            else -> 0
        }
        val tileX = Math.floorMod(effX, MAZE_CELL_X)
        val tileZ = Math.floorMod(effZ, MAZE_CELL_Z)
        val maxX = MAZE_CELL_X - 1
        val maxZ = MAZE_CELL_Z - 1
        return tileX == 0 || tileX == maxX || tileZ == 0 || tileZ == maxZ
    }

    /** Stamp `sselith_axial_arch.nbt` into pathCache at the column's top
     *  procedural cross-axial arch. Writes NBT material where present and
     *  AIR everywhere else in the footprint — the AIR fill is load-bearing
     *  because the procedural gap-column pillar ([isGapColumn] →
     *  DEEPSLATE_TILES) at `GAP_COLUMN_NEG_Z` / `GAP_COLUMN_NEG_X` /
     *  `GAP_COLUMN_POS_X` would otherwise resume above the NBT's stair
     *  shoulder once the column-fill's headroom window expires, leaving a
     *  floating fragment of pillar inside the arch interior. With AIR in
     *  pathCache for the empty positions, the column-fill uses that value
     *  directly and the arch interior stays open all the way to the
     *  cellY ceiling. */
    private fun paintAxialArchInto(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        placement: AxialArchPlacement,
    ) {
        val blocks = if (placement.alongAxisIsZ) {
            AXIAL_ARCH_BLOCKS
        } else {
            AXIAL_ARCH_BLOCKS_ROTATED
        } ?: return
        val cornerY = placement.cellY * MAZE_CELL_Y + AXIAL_ARCH_CELL_Y_OFFSET
        val cornerX: Int
        val cornerZ: Int
        val dimX: Int
        val dimZ: Int
        if (placement.alongAxisIsZ) {
            // X-axial-path arch — long axis spans world Z (NBT natural).
            cornerX = placement.archCoord
            cornerZ = GAP_COLUMN_NEG_Z
            dimX = AXIAL_ARCH_DIM_X
            dimZ = AXIAL_ARCH_DIM_Z
        } else {
            // Z-axial-path arch — long axis spans world X (NBT rotated CCW90).
            cornerX = GAP_COLUMN_NEG_X
            cornerZ = placement.archCoord
            dimX = AXIAL_ARCH_DIM_Z
            dimZ = AXIAL_ARCH_DIM_X
        }
        for (dx in 0 until dimX) {
            val lx = cornerX + dx - baseX
            if (lx !in 0 until cacheSize) continue
            val xPlane = cache[lx]
            for (dz in 0 until dimZ) {
                val lz = cornerZ + dz - baseZ
                if (lz !in 0 until cacheSize) continue
                val yCol = xPlane[lz]
                // Skip only the NBT's bottom row (dy = 0) — also skipping dy = 3..5 to
                // drop the "outer arch" band leaves a 3-block AIR shaft between the lower
                // walls/lanterns and the upper arch curve that reads as two structures.
                // Painting dy = 1..9 continuously keeps it as one coherent piece.
                for (dy in 1 until AXIAL_ARCH_DIM_Y) {
                    val ly = cornerY + dy - baseY
                    if (ly !in 0 until span) continue
                    yCol[ly] = blocks[dx][dy][dz] ?: AIR
                }
            }
        }
    }

    /** Anchor cellY for the NBT axial-arch stamp at [archCoord]. Always
     *  `TOP_CELL_Y - 1` — the world's topmost generated cell row — for
     *  every cross-axial arch column, regardless of whether the
     *  procedural cross-axial parity gate would have fired there.
     *  Previously parity-odd columns (e.g. archCoord = 24) anchored to
     *  `maxCellY - 1 = 3`, producing a visible NBT arch at world
     *  Y = 94 in addition to the proper top-cellY arch at Y = 118
     *  — the player read those as two stacked arch layers. With every
     *  column anchored at `maxCellY`, every NBT arch lines up at the
     *  same Y row, and the placement-based suppression in
     *  [isInAxialArchColumn] still covers every archCoord so no
     *  procedural arch leaks through at lower cellYs. */
    private fun topAxialArchCellY(archCoord: Int): Int? {
        val maxCellY = TOP_CELL_Y - 1
        if (maxCellY < 0) return null
        return maxCellY
    }

    /** True when [worldX]/[worldZ] sits in a cross-axial-arch column that
     *  the NBT replaces at its top procedural row. Consulted by
     *  [gapArchwayBlock] and [archChainFroglightAt] to suppress the
     *  PROCEDURAL cross-axial arch at ALL cellY in the column — not
     *  just the top one. Without the lower-cellY suppression, every
     *  parity-matching cellY below the NBT (cellY=2, 0, −2, −4 for an
     *  even-parity column whose top is cellY=4) keeps painting its own
     *  procedural arch + hanging chain, and the player looking up the
     *  axial path sees a stack of arches with the NBT only on top. */
    private fun isInAxialArchColumn(worldX: Int, worldZ: Int): Boolean {
        val placements = activeCaches.get()?.axialArchesInRange ?: return false
        if (placements.isEmpty()) return false
        for (p in placements) {
            if (p.alongAxisIsZ) {
                if (worldX == p.archCoord) return true
            } else {
                if (worldZ == p.archCoord) return true
            }
        }
        return false
    }

    private fun paintPlatformInto(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        node: MazeNode,
    ) {
        val ly = node.ny - baseY
        if (ly !in 0 until span) return
        for (dx in 0 until FLOOR_SIZE) {
            val lx = node.nx + dx - baseX
            if (lx !in 0 until cacheSize) continue
            val xPlane = cache[lx]
            for (dz in 0 until FLOOR_SIZE) {
                val lz = node.nz + dz - baseZ
                if (lz !in 0 until cacheSize) continue
                // Preserve trapdoors painted by vertical connections.
                val existing = xPlane[lz][ly]
                if (existing != null && existing.block is TrapDoorBlock) continue
                val block = when {
                    dx == 2 && dz == 2 -> CHISELED_DEEPSLATE
                    dx in 1..3 && dz in 1..3 -> DEEPSLATE_TILES
                    else -> POLISHED_DEEPSLATE
                }
                xPlane[lz][ly] = block
            }
        }
    }

    /** Two-tier per-cell structure picker, gated on no outgoing upward
     *  connection (a ceiling or full-cell room would block the ladder /
     *  stair flight).
     *
     *  - Rare gate (`SSELITH_GLOBE_PERCENT`%) → pick from the rare set
     *    (globe / gallery / mosaic / shelves / enchant / viewing platform /
     *    specimen).
     *  - Otherwise → pick one of the 7×7 normal platforms. Every eligible
     *    cell ends up with SOME NBT structure — the hardcoded 5×5 floor
     *    paint now only runs as a fallback for cells with an upward
     *    connection.
     *
     *  Returns null on cells that have no node OR have an upward
     *  connection. */
    private fun replacementTypeAt(
        cellX: Int, cellY: Int, cellZ: Int,
    ): StructureReplacement? {
        if (mazeNodeAt(cellX, cellY, cellZ) == null) return null
        // OBSERVATORY columns are NEVER picked through the per-cell
        // path — placements are added by a dedicated supplementary
        // scan over (cellX, cellZ) so they survive chunks that don't
        // include the column's bottom cellY in their cellMinY..cellMaxY
        // window. Return null here for every cell in an observatory
        // column; the column-aware [hasStructureReplacementAt] branch
        // still suppresses the per-cell platforms / vertical paths.
        if (hasObservatoryColumnAt(cellX, cellZ)) return null
        val hasUp = hasUpwardConnection(cellX, cellY, cellZ)
        // The topmost cellY has no maze nodes (cellY = TOP_CELL_Y), so `hasUp` is always
        // false there. Without a forced override it falls into RARE/NORMAL and picks a tall
        // structure, which trips [isStructureReplacementNode] and rejects every INCOMING
        // stairwell from cellY-1 → no vertical connectivity reaches the top row. Force the
        // top cellY into SHORT_PLATFORMS so it acts as a stair-compatible landing pad.
        val isTopCellY = cellY == TOP_CELL_Y - 1
        if (hasUp || isTopCellY) {
            // Roofed structures (rare set-pieces + most normal platforms)
            // would block the outgoing stair / ladder. Restrict to the
            // open-top SHORT_PLATFORMS so the cell still gets an NBT
            // platform instead of falling back to the hardcoded 5×5 floor
            // — without this guard, every upward-connection cell looked
            // sparse compared to its neighbours.
            if (SHORT_PLATFORMS.isEmpty()) return null
            val typeRoll = positiveMod(
                mazeHash(cellX, cellY, cellZ, REPLACEMENT_TYPE_HASH_SALT), SHORT_PLATFORMS.size,
            )
            return SHORT_PLATFORMS[typeRoll]
        }
        val roll = positiveMod(mazeHash(cellX, cellY, cellZ, SSELITH_GLOBE_HASH_SALT), 100)
        val pool = if (roll < SSELITH_GLOBE_PERCENT) RARE_REPLACEMENTS else NORMAL_REPLACEMENTS
        val typeRoll = positiveMod(mazeHash(cellX, cellY, cellZ, REPLACEMENT_TYPE_HASH_SALT), pool.size)
        return pool[typeRoll]
    }

    /** Thin "is any replacement here?" predicate for the maze decor passes
     *  that don't care about the specific type — e.g. the platform-paint
     *  skip and the vertical-connection skip. Observatory columns return
     *  true for EVERY cell in the column (not just the bottom one), so
     *  the upper cells' platforms / vertical connections are suppressed
     *  the same way the bottom cell's are. */
    private fun hasStructureReplacementAt(cellX: Int, cellY: Int, cellZ: Int): Boolean {
        if (hasObservatoryColumnAt(cellX, cellZ)) return true
        return replacementTypeAt(cellX, cellY, cellZ) != null
    }

    /** True iff the (cellX, cellZ) column hash-rolls into hosting an
     *  observatory AND has at least one maze node to anchor on. The
     *  observatory's `y=0` lands at the column's BOTTOM PLATFORM —
     *  the lowest cellY with a maze node — not at world Y=0 (which
     *  is mid-stack, not the world's bottom). */
    private fun hasObservatoryColumnAt(cellX: Int, cellZ: Int): Boolean {
        if (observatoryBottomCellY(cellX, cellZ) == null) return false
        val roll = positiveMod(
            mazeHash(cellX, 0, cellZ, OBSERVATORY_HASH_SALT), 100,
        )
        return roll < OBSERVATORY_PERCENT
    }

    /** Anchor cellY for the observatory in column (cellX, cellZ), or
     *  null when the column hosts no maze node at all. Returns the
     *  **global** minimum cellY a maze node CAN exist at — the lowest
     *  cellY where `mazeNodeBaseExists` permits a platform
     *  (cellY * MAZE_CELL_Y ≥ MIN_Y + STAIR_DROP + 2 → cellY ≥
     *  ceilDiv(MIN_Y + STAIR_DROP + 2, MAZE_CELL_Y) ≈ −5 here). NOT
     *  the column's actual lowest-node cellY: every observatory now
     *  shares a single base Y, regardless of where the column's
     *  bottom-most node sits, so towers line up at the same world Y
     *  instead of a column whose lowest node lands at cellY=−2
     *  spawning its observatory 72 blocks above its neighbour's. */
    private fun observatoryBottomCellY(cellX: Int, cellZ: Int): Int? {
        val minCellY = Math.floorDiv(MIN_Y + STAIR_DROP + 2 + MAZE_CELL_Y - 1, MAZE_CELL_Y)
        for (cellY in minCellY until TOP_CELL_Y) {
            if (mazeNodeAt(cellX, cellY, cellZ) != null) return minCellY
        }
        return null
    }

    /** Per-cell ring picker — every node picks one of [RING_STYLE_COUNT]
     *  options: each NBT ring + a "procedural" sentinel that defers to
     *  the [LIBRARY_CELL]-baked concentric-slab pattern. Independent of
     *  [replacementTypeAt] (its own hash salt so the ring choice doesn't
     *  correlate with the platform / rare structure pick). Returns null
     *  for "no NBT ring here" — either the cell has no maze node, or
     *  the picker rolled the procedural sentinel (which means the
     *  [LIBRARY_CELL] y=0 ring fires unsuppressed for this cell).
     *  Rings paint at floor-Y; orthogonal to vertical-connection logic,
     *  so no upward-connection filter. */
    private fun ringAt(cellX: Int, cellY: Int, cellZ: Int): RingDecoration? {
        if (mazeNodeAt(cellX, cellY, cellZ) == null) return null
        val typeRoll = positiveMod(
            mazeHash(cellX, cellY, cellZ, RING_TYPE_HASH_SALT), RING_STYLE_COUNT,
        )
        return if (typeRoll < RingDecoration.entries.size) RingDecoration.entries[typeRoll]
               else null
    }


    /** NBT path salting — sparsely overlays authored path segments on a
     *  small fraction of straight horizontal corridors. Runs AFTER the
     *  procedural pathway paint AND after the decor pass: the procedural
     *  corridor + maze decor walls are the default look, and a salted
     *  tile only commits if its ENTIRE footprint is clear of platforms,
     *  cell walls, structure replacements, and the axial strip — i.e.,
     *  if it's in a stretch of corridor that's just running through
     *  the library background with no procedural geometry to clobber.
     *  Skips negative-direction cardinal picks so the other endpoint's
     *  east/south pick owns the corridor. */
    private fun paintPathSaltInto(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        cellX: Int, cellY: Int, cellZ: Int, vIdx: Int,
    ) {
        if (vIdx != PATHWAY_EAST && vIdx != PATHWAY_SOUTH) return
        val v = CONN_VECTORS[vIdx]
        // BOTH endpoints must exist — without the source check, salt
        // was firing for cells that had no maze node (pickConnections
        // is computed from a deterministic hash so it returns picks
        // even for empty cells; the procedural paint silently dropped
        // them by checking the node first).
        val a = mazeNodeAt(cellX, cellY, cellZ) ?: return
        val b = mazeNodeAt(cellX + v.dx, cellY + v.dy, cellZ + v.dz) ?: return
        val width = corridorWidth(a, b)
        // Per-corridor gate — only PATH_SALT_PERCENT of corridors get
        // any salt at all, so the procedural look stays dominant.
        val salt = positiveMod(
            mazeHash(cellX, cellY * 13 + vIdx, cellZ, PATH_SALT_HASH_SALT), 100,
        )
        if (salt >= PATH_SALT_PERCENT) return

        val bbox = connectionBBox(cellX, cellY, cellZ, vIdx)
        val xLo = bbox[0]; val xHi = bbox[1]
        val yLo = bbox[2]
        val zLo = bbox[4]; val zHi = bbox[5]

        val xAxisCorridor = v.dx != 0
        val rotation = if (xAxisCorridor) Rotation.COUNTERCLOCKWISE_90 else Rotation.NONE
        val length = if (xAxisCorridor) xHi - xLo + 1 else zHi - zLo + 1
        val tileCount = length / PATH_SEGMENT_TILE_LENGTH
        if (tileCount <= 0) return
        val corridorPerpCentre = if (xAxisCorridor) (zLo + zHi) / 2 else (xLo + xHi) / 2
        val corridorLengthStart = if (xAxisCorridor) xLo else zLo

        for (tileIdx in 0 until tileCount) {
            val segment = pickPathSegment(cellX, cellY, cellZ, vIdx, tileIdx, width)
            val blocks = PATH_BLOCKS[segment] ?: continue
            val tileStart = corridorLengthStart + tileIdx * PATH_SEGMENT_TILE_LENGTH
            val perpOrigin = corridorPerpCentre - (segment.dimX - 1) / 2

            // Validate the WHOLE tile footprint up-front — if any cell
            // would intersect a platform, cell wall, structure
            // replacement, or the axial strip, skip the entire tile
            // (atomic — no partial paint).
            if (!isSaltTileFootprintClean(segment, xAxisCorridor,
                    tileStart, yLo, perpOrigin)) continue

            val ctx = activeCaches.get()!!
            var saltBits = ctx.saltBits
            for (dy in 0 until segment.dimY) {
                val wy = yLo + dy
                val ly = wy - baseY
                if (ly !in 0 until span) continue
                for (dx in 0 until segment.dimX) {
                    for (dz in 0 until segment.dimZ) {
                        val state = blocks[dx][dy][dz] ?: continue
                        val wx: Int; val wz: Int
                        if (xAxisCorridor) {
                            wx = tileStart + dz
                            wz = perpOrigin + dx
                        } else {
                            wx = perpOrigin + dx
                            wz = tileStart + dz
                        }
                        val lx = wx - baseX
                        val lz = wz - baseZ
                        if (lx !in 0 until cacheSize) continue
                        if (lz !in 0 until cacheSize) continue
                        cache[lx][lz][ly] = state.rotate(rotation)
                        // Mark the ENTIRE NBT footprint (every dy row, not
                        // just the floor) in [saltBits] — the salt region
                        // owns its full 3D volume. The decor pass, carpet
                        // runner, and [isPathFloor] all gate on this so
                        // procedural walls / slabs / carpets / lanterns
                        // can't double-up with whatever the NBT placed.
                        if (saltBits == null) {
                            saltBits = java.util.BitSet(
                                ctx.structBitsWidthX * ctx.structBitsWidthY * ctx.structBitsWidthZ,
                            )
                            ctx.saltBits = saltBits
                        }
                        val bx = wx - ctx.structBitsBaseX
                        val by = wy - ctx.structBitsBaseY
                        val bz = wz - ctx.structBitsBaseZ
                        if (bx in 0 until ctx.structBitsWidthX &&
                            by in 0 until ctx.structBitsWidthY &&
                            bz in 0 until ctx.structBitsWidthZ
                        ) {
                            saltBits.set((bx * ctx.structBitsWidthY + by) * ctx.structBitsWidthZ + bz)
                        }
                    }
                }
            }
        }
    }

    /** True iff every world-space cell the salted tile would touch is
     *  clear of structure replacements, standard 5×5 platforms, library
     *  cell walls, the axial strip, and any critical path block (stair /
     *  ladder / trapdoor) already placed in the path cache. The check
     *  covers the full 3D dimX × dimY × dimZ NBT footprint — a single
     *  bad cell invalidates the whole tile. */
    private fun isSaltTileFootprintClean(
        segment: PathSegment, xAxisCorridor: Boolean,
        lengthStart: Int, yStart: Int, perpStart: Int,
    ): Boolean {
        val ctx = activeCaches.get() ?: return false
        val pathCache = ctx.pathCache
        for (dy in 0 until segment.dimY) {
            val wy = yStart + dy
            for (dx in 0 until segment.dimX) {
                for (dz in 0 until segment.dimZ) {
                    val wx: Int; val wz: Int
                    if (xAxisCorridor) {
                        wx = lengthStart + dz
                        wz = perpStart + dx
                    } else {
                        wx = perpStart + dx
                        wz = lengthStart + dz
                    }
                    if (isPathStrip(wx, wz)) return false
                    if (isOnLibraryCellWall(wx, wz)) return false
                    if (isInsideStructureReplacement(wx, wy, wz)) return false
                    if (isOnStandardPlatform(wx, wy, wz)) return false
                    // pathCache holds anything earlier paint passes wrote:
                    // corridor floor (OK to overlay) plus stairs / ladders /
                    // trapdoors (NOT OK — those carry essential navigation
                    // geometry the player needs to climb between cells).
                    if (pathCache != null) {
                        val lx = wx - ctx.pathBaseX
                        val ly = wy - ctx.pathBaseY
                        val lz = wz - ctx.pathBaseZ
                        if (lx in 0 until pathCache.size &&
                            lz in 0 until pathCache[0].size &&
                            ly in 0 until ctx.pathSpan
                        ) {
                            val existing = pathCache[lx][lz][ly]
                            if (existing != null && (
                                existing.`is`(Blocks.POLISHED_DEEPSLATE_STAIRS) ||
                                existing.block is LadderBlock ||
                                existing.block is TrapDoorBlock
                            )) return false
                        }
                    }
                }
            }
        }
        return true
    }

    /** True iff (wx, wy, wz) lies on a standard 5×5 platform floor — the
     *  hardcoded floor placed for cells that don't host a structure
     *  replacement. Salt tiles must avoid these so they don't clobber
     *  the procedural platform's centre/ring pattern. */
    private fun isOnStandardPlatform(wx: Int, wy: Int, wz: Int): Boolean {
        val cellX = Math.floorDiv(wx - GRID_OFFSET, MAZE_CELL_X)
        val cellZ = Math.floorDiv(wz - GRID_OFFSET, MAZE_CELL_Z)
        val cellY = Math.floorDiv(wy, MAZE_CELL_Y)
        val node = mazeNodeAt(cellX, cellY, cellZ) ?: return false
        if (wy != node.ny) return false
        return wx in node.nx until (node.nx + FLOOR_SIZE) &&
               wz in node.nz until (node.nz + FLOOR_SIZE)
    }

    /** True iff (wx, wz) sits on the perimeter of a library-cell tile —
     *  the 1-block walls between adjacent 49×49 tiles that
     *  [libraryCellBlock] paints as DEEPSLATE_BRICKS (corners) or
     *  BOOKSHELF (edges). NBT path overlay skips these so the user's
     *  "regular painter should do that" rule holds. */
    private fun isOnLibraryCellWall(wx: Int, wz: Int): Boolean {
        val effX = when {
            wx > LIBRARY_QUADRANT_SHIFT -> wx - LIBRARY_QUADRANT_SHIFT - 1
            wx < -LIBRARY_QUADRANT_SHIFT -> wx + LIBRARY_QUADRANT_SHIFT
            else -> 0
        }
        val effZ = when {
            wz > LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT ->
                wz - LIBRARY_QUADRANT_SHIFT - POSITIVE_Z_EXTRA_SHIFT - 1
            wz < -LIBRARY_QUADRANT_SHIFT -> wz + LIBRARY_QUADRANT_SHIFT
            else -> 0
        }
        val tileXMod = Math.floorMod(effX, MAZE_CELL_X)
        val tileZMod = Math.floorMod(effZ, MAZE_CELL_Z)
        return tileXMod == 0 || tileXMod == MAZE_CELL_X - 1 ||
               tileZMod == 0 || tileZMod == MAZE_CELL_Z - 1
    }

    private fun pickPathSegment(
        cellX: Int, cellY: Int, cellZ: Int, vIdx: Int, tileIdx: Int, corridorWidth: Int,
    ): PathSegment {
        // Match the NBT's walkable width to the procedural corridor's
        // walkable width. "Normal" tagged NBTs (path_1..6) have 1-wide
        // walkable; "wide" tagged NBTs (path_wide_*) have 3-wide
        // walkable. Mixing them within one corridor — or salting a
        // 1-wide corridor with a 5-wide-bbox wide NBT — would create
        // a visible width pinch the player walks through.
        val pool = if (corridorWidth == 3) PATH_SEGMENTS_WIDE else PATH_SEGMENTS_NARROW
        val hash = mazeHash(
            cellX + tileIdx * 31,
            cellY * 13 + vIdx,
            cellZ * 17,
            PATH_SEGMENT_HASH_SALT,
        )
        val idx = positiveMod(hash, pool.size)
        return pool[idx]
    }

    /** Per-maze-cell procedural concentric-ring + corner-froglight
     *  paint. Mirrors the slab pattern the old [LIBRARY_CELL] y=0
     *  branch used (edges 3 / 8 / 13 from the cell perimeter plus
     *  corner ochre froglights at edge [LIBRARY_FROGLIGHT_INSET]),
     *  but centred on the maze node's 49×49 cell instead of the
     *  library tile. Library tiles are offset from maze cells by
     *  [LIBRARY_QUADRANT_SHIFT]; the old fallback's quadrants ended
     *  up straddling adjacent maze cells, which is why some rings
     *  rendered with half-rings or stray slabs in cells that had
     *  picked an NBT ring. */
    private fun paintProceduralRingInto(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        node: MazeNode,
    ) {
        // Cell SW corner — platform at cell-local 0..4 (5-wide
        // platform anchored at the cell's SW corner; ring slabs at
        // cell-local 3/8/13 and their mirrors are centred on the
        // cell, not the platform).
        val cellOriginX = node.nx - GRID_OFFSET  // GRID_OFFSET = 22
        val cellOriginZ = node.nz - GRID_OFFSET
        val wy = node.ny
        val ly = wy - baseY
        if (ly !in 0 until span) return
        val maxCell = MAZE_CELL_X - 1  // 48
        for (cx in 0..maxCell) {
            val wx = cellOriginX + cx
            val lx = wx - baseX
            if (lx !in 0 until cacheSize) continue
            val edgeX = minOf(cx, maxCell - cx)
            for (cz in 0..maxCell) {
                val wz = cellOriginZ + cz
                val lz = wz - baseZ
                if (lz !in 0 until cacheSize) continue
                val edgeZ = minOf(cz, maxCell - cz)
                val edge = minOf(edgeX, edgeZ)
                val isFroglight = edgeX == LIBRARY_FROGLIGHT_INSET && edgeZ == LIBRARY_FROGLIGHT_INSET
                val block = when {
                    isFroglight -> OCHRE_FROGLIGHT
                    edge == 3 || edge == 8 || edge == 13 -> POLISHED_DEEPSLATE_SLAB_BOTTOM
                    else -> continue
                }
                // Keep the ring physically isolated from any corridor —
                // refuse to write at corridor positions so the corridor
                // floor (painted in step 2) is the only block there.
                if (isInsideCorridorBbox(wx, wy, wz)) continue
                cache[lx][lz][ly] = block
            }
        }
    }

    private fun paintRingDecorationInto(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        node: MazeNode, type: RingDecoration,
    ) {
        val blocks = RING_BLOCKS[type] ?: return
        val cornerX = node.nx + type.cornerOffsetX
        val cornerY = node.ny
        val cornerZ = node.nz + type.cornerOffsetZ
        for (dx in 0 until type.dimX) {
            val wx = cornerX + dx
            val lx = wx - baseX
            if (lx !in 0 until cacheSize) continue
            val xPlane = cache[lx]
            for (dz in 0 until type.dimZ) {
                val wz = cornerZ + dz
                val lz = wz - baseZ
                if (lz !in 0 until cacheSize) continue
                val yCol = xPlane[lz]
                for (dy in 0 until type.dimY) {
                    val wy = cornerY + dy
                    val ly = wy - baseY
                    if (ly !in 0 until span) continue
                    val state = blocks[dx][dy][dz] ?: continue
                    // Keep the ring physically isolated from any corridor.
                    if (isInsideCorridorBbox(wx, wy, wz)) continue
                    yCol[ly] = state
                }
            }
        }
    }

    /** True iff any vIdx in this cell's pick list resolves to an actual
     *  outgoing connection with `dy > 0` — ladder-up to a valid neighbour
     *  or one of the four `dy = +1` stairwells that passes the standard
     *  [stairwellAllowed] check. */
    private fun hasUpwardConnection(cellX: Int, cellY: Int, cellZ: Int): Boolean {
        for (vIdx in pickConnections(cellX, cellY, cellZ)) {
            val v = CONN_VECTORS[vIdx]
            if (v.dy <= 0) continue
            if (mazeNodeAt(cellX + v.dx, cellY + v.dy, cellZ + v.dz) == null) continue
            if (IS_STAIRWELL_VEC[vIdx] && !stairwellAllowed(cellX, cellY, cellZ, vIdx)) continue
            return true
        }
        return false
    }

    /** Write a structure-replacement NBT into [cache]. The NBT's
     *  `(0, 0, 0)` corner is mapped to world-space
     *  `(node.nx + cornerOffsetX, node.ny, node.nz + cornerOffsetZ)` —
     *  centring the structure horizontally on the would-be 5×5 platform
     *  and sitting Y-bottom on the platform level so the structure builds
     *  on top of existing terrain with no beard. structure_void positions
     *  in the NBT leave whatever earlier passes painted in place. */
    private fun paintStructureReplacementInto(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        node: MazeNode, type: StructureReplacement,
    ) {
        val blocks = REPLACEMENT_BLOCKS[type] ?: return
        val cornerX = node.nx + type.cornerOffsetX
        val cornerY = node.ny + type.cornerOffsetY
        val cornerZ = node.nz + type.cornerOffsetZ
        for (dx in 0 until type.dimX) {
            val lx = cornerX + dx - baseX
            if (lx !in 0 until cacheSize) continue
            val xPlane = cache[lx]
            for (dz in 0 until type.dimZ) {
                val lz = cornerZ + dz - baseZ
                if (lz !in 0 until cacheSize) continue
                val yCol = xPlane[lz]
                for (dy in 0 until type.dimY) {
                    val ly = cornerY + dy - baseY
                    if (ly !in 0 until span) continue
                    val state = blocks[dx][dy][dz] ?: continue
                    yCol[ly] = state
                }
            }
        }
    }

    /** Apply each structure-replacement's saved block-entity NBT to the
     *  chunk. Mirrors the path vanilla `StructureTemplate.placeInWorld`
     *  takes for BEs: get-or-create the BE, copy the structure NBT,
     *  inject `x`/`y`/`z`, call `BlockEntity.load`, mark dirty.
     *
     *  We can't rely on `ChunkAccess.setBlockState` having already
     *  registered a BE (`ProtoChunk` doesn't always auto-create them at
     *  chunk-gen time), and `BlockEntity.load` is generally happy to
     *  hydrate fields whether or not the BE pre-existed, so we
     *  explicitly construct via `EntityBlock.newBlockEntity` when the
     *  chunk doesn't yet hold one. */
    private fun applyStructureBlockEntities(chunk: ChunkAccess) {
        val ctx = activeCaches.get() ?: return
        val placements = ctx.replacementsInRange
        if (placements.isEmpty()) return
        val chunkPos = chunk.pos
        val chunkXMin = chunkPos.minBlockX
        val chunkXMax = chunkPos.minBlockX + 15
        val chunkZMin = chunkPos.minBlockZ
        val chunkZMax = chunkPos.minBlockZ + 15
        val mutable = BlockPos.MutableBlockPos()
        for (placement in placements) {
            // Scroll seeding: SPECIMEN_SCULK ships a lectern whose stack is
            // populated at gen time (the structure NBT itself can't carry
            // mod items without the resourcepack/save round-trip mangling
            // them). The lectern position is found by scanning the
            // structure's prebuilt block array for a LecternBlock; we set
            // HAS_BOOK on the chunk's state and hydrate the BE with a single
            // Scroll of Sculk Catastrophe. Runs first so the BE NBT loop
            // below sees the populated state if the NBT also carried a
            // lectern entry (it doesn't today — just being defensive).
            if (placement.type == StructureReplacement.SPECIMEN_SCULK) {
                seedSpecimenSculkScroll(chunk, placement, chunkXMin, chunkXMax, chunkZMin, chunkZMax, mutable)
            }
            val type = placement.type
            val bes = REPLACEMENT_BES[type] ?: continue
            if (bes.isEmpty()) continue
            val node = placement.node
            val cornerX = node.nx + type.cornerOffsetX
            val cornerY = node.ny + type.cornerOffsetY
            val cornerZ = node.nz + type.cornerOffsetZ
            for (entry in bes.long2ObjectEntrySet()) {
                val key = entry.longKey
                val lx = unpackStructureLocalX(key)
                val ly = unpackStructureLocalY(key)
                val lz = unpackStructureLocalZ(key)
                val wx = cornerX + lx
                val wy = cornerY + ly
                val wz = cornerZ + lz
                if (wx !in chunkXMin..chunkXMax) continue
                if (wz !in chunkZMin..chunkZMax) continue
                mutable.set(wx, wy, wz)
                val pos = mutable.immutable()
                val state = chunk.getBlockState(pos)
                if (!state.hasBlockEntity()) continue
                // Get-or-create the BE. ProtoChunk.setBlockState DOES
                // sometimes leave the BE map empty during chunk gen, so
                // we construct directly via EntityBlock.newBlockEntity
                // and register it on the chunk if needed.
                var be = chunk.getBlockEntity(pos)
                if (be == null) {
                    val block = state.block
                    if (block is net.minecraft.world.level.block.EntityBlock) {
                        be = block.newBlockEntity(pos, state) ?: continue
                        chunk.setBlockEntity(be)
                    } else continue
                }
                // Vanilla structure-place pattern: copy the saved tag,
                // inject world position (some BE.load impls read x/y/z),
                // delegate to BE.load. setChanged marks the chunk dirty
                // so the hydrated state gets saved.
                val tag = entry.value.copy()
                tag.putInt("x", pos.x)
                tag.putInt("y", pos.y)
                tag.putInt("z", pos.z)
                try {
                    be.load(tag)
                } catch (t: Throwable) {
                    PROFILE_LOG.error("Failed to load BE NBT at $pos for type $type", t)
                    continue
                }
                be.setChanged()
            }
        }
    }

    /** Walk the SPECIMEN_SCULK NBT blockset for lecterns and inject a
     *  [EKItems.SCROLL_OF_SCULK_CATASTROPHE] into each one inside this
     *  chunk. The structure ships with `has_book=false` lecterns so we
     *  set `HAS_BOOK=true` on the chunk's block state too — vanilla's
     *  `LecternBlockEntity.load` reads `Book` happily either way, but
     *  the block state's flag drives the menu's `stillValid` and the
     *  comparator output. */
    private fun seedSpecimenSculkScroll(
        chunk: ChunkAccess,
        placement: StructurePlacement,
        chunkXMin: Int, chunkXMax: Int, chunkZMin: Int, chunkZMax: Int,
        mutable: BlockPos.MutableBlockPos,
    ) {
        val type = placement.type
        val blocks = REPLACEMENT_BLOCKS[type] ?: return
        val node = placement.node
        val cornerX = node.nx + type.cornerOffsetX
        val cornerY = node.ny + type.cornerOffsetY
        val cornerZ = node.nz + type.cornerOffsetZ
        for (dx in 0 until type.dimX) {
            val wx = cornerX + dx
            if (wx !in chunkXMin..chunkXMax) continue
            for (dz in 0 until type.dimZ) {
                val wz = cornerZ + dz
                if (wz !in chunkZMin..chunkZMax) continue
                for (dy in 0 until type.dimY) {
                    val state = blocks[dx][dy][dz] ?: continue
                    if (state.block !is LecternBlock) continue
                    val wy = cornerY + dy
                    mutable.set(wx, wy, wz)
                    val pos = mutable.immutable()
                    val live = chunk.getBlockState(pos)
                    if (live.block !is LecternBlock) continue
                    if (!live.getValue(LecternBlock.HAS_BOOK)) {
                        chunk.setBlockState(pos, live.setValue(LecternBlock.HAS_BOOK, true), false)
                    }
                    val scroll = ItemStack(EKItems.SCROLL_OF_SCULK_CATASTROPHE.get())
                    var be = chunk.getBlockEntity(pos)
                    if (be == null) {
                        val block = live.block
                        if (block is net.minecraft.world.level.block.EntityBlock) {
                            be = block.newBlockEntity(pos, live) ?: continue
                            chunk.setBlockEntity(be)
                        } else continue
                    }
                    if (be !is LecternBlockEntity) continue
                    val tag = CompoundTag()
                    tag.put("Book", scroll.save(CompoundTag()))
                    tag.putInt("Page", 0)
                    tag.putInt("x", pos.x)
                    tag.putInt("y", pos.y)
                    tag.putInt("z", pos.z)
                    try {
                        be.load(tag)
                        be.setChanged()
                    } catch (t: Throwable) {
                        PROFILE_LOG.error("Failed to seed scroll on lectern at $pos", t)
                    }
                }
            }
        }
    }

    private fun paintConnectionPick(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        a: MazeNode, cellX: Int, cellY: Int, cellZ: Int, vIdx: Int,
    ) {
        val v = CONN_VECTORS[vIdx]
        val nCellX = cellX + v.dx
        val nCellY = cellY + v.dy
        val nCellZ = cellZ + v.dz
        val b = mazeNodeAt(nCellX, nCellY, nCellZ) ?: return
        // Stairwells: only paint if no nearby pathway or higher-priority
        // stairwell occupies overlapping space.
        if (isStairwellVec(vIdx) && !stairwellAllowed(cellX, cellY, cellZ, vIdx)) return

        // Structure-replacement nodes (Sselith Globe today; future
        // replacements via [isStructureReplacementNode]) own their full
        // vertical column — any ladder, trapdoor, stair flight or
        // brick-undertread the connection would plant inside the
        // structure's centre would overwrite intentional NBT content.
        // Skip the entire paint when the connection's source or
        // destination is a replacement AND `dy != 0`. Flat horizontal
        // pathways still draw; the carve-doorway logic in the loop below
        // limits their reach to the structure's outer X/Z wall layer.
        if (v.dy != 0 &&
            (isStructureReplacementNode(cellX, cellY, cellZ) ||
             isStructureReplacementNode(nCellX, nCellY, nCellZ))
        ) {
            return
        }

        // Compute a tight bbox for this connection's possible block placements.
        val xLo: Int; val xHi: Int; val yLo: Int; val yHi: Int; val zLo: Int; val zHi: Int
        if (v.dx == 0 && v.dz == 0) {
            // Pure vertical ladder: 3×3 column footprint, full Y span.
            xLo = a.nx + 1; xHi = a.nx + 3
            zLo = a.nz + 1; zHi = a.nz + 3
            yLo = Math.min(a.ny, b.ny); yHi = Math.max(a.ny, b.ny)
        } else {
            // Horizontal / diagonal corridor: spans both platforms, plus
            // corridor + stair-undertread one Y below.
            xLo = Math.min(a.nx, b.nx); xHi = Math.max(a.nx, b.nx) + FLOOR_SIZE - 1
            zLo = Math.min(a.nz, b.nz); zHi = Math.max(a.nz, b.nz) + FLOOR_SIZE - 1
            yLo = Math.min(a.ny, b.ny) - 1; yHi = Math.max(a.ny, b.ny)
        }

        val clipXLo = Math.max(xLo, baseX)
        val clipXHi = Math.min(xHi, baseX + cacheSize - 1)
        if (clipXLo > clipXHi) return
        val clipZLo = Math.max(zLo, baseZ)
        val clipZHi = Math.min(zHi, baseZ + cacheSize - 1)
        if (clipZLo > clipZHi) return
        val clipYLo = Math.max(yLo, baseY)
        val clipYHi = Math.min(yHi, baseY + span - 1)
        if (clipYLo > clipYHi) return

        // Iterate the bbox, calling the existing connectionBlockX/Y/Z to keep
        // a single source of truth for block geometry. When two connections
        // overlap (most commonly stairwell vs stairwell where both flights
        // have the same slope and cross at the midpoint), a per-position
        // priority guard prevents fill blocks from clobbering player-
        // navigation blocks placed by another connection.
        for (wx in clipXLo..clipXHi) {
            val lx = wx - baseX
            val xPlane = cache[lx]
            for (wz in clipZLo..clipZHi) {
                val lz = wz - baseZ
                val col = xPlane[lz]
                for (wy in clipYLo..clipYHi) {
                    val block = connectionBlockForVector(wx, wy, wz, a, b, v) ?: continue
                    val ly = wy - baseY
                    val existing = col[ly]
                    if (existing != null && !canOverwriteForConnection(existing, block)) continue

                    // Inside a structure-replacement footprint: only carve
                    // at the OUTER X/Z wall layer (the 1-block boundary).
                    // At that wall the corridor's floor block replaces the
                    // wall's floor-Y block and the two cells above are
                    // cleared so the doorway is walkable. Past the wall
                    // (interior) the replacement's NBT owns every block —
                    // skip the write so intentional centre-pieces survive.
                    if (isInsideStructureReplacement(wx, wy, wz)) {
                        if (!isStructureReplacementOuterWall(wx, wy, wz)) continue
                        col[ly] = block
                        val headLy1 = wy + 1 - baseY
                        if (headLy1 in 0 until span) col[headLy1] = null
                        val headLy2 = wy + 2 - baseY
                        if (headLy2 in 0 until span) col[headLy2] = null
                        continue
                    }

                    col[ly] = block
                    // 2-tall rings sometimes drop a slab at the corridor's
                    // headroom Y (PATH_Y+1 = player feet). Clear ring blocks
                    // in the two cells above the corridor floor so the
                    // walkway stays open. The bitmap check keeps us from
                    // clearing anything ELSE that might legitimately sit
                    // here (e.g. an earlier corridor pass's stair / ladder
                    // already filtered by `canOverwriteForConnection`).
                    val headLy1 = wy + 1 - baseY
                    if (headLy1 in 0 until span && isInsideRingDecoration(wx, wy + 1, wz)) {
                        col[headLy1] = null
                    }
                    val headLy2 = wy + 2 - baseY
                    if (headLy2 in 0 until span && isInsideRingDecoration(wx, wy + 2, wz)) {
                        col[headLy2] = null
                    }
                }
            }
        }
    }

    /** Per-position priority for the connection paint pass. Returns true iff
     *  `incoming` may replace `existing` at the same cache position.
     *
     *  **Critical** (player-navigation) blocks — stairs, ladders, trapdoors —
     *  may only be overwritten by another critical block. This keeps a stair
     *  flight intact when a different flight's brick undertread (a fill
     *  block) lands on the same coordinate, which happens whenever two stair
     *  flights with the same slope cross at the midpoint. */
    private fun canOverwriteForConnection(existing: BlockState, incoming: BlockState): Boolean {
        if (!isCriticalConnectionBlock(existing)) return true
        return isCriticalConnectionBlock(incoming)
    }

    private fun isCriticalConnectionBlock(block: BlockState): Boolean =
        block.`is`(Blocks.POLISHED_DEEPSLATE_STAIRS) ||
            block.block is LadderBlock ||
            block.block is TrapDoorBlock


    /** Pre-compute every position that [decorBlockAt] would produce. Optimized
     *  to only call [decorBlockAt] at "candidate" positions — those within ±1
     *  cache column and 0..+2 Y of an actual path block. For a column spanning
     *  many path Y levels (e.g., a stairwell column), this avoids the previous
     *  O(span) scan and only touches the few Ys near actual paths. */
    private fun buildDecorCache(
        pathCache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
    ): Array<Array<Array<BlockState?>>> {
        val decor = Array(cacheSize) { Array(cacheSize) { arrayOfNulls<BlockState>(span) } }
        val candidate = BooleanArray(cacheSize * cacheSize * span)
        for (lx in 0 until cacheSize) {
            val xLo = Math.max(0, lx - 1); val xHi = Math.min(cacheSize - 1, lx + 1)
            for (lz in 0 until cacheSize) {
                val col = pathCache[lx][lz]
                val zLo = Math.max(0, lz - 1); val zHi = Math.min(cacheSize - 1, lz + 1)
                for (y in 0 until span) {
                    if (col[y] == null) continue
                    // Mark slab(y), wall(y+1), chiseled-top/lantern(y+2) in all
                    // 3×3 neighbours of this path block.
                    for (nx in xLo..xHi) {
                        for (nz in zLo..zHi) {
                            val baseIdx = (nx * cacheSize + nz) * span
                            candidate[baseIdx + y] = true
                            if (y + 1 < span) candidate[baseIdx + y + 1] = true
                            if (y + 2 < span) candidate[baseIdx + y + 2] = true
                        }
                    }
                }
            }
        }
        // Precompute the wall-outline bit for every candidate position
        // before the dispatch pass below. The dispatch's wall-connectivity
        // computation then reads four neighbour bits per wall position
        // (via [wallOutlineAt]) instead of redoing isWallOutline +
        // isPathFloor for each cardinal.
        //
        // Restrict BOTH passes to the chunk's own positions
        // (`lx, lz in 2 until cacheSize - 2` — the inner 16×16 the
        // chunk actually reads from `decorCache[localX + 2]`). The
        // 2-block buffer either side was iterated only so that wall
        // recompute at the buffer-adjacent column could read
        // `wallBits` for ±1 neighbours, but iterating the buffer
        // pulled `isWallOutline` into computing `isPathFloor` at
        // positions ±2 outside the pathCache window — which falls
        // through to a fresh `mazeBlockAt` per query. That single
        // fallthrough was the dominant 25s cost in the chunk-gen
        // hot path. Wall recompute at chunk-edge positions now
        // reads the buffer's `wallBits = false` (its initialised
        // value), and `wallOutlineAt` falls back to a fresh
        // `isWallOutline` compute that only needs in-cache
        // `isPathFloor` lookups — handful of calls at the chunk
        // perimeter per Y, dwarfed by the savings.
        val wallBits = BooleanArray(cacheSize * cacheSize * span)
        val edgeLo = 2
        val edgeHi = cacheSize - 2
        for (lx in edgeLo until edgeHi) {
            val pathCol = pathCache[lx]
            val wx = baseX + lx
            for (lz in edgeLo until edgeHi) {
                val col = pathCol[lz]
                val baseIdx = (lx * cacheSize + lz) * span
                val wz = baseZ + lz
                for (y in 0 until span) {
                    if (!candidate[baseIdx + y]) continue
                    if (col[y] != null) continue
                    if (isWallOutline(wx, baseY + y, wz)) {
                        wallBits[baseIdx + y] = true
                    }
                }
            }
        }
        val ctx = activeCaches.get()!!
        ctx.wallBits = wallBits
        ctx.wallBitsBaseX = baseX
        ctx.wallBitsBaseZ = baseZ
        ctx.wallBitsBaseY = baseY
        ctx.wallBitsCacheSize = cacheSize
        ctx.wallBitsSpan = span

        for (lx in edgeLo until edgeHi) {
            for (lz in edgeLo until edgeHi) {
                val pathCol = pathCache[lx][lz]
                val decorCol = decor[lx][lz]
                val baseIdx = (lx * cacheSize + lz) * span
                val wx = baseX + lx
                val wz = baseZ + lz
                for (y in 0 until span) {
                    if (!candidate[baseIdx + y]) continue
                    if (pathCol[y] != null) continue
                    decorCol[y] = decorBlockAt(wx, baseY + y, wz)
                }
            }
        }
        return decor
    }

    /** Cache-aware wall-outline query. Reads the precomputed bit from
     *  [ChunkCaches.wallBits] when in range; falls back to a fresh
     *  [isWallOutline] compute outside the cache window (or when no
     *  caches are active, e.g. structure-placement probing). */
    private fun wallOutlineAt(wx: Int, wy: Int, wz: Int): Boolean {
        val ctx = activeCaches.get() ?: return isWallOutline(wx, wy, wz)
        val bits = ctx.wallBits ?: return isWallOutline(wx, wy, wz)
        val lx = wx - ctx.wallBitsBaseX
        val lz = wz - ctx.wallBitsBaseZ
        val ly = wy - ctx.wallBitsBaseY
        val size = ctx.wallBitsCacheSize
        val span = ctx.wallBitsSpan
        if (lx < 0 || lx >= size) return isWallOutline(wx, wy, wz)
        if (lz < 0 || lz >= size) return isWallOutline(wx, wy, wz)
        if (ly < 0 || ly >= span) return isWallOutline(wx, wy, wz)
        return bits[(lx * size + lz) * span + ly]
    }


    /** Build the cube exclusion mask. Covers every position any cube touched
     *  by this chunk could occupy, plus +1 padding. Marks: axial strip,
     *  every platform, every realised connection bbox. */
    private fun buildCubeExclude(
        exclBaseX: Int, exclBaseZ: Int, baseY: Int, exclSize: Int, span: Int,
    ): BooleanArray {
        val excl = BooleanArray(exclSize * exclSize * span)
        val exclMaxX = exclBaseX + exclSize - 1
        val exclMaxZ = exclBaseZ + exclSize - 1
        val maxY = baseY + span - 1

        // Axial strip + 1 padding, full Y, full mask extent in the orthogonal axis.
        val stripPadLo = PATH_RANGE.first - 1
        val stripPadHi = PATH_RANGE.last + 1
        markExclusionBox(excl, exclBaseX, exclBaseZ, baseY, exclSize, span,
            stripPadLo, stripPadHi, baseY, maxY, exclBaseZ, exclMaxZ, pad = false)
        markExclusionBox(excl, exclBaseX, exclBaseZ, baseY, exclSize, span,
            exclBaseX, exclMaxX, baseY, maxY, stripPadLo, stripPadHi, pad = false)

        // Cells whose connections / platforms could touch the exclusion window.
        val minCellX = Math.floorDiv(exclBaseX - GRID_OFFSET, MAZE_CELL_X) - 1
        val maxCellX = Math.floorDiv(exclMaxX - GRID_OFFSET, MAZE_CELL_X) + 1
        val minCellY = Math.floorDiv(baseY, MAZE_CELL_Y) - 1
        val maxCellY = Math.floorDiv(maxY, MAZE_CELL_Y) + 1
        val minCellZ = Math.floorDiv(exclBaseZ - GRID_OFFSET, MAZE_CELL_Z) - 1
        val maxCellZ = Math.floorDiv(exclMaxZ - GRID_OFFSET, MAZE_CELL_Z) + 1
        for (cellX in minCellX..maxCellX) {
            for (cellY in minCellY..maxCellY) {
                for (cellZ in minCellZ..maxCellZ) {
                    val node = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                    // Platform 5×5×1 at node.ny.
                    markExclusionBox(excl, exclBaseX, exclBaseZ, baseY, exclSize, span,
                        node.nx, node.nx + FLOOR_SIZE - 1,
                        node.ny, node.ny,
                        node.nz, node.nz + FLOOR_SIZE - 1,
                        pad = true)
                    // Outgoing connections.
                    for (vIdx in pickConnections(cellX, cellY, cellZ)) {
                        if (IS_STAIRWELL_VEC[vIdx] && !stairwellAllowed(cellX, cellY, cellZ, vIdx)) continue
                        val v = CONN_VECTORS[vIdx]
                        mazeNodeAt(cellX + v.dx, cellY + v.dy, cellZ + v.dz) ?: continue
                        val bbox = connectionBBox(cellX, cellY, cellZ, vIdx)
                        markExclusionBox(excl, exclBaseX, exclBaseZ, baseY, exclSize, span,
                            bbox[0], bbox[1], bbox[2], bbox[3], bbox[4], bbox[5],
                            pad = true)
                    }
                }
            }
        }
        return excl
    }

    /** Mark `excl[lx,lz,ly]=true` for every position inside the given bbox
     *  (with optional ±1 padding on every axis), clipped to the mask window. */
    private fun markExclusionBox(
        excl: BooleanArray,
        baseX: Int, baseZ: Int, baseY: Int, size: Int, span: Int,
        xLo: Int, xHi: Int, yLo: Int, yHi: Int, zLo: Int, zHi: Int,
        pad: Boolean,
    ) {
        val p = if (pad) 1 else 0
        val clipXLo = Math.max(xLo - p, baseX)
        val clipXHi = Math.min(xHi + p, baseX + size - 1)
        if (clipXLo > clipXHi) return
        val clipYLo = Math.max(yLo - p, baseY)
        val clipYHi = Math.min(yHi + p, baseY + span - 1)
        if (clipYLo > clipYHi) return
        val clipZLo = Math.max(zLo - p, baseZ)
        val clipZHi = Math.min(zHi + p, baseZ + size - 1)
        if (clipZLo > clipZHi) return
        for (wx in clipXLo..clipXHi) {
            val lx = wx - baseX
            for (wz in clipZLo..clipZHi) {
                val lz = wz - baseZ
                val baseIdx = (lx * size + lz) * span
                for (wy in clipYLo..clipYHi) {
                    excl[baseIdx + (wy - baseY)] = true
                }
            }
        }
    }


    /** Iterate slot positions in the chunk's column. Each slot picks (via
     *  hash) one of [FEATURE_TYPE_COUNT] feature types. Anchor-in-column
     *  ownership: a feature is painted by exactly the chunk whose column
     *  contains its anchor, and the feature must fit within that column.
     *  This means features never straddle chunk boundaries and there's no
     *  slicing. Per-feature `try*` functions check anchor-fit + exclusion
     *  mask before writing. */
    private fun paintFeaturesInto(
        featureCache: Array<Array<Array<BlockState?>>>,
        excl: BooleanArray,
        exclBaseX: Int, exclBaseZ: Int, exclSize: Int,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        chunkX0: Int, chunkZ0: Int,
    ) {
        val chunkXEnd = chunkX0 + 15
        val chunkZEnd = chunkZ0 + 15
        val maxY = baseY + span - 1
        val offsetSpan = 2 * SLOT_JITTER + 1
        val minSlotX = Math.floorDiv(chunkX0 - SLOT_JITTER, SLOT_SIZE)
        val maxSlotX = Math.floorDiv(chunkXEnd + SLOT_JITTER, SLOT_SIZE)
        val minSlotY = Math.floorDiv(baseY - SLOT_JITTER, SLOT_SIZE)
        val maxSlotY = Math.floorDiv(maxY + SLOT_JITTER, SLOT_SIZE)
        val minSlotZ = Math.floorDiv(chunkZ0 - SLOT_JITTER, SLOT_SIZE)
        val maxSlotZ = Math.floorDiv(chunkZEnd + SLOT_JITTER, SLOT_SIZE)

        for (slotX in minSlotX..maxSlotX) {
            for (slotY in minSlotY..maxSlotY) {
                for (slotZ in minSlotZ..maxSlotZ) {
                    val ax = slotX * SLOT_SIZE + positiveMod(hash(slotX, slotY, slotZ, 17), offsetSpan) - SLOT_JITTER
                    if (ax < chunkX0 || ax > chunkXEnd) continue
                    val az = slotZ * SLOT_SIZE + positiveMod(hash(slotX, slotY, slotZ, 23), offsetSpan) - SLOT_JITTER
                    if (az < chunkZ0 || az > chunkZEnd) continue
                    val ay = slotY * SLOT_SIZE + positiveMod(hash(slotX, slotY, slotZ, 19), offsetSpan) - SLOT_JITTER
                    if (ay < baseY || ay > maxY) continue
                    if (positiveMod(hash(slotX, slotY, slotZ, 11), 100) < FEATURE_SKIP_PERCENT) continue

                    val ft = positiveMod(hash(slotX, slotY, slotZ, 13), FEATURE_TYPE_COUNT)
                    val seed = hash(slotX, slotY, slotZ, 29)
                    when (ft) {
                        0 -> tryFloatingShelf(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, maxY)
                        1 -> tryShelfTower(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, maxY)
                        2 -> tryShelfWall(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY)
                        3 -> tryFurnitureIsland(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY)
                        4 -> tryReadingNook(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY)
                        5 -> tryChandelier(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY)
                        6 -> tryTomeShrine(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY)
                        7 -> tryWallCorner(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY)
                        8 -> trySpiralPillar(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY)
                        9 -> tryLecternGarden(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY)
                        10 -> tryTotem(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, maxY)
                        11 -> tryCubeOfSize(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY, size = 5, hollow = false)
                        12 -> tryCubeOfSize(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY, size = 7, hollow = true)
                        13 -> tryCubeOfSize(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY, size = 11, hollow = true)
                        14 -> tryBambooFloor(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY)
                        15 -> tryLecternRoom(featureCache, excl, exclBaseX, exclBaseZ, exclSize, baseX, baseZ, baseY, cacheSize, span, ax, ay, az, seed, chunkX0, chunkXEnd, chunkZ0, chunkZEnd, maxY)
                    }
                }
            }
        }
    }

    private fun isFeatureExcluded(
        excl: BooleanArray, exclBaseX: Int, exclBaseZ: Int, exclSize: Int, baseY: Int, span: Int,
        wx: Int, wy: Int, wz: Int,
    ): Boolean {
        val cx = wx - exclBaseX
        if (cx !in 0 until exclSize) return true
        val cz = wz - exclBaseZ
        if (cz !in 0 until exclSize) return true
        val cy = wy - baseY
        if (cy !in 0 until span) return true
        return excl[(cx * exclSize + cz) * span + cy]
    }

    private fun putFeatureBlock(
        featureCache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        wx: Int, wy: Int, wz: Int, block: BlockState,
    ) {
        val cx = wx - baseX
        if (cx !in 0 until cacheSize) return
        val cz = wz - baseZ
        if (cz !in 0 until cacheSize) return
        val cy = wy - baseY
        if (cy !in 0 until span) return
        featureCache[cx][cz][cy] = block
    }

    /** Mark every still-null position in the given bbox as explicit AIR — each
     *  feature calls this to carve its clearance out of the surrounding library
     *  mass. Without this, the dimension's default chiseled-bookshelf fill
     *  would bury the feature in solid books. */
    private fun fillBboxAir(
        fc: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        xLo: Int, xHi: Int, yLo: Int, yHi: Int, zLo: Int, zHi: Int,
    ) {
        for (wx in xLo..xHi) {
            val cx = wx - baseX
            if (cx !in 0 until cacheSize) continue
            for (wz in zLo..zHi) {
                val cz = wz - baseZ
                if (cz !in 0 until cacheSize) continue
                for (wy in yLo..yHi) {
                    val cy = wy - baseY
                    if (cy !in 0 until span) continue
                    if (fc[cx][cz][cy] == null) fc[cx][cz][cy] = AIR
                }
            }
        }
    }

    private fun tryFloatingShelf(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int, maxY: Int,
    ) {
        val chainLen = 2 + positiveMod(seed, 5)
        if (ay + chainLen > maxY) return
        if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay, az)) return
        for (i in 1..chainLen) if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay + i, az)) return
        putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay, az, CHISELED_BOOKSHELF)
        for (i in 1..chainLen) putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay + i, az, CHAIN)
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + 1, ay, ay + chainLen, az - 1, az + 1)
    }

    private fun tryShelfTower(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int, maxY: Int,
    ) {
        val height = 3 + positiveMod(seed, 6)
        val capped = positiveMod(seed ushr 4, 4) > 0
        val topY = if (capped) ay + height + 1 else ay + height - 1
        if (topY > maxY) return
        for (i in 0 until height) if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay + i, az)) return
        if (capped) {
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay + height, az)) return
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay + height + 1, az)) return
        }
        for (i in 0 until height) putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay + i, az, CHISELED_BOOKSHELF)
        if (capped) {
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay + height, az, CHISELED_DEEPSLATE)
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay + height + 1, az, LIT_CANDLE)
        }
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + 1, ay - 1, topY + 1, az - 1, az + 1)
    }

    private fun tryShelfWall(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
    ) {
        val width = 3 + positiveMod(seed, 3)            // 3-5
        val height = 2 + positiveMod(seed ushr 4, 3)    // 2-4
        val alongZ = positiveMod(seed ushr 8, 2) == 0
        val xEnd = if (alongZ) ax else ax + width - 1
        val zEnd = if (alongZ) az + width - 1 else az
        if (xEnd > cxHi || zEnd > czHi || ay + height - 1 > maxY) return
        for (w in 0 until width) for (h in 0 until height) {
            val x = if (alongZ) ax else ax + w
            val z = if (alongZ) az + w else az
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, x, ay + h, z)) return
        }
        for (w in 0 until width) for (h in 0 until height) {
            val x = if (alongZ) ax else ax + w
            val z = if (alongZ) az + w else az
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, x, ay + h, z, CHISELED_BOOKSHELF)
        }
        val xLoP = if (alongZ) ax - 1 else ax - 1
        val xHiP = if (alongZ) ax + 1 else ax + width
        val zLoP = if (alongZ) az - 1 else az - 1
        val zHiP = if (alongZ) az + width else az + 1
        fillBboxAir(fc, bX, bZ, bY, cs, sp, xLoP, xHiP, ay - 1, ay + height, zLoP, zHiP)
    }

    private fun tryFurnitureIsland(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
    ) {
        if (ax + 2 > cxHi || az + 2 > czHi) return
        val chainLen = 3 + positiveMod(seed, 4)
        if (ay + chainLen > maxY) return
        // 3×3 floor at ay (player walks on top at ay+1).
        for (dx in 0..2) for (dz in 0..2)
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + dx, ay, az + dz)) return
        val cx = ax + 1
        val cz = az + 1
        // Lectern at centre of floor (sits on top, so at ay+1).
        if (ay + 1 > maxY) return
        if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, cx, ay + 1, cz)) return
        // Candle at one of the 4 corners on top of floor.
        val candleCorner = positiveMod(seed ushr 4, 4)
        val candleX = ax + intArrayOf(0, 2, 0, 2)[candleCorner]
        val candleZ = az + intArrayOf(0, 0, 2, 2)[candleCorner]
        if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, candleX, ay + 1, candleZ)) return
        // Chain rising from opposite corner.
        val opp = (candleCorner + 3) and 3
        val chainX = ax + intArrayOf(0, 2, 0, 2)[opp]
        val chainZ = az + intArrayOf(0, 0, 2, 2)[opp]
        for (i in 1..chainLen) if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, chainX, ay + i, chainZ)) return

        for (dx in 0..2) for (dz in 0..2)
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + dx, ay, az + dz, DEEPSLATE_BRICKS)
        val lecternFacing = LADDER_FACINGS[positiveMod(seed ushr 12, 4)]
        putFeatureBlock(fc, bX, bZ, bY, cs, sp, cx, ay + 1, cz,
            LECTERN_DEFAULT.setValue(LecternBlock.FACING, lecternFacing))
        putFeatureBlock(fc, bX, bZ, bY, cs, sp, candleX, ay + 1, candleZ, LIT_CANDLE)
        for (i in 1..chainLen) putFeatureBlock(fc, bX, bZ, bY, cs, sp, chainX, ay + i, chainZ, CHAIN)
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + 3, ay - 1, ay + chainLen + 1, az - 1, az + 3)
    }

    private fun tryReadingNook(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
    ) {
        // Linear 3-block arrangement along +X at z=az: chair at ax, lectern at ax+1,
        // bookshelf column 3 tall at ax+2. Floor 3 deepslate bricks at ay-1.
        if (ax + 2 > cxHi || ay + 2 > maxY || ay - 1 < bY) return
        val facing = Direction.EAST   // chair/lectern face the shelf wall
        for (dx in 0..2) if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + dx, ay - 1, az)) return
        if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay, az)) return       // chair
        if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + 1, ay, az)) return   // lectern
        for (h in 0..2) if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + 2, ay + h, az)) return

        for (dx in 0..2) putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + dx, ay - 1, az, DEEPSLATE_BRICKS)
        putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay, az,
            DEEPSLATE_BRICK_STAIRS_BASE.setValue(StairBlock.FACING, facing))
        putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + 1, ay, az,
            LECTERN_DEFAULT.setValue(LecternBlock.FACING, facing))
        for (h in 0..2) putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + 2, ay + h, az, CHISELED_BOOKSHELF)
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + 3, ay - 1, ay + 3, az - 1, az + 1)
    }

    private fun tryChandelier(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
    ) {
        if (ax - 1 < cxLo || ax + 1 > cxHi || az - 1 < czLo || az + 1 > czHi) return
        if (ay - 1 < bY) return
        val chainLen = 2 + positiveMod(seed, 4)
        if (ay + chainLen > maxY) return
        // Central shelf at (ax, ay, az). Arms at (ax±1, ay, az) and (ax, ay, az±1).
        // Lanterns one below each arm. Chain rises from (ax, ay+1..ay+chainLen, az).
        if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay, az)) return
        val arms = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        for (a in arms) {
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + a[0], ay, az + a[1])) return
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + a[0], ay - 1, az + a[1])) return
        }
        for (i in 1..chainLen) if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay + i, az)) return

        putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay, az, CHISELED_BOOKSHELF)
        for (a in arms) {
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + a[0], ay, az + a[1], CHISELED_BOOKSHELF)
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + a[0], ay - 1, az + a[1], LANTERN)
        }
        for (i in 1..chainLen) putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay + i, az, CHAIN)
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 2, ax + 2, ay - 2, ay + chainLen + 1, az - 2, az + 2)
    }

    private fun tryTomeShrine(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
    ) {
        if (ax + 2 > cxHi || az + 2 > czHi) return
        val height = 4 + positiveMod(seed, 3)  // 4-6
        if (ay + height + 1 > maxY) return
        val cx = ax + 1
        val cz = az + 1
        // Floor 3×3 at ay (chiseled centre).
        for (dx in 0..2) for (dz in 0..2)
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + dx, ay, az + dz)) return
        // Column.
        for (i in 1..height) if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, cx, ay + i, cz)) return
        // Lantern cap.
        if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, cx, ay + height + 1, cz)) return

        for (dx in 0..2) for (dz in 0..2) {
            val block = if (dx == 1 && dz == 1) CHISELED_DEEPSLATE else DEEPSLATE_BRICKS
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + dx, ay, az + dz, block)
        }
        for (i in 1..height) putFeatureBlock(fc, bX, bZ, bY, cs, sp, cx, ay + i, cz, CHISELED_BOOKSHELF)
        putFeatureBlock(fc, bX, bZ, bY, cs, sp, cx, ay + height + 1, cz, LANTERN)
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + 3, ay - 1, ay + height + 2, az - 1, az + 3)
    }

    private fun tryWallCorner(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
    ) {
        val len = 3 + positiveMod(seed, 2)              // 3-4
        val height = 2 + positiveMod(seed ushr 4, 2)    // 2-3
        if (ax + len - 1 > cxHi || az + len - 1 > czHi || ay + height - 1 > maxY) return
        // Wall A along +X at z=az. Wall B along +Z at x=ax (shared corner at ax,_,az).
        for (i in 0 until len) for (h in 0 until height) {
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + i, ay + h, az)) return
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay + h, az + i)) return
        }
        for (i in 0 until len) for (h in 0 until height) {
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + i, ay + h, az, CHISELED_BOOKSHELF)
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay + h, az + i, CHISELED_BOOKSHELF)
        }
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + len, ay - 1, ay + height, az - 1, az + len)
    }

    private fun trySpiralPillar(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
    ) {
        if (ax - 1 < cxLo || ax + 1 > cxHi || az - 1 < czLo || az + 1 > czHi) return
        val height = 5 + positiveMod(seed, 4)  // 5-8
        if (ay + height - 1 > maxY) return
        val dxArr = intArrayOf(1, 0, -1, 0)
        val dzArr = intArrayOf(0, 1, 0, -1)
        val phase = positiveMod(seed ushr 4, 4)
        for (i in 0 until height) {
            val p = (phase + i) and 3
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + dxArr[p], ay + i, az + dzArr[p])) return
        }
        for (i in 0 until height) {
            val p = (phase + i) and 3
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + dxArr[p], ay + i, az + dzArr[p], CHISELED_BOOKSHELF)
        }
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 2, ax + 2, ay - 1, ay + height, az - 2, az + 2)
    }

    private fun tryLecternGarden(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
    ) {
        if (ax + 2 > cxHi || az + 2 > czHi || ay + 1 > maxY) return
        for (dx in 0..2) for (dz in 0..2)
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + dx, ay, az + dz)) return
        // 3 lecterns at 3 deterministic positions among the 9 floor cells.
        val positions = IntArray(3)
        var s = seed
        for (k in 0..2) {
            positions[k] = positiveMod(s, 9)
            s = s ushr 3
            // Avoid duplicates (cheap rejection: nudge by 1).
            for (j in 0 until k) if (positions[k] == positions[j]) positions[k] = (positions[k] + 1) % 9
        }
        for (p in positions) {
            val dx = p / 3; val dz = p % 3
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + dx, ay + 1, az + dz)) return
        }

        for (dx in 0..2) for (dz in 0..2) putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + dx, ay, az + dz, DEEPSLATE_TILES)
        for ((k, p) in positions.withIndex()) {
            val dx = p / 3; val dz = p % 3
            val facing = LADDER_FACINGS[positiveMod(seed ushr (12 + k * 2), 4)]
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + dx, ay + 1, az + dz,
                LECTERN_DEFAULT.setValue(LecternBlock.FACING, facing))
        }
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + 3, ay - 1, ay + 2, az - 1, az + 3)
    }

    /** Generic wireframe cube. Corners → CHISELED_DEEPSLATE, edges →
     *  DEEPSLATE_BRICKS, faces → air windows, interior → BOOKSHELF when
     *  `hollow=false` else air. Chains rise 2-5 blocks from each of the 4
     *  top corners. */
    private fun tryCubeOfSize(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
        size: Int, hollow: Boolean,
    ) {
        val end = size - 1
        if (ax + end > cxHi || az + end > czHi || ay + end > maxY) return
        val chainLen = 2 + positiveMod(seed, 4)  // 2-5
        if (ay + end + chainLen > maxY) return

        // Exclusion check: skip face positions (extrema==1) and interior
        // positions in hollow cubes (extrema==0 && hollow).
        for (lx in 0..end) {
            val xE = lx == 0 || lx == end
            for (lz in 0..end) {
                val zE = lz == 0 || lz == end
                for (ly in 0..end) {
                    val yE = ly == 0 || ly == end
                    val extrema = (if (xE) 1 else 0) + (if (yE) 1 else 0) + (if (zE) 1 else 0)
                    if (extrema == 1) continue
                    if (extrema == 0 && hollow) continue
                    if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + lx, ay + ly, az + lz)) return
                }
            }
        }
        // Chain positions.
        for (cornerIdx in 0..3) {
            val cx = if ((cornerIdx and 1) == 0) ax else ax + end
            val cz = if ((cornerIdx and 2) == 0) az else az + end
            for (i in 1..chainLen) {
                if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, cx, ay + end + i, cz)) return
            }
        }

        for (lx in 0..end) {
            val xE = lx == 0 || lx == end
            for (lz in 0..end) {
                val zE = lz == 0 || lz == end
                for (ly in 0..end) {
                    val yE = ly == 0 || ly == end
                    val extrema = (if (xE) 1 else 0) + (if (yE) 1 else 0) + (if (zE) 1 else 0)
                    val block: BlockState = when (extrema) {
                        3 -> CHISELED_DEEPSLATE
                        2 -> DEEPSLATE_BRICKS
                        1 -> AIR                                 // face window
                        0 -> if (hollow) AIR else CHISELED_BOOKSHELF  // interior
                        else -> AIR
                    }
                    putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + lx, ay + ly, az + lz, block)
                }
            }
        }
        for (cornerIdx in 0..3) {
            val cx = if ((cornerIdx and 1) == 0) ax else ax + end
            val cz = if ((cornerIdx and 2) == 0) az else az + end
            for (i in 1..chainLen) {
                putFeatureBlock(fc, bX, bZ, bY, cs, sp, cx, ay + end + i, cz, CHAIN)
            }
        }
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + end + 1, ay - 1, ay + end + chainLen + 1, az - 1, az + end + 1)
    }

    private fun tryBambooFloor(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
    ) {
        val size = if (positiveMod(seed, 2) == 0) 5 else 7
        val end = size - 1
        if (ax + end > cxHi || az + end > czHi || ay + 1 > maxY) return
        // Floor 5×5 or 7×7 at Y=ay.
        for (dx in 0..end) for (dz in 0..end) {
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + dx, ay, az + dz)) return
        }
        // Bookshelf positions scattered on top.
        val numShelves = 3 + positiveMod(seed ushr 4, 5)  // 3-7
        val area = size * size
        val positions = IntArray(numShelves)
        var s = seed ushr 8
        for (k in 0 until numShelves) {
            var p = positiveMod(s, area)
            s = s * 1103515245 + 12345
            // Reject duplicates.
            var attempts = 0
            while (attempts < area) {
                var dup = false
                for (j in 0 until k) if (positions[j] == p) { dup = true; break }
                if (!dup) break
                p = (p + 1) % area
                attempts++
            }
            positions[k] = p
        }
        for (p in positions) {
            val dx = p / size; val dz = p % size
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + dx, ay + 1, az + dz)) return
        }

        for (dx in 0..end) for (dz in 0..end) {
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + dx, ay, az + dz, BAMBOO_PLANKS)
        }
        for (p in positions) {
            val dx = p / size; val dz = p % size
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + dx, ay + 1, az + dz, CHISELED_BOOKSHELF)
        }
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + end + 1, ay - 1, ay + 2, az - 1, az + end + 1)
    }

    private fun tryLecternRoom(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int,
        cxLo: Int, cxHi: Int, czLo: Int, czHi: Int, maxY: Int,
    ) {
        if (ax + 4 > cxHi || az + 4 > czHi || ay + 3 > maxY) return
        val cx = ax + 2
        val cz = az + 2
        val doorSide = positiveMod(seed, 4)  // 0=N(z-), 1=S(z+), 2=E(x+), 3=W(x-)

        // 5×5 floor at Y=ay.
        for (dx in 0..4) for (dz in 0..4) {
            if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + dx, ay, az + dz)) return
        }
        // 5×5 perimeter walls at Y=ay+1..ay+3, with 1×2 door cutout on one side.
        for (h in 1..3) {
            for (dx in 0..4) for (dz in 0..4) {
                if (dx != 0 && dx != 4 && dz != 0 && dz != 4) continue
                val isDoor = h <= 2 && when (doorSide) {
                    0 -> dx == 2 && dz == 0
                    1 -> dx == 2 && dz == 4
                    2 -> dx == 4 && dz == 2
                    else -> dx == 0 && dz == 2
                }
                if (isDoor) continue
                if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax + dx, ay + h, az + dz)) return
            }
        }
        // Lectern atop centre of floor.
        if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, cx, ay + 1, cz)) return

        for (dx in 0..4) for (dz in 0..4) {
            putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + dx, ay, az + dz, DEEPSLATE_BRICKS)
        }
        for (h in 1..3) {
            for (dx in 0..4) for (dz in 0..4) {
                if (dx != 0 && dx != 4 && dz != 0 && dz != 4) continue
                val isDoor = h <= 2 && when (doorSide) {
                    0 -> dx == 2 && dz == 0
                    1 -> dx == 2 && dz == 4
                    2 -> dx == 4 && dz == 2
                    else -> dx == 0 && dz == 2
                }
                if (isDoor) continue
                putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax + dx, ay + h, az + dz, CHISELED_BOOKSHELF)
            }
        }
        val facing = LADDER_FACINGS[positiveMod(seed ushr 4, 4)]
        putFeatureBlock(fc, bX, bZ, bY, cs, sp, cx, ay + 1, cz,
            LECTERN_DEFAULT.setValue(LecternBlock.FACING, facing))
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + 5, ay - 1, ay + 4, az - 1, az + 5)
    }

    private fun tryTotem(
        fc: Array<Array<Array<BlockState?>>>, excl: BooleanArray,
        ebX: Int, ebZ: Int, es: Int,
        bX: Int, bZ: Int, bY: Int, cs: Int, sp: Int,
        ax: Int, ay: Int, az: Int, seed: Int, maxY: Int,
    ) {
        val coreHeight = 4 + positiveMod(seed, 3)  // 4-6
        val topY = ay + coreHeight + 1
        if (topY > maxY) return
        if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay, az)) return
        for (i in 1..coreHeight) if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, ay + i, az)) return
        if (isFeatureExcluded(excl, ebX, ebZ, es, bY, sp, ax, topY, az)) return

        putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay, az, CHISELED_DEEPSLATE)
        for (i in 1..coreHeight) putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, ay + i, az, CHISELED_BOOKSHELF)
        putFeatureBlock(fc, bX, bZ, bY, cs, sp, ax, topY, az, CHISELED_DEEPSLATE)
        fillBboxAir(fc, bX, bZ, bY, cs, sp, ax - 1, ax + 1, ay - 1, topY + 1, az - 1, az + 1)
    }

    private fun connectionBlockForVector(
        wx: Int, wy: Int, wz: Int, a: MazeNode, b: MazeNode, v: ConnVec,
    ): BlockState? = when {
        v.dx == 0 && v.dz == 0 -> {
            val lo: MazeNode; val hi: MazeNode
            if (v.dy > 0) { lo = a; hi = b } else { lo = b; hi = a }
            connectionBlockY(wx, wy, wz, lo, hi)
        }
        v.dx != 0 -> {
            val left: MazeNode; val right: MazeNode
            if (v.dx > 0) { left = a; right = b } else { left = b; right = a }
            connectionBlockX(wx, wy, wz, left, right)
        }
        else -> {
            val front: MazeNode; val back: MazeNode
            if (v.dz > 0) { front = a; back = b } else { front = b; back = a }
            connectionBlockZ(wx, wy, wz, front, back)
        }
    }


    /** "Is the block at (wx, wy, wz) a walkable surface the wall-decor
     *  pass should fire walls / slabs / lanterns adjacent to?"
     *
     *  Cache-first detection — same as the original painter. Whatever
     *  the procedural paint pipeline wrote (corridor floor, platform
     *  floor, structure-replacement interior, custom-path NBT content)
     *  shows up here as a non-null cache block and qualifies UNLESS:
     *    - The material is one of the four "navigation, not floor"
     *      blocks (ladders, deepslate, deepslate-bricks, stairs).
     *    - The position is owned by the rings pass (and not currently
     *      cut by a corridor — corridor floors overwrite ring slabs
     *      where they cross).
     *    - The position is inside a salt NBT region (salt brings its
     *      own decor, double-stacking would create floating walls /
     *      duplicate carpets).
     *    - The position is inside a structure replacement (the
     *      structure NBT already has its perimeter walls). */
    private fun isPathFloor(wx: Int, wy: Int, wz: Int): Boolean {
        val block = pathBlockAt(wx, wy, wz) ?: return false
        if (block.block is LadderBlock) return false
        if (block.`is`(Blocks.DEEPSLATE)) return false
        if (block.`is`(Blocks.DEEPSLATE_BRICKS)) return false
        if (block.`is`(Blocks.POLISHED_DEEPSLATE_STAIRS)) return false
        if (isInsideRingDecoration(wx, wy, wz)) return false
        if (isInsideSalt(wx, wy, wz)) return false
        if (isInsideStructureReplacement(wx, wy, wz)) return false
        // The cell-top battlement caps' DEEPSLATE_TILES posts and the
        // DEEPSLATE_BRICK_STAIRS edge row would otherwise pass the path-
        // floor check and trigger procedural wall outlines / slab
        // outlines / candle decoration on top of the parapet. Treat the
        // whole battlement footprint as non-path-floor so the maze decor
        // pass leaves the crown alone.
        if (isInsideCellTopBattlement(wx, wy, wz)) return false
        return true
    }

    /** True iff `(wx, wy, wz)` sits anywhere inside the footprint of a
     *  structure-replacement placement logged during fillFromNoise's
     *  setup pass. The structure owns its full footprint volume — maze
     *  decor (carpet, walls, slabs) and connection paint defer to its
     *  NBT content here, with only the outer wall layer carved by
     *  horizontal corridors via [isStructureReplacementOuterWall]. The
     *  per-placement dimensions come from [StructureReplacement] so each
     *  type contributes a different footprint. */
    /** True iff `(wx, wy, wz)` holds a [RingDecoration] block — same
     *  bitmap dims as [isInsideStructureReplacement] (rings reuse the
     *  structure bits' window). Material-agnostic: any future ring NBT
     *  using a new palette automatically participates. */
    private fun isInsideCorridorBbox(wx: Int, wy: Int, wz: Int): Boolean {
        val ctx = activeCaches.get() ?: return false
        val bits = ctx.corridorBits ?: return false
        val lx = wx - ctx.structBitsBaseX
        val ly = wy - ctx.structBitsBaseY
        val lz = wz - ctx.structBitsBaseZ
        if (lx < 0 || lx >= ctx.structBitsWidthX) return false
        if (ly < 0 || ly >= ctx.structBitsWidthY) return false
        if (lz < 0 || lz >= ctx.structBitsWidthZ) return false
        return bits.get((lx * ctx.structBitsWidthY + ly) * ctx.structBitsWidthZ + lz)
    }

    private fun isInsideSalt(wx: Int, wy: Int, wz: Int): Boolean {
        val ctx = activeCaches.get() ?: return false
        val bits = ctx.saltBits ?: return false
        val lx = wx - ctx.structBitsBaseX
        val ly = wy - ctx.structBitsBaseY
        val lz = wz - ctx.structBitsBaseZ
        if (lx < 0 || lx >= ctx.structBitsWidthX) return false
        if (ly < 0 || ly >= ctx.structBitsWidthY) return false
        if (lz < 0 || lz >= ctx.structBitsWidthZ) return false
        return bits.get((lx * ctx.structBitsWidthY + ly) * ctx.structBitsWidthZ + lz)
    }

    private fun isInsideRingDecoration(wx: Int, wy: Int, wz: Int): Boolean {
        val ctx = activeCaches.get() ?: return false
        val bits = ctx.ringInsideBits ?: return false
        val lx = wx - ctx.structBitsBaseX
        val ly = wy - ctx.structBitsBaseY
        val lz = wz - ctx.structBitsBaseZ
        if (lx < 0 || lx >= ctx.structBitsWidthX) return false
        if (ly < 0 || ly >= ctx.structBitsWidthY) return false
        if (lz < 0 || lz >= ctx.structBitsWidthZ) return false
        return bits.get((lx * ctx.structBitsWidthY + ly) * ctx.structBitsWidthZ + lz)
    }

    private fun isInsideStructureReplacement(wx: Int, wy: Int, wz: Int): Boolean {
        val ctx = activeCaches.get() ?: return false
        val bits = ctx.structInsideBits ?: return false
        val lx = wx - ctx.structBitsBaseX
        val ly = wy - ctx.structBitsBaseY
        val lz = wz - ctx.structBitsBaseZ
        if (lx < 0 || lx >= ctx.structBitsWidthX) return false
        if (ly < 0 || ly >= ctx.structBitsWidthY) return false
        if (lz < 0 || lz >= ctx.structBitsWidthZ) return false
        return bits.get((lx * ctx.structBitsWidthY + ly) * ctx.structBitsWidthZ + lz)
    }

    /** True iff `(wx, wy, wz)` is on the OUTER X-or-Z wall layer of a
     *  structure-replacement footprint (the 1-block-thick boundary on
     *  each of the four side faces). Horizontal corridors carve a
     *  doorway here: the position's floor block is replaced by the
     *  corridor's floor and the two cells above are cleared. Y
     *  boundaries are intentionally excluded — the structure owns its
     *  top and bottom and corridors never enter via the floor or
     *  ceiling face. */
    private fun isStructureReplacementOuterWall(wx: Int, wy: Int, wz: Int): Boolean {
        val ctx = activeCaches.get() ?: return false
        val bits = ctx.structOuterWallBits ?: return false
        val lx = wx - ctx.structBitsBaseX
        val ly = wy - ctx.structBitsBaseY
        val lz = wz - ctx.structBitsBaseZ
        if (lx < 0 || lx >= ctx.structBitsWidthX) return false
        if (ly < 0 || ly >= ctx.structBitsWidthY) return false
        if (lz < 0 || lz >= ctx.structBitsWidthZ) return false
        return bits.get((lx * ctx.structBitsWidthY + ly) * ctx.structBitsWidthZ + lz)
    }

    /** True iff the given cell hosts a structure replacement (any
     *  type). Used by [paintConnectionPick] to early-out on vertical /
     *  diagonal connections whose endpoint is a replacement node —
     *  those would otherwise plant ladder columns, trapdoors, or stair
     *  treads in the structure's centre and overwrite intentional NBT
     *  content. */
    private fun isStructureReplacementNode(cellX: Int, cellY: Int, cellZ: Int): Boolean {
        // Observatory columns are tall by construction — always block.
        if (hasObservatoryColumnAt(cellX, cellZ)) return true
        // Only RARE set-pieces (Globe, Gallery, Mosaic, Shelves, Enchant,
        // Specimen Sculk, Viewing Platform) block vertical pierce-throughs
        // — their interior carries authored NBT content a stair flight
        // would clobber. Every NORMAL `PLATFORM_n` variant (`isRare =
        // false`) is just a decorated 7×7 floor platform with low walls;
        // a stair flight punches the wall via the doorway exactly the
        // same way it would on a 5×5 procedural floor, so allowing it
        // through restores the per-cell vertical density the maze relied
        // on before every eligible cell started hosting a structure.
        val type = replacementTypeAt(cellX, cellY, cellZ) ?: return false
        return type.isRare
    }

    /** Common headroom check shared by [isSlabOutline] and [isWallOutline]:
     *  the position itself must be empty AND no path block sits 1 or 2 blocks
     *  below it (those Y values are player headroom — walls/slabs there would
     *  block stairs or corridors). */
    private fun decorHeadroomClear(wx: Int, wy: Int, wz: Int): Boolean {
        if (pathBlockAt(wx, wy, wz) != null) return false
        if (pathBlockAt(wx, wy - 1, wz) != null) return false
        if (pathBlockAt(wx, wy - 2, wz) != null) return false
        return true
    }

    /** Triggered when the candidate is at the SAME Y as an adjacent path floor.
     *  Placed as a top-half deepslate-brick slab to fill the floor-level half
     *  of the visual barrier. Walls sit one block above this. */
    private fun isSlabOutline(wx: Int, wy: Int, wz: Int): Boolean {
        if (!decorHeadroomClear(wx, wy, wz)) return false

        val ePath = isPathFloor(wx + 1, wy, wz)
        val wPath = isPathFloor(wx - 1, wy, wz)
        val nPath = isPathFloor(wx, wy, wz - 1)
        val sPath = isPathFloor(wx, wy, wz + 1)

        if ((ePath && wPath) || (nPath && sPath)) return false
        if (ePath || wPath || nPath || sPath) return true

        // Unrolled 4-diagonal check — was `for (dx in intArrayOf(-1, 1))
        // for (dz in intArrayOf(-1, 1))`, which allocated two IntArrays
        // per call in the per-candidate hot loop.
        return isPathFloor(wx - 1, wy, wz - 1) ||
            isPathFloor(wx - 1, wy, wz + 1) ||
            isPathFloor(wx + 1, wy, wz - 1) ||
            isPathFloor(wx + 1, wy, wz + 1)
    }

    /** Triggered when the candidate is ONE BLOCK ABOVE an adjacent path floor.
     *  Becomes a deepslate-brick wall — combined with the slab below it forms
     *  a ~1.5-tall barrier. Walls **never** sit at floor level (those bottoms
     *  were the original 2-tall design's source of blockstate confusion; the
     *  slab replaces them). */
    private fun isWallOutline(wx: Int, wy: Int, wz: Int): Boolean {
        if (!decorHeadroomClear(wx, wy, wz)) return false

        val ePath = isPathFloor(wx + 1, wy - 1, wz)
        val wPath = isPathFloor(wx - 1, wy - 1, wz)
        val nPath = isPathFloor(wx, wy - 1, wz - 1)
        val sPath = isPathFloor(wx, wy - 1, wz + 1)

        if ((ePath && wPath) || (nPath && sPath)) return false
        if (ePath || wPath || nPath || sPath) return true

        // Same unrolling as [isSlabOutline]. Hot loop.
        return isPathFloor(wx - 1, wy - 1, wz - 1) ||
            isPathFloor(wx - 1, wy - 1, wz + 1) ||
            isPathFloor(wx + 1, wy - 1, wz - 1) ||
            isPathFloor(wx + 1, wy - 1, wz + 1)
    }

    /** Decoration layer (pass 2). At each empty position, returns one of:
     *    - slab outline (at path_Y, floor level)  → top deepslate-brick slab
     *    - wall outline (at path_Y+1)             → deepslate-brick wall
     *    - one above a wall                       → chiseled-top OR lantern
     *  Chiseled columns (hash-selected) replace ALL three layers with a
     *  3-tall CHISELED_DEEPSLATE post. Bamboo wall signs hang on the
     *  interior side of chiseled posts (45% chance per post). */
    private fun decorBlockAt(wx: Int, wy: Int, wz: Int): BlockState? {
        // Salt regions are self-contained — their NBT brings the entire
        // wall / archway / lantern / carpet kit. The procedural decor
        // pass is shut off across the salt's full 3D footprint to
        // prevent floating walls above NBT archways, double carpets
        // on top of NBT carpets, etc.
        if (isInsideSalt(wx, wy, wz)) return null
        if (isSlabOutline(wx, wy, wz)) {
            if (isChiseledColumn(wx, wz)) return CHISELED_DEEPSLATE
            return DEEPSLATE_BRICK_SLAB_TOP
        }
        if (wallOutlineAt(wx, wy, wz)) {
            if (isChiseledColumn(wx, wz)) return CHISELED_DEEPSLATE
            val lectern = lecternWallAt(wx, wy, wz)
            if (lectern != null) return lectern

            // Neighbour-wall connectivity. Mirrors vanilla
            // [WallBlock.connectsTo]: connect to walls
            // ([BlockTags.WALLS]), iron bars, perpendicular fence
            // gates, and to any non-exception block whose face facing
            // the wall is sturdy. PathCache is consulted FIRST so the
            // decor wall connects to NBT salt / axial-arch /
            // observatory / globe / gallery / mosaic / shelves /
            // enchant material directly; outside pathCache the
            // procedural decor / structure-outer-wall predicates
            // round-trip through the same connection rules.
            val east  = decorWallNeighborConnects(wx + 1, wy, wz, Direction.EAST)
            val west  = decorWallNeighborConnects(wx - 1, wy, wz, Direction.WEST)
            val north = decorWallNeighborConnects(wx, wy, wz - 1, Direction.NORTH)
            val south = decorWallNeighborConnects(wx, wy, wz + 1, Direction.SOUTH)
            // Walls are only 1 tall (no wall above this), so the only source
            // of an above-block is a lantern / candle cluster placed by the
            // wall-top decoration pool. UP=true is needed so the centre post
            // renders for the decoration to sit on.
            val above = wallTopDecorPresent(wx, wz)
            return makeWallState(east, west, north, south, above)
        }
        // One block above a wall outline: chiseled-post top, or one of
        // the wall-top decoration-pool blocks (lantern / 1..4 lit
        // yellow candles) hanging over a non-chiseled wall.
        if (wallOutlineAt(wx, wy - 1, wz)) {
            if (isChiseledColumn(wx, wz)) return CHISELED_DEEPSLATE
            val decoration = wallTopDecoration(wx, wz)
            if (decoration != null) return decoration
        }
        // Bamboo sign hung on the interior face of a chiseled post — at the
        // air column 2 blocks above a corridor floor (i.e., the same Y as
        // the TOP block of the 3-tall chiseled post), with the post in one
        // cardinal direction.
        if (isPathFloor(wx, wy - 2, wz)) {
            val sign = chiseledPostSignAt(wx, wy, wz)
            if (sign != null) return sign
        }
        return null
    }

    /** Decor-wall connection predicate. Returns true when a maze decor
     *  wall placed at `(wx, wy, wz)`'s 4-neighbour should bond to it
     *  with a LOW connection. Mirrors vanilla [WallBlock.connectsTo]:
     *
     *   - PathCache is consulted FIRST so any NBT material at the
     *     neighbour position runs through the same connection rules as
     *     vanilla (`BlockTags.WALLS` → connect; iron bars / fence gate
     *     → connect; full-face sturdy → connect; otherwise no connect).
     *     AIR / AIR-fill positions correctly report no-connect because
     *     vanilla's first branch fails on air-state.
     *   - Outside pathCache we fall back to procedural decor / outer-
     *     wall / salt predicates, swapping in a representative
     *     [BlockState] (`DEEPSLATE_BRICK_WALL` for decor outlines,
     *     `DEEPSLATE_BRICKS` for outer walls and salt) so the same
     *     connection check yields the right answer.
     *
     *  [fromDecorWallToNeighbour] is the direction FROM the decor wall
     *  position TO the queried neighbour — `Direction.EAST` when
     *  computing the EAST connection, etc. */
    private fun decorWallNeighborConnects(
        wx: Int, wy: Int, wz: Int, fromDecorWallToNeighbour: Direction,
    ): Boolean {
        val ctx = activeCaches.get()
        val cache = ctx?.pathCache
        if (cache != null) {
            val lx = wx - ctx.pathBaseX
            val lz = wz - ctx.pathBaseZ
            val ly = wy - ctx.pathBaseY
            if (lx in 0 until cache.size && lz in 0 until cache[0].size && ly in 0 until ctx.pathSpan) {
                // In-window: pathCache is the source of truth. null
                // means genuinely empty (corridor headroom, tunnel
                // interior, structure carved space) — do NOT consult
                // the salt / outer-wall bitmaps here, since they cover
                // the structure's full 3D footprint and would over-
                // connect into empty volume. Only other decor walls
                // ([wallOutlineAt]) supplement the cache miss.
                val cached = cache[lx][lz][ly]
                if (cached != null) {
                    return wallConnectsToNeighbour(cached, fromDecorWallToNeighbour)
                }
                if (wallOutlineAt(wx, wy, wz)) {
                    return wallConnectsToNeighbour(
                        DEEPSLATE_BRICK_WALL_DEFAULT, fromDecorWallToNeighbour,
                    )
                }
                return false
            }
        }
        // Outside the chunk's cache window — predict from the
        // procedural predicates that know which positions the
        // neighbouring chunk WILL host structure outer walls / salt /
        // decor walls at. `DEEPSLATE_BRICKS` stands in as a generic
        // sturdy-face block for the structure / salt cases; vanilla's
        // sturdy-face branch in [wallConnectsToNeighbour] then yields
        // the LOW connection.
        if (wallOutlineAt(wx, wy, wz)) {
            return wallConnectsToNeighbour(DEEPSLATE_BRICK_WALL_DEFAULT, fromDecorWallToNeighbour)
        }
        if (isStructureReplacementOuterWall(wx, wy, wz)) {
            return wallConnectsToNeighbour(DEEPSLATE_BRICKS, fromDecorWallToNeighbour)
        }
        if (isInsideSalt(wx, wy, wz)) {
            return wallConnectsToNeighbour(DEEPSLATE_BRICKS, fromDecorWallToNeighbour)
        }
        return false
    }

    /** Hash-based "this XZ column hosts a 3-tall chiseled post wherever it
     *  intersects a wall outline" decision. */
    private fun isChiseledColumn(wx: Int, wz: Int): Boolean =
        positiveMod(mazeHash(wx, 0, wz, 53), 100) < CHISELED_WALL_PERCENT

    /** Hash-based "wall-top decoration sits over the wall in this XZ
     *  column" decision — fires for both lanterns and lit yellow candle
     *  clusters, the two decoration types in the wall-top pool. Only
     *  takes effect over non-chiseled wall outlines (chiseled columns
     *  replace the wall outright with the post). The wall update logic
     *  reads this to decide UP=true so the wall renders its centre post
     *  for the candle / lantern to sit on. */
    private fun wallTopDecorPresent(wx: Int, wz: Int): Boolean =
        positiveMod(mazeHash(wx, 0, wz, 41), LANTERN_INTERVAL) == 0

    /** Block placed one block above a decor wall outline when
     *  [wallTopDecorPresent] fires. Splits between LANTERN and a 1..4
     *  lit yellow candle cluster on independent hash salts (so the type
     *  choice and the candle count don't correlate with the existence
     *  roll). */
    private fun wallTopDecoration(wx: Int, wz: Int): BlockState? {
        if (!wallTopDecorPresent(wx, wz)) return null
        val typeRoll = positiveMod(mazeHash(wx, 0, wz, 42), 100)
        if (typeRoll < WALL_TOP_LANTERN_PERCENT) return LANTERN
        val count = 1 + positiveMod(mazeHash(wx, 0, wz, 43), 4)
        return YELLOW_CANDLES_LIT[count - 1]
    }

    /** Deterministic corridor width — either 1 or 3, never 2. Canonicalises
     *  `(a, b)` ordering so each connection has a single width regardless of
     *  which endpoint drives the call. */
    private fun corridorWidth(a: MazeNode, b: MazeNode): Int {
        // Avoid the `Pair` allocation by branching directly on which
        // node sorts lower. Hot path — called from paint/frame/mazeBlockAt.
        val lower: MazeNode; val upper: MazeNode
        if (a.nx + a.nz * 1_000_003 + a.ny * 7919L < b.nx + b.nz * 1_000_003 + b.ny * 7919L) {
            lower = a; upper = b
        } else {
            lower = b; upper = a
        }
        val h = mazeHash(lower.nx, lower.ny, lower.nz, 19) xor
                mazeHash(upper.nx, upper.ny, upper.nz, 31)
        return if ((h and 1) == 0) 1 else 3
    }


    private fun isPathStrip(x: Int, z: Int): Boolean =
        x in PATH_RANGE || z in PATH_RANGE

    /** Vanilla-rule wall blockstate.
     *
     *  Side: TALL when neighbour connects + block above, LOW when neighbour
     *  connects + no block above, NONE otherwise.
     *  UP: matches `WallBlock.shouldRaisePost`:
     *    1. Block above (covers post / stacked wall) → UP=true.
     *    2. All 4 sides NONE → UP=true.
     *    3. (N is NONE) != (S is NONE)  OR  (E is NONE) != (W is NONE) → UP=true.
     *    4. (N TALL && S TALL) OR (E TALL && W TALL) → UP=false (straight or X).
     *    5. Otherwise → UP=false (default; no covering block we can detect).
     *
     *  Final blockstate is looked up from [WALL_STATES_BY_KEY] — every valid
     *  combination of (east, west, north, south, up, hasBlockAbove) is
     *  precomputed at class load, so this function just packs a 6-bit key
     *  and returns the interned state. */
    private fun makeWallState(
        east: Boolean, west: Boolean, north: Boolean, south: Boolean, hasBlockAbove: Boolean,
    ): BlockState {
        val up = when {
            hasBlockAbove -> true
            !east && !west && !north && !south -> true
            north != south || east != west -> true
            (north && south) || (east && west) -> false
            else -> false
        }
        val key = (if (east) WALL_KEY_EAST else 0) or
            (if (west) WALL_KEY_WEST else 0) or
            (if (north) WALL_KEY_NORTH else 0) or
            (if (south) WALL_KEY_SOUTH else 0) or
            (if (up) WALL_KEY_UP else 0) or
            (if (hasBlockAbove) WALL_KEY_ABOVE else 0)
        return WALL_STATES_BY_KEY[key]
    }


    private fun cubeBlockAt(wx: Int, wy: Int, wz: Int): BlockState? {
        val minCellX = Math.floorDiv(wx - FRAME_SIZE + 1 - OFFSET_RANGE, CELL_SIZE)
        val maxCellX = Math.floorDiv(wx + OFFSET_RANGE, CELL_SIZE)
        val minCellY = Math.floorDiv(wy - FRAME_SIZE + 1 - OFFSET_RANGE, CELL_SIZE)
        val maxCellY = Math.floorDiv(wy + OFFSET_RANGE, CELL_SIZE)
        val minCellZ = Math.floorDiv(wz - FRAME_SIZE + 1 - OFFSET_RANGE, CELL_SIZE)
        val maxCellZ = Math.floorDiv(wz + OFFSET_RANGE, CELL_SIZE)
        val offsetSpan = 2 * OFFSET_RANGE + 1
        val maxLocal = FRAME_SIZE - 1

        var hasEdge = false
        var hasInterior = false
        for (cellX in minCellX..maxCellX) {
            val fx = cellX * CELL_SIZE +
                positiveMod(hash(cellX, 0, 0, 11), offsetSpan) - OFFSET_RANGE
            val lx = wx - fx
            if (lx !in 0..maxLocal) continue
            for (cellY in minCellY..maxCellY) {
                val fy = cellY * CELL_SIZE +
                    positiveMod(hash(cellX, cellY, 0, 23), offsetSpan) - OFFSET_RANGE
                val ly = wy - fy
                if (ly !in 0..maxLocal) continue
                for (cellZ in minCellZ..maxCellZ) {
                    val fz = cellZ * CELL_SIZE +
                        positiveMod(hash(cellX, cellY, cellZ, 37), offsetSpan) - OFFSET_RANGE
                    val lz = wz - fz
                    if (lz !in 0..maxLocal) continue
                    if (!cubeIsValid(fx, fy, fz)) continue
                    var extrema = 0
                    if (lx == 0 || lx == maxLocal) extrema++
                    if (ly == 0 || ly == maxLocal) extrema++
                    if (lz == 0 || lz == maxLocal) extrema++
                    when (extrema) {
                        3 -> return CHISELED_DEEPSLATE
                        2 -> hasEdge = true
                        0 -> hasInterior = true
                    }
                }
            }
        }
        return when {
            hasEdge -> DEEPSLATE_BRICKS
            hasInterior -> BOOKSHELF
            else -> null
        }
    }

    private fun cubeIsValid(fx: Int, fy: Int, fz: Int): Boolean {
        if (fy < MIN_Y) return false
        if (fy + FRAME_SIZE > MIN_Y + WORLD_HEIGHT) return false
        return true
    }

    //
    // **Grid layout.** Every `(cellX, cellY, cellZ)` cell hosts a 5×5 platform
    // at deterministic position — no random offsets. Each platform picks up to
    // 2 outgoing connections from 14 candidates:
    //
    //   - 4 horizontal pathways   (dy = 0, |dx| + |dz| = 1)
    //   - 2 vertical ladders      (|dy| = 1, dx = dz = 0)
    //   - 8 diagonal stairwells   (|dy| = 1, |dx| + |dz| = 1)
    //
    // Connections are realised even if the neighbour cell hosts a platform
    // (which it always does in pure grid mode). Each connection is drawn by
    // BOTH endpoints' iterations, so the same wall/floor blocks get placed
    // either way — symmetric.

    private data class MazeNode(val nx: Int, val ny: Int, val nz: Int, val seed: Int)

    /** A picked [StructureReplacement] at a specific [MazeNode]. Carried in
     *  the chunk's `replacementsInRange` cache so footprint queries (which
     *  are per-position, type-blind) can dispatch to the right per-type
     *  dimensions without re-rolling the placement hash. */
    private data class StructurePlacement(val node: MazeNode, val type: StructureReplacement)

    /** A `sselith_axial_arch.nbt` stamp replacing the topmost cross-axial
     *  procedural arch in a given column. [alongAxisIsZ] mirrors the
     *  procedural arch's "long axis": true = arch spans world Z (at fixed
     *  worldX = [archCoord]), false = arch spans world X (at fixed
     *  worldZ = [archCoord]). [cellY] is the column's top arch cellY —
     *  determined by the same parity rule [gapArchwayBlock] uses. */
    private data class AxialArchPlacement(
        val alongAxisIsZ: Boolean,
        val archCoord: Int,
        val cellY: Int,
    )

    /** Allocation-free existence check: would this cell host a platform on
     *  its own (skip-hash + Y-bounds) ignoring neighbours? Top-cell layer is
     *  excluded so platforms stop one cell below the world ceiling — same
     *  treatment the library tile gets, which lets chains rise into clear
     *  sky above the topmost real platform. */
    private fun mazeNodeBaseExists(cellX: Int, cellY: Int, cellZ: Int): Boolean {
        if (cellY >= TOP_CELL_Y) return false
        if (positiveMod(mazeHash(cellX, cellY, cellZ, 0), 100) >= PLATFORM_PRESENCE_PERCENT) return false
        val ny = cellY * MAZE_CELL_Y
        return ny >= MIN_Y + STAIR_DROP + 2 && ny <= MIN_Y + WORLD_HEIGHT - 4
    }

    /** Every base-existing cell materialises. Drawn corridors are still
     *  guarded by `mazeNodeAt(neighbour) ?: continue` in the paint pass, so a
     *  pick landing on a Y-out-of-bounds or skip-hash-failed cell silently
     *  drops without leaving an orphan corridor. Reciprocity is not required;
     *  enforcing it dropped ~78% of platforms and limited chains to 2-3.
     *
     *  Platform X/Z is shifted by [LIBRARY_QUADRANT_SHIFT] away from the
     *  origin (per quadrant) so the platform stays anchored at the same
     *  cell-local position as the surrounding library cell. */
    private fun mazeNodeAt(cellX: Int, cellY: Int, cellZ: Int): MazeNode? =
        mazeNodeAtCtx(activeCaches.get(), cellX, cellY, cellZ)

    /** Pre-fetched-context variant of [mazeNodeAt]. Hot loops (paint
     *  passes, lectern-map build, cube-exclude, frame build) should
     *  hoist `activeCaches.get()` once and use this — profile (R4)
     *  showed `mazeNodeAt` at 11.0 self/s, much of which was the
     *  per-call ThreadLocal hop. */
    private fun mazeNodeAtCtx(ctx: ChunkCaches?, cellX: Int, cellY: Int, cellZ: Int): MazeNode? {
        val cache = ctx?.nodeCache
        if (cache != null) {
            val dx = cellX - ctx.nodeBaseX
            val dy = cellY - ctx.nodeBaseY
            val dz = cellZ - ctx.nodeBaseZ
            if (dx in 0 until ctx.nodeWidthX &&
                dy in 0 until ctx.nodeWidthY &&
                dz in 0 until ctx.nodeWidthZ
            ) {
                return cache[(dy * ctx.nodeWidthZ + dz) * ctx.nodeWidthX + dx]
            }
        }
        return computeMazeNodeAt(cellX, cellY, cellZ)
    }

    /** Uncached MazeNode construction. Used by both the [mazeNodeAt]
     *  fall-through path and the up-front [ChunkCaches.nodeCache]
     *  population in [fillFromNoise]. Returns null when the cell has
     *  no platform — base existence is the only acceptance check. */
    private fun computeMazeNodeAt(cellX: Int, cellY: Int, cellZ: Int): MazeNode? {
        if (!mazeNodeBaseExists(cellX, cellY, cellZ)) return null
        // Grid Y is anchored at world Y=0 so cellY=0 sits on the axial path
        // plane (PATH_Y=0). One TL hop shared between the two origin lookups.
        val ctx = activeCaches.get()
        val ny = cellY * MAZE_CELL_Y
        val nx = platformOriginXCtx(ctx, cellX)
        val nz = platformOriginZCtx(ctx, cellZ)
        return MazeNode(nx, ny, nz, mazeHash(cellX, cellY, cellZ, 7))
    }

    /** World X of the SW corner of the 5×5 platform for `cellX`, with the
     *  per-quadrant library shift applied. The positive side gets the same
     *  extra +1 block applied to the library effX formula so the platform
     *  stays aligned with its surrounding library cell (the cell wall on
     *  the gap-facing side lands at the first non-gap worldX). */
    private fun platformOriginX(cellX: Int): Int =
        platformOriginXCtx(activeCaches.get(), cellX)

    /** Pre-fetched-context variant of [platformOriginX] for hot loops.
     *  Callers that already have a [ChunkCaches] handy (e.g.
     *  [connectionBBoxCtx], [computeMazeNodeAt]) skip the per-call
     *  ThreadLocal lookup. Profile (R3) showed `ThreadLocalMap.getEntry`
     *  doubled after the origin caches landed; this overload reclaims
     *  that. */
    private fun platformOriginXCtx(ctx: ChunkCaches?, cellX: Int): Int {
        val cache = ctx?.originXCache
        if (cache != null) {
            val i = cellX - ctx.originXBase
            if (i in cache.indices) return cache[i]
        }
        return computePlatformOriginX(cellX)
    }

    /** Uncached arithmetic — used by both the [platformOriginX] cache
     *  miss path and the cache-population pass in [fillFromNoise]. */
    private fun computePlatformOriginX(cellX: Int): Int {
        val raw = cellX * MAZE_CELL_X + GRID_OFFSET
        return if (raw >= 0) raw + LIBRARY_QUADRANT_SHIFT + 1
        else raw - LIBRARY_QUADRANT_SHIFT
    }

    /** World Z of the SW corner of the 5×5 platform for `cellZ`, with the
     *  per-quadrant library shift applied. The +Z side gets the additional
     *  [POSITIVE_Z_EXTRA_SHIFT] block AND the +1 wall-alignment block. */
    private fun platformOriginZ(cellZ: Int): Int =
        platformOriginZCtx(activeCaches.get(), cellZ)

    private fun platformOriginZCtx(ctx: ChunkCaches?, cellZ: Int): Int {
        val cache = ctx?.originZCache
        if (cache != null) {
            val i = cellZ - ctx.originZBase
            if (i in cache.indices) return cache[i]
        }
        return computePlatformOriginZ(cellZ)
    }

    private fun computePlatformOriginZ(cellZ: Int): Int {
        val raw = cellZ * MAZE_CELL_Z + GRID_OFFSET
        return if (raw >= 0) raw + LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT + 1
        else raw - LIBRARY_QUADRANT_SHIFT
    }

    /** Per-cell random ladder injection for the inner-wall shelving. Each of
     *  the four walls has a single ladder column (one block inward from the
     *  shelf column). For each (cell, wall, vertical-slot) tuple, two hashes
     *  decide whether a ladder exists in that slot and at which along-wall
     *  tile position it sits.
     *
     *  Returns the appropriate ladder blockstate if the requested position
     *  is part of such a ladder, otherwise null. */
    private fun archwayLadderAt(
        cellX: Int, cellY: Int, cellZ: Int,
        tileX: Int, tileY: Int, tileZ: Int,
    ): BlockState? {
        if (tileY < ARCHWAY_SHELF_FIRST_Y || tileY > MAZE_CELL_Y - 2) return null
        val slotOffset = tileY - ARCHWAY_SHELF_FIRST_Y
        if (slotOffset % ARCHWAY_SHELF_SPACING == 0) return null   // shelf row
        val slotIdx = slotOffset / ARCHWAY_SHELF_SPACING
        val maxX = MAZE_CELL_X - 1
        val maxZ = MAZE_CELL_Z - 1
        val alongRangeWE = MAZE_CELL_Z - 2   // valid along positions 1..maxZ-1
        val alongRangeNS = MAZE_CELL_X - 2
        return when {
            tileX == 2 && ladderHere(cellX, cellY, cellZ, ARCHWAY_WALL_W, slotIdx, tileZ - 1, alongRangeWE) -> LADDER_EAST
            tileX == maxX - 2 && ladderHere(cellX, cellY, cellZ, ARCHWAY_WALL_E, slotIdx, tileZ - 1, alongRangeWE) -> LADDER_WEST
            tileZ == 2 && ladderHere(cellX, cellY, cellZ, ARCHWAY_WALL_N, slotIdx, tileX - 1, alongRangeNS) -> LADDER_SOUTH
            tileZ == maxZ - 2 && ladderHere(cellX, cellY, cellZ, ARCHWAY_WALL_S, slotIdx, tileX - 1, alongRangeNS) -> LADDER_NORTH
            else -> null
        }
    }

    /** True if `(worldX, worldZ)` sits on one of the decorative deepslate-
     *  tile columns that line the gap between the axial paths and the
     *  library walls. Each side of the gap has a single column line one
     *  block in from the wall, with pillars placed every
     *  [GAP_COLUMN_INTERVAL] blocks along the perpendicular axis. Positions
     *  on the axial path itself are skipped so the path stays clear. */
    private fun isGapColumn(worldX: Int, worldZ: Int): Boolean {
        val zInterval = worldZ !in PATH_RANGE &&
            positiveMod(worldZ, GAP_COLUMN_INTERVAL) == 0
        val xInterval = worldX !in PATH_RANGE &&
            positiveMod(worldX, GAP_COLUMN_INTERVAL) == 0
        return (worldX == GAP_COLUMN_POS_X && zInterval) ||
            (worldX == GAP_COLUMN_NEG_X && zInterval) ||
            (worldZ == GAP_COLUMN_POS_Z && xInterval) ||
            (worldZ == GAP_COLUMN_NEG_Z && xInterval)
    }

    /** Block placed at `(worldX, tileYMod, worldZ)` for a gap archway, or
     *  null. Three flavours, all built from the same base shoulder pattern:
     *
     *  - **Gap-col-line arches** (at `worldX = ±GAP_COLUMN_POS/NEG_X` or
     *    `worldZ = ±GAP_COLUMN_POS/NEG_Z`) — either the long path-crossing
     *    arch (span = 2·INTERVAL = 16, uses the 3-row "domed" pattern with
     *    a Y=24 outer lintel and Y=23 top-slab centre) or an arm arch
     *    between adjacent columns (span = INTERVAL = 8, uses the 2-row
     *    pattern; parity XOR-shifted by `cellY`).
     *  - **Cross-path arches** at every `N·INTERVAL` of `worldX` (resp.
     *    `worldZ`) — repeated along the axial path, using the Z-gap (resp.
     *    X-gap) column line as endpoints. Parity-gated per `cellY` like
     *    the arm arches. */
    private fun gapArchwayBlock(
        worldX: Int, tileYMod: Int, worldZ: Int, cellY: Int,
    ): BlockState? {
        val topY = MAZE_CELL_Y - 1
        val stepDepth = topY - tileYMod  // -1 = Y=top+1, 0 = Y=top, 1 = Y=top-1
        if (stepDepth < -1 || stepDepth > 1) return null
        if (worldX == GAP_COLUMN_POS_X || worldX == GAP_COLUMN_NEG_X) {
            return gapColLineArchAt(worldZ, cellY, stepDepth, alongAxisIsZ = true)
        }
        if (worldZ == GAP_COLUMN_POS_Z || worldZ == GAP_COLUMN_NEG_Z) {
            return gapColLineArchAt(worldX, cellY, stepDepth, alongAxisIsZ = false)
        }
        if (stepDepth in 0..1) {
            // Cross-path arches repeat along the X-axis path at every
            // INTERVAL of worldX (parity-gated per cellY).
            if (worldX !in PATH_RANGE && worldX != 0 &&
                positiveMod(worldX, GAP_COLUMN_INTERVAL) == 0 &&
                Math.floorMod(worldX / GAP_COLUMN_INTERVAL + cellY, 2) == 0
            ) {
                // NBT axial-arch replacement owns this column at its top
                // cellY — suppress the procedural arch at EVERY cellY in
                // the same column so the player sees only the NBT arch
                // and not a stack of arches all the way down.
                if (isInAxialArchColumn(worldX, worldZ)) return null
                return archOldPattern(
                    worldZ, GAP_COLUMN_NEG_Z, GAP_COLUMN_POS_Z,
                    stepDepth, alongAxisIsZ = true,
                )
            }
            // …and along the Z-axis path at every INTERVAL of worldZ.
            if (worldZ !in PATH_RANGE && worldZ != 0 &&
                positiveMod(worldZ, GAP_COLUMN_INTERVAL) == 0 &&
                Math.floorMod(worldZ / GAP_COLUMN_INTERVAL + cellY, 2) == 0
            ) {
                if (isInAxialArchColumn(worldX, worldZ)) return null
                return archOldPattern(
                    worldX, GAP_COLUMN_NEG_X, GAP_COLUMN_POS_X,
                    stepDepth, alongAxisIsZ = false,
                )
            }
        }
        return null
    }

    private fun gapColLineArchAt(
        coord: Int, cellY: Int, stepDepth: Int, alongAxisIsZ: Boolean,
    ): BlockState? {
        val lowerCoord: Int
        val upperCoord: Int
        val pathCrossing: Boolean
        if (coord in -GAP_COLUMN_INTERVAL..GAP_COLUMN_INTERVAL) {
            lowerCoord = -GAP_COLUMN_INTERVAL
            upperCoord = GAP_COLUMN_INTERVAL
            pathCrossing = true
        } else {
            val lowerCol = Math.floorDiv(coord, GAP_COLUMN_INTERVAL)
            lowerCoord = lowerCol * GAP_COLUMN_INTERVAL
            upperCoord = lowerCoord + GAP_COLUMN_INTERVAL
            if (lowerCoord == 0 || upperCoord == 0) return null
            if (lowerCoord in PATH_RANGE || upperCoord in PATH_RANGE) return null
            if (Math.floorMod(lowerCol + cellY, 2) != 0) return null
            pathCrossing = false
        }
        return if (pathCrossing) {
            archNewPattern(coord, lowerCoord, upperCoord, stepDepth, alongAxisIsZ)
        } else {
            if (stepDepth !in 0..1) null
            else archOldPattern(coord, lowerCoord, upperCoord, stepDepth, alongAxisIsZ)
        }
    }

    /** 3-row "domed" pattern for spans long enough to fit a Y=24 outer
     *  lintel + Y=23 top-slab centre on top of the standard shoulder. */
    private fun archNewPattern(
        coord: Int, lowerCoord: Int, upperCoord: Int, stepDepth: Int, alongAxisIsZ: Boolean,
    ): BlockState? {
        val curve = ARCHWAY_CURVE_DEPTH
        val midCenter = (lowerCoord + upperCoord) / 2
        return when (stepDepth) {
            -1 -> when (coord) {
                // Y=24: `. . . . b L L L L L b . . . .`
                lowerCoord + curve + 2, upperCoord - curve - 2 -> DEEPSLATE_TILE_SLAB_BOTTOM
                in (lowerCoord + curve + 3)..(upperCoord - curve - 3) -> DEEPSLATE_TILES
                else -> null
            }
            0 -> when (coord) {
                // Y=23: `. S b L L L p p p L L L b S .`
                lowerCoord + curve - 1 -> bottomStair(toLow = true, alongAxisIsZ)
                upperCoord - curve + 1 -> bottomStair(toLow = false, alongAxisIsZ)
                lowerCoord + curve, upperCoord - curve -> DEEPSLATE_TILE_SLAB_BOTTOM
                in (midCenter - 1)..(midCenter + 1) -> DEEPSLATE_TILE_SLAB_TOP
                in (lowerCoord + curve + 1)..(upperCoord - curve - 1) -> DEEPSLATE_TILES
                else -> null
            }
            1 -> archOldPattern(coord, lowerCoord, upperCoord, 1, alongAxisIsZ)
            else -> null
        }
    }

    /** 2-row shoulder pattern for narrower spans (arm + cross-path arches). */
    private fun archOldPattern(
        coord: Int, lowerCoord: Int, upperCoord: Int, stepDepth: Int, alongAxisIsZ: Boolean,
    ): BlockState? {
        val curve = ARCHWAY_CURVE_DEPTH
        return when (stepDepth) {
            0 -> when (coord) {
                // Y=top: `. S b L … L b S .`
                lowerCoord + curve - 1 -> bottomStair(toLow = true, alongAxisIsZ)
                upperCoord - curve + 1 -> bottomStair(toLow = false, alongAxisIsZ)
                lowerCoord + curve, upperCoord - curve -> DEEPSLATE_TILE_SLAB_BOTTOM
                in (lowerCoord + curve + 1)..(upperCoord - curve - 1) -> DEEPSLATE_TILES
                else -> null
            }
            1 -> when (coord) {
                // Y=top-1: `S p p … p p S`
                lowerCoord + curve - 2 -> topStair(toLow = true, alongAxisIsZ)
                upperCoord - curve + 2 -> topStair(toLow = false, alongAxisIsZ)
                in (lowerCoord + curve - 1)..(lowerCoord + curve) -> DEEPSLATE_TILE_SLAB_TOP
                in (upperCoord - curve)..(upperCoord - curve + 1) -> DEEPSLATE_TILE_SLAB_TOP
                else -> null
            }
            else -> null
        }
    }

    private fun topStair(toLow: Boolean, alongAxisIsZ: Boolean): BlockState =
        when {
            alongAxisIsZ && toLow -> DEEPSLATE_TILE_STAIRS_TOP_NORTH
            alongAxisIsZ && !toLow -> DEEPSLATE_TILE_STAIRS_TOP_SOUTH
            !alongAxisIsZ && toLow -> DEEPSLATE_TILE_STAIRS_TOP_WEST
            else -> DEEPSLATE_TILE_STAIRS_TOP_EAST
        }

    private fun bottomStair(toLow: Boolean, alongAxisIsZ: Boolean): BlockState =
        when {
            alongAxisIsZ && toLow -> DEEPSLATE_TILE_STAIRS_BOTTOM_NORTH
            alongAxisIsZ && !toLow -> DEEPSLATE_TILE_STAIRS_BOTTOM_SOUTH
            !alongAxisIsZ && toLow -> DEEPSLATE_TILE_STAIRS_BOTTOM_WEST
            else -> DEEPSLATE_TILE_STAIRS_BOTTOM_EAST
        }

    /** Hanging chain + froglight fixture below the middle of some arches.
     *  An arch is identified by its mid (X, Z) — for X-axis arches the mid
     *  is `(arch_X, 0)`, for Z-axis arches `(0, arch_Z)`. A per-arch hash
     *  decides whether the fixture exists; if it does, blocks at
     *  `Y = topY - 1 .. topY - CHAIN_LENGTH` are CHAIN and
     *  `Y = topY - CHAIN_LENGTH - 1` is OCHRE_FROGLIGHT. */
    private fun archChainFroglightAt(
        worldX: Int, worldY: Int, worldZ: Int, cellY: Int,
    ): BlockState? {
        val topY = cellY * MAZE_CELL_Y + MAZE_CELL_Y - 1
        val yDelta = topY - worldY
        if (yDelta < 1 || yDelta > ARCHWAY_CHAIN_LENGTH + 1) return null
        val onXAxisArchMid = worldZ == 0 && (
            worldX == GAP_COLUMN_POS_X || worldX == GAP_COLUMN_NEG_X ||
                (worldX !in PATH_RANGE && worldX != 0 &&
                    positiveMod(worldX, GAP_COLUMN_INTERVAL) == 0 &&
                    Math.floorMod(worldX / GAP_COLUMN_INTERVAL + cellY, 2) == 0)
            )
        val onZAxisArchMid = worldX == 0 && (
            worldZ == GAP_COLUMN_POS_Z || worldZ == GAP_COLUMN_NEG_Z ||
                (worldZ !in PATH_RANGE && worldZ != 0 &&
                    positiveMod(worldZ, GAP_COLUMN_INTERVAL) == 0 &&
                    Math.floorMod(worldZ / GAP_COLUMN_INTERVAL + cellY, 2) == 0)
            )
        if (!onXAxisArchMid && !onZAxisArchMid) return null
        // NBT axial-arch replacement carries its own chain — the procedural
        // hanging chain would intersect / duplicate it at top-cellY columns.
        if (isInAxialArchColumn(worldX, worldZ)) return null
        if (positiveMod(hash(worldX, cellY, worldZ, 247), 100) >= ARCHWAY_CHAIN_PERCENT) return null
        return if (yDelta == ARCHWAY_CHAIN_LENGTH + 1) OCHRE_FROGLIGHT else CHAIN
    }

    /** Carpet (yellow/white) placed on the centerline of every straight
     *  corridor floor (POLISHED_DEEPSLATE), skipping platform positions.
     *  The 2-yellow / 2-white pattern alternates with `(worldX + worldZ)`
     *  so it reads correctly down either axial or maze corridor. */
    private fun carpetBlockAt(
        pathCache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        worldX: Int, worldY: Int, worldZ: Int,
    ): BlockState? {
        val ly = worldY - baseY
        if (ly < 1 || ly >= span) return null
        val lx = worldX - baseX
        val lz = worldZ - baseZ
        if (lx < 1 || lx >= cacheSize - 1) return null
        if (lz < 1 || lz >= cacheSize - 1) return null
        val below = pathCache[lx][lz][ly - 1] ?: return null
        if (!below.`is`(Blocks.POLISHED_DEEPSLATE)) return null
        // Platform outer rings are also POLISHED_DEEPSLATE; identify
        // them cheaply by looking for the 3×3 inner-ring DEEPSLATE_TILES
        // in the 8-neighbour box. Corridors never place TILES, so any
        // tiles adjacent at this Y means the cell sits on a platform.
        // This replaces a per-call `mazePlatformFloorAt` cell scan with
        // 8 cache array reads.
        var nx = -1
        while (nx <= 1) {
            var nz = -1
            while (nz <= 1) {
                if (!(nx == 0 && nz == 0)) {
                    val s = pathCache[lx + nx][lz + nz][ly - 1]
                    if (s != null && s.`is`(Blocks.DEEPSLATE_TILES)) return null
                }
                nz++
            }
            nx++
        }
        // Centerline check: perpendicular neighbour floors must be the same
        // on both sides (both present OR both absent), separately for each
        // axis. Sides of a W=3 corridor fail this and don't get carpet.
        val xMinus = isCorridorFloor(pathCache[lx - 1][lz][ly - 1])
        val xPlus = isCorridorFloor(pathCache[lx + 1][lz][ly - 1])
        val zMinus = isCorridorFloor(pathCache[lx][lz - 1][ly - 1])
        val zPlus = isCorridorFloor(pathCache[lx][lz + 1][ly - 1])
        if (xMinus != xPlus) return null
        if (zMinus != zPlus) return null
        return if (Math.floorMod(worldX + worldZ, 4) < 2) YELLOW_CARPET else WHITE_CARPET
    }

    private fun isCorridorFloor(block: BlockState?): Boolean =
        block != null && (block.`is`(Blocks.POLISHED_DEEPSLATE) ||
            block.`is`(Blocks.POLISHED_DEEPSLATE_STAIRS))

    /** Per-platform wall lectern — O(1) hash lookup into the chunk's
     *  precomputed [ChunkCaches.lecterns] map. Every platform at every
     *  reachable `cellY` gets its own lectern at one of the four wall
     *  corners (alongIdx 0 or 4 — never on a corridor exit at 2) on a
     *  hash-picked side, facing into the platform interior. The map is
     *  built once per chunk by [buildLecternMap] before [buildDecorCache]
     *  runs, replacing the previous per-call cell-window scan. */
    private fun lecternWallAt(wx: Int, wy: Int, wz: Int): BlockState? {
        val map = activeCaches.get()?.lecterns ?: return null
        return map.get(packLecternKey(wx, wy, wz))
    }

    /** Precompute every wall lectern within the chunk's decor window plus
     *  a 1-block border (so neighbour wall-connectivity queries at the
     *  chunk edges hit lecterns that physically live in the next chunk over).
     *  Iterates the cells whose platforms could project a lectern into
     *  that range, using the same hash + side/along logic the OLD
     *  per-call scan used. */
    private fun buildLecternMap(
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ): Long2ObjectOpenHashMap<BlockState> {
        // Cover chunk + 1-block decor border + 1-block neighbour reach.
        val wxMin = chunkX0 - 3
        val wxMax = chunkX0 + 18
        val wzMin = chunkZ0 - 3
        val wzMax = chunkZ0 + 18
        val baseY = minY
        val maxY = minY + span - 1
        // Same cell-window math as the OLD lecternWallAt, applied at
        // both window extremes so we sweep every cell whose lectern
        // could land anywhere in [wxMin..wxMax, wzMin..wzMax].
        val cellMinX = Math.floorDiv(wxMin - GRID_OFFSET - FLOOR_SIZE - LIBRARY_QUADRANT_SHIFT, MAZE_CELL_X)
        val cellMaxX = Math.floorDiv(wxMax - GRID_OFFSET + LIBRARY_QUADRANT_SHIFT + 2, MAZE_CELL_X)
        val cellMinZ = Math.floorDiv(wzMin - GRID_OFFSET - FLOOR_SIZE - LIBRARY_QUADRANT_SHIFT, MAZE_CELL_Z)
        val cellMaxZ = Math.floorDiv(wzMax - GRID_OFFSET + LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT + 2, MAZE_CELL_Z)
        val cellMinY = Math.floorDiv(baseY, MAZE_CELL_Y)
        val cellMaxY = Math.floorDiv(maxY, MAZE_CELL_Y)
        val map = Long2ObjectOpenHashMap<BlockState>()
        for (cx in cellMinX..cellMaxX) {
            for (cz in cellMinZ..cellMaxZ) {
                for (cy in cellMinY..cellMaxY) {
                    if (cy >= TOP_CELL_Y) continue
                    val node = mazeNodeAt(cx, cy, cz) ?: continue
                    val sideIdx = positiveMod(mazeHash(cx, cy, cz, 313), 4)
                    val alongIdx = positiveMod(mazeHash(cx, cy, cz, 317), 2) * (FLOOR_SIZE - 1)
                    val targetX: Int; val targetZ: Int; val facing: Direction
                    when (sideIdx) {
                        0 -> { targetX = node.nx + FLOOR_SIZE; targetZ = node.nz + alongIdx; facing = Direction.WEST }
                        1 -> { targetX = node.nx - 1;          targetZ = node.nz + alongIdx; facing = Direction.EAST }
                        2 -> { targetX = node.nx + alongIdx;   targetZ = node.nz + FLOOR_SIZE; facing = Direction.NORTH }
                        else -> { targetX = node.nx + alongIdx; targetZ = node.nz - 1;        facing = Direction.SOUTH }
                    }
                    val ty = node.ny + 1
                    if (targetX in wxMin..wxMax && targetZ in wzMin..wzMax && ty in baseY..maxY) {
                        map.put(
                            packLecternKey(targetX, ty, targetZ),
                            cachedLectern(facing),
                        )
                    }
                }
            }
        }
        return map
    }

    /** Pack (wx, wz, wy) into a single Long for [ChunkCaches.lecterns]
     *  lookup. 21 signed bits each fit Sselith comfortably (~±1M XZ,
     *  ±1M Y); collision-free for our coordinate range. */
    private fun packLecternKey(wx: Int, wy: Int, wz: Int): Long {
        return (wx.toLong() and 0x1FFFFFL) or
            ((wz.toLong() and 0x1FFFFFL) shl 21) or
            ((wy.toLong() and 0x1FFFFFL) shl 42)
    }

    /** Attach a sign-text block-entity NBT to a freshly-placed bamboo wall
     *  sign with its first line set to the floor name (top floor 1 =
     *  "Spines", middle floor 5 = "Atrium", bottom floor 9 = "Frontispiece").
     *
     *  Goes through [ChunkAccess.setBlockEntityNbt] (a pending-NBT map that
     *  the chunk converts to a real BE on load) rather than constructing a
     *  [SignBlockEntity] and calling `setText`. The latter eventually calls
     *  `level.sendBlockUpdated(...)`, but at chunk-gen time the BE's `level`
     *  field is null, so it NPEs. */
    private fun attachFloorSign(chunk: ChunkAccess, pos: BlockPos, state: BlockState, worldY: Int) {
        val cellY = Math.floorDiv(worldY - 1, MAZE_CELL_Y)
            .coerceIn(MIN_NAMED_CELL_Y, MAX_NAMED_CELL_Y)
        val floorName = FLOOR_NAMES[MAX_NAMED_CELL_Y - cellY]
        // Build the BE NBT via a throwaway SignBlockEntity, then patch the
        // first two front-text messages in place.
        val template = SignBlockEntity(pos.immutable(), state).saveWithFullMetadata()
        val frontText = template.getCompound("front_text")
        val messages = frontText.getList("messages", Tag.TAG_STRING.toInt())
        messages[0] = StringTag.valueOf(
            Component.Serializer.toJson(Component.literal(floorName)))
        if (SSELITH_WORDS.isNotEmpty()) {
            val wordIdx = positiveMod(hash(pos.x, pos.y, pos.z, 421), SSELITH_WORDS.size)
            val word = SSELITH_WORDS[wordIdx]
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            messages[1] = StringTag.valueOf(
                Component.Serializer.toJson(Component.literal(word)))
        }
        chunk.setBlockEntityNbt(template)
    }

    /** Bamboo wall sign hung on the interior face of a chiseled deepslate
     *  post. Sign sits at the TOP of the 3-tall post — that's the Y where
     *  the post column is filled by the `isChiseledColumn ∧ isWallOutline(_,
     *  wy-1, _)` branch of [decorBlockAt]. The first cardinal neighbour
     *  whose post top exists here wins; per-post hash rolls the 45% chance.
     *  Sign FACING points away from the post toward the viewer. */
    private fun chiseledPostSignAt(wx: Int, wy: Int, wz: Int): BlockState? {
        for (i in 0 until 4) {
            val dx = CARDINAL_DX[i]
            val dz = CARDINAL_DZ[i]
            val px = wx + dx
            val pz = wz + dz
            if (!isChiseledColumn(px, pz)) continue
            // Post top exists at wy iff the post's wall-outline middle exists
            // at wy-1 (decorBlockAt's "one block above a wall outline" rule
            // returns CHISELED_DEEPSLATE there for chiseled columns).
            if (!isWallOutline(px, wy - 1, pz)) continue
            if (positiveMod(mazeHash(px, wy, pz, 271), 100) >= CHISELED_POST_SIGN_PERCENT) continue
            return cachedBambooWallSign(CARDINAL_SIGN_FACING[i])
        }
        return null
    }

    private fun ladderHere(
        cellX: Int, cellY: Int, cellZ: Int, wallId: Int, slotIdx: Int,
        alongPos: Int, alongRange: Int,
    ): Boolean {
        if (alongPos < 0 || alongPos >= alongRange) return false
        val seedA = cellX * 4 + wallId
        val seedB = cellY * 8 + slotIdx
        if (positiveMod(hash(seedA, seedB, cellZ, 877), 100) >= LADDER_EXISTS_PERCENT) return false
        return positiveMod(hash(seedA, seedB, cellZ, 991), alongRange) == alongPos
    }


    /** Frame every pathway / stairwell opening through a cell wall with a
     *  5-tall doorway: deepslate-tile jambs at the perpendicular ±1 columns
     *  on each Y from `floorY` to `floorY+4`, and a polished-deepslate slab
     *  capping the passage at `floorY+4`. Floor center stays as-is (the
     *  corridor floor block remains). */
    private fun buildWallFrameCache(
        pathCache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
    ): Array<Array<Array<BlockState?>>> {
        val frame = Array(cacheSize) { Array(cacheSize) { arrayOfNulls<BlockState>(span) } }
        val maxX = baseX + cacheSize - 1
        val maxZ = baseZ + cacheSize - 1
        val maxY = baseY + span - 1
        // Same widening as paintChunkInto — a connection can extend up to
        // one full cell in any direction from its source.
        val minCellX = Math.floorDiv(baseX - GRID_OFFSET, MAZE_CELL_X) - 1
        val maxCellX = Math.floorDiv(maxX - GRID_OFFSET, MAZE_CELL_X) + 1
        val minCellY = Math.floorDiv(baseY, MAZE_CELL_Y) - 1
        val maxCellY = Math.floorDiv(maxY, MAZE_CELL_Y) + 1
        val minCellZ = Math.floorDiv(baseZ - GRID_OFFSET, MAZE_CELL_Z) - 1
        val maxCellZ = Math.floorDiv(maxZ - GRID_OFFSET, MAZE_CELL_Z) + 1
        for (cellX in minCellX..maxCellX) {
            for (cellY in minCellY..maxCellY) {
                for (cellZ in minCellZ..maxCellZ) {
                    val a = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                    for (vIdx in pickConnections(cellX, cellY, cellZ)) {
                        paintWallFramesForPick(frame, pathCache, baseX, baseZ, baseY, cacheSize, span,
                            a, cellX, cellY, cellZ, vIdx)
                    }
                }
            }
        }
        return frame
    }

    private fun paintWallFramesForPick(
        frame: Array<Array<Array<BlockState?>>>,
        pathCache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        a: MazeNode, cellX: Int, cellY: Int, cellZ: Int, vIdx: Int,
    ) {
        val v = CONN_VECTORS[vIdx]
        // Pure vertical ladders cross no horizontal wall — no frame needed.
        if (v.dx == 0 && v.dz == 0) return
        if (isStairwellVec(vIdx) && !stairwellAllowed(cellX, cellY, cellZ, vIdx)) return
        val b = mazeNodeAt(cellX + v.dx, cellY + v.dy, cellZ + v.dz) ?: return
        if (Math.abs(a.ny - b.ny) > MAX_CONNECT_Y_DIFF) return
        // Mirror [paintConnectionPick]'s vertical-into-structure skip:
        // when the source or destination cell hosts a structure
        // replacement, the corridor pass refuses to carve the
        // stair flight (the NBT owns the column). Without this matching
        // skip the wall-frame pass still paints a 5-tall doorway through
        // the cell wall, leaving a stairwell-opening with no actual
        // stairwell behind it.
        if (v.dy != 0 &&
            (isStructureReplacementNode(cellX, cellY, cellZ) ||
             isStructureReplacementNode(cellX + v.dx, cellY + v.dy, cellZ + v.dz))
        ) return
        val width = corridorWidth(a, b)
        val wallOffsetFar = MAZE_CELL_X - 1 - GRID_OFFSET   // east/south wall offset from platform origin
        val wallOffsetNear = -GRID_OFFSET                    // west/north wall offset from platform origin
        if (v.dx != 0) {
            val east = v.dx > 0
            val aWallX = a.nx + if (east) wallOffsetFar else wallOffsetNear
            val bWallX = b.nx + if (east) wallOffsetNear else wallOffsetFar
            val corridorZCenter = a.nz + 2
            val zLo = corridorZCenter - (width - 1) / 2
            val zHi = corridorZCenter + width / 2
            paintXWallFrame(frame, baseX, baseZ, baseY, cacheSize, span,
                a, b, v, aWallX, -v.dx, zLo, zHi)
            paintXWallFrame(frame, baseX, baseZ, baseY, cacheSize, span,
                a, b, v, bWallX, v.dx, zLo, zHi)
        } else {
            val south = v.dz > 0
            val aWallZ = a.nz + if (south) wallOffsetFar else wallOffsetNear
            val bWallZ = b.nz + if (south) wallOffsetNear else wallOffsetFar
            val corridorXCenter = a.nx + 2
            val xLo = corridorXCenter - (width - 1) / 2
            val xHi = corridorXCenter + width / 2
            paintZWallFrame(frame, baseX, baseZ, baseY, cacheSize, span,
                a, b, v, aWallZ, -v.dz, xLo, xHi)
            paintZWallFrame(frame, baseX, baseZ, baseY, cacheSize, span,
                a, b, v, bWallZ, v.dz, xLo, xHi)
        }
    }

    /** Analytical corridor floor Y for the connection from `a` to `b` along
     *  vector `v`, at the point where the corridor's along-axis coord is
     *  `w` (worldX for X-axis vectors, worldZ for Z-axis vectors).
     *
     *  Mirrors the geometry of [connectionBlockX] / [connectionBlockZ]:
     *  flat at `a.ny` when `yDiff == 0`, stair Y at each `stairIdx` when in
     *  the stair flight, flat at `b.ny` after the stair flight, and flat at
     *  `a.ny` for ladder connections. Returns null if `w` is outside the
     *  corridor's extent. */
    private fun corridorFloorYAt(a: MazeNode, b: MazeNode, v: ConnVec, w: Int): Int? {
        val yDiff = b.ny - a.ny
        val absDiff = Math.abs(yDiff)
        if (absDiff > MAX_CONNECT_Y_DIFF) return null
        val aExit: Int; val ladderPos: Int; val d: Int
        if (v.dx != 0) {
            aExit = if (v.dx > 0) a.nx + FLOOR_SIZE else a.nx - 1
            ladderPos = b.nx + 2
            d = if (v.dx > 0) 1 else -1
        } else {
            aExit = if (v.dz > 0) a.nz + FLOOR_SIZE else a.nz - 1
            ladderPos = b.nz + 2
            d = if (v.dz > 0) 1 else -1
        }
        val corridorLen = ladderPos - aExit
        val absCorridorLen = Math.abs(corridorLen)
        val stepIdx = (w - aExit) * d
        if (stepIdx < 0) return null
        if (yDiff == 0) {
            if (stepIdx > absCorridorLen) return null
            return a.ny
        }
        val isStair = absDiff <= STAIR_THRESHOLD && absCorridorLen >= absDiff + 2
        if (isStair) {
            val stairCount = absDiff
            // Stair flight is centred in the corridor — must match the
            // geometry in connectionBlockX / connectionBlockZ exactly,
            // otherwise the wall opening Y won't line up with the actual
            // stair tread Y at the wall position.
            val preStairFlat = (absCorridorLen - stairCount) / 2
            if (stepIdx < preStairFlat) return a.ny
            val sIdx = stepIdx - preStairFlat
            if (sIdx < stairCount) {
                val descending = yDiff < 0
                return if (descending) a.ny - sIdx else a.ny + 1 + sIdx
            }
            if (stepIdx <= absCorridorLen) return b.ny
            return null
        }
        // Ladder: flat at a.ny from aExit to xLegFar = ladderPos - d.
        if (stepIdx > absCorridorLen - 1) return null
        return a.ny
    }

    /** Scan the path cache at `(wx, wz)` across `[yLo, yHi]` for a corridor
     *  floor — POLISHED_DEEPSLATE or POLISHED_DEEPSLATE_STAIRS. Returns the
     *  world Y of the first one found, or null. */
    private fun findCorridorFloorY(
        pathCache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        wx: Int, wz: Int, yLo: Int, yHi: Int,
    ): Int? {
        val lx = wx - baseX
        if (lx !in 0 until cacheSize) return null
        val lz = wz - baseZ
        if (lz !in 0 until cacheSize) return null
        val clipYLo = Math.max(yLo, baseY)
        val clipYHi = Math.min(yHi, baseY + span - 1)
        if (clipYLo > clipYHi) return null
        val col = pathCache[lx][lz]
        for (wy in clipYLo..clipYHi) {
            val block = col[wy - baseY] ?: continue
            if (block.`is`(Blocks.POLISHED_DEEPSLATE) || block.`is`(Blocks.POLISHED_DEEPSLATE_STAIRS)) {
                return wy
            }
        }
        return null
    }

    /** Paint the wall opening frame across two columns: the wall itself
     *  (`depth=0`) and the inner archway shelf column one block inward
     *  (`depth=1`). For stair connections, the corridor floor Y differs by
     *  one between these depths, so we compute `floorY` per depth — using
     *  one Y for both would leave the opening offset and either wipe the
     *  stair tread or cut shelves at the wrong row. */
    private fun paintXWallFrame(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        a: MazeNode, b: MazeNode, v: ConnVec,
        wallX: Int, interiorDx: Int,
        corridorZLo: Int, corridorZHi: Int,
    ) {
        val jambZLo = corridorZLo - 1
        val jambZHi = corridorZHi + 1
        for (depth in 0..1) {
            val wx = wallX + depth * interiorDx
            val floorY = corridorFloorYAt(a, b, v, wx) ?: continue
            val topY = floorY + WALL_OPENING_HEIGHT - 1
            for (wy in floorY..topY) {
                val isTop = wy == topY
                val isFloor = wy == floorY
                for (wz in jambZLo..jambZHi) {
                    val isJamb = wz == jambZLo || wz == jambZHi
                    if (isFloor && !isJamb) continue   // corridor floor stays
                    val block: BlockState = when {
                        isTop && !isJamb -> POLISHED_DEEPSLATE_SLAB_TOP
                        isJamb -> DEEPSLATE_TILES
                        else -> AIR
                    }
                    paintFrameInto(cache, baseX, baseZ, baseY, cacheSize, span,
                        wx, wy, wz, block)
                }
            }
        }
    }

    private fun paintZWallFrame(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        a: MazeNode, b: MazeNode, v: ConnVec,
        wallZ: Int, interiorDz: Int,
        corridorXLo: Int, corridorXHi: Int,
    ) {
        val jambXLo = corridorXLo - 1
        val jambXHi = corridorXHi + 1
        for (depth in 0..1) {
            val wz = wallZ + depth * interiorDz
            val floorY = corridorFloorYAt(a, b, v, wz) ?: continue
            val topY = floorY + WALL_OPENING_HEIGHT - 1
            for (wy in floorY..topY) {
                val isTop = wy == topY
                val isFloor = wy == floorY
                for (wx in jambXLo..jambXHi) {
                    val isJamb = wx == jambXLo || wx == jambXHi
                    if (isFloor && !isJamb) continue
                    val block: BlockState = when {
                        isTop && !isJamb -> POLISHED_DEEPSLATE_SLAB_TOP
                        isJamb -> DEEPSLATE_TILES
                        else -> AIR
                    }
                    paintFrameInto(cache, baseX, baseZ, baseY, cacheSize, span,
                        wx, wy, wz, block)
                }
            }
        }
    }

    private fun paintFrameInto(
        cache: Array<Array<Array<BlockState?>>>,
        baseX: Int, baseZ: Int, baseY: Int, cacheSize: Int, span: Int,
        wx: Int, wy: Int, wz: Int, block: BlockState,
    ) {
        val lx = wx - baseX
        if (lx !in 0 until cacheSize) return
        val lz = wz - baseZ
        if (lz !in 0 until cacheSize) return
        val ly = wy - baseY
        if (ly !in 0 until span) return
        cache[lx][lz][ly] = block
    }

    // Hot-path predicates: each was an array dereference + 3 getter
    // calls + boolean ops, called millions of times during chunk gen
    // (especially from `stairwellAllowed`'s 125-cell scan after the
    // ±2 widening). Both answers are a pure function of vIdx, so
    // precompute a BooleanArray lookup once at class init — the
    // method body becomes a single array read.
    private fun isStairwellVec(vIdx: Int): Boolean = IS_STAIRWELL_VEC[vIdx]
    private fun isPathwayVec(vIdx: Int): Boolean = IS_PATHWAY_VEC[vIdx]

    /** Tight bbox of the corridor blocks a connection would place
     *  (excludes the platform footprints). Returns a shared **read-only**
     *  6-int array `[xLo, xHi, yLo, yHi, zLo, zHi]`. Result is memoised
     *  per `(cell, vIdx)` in [ChunkCaches.connectionBBoxCache] — this
     *  function is hot inside `computeStairwellAllowed`'s 125-cell
     *  scan and also called from several paint sites, so a shared
     *  cached array per `(cell, vIdx)` eliminates the bulk of the
     *  re-computation. */
    private fun connectionBBox(cellX: Int, cellY: Int, cellZ: Int, vIdx: Int): IntArray =
        connectionBBoxCtx(activeCaches.get(), cellX, cellY, cellZ, vIdx)

    /** Pre-fetched-context variant of [connectionBBox]. Callers in
     *  hot loops should reuse a single ctx for repeated calls. */
    private fun connectionBBoxCtx(
        ctx: ChunkCaches?, cellX: Int, cellY: Int, cellZ: Int, vIdx: Int,
    ): IntArray {
        val memo = ctx?.connectionBBoxCache
        if (memo != null) {
            val key = packStairwellKey(cellX, cellY, cellZ, vIdx)
            val hit = memo.get(key)
            if (hit != null) return hit
            val fresh = computeConnectionBBox(ctx, cellX, cellY, cellZ, vIdx)
            memo.put(key, fresh)
            return fresh
        }
        return computeConnectionBBox(null, cellX, cellY, cellZ, vIdx)
    }

    /** Uncached body of [connectionBBox] — allocates a fresh 6-int
     *  array and fills it. */
    private fun computeConnectionBBox(
        ctx: ChunkCaches?, cellX: Int, cellY: Int, cellZ: Int, vIdx: Int,
    ): IntArray {
        val v = CONN_VECTORS[vIdx]
        val aNx = platformOriginXCtx(ctx, cellX)
        val aNy = cellY * MAZE_CELL_Y
        val aNz = platformOriginZCtx(ctx, cellZ)
        val bNx = platformOriginXCtx(ctx, cellX + v.dx)
        val bNy = (cellY + v.dy) * MAZE_CELL_Y
        val bNz = platformOriginZCtx(ctx, cellZ + v.dz)
        val yLo = Math.min(aNy, bNy)
        val yHi = Math.max(aNy, bNy)
        val out = IntArray(6)
        when {
            v.dx == 0 && v.dz == 0 -> {
                // Vertical ladder column.
                out[0] = aNx + 1; out[1] = aNx + 3
                out[2] = yLo;     out[3] = yHi
                out[4] = aNz + 1; out[5] = aNz + 3
            }
            v.dx != 0 -> {
                val aExitX = if (v.dx > 0) aNx + FLOOR_SIZE else aNx - 1
                val ladderX = bNx + 2
                out[0] = Math.min(aExitX, ladderX); out[1] = Math.max(aExitX, ladderX)
                out[2] = yLo;                       out[3] = yHi
                out[4] = aNz + 1;                   out[5] = aNz + 3
            }
            else -> {
                val aExitZ = if (v.dz > 0) aNz + FLOOR_SIZE else aNz - 1
                val ladderZ = bNz + 2
                out[0] = aNx + 1;                   out[1] = aNx + 3
                out[2] = yLo;                       out[3] = yHi
                out[4] = Math.min(aExitZ, ladderZ); out[5] = Math.max(aExitZ, ladderZ)
            }
        }
        return out
    }

    private fun bboxOverlaps(a: IntArray, b: IntArray): Boolean =
        a[0] <= b[1] && a[1] >= b[0] &&
            a[2] <= b[3] && a[3] >= b[2] &&
            a[4] <= b[5] && a[5] >= b[4]

    /** Detects three stairwell configurations whose stair flights
     *  actually share blocks or visibly cross in the same corridor
     *  volume:
     *
     *  - **Anti-parallel adjacent same-Y-direction**: two stairs at
     *    adjacent source cells along the same axis going opposite
     *    axial directions but the same Y direction. E.g. `(10, 5, 10)
     *    east-up` + `(11, 5, 10) west-up` — they pass through the
     *    same inter-cell volume midpoint in opposite X directions.
     *  - **Stacked-Y opposite-direction same-axial-direction**: two
     *    stairs at the same source cellX/cellZ with consecutive
     *    cellYs going the same axial direction with opposite Y
     *    directions. E.g. `(10, 5, 10) east-up` + `(10, 6, 10)
     *    east-down`.
     *  - **Converging from same source line onto same target**:
     *    two stairs at the same source cellX/cellZ whose cellYs
     *    differ by 2, going the same axial direction with opposite Y
     *    directions, landing on the same target cell. E.g.
     *    `(10, 4, 10) east-up` (to (11, 5, 10)) + `(10, 6, 10)
     *    east-down` (also to (11, 5, 10)). Both share the corridor
     *    X range and visibly cross at the corridor midpoint even
     *    though their actual stair treads sit at different Y values
     *    per X column — the user sees one "/" stair and one "\"
     *    stair overlapping in the same inter-cell space.
     *
     *  Cross-axis convergence (e.g. `(9, 5, 10) east-up` and `(10, 5,
     *  9) south-up` both terminating at `(10, 6, 10)`) is still NOT
     *  detected — those flights meet at the destination platform
     *  rather than interpenetrating, and rejecting them halves the
     *  per-cell density without removing a visible criss-cross. */
    private fun stairwellsCrossInSharedVolume(
        v1: ConnVec, c1X: Int, c1Y: Int, c1Z: Int,
        v2: ConnVec, c2X: Int, c2Y: Int, c2Z: Int,
    ): Boolean {
        // Common destination: two stairs whose targets are the same
        // cell going opposite Y directions land on the same platform
        // from converging approach corridors. Catches every common-
        // destination configuration including the cross-axis case
        // (`east-up` + `south-down` both landing at the same
        // platform). Distance candidates: same-axis cellY±2 (in
        // scan), cross-axis (±1, ±2, ±1) (in scan).
        if (c1X + v1.dx == c2X + v2.dx &&
            c1Y + v1.dy == c2Y + v2.dy &&
            c1Z + v1.dz == c2Z + v2.dz &&
            v1.dy * v2.dy < 0
        ) return true
        val v1OnX = v1.dx != 0 && v1.dz == 0
        val v2OnX = v2.dx != 0 && v2.dz == 0
        val v1OnZ = v1.dz != 0 && v1.dx == 0
        val v2OnZ = v2.dz != 0 && v2.dx == 0
        if (v1OnX && v2OnX) {
            if (c1Z != c2Z) return false
            // Anti-parallel adjacent same Y direction.
            if (v1.dx * v2.dx < 0 && v1.dy * v2.dy > 0 &&
                Math.abs(c1X - c2X) == 1 && c1Y == c2Y
            ) return true
            // Parallel same axial direction, consecutive source cellY,
            // opposite Y direction (stacked-Y).
            if (v1.dx * v2.dx > 0 && v1.dy * v2.dy < 0 &&
                c1X == c2X && Math.abs(c1Y - c2Y) == 1
            ) return true
            // Converging at same target: same source cellX, cellYs
            // differ by 2, same axial direction, opposite Y direction.
            if (v1.dx * v2.dx > 0 && v1.dy * v2.dy < 0 &&
                c1X == c2X && Math.abs(c1Y - c2Y) == 2
            ) return true
            return false
        }
        if (v1OnZ && v2OnZ) {
            if (c1X != c2X) return false
            if (v1.dz * v2.dz < 0 && v1.dy * v2.dy > 0 &&
                Math.abs(c1Z - c2Z) == 1 && c1Y == c2Y
            ) return true
            if (v1.dz * v2.dz > 0 && v1.dy * v2.dy < 0 &&
                c1Z == c2Z && Math.abs(c1Y - c2Y) == 1
            ) return true
            if (v1.dz * v2.dz > 0 && v1.dy * v2.dy < 0 &&
                c1Z == c2Z && Math.abs(c1Y - c2Y) == 2
            ) return true
            return false
        }
        return false
    }

    /** Two connections describe the same physical corridor if A picks B AND
     *  B picks A back via reciprocal vectors — both ends drawing identical
     *  blocks, never a real conflict. */
    private fun isReciprocalPair(
        c1X: Int, c1Y: Int, c1Z: Int, v1Idx: Int,
        c2X: Int, c2Y: Int, c2Z: Int, v2Idx: Int,
    ): Boolean {
        val v1 = CONN_VECTORS[v1Idx]
        val v2 = CONN_VECTORS[v2Idx]
        return c1X + v1.dx == c2X && c1Y + v1.dy == c2Y && c1Z + v1.dz == c2Z &&
            c2X + v2.dx == c1X && c2Y + v2.dy == c1Y && c2Z + v2.dz == c1Z
    }

    /** Lexicographic priority over (cellX, cellY, cellZ, vIdx). Lower wins
     *  conflict resolution. Cell coords biased by 2^15 so negative cells
     *  still sort consistently. */
    private fun connectionRank(cellX: Int, cellY: Int, cellZ: Int, vIdx: Int): Long {
        val bias = 1L shl 15
        val cx = (cellX + bias) and 0xFFFFL
        val cy = (cellY + bias) and 0xFFFFL
        val cz = (cellZ + bias) and 0xFFFFL
        return (cx shl 48) or (cy shl 32) or (cz shl 16) or vIdx.toLong()
    }

    /** Should this stairwell paint? Returns false iff its bbox overlaps any
     *  pathway (which always wins) or any other stairwell with lower rank.
     *  Search range is ±2 cells in each axis — a stairwell's bbox can
     *  reach one full cell stride outside its source, so two stairwells
     *  whose source cells are 2 cells apart can still terminate at the
     *  same intermediate cell and visibly intersect. A ±1 search would
     *  miss that case and let both paint, producing the visible
     *  stair-on-stair crossings users were reporting.
     *
     *  The stairwell-vs-stairwell check uses a STRICT Y overlap (Y ranges
     *  must share more than a single boundary block) so that adjacent
     *  vertical layers — e.g. cellY-1's east-up (Y=-24..0) and cellA's
     *  east-up (Y=0..24) — don't chain-cull each other. Their actual block
     *  ranges don't conflict at the shared boundary Y, only the inclusive
     *  bbox check did. The pathway-vs-stairwell check stays inclusive
     *  because a stair undertread really does collide with a pathway floor
     *  at the shared boundary Y. */
    private fun stairwellAllowed(cellX: Int, cellY: Int, cellZ: Int, vIdx: Int): Boolean {
        // Two-tier memo. Tier 1 (per-`ChunkCaches`) is a primitive-
        // specialized Long2ByteOpenHashMap — fastest path, no CAS.
        // Tier 2 ([GLOBAL_STAIRWELL_CACHE]) is a `ConcurrentHashMap`
        // shared across all chunk-gen workers so neighbour chunks
        // skip the 5×5×5 = 125-cell scan in [computeStairwellAllowed]
        // entirely. Spark report had `replacementTypeAt → hasUpward
        // Connection → stairwellAllowed → computeStairwellAllowed` at
        // ~33s cumulative across the worker pool; the global cache
        // converts that to one compute per (cell, vIdx) for the
        // lifetime of the process.
        val key = packStairwellKey(cellX, cellY, cellZ, vIdx)
        val ctx = activeCaches.get()
        val memo = ctx?.stairwellAllowedCache
        if (memo != null) {
            val cached = memo.get(key).toInt()
            if (cached == 1) return true
            if (cached == 2) return false
        }
        // Tier 2: shared cache. Boxed key + boxed Boolean, but the
        // map's two singleton Booleans aren't boxed per put — only
        // the Long key allocates (~24 B). Net win vs recomputing the
        // 125-cell scan dwarfs the allocation by several orders of
        // magnitude.
        val global = GLOBAL_STAIRWELL_CACHE[key]
        if (global != null) {
            memo?.put(key, (if (global) 1 else 2).toByte())
            return global
        }
        val result = computeStairwellAllowed(ctx, cellX, cellY, cellZ, vIdx)
        memo?.put(key, (if (result) 1 else 2).toByte())
        // Cap-and-stop eviction policy. Once the cache reaches its
        // cap, new entries aren't added but existing ones stay valid
        // and keep serving hits. Cheaper than maintaining LRU order
        // under contention; the working set for a single player's
        // load-window fits comfortably under the cap.
        if (GLOBAL_STAIRWELL_CACHE.size < GLOBAL_STAIRWELL_CACHE_CAP) {
            GLOBAL_STAIRWELL_CACHE.putIfAbsent(key, result)
        }
        return result
    }

    /** Pack (cellX, cellY, cellZ, vIdx) into a Long stairwell-cache key.
     *  21 bits each for X/Z, 17 bits for Y, 4 bits for vIdx — covers
     *  Sselith comfortably with margin. */
    private fun packStairwellKey(cellX: Int, cellY: Int, cellZ: Int, vIdx: Int): Long =
        ((cellX.toLong() and 0x1FFFFFL) shl 42) or
            ((cellZ.toLong() and 0x1FFFFFL) shl 21) or
            ((cellY.toLong() and 0x1FFFFL) shl 4) or
            (vIdx.toLong() and 0xFL)

    /** Uncached body of [stairwellAllowed]. Takes a pre-fetched
     *  [ChunkCaches]? to avoid a second ThreadLocal hop. */
    private fun computeStairwellAllowed(
        ctx: ChunkCaches?, cellX: Int, cellY: Int, cellZ: Int, vIdx: Int,
    ): Boolean {
        // Both bbox calls go through the memo via *Ctx variants — no
        // local IntArray allocations needed; the cached arrays are
        // read-only and shared across calls for the same (cell, vIdx).
        val thisBox = connectionBBoxCtx(ctx, cellX, cellY, cellZ, vIdx)
        // Scan range derivation: each axis is sized to the maximum
        // cell-coord distance at which a real conflict can fire.
        //   X / Z: ±1. Cells at distance 2 in X (or Z) have no bbox
        //   overlap with this stair (verified analytically — the
        //   other cell's stair bbox starts at its `east_edge` and
        //   ends at its `middle`, both outside our [aExit, ladderX]
        //   span when the source cellX differs by 2). The remaining
        //   distance-2 case the broad common-destination check
        //   below was catching is cross-axis convergence at the
        //   shared target's middle, and those two flights actually
        //   sit in disjoint X / Z ranges meeting only at the
        //   destination-middle POLISHED_DEEPSLATE (same block on
        //   both sides — no visible crossing).
        //   Y: ±2. Source-line case (`stairwellsCrossInSharedVolume`
        //   's third clause) genuinely needs cellY-distance 2.
        // 5×5×5 = 125 → 3×5×3 = 45 inner iterations — 2.8× less
        // work per uncached stairwellAllowed call.
        for (oCellX in cellX - 1..cellX + 1) {
            for (oCellY in cellY - 2..cellY + 2) {
                for (oCellZ in cellZ - 1..cellZ + 1) {
                    val isSameCell = oCellX == cellX && oCellY == cellY && oCellZ == cellZ
                    // Skip same-cell self-conflicts entirely. With
                    // STAIRWELL_PICKS_PER_CELL > 1 the cell's own
                    // pick list contains multiple stairwell vIdxes;
                    // their bboxes share the source cell so the bbox
                    // check trips, and the lex rank then culls every
                    // pick except the smallest-vIdx one — eating the
                    // increased per-cell density the multi-pick fix
                    // was supposed to add. The picks point at
                    // different neighbour cells, so they never share
                    // actual stair material; skipping the self-check
                    // restores the full per-cell density without
                    // letting any real conflict slip through.
                    if (isSameCell) continue
                    for (oPick in pickConnectionsCtx(ctx, oCellX, oCellY, oCellZ)) {
                        val isPathway = IS_PATHWAY_VEC[oPick]
                        val isStairwell = IS_STAIRWELL_VEC[oPick]
                        if (!isPathway && !isStairwell) continue
                        val otherBox = connectionBBoxCtx(ctx, oCellX, oCellY, oCellZ, oPick)
                        val v = CONN_VECTORS[vIdx]
                        val oV = CONN_VECTORS[oPick]
                        // Same-axis pathway sharing source or
                        // destination with the stair. The stair's
                        // top tread sits at `b.ny` and its bottom
                        // undertread sits at `a.ny`, both inside the
                        // same X (or Z) corridor range a same-axis
                        // pathway lays its flat floor across. The
                        // tread / undertread peek through the
                        // pathway's `POLISHED_DEEPSLATE` floor as a
                        // visible texture protrusion. Strict-Y
                        // bbox-overlap misses this because the
                        // pathway's floor Y exactly equals the stair's
                        // a.ny / b.ny boundary (strict-Y excludes
                        // boundary-only matches by design).
                        if (isPathway) {
                            val sameAxis = (v.dx != 0 && oV.dx != 0) ||
                                (v.dz != 0 && oV.dz != 0)
                            if (sameAxis) {
                                val sX = cellX
                                val sY = cellY
                                val sZ = cellZ
                                val dX = cellX + v.dx
                                val dY = cellY + v.dy
                                val dZ = cellZ + v.dz
                                val pSX = oCellX
                                val pSY = oCellY
                                val pSZ = oCellZ
                                val pDX = oCellX + oV.dx
                                val pDY = oCellY + oV.dy
                                val pDZ = oCellZ + oV.dz
                                val sharesCell =
                                    (pSX == sX && pSY == sY && pSZ == sZ) ||
                                    (pSX == dX && pSY == dY && pSZ == dZ) ||
                                    (pDX == sX && pDY == sY && pDZ == sZ) ||
                                    (pDX == dX && pDY == dY && pDZ == dZ)
                                if (sharesCell) return false
                            }
                        }
                        // Strict-Y bbox for both pathway and stair.
                        // The block-level [canOverwriteForConnection]
                        // guard handles the only genuine paint
                        // contention at the stair's top tread vs a
                        // same-cellY pathway's floor (stair wins as
                        // critical block). Using INCLUSIVE-Y here was
                        // rejecting every stair whose source / target
                        // floor Y matched a neighbouring cell's
                        // cardinal pathway floor — i.e. every typical
                        // adjacency — and gutted stair density.
                        if (!bboxOverlapsStrictY(thisBox, otherBox)) continue
                        if (isReciprocalPair(cellX, cellY, cellZ, vIdx, oCellX, oCellY, oCellZ, oPick)) continue
                        if (isPathway) {
                            // A pathway whose Y stratum strictly
                            // overlaps a stair flight (i.e. the
                            // pathway's floor Y lies STRICTLY between
                            // the stair's source / target floor Ys)
                            // really would collide — the stair's
                            // mid-flight treads pass through that
                            // pathway's walkway. Reject the stair.
                            return false
                        }
                        // Stairwell-vs-stairwell: reject only the configurations where
                        // stair flights visibly interpenetrate (anti-parallel adjacent +
                        // stacked-Y opposite direction); see [stairwellsCrossInSharedVolume].
                        // A strict 3-axis bbox check also catches cross-axis convergence at
                        // the shared destination, but that geometry just reads as two stairs
                        // meeting at a junction — rejecting it halves per-cell density.
                        if (!stairwellsCrossInSharedVolume(
                                v, cellX, cellY, cellZ, oV, oCellX, oCellY, oCellZ,
                            )
                        ) continue
                        // Pair-symmetric tiebreaker: both cells compute
                        // the same pair hash (it's commutative in its
                        // args) and the same winner, so exactly one of
                        // the criss-crossing stairs rejects regardless
                        // of which side computes. Using a hash bit
                        // instead of pure lex rank prevents the
                        // systematic low-coord bias that left
                        // high-coord cells stairwell-starved.
                        val thisRank = connectionRank(cellX, cellY, cellZ, vIdx)
                        val oRank = connectionRank(oCellX, oCellY, oCellZ, oPick)
                        val pairSeed = mazeHash(
                            cellX + oCellX,
                            cellY + oCellY,
                            cellZ + oCellZ,
                            vIdx + oPick,
                        )
                        val higherRankWins = (pairSeed and 1) != 0
                        val thisIsLow = thisRank < oRank
                        if (thisIsLow == higherRankWins) return false
                    }
                }
            }
        }
        return true
    }

    /** Like [bboxOverlaps] but with a STRICT Y check — two boxes that only
     *  touch at a single boundary Y are reported as non-overlapping. */
    private fun bboxOverlapsStrictY(a: IntArray, b: IntArray): Boolean =
        a[0] <= b[1] && a[1] >= b[0] &&
            a[2] < b[3] && a[3] > b[2] &&
            a[4] <= b[5] && a[5] >= b[4]

    /** Strict overlap on ALL three axes — two boxes that only touch at a
     *  single boundary plane on any axis are reported as non-overlapping.
     *  Used by the stairwell-vs-stairwell check to identify true
     *  block-sharing crossings vs adjacent-but-tangent flights. */
    private fun bboxOverlapsAllStrict(a: IntArray, b: IntArray): Boolean =
        a[0] < b[1] && a[1] > b[0] &&
            a[2] < b[3] && a[3] > b[2] &&
            a[4] < b[5] && a[5] > b[4]

    /** Picks for this cell: ALWAYS includes one preferred diagonal
     *  stairwell ([preferredStairwell]) plus the two vertical ladders, then
     *  fills in any cardinal pathway whose bbox doesn't overlap a vicinity
     *  preferred stairwell. Returns a shared immutable IntArray from
     *  [PICKS_BY_KEY]; no per-call allocation.
     *
     *  Looks up the per-cell key from [skipMaskCache] (populated at the
     *  start of [fillFromNoise]); falls back to recomputing otherwise. */
    private fun pickConnections(cellX: Int, cellY: Int, cellZ: Int): IntArray =
        pickConnectionsCtx(activeCaches.get(), cellX, cellY, cellZ)

    /** Hot inner-loop variant of [pickConnections] that accepts a
     *  pre-fetched [ChunkCaches]? — callers in tight loops (e.g.
     *  [stairwellAllowed]'s 125-cell scan) hoist the ThreadLocal
     *  lookup once and pass the result, avoiding per-call
     *  `activeCaches.get()` overhead. */
    private fun pickConnectionsCtx(
        ctx: ChunkCaches?, cellX: Int, cellY: Int, cellZ: Int,
    ): IntArray {
        val cache = ctx?.skipMaskCache
        if (cache != null) {
            val dx = cellX - ctx.skipMaskBaseX
            val dy = cellY - ctx.skipMaskBaseY
            val dz = cellZ - ctx.skipMaskBaseZ
            if (dx in 0 until ctx.skipMaskWidthX &&
                dy in 0 until ctx.skipMaskWidthY &&
                dz in 0 until ctx.skipMaskWidthZ
            ) {
                val idx = (dy * ctx.skipMaskWidthZ + dz) * ctx.skipMaskWidthX + dx
                return PICKS_BY_KEY[cache[idx]]
            }
        }
        return PICKS_BY_KEY[computePickKey(cellX, cellY, cellZ)]
    }

    /** Pack the preferred-stairwell index (0..7) and the 4-bit pathway skip
     *  mask into a single int suitable for [PICKS_BY_KEY] lookup. */
    private fun computePickKey(cellX: Int, cellY: Int, cellZ: Int): Int {
        val preferred = preferredStairwell(cellX, cellY, cellZ)
        val skipMask = pathwaySkipMask(cellX, cellY, cellZ)
        return (preferred - STAIRWELL_VIDX_BASE) * 16 + skipMask
    }

    /** Each cell deterministically chooses one diagonal stairwell as its
     *  guaranteed connection. Iterates through the 8 stairwell vIdx (6..13)
     *  starting at a hash-rolled offset and returns the first one whose
     *  target cell exists — guarantees "every cell with any reachable
     *  neighbour has at least one stairwell". */
    private fun preferredStairwell(cellX: Int, cellY: Int, cellZ: Int): Int {
        val first = positiveMod(mazeHash(cellX, cellY, cellZ, 421), STAIRWELL_COUNT)
        for (offset in 0 until STAIRWELL_COUNT) {
            val sIdx = STAIRWELL_VIDX_BASE + (first + offset) % STAIRWELL_COUNT
            val v = CONN_VECTORS[sIdx]
            if (mazeNodeAt(cellX + v.dx, cellY + v.dy, cellZ + v.dz) != null) return sIdx
        }
        return STAIRWELL_VIDX_BASE + first
    }

    /** Skip mask for the 4 cardinal pathways. A pathway bit is set if the
     *  pathway can't paint (no neighbour) OR its bbox overlaps the
     *  preferred stairwell of this cell or the edge-sharing neighbour cell.
     *  Pathways at cellY ± 1 also rarely conflict via Y=0 boundary; we
     *  check those too to keep visual quirks rare. */
    private fun pathwaySkipMask(cellX: Int, cellY: Int, cellZ: Int): Int {
        var mask = 0
        for (pIdx in PATHWAY_EAST..PATHWAY_NORTH) {
            val v = CONN_VECTORS[pIdx]
            if (mazeNodeAt(cellX + v.dx, cellY + v.dy, cellZ + v.dz) == null) {
                mask = mask or (1 shl pIdx)
                continue
            }
            val pathwayBox = connectionBBox(cellX, cellY, cellZ, pIdx)
            if (pathwayConflictsWithStairwell(cellX, cellY, cellZ, pathwayBox) ||
                pathwayConflictsWithStairwell(cellX + v.dx, cellY, cellZ + v.dz, pathwayBox) ||
                pathwayConflictsWithStairwell(cellX, cellY - 1, cellZ, pathwayBox) ||
                pathwayConflictsWithStairwell(cellX, cellY + 1, cellZ, pathwayBox) ||
                pathwayConflictsWithStairwell(cellX + v.dx, cellY - 1, cellZ + v.dz, pathwayBox) ||
                pathwayConflictsWithStairwell(cellX + v.dx, cellY + 1, cellZ + v.dz, pathwayBox)
            ) {
                mask = mask or (1 shl pIdx)
            }
        }
        return mask
    }

    private fun pathwayConflictsWithStairwell(
        cellX: Int, cellY: Int, cellZ: Int,
        pathwayBox: IntArray,
    ): Boolean {
        if (mazeNodeAt(cellX, cellY, cellZ) == null) return false
        val sIdx = preferredStairwell(cellX, cellY, cellZ)
        val sv = CONN_VECTORS[sIdx]
        if (mazeNodeAt(cellX + sv.dx, cellY + sv.dy, cellZ + sv.dz) == null) return false
        val scratch = connectionBBox(cellX, cellY, cellZ, sIdx)
        return bboxOverlaps(pathwayBox, scratch)
    }

    private fun mazeBlockAt(wx: Int, wy: Int, wz: Int): BlockState? {
        // Connections can span up to one cell in any axis (a stairwell crosses
        // one cellX/Z and one cellY; a vertical ladder crosses one cellY). So
        // iterate cells whose realised blocks could include (wx, wy, wz),
        // accounting for the GRID_OFFSET that shifts each platform inside its
        // cell.
        val minCellX = Math.floorDiv(wx - GRID_OFFSET - MAZE_CELL_X, MAZE_CELL_X)
        val maxCellX = Math.floorDiv(wx - GRID_OFFSET, MAZE_CELL_X)
        val minCellZ = Math.floorDiv(wz - GRID_OFFSET - MAZE_CELL_Z, MAZE_CELL_Z)
        val maxCellZ = Math.floorDiv(wz - GRID_OFFSET, MAZE_CELL_Z)
        // Grid Y is now anchored at 0, so cellY = floorDiv(wy, MAZE_CELL_Y).
        val minCellY = Math.floorDiv(wy - MAZE_CELL_Y, MAZE_CELL_Y)
        val maxCellY = Math.floorDiv(wy + MAZE_CELL_Y, MAZE_CELL_Y)

        // Pre-fetch context once: connectionBBoxCtx + stairwellAllowed
        // both hit thread-local memos; threading the ctx through saves
        // a ThreadLocal.get() per inner iteration on the hottest gen
        // path.
        val ctx = activeCaches.get()
        for (cellX in minCellX..maxCellX) {
            for (cellY in minCellY..maxCellY) {
                for (cellZ in minCellZ..maxCellZ) {
                    val node = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                    for (pickIdx in pickConnections(cellX, cellY, cellZ)) {
                        // bbox pre-filter: most picks have bboxes that
                        // don't contain (wx, wy, wz). 6 int comparisons
                        // skip the rest of the pick-handling, which
                        // otherwise pays for stairwellAllowed cache
                        // lookups + the connectionBlockForVector
                        // dispatch on every pick of every neighbouring
                        // cell, for every block in the chunk.
                        val bbox = connectionBBoxCtx(ctx, cellX, cellY, cellZ, pickIdx)
                        if (wx < bbox[0] || wx > bbox[1] ||
                            wy < bbox[2] || wy > bbox[3] ||
                            wz < bbox[4] || wz > bbox[5]
                        ) continue
                        if (IS_STAIRWELL_VEC[pickIdx] && !stairwellAllowed(cellX, cellY, cellZ, pickIdx)) continue
                        val v = CONN_VECTORS[pickIdx]
                        val neighbour = mazeNodeAt(cellX + v.dx, cellY + v.dy, cellZ + v.dz) ?: continue
                        val block = connectionBlockForVector(wx, wy, wz, node, neighbour, v)
                        block?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** Pure-vertical ladder column between two grid-aligned platforms (same
     *  cellX/cellZ, cellY differing by 1). `a` is the LOWER platform.
     *
     *  Trapdoors cap both ends (so both platforms' centres show a trapdoor).
     *  Facing is randomised per ladder; the backing column sits opposite the
     *  facing and uses deepslate brick. */
    private fun connectionBlockY(wx: Int, wy: Int, wz: Int, a: MazeNode, b: MazeNode): BlockState? {
        val ladderX = a.nx + 2
        val ladderZ = a.nz + 2
        val ladderLo = a.ny
        val ladderHi = b.ny
        val facing = LADDER_FACINGS[positiveMod(mazeHash(a.nx, a.ny, a.nz, 91), 4)]
        // Backing block sits on the side OPPOSITE the facing.
        val backingX = ladderX - facing.stepX
        val backingZ = ladderZ - facing.stepZ
        if (wx == ladderX && wz == ladderZ) {
            if (wy == ladderHi || wy == ladderLo) return trapdoorState(facing)
            if (wy in (ladderLo + 1) until ladderHi) return ladderState(facing)
        }
        if (wx == backingX && wz == backingZ && wy in ladderLo..ladderHi) {
            return DEEPSLATE_BRICKS
        }
        return null
    }

    /** Unused after the grid rewrite — kept to satisfy old call sites until
     *  they're all removed. (Never actually invoked.) */
    @Suppress("unused")
    private fun axisBranchBlockAt(wx: Int, wy: Int, wz: Int, node: MazeNode): BlockState? {
        val centreX = node.nx + 2
        val centreZ = node.nz + 2
        val distToXAxis = Math.min(Math.abs(centreZ), Math.abs(centreZ - 1))
        val distToZAxis = Math.min(Math.abs(centreX), Math.abs(centreX - 1))
        val nearXAxis = distToXAxis in (FLOOR_SIZE)..AXIS_BRANCH_RANGE
        val nearZAxis = distToZAxis in (FLOOR_SIZE)..AXIS_BRANCH_RANGE
        if (!nearXAxis && !nearZAxis) return null
        val branchToXAxis = if (nearXAxis && nearZAxis) distToXAxis <= distToZAxis else nearXAxis
        return if (branchToXAxis) {
            val targetZ = if (centreZ > 0) 1 else 0
            val virtualNz = targetZ - 2
            val virtual = MazeNode(node.nx, PATH_Y, virtualNz, 0)
            if (centreZ < targetZ) connectionBlockZ(wx, wy, wz, node, virtual)
            else connectionBlockZ(wx, wy, wz, virtual, node)
        } else {
            val targetX = if (centreX > 0) 1 else 0
            val virtualNx = targetX - 2
            val virtual = MazeNode(virtualNx, PATH_Y, node.nz, 0)
            if (centreX < targetX) connectionBlockX(wx, wy, wz, node, virtual)
            else connectionBlockX(wx, wy, wz, virtual, node)
        }
    }

    /** L-shape corridor from `a` to `b` where `b` is east (+X) of `a`.
     *
     *  Vertical transition method depends on `|a.ny − b.ny|`:
     *   - 0: simple 2-wide L (X-leg + Z-leg at shared Y).
     *   - 1..[STAIR_THRESHOLD]: **stair flight** embedded in the X-leg; the X-leg
     *     splits into a flat section at `a.ny`, then `|Δy|` stairs descending or
     *     ascending east, then a flat section at `b.ny`, finally the Z-leg.
     *   - >[STAIR_THRESHOLD]: ladder column at the end of the X-leg, Z-leg at the
     *     post-ladder Y, plus polished-deepslate backing wall.
     */
    private fun connectionBlockX(wx: Int, wy: Int, wz: Int, a: MazeNode, b: MazeNode): BlockState? {
        if (Math.abs(a.ny - b.ny) > MAX_CONNECT_Y_DIFF) return null

        val aExitX = a.nx + FLOOR_SIZE
        val ladderX = b.nx + 2
        val aZ = a.nz + 2
        val ladderZ = b.nz + 2
        val yDiff = b.ny - a.ny
        val absDiff = Math.abs(yDiff)
        val width = corridorWidth(a, b)
        // Centre the W-wide corridor on the platform's centre line.
        // For W=1 → [aZ, aZ]; for W=3 → [aZ-1, aZ+1].
        val xLegPerpLo = aZ - (width - 1) / 2
        val xLegPerpHi = aZ + width / 2

        // Available X distance from corridor entry to the column above B's floor centre.
        val corridorLen = ladderX - aExitX   // can be negative if b sits to the west

        if (yDiff == 0) {
            // X-leg at a.ny, W-wide in Z centred on aZ.
            if (wy == a.ny && wz in xLegPerpLo..xLegPerpHi) {
                if (wx in Math.min(aExitX, ladderX)..Math.max(aExitX, ladderX)) {
                    return POLISHED_DEEPSLATE
                }
            }
            // Z-leg at a.ny, W-wide in X centred on ladderX.
            val zLegPerpLo = ladderX - (width - 1) / 2
            val zLegPerpHi = ladderX + width / 2
            if (wy == a.ny && wx in zLegPerpLo..zLegPerpHi) {
                if (wz in Math.min(aZ, ladderZ)..Math.max(aZ, ladderZ)) {
                    return POLISHED_DEEPSLATE
                }
            }
            return null
        }

        if (absDiff <= STAIR_THRESHOLD && Math.abs(corridorLen) >= absDiff + 2) {
            val descending = yDiff < 0
            val stairCount = absDiff
            val dx = if (corridorLen > 0) 1 else -1
            // Centre the stair flight in the corridor: flat at a.ny for
            // [preStairFlat] blocks before the flight, then stairCount
            // descending/ascending treads, then flat at b.ny until the
            // ladder. This way the stair-vs-wall intersection lands at the
            // geometric midpoint (Y ≈ (a.ny+b.ny)/2) instead of pinning to
            // a.ny or b.ny at the wall.
            val preStairFlat = (Math.abs(corridorLen) - stairCount) / 2
            val stairBaseX = aExitX + dx * preStairFlat

            // Pre-flat at a.ny from aExitX (inclusive) to stairBaseX (exclusive).
            val preFlatIdx = (wx - aExitX) * dx
            if (preFlatIdx in 0 until preStairFlat && wy == a.ny &&
                wz in xLegPerpLo..xLegPerpHi
            ) {
                return POLISHED_DEEPSLATE
            }

            // O(1) stair lookup: index relative to the (offset) stair base.
            // Ascending: first tread Y=a.ny+1, last Y=b.ny.
            // Descending: first tread Y=a.ny, last Y=b.ny+1.
            val stairIdx = (wx - stairBaseX) * dx
            if (stairIdx in 0 until stairCount && wz in xLegPerpLo..xLegPerpHi) {
                val sy = if (descending) a.ny - stairIdx else a.ny + 1 + stairIdx
                if (wy == sy) {
                    val facing = if (descending) {
                        if (dx > 0) Direction.WEST else Direction.EAST
                    } else {
                        if (dx > 0) Direction.EAST else Direction.WEST
                    }
                    return polishedStair(facing)
                }
                if (wy == sy - 1) return DEEPSLATE_BRICKS
            }
            // Post-flat at b.ny from (stairBaseX + dx*stairCount) to ladderX.
            val flatStartX = stairBaseX + dx * stairCount
            if (wy == b.ny && wz in xLegPerpLo..xLegPerpHi) {
                if (wx in Math.min(flatStartX, ladderX)..Math.max(flatStartX, ladderX)) {
                    return POLISHED_DEEPSLATE
                }
            }
            // Z-leg at b.ny over to B's floor centre, W-wide in X centred on ladderX.
            val zLegPerpLo = ladderX - (width - 1) / 2
            val zLegPerpHi = ladderX + width / 2
            if (wy == b.ny && wx in zLegPerpLo..zLegPerpHi) {
                if (wz in Math.min(aZ, ladderZ)..Math.max(aZ, ladderZ)) {
                    return POLISHED_DEEPSLATE
                }
            }
            return null
        }

        val xLegFar = if (ladderX > aExitX) ladderX - 1 else ladderX + 1
        if (wy == a.ny && wz in xLegPerpLo..xLegPerpHi) {
            if (wx in Math.min(aExitX, xLegFar)..Math.max(aExitX, xLegFar)) {
                return POLISHED_DEEPSLATE
            }
        }
        // Z-leg @ b.ny, W-wide in X anchored at xLegFar and extending toward `away from ladder`.
        val zLegWideOffset = if (xLegFar < ladderX) -1 else 1
        val zLegPerpLo = Math.min(xLegFar, xLegFar + (width - 1) * zLegWideOffset)
        val zLegPerpHi = Math.max(xLegFar, xLegFar + (width - 1) * zLegWideOffset)
        if (wy == b.ny && wx in zLegPerpLo..zLegPerpHi) {
            if (wz in Math.min(aZ, ladderZ)..Math.max(aZ, ladderZ)) {
                return POLISHED_DEEPSLATE
            }
        }
        // Ladder column + backing wall + trapdoor at top + chiseled deepslate
        // at the bottom (the chiseled block IS the floor the player steps onto
        // when they reach the bottom of the ladder).
        val ladderFacing = if (ladderX > aExitX) Direction.WEST else Direction.EAST
        val ladderLo = Math.min(a.ny, b.ny)
        val ladderHi = Math.max(a.ny, b.ny)
        val wallX = ladderX - ladderFacing.stepX
        if (wx == ladderX && wz == aZ) {
            if (wy == ladderHi) return trapdoorState(ladderFacing)
            if (wy == ladderLo) return CHISELED_DEEPSLATE
            if (wy in (ladderLo + 1) until ladderHi) return ladderState(ladderFacing)
        }
        if (wx == wallX && wz == aZ && wy in ladderLo..ladderHi) {
            return DEEPSLATE_BRICKS
        }
        // Fill the corridor's other-Z columns at the ladder column at the two
        // anchor Y levels (ladderLo and ladderHi) so the wider corridor doesn't
        // collapse to a 1-block gap at the ladder. The ladder itself occupies
        // (ladderX, _, aZ); we fill (ladderX, ladderLo|ladderHi, aZ+1..aZ+W-1).
        if (wx == ladderX && wz in xLegPerpLo..xLegPerpHi && wz != aZ &&
            (wy == ladderLo || wy == ladderHi)
        ) {
            return POLISHED_DEEPSLATE
        }
        return null
    }

    /** Mirror of [connectionBlockX] for the `+Z` axis. Same logic — flat L, stair
     *  flight, or ladder + wall depending on Y diff. */
    private fun connectionBlockZ(wx: Int, wy: Int, wz: Int, a: MazeNode, b: MazeNode): BlockState? {
        if (Math.abs(a.ny - b.ny) > MAX_CONNECT_Y_DIFF) return null

        val aExitZ = a.nz + FLOOR_SIZE
        val ladderZ = b.nz + 2
        val aX = a.nx + 2
        val ladderX = b.nx + 2
        val yDiff = b.ny - a.ny
        val absDiff = Math.abs(yDiff)
        val corridorLen = ladderZ - aExitZ
        val width = corridorWidth(a, b)
        // Centre the W-wide corridor on the platform's centre line.
        val zLegPerpLo = aX - (width - 1) / 2
        val zLegPerpHi = aX + width / 2

        if (yDiff == 0) {
            if (wy == a.ny && wx in zLegPerpLo..zLegPerpHi) {
                if (wz in Math.min(aExitZ, ladderZ)..Math.max(aExitZ, ladderZ)) {
                    return POLISHED_DEEPSLATE
                }
            }
            val xLegPerpLo = ladderZ - (width - 1) / 2
            val xLegPerpHi = ladderZ + width / 2
            if (wy == a.ny && wz in xLegPerpLo..xLegPerpHi) {
                if (wx in Math.min(aX, ladderX)..Math.max(aX, ladderX)) {
                    return POLISHED_DEEPSLATE
                }
            }
            return null
        }

        if (absDiff <= STAIR_THRESHOLD && Math.abs(corridorLen) >= absDiff + 2) {
            val descending = yDiff < 0
            val stairCount = absDiff
            val dz = if (corridorLen > 0) 1 else -1
            // Centre the stair flight in the corridor (see [connectionBlockX]
            // for rationale). Pre-flat at a.ny, then stair, then post-flat
            // at b.ny — so the stair-vs-wall intersection lands mid-corridor.
            val preStairFlat = (Math.abs(corridorLen) - stairCount) / 2
            val stairBaseZ = aExitZ + dz * preStairFlat

            val preFlatIdx = (wz - aExitZ) * dz
            if (preFlatIdx in 0 until preStairFlat && wy == a.ny &&
                wx in zLegPerpLo..zLegPerpHi
            ) {
                return POLISHED_DEEPSLATE
            }

            val stairIdx = (wz - stairBaseZ) * dz
            if (stairIdx in 0 until stairCount && wx in zLegPerpLo..zLegPerpHi) {
                val sy = if (descending) a.ny - stairIdx else a.ny + 1 + stairIdx
                if (wy == sy) {
                    val facing = if (descending) {
                        if (dz > 0) Direction.NORTH else Direction.SOUTH
                    } else {
                        if (dz > 0) Direction.SOUTH else Direction.NORTH
                    }
                    return polishedStair(facing)
                }
                if (wy == sy - 1) return DEEPSLATE_BRICKS
            }
            val flatStartZ = stairBaseZ + dz * stairCount
            if (wy == b.ny && wx in zLegPerpLo..zLegPerpHi) {
                if (wz in Math.min(flatStartZ, ladderZ)..Math.max(flatStartZ, ladderZ)) {
                    return POLISHED_DEEPSLATE
                }
            }
            val xLegPerpLo = ladderZ - (width - 1) / 2
            val xLegPerpHi = ladderZ + width / 2
            if (wy == b.ny && wz in xLegPerpLo..xLegPerpHi) {
                if (wx in Math.min(aX, ladderX)..Math.max(aX, ladderX)) {
                    return POLISHED_DEEPSLATE
                }
            }
            return null
        }

        val zLegFar = if (ladderZ > aExitZ) ladderZ - 1 else ladderZ + 1
        if (wy == a.ny && wx in zLegPerpLo..zLegPerpHi) {
            if (wz in Math.min(aExitZ, zLegFar)..Math.max(aExitZ, zLegFar)) {
                return POLISHED_DEEPSLATE
            }
        }
        val xLegWideOffset = if (zLegFar < ladderZ) -1 else 1
        val xLegPerpLo = Math.min(zLegFar, zLegFar + (width - 1) * xLegWideOffset)
        val xLegPerpHi = Math.max(zLegFar, zLegFar + (width - 1) * xLegWideOffset)
        if (wy == b.ny && wz in xLegPerpLo..xLegPerpHi) {
            if (wx in Math.min(aX, ladderX)..Math.max(aX, ladderX)) {
                return POLISHED_DEEPSLATE
            }
        }
        val ladderFacing = if (ladderZ > aExitZ) Direction.NORTH else Direction.SOUTH
        val ladderLo = Math.min(a.ny, b.ny)
        val ladderHi = Math.max(a.ny, b.ny)
        val wallZ = ladderZ - ladderFacing.stepZ
        if (wx == aX && wz == ladderZ) {
            if (wy == ladderHi) return trapdoorState(ladderFacing)
            if (wy == ladderLo) return CHISELED_DEEPSLATE
            if (wy in (ladderLo + 1) until ladderHi) return ladderState(ladderFacing)
        }
        if (wx == aX && wz == wallZ && wy in ladderLo..ladderHi) {
            return DEEPSLATE_BRICKS
        }
        // Fill the corridor's other-X columns at the ladder column at the two
        // anchor Y levels so the wider corridor doesn't collapse at the ladder.
        if (wx in zLegPerpLo..zLegPerpHi && wx != aX && wz == ladderZ &&
            (wy == ladderLo || wy == ladderHi)
        ) {
            return POLISHED_DEEPSLATE
        }
        return null
    }

    /** Returns one of the four cached ladder blockstates for `facing`. */
    private fun ladderState(facing: Direction): BlockState = when (facing) {
        Direction.NORTH -> LADDER_NORTH
        Direction.SOUTH -> LADDER_SOUTH
        Direction.EAST -> LADDER_EAST
        Direction.WEST -> LADDER_WEST
        else -> LADDER_NORTH
    }

    /** Returns a bamboo trapdoor blockstate facing `facing`, sitting at the top of
     *  the block (HALF=TOP) so it's flush with the upper floor when closed.
     *  Backed by [cachedTrapdoor] — no setValue chain per call. */
    private fun trapdoorState(facing: Direction): BlockState = cachedTrapdoor(facing)


    private fun hash(a: Int, b: Int, c: Int, salt: Int): Int {
        var h = a * 0x9E3779B1.toInt() xor b * 0x85EBCA77.toInt() xor c * 0xC2B2AE35.toInt() xor salt * 0x27D4EB2F.toInt()
        h = (h xor (h ushr 16)) * 0x85EBCA6B.toInt()
        h = (h xor (h ushr 13)) * 0xC2B2AE35.toInt()
        return h xor (h ushr 16)
    }

    private fun mazeHash(a: Int, b: Int, c: Int, salt: Int): Int =
        hash(a + 73, b + 113, c + 167, salt)

    private fun positiveMod(value: Int, divisor: Int): Int {
        val m = value % divisor
        return if (m < 0) m + divisor else m
    }


    override fun applyCarvers(
        level: WorldGenRegion, seed: Long, randomState: RandomState,
        biomeManager: BiomeManager, structureManager: StructureManager,
        chunk: ChunkAccess, step: GenerationStep.Carving,
    ) {}

    override fun buildSurface(
        level: WorldGenRegion, structureManager: StructureManager,
        randomState: RandomState, chunk: ChunkAccess,
    ) {}

    /** Sselith spawns Catalogers per `(cellX, cellZ)` library cell column,
     *  not via the biome's natural-spawner roll. For each column whose
     *  platform-centre tile falls inside this chunk's XZ footprint, place one
     *  Cataloger on every valid platform in the vertical stack — one per layer.
     *  Layers with no platform get none, so columns with gaps spawn fewer.
     *  Per-chunk, so it fires exactly once when the chunk first generates and is
     *  persisted with the chunk afterwards. */
    override fun spawnOriginalMobs(level: WorldGenRegion) {
        val chunkPos = level.center
        val serverLevel = level.level

        // Flush POI registrations queued by fillFromNoise. See the doc comment on
        // [pendingPoiRegistrations] for why this dance is needed — chunk gen writes
        // bypass the only paths that auto-register POIs (`onBlockStateChange` during
        // gen, `ChunkSerializer.read` on load).
        //
        // Crucially: `poiManager.add` MUST run on the server thread. PoiManager and
        // the underlying `SectionStorage.dirty` `LongLinkedOpenHashSet` are not
        // thread-safe; mutating from this chunk-gen worker thread corrupts the dirty
        // set and the next server tick crashes inside
        // `LongLinkedOpenHashSet.fixPointers`. Vanilla's
        // `ServerLevel.onBlockStateChange` does the same deferral via
        // `MinecraftServer.execute`.
        val pending = pendingPoiRegistrations.remove(chunkPos)
        if (pending != null && pending.isNotEmpty()) {
            val poiManager = serverLevel.poiManager
            serverLevel.server.execute {
                for ((pos, holder) in pending) {
                    try {
                        poiManager.add(pos, holder)
                    } catch (_: IllegalStateException) {
                        // `PoiSection.add` throws if the position already holds a
                        // different POI type. Shouldn't happen — we only record
                        // positions where we placed a POI-target block — but be
                        // defensive.
                    }
                }
            }
        }

        val chunkX0 = chunkPos.minBlockX
        val chunkZ0 = chunkPos.minBlockZ
        val chunkX1 = chunkX0 + 15
        val chunkZ1 = chunkZ0 + 15
        val pad = LIBRARY_QUADRANT_SHIFT + 1
        val minCellX = Math.floorDiv(chunkX0 - 2 - pad - GRID_OFFSET, MAZE_CELL_X)
        val maxCellX = Math.floorDiv(chunkX1 - 2 + pad - GRID_OFFSET, MAZE_CELL_X)
        val minCellZ = Math.floorDiv(chunkZ0 - 2 - pad - GRID_OFFSET, MAZE_CELL_Z)
        val maxCellZ = Math.floorDiv(chunkZ1 - 2 + pad - GRID_OFFSET, MAZE_CELL_Z)

        for (cellX in minCellX..maxCellX) {
            val nx = platformOriginX(cellX)
            val centreX = nx + 2
            if (centreX < chunkX0 || centreX > chunkX1) continue
            for (cellZ in minCellZ..maxCellZ) {
                val nz = platformOriginZ(cellZ)
                val centreZ = nz + 2
                if (centreZ < chunkZ0 || centreZ > chunkZ1) continue

                // One Cataloger per valid platform in this column — one per layer.
                // Layers without a platform (`mazeNodeAt == null`: void gaps, or
                // cells the maze never carved) naturally get none, so a column
                // with holes spawns fewer than the full vertical stack. Profiling
                // showed cataloger cost scales ~linearly with count (neighbour
                // collision / push is ~1.5% of the tick), so a full per-layer fill
                // stays predictable; see [Cataloger]'s fluid-strip overrides and
                // the natural-spawn skip that bought the budget for this density.
                for (cellY in 0 until TOP_CELL_Y) {
                    val pickedNode = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                    val pickedCellY = cellY

                    // Halve density — spark profile showed 713 catalogers
                    // dominated server-tick cost in a typical Sselith
                    // session. Per-cell hash so the same platforms always
                    // do/skip across reloads, no chunkgen non-determinism.
                    if ((mazeHash(cellX, pickedCellY, cellZ, 0x6A71F4) and 1) == 0) continue

                    // Pick the spawn tile NOW — uses `level.getBlockState`
                    // which only works while the chunk is being generated
                    // (WorldGenRegion is bounded to the spawn chunk).
                    // The actual entity creation is deferred via
                    // [SselithPendingSpawns] so the per-chunk-batch mob-
                    // construction cost smears across server ticks instead
                    // of spiking on the tick the chunk reaches SPAWN.
                    val (spawnX, spawnZ) = pickCatalogerSpawnTile(
                        level, centreX, centreZ, pickedNode.ny + 1,
                        chunkX0, chunkX1, chunkZ0, chunkZ1,
                        mazeHash(cellX, pickedCellY, cellZ, 912),
                    )
                    SselithPendingSpawns.enqueue(
                        serverLevel,
                        SselithPendingSpawns.Intent.Cataloger(
                            spawnX, pickedNode.ny + 1, spawnZ,
                        ),
                    )
                }

                // For every cell in this chunk's centre column that hosts a
                // Gallery, fill each of the four interior side walls with a
                // randomly-tiled grid of paintings. The pack starts from a
                // random variant + position and then greedily places
                // largest-area paintings into every remaining empty cell,
                // giving a dense but visually varied wall.
                for (cellY in 0 until TOP_CELL_Y) {
                    val node = mazeNodeAt(cellX, cellY, cellZ) ?: continue
                    if (replacementTypeAt(cellX, cellY, cellZ) != StructureReplacement.GALLERY) continue
                    spawnGalleryPaintings(level, node, StructureReplacement.GALLERY)
                }
            }
        }
    }

    /** Pack a random arrangement of paintings onto each of the gallery's
     *  four interior side walls (N / S / E / W) and spawn the entities into
     *  the world. The grid on each wall is `GALLERY_GRID_W × GALLERY_GRID_H`,
     *  centred horizontally on the wall and starting one block above the
     *  gallery floor. Paint packing is deterministic per (node, wall) so
     *  the arrangement stays stable across reloads. */
    private fun spawnGalleryPaintings(
        level: WorldGenRegion, node: MazeNode, type: StructureReplacement,
    ) {
        val cornerX = node.nx + type.cornerOffsetX
        val cornerZ = node.nz + type.cornerOffsetZ
        val cornerY = node.ny + type.cornerOffsetY
        // Centre the W-wide grid horizontally on each wall.
        val xBase = cornerX + (type.dimX - GALLERY_GRID_W) / 2
        val zBase = cornerZ + (type.dimZ - GALLERY_GRID_W) / 2
        // One block above the gallery floor — the bottom row of the grid
        // sits at (cornerY + 1), so 1-tall paintings hang clear of the
        // floor block and 4-tall paintings reach the ceiling.
        val yBase = cornerY + 1
        val serverLevel = level.level

        // The gallery NBT has no outer walls — its "open faces" are the
        // two long sides of the central north-south spine column (the
        // deepslate / cobbled-deepslate stack at `lx = dimX / 2`,
        // spanning `lz = 1..9`). Each side gets its own 4×5 grid of
        // paintings hanging off the spine. The spine wall block is at
        // `cornerX + dimX/2`; paintings hang one block west of it for
        // the west face, one block east for the east face.
        val spineWallX = cornerX + type.dimX / 2
        spawnWallPaintings(serverLevel, node, type, GalleryWall.SPINE_WEST,
            wallX = spineWallX, wallZ = zBase, wallY = yBase, dirOpening = Direction.WEST)
        spawnWallPaintings(serverLevel, node, type, GalleryWall.SPINE_EAST,
            wallX = spineWallX, wallZ = zBase, wallY = yBase, dirOpening = Direction.EAST)
    }

    private fun spawnWallPaintings(
        level: net.minecraft.server.level.ServerLevel,
        node: MazeNode, type: StructureReplacement, wall: GalleryWall,
        wallX: Int, wallZ: Int, wallY: Int, dirOpening: Direction,
    ) {
        val seed = mazeHash(node.nx, node.ny, node.nz, wall.hashSalt).toLong()
        // Pre-mark unpaintable cells (where the NBT wall backer is
        // structure_void or a non-solid block) so the packer only emits
        // paintings whose backer would actually pass vanilla's
        // `survives()` check on the first server tick.
        val occupied = buildGalleryWallOccupied(type, wall)
        val packed = packGalleryWall(GALLERY_GRID_W, GALLERY_GRID_H, seed, occupied)
        for (placed in packed) {
            // Vanilla `HangingEntity.survives()` walks the painting's
            // wall-block footprint along `direction.getCounterClockWise()`
            // at offsets `k + m` where `m = (W-1) / -2` (Java int div).
            // To make the painting cover packer-grid cells
            // `[placed.x, placed.x + W - 1]` we shift the anchor by the
            // distance from the leftmost covered cell to `pos.z`. That
            // distance differs between walls for EVEN widths because the
            // CCW direction flips sign:
            //
            //   WEST → CCW=SOUTH(+z) → offset = (W-1)/2   (0,0,1,1)
            //   EAST → CCW=NORTH(-z) → offset =  W   /2   (0,1,1,2)
            //
            // The Y axis only goes up (no CCW flip), so heightOffset
            // matches WEST's form for both walls.
            val widthOffset = when (wall) {
                GalleryWall.SPINE_WEST -> (placed.variant.w - 1) / 2
                GalleryWall.SPINE_EAST -> placed.variant.w / 2
            }
            val heightOffset = (placed.variant.h - 1) / 2
            // BlockPos of the painting's anchor is the air block one step
            // OUT from the wall along its surface normal (the direction
            // the painting faces).
            val pos = when (wall) {
                GalleryWall.SPINE_WEST -> BlockPos(wallX - 1, wallY + placed.y + heightOffset, wallZ + placed.x + widthOffset)
                GalleryWall.SPINE_EAST -> BlockPos(wallX + 1, wallY + placed.y + heightOffset, wallZ + placed.x + widthOffset)
            }

            // Live-state backer verification is deferred to drain time
            // in [SselithPendingSpawns.spawnPainting] — running it
            // here would call `WorldGenRegion.getBlockState` for the
            // painting's wall footprint, which for paintings whose
            // backer extends into a neighbour chunk throws "asking a
            // region for a chunk out of bound" (SPAWN status grants
            // the worldgen region access to the centre chunk only).
            // At drain time the chunk is fully loaded and `ServerLevel.
            // getBlockState` is unbounded, so the same check there
            // catches the corridor-carved-spine case without crashing.

            // Construct the Painting directly from an NBT compound rather
            // than calling `Painting.create(...)`. `create` iterates all
            // ~30 painting variants and calls `survives()` for each one,
            // which fires 16-block `getBlockState` lookups per variant.
            // On the chunk-gen worker thread those lookups serialise on
            // neighbour-chunk access and freeze the server tick once
            // enough galleries roll in a single chunk window. The NBT
            // path skips the survives loop entirely; vanilla's
            // `HangingEntity.tick` re-checks survives on the entity's
            // first server tick and removes any painting whose backer
            // turned out to be air, so misplaced paintings still self-
            // heal — they just don't block the worker.
            val tag = CompoundTag().apply {
                putString("id", "minecraft:painting")
                put("Pos", ListTag().apply {
                    add(DoubleTag.valueOf(pos.x + 0.5))
                    add(DoubleTag.valueOf(pos.y + 0.5))
                    add(DoubleTag.valueOf(pos.z + 0.5))
                })
                put("Motion", ListTag().apply {
                    add(DoubleTag.valueOf(0.0))
                    add(DoubleTag.valueOf(0.0))
                    add(DoubleTag.valueOf(0.0))
                })
                put("Rotation", ListTag().apply {
                    add(FloatTag.valueOf(0.0f))
                    add(FloatTag.valueOf(0.0f))
                })
                putInt("TileX", pos.x)
                putInt("TileY", pos.y)
                putInt("TileZ", pos.z)
                // NBT key MUST be lowercase "facing" in MC 1.20.1 — vanilla's
                // `Painting.addAdditionalSaveData`/`readAdditionalSaveData` use
                // `putByte("facing", ...)`. The historical capitalized "Facing" reads as
                // default 0 (SOUTH) on load → paintings face the wrong wall and pop off.
                putByte("facing", dirOpening.get2DDataValue().toByte())
                putString("variant", placed.variant.key.location().toString())
            }
            // Defer the EntityType.create + addFreshEntity to a later
            // server tick — chunk-gen workers all funnel back through
            // the server thread for `addFreshEntity`, so a chunk batch
            // hitting SPAWN status the same tick produces a spike.
            // The NBT tag is the full intent; `SselithPendingSpawns`
            // does another live-state backer check at drain time
            // (which is cheap by then — chunk is fully loaded).
            SselithPendingSpawns.enqueue(
                level,
                SselithPendingSpawns.Intent.Painting(tag),
            )
        }
    }

    /** The two paintable faces of the gallery's central north-south
     *  spine. The structure NBT has no outer perimeter walls — its
     *  "open faces" are the west and east faces of the spine column at
     *  the middle of the structure, so paintings hang there. */
    private enum class GalleryWall(val hashSalt: Int) {
        SPINE_WEST(0x4A001),
        SPINE_EAST(0x4A002),
    }

    /** Find a non-centre spawn tile on the 5×5 platform whose foot block (`footY`)
     *  and head block (`footY + 1`) are both air. Iterates [CATALOGER_SPAWN_OFFSETS]
     *  starting from a per-cell offset (so each platform gets a stable but varying
     *  spawn position), and returns the first tile that passes the air check.
     *
     *  Bounded by `[chunkX0..chunkX1, chunkZ0..chunkZ1]`: SPAWN chunk-status only
     *  grants access to the centre chunk, so any offset that would step outside
     *  is skipped — getBlockState would throw "out of bound". Falls back to the
     *  platform centre if every in-bounds offset is occupied (or if every offset
     *  is out of bounds). */
    private fun pickCatalogerSpawnTile(
        level: WorldGenRegion,
        centreX: Int,
        centreZ: Int,
        footY: Int,
        chunkX0: Int, chunkX1: Int,
        chunkZ0: Int, chunkZ1: Int,
        seed: Int,
    ): Pair<Int, Int> {
        val mut = BlockPos.MutableBlockPos()
        val start = positiveMod(seed, CATALOGER_SPAWN_OFFSETS.size)
        for (i in CATALOGER_SPAWN_OFFSETS.indices) {
            val (dx, dz) = CATALOGER_SPAWN_OFFSETS[(start + i) % CATALOGER_SPAWN_OFFSETS.size]
            val sx = centreX + dx
            val sz = centreZ + dz
            if (sx < chunkX0 || sx > chunkX1) continue
            if (sz < chunkZ0 || sz > chunkZ1) continue
            val foot = level.getBlockState(mut.set(sx, footY, sz))
            if (!foot.isAir) continue
            val head = level.getBlockState(mut.set(sx, footY + 1, sz))
            if (!head.isAir) continue
            return sx to sz
        }
        return centreX to centreZ
    }

    override fun getGenDepth(): Int = WORLD_HEIGHT
    override fun getSeaLevel(): Int = MIN_Y
    override fun getMinY(): Int = MIN_Y

    override fun getBaseHeight(
        x: Int, z: Int, type: Heightmap.Types, level: LevelHeightAccessor, randomState: RandomState,
    ): Int = if (isPathStrip(x, z)) MIN_Y else MIN_Y + WORLD_HEIGHT - 1

    override fun getBaseColumn(
        x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState,
    ): NoiseColumn {
        val states: Array<BlockState> = Array(WORLD_HEIGHT) { i ->
            blockAt(x, MIN_Y + i, z) ?: Blocks.AIR.defaultBlockState()
        }
        return NoiseColumn(MIN_Y, states)
    }

    override fun addDebugScreenInfo(
        info: MutableList<String>, randomState: RandomState, pos: BlockPos,
    ) {
        info.add("Sselith's Repertory chunk generator")
    }

    @Suppress("unused")
    fun getBiomeSourceForCodec(): BiomeSource = this.biomeSource


    companion object {
        /** Shared across all C2ME chunk-gen workers — keyed by
         *  [packStairwellKey] (cellX/Y/Z + vIdx). [stairwellAllowed]
         *  is pure of its args, so once one worker computes the
         *  125-cell scan for a (cell, vIdx) every neighbour-chunk
         *  worker reads the same answer instead of recomputing it. */
        private val GLOBAL_STAIRWELL_CACHE: java.util.concurrent.ConcurrentHashMap<Long, Boolean> =
            java.util.concurrent.ConcurrentHashMap()
        /** Cap-and-stop policy: once the cache reaches this size, new
         *  entries stop being added but existing ones keep serving
         *  hits. A player's typical load-window is ~10–20k cells × 8
         *  vIdx ≈ 100–200k entries; this cap fits that comfortably. */
        private const val GLOBAL_STAIRWELL_CACHE_CAP: Int = 262_144

        /** Enable with JVM arg `-Denderkinesis.chunkProfile=true`. When
         *  on, [fillFromNoise] times each chunk and logs a rolling
         *  histogram. Per-chunk timing is the metric to compare across
         *  builds — it doesn't move with exploration intensity the way
         *  the spark sample-per-second number does. */
        private val CHUNK_PROFILE: Boolean =
            System.getProperty("enderkinesis.chunkProfile") == "true"
        private const val CHUNK_PROFILE_LOG_INTERVAL = 256L
        private val PROFILE_LOG = LogUtils.getLogger()
        private val PROF_CHUNKS = AtomicLong()
        private val PROF_NANOS = AtomicLong()
        private val PROF_MAX_NANOS = AtomicLong()
        private val PROF_BUCKETS = AtomicLongArray(8)

        /** Decorative feature pass (floating shelves, towers, walls,
         *  furniture islands, reading nooks, chandeliers, tome shrines,
         *  wall-corners, spiral pillars, lectern gardens, totems, cubes,
         *  bamboo floors, lectern rooms). Disabled while the library tile
         *  is the primary dimension styling. */
        private const val ENABLE_FEATURES = false

        private const val SLOT_SIZE = 12
        private const val SLOT_JITTER = 2
        private const val FEATURE_SKIP_PERCENT = 25
        private const val FEATURE_TYPE_COUNT = 16

        /** Library wall block. Lazy because [EKBlocks.SSELITH_BOOKSHELF] is
         *  populated during mod init, which runs before chunk gen but after
         *  this companion's eager-init pass that builds other cached states. */
        private val BOOKSHELF: BlockState by lazy {
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_BOOKSHELF.get().defaultBlockState()
        }
        private val BAMBOO_PLANKS: BlockState = Blocks.BAMBOO_PLANKS.defaultBlockState()
        /** Per user direction, the dimension uses only regular [BOOKSHELF]
         *  (no chiseled bookshelves). These named aliases exist so the
         *  library tile decoder (which preserves the original 4-facing
         *  variants from the 20w14 `book_box`) maps every facing to the
         *  same `BOOKSHELF` block. */
        private val CHISELED_BOOKSHELF_NORTH: BlockState = BOOKSHELF
        private val CHISELED_BOOKSHELF_SOUTH: BlockState = BOOKSHELF
        private val CHISELED_BOOKSHELF_EAST: BlockState = BOOKSHELF
        private val CHISELED_BOOKSHELF_WEST: BlockState = BOOKSHELF
        private val CHISELED_BOOKSHELF: BlockState = BOOKSHELF
        /** Light source in each library tile (replaces 20w14 glowstone). Used
         *  to be `Blocks.OCHRE_FROGLIGHT` — now uses our own
         *  [org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_LAMP]
         *  so the dimension's palette stays Sselith-themed. */
        private val OCHRE_FROGLIGHT: BlockState =
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_LAMP.get().defaultBlockState()
        /** Floor decoration in each library tile (replaces 20w14 oak slab). */
        private val POLISHED_DEEPSLATE_SLAB_BOTTOM: BlockState =
            Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
        /** Doorway lintel (top half) — used to cap wall-opening frames. */
        private val POLISHED_DEEPSLATE_SLAB_TOP: BlockState =
            Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP)
        /** Lit yellow candle, 1..4 candles per block — cached by
         *  `[count - 1]`. Used as the "top with candles" decoration on
         *  some inner-wall shelves (see [archwayBlock]). */
        private val YELLOW_CANDLES_LIT: Array<BlockState> = Array(4) { i ->
            Blocks.YELLOW_CANDLE.defaultBlockState()
                .setValue(CandleBlock.LIT, true)
                .setValue(CandleBlock.CANDLES, i + 1)
        }


        /** Per-axis shift applied to each library quadrant so the cells back
         *  away from the central axial paths, leaving this many blocks of
         *  empty space between the path edge and the start of the library. */
        private const val LIBRARY_QUADRANT_SHIFT = 5

        /** Extra shift applied ONLY on the +Z half of the world, on top of
         *  [LIBRARY_QUADRANT_SHIFT]. Set to 0 so +Z matches the +X / -X / -Z
         *  quadrants exactly — non-zero values pushed every +Z element one
         *  block too far from the axial path. Left as a configurable
         *  constant in case the +Z asymmetry is ever needed again. */
        private const val POSITIVE_Z_EXTRA_SHIFT = 0

        /** Total height (blocks) of the deepslate-framed opening cut through
         *  each cell wall when a pathway or stairwell crosses. Includes the
         *  corridor floor at the bottom and the polished-deepslate slab
         *  lintel at the top. */
        /** Cell-wall opening height for cardinal pathways AND stair
         *  flights. The stair-flight case is the demanding one: the
         *  opening is positioned at the corridor floor Y at the wall
         *  position, and as the player ascends the stair flight past
         *  the wall their head height rises by one per step. At a
         *  6-block opening (the previous value) the top slab landed
         *  at `floorY + 5`; a player on the wall's stair step plus the
         *  next step crammed the head against the slab. 8 blocks
         *  gives the same flat-corridor look but enough headroom for
         *  the player to walk a stair-bearing 1-wide opening. */
        private const val WALL_OPENING_HEIGHT = 8

        /** Number of stepped tiers at each corner of a gap archway. The
         *  lintel sits at the cell-top row; the tiers descend Y-by-Y and
         *  inset coord-by-coord toward the arch interior, forming a
         *  staircase corbel. Lintel width = `span - 2*(CURVE_DEPTH + 1)`. */
        private const val ARCHWAY_CURVE_DEPTH = 3

        /** Chance (0-100) that a given arch hangs a chain + froglight from
         *  its middle. Rolled per (arch_mid_X, cellY, arch_mid_Z). */
        private const val ARCHWAY_CHAIN_PERCENT = 35
        /** Number of CHAIN blocks below the arch lintel; a single
         *  OCHRE_FROGLIGHT block hangs one further block down. */
        private const val ARCHWAY_CHAIN_LENGTH = 3

        /** Spacing (blocks) between deepslate-tile columns along each gap
         *  arm. Decorative pillars that break up the otherwise-empty padding
         *  between the axial paths and the library walls. */
        private const val GAP_COLUMN_INTERVAL = 8
        /** Gap-side block adjacent to each library wall — the X/Z line on
         *  which the decorative columns sit. */
        private const val GAP_COLUMN_POS_X = LIBRARY_QUADRANT_SHIFT          // 5
        private const val GAP_COLUMN_NEG_X = -LIBRARY_QUADRANT_SHIFT          // -5
        private const val GAP_COLUMN_POS_Z = LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT  // 6
        private const val GAP_COLUMN_NEG_Z = -LIBRARY_QUADRANT_SHIFT          // -5

        /** Inset (from each wall) for the 4 corner froglights / chain pillars
         *  in every library cell. Same inset on all 4 corners of the cell. */
        private const val LIBRARY_FROGLIGHT_INSET = 4

        /** Lowest archway-shelf Y in cell-local coords. */
        private const val ARCHWAY_SHELF_FIRST_Y = 2
        /** Vertical spacing between archway shelves (slab → ladder rungs →
         *  next slab). */
        private const val ARCHWAY_SHELF_SPACING = 4
        /** Per-slot chance (0-100) that an inter-shelf ladder exists. Rolled
         *  separately for each (cell, wall, slot). */
        private const val LADDER_EXISTS_PERCENT = 60
        /** Wall ids used to salt the per-slot ladder hashes. */
        private const val ARCHWAY_WALL_W = 0
        private const val ARCHWAY_WALL_E = 1
        private const val ARCHWAY_WALL_N = 2
        private const val ARCHWAY_WALL_S = 3

        /** Library cell as a 3-D pattern function. Cell size matches our
         *  maze grid (`MAZE_CELL_X × MAZE_CELL_Y × MAZE_CELL_Z` = 49×24×49),
         *  so each cell wraps exactly one of our standard platforms — placed
         *  at world coords `(cellX*48 + GRID_OFFSET, cellY*24, cellZ*48 + GRID_OFFSET)`
         *  by the path system, which overrides whatever this function returns
         *  at the platform area.
         *
         *  Floor (Y=0) follows a concentric ring layout — alternating slab
         *  bands and air gaps that radiate out from the centre, with the
         *  4 corner froglights at `LIBRARY_FROGLIGHT_INSET` from each wall.
         *  Above Y=0 the cell is open air except the 4 chain columns rising
         *  from each froglight to the next cell's floor above. */
        private fun libraryCellBlock(x: Int, y: Int, z: Int): BlockState? {
            val maxX = MAZE_CELL_X - 1
            val maxZ = MAZE_CELL_Z - 1
            val isXWall = x == 0 || x == maxX
            val isZWall = z == 0 || z == maxZ
            if (isXWall && isZWall) return DEEPSLATE_BRICKS
            if (isXWall || isZWall) return BOOKSHELF
            // y=0 cell interior is empty here — both the NBT and the
            // procedural ring patterns are painted by the dedicated
            // ring-decoration pass ([paintRingDecorationInto] or
            // [paintProceduralRingInto]). Painting them inline here as
            // a [LIBRARY_CELL] fallback caused the ring positions to
            // straddle adjacent library tiles (different shift from
            // maze cells), so quadrants showed up half-suppressed or
            // half-spurious. The dedicated paint pass keys on the
            // maze node, which is the source of truth.
            if (y == 0) return null
            // Vertical chains at the 4 froglight columns aren't placed as
            // blocks anymore — SselithChainRenderer draws them client-side
            // as a screen-tall pair of crossed quads, so the chains read as
            // infinite in both directions like a beacon beam without
            // wasting a block per Y.
            val archway = archwayBlock(x, y, z)
            if (archway != null) return archway
            return null
        }

        /** Inner-wall shelving — the bookshelf+slab pattern formerly limited
         *  to a centred archway now extends the full length of each wall.
         *  The wall's outer column (one block into the cell) carries the
         *  static pattern:
         *    - DEEPSLATE_BRICK_SLAB_TOP at every `ARCHWAY_SHELF_SPACING` Y
         *      (shelves at y = 2, 6, 10, 14, 18, 22).
         *    - BOOKSHELF below the lowest shelf (Y < ARCHWAY_SHELF_FIRST_Y),
         *      where there's no shelf to "stack" on top of.
         *    - Above each shelf, a per-column **stack** of bookshelves of
         *      height 1–3 instead of solid fill — different (x, z, shelf-Y)
         *      hash to different heights, so the wall reads as books
         *      standing on the shelf in varying vertical columns with air
         *      above the shorter stacks. Always at least one row of books
         *      sits on every shelf so the shelves themselves never look
         *      empty.
         *
         *  Ladders are NOT placed here — they're injected per-cell at
         *  chunk-gen time so their slot existence and along-wall position
         *  can vary randomly per cell (see archwayLadderAt). */
        private fun archwayBlock(x: Int, y: Int, z: Int): BlockState? {
            if (y < 1 || y > MAZE_CELL_Y - 2) return null
            val maxX = MAZE_CELL_X - 1
            val maxZ = MAZE_CELL_Z - 1
            val isOuter = (x == 1 && z in 1..(maxZ - 1)) ||
                (x == maxX - 1 && z in 1..(maxZ - 1)) ||
                (z == 1 && x in 1..(maxX - 1)) ||
                (z == maxZ - 1 && x in 1..(maxX - 1))
            if (!isOuter) return null
            val heightAboveShelf = (y - ARCHWAY_SHELF_FIRST_Y) % ARCHWAY_SHELF_SPACING
            // Below the lowest shelf — solid fill (nothing to stack on).
            if (y < ARCHWAY_SHELF_FIRST_Y) return BOOKSHELF
            // Shelf row itself.
            if (heightAboveShelf == 0) return DEEPSLATE_BRICK_SLAB_TOP
            val shelfBaseY = y - heightAboveShelf
            // Book stack with hash-varied height (1..ARCHWAY_SHELF_SPACING-1).
            // Positions with `heightAboveShelf <= stackHeight` get a book.
            val stackHeight = 1 + archwayStackHash(x, shelfBaseY, z, 0) % (ARCHWAY_SHELF_SPACING - 1)
            if (heightAboveShelf <= stackHeight) return BOOKSHELF
            // Position immediately above the topmost book — random shelves
            // get a lantern or a varying-count yellow-candle block resting
            // on the top book. Only fires when there's room (the stack
            // isn't already at the section ceiling).
            if (heightAboveShelf == stackHeight + 1) {
                val decoration = shelfTopDecoration(x, shelfBaseY, z)
                if (decoration != null) return decoration
            }
            return null
        }

        /** Chance (0–100) that a shelf-section gets a lantern or candle
         *  decoration on top instead of books. */
        private const val SHELF_DECORATION_PERCENT = 18
        /** Of decorated shelves, fraction that get a lantern vs. candles. */
        private const val SHELF_LANTERN_PERCENT = 40

        /** Per-(x, shelfBaseY, z) decoration roll. Returns LANTERN or a
         *  cached yellow-candle state, or null for "no decoration → use
         *  the normal book stack". Three independent salts keep the
         *  decision/type/count rolls uncorrelated. */
        private fun shelfTopDecoration(x: Int, shelfY: Int, z: Int): BlockState? {
            val roll = archwayStackHash(x, shelfY, z, 1) % 100
            if (roll >= SHELF_DECORATION_PERCENT) return null
            val typeRoll = archwayStackHash(x, shelfY, z, 2) % 100
            if (typeRoll < SHELF_LANTERN_PERCENT) return LANTERN
            val count = 1 + (archwayStackHash(x, shelfY, z, 3) % 4)
            return YELLOW_CANDLES_LIT[count - 1]
        }

        /** Deterministic non-negative hash for the book-stack pattern.
         *  Same general shape as the instance-level [hash] used elsewhere
         *  in this file, but lives in the companion so the static
         *  [archwayBlock] can call it during [LIBRARY_CELL] bake. */
        private fun archwayStackHash(x: Int, shelfY: Int, z: Int, salt: Int): Int {
            var h = x * 0x9E3779B1.toInt() xor
                shelfY * 0x85EBCA77.toInt() xor
                z * 0xC2B2AE35.toInt() xor
                (salt * 0x27D4EB2F.toInt() + 0x27D4EB2F.toInt())
            h = (h xor (h ushr 16)) * 0x85EBCA6B.toInt()
            h = (h xor (h ushr 13)) * 0xC2B2AE35.toInt()
            return (h xor (h ushr 16)) and 0x7FFFFFFF
        }

        /** Pre-computed library cell pattern; indexed `[y][z][x]`. **Lazy**:
         *  the builder runs on first access (after class init has finished),
         *  which guarantees that all the block constants `libraryCellBlock`
         *  references (`CHAIN`, `DEEPSLATE_BRICKS`, …) are already initialised.
         *  ~55k entries, ~440 KB. */
        private val LIBRARY_CELL: Array<Array<Array<BlockState?>>> by lazy {
            Array(MAZE_CELL_Y) { y ->
                Array(MAZE_CELL_Z) { z ->
                    Array(MAZE_CELL_X) { x -> libraryCellBlock(x, y, z) }
                }
            }
        }

        /** Legacy 16³ tile data from `data/minecraft/structures/library.nbt`
         *  (Mojang 20w14infinite client). No longer used after the rescale
         *  to 48×24×48 procedural cells. Kept inline as a reference. */
        @Suppress("unused")
        private const val LIBRARY_TILE_DATA: String =
            "PSSSSSS__SSSSSSPE......__......WE.____________.WE._G_......_G_.W" +
            "E.____________.WE._._.n..n._._.WE._._wPNNPe_._.W___._.W..E._.___" +
            "___._.W..E._.___E._._wPSSPe_._.WE._._.s..s._._.WE.____________.W" +
            "E._G_......_G_.WE.____________.WE......__......WPNNNNNN__NNNNNNP" +
            "PSSSSSS..SSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wPNNPe....W......W..E......" +
            "......W..E......E....wPSSPe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNN..NNNNNNP" +
            "PSSSSSS..SSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wPNNPe....W......W..E......" +
            "......W..E......E....wPSSPe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNN..NNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wPPPPe....WE........P.....W" +
            "E........P.....WE....wPPPPe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wP..Pe....WE..............W" +
            "E..............WE....wP..Pe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP" +
            "PSSSSSSSSSSSSSSPE..............WE..............WE..............W" +
            "E..............WE.....n..n.....WE....wPPPPe....WE.....P..P.....W" +
            "E.....P..P.....WE....wPPPPe....WE.....s..s.....WE..............W" +
            "E..............WE..............WE..............WPNNNNNNNNNNNNNNP"

        /** Explicit-air marker for the feature cache. Distinguishes "feature
         *  has claimed this position as carved space" from "no feature touched
         *  this position" (which gets default-filled with CHISELED_BOOKSHELF). */
        private val AIR: BlockState = Blocks.AIR.defaultBlockState()
        private val DEEPSLATE_BRICKS: BlockState = Blocks.DEEPSLATE_BRICKS.defaultBlockState()
        /** Sentinel returned by [neighbourBlockStateAt] for out-of-cache
         *  positions that the maze-decor predicate identifies as a wall
         *  outline. Vanilla's connection logic short-circuits on
         *  `BlockTags.WALLS`, so any wall blockstate (default
         *  `DEEPSLATE_BRICK_WALL`) yields the correct LOW connection
         *  regardless of its own side properties. */
        private val DEEPSLATE_BRICK_WALL_DEFAULT: BlockState =
            Blocks.DEEPSLATE_BRICK_WALL.defaultBlockState()
        private val DEEPSLATE_BRICK_SLAB_TOP: BlockState =
            Blocks.DEEPSLATE_BRICK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP)
        private val DEEPSLATE_TILES: BlockState = Blocks.DEEPSLATE_TILES.defaultBlockState()
        private val DEEPSLATE_TILE_SLAB_BOTTOM: BlockState =
            Blocks.DEEPSLATE_TILE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
        private val DEEPSLATE_TILE_SLAB_TOP: BlockState =
            Blocks.DEEPSLATE_TILE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP)
        // Inverted (top-half) deepslate-tile stairs — sit one Y below the
        // lintel, FACING points toward the arch's column.
        private val DEEPSLATE_TILE_STAIRS_TOP_NORTH: BlockState =
            Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.TOP)
        private val DEEPSLATE_TILE_STAIRS_TOP_SOUTH: BlockState =
            Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.SOUTH)
                .setValue(StairBlock.HALF, Half.TOP)
        private val DEEPSLATE_TILE_STAIRS_TOP_EAST: BlockState =
            Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)
                .setValue(StairBlock.HALF, Half.TOP)
        private val DEEPSLATE_TILE_STAIRS_TOP_WEST: BlockState =
            Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.HALF, Half.TOP)
        // Regular (bottom-half) deepslate-tile stairs — sit on the lintel
        // row, FACING points toward the arch's column.
        private val DEEPSLATE_TILE_STAIRS_BOTTOM_NORTH: BlockState =
            Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        private val DEEPSLATE_TILE_STAIRS_BOTTOM_SOUTH: BlockState =
            Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.SOUTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        private val DEEPSLATE_TILE_STAIRS_BOTTOM_EAST: BlockState =
            Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        private val DEEPSLATE_TILE_STAIRS_BOTTOM_WEST: BlockState =
            Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        private val CHISELED_DEEPSLATE: BlockState = Blocks.CHISELED_DEEPSLATE.defaultBlockState()
        private val POLISHED_DEEPSLATE: BlockState = Blocks.POLISHED_DEEPSLATE.defaultBlockState()
        /** Reserved — kept for compatibility but no longer placed; backing
         *  walls use [DEEPSLATE_BRICKS] now. */
        @Suppress("unused")
        private val DEEPSLATE: BlockState = Blocks.DEEPSLATE.defaultBlockState()
        private val CHAIN: BlockState = Blocks.CHAIN.defaultBlockState()
        private val YELLOW_CARPET: BlockState = Blocks.YELLOW_CARPET.defaultBlockState()
        private val WHITE_CARPET: BlockState = Blocks.WHITE_CARPET.defaultBlockState()
        // Sselith-tinted lectern (subclasses vanilla LecternBlock, shares the
        // LecternBlockEntity). Drop-in replacement: every existing LECTERN_*
        // facing constant + cachedLectern() path picks this up automatically.
        private val LECTERN_DEFAULT: BlockState =
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_LECTERN.get().defaultBlockState()
        private val LIT_CANDLE: BlockState = Blocks.CANDLE.defaultBlockState()
            .setValue(CandleBlock.LIT, true)
        private val DEEPSLATE_BRICK_STAIRS_BASE: BlockState =
            Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState()
                .setValue(StairBlock.HALF, Half.BOTTOM)
        // Cell-wall-top inward-facing battlement caps (half = bottom so
        // the solid material sits on top of the wall and the step rises
        // from the cell-interior side, reading as a step you could climb
        // up onto from inside the cell). Painted by
        // [paintCellTopBattlementInto] at the topmost-cellY's top row
        // only — every cell below the world's topmost layer still gets
        // its original BOOKSHELF/DEEPSLATE_BRICKS perimeter.
        private val DEEPSLATE_BRICK_STAIRS_BOTTOM_NORTH: BlockState =
            Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        private val DEEPSLATE_BRICK_STAIRS_BOTTOM_SOUTH: BlockState =
            Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.SOUTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        private val DEEPSLATE_BRICK_STAIRS_BOTTOM_EAST: BlockState =
            Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        private val DEEPSLATE_BRICK_STAIRS_BOTTOM_WEST: BlockState =
            Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        // Polished-deepslate corner stairs forming the "circle" around
        // each corner post one block above the cell ceiling. half =
        // bottom so the stair sits directly on the DEEPSLATE_TILES post
        // below; shape = OUTER_LEFT / OUTER_RIGHT so the stair's solid
        // corner extends toward the corner post diagonally.
        private val POLISHED_DEEPSLATE_STAIRS_BOTTOM_NORTH_OUTER_LEFT: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.OUTER_LEFT)
        private val POLISHED_DEEPSLATE_STAIRS_BOTTOM_NORTH_OUTER_RIGHT: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.OUTER_RIGHT)
        private val POLISHED_DEEPSLATE_STAIRS_BOTTOM_SOUTH_OUTER_LEFT: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.SOUTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.OUTER_LEFT)
        private val POLISHED_DEEPSLATE_STAIRS_BOTTOM_SOUTH_OUTER_RIGHT: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.SOUTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.OUTER_RIGHT)
        private val POLISHED_DEEPSLATE_STAIRS_BOTTOM_EAST_OUTER_LEFT: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.OUTER_LEFT)
        private val POLISHED_DEEPSLATE_STAIRS_BOTTOM_EAST_OUTER_RIGHT: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.OUTER_RIGHT)
        private val POLISHED_DEEPSLATE_STAIRS_BOTTOM_WEST_OUTER_LEFT: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.OUTER_LEFT)
        private val POLISHED_DEEPSLATE_STAIRS_BOTTOM_WEST_OUTER_RIGHT: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.OUTER_RIGHT)
        private val LADDER_FACINGS: Array<Direction> = arrayOf(
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
        )
        // Sselith ladders — deepslate-textured, survive without a backer block (see
        // [org.shipwrights.enderkinesis.block.SselithLadderBlock]). `by lazy` defers the
        // RegistrySupplier.get() call until first use so this companion object doesn't
        // crash if classloaded before the block registry has flushed its deferred entries.
        private val LADDER_NORTH: BlockState by lazy {
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_LADDER.get().defaultBlockState()
                .setValue(LadderBlock.FACING, Direction.NORTH)
        }
        private val LADDER_SOUTH: BlockState by lazy {
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_LADDER.get().defaultBlockState()
                .setValue(LadderBlock.FACING, Direction.SOUTH)
        }
        private val LADDER_EAST: BlockState by lazy {
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_LADDER.get().defaultBlockState()
                .setValue(LadderBlock.FACING, Direction.EAST)
        }
        private val LADDER_WEST: BlockState by lazy {
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_LADDER.get().defaultBlockState()
                .setValue(LadderBlock.FACING, Direction.WEST)
        }


        private val POLISHED_DEEPSLATE_STAIRS_NORTH: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        private val POLISHED_DEEPSLATE_STAIRS_SOUTH: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.SOUTH)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        private val POLISHED_DEEPSLATE_STAIRS_EAST: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        private val POLISHED_DEEPSLATE_STAIRS_WEST: BlockState =
            Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
        fun polishedStair(facing: Direction): BlockState = when (facing) {
            Direction.NORTH -> POLISHED_DEEPSLATE_STAIRS_NORTH
            Direction.SOUTH -> POLISHED_DEEPSLATE_STAIRS_SOUTH
            Direction.EAST -> POLISHED_DEEPSLATE_STAIRS_EAST
            Direction.WEST -> POLISHED_DEEPSLATE_STAIRS_WEST
            else -> POLISHED_DEEPSLATE_STAIRS_NORTH
        }

        // Sselith Trapdoor — deepslate-textured variant, structurally the
        // same as vanilla wooden trapdoors. Replaces every BAMBOO_TRAPDOOR
        // placement the procedural platform / connection code emits. `by
        // lazy` defers the RegistrySupplier.get() call until first use so
        // this companion object doesn't crash if classloaded before the
        // block registry has flushed its deferred registrations.
        private val BAMBOO_TRAPDOOR_NORTH: BlockState by lazy {
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_TRAPDOOR.get().defaultBlockState()
                .setValue(TrapDoorBlock.FACING, Direction.NORTH)
                .setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.OPEN, false)
        }
        private val BAMBOO_TRAPDOOR_SOUTH: BlockState by lazy {
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_TRAPDOOR.get().defaultBlockState()
                .setValue(TrapDoorBlock.FACING, Direction.SOUTH)
                .setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.OPEN, false)
        }
        private val BAMBOO_TRAPDOOR_EAST: BlockState by lazy {
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_TRAPDOOR.get().defaultBlockState()
                .setValue(TrapDoorBlock.FACING, Direction.EAST)
                .setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.OPEN, false)
        }
        private val BAMBOO_TRAPDOOR_WEST: BlockState by lazy {
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_TRAPDOOR.get().defaultBlockState()
                .setValue(TrapDoorBlock.FACING, Direction.WEST)
                .setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.OPEN, false)
        }
        fun cachedTrapdoor(facing: Direction): BlockState = when (facing) {
            Direction.NORTH -> BAMBOO_TRAPDOOR_NORTH
            Direction.SOUTH -> BAMBOO_TRAPDOOR_SOUTH
            Direction.EAST -> BAMBOO_TRAPDOOR_EAST
            Direction.WEST -> BAMBOO_TRAPDOOR_WEST
            else -> BAMBOO_TRAPDOOR_NORTH
        }

        private val LECTERN_NORTH: BlockState =
            LECTERN_DEFAULT.setValue(LecternBlock.FACING, Direction.NORTH)
        private val LECTERN_SOUTH: BlockState =
            LECTERN_DEFAULT.setValue(LecternBlock.FACING, Direction.SOUTH)
        private val LECTERN_EAST: BlockState =
            LECTERN_DEFAULT.setValue(LecternBlock.FACING, Direction.EAST)
        private val LECTERN_WEST: BlockState =
            LECTERN_DEFAULT.setValue(LecternBlock.FACING, Direction.WEST)
        fun cachedLectern(facing: Direction): BlockState = when (facing) {
            Direction.NORTH -> LECTERN_NORTH
            Direction.SOUTH -> LECTERN_SOUTH
            Direction.EAST -> LECTERN_EAST
            Direction.WEST -> LECTERN_WEST
            else -> LECTERN_NORTH
        }

        private val BAMBOO_WALL_SIGN_NORTH: BlockState =
            Blocks.BAMBOO_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, Direction.NORTH)
        private val BAMBOO_WALL_SIGN_SOUTH: BlockState =
            Blocks.BAMBOO_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, Direction.SOUTH)
        private val BAMBOO_WALL_SIGN_EAST: BlockState =
            Blocks.BAMBOO_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, Direction.EAST)
        private val BAMBOO_WALL_SIGN_WEST: BlockState =
            Blocks.BAMBOO_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, Direction.WEST)
        fun cachedBambooWallSign(facing: Direction): BlockState = when (facing) {
            Direction.NORTH -> BAMBOO_WALL_SIGN_NORTH
            Direction.SOUTH -> BAMBOO_WALL_SIGN_SOUTH
            Direction.EAST -> BAMBOO_WALL_SIGN_EAST
            Direction.WEST -> BAMBOO_WALL_SIGN_WEST
            else -> BAMBOO_WALL_SIGN_NORTH
        }

        /** Cardinal offsets used by [chiseledPostSignAt] to scan the 4
         *  neighbours of a corridor-air position for an adjacent post. */
        private val CARDINAL_DX: IntArray = intArrayOf( 1, -1,  0,  0)
        private val CARDINAL_DZ: IntArray = intArrayOf( 0,  0,  1, -1)
        /** Sign FACING for each cardinal direction in [CARDINAL_DX]/[CARDINAL_DZ]
         *  — the sign's face points AWAY from the supporting post, so the
         *  facing is the opposite of the (dx, dz) direction toward the post. */
        private val CARDINAL_SIGN_FACING: Array<Direction> = arrayOf(
            Direction.WEST,   // post is east of sign → sign faces west
            Direction.EAST,   // post west of sign
            Direction.NORTH,  // post south of sign
            Direction.SOUTH,  // post north of sign
        )
        /** Per-post chance (0-100) that a chiseled wall post hangs a bamboo
         *  sign on its interior face. */
        private const val CHISELED_POST_SIGN_PERCENT = 45

        /** Lowest cellY that maps to a named floor; lower cells clamp to this. */
        private const val MIN_NAMED_CELL_Y = -4
        /** Highest cellY that maps to a named floor; higher cells clamp to this. */
        private const val MAX_NAMED_CELL_Y = 4
        /** Floor names indexed from top (floor 1) down to bottom (floor 9).
         *  cellY=4 → "Spines" (top), cellY=0 → "Atrium" (middle),
         *  cellY=-4 → "Frontispiece" (bottom). Index = MAX_NAMED_CELL_Y - cellY. */
        private val FLOOR_NAMES: Array<String> = arrayOf(
            "Spines",        // floor 1 (cellY=4, top)
            "Concordance",   // floor 2
            "Annotation",    // floor 3
            "Recitation",    // floor 4
            "Atrium",        // floor 5 (middle)
            "Apse",          // floor 6
            "Reliquary",     // floor 7
            "Forthcoming",   // floor 8
            "Frontispiece",  // floor 9 (cellY=-4, bottom)
        )

        /** Resource path for the Sselith dictionary JSON. Loaded once at
         *  first use to build [SSELITH_WORDS]. */
        private const val SSELITH_DICTIONARY_PATH =
            "assets/enderkinesis/texts/sselith/dictionary/sselith_dictionary.json"
        /** Flat list of Sselith words — every `sselith` value in the
         *  `lexicon` object plus every entry in `properNouns`. Used by the
         *  bamboo wall sign line 2. Falls back to empty if the resource
         *  can't be read; the sign just gets a blank second line in that
         *  case. */
        private val SSELITH_WORDS: List<String> by lazy { loadSselithWords() }

        private fun loadSselithWords(): List<String> {
            val stream = SselithRepertoryChunkGenerator::class.java.classLoader
                .getResourceAsStream(SSELITH_DICTIONARY_PATH) ?: return emptyList()
            val root: JsonObject = stream.use {
                JsonParser.parseReader(it.bufferedReader()).asJsonObject
            }
            val words = ArrayList<String>()
            root.getAsJsonObject("lexicon")?.entrySet()?.forEach { (_, value) ->
                val word = value.asJsonObject.get("sselith")?.asString
                if (!word.isNullOrEmpty()) words.add(word)
            }
            root.getAsJsonArray("properNouns")?.forEach { el ->
                val word = el.asString
                if (!word.isNullOrEmpty()) words.add(word)
            }
            return words
        }

        // 6-bit key for [WALL_STATES_BY_KEY] — encodes (east, west, north,
        // south, up, hasBlockAbove) booleans. Bit layout chosen so that
        // makeWallState can OR up the key in one expression.
        const val WALL_KEY_EAST = 1
        const val WALL_KEY_WEST = 2
        const val WALL_KEY_NORTH = 4
        const val WALL_KEY_SOUTH = 8
        const val WALL_KEY_UP = 16
        const val WALL_KEY_ABOVE = 32
        private val WALL_STATES_BY_KEY: Array<BlockState> = Array(64) { key ->
            val east = (key and WALL_KEY_EAST) != 0
            val west = (key and WALL_KEY_WEST) != 0
            val north = (key and WALL_KEY_NORTH) != 0
            val south = (key and WALL_KEY_SOUTH) != 0
            val up = (key and WALL_KEY_UP) != 0
            // Side height is always LOW: the only "above" block we ever place
            // on a regular wall is a standing lantern (chiseled posts replace
            // the wall outright), and a lantern's small footprint doesn't
            // cover the wall's 1/3-above-center region, so vanilla wall rules
            // keep the sides LOW even when UP=true.
            Blocks.DEEPSLATE_BRICK_WALL.defaultBlockState()
                .setValue(WallBlock.EAST_WALL,  if (east)  WallSide.LOW else WallSide.NONE)
                .setValue(WallBlock.WEST_WALL,  if (west)  WallSide.LOW else WallSide.NONE)
                .setValue(WallBlock.NORTH_WALL, if (north) WallSide.LOW else WallSide.NONE)
                .setValue(WallBlock.SOUTH_WALL, if (south) WallSide.LOW else WallSide.NONE)
                .setValue(WallBlock.UP, up)
        }

        private const val FRAME_SIZE = 5
        private const val CELL_SIZE = 7
        private const val OFFSET_RANGE = 2
        /** Cube-exclusion mask extends `CUBE_EXCL_MARGIN` blocks past each
         *  side of the chunk so it can cover every position a cube touched by
         *  this chunk can occupy (cube reaches FRAME_SIZE-1 = 4 blocks past
         *  the chunk's column on either side; +1 for bbox padding). */
        private const val CUBE_EXCL_MARGIN = FRAME_SIZE - 1 + 1  // 5
        private const val CUBE_EXCL_SIZE = 16 + 2 * CUBE_EXCL_MARGIN  // 26

        private const val PATH_Y = 0
        /** Axial path is 3 blocks wide centred on the axis. */
        private val PATH_RANGE: IntRange = -1..1

        // Walls 2 tall (path Y, path Y+1) emerge from the outline of pass 1's
        // path layer. Chiseled posts extend one block higher to make 3 tall,
        // and an optional lantern hangs over non-chiseled wall tops.
        /** Roughly one wall-top decoration per N wall columns, hash-spaced.
         *  Decoration is either a lantern or a 1..4 lit yellow candle cluster,
         *  picked via [WALL_TOP_LANTERN_PERCENT]. */
        private const val LANTERN_INTERVAL = 11
        /** Of all [wallTopDecorPresent] columns, fraction that get a hanging
         *  lantern (vs. a 1..4 lit yellow candle cluster on the wall's
         *  centre post). Matches the SHELF_LANTERN_PERCENT split used by
         *  [shelfTopDecoration]. */
        private const val WALL_TOP_LANTERN_PERCENT = 40
        /** Percent of wall columns swapped for a 3-tall chiseled post. */
        private const val CHISELED_WALL_PERCENT = 4
        /** Spacing of the chiseled-deepslate marker block in the middle of the
         *  axial path (Y=0, centre column). */
        private const val AXIAL_PATH_CHISEL_INTERVAL = 16
        /** Maze corridor width range (inclusive). Each connection picks a width
         *  here deterministically from the hash of its endpoints. */
        private const val MIN_CORRIDOR_WIDTH = 1
        private const val MAX_CORRIDOR_WIDTH = 3

        /** Every Sselith lantern placement (arm-undersides, totem caps,
         *  wall-top decoration, shelf-top decoration) feeds through this
         *  one constant; switching it to the dimension-specific block
         *  swaps every gen-time vanilla lantern out at once. Lazy because
         *  [EKBlocks.SSELITH_LANTERN] is populated by mod init, which
         *  runs after this companion's eager-init pass. */
        private val LANTERN: BlockState by lazy {
            org.shipwrights.enderkinesis.registry.EKBlocks.SSELITH_LANTERN.get().defaultBlockState()
        }

        private const val MIN_Y = -128
        private const val WORLD_HEIGHT = 256

        private const val FLOOR_SIZE = 5
        private const val STAIR_DROP = 5
        // Odd horizontal cell extent so the cell has a single centre column
        // matching the (odd-sized) 5×5 platform. With width 49 the centre
        // column is local x=24, and the platform spans local x=22..26.
        private const val MAZE_CELL_X = 49
        private const val MAZE_CELL_Y = 24
        private const val MAZE_CELL_Z = 49

        /** Candidate tile offsets (Δx, Δz) from the platform centre for cataloger
         *  spawning. The eight cells immediately adjacent to centre — four
         *  cardinals + four diagonals — staying within the 5×5 platform but
         *  avoiding the centre (ladder column) and the corners (often stair
         *  landings / wall posts). `pickCatalogerSpawnTile` iterates these in
         *  a per-cell-deterministic order and picks the first one whose foot
         *  and head blocks are air. */
        private val CATALOGER_SPAWN_OFFSETS: Array<Pair<Int, Int>> = arrayOf(
            1 to 0, -1 to 0, 0 to 1, 0 to -1,
            1 to 1, -1 to 1, 1 to -1, -1 to -1,
        )

        /** Topmost vertical cell index. Cells at this Y are skipped so the
         *  chain pillars from the cell below lift into open sky with no
         *  capping floor / froglight above them. */
        private val TOP_CELL_Y = Math.floorDiv(MIN_Y + WORLD_HEIGHT - 1, MAZE_CELL_Y)

        /** Max `|Δy|` a connection can resolve with a stair flight before
         *  falling back to a ladder. Set to [MAZE_CELL_Y] so a diagonal
         *  cell-to-cell connection (cellY±1, cellX±1 or cellZ±1) becomes a
         *  stairwell instead of a ladder. */
        private const val STAIR_THRESHOLD = MAZE_CELL_Y
        @Suppress("unused")
        private const val AXIS_BRANCH_RANGE = 48
        @Suppress("unused")
        private const val MAZE_DENSITY_PERCENT = 100
        /** Max vertical span of a connecting ladder. */
        private const val MAX_CONNECT_Y_DIFF = MAZE_CELL_Y

        /** Horizontal offset for the platform grid. Chosen so the central
         *  axial strip (x/z ∈ [-1, 1]) is **equidistant** from the platforms
         *  on either side. With cell stride 48 and floor span 5:
         *    - cellX=0  → platform at X=22..26 (20-block gap east of strip)
         *    - cellX=-1 → platform at X=-26..-22 (20-block gap west of strip)
         *  Solved from `GRID_OFFSET - 2 = 42 - GRID_OFFSET`. */
        private const val GRID_OFFSET = 22
        /** Per-cell chance (0–100) of actually placing a platform. Cells whose
         *  hash exceeds this are intentionally empty. */
        private const val PLATFORM_PRESENCE_PERCENT = 80

        /** One of 14 directional candidates. Every platform attempts ALL of
         *  these as outgoing connections; the connection type is derived
         *  from the vector (purely horizontal → pathway, purely vertical →
         *  ladder, mixed → stairwell). Stairwells whose bbox overlaps a
         *  pathway or a higher-ranked stairwell are still culled by
         *  [stairwellAllowed]. */
        private data class ConnVec(val dx: Int, val dy: Int, val dz: Int)

        private val CONN_VECTORS: Array<ConnVec> = arrayOf(
            // 4 horizontal pathways
            ConnVec( 1, 0,  0), ConnVec(-1, 0,  0),
            ConnVec( 0, 0,  1), ConnVec( 0, 0, -1),
            // 2 vertical ladders
            ConnVec( 0,  1, 0), ConnVec( 0, -1, 0),
            // 8 vertical-diagonal stairwells
            ConnVec( 1,  1, 0), ConnVec( 1, -1, 0),
            ConnVec(-1,  1, 0), ConnVec(-1, -1, 0),
            ConnVec( 0,  1,  1), ConnVec( 0, -1,  1),
            ConnVec( 0,  1, -1), ConnVec( 0, -1, -1),
        )

        /** Precomputed `isPathwayVec(vIdx)` / `isStairwellVec(vIdx)`
         *  results, indexed by vIdx. Both predicates are called from
         *  `stairwellAllowed`'s 125-cell × ~16-picks-per-cell scan,
         *  which (after the ±2 widening for stairwell intersection
         *  correctness) is the single hottest chunk-gen path. Replacing
         *  the array-dereference + 3-getter-call + boolean-op chain
         *  with a single BooleanArray read removes the runtime cost. */
        private val IS_PATHWAY_VEC: BooleanArray = BooleanArray(CONN_VECTORS.size) { i ->
            val v = CONN_VECTORS[i]
            v.dy == 0 && (v.dx != 0 || v.dz != 0)
        }
        private val IS_STAIRWELL_VEC: BooleanArray = BooleanArray(CONN_VECTORS.size) { i ->
            val v = CONN_VECTORS[i]
            v.dy != 0 && (v.dx != 0 || v.dz != 0)
        }

        /** Cardinal-pathway indices into [CONN_VECTORS] (the first four). */
        private const val PATHWAY_EAST = 0
        private const val PATHWAY_WEST = 1
        private const val PATHWAY_SOUTH = 2
        private const val PATHWAY_NORTH = 3

        /** Vertical-ladder indices into [CONN_VECTORS]. */
        private const val LADDER_UP_VIDX = 4
        private const val LADDER_DOWN_VIDX = 5

        /** Diagonal-stairwell indices into [CONN_VECTORS]. The 8 stairwells
         *  occupy `[STAIRWELL_VIDX_BASE, STAIRWELL_VIDX_BASE + STAIRWELL_COUNT)`. */
        private const val STAIRWELL_VIDX_BASE = 6
        private const val STAIRWELL_COUNT = 8

        /** Number of diagonal stairwells each cell proposes. Every cell
         *  now tables all eight stairwell directions; the per-cell
         *  picks share the source cell's bbox (handled by the same-cell
         *  skip in [computeStairwellAllowed]), and the cross-cell
         *  conflicts cull the truly-overlapping ones. With anything
         *  less, half the cells in any given column ended up with zero
         *  surviving stairwell after the rank filter — every smaller
         *  candidate set left too many cells starved. */
        private const val STAIRWELL_PICKS_PER_CELL: Int = STAIRWELL_COUNT
        private val STAIRWELL_PICK_OFFSETS: IntArray =
            IntArray(STAIRWELL_COUNT) { it }

        /** Precomputed pick list for each `(preferredStairwellOffset,
         *  pathwaySkipMask)` key, indexed as `preferred * 16 + skipMask`.
         *  Includes [STAIRWELL_PICKS_PER_CELL] diagonal stairwells (the
         *  preferred one plus the offsets in [STAIRWELL_PICK_OFFSETS]);
         *  vertical ladders are NOT included — the maze's only vertical
         *  connectivity is stairwells now. Cardinal pathways are only
         *  included where the matching skip-mask bit is unset. */
        private val PICKS_BY_KEY: Array<IntArray> = Array(STAIRWELL_COUNT * 16) { keyIdx ->
            val preferred = STAIRWELL_VIDX_BASE + (keyIdx / 16)
            val skipMask = keyIdx and 15
            val out = ArrayList<Int>(STAIRWELL_PICKS_PER_CELL + 4)
            for (offset in STAIRWELL_PICK_OFFSETS) {
                val sIdx = STAIRWELL_VIDX_BASE +
                    Math.floorMod((preferred - STAIRWELL_VIDX_BASE) + offset, STAIRWELL_COUNT)
                if (!out.contains(sIdx)) out.add(sIdx)
            }
            if ((skipMask and 1) == 0) out.add(PATHWAY_EAST)
            if ((skipMask and 2) == 0) out.add(PATHWAY_WEST)
            if ((skipMask and 4) == 0) out.add(PATHWAY_SOUTH)
            if ((skipMask and 8) == 0) out.add(PATHWAY_NORTH)
            out.toIntArray()
        }

        //
        // A "structure replacement" is an NBT structure that takes the place of the
        // standard 5×5 platform on rare eligible cells. Every replacement shares
        // the same spawn condition (`SSELITH_GLOBE_PERCENT`% per-cell roll + no
        // upward connection); a secondary hash picks WHICH type goes in the cell.
        // The painting + footprint + connection-paint machinery is type-aware via
        // [StructureReplacement.dimX/dimY/dimZ] and [cornerOffsetX/Z], so adding a
        // new replacement is just appending an enum entry and dropping the NBT
        // under `data/enderkinesis/structures/`.

        /** Per-cell rarity gate. 18 ≈ 18% of eligible nodes pick a
         *  replacement (across all types combined). */
        /** Per-(cellX, cellZ) probability that the column hosts a
         *  244-tall observatory. Low so each spawn is a landmark. */
        private const val OBSERVATORY_PERCENT: Int = 1
        private const val OBSERVATORY_HASH_SALT: Int = 0x3E91BAD

        private const val SSELITH_GLOBE_PERCENT: Int = 18

        /** Hash salt distinguishing the rare-replacement roll from every
         *  other per-cell decision driven through [mazeHash]. */
        private const val SSELITH_GLOBE_HASH_SALT: Int = 0x6CBA127

        /** Hash salt for picking WHICH replacement type a qualifying cell
         *  gets. Distinct from the gate salt so the type distribution is
         *  independent of the gate roll. */
        private const val REPLACEMENT_TYPE_HASH_SALT: Int = 0x4BCDA22

        /** Catalogue of NBT structures that can replace the standard 5×5
         *  platform on rare cells. Each entry declares its NBT resource
         *  path and its `(x, y, z)` dimensions so footprint queries,
         *  connection carving, and the paint loop can treat every type
         *  uniformly. Cornering ([cornerOffsetX] / [cornerOffsetZ])
         *  centres the structure on the platform's 5-wide central column;
         *  even-dimension structures bias to the lower coord. */
        private enum class StructureReplacement(
            val resourcePath: String,
            val dimX: Int, val dimY: Int, val dimZ: Int,
            /** Vertical shift from `node.ny` to the structure's `y=0` corner.
             *  Default 0 means the NBT's `y=0` layer lands at platform-floor
             *  level — the walking surface ends up at `node.ny + 1` (top of
             *  the y=0 row), matching the standard 5×5 platform.
             *
             *  Negative values sink the structure: e.g. for the viewing
             *  platform the NBT's `y=0` is a sunken-pit layer (lecterns +
             *  central pedestal base) and the actual 15×15 walking floor is
             *  the `y=1` layer; offsetting by `-1` lines that walking floor
             *  up with the standard platform-floor level. */
            val cornerOffsetY: Int = 0,
            /** Rare placements roll against [SSELITH_GLOBE_PERCENT] per
             *  eligible cell; normal placements fill EVERY other eligible
             *  cell (replacing the hardcoded 5×5 floor paint). The 7×7
             *  PLATFORM_n NBTs are the everyday rooms; the wider ones are
             *  rare set-pieces. */
            val isRare: Boolean = true,
        ) {
            GLOBE("sselith_globe.nbt", 11, 11, 11),
            GALLERY("sselith_gallery.nbt", 11, 6, 11),
            MOSAIC_1("sselith_mosaic_1.nbt", 9, 3, 9),
            SHELVES("sselith_shelves.nbt", 9, 6, 9),
            ENCHANT("sselith_enchant.nbt", 9, 7, 9),
            SPECIMEN_SCULK("sselith_specimen_sculk.nbt", 9, 11, 9),
            VIEWING_PLATFORM("sselith_viewing_platform.nbt", 15, 9, 15, cornerOffsetY = -1),
            /** Column-spanning observatory: 7×244×7. cornerOffsetY=0 so
             *  the NBT's `y=0` layer lands at the column's BOTTOM
             *  PLATFORM — i.e. node.ny of the lowest cellY in the
             *  column with a maze node (see [observatoryBottomCellY]).
             *  In this dimension the lowest valid cellY is around −5
             *  (cellY * 24 ≥ MIN_Y + STAIR_DROP + 2 = −121), so the
             *  observatory base sits roughly at world Y=−120 in
             *  well-populated columns. The top extends well past the
             *  regular cell ceiling; positions above world-max clip
             *  naturally at paint time. */
            OBSERVATORY("sselith_observatory.nbt", 7, 244, 7),
            PLATFORM_1("sselith_platform_1.nbt", 7, 7, 7, isRare = false),
            PLATFORM_2("sselith_platform_2.nbt", 7, 5, 7, isRare = false),
            PLATFORM_3("sselith_platform_3.nbt", 7, 5, 7, isRare = false),
            PLATFORM_4("sselith_platform_4.nbt", 7, 2, 7, isRare = false),
            PLATFORM_5("sselith_platform_5.nbt", 7, 5, 7, isRare = false),
            PLATFORM_6("sselith_platform_6.nbt", 7, 6, 7, isRare = false),
            PLATFORM_7("sselith_platform_7.nbt", 7, 5, 7, isRare = false),
            PLATFORM_8("sselith_platform_8.nbt", 7, 6, 7, isRare = false),
            PLATFORM_10("sselith_platform_10.nbt", 7, 4, 7, isRare = false),
            ;

            val fullResourcePath: String = "data/enderkinesis/structures/$resourcePath"
            /** X offset from the cell's `node.nx` to the structure's `(0,0,0)`
             *  corner, chosen so the structure centres on the 5×5 platform.
             *  For an 11-wide structure: `-(11-5)/2 = -3`; for 9-wide: `-2`;
             *  for the 7-wide normal platforms: `-1`. */
            val cornerOffsetX: Int = -(dimX - FLOOR_SIZE) / 2
            val cornerOffsetZ: Int = -(dimZ - FLOOR_SIZE) / 2
        }

        /** Sliced views of [StructureReplacement.entries] for the two-tier
         *  picker — built once at class load so [replacementTypeAt] doesn't
         *  re-filter the full set per cell. */
        private val RARE_REPLACEMENTS: List<StructureReplacement> =
            // OBSERVATORY is column-scoped (see [hasObservatoryColumnAt])
            // — it's never picked by the per-cell rare roll. Without
            // this filter the rare picker could land on OBSERVATORY at
            // any random cell, anchoring a 244-tall tower wherever
            // that cell happened to be in the stack.
            StructureReplacement.entries.filter {
                it.isRare && it != StructureReplacement.OBSERVATORY
            }
        private val NORMAL_REPLACEMENTS: List<StructureReplacement> =
            StructureReplacement.entries.filter { !it.isRare }
        /** Normal platforms with no roof — safe to place on a cell that
         *  has an outgoing upward connection (stair / ladder). Authors
         *  who want a platform to be stair-compatible should keep dimY ≤
         *  2 (floor row + a single wall row, no ceiling layer). */
        private val SHORT_PLATFORMS: List<StructureReplacement> =
            NORMAL_REPLACEMENTS.filter { it.dimY <= 2 }

        /** Flat 43×N×43 decorative overlay painted *under* the per-cell
         *  platform. The NBTs are mostly concentric-slab rings with an
         *  open centre where the platform sits, so the ring frames the
         *  platform without occluding it. AIR cells in these NBTs are
         *  treated as "skip" at load time (see [loadDecorationBlocks])
         *  — without that, the ring's air interior would clobber the
         *  surrounding maze paint that the chunkgen otherwise leaves
         *  for [LIBRARY_CELL] fill. */
        private enum class RingDecoration(
            val resourcePath: String,
            val dimX: Int, val dimY: Int, val dimZ: Int,
        ) {
            RING_1("sselith_rings_1.nbt", 43, 1, 43),
            RING_2("sselith_rings_2.nbt", 43, 1, 43),
            RING_3("sselith_rings_3.nbt", 43, 1, 43),
            RING_4("sselith_rings_4.nbt", 43, 2, 43),
            RING_5("sselith_rings_5.nbt", 43, 2, 43),
            RING_6("sselith_rings_6.nbt", 43, 2, 43),
            RING_7("sselith_rings_7.nbt", 43, 2, 43),
            RING_8("sselith_rings_8.nbt", 43, 2, 43),
            ;

            val fullResourcePath: String = "data/enderkinesis/structures/$resourcePath"
            /** Centre the ring on the standard 5×5 platform: `-(43-5)/2 = -19`. */
            val cornerOffsetX: Int = -(dimX - FLOOR_SIZE) / 2
            val cornerOffsetZ: Int = -(dimZ - FLOOR_SIZE) / 2
        }

        private const val RING_TYPE_HASH_SALT: Int = 0x7AE2C9D

        /** Picker bound: NBT ring count + 1 procedural sentinel. The
         *  sentinel index returns null from [ringAt], which means
         *  "let the [LIBRARY_CELL] y=0 concentric-slab + corner
         *  froglight pattern fire unsuppressed for this cell." */
        private val RING_STYLE_COUNT: Int = RingDecoration.entries.size + 1

        /** Authored path-corridor decoration segments. Each NBT is a
         *  [PATH_SEGMENT_TILE_LENGTH]-long chunk along its Z axis;
         *  width along X is 3 (narrow) or 5 (wide). The overlay tiles
         *  these end-to-end along every straight horizontal pathway,
         *  rotating to the corridor's axis. AIR cells in the NBT are
         *  stripped at load time so the overlay never erases procedural
         *  corridor content where the author left it bare. */
        private enum class PathSegment(
            val resourcePath: String,
            val dimX: Int, val dimY: Int, val dimZ: Int,
            /** True for the `path_wide_*` set — 5-block-wide NBT where
             *  the walkable floor spans the full 3-wide corridor strip
             *  and the walls flank one block outside it. False for the
             *  `path_*` set — 3-block-wide effective footprint where
             *  walkable space is only the centre column and the walls
             *  sit inside the corridor strip. */
            val isWide: Boolean,
        ) {
            PATH_1("sselith_path_1.nbt", 3, 2, 4, isWide = false),
            PATH_2("sselith_path_2.nbt", 3, 6, 4, isWide = false),
            PATH_3("sselith_path_3.nbt", 5, 5, 4, isWide = false),
            PATH_4("sselith_path_4.nbt", 5, 5, 4, isWide = false),
            PATH_5("sselith_path_5.nbt", 5, 5, 4, isWide = false),
            PATH_6("sselith_path_6.nbt", 5, 5, 4, isWide = false),
            PATH_WIDE_1("sselith_path_wide_1.nbt", 5, 2, 4, isWide = true),
            PATH_WIDE_2("sselith_path_wide_2.nbt", 5, 5, 4, isWide = true),
            PATH_WIDE_3("sselith_path_wide_3.nbt", 5, 5, 4, isWide = true),
            PATH_WIDE_4("sselith_path_wide_4.nbt", 5, 5, 4, isWide = true),
            PATH_WIDE_5("sselith_path_wide_5.nbt", 5, 5, 4, isWide = true),
            PATH_WIDE_6("sselith_path_wide_6.nbt", 5, 5, 4, isWide = true),
            PATH_WIDE_7("sselith_path_wide_7.nbt", 5, 5, 4, isWide = true),
            PATH_WIDE_8("sselith_path_wide_8.nbt", 5, 5, 4, isWide = true),
            PATH_WIDE_9("sselith_path_wide_9.nbt", 5, 5, 4, isWide = true),
            PATH_WIDE_10("sselith_path_wide_10.nbt", 5, 5, 4, isWide = true),
            ;
            val fullResourcePath: String = "data/enderkinesis/structures/$resourcePath"
        }

        private const val PATH_SEGMENT_HASH_SALT: Int = 0x3D2A14F
        private const val PATH_WIDTH_HASH_SALT: Int = 0x5B91E37
        private const val PATH_SALT_HASH_SALT: Int = 0x2C7D419
        private const val PATH_SEGMENT_TILE_LENGTH: Int = 4
        /** Fraction of straight horizontal corridors that receive any
         *  salt at all (0..100). Set low so the procedural look stays
         *  dominant — salting accents a few stretches, doesn't replace
         *  the corridor system. */
        private const val PATH_SALT_PERCENT: Int = 25

        /** Sliced views of [PathSegment.entries] for the two-tier picker —
         *  the corridor first rolls wide vs narrow, then picks one
         *  segment from the chosen pool per tile. */
        private val PATH_SEGMENTS_WIDE: List<PathSegment> =
            PathSegment.entries.filter { it.isWide }
        private val PATH_SEGMENTS_NARROW: List<PathSegment> =
            PathSegment.entries.filter { !it.isWide }

        private val PATH_BLOCKS: Map<PathSegment, Array<Array<Array<BlockState?>>>?> by lazy {
            PathSegment.entries.associateWith { type ->
                loadDecorationBlocks(type.fullResourcePath, type.dimX, type.dimY, type.dimZ)
            }
        }

        private val RING_BLOCKS: Map<RingDecoration, Array<Array<Array<BlockState?>>>?> by lazy {
            RingDecoration.entries.associateWith { type ->
                loadDecorationBlocks(type.fullResourcePath, type.dimX, type.dimY, type.dimZ)
            }
        }

        /** Variant of [loadReplacementBlocks] that skips AIR palette entries.
         *  Rings are overlays, not replacements — leaving the air cells null
         *  means [paintRingDecorationInto]'s `?: continue` keeps the
         *  underlying paint (path, structure, decor) intact at the ring's
         *  interior. */
        private fun loadDecorationBlocks(
            path: String, dimX: Int, dimY: Int, dimZ: Int,
        ): Array<Array<Array<BlockState?>>>? {
            return try {
                val cl = SselithRepertoryChunkGenerator::class.java.classLoader
                val stream = cl.getResourceAsStream(path)
                if (stream == null) {
                    PROFILE_LOG.error("Decoration NBT not found at $path")
                    return null
                }
                val tag = stream.use { NbtIo.readCompressed(it) }
                val blockLookup = BuiltInRegistries.BLOCK.asLookup()
                val paletteTag = tag.getList("palette", Tag.TAG_COMPOUND.toInt())
                val palette = Array(paletteTag.size) { i ->
                    NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i))
                }
                val blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND.toInt())
                val result = Array(dimX) {
                    Array(dimY) { arrayOfNulls<BlockState>(dimZ) }
                }
                for (i in 0 until blocksTag.size) {
                    val block = blocksTag.getCompound(i)
                    val posList = block.getList("pos", Tag.TAG_INT.toInt())
                    if (posList.size < 3) continue
                    val x = posList.getInt(0)
                    val y = posList.getInt(1)
                    val z = posList.getInt(2)
                    if (x !in 0 until dimX) continue
                    if (y !in 0 until dimY) continue
                    if (z !in 0 until dimZ) continue
                    val stateIdx = block.getInt("state")
                    if (stateIdx !in palette.indices) continue
                    val state = palette[stateIdx]
                    if (state.`is`(Blocks.STRUCTURE_VOID)) continue
                    if (state.isAir) continue
                    result[x][y][z] = state
                }
                result
            } catch (t: Throwable) {
                PROFILE_LOG.error("Failed to load decoration NBT at $path", t)
                null
            }
        }

        /** Pre-decoded `[type] → [x][y][z]` block-state arrays for every
         *  structure replacement. Nulls represent structure_void positions
         *  (leave the underlying paint untouched). Loaded once per JVM via
         *  [loadReplacementBlocks]; a missing or corrupt NBT yields a null
         *  array, and the paint pass for that type becomes a no-op. */
        private val REPLACEMENT_BLOCKS: Map<StructureReplacement, Array<Array<Array<BlockState?>>>?> by lazy {
            StructureReplacement.entries.associateWith { type ->
                loadReplacementBlocks(type)
            }
        }

        /** Per-replacement map of `(lx, ly, lz)` → block-entity NBT for
         *  every NBT block that carries a `nbt` compound (lectern books,
         *  sign text, banner patterns, chest contents — anything an
         *  author baked into the structure). Loaded lazily once per JVM
         *  alongside [REPLACEMENT_BLOCKS]. Keys are packed via
         *  [packStructureLocalKey] so lookups stay primitive-fast. */
        private val REPLACEMENT_BES: Map<StructureReplacement, Long2ObjectOpenHashMap<CompoundTag>> by lazy {
            StructureReplacement.entries.associateWith { type ->
                loadReplacementBlockEntities(type)
            }
        }

        private fun loadReplacementBlockEntities(
            type: StructureReplacement,
        ): Long2ObjectOpenHashMap<CompoundTag> {
            val map = Long2ObjectOpenHashMap<CompoundTag>()
            try {
                val cl = SselithRepertoryChunkGenerator::class.java.classLoader
                val stream = cl.getResourceAsStream(type.fullResourcePath) ?: return map
                val tag = stream.use { NbtIo.readCompressed(it) }
                val blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND.toInt())
                for (i in 0 until blocksTag.size) {
                    val block = blocksTag.getCompound(i)
                    if (!block.contains("nbt", Tag.TAG_COMPOUND.toInt())) continue
                    val posList = block.getList("pos", Tag.TAG_INT.toInt())
                    if (posList.size < 3) continue
                    val x = posList.getInt(0)
                    val y = posList.getInt(1)
                    val z = posList.getInt(2)
                    if (x !in 0 until type.dimX) continue
                    if (y !in 0 until type.dimY) continue
                    if (z !in 0 until type.dimZ) continue
                    map.put(packStructureLocalKey(x, y, z), block.getCompound("nbt"))
                }
            } catch (t: Throwable) {
                PROFILE_LOG.error("Failed to load block-entity NBTs from ${type.fullResourcePath}", t)
            }
            return map
        }

        /** Pack a structure-local `(lx, ly, lz)` into a single Long. Each
         *  coord fits in a byte (max structure dim is ~16), so 24 bits
         *  total — Long is overkill but keeps the map key primitive. */
        private fun packStructureLocalKey(x: Int, y: Int, z: Int): Long =
            (x.toLong() and 0xFF) or
            ((y.toLong() and 0xFF) shl 8) or
            ((z.toLong() and 0xFF) shl 16)

        private fun unpackStructureLocalX(key: Long): Int = (key and 0xFF).toInt()
        private fun unpackStructureLocalY(key: Long): Int = ((key ushr 8) and 0xFF).toInt()
        private fun unpackStructureLocalZ(key: Long): Int = ((key ushr 16) and 0xFF).toInt()

        private fun loadReplacementBlocks(
            type: StructureReplacement,
        ): Array<Array<Array<BlockState?>>>? {
            return try {
                val cl = SselithRepertoryChunkGenerator::class.java.classLoader
                val stream = cl.getResourceAsStream(type.fullResourcePath)
                if (stream == null) {
                    PROFILE_LOG.error("Structure NBT not found at ${type.fullResourcePath}")
                    return null
                }
                val tag = stream.use { NbtIo.readCompressed(it) }
                // Parse the NBT directly — StructureTemplate.palettes is
                // package-private and exposes no public iterator we can use
                // pre-place. Format is stable:
                //   palette: ListTag<CompoundTag> of block-state entries
                //   blocks:  ListTag<CompoundTag> { pos: IntArray[3], state: int }
                val blockLookup = BuiltInRegistries.BLOCK.asLookup()
                val paletteTag = tag.getList("palette", Tag.TAG_COMPOUND.toInt())
                val palette = Array(paletteTag.size) { i ->
                    NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i))
                }
                val blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND.toInt())
                val result = Array(type.dimX) {
                    Array(type.dimY) { arrayOfNulls<BlockState>(type.dimZ) }
                }
                for (i in 0 until blocksTag.size) {
                    val block = blocksTag.getCompound(i)
                    val posList = block.getList("pos", Tag.TAG_INT.toInt())
                    if (posList.size < 3) continue
                    val x = posList.getInt(0)
                    val y = posList.getInt(1)
                    val z = posList.getInt(2)
                    if (x !in 0 until type.dimX) continue
                    if (y !in 0 until type.dimY) continue
                    if (z !in 0 until type.dimZ) continue
                    val stateIdx = block.getInt("state")
                    if (stateIdx !in palette.indices) continue
                    val state = palette[stateIdx]
                    if (state.`is`(Blocks.STRUCTURE_VOID)) continue
                    result[x][y][z] = state
                }
                result
            } catch (t: Throwable) {
                PROFILE_LOG.error("Failed to load structure NBT at ${type.fullResourcePath}", t)
                null
            }
        }

        /** NBT replacement for the topmost procedural cross-axial arch in
         *  each gap-column. Natural NBT shape is 1×10×11 (X×Y×Z) — a
         *  single column-slice with the arch's long axis along Z. The
         *  rotated variant ([AXIAL_ARCH_BLOCKS_ROTATED]) maps NBT +Z onto
         *  world +X for Z-axial-path arches (whose long axis spans
         *  worldX), preserving facing/connection properties via
         *  [BlockState.rotate]. */
        private const val AXIAL_ARCH_RESOURCE_PATH: String =
            "data/enderkinesis/structures/sselith_axial_arch.nbt"
        private const val AXIAL_ARCH_DIM_X: Int = 1
        private const val AXIAL_ARCH_DIM_Y: Int = 10
        private const val AXIAL_ARCH_DIM_Z: Int = 11
        /** worldY offset from `cellY * MAZE_CELL_Y` to the NBT's `y = 0`
         *  corner. Picked so the NBT's bottom row (`y = 0`) lands at
         *  `cellY * MAZE_CELL_Y + (MAZE_CELL_Y - 2)`, i.e. one block
         *  below the procedural decorative pillar's top block (which
         *  sits at `cellY * MAZE_CELL_Y + (MAZE_CELL_Y - 1)` since
         *  `isGapColumn` fills the gap-column line solid up through the
         *  cell ceiling). With the bottom NBT pillar block landing on
         *  the column's top-1 row, the NBT's 5-tall pillar (`y = 0..4`)
         *  reads as the natural continuation of the procedural column,
         *  and the arch curve (`y = 5..9`) carries the peak up to
         *  `cellY * MAZE_CELL_Y + (MAZE_CELL_Y - 2) + 9` — which for
         *  `cellY = TOP_CELL_Y - 1` lands at world Y = 127, the world's
         *  highest writable row, so the peak slabs are NOT clipped.
         *  Bumping the offset higher (e.g. to `MAZE_CELL_Y - 1` so the
         *  bottom block sits exactly on the column top) would push the
         *  peak past Y = 127 and lose the topmost row. */
        private const val AXIAL_ARCH_CELL_Y_OFFSET: Int = MAZE_CELL_Y - 2

        /** Natural-orientation arch blocks. Index `[lx=0][ly][lz]`, matches
         *  NBT (X, Y, Z) verbatim. Used for X-axial-path arches whose long
         *  axis already runs along world Z. */
        private val AXIAL_ARCH_BLOCKS: Array<Array<Array<BlockState?>>>? by lazy {
            loadAxialArchBlocks(rotateCcw90 = false)
        }

        /** 90°-CCW-rotated arch blocks (looking down from +Y). Index
         *  `[lx][ly][lz=0]`, with NBT-Z values mapped onto world-X for
         *  Z-axial-path arches whose long axis spans world X. Block-state
         *  facings (stairs) and wall connections are rotated via
         *  [BlockState.rotate]. */
        private val AXIAL_ARCH_BLOCKS_ROTATED: Array<Array<Array<BlockState?>>>? by lazy {
            loadAxialArchBlocks(rotateCcw90 = true)
        }

        private fun loadAxialArchBlocks(
            rotateCcw90: Boolean,
        ): Array<Array<Array<BlockState?>>>? {
            return try {
                val cl = SselithRepertoryChunkGenerator::class.java.classLoader
                val stream = cl.getResourceAsStream(AXIAL_ARCH_RESOURCE_PATH)
                if (stream == null) {
                    PROFILE_LOG.error("Axial arch NBT not found at $AXIAL_ARCH_RESOURCE_PATH")
                    return null
                }
                val tag = stream.use { NbtIo.readCompressed(it) }
                val blockLookup = BuiltInRegistries.BLOCK.asLookup()
                val paletteTag = tag.getList("palette", Tag.TAG_COMPOUND.toInt())
                val palette = Array(paletteTag.size) { i ->
                    NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i))
                }
                val blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND.toInt())
                // Rotated shape swaps dim-X and dim-Z. With dim-X = 1, the
                // rotated array becomes (dim-Z, dim-Y, 1).
                val outX = if (rotateCcw90) AXIAL_ARCH_DIM_Z else AXIAL_ARCH_DIM_X
                val outZ = if (rotateCcw90) AXIAL_ARCH_DIM_X else AXIAL_ARCH_DIM_Z
                val result = Array(outX) {
                    Array(AXIAL_ARCH_DIM_Y) { arrayOfNulls<BlockState>(outZ) }
                }
                for (i in 0 until blocksTag.size) {
                    val block = blocksTag.getCompound(i)
                    val posList = block.getList("pos", Tag.TAG_INT.toInt())
                    if (posList.size < 3) continue
                    val x = posList.getInt(0)
                    val y = posList.getInt(1)
                    val z = posList.getInt(2)
                    if (x !in 0 until AXIAL_ARCH_DIM_X) continue
                    if (y !in 0 until AXIAL_ARCH_DIM_Y) continue
                    if (z !in 0 until AXIAL_ARCH_DIM_Z) continue
                    val stateIdx = block.getInt("state")
                    if (stateIdx !in palette.indices) continue
                    val state = palette[stateIdx]
                    if (state.`is`(Blocks.STRUCTURE_VOID)) continue
                    if (rotateCcw90) {
                        // CCW 90° around +Y: (lx, lz) → (lz, dim-X - 1 - lx).
                        // With dim-X = 1, the new Z coord collapses to 0.
                        val newLx = z
                        val newLz = (AXIAL_ARCH_DIM_X - 1) - x
                        result[newLx][y][newLz] = state.rotate(Rotation.COUNTERCLOCKWISE_90)
                    } else {
                        result[x][y][z] = state
                    }
                }
                result
            } catch (t: Throwable) {
                PROFILE_LOG.error("Failed to load axial arch NBT at $AXIAL_ARCH_RESOURCE_PATH", t)
                null
            }
        }

        /** Horizontal width of the per-wall painting grid in blocks (the
         *  Z-axis extent on a SPINE_WEST / SPINE_EAST face). The author's
         *  spec is "4 high × 5 wide" — i.e. 5 horizontal cells × 4
         *  vertical cells on each open wall. */
        private const val GALLERY_GRID_W: Int = 5

        /** Vertical height of the per-wall painting grid in blocks. */
        private const val GALLERY_GRID_H: Int = 4

        /** A vanilla painting variant + its block dimensions. The packer
         *  reads `(w, h)` to test fit + occupancy; the spawn pass uses
         *  `key` to look up the registered [PaintingVariant] holder. */
        private data class PaintingPick(
            val key: ResourceKey<PaintingVariant>,
            val w: Int, val h: Int,
        )

        /** Every vanilla 1.20.1 painting variant the packer can choose
         *  from, with its block dimensions. Sorted small-to-large doesn't
         *  matter — the packer's largest-area-first rule reorders at
         *  fill time. */
        private val PAINTING_VARIANTS: List<PaintingPick> = listOf(
            // 1×1
            PaintingPick(PaintingVariants.KEBAB, 1, 1),
            PaintingPick(PaintingVariants.AZTEC, 1, 1),
            PaintingPick(PaintingVariants.ALBAN, 1, 1),
            PaintingPick(PaintingVariants.AZTEC2, 1, 1),
            PaintingPick(PaintingVariants.BOMB, 1, 1),
            PaintingPick(PaintingVariants.PLANT, 1, 1),
            PaintingPick(PaintingVariants.WASTELAND, 1, 1),
            // 2×1
            PaintingPick(PaintingVariants.POOL, 2, 1),
            PaintingPick(PaintingVariants.COURBET, 2, 1),
            PaintingPick(PaintingVariants.SEA, 2, 1),
            PaintingPick(PaintingVariants.SUNSET, 2, 1),
            PaintingPick(PaintingVariants.CREEBET, 2, 1),
            // 1×2
            PaintingPick(PaintingVariants.WANDERER, 1, 2),
            PaintingPick(PaintingVariants.GRAHAM, 1, 2),
            // 2×2
            PaintingPick(PaintingVariants.MATCH, 2, 2),
            PaintingPick(PaintingVariants.BUST, 2, 2),
            PaintingPick(PaintingVariants.STAGE, 2, 2),
            PaintingPick(PaintingVariants.VOID, 2, 2),
            PaintingPick(PaintingVariants.SKULL_AND_ROSES, 2, 2),
            PaintingPick(PaintingVariants.WITHER, 2, 2),
            PaintingPick(PaintingVariants.EARTH, 2, 2),
            PaintingPick(PaintingVariants.WIND, 2, 2),
            PaintingPick(PaintingVariants.FIRE, 2, 2),
            PaintingPick(PaintingVariants.WATER, 2, 2),
            // 4×2
            PaintingPick(PaintingVariants.FIGHTERS, 4, 2),
            // 4×3
            PaintingPick(PaintingVariants.SKELETON, 4, 3),
            PaintingPick(PaintingVariants.DONKEY_KONG, 4, 3),
            // 4×4
            PaintingPick(PaintingVariants.POINTER, 4, 4),
            PaintingPick(PaintingVariants.PIGSCENE, 4, 4),
            PaintingPick(PaintingVariants.BURNING_SKULL, 4, 4),
        )

        /** A painting placed at a specific origin in a packing grid. The
         *  origin `(x, y)` is the bottom-left grid cell the painting
         *  occupies; the painting then spans `(x..x+w-1, y..y+h-1)`. */
        private data class PlacedPainting(val variant: PaintingPick, val x: Int, val y: Int)

        /** Pack a random arrangement of paintings into a [w] × [h] grid.
         *
         *  Algorithm:
         *   1. Pick a random variant that fits anywhere, place it at a
         *      random valid `(x, y)`. This is the "starting from a
         *      random painting" anchor.
         *   2. Top-left-scan empty cells. For each empty cell, find every
         *      variant that fits there without overlapping an existing
         *      placement; pick a random one weighted toward the largest
         *      area (random among ties) so the wall fills densely
         *      instead of getting choked with 1×1s.
         *   3. Stop once the scan finds no fitting variants for any
         *      remaining empty cell.
         *
         *  Deterministic for a given [seed]. */
        private fun packGalleryWall(
            w: Int, h: Int, seed: Long, initialOccupied: Array<BooleanArray>,
        ): List<PlacedPainting> {
            val rng = java.util.Random(seed)
            // Copy the caller's mask so step 2's mutations don't leak back.
            // `initialOccupied[gx][gy] == true` marks cells whose NBT wall
            // backer is missing / non-solid; the packer treats them as
            // already-filled so paintings only land where vanilla's
            // `survives` check would later pass.
            val occupied = Array(w) { gx -> initialOccupied[gx].copyOf() }
            val out = ArrayList<PlacedPainting>()

            // Step 1: random starting painting at a random valid position
            // — sampled from positions that respect the pre-marked mask.
            val fitsGrid = PAINTING_VARIANTS.filter { it.w <= w && it.h <= h }
            if (fitsGrid.isEmpty()) return out
            val first = fitsGrid[rng.nextInt(fitsGrid.size)]
            val validStarts = ArrayList<IntArray>()
            for (y in 0..h - first.h) {
                for (x in 0..w - first.w) {
                    if (paintingFitsAt(first, x, y, w, h, occupied)) {
                        validStarts.add(intArrayOf(x, y))
                    }
                }
            }
            if (validStarts.isNotEmpty()) {
                val startPos = validStarts[rng.nextInt(validStarts.size)]
                placePainting(first, startPos[0], startPos[1], occupied, out)
            }

            // Step 2: greedy largest-first fill, scanning top-left to
            // bottom-right. Unpaintable mask cells stay marked, so the
            // scan walks past them just like already-placed paintings.
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (occupied[x][y]) continue
                    var bestArea = 0
                    val fits = ArrayList<PaintingPick>()
                    for (v in PAINTING_VARIANTS) {
                        if (!paintingFitsAt(v, x, y, w, h, occupied)) continue
                        val a = v.w * v.h
                        if (a > bestArea) {
                            bestArea = a
                            fits.clear()
                            fits.add(v)
                        } else if (a == bestArea) {
                            fits.add(v)
                        }
                    }
                    if (fits.isEmpty()) continue
                    val pick = fits[rng.nextInt(fits.size)]
                    placePainting(pick, x, y, occupied, out)
                }
            }
            return out
        }

        /** Build the per-cell "unpaintable" mask for one wall of a
         *  gallery: each grid cell `(gx, gy)` maps to a wall-backer
         *  position in the gallery NBT, and the mask cell is true (i.e.
         *  occupied / off-limits) when that backer is missing
         *  (structure_void) or not a solid block. Vanilla painting
         *  survival requires `isSolid` on every covered backer; pre-
         *  marking eliminates the placements that would later trip
         *  `HangingEntity.tick` into dropping the painting as an item. */
        private fun buildGalleryWallOccupied(
            type: StructureReplacement, wall: GalleryWall,
        ): Array<BooleanArray> {
            val blocks = REPLACEMENT_BLOCKS[type]
            // Grid y=0 sits one block above the gallery floor (NBT ly=1).
            val lyBase = 1
            // Centre the GALLERY_GRID_W-wide grid horizontally on the
            // spine — for an 11-deep structure with a 4-wide grid that's
            // an offset of 3, matching the world coords in
            // `spawnGalleryPaintings`.
            val widthOffset = (type.dimZ - GALLERY_GRID_W) / 2
            // Both spine faces share the same backer column (the spine
            // itself), so the mask is identical for SPINE_WEST and
            // SPINE_EAST. We compute it per wall anyway because future
            // gallery designs may put the spine elsewhere.
            val spineLx = type.dimX / 2
            return Array(GALLERY_GRID_W) { gx ->
                BooleanArray(GALLERY_GRID_H) { gy ->
                    val ly = lyBase + gy
                    val lx = spineLx
                    val lz = widthOffset + gx
                    val state = if (
                        blocks != null &&
                        lx in 0 until type.dimX &&
                        ly in 0 until type.dimY &&
                        lz in 0 until type.dimZ
                    ) blocks[lx][ly][lz] else null
                    // True ⇒ unpaintable: NBT had structure_void (null) or
                    // a non-solid block at the would-be backer. The
                    // `isSolid()` check matches vanilla `survives()`.
                    state == null || !state.isSolid
                }
            }
        }

        private fun paintingFitsAt(
            v: PaintingPick, x: Int, y: Int, w: Int, h: Int, occupied: Array<BooleanArray>,
        ): Boolean {
            if (x + v.w > w) return false
            if (y + v.h > h) return false
            for (dx in 0 until v.w) {
                for (dy in 0 until v.h) {
                    if (occupied[x + dx][y + dy]) return false
                }
            }
            return true
        }

        private fun placePainting(
            v: PaintingPick, x: Int, y: Int,
            occupied: Array<BooleanArray>, out: MutableList<PlacedPainting>,
        ) {
            for (dx in 0 until v.w) {
                for (dy in 0 until v.h) {
                    occupied[x + dx][y + dy] = true
                }
            }
            out.add(PlacedPainting(v, x, y))
        }

        val CODEC: Codec<SselithRepertoryChunkGenerator> =
            RecordCodecBuilder.create { instance ->
                instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                        .forGetter(SselithRepertoryChunkGenerator::getBiomeSourceForCodec),
                ).apply(instance, ::SselithRepertoryChunkGenerator)
            }
    }
}
