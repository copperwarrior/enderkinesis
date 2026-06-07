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
import org.shipwrights.enderkinesis.blockentity.PlanarAnchorBlockEntity
import org.shipwrights.enderkinesis.registry.EKProcessors
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore

/**
 * Structure processor for the Ygann Anchor: after the template's blocks have been placed,
 * assemble them into a Valkyrien Skies 2 ship and snap to a random 0–360° yaw plus a small
 * uniform ±[MAX_TILT_RAD] pitch and roll, so the anchor reads as "drifting in the void, not
 * sitting on a parking pad". Scheduling + retry mirrors [RoamingEndShipShipifyProcessor] —
 * same finalize-then-deferred-TickTask pattern, same chunk-load retry, same dedup-by-startPos.
 *
 *  Per spec ("Random yaw on spawn, random pitch and roll by ±5 degrees"): the small tilt
 *  range keeps each anchor recognisably anchor-like (not visibly upside-down) while breaking
 *  the grid-aligned look that yaw-only rotation produces. Yaw is full-range; pitch + roll are
 *  ±5° (~0.087 rad) so even at the extremes you'd struggle to spot the tilt from a distance.
 */
object YgannAnchorShipifyProcessor : StructureProcessor() {

    val CODEC: Codec<YgannAnchorShipifyProcessor> = Codec.unit(YgannAnchorShipifyProcessor)

    /** Maximum pitch/roll deflection (radians) from level. Spec: ±5°. */
    private val MAX_TILT_RAD = Math.toRadians(5.0)

    override fun finalizeProcessing(
        level: ServerLevelAccessor,
        startPos: BlockPos,
        pivotPos: BlockPos,
        rawBlockInfos: MutableList<StructureTemplate.StructureBlockInfo>,
        processedBlockInfos: MutableList<StructureTemplate.StructureBlockInfo>,
        settings: StructurePlaceSettings,
    ): MutableList<StructureTemplate.StructureBlockInfo> {
        val server = level.level
        val blocks = HashSet<BlockPos>(processedBlockInfos.size)
        for (info in processedBlockInfos) {
            if (info.state.isAir) continue
            blocks.add(info.pos.immutable())
        }
        if (blocks.isEmpty()) return processedBlockInfos

        // Multi-chunk dedup — finalize fires once per intersecting chunk × worker. First call
        // for a given startPos wins, the rest skip without scheduling.
        if (!pendingAssemblies.add(startPos.immutable())) return processedBlockInfos

        LOG.info("[EK] ygann anchor: finalize @ start={} processed={} non-air={} (scheduling)",
            startPos, processedBlockInfos.size, blocks.size)

        // Position-seeded random. [StructurePlaceSettings.getRandom] returns a fixed-seed
        // RNG that ignores its BlockPos argument — see [RoamingEndShipShipifyProcessor] for
        // the full why. Mth.getSeed gives a deterministic-but-position-varying seed.
        val rng = RandomSource.create(Mth.getSeed(startPos.x, startPos.y, startPos.z))
        val yaw = rng.nextDouble() * (2.0 * Math.PI)
        val pitch = (rng.nextDouble() * 2.0 - 1.0) * MAX_TILT_RAD
        val roll  = (rng.nextDouble() * 2.0 - 1.0) * MAX_TILT_RAD

        val anchor = centerOf(blocks)
        scheduleAssembleAndRotate(
            server, blocks, anchor, yaw, pitch, roll, startPos.immutable(), ASSEMBLY_ATTEMPTS,
        )

        return processedBlockInfos
    }

    override fun getType(): StructureProcessorType<*> = EKProcessors.YGANN_ANCHOR_SHIPIFY.get()

