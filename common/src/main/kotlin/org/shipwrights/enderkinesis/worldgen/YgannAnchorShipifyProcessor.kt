package org.shipwrights.enderkinesis.worldgen

import dev.architectury.event.events.common.LifecycleEvent
import org.shipwrights.enderkinesis.util.ServerTicks
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
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.assembly.VSAssemblyEvents
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
object YgannAnchorShipifyProcessor : StructureProcessor(), CapturesCrew {

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
                    // A TickTask is not a delay, so the retry waits real ticks first.
                    ServerTicks.runAfter(ms, ASSEMBLY_DELAY_TICKS) {
                        scheduleAssembleAndRotate(
                            server, blocks, anchor, yaw, pitch, roll, dedupeKey, attemptsLeft - 1,
                        )
                    }
                } else {
                    pendingAssemblies.remove(dedupeKey)
                    ShipCrew.takeCaptured(dedupeKey).forEach { it.discard() }
                }
                return@TickTask
            }
            try {
                val crew = ShipCrew.takeCaptured(dedupeKey)
                val assembled = ShipAssembler.assembleToShipFull(server, blocks, /* scale */ 1.0)
                val ship = assembled.ship
                // VS2 assembly ends dynamic and returns the ship before it is loaded; hold it
                // static until its anchors have joints.
                ship.isStatic = true
                // Assembly is a pure block translation, so this is exact (no transform timing).
                val offset = BlockPos(
                    Math.round(assembled.toCenter.x - assembled.fromCenter.x).toInt(),
                    Math.round(assembled.toCenter.y - assembled.fromCenter.y).toInt(),
                    Math.round(assembled.toCenter.z - assembled.fromCenter.z).toInt(),
                )
                // Compose Y(yaw) · X(pitch) · Z(roll) — standard aerospace order. JOML's
                // `rotate*` post-multiplies, so applied to a body-frame vector this rolls
                // first, then pitches, then yaws. World up is +Y; pitch tips the bow up/down
                // along X; roll banks left/right around Z.
                val rot = Quaterniond().rotateY(yaw).rotateX(pitch).rotateZ(roll)
                // assembleToShipFull returns before VS2 has loaded the pasted blocks; the rest
                // runs on onPasteAfterBlocksAreLoaded, which VS2 always fires on a later tick.
                awaitingPaste[ship.id] = PostAssembly(server, blocks, offset, rot, crew)
            } catch (t: Throwable) {
                LOG.error("[EK] ygann anchor assembly threw at {}", anchor, t)
            } finally {
                pendingAssemblies.remove(dedupeKey)
            }
        })
    }

    fun init() {
        // The maps are static, and `awaitingPaste` holds a level: a stale entry must not reach the next world.
        LifecycleEvent.SERVER_STOPPED.register {
            pendingAssemblies.clear()
            awaitingPaste.clear()
        }
        VSAssemblyEvents.onPasteAfterBlocksAreLoaded.on { event ->
            val shipId = event.newShip?.id ?: return@on
            val post = awaitingPaste.remove(shipId) ?: return@on
            finishAssembly(shipId, post)
        }
    }

    private class PostAssembly(
        val server: ServerLevel,
        val worldBlocks: Set<BlockPos>,
        val offset: BlockPos,
        val rot: Quaterniond,
        val crew: List<net.minecraft.world.entity.Entity>,
    )

    private fun finishAssembly(shipId: Long, post: PostAssembly) {
        val server = post.server
        // Template BE NBT holds joint and lid-ship state from the world that built the template.
        val anchors = ArrayList<BlockPos>()
        for (worldPos in post.worldBlocks) {
            val shipyardPos = worldPos.offset(post.offset)
            when (val be = server.getBlockEntity(shipyardPos)) {
                is PlanarAnchorBlockEntity -> {
                    be.resetForStructureRespawn()
                    anchors.add(shipyardPos)
                }
                is org.shipwrights.enderkinesis.blockentity.ShulkerStrutBlockEntity -> be.resetForStructureRespawn()
            }
        }
        rotateWhenLoaded(server, shipId, post.rot, post.crew, post.offset, anchors, LOAD_ATTEMPTS)
    }

    private fun rotateWhenLoaded(
        server: ServerLevel, shipId: Long, rot: Quaterniond,
        crew: List<net.minecraft.world.entity.Entity>, offset: BlockPos,
        anchors: List<BlockPos>, attemptsLeft: Int,
    ) {
        val ms = server.server
        ServerTicks.runAfter(ms, LOAD_RETRY_TICKS) {
            val ship = server.shipObjectWorld.loadedShips.getById(shipId)
            if (ship == null) {
                if (attemptsLeft > 1) {
                    rotateWhenLoaded(server, shipId, rot, crew, offset, anchors, attemptsLeft - 1)
                } else {
                    ShipCrew.spawnInShipyard(server, crew, offset, "ygann anchor")
                    releaseWhenAnchored(server, shipId, anchors, ANCHOR_POLL_ATTEMPTS)
                }
                return@runAfter
            }
            val data = vsCore.newShipTeleportData(
                newPos = Vector3d(ship.transform.positionInWorld),
                newRot = rot,
                newDimension = server.dimensionId,
            )
            vsCore.teleportShip(server.shipObjectWorld as ServerShipWorld, ship, data)
            // Placing shipyard entities before the teleport applies lets VS2 swing them off the hull.
            ServerTicks.runAfter(ms, CREW_PLACE_DELAY_TICKS) {
                ShipCrew.spawnInShipyard(server, crew, offset, "ygann anchor")
            }
            releaseWhenAnchored(server, shipId, anchors, ANCHOR_POLL_ATTEMPTS)
        }
    }

    /** Makes the ship dynamic once every powered anchor has its joint, or after the last attempt. */
    private fun releaseWhenAnchored(server: ServerLevel, shipId: Long, anchors: List<BlockPos>, attemptsLeft: Int) {
        val ms = server.server
        ServerTicks.runAfter(ms, ANCHOR_POLL_TICKS) {
            val ship = server.shipObjectWorld.allShips.getById(shipId) ?: return@runAfter
            val waiting = anchors.any { pos ->
                if (!server.isLoaded(pos)) return@any true
                val anchor = server.getBlockEntity(pos) as? PlanarAnchorBlockEntity ?: return@any false
                server.hasNeighborSignal(pos) && !anchor.hasJoint
            }
            if (waiting && attemptsLeft > 1) {
                releaseWhenAnchored(server, shipId, anchors, attemptsLeft - 1)
                return@runAfter
            }
            ship.isStatic = false
        }
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
    private const val CREW_PLACE_DELAY_TICKS = 10
    private const val LOAD_RETRY_TICKS = 5
    private const val LOAD_ATTEMPTS = 40
    private const val ANCHOR_POLL_TICKS = 5
    /** 60 s at [ANCHOR_POLL_TICKS]. */
    private const val ANCHOR_POLL_ATTEMPTS = 240
    private val pendingAssemblies = ConcurrentHashMap.newKeySet<BlockPos>()
    private val awaitingPaste = ConcurrentHashMap<Long, PostAssembly>()
}
