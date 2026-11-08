package org.shipwrights.enderkinesis.worldgen

import org.shipwrights.enderkinesis.compat.distanthorizons.DistantHorizonsCompat
import dev.architectury.event.events.common.LifecycleEvent
import com.mojang.serialization.Codec
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.TickTask
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.entity.ShulkonautEntity
import org.shipwrights.enderkinesis.registry.EKProcessors
import org.shipwrights.enderkinesis.util.ServerTicks
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.assembly.VSAssemblyEvents
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.core.api.util.GameTickOnly

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
@OptIn(GameTickOnly::class)
object RoamingEndShipShipifyProcessor : StructureProcessor(), CapturesCrew {

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
        // Before the dedup, or a DH LOD pass would claim startPos and block the real assembly.
        if (DistantHorizonsCompat.isLodGeneration()) return processedBlockInfos

        // Multi-chunk structures get postProcess'd once per intersecting chunk and once per
        // worldgen worker, so the SAME structure instance fires finalize multiple times in
        // parallel. Dedup on the structure's start position — first finalize wins, the rest
        // skip without scheduling.
        if (!pendingAssemblies.add(startPos.immutable())) return processedBlockInfos


        // Position-seeded yaw. We deliberately *don't* use [StructurePlaceSettings.getRandom]
        // here: for worldgen-placed structures that random is a fixed-seed [SimpleRandomSource]
        // (the default `new Random(0L)`-style RNG StructurePlaceSettings ships with), and it
        // ignores the BlockPos argument. Calling `.nextFloat()` on it returns the same value
        // for every roaming end ship in the world — which is why every ship was spawning at
        // the same yaw. Mth.getSeed gives a deterministic but position-varying seed, so
        // different ships get different yaws and re-generating the same chunk picks the
        // same one.
        @Suppress("DEPRECATION") // Mth.getSeed is the vanilla position hash; 1.20.1 has no replacement.
        val rng = RandomSource.create(Mth.getSeed(startPos.x, startPos.y, startPos.z))
        val yawRadians = rng.nextDouble() * (2.0 * Math.PI)
        // Standing orders, rolled per ship from the same position-stable seed: a
        // doctrine verb × a target (vessels or players). Issued to the shulkonaut at
        // crew placement; its signed book is rewritten to match.
        val target = if (rng.nextBoolean()) "@v" else "@a"
        val orderLine = ORDER_TEMPLATES[rng.nextInt(ORDER_TEMPLATES.size)].replace("%s", target)

        val anchor = centerOf(blocks)
        scheduleAssembleAndRotate(server, blocks, anchor, yawRadians, orderLine, startPos.immutable(), ASSEMBLY_ATTEMPTS)