    private fun scheduleAssembleAndRotate(
        server: ServerLevel, blocks: Set<BlockPos>, anchor: BlockPos,
        yaw: Double, pitch: Double, roll: Double,
        dedupeKey: BlockPos, attemptsLeft: Int,
    ) {
        val ms = server.server
        ms.tell(TickTask(ms.tickCount + ASSEMBLY_DELAY_TICKS) {
            val chunksContainingBlocks = blocks.mapTo(HashSet()) { ChunkPos(it) }
            for (cp in chunksContainingBlocks) server.getChunk(cp.x, cp.z)
            if (!blocks.all { server.isLoaded(it) }) {
                if (attemptsLeft > 1) {
                    scheduleAssembleAndRotate(
                        server, blocks, anchor, yaw, pitch, roll, dedupeKey, attemptsLeft - 1,
                    )
                } else {
                    LOG.warn("[EK] ygann anchor at {} never had all {} chunks loaded; gave up.",
                        anchor, chunksContainingBlocks.size)
                    pendingAssemblies.remove(dedupeKey)
                }
                return@TickTask
            }
            try {
                LOG.info("[EK] ygann anchor: assembling {} blocks across {} chunks @ {}",
                    blocks.size, chunksContainingBlocks.size, anchor)
                val ship = ShipAssembler.assembleToShip(server, blocks, /* scale */ 1.0)
                if (ship !is LoadedServerShip) {
                    LOG.warn("[EK] ygann anchor assembled but is not a LoadedServerShip ({}); skipping rotation.",
                        ship?.javaClass)
                    return@TickTask
                }
                // Reset planar anchor BE state on every block we just shipified. The NBT
                // the BEs came back from holds activated-anchor state from whichever world
                // built this structure template — `persistedPose0` points at long-gone world
                // coords and `persistedTargetShipId` references a ship that doesn't exist
                // here. Without this pass, the BE's next [serverTick] sees
                // `persistedJointType != JOINT_NONE` and calls `rebindJoint` with the stale
                // data instead of running a fresh trace+scan in the new ship frame — net
                // effect is "the anchors look already-activated but their joint is bound to
                // nothing useful." Wiping their joint state forces a clean resolve.
                //
                // Done *before* teleportShip: at this point `worldToShip` still maps the
                // structure's original world block positions to the freshly-allocated
                // shipyard coords. After teleport, `worldToShip` references the *new* world
                // pose, but shipyard coords are stable across that — using the pre-teleport
                // transform is the path of least confusion.
                val w2s = ship.transform.worldToShip
                val tmp = Vector3d()
                for (worldPos in blocks) {
                    tmp.set(worldPos.x + 0.5, worldPos.y + 0.5, worldPos.z + 0.5)
                    w2s.transformPosition(tmp)
                    val shipyardPos = BlockPos(
                        Math.floor(tmp.x).toInt(),
                        Math.floor(tmp.y).toInt(),
                        Math.floor(tmp.z).toInt(),
                    )
                    (server.getBlockEntity(shipyardPos) as? PlanarAnchorBlockEntity)
                        ?.resetForStructureRespawn()
                }

                // Compose Y(yaw) · X(pitch) · Z(roll) — standard aerospace order. JOML's
                // `rotate*` post-multiplies, so applied to a body-frame vector this rolls
                // first, then pitches, then yaws. World up is +Y; pitch tips the bow up/down
                // along X; roll banks left/right around Z.
                val rot = Quaterniond().rotateY(yaw).rotateX(pitch).rotateZ(roll)
                LOG.info("[EK] ygann anchor assembled OK; rotating ship {} yaw={} pitch={} roll={} rad",
                    ship.id, yaw, pitch, roll)
                val data = vsCore.newShipTeleportData(
                    newPos = Vector3d(ship.transform.positionInWorld),
                    newRot = rot,
                    newDimension = server.dimensionId,
                )
                vsCore.teleportShip(server.shipObjectWorld as ServerShipWorld, ship, data)
            } catch (t: Throwable) {
                LOG.error("[EK] ygann anchor assembly threw at {}", anchor, t)
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
    private const val ASSEMBLY_DELAY_TICKS = 10
    private const val ASSEMBLY_ATTEMPTS = 20
    private val pendingAssemblies = ConcurrentHashMap.newKeySet<BlockPos>()
}
