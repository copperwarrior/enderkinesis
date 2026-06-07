package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.TickTask
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKProcessors
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore

/**
 * Structure processor for the Roaming End Ship: after the template's blocks have been placed,
 * assemble them into a Valkyrien Skies 2 ship and snap that ship to a random 0–360° yaw.
 *
 * Runs in [finalizeProcessing] (which receives the final processed block list AND a real
 * [ServerLevelAccessor], so we can defer to the main thread). The actual assembly call is
 * deferred via [TickTask] because:
 *   1. Structure processing happens during chunk generation, which is largely off the main
 *      thread, while [ShipAssembler.assembleToShip] is game-tick-only.
 *   2. The blocks aren't physically in the world yet at processor time — placement happens
 *      *after* finalize returns. The deferred task waits a few ticks, then force-loads the
 *      lattice chunk so the block reads are deterministic regardless of player proximity
 *      (same retry pattern as [CrepusculiteGeodeFeature]).
 *
 * Yaw is picked from [StructurePlaceSettings.getRandom] keyed on the structure's start
 * position, so the chosen rotation is stable across chunk regeneration of the same world seed.
 */
object RoamingEndShipShipifyProcessor : StructureProcessor() {

    /** Empty codec — this processor takes no configuration. Use the value-form of
     *  {@code Codec.unit}; the {@code Supplier}-form via a Kotlin lambda can resolve to an
     *  unintended overload depending on inference context. */
    val CODEC: Codec<RoamingEndShipShipifyProcessor> = Codec.unit(RoamingEndShipShipifyProcessor)

    override fun finalizeProcessing(
        level: ServerLevelAccessor,
        startPos: BlockPos,
        pivotPos: BlockPos,
        rawBlockInfos: MutableList<StructureTemplate.StructureBlockInfo>,
        processedBlockInfos: MutableList<StructureTemplate.StructureBlockInfo>,
        settings: StructurePlaceSettings,
    ): MutableList<StructureTemplate.StructureBlockInfo> {
        val server = level.level   // ServerLevelAccessor.getLevel() returns ServerLevel
        val blocks = HashSet<BlockPos>(processedBlockInfos.size)
        for (info in processedBlockInfos) {
            if (info.state.isAir) continue                 // skip air voxels
            blocks.add(info.pos.immutable())
        }
        if (blocks.isEmpty()) return processedBlockInfos

        // Multi-chunk structures get postProcess'd once per intersecting chunk and once per
        // worldgen worker, so the SAME structure instance fires finalize multiple times in
        // parallel. Dedup on the structure's start position — first finalize wins, the rest
        // skip without scheduling.
        if (!pendingAssemblies.add(startPos.immutable())) return processedBlockInfos

        LOG.info("[EK] roaming end ship: finalize @ start={} processed={} non-air={} (scheduling)",
            startPos, processedBlockInfos.size, blocks.size)

        // Position-seeded yaw. We deliberately *don't* use [StructurePlaceSettings.getRandom]
        // here: for worldgen-placed structures that random is a fixed-seed [SimpleRandomSource]
        // (the default `new Random(0L)`-style RNG StructurePlaceSettings ships with), and it
        // ignores the BlockPos argument. Calling `.nextFloat()` on it returns the same value
        // for every roaming end ship in the world — which is why every ship was spawning at
        // the same yaw. Mth.getSeed gives a deterministic but position-varying seed, so
        // different ships get different yaws and re-generating the same chunk picks the
        // same one.
        val rng = RandomSource.create(Mth.getSeed(startPos.x, startPos.y, startPos.z))
        val yawRadians = rng.nextDouble() * (2.0 * Math.PI)

        val anchor = centerOf(blocks)
        scheduleAssembleAndRotate(server, blocks, anchor, yawRadians, startPos.immutable(), ASSEMBLY_ATTEMPTS)

        return processedBlockInfos
    }

    override fun getType(): StructureProcessorType<*> = EKProcessors.SHIPIFY.get()

    private fun scheduleAssembleAndRotate(
        server: ServerLevel, blocks: Set<BlockPos>, anchor: BlockPos,
        yawRadians: Double, dedupeKey: BlockPos, attemptsLeft: Int,
    ) {
        val ms = server.server
        ms.tell(TickTask(ms.tickCount + ASSEMBLY_DELAY_TICKS) {
            // The structure spans multiple chunks. Force-load ALL of them on the server thread
            // so the block reads inside assembleToShip don't fail on edge chunks that haven't
            // been promoted to FULL by a nearby player. Just the anchor isn't enough.
            val chunksContainingBlocks = blocks.mapTo(HashSet()) { ChunkPos(it) }
            for (cp in chunksContainingBlocks) server.getChunk(cp.x, cp.z)
            if (!blocks.all { server.isLoaded(it) }) {
                if (attemptsLeft > 1) {
                    scheduleAssembleAndRotate(server, blocks, anchor, yawRadians, dedupeKey, attemptsLeft - 1)
                } else {
                    LOG.warn("[EK] roaming end ship at {} never had all {} chunks loaded; gave up.",
                        anchor, chunksContainingBlocks.size)
                    pendingAssemblies.remove(dedupeKey)
                }
                return@TickTask
            }
            try {
                LOG.info("[EK] roaming end ship: assembling {} blocks across {} chunks @ {}",
                    blocks.size, chunksContainingBlocks.size, anchor)
                val ship = ShipAssembler.assembleToShip(server, blocks, /* scale */ 1.0)
                if (ship !is LoadedServerShip) {
                    LOG.warn("[EK] roaming end ship assembled but is not a LoadedServerShip ({}); skipping yaw rotation.",
                        ship?.javaClass)
                    return@TickTask
                }
                LOG.info("[EK] roaming end ship assembled OK; rotating ship {} to {} rad",
                    ship.id, yawRadians)
                // Reposition with the new rotation. Position-in-world is unchanged; just the
                // shipToWorld rotation gets swapped.
                val data = vsCore.newShipTeleportData(
                    newPos = Vector3d(ship.transform.positionInWorld),
                    newRot = Quaterniond().rotateY(yawRadians),
                    newDimension = server.dimensionId,
                )
                vsCore.teleportShip(server.shipObjectWorld as ServerShipWorld, ship, data)
            } catch (t: Throwable) {
                LOG.error("[EK] roaming end ship assembly threw at {}", anchor, t)
            } finally {
                pendingAssemblies.remove(dedupeKey)
            }
        })
    }

    private fun centerOf(blocks: Set<BlockPos>): BlockPos {
        var cx = 0L; var cy = 0L; var cz = 0L
        for (p in blocks) { cx += p.x; cy += p.y; cz += p.z }
        val n = blocks.size
        return BlockPos((cx / n).toInt(), (cy / n).toInt(), (cz / n).toInt())
    }

    private val LOG = com.mojang.logging.LogUtils.getLogger()
    /** Ticks to wait before the first assembly attempt — lets the chunks settle. */
    private const val ASSEMBLY_DELAY_TICKS = 10
    /** How many retries before giving up if chunks never finish loading. */
    private const val ASSEMBLY_ATTEMPTS = 20

    /** Structures that have already been scheduled for assembly. Keyed on `startPos` since
     *  worldgen calls finalize once per intersecting chunk × worker thread for the same
     *  structure piece — without dedup we'd schedule the same assembly 4–8 times. Removed
     *  once the deferred task runs (whether it succeeds, fails, or gives up). */
    private val pendingAssemblies = ConcurrentHashMap.newKeySet<BlockPos>()
}