        return processedBlockInfos
    }

    override fun getType(): StructureProcessorType<*> = EKProcessors.SHIPIFY.get()

    fun init() {
        // The maps are static, and `awaitingPaste` holds a level: a stale entry must not reach the next world.
        LifecycleEvent.SERVER_STOPPED.register {
            pendingAssemblies.clear()
            awaitingPaste.clear()
        }
        VSAssemblyEvents.onPasteAfterBlocksAreLoaded.on { event ->
            val shipId = event.newShip?.id ?: return@on
            val post = awaitingPaste.remove(shipId) ?: return@on
            yawWhenLoaded(shipId, post, YAW_ATTEMPTS)
        }
    }

    private fun scheduleAssembleAndRotate(
        server: ServerLevel, blocks: Set<BlockPos>, anchor: BlockPos,
        yawRadians: Double, orderLine: String, dedupeKey: BlockPos, attemptsLeft: Int,
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
                    // A TickTask is not a delay, so the retry waits real ticks first.
                    ServerTicks.runAfter(ms, ASSEMBLY_DELAY_TICKS) {
                        scheduleAssembleAndRotate(server, blocks, anchor, yawRadians, orderLine, dedupeKey, attemptsLeft - 1)
                    }
                } else {
                    pendingAssemblies.remove(dedupeKey)
                    ShipCrew.takeCaptured(dedupeKey).forEach { it.discard() }
                }
                return@TickTask
            }
            try {
                // Authoritative flotation line BEFORE assembly: every lattice aboard is
                // stamped with its generation-height world Y in THIS dimension. Kills
                // both the stale-template-stamp problem (exported-world Y/dim riding in
                // the BE NBT) and the derive-from-transform race on a falling ship.
                val dimId = server.dimension().location().toString()
                for (bp in blocks) {
                    val be = server.getBlockEntity(bp)
                        as? org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
                        ?: continue
                    be.stampSeaLevel(bp.y + 0.5, dimId)
                    be.parkForWorldgen()
                }
                val crew = ShipCrew.takeCaptured(dedupeKey)
                val assembled = ShipAssembler.assembleToShipFull(server, blocks, /* scale */ 1.0)
                val ship = assembled.ship
                // Tag it so the discovery advancement can fire on its live (drifting)
                // position rather than a stale structure bounding box.
                org.shipwrights.enderkinesis.dimension.YgannDiscoveries.mark(
                    ms, ship.id, org.shipwrights.enderkinesis.dimension.YgannDiscoveries.KIND_ROAMING_END_SHIP,
                )
                // Parked STATIC until a player first comes near (see the lattice BE's
                // parkForWorldgen): far shipyard chunks never tick, so no inducer, and
                // a dynamic hull free-fell into the void before anyone ever arrived.
                ship.isStatic = true
                val offset = ShipCrew.assemblyOffset(blocks, assembled.toCenter)
                // assembleToShipFull returns before VS2 has loaded the pasted blocks; the rest
                // runs on onPasteAfterBlocksAreLoaded, which VS2 always fires on a later tick.
                awaitingPaste[ship.id] = PostAssembly(server, offset, yawRadians, orderLine, crew)
            } catch (t: Throwable) {
                LOG.error("[EK] roaming end ship assembly threw at {}", anchor, t)
            } finally {
                pendingAssemblies.remove(dedupeKey)
            }
        })
    }

    private class PostAssembly(
        val server: ServerLevel,
        val offset: BlockPos,
        val yawRadians: Double,
        val orderLine: String,
        val crew: List<Entity>,
    )

    private fun yawWhenLoaded(shipId: Long, post: PostAssembly, attemptsLeft: Int) {
        val server = post.server
        val ms = server.server
        ServerTicks.runAfter(ms, YAW_RETRY_DELAY_TICKS) {
            val loaded = server.shipObjectWorld.loadedShips.getById(shipId)
            if (loaded == null) {
                if (attemptsLeft > 1) yawWhenLoaded(shipId, post, attemptsLeft - 1) else placeCrew(post)
                return@runAfter
            }
            val data = vsCore.newShipTeleportData(
                newPos = Vector3d(loaded.transform.positionInWorld),
                newRot = Quaterniond().rotateY(post.yawRadians),
                newDimension = server.dimensionId,
            )
            vsCore.teleportShip(server.shipObjectWorld as ServerShipWorld, loaded, data)
            // Placing shipyard entities before the teleport applies lets VS2 swing them off the hull.
            ServerTicks.runAfter(ms, CREW_PLACE_DELAY_TICKS) { placeCrew(post) }
        }
    }

    private fun placeCrew(post: PostAssembly) {
        ShipCrew.spawnInShipyard(post.server, post.crew, post.offset, "roaming end ship")
        for (entity in post.crew) (entity as? ShulkonautEntity)?.issueOrder(post.orderLine)
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
    private const val YAW_RETRY_DELAY_TICKS = 5
    private const val YAW_ATTEMPTS = 40
    private const val CREW_PLACE_DELAY_TICKS = 10

    /** Doctrine pool for randomized standing orders ("%s" = target selector). */
    private val ORDER_TEMPLATES = listOf(
        "TO: %s",
        "AROUND(30): %s",
        "NEAR(10): %s",
        "ABOVE(5): %s",
        "BEHIND(30): %s",
        "LEAD(20): %s",
        "SCATTER(50): %s",
    )

    /** Structures that have already been scheduled for assembly. Keyed on `startPos` since
     *  worldgen calls finalize once per intersecting chunk × worker thread for the same
     *  structure piece — without dedup we'd schedule the same assembly 4–8 times. Removed
     *  once the deferred task runs (whether it succeeds, fails, or gives up). */
    private val pendingAssemblies = ConcurrentHashMap.newKeySet<BlockPos>()
    private val awaitingPaste = ConcurrentHashMap<Long, PostAssembly>()
}
