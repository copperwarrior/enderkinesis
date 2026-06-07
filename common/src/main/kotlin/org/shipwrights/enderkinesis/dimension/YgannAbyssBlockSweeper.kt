package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import org.valkyrienskies.mod.api.isBlockInShipyard
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * Global "random tick" sweep that destroys any block in [YgannAbyss] that isn't held by a
 * VS2 shipyard. The placement guard ([YgannAbyssGuard]) catches *normal* placement at intent
 * time, but anything that bypasses block-place events — `/setblock`, explosion residue,
 * piston-pushed blocks, fluid-flow placements, mod-driven `Level.setBlock` calls, world data
 * inherited from older saves — still ends up in the world. This sweeper is the second line:
 * a vanilla-style randomTick that fires per non-air voxel in loaded chunks and destroys
 * everything that isn't a ship's shipyard block.
 *
 * Implementation choices:
 *  - Hooks `TickEvent.SERVER_LEVEL_POST` once per server tick per level — only Ygann's Abyss
 *    runs the sweep, every other dimension is a single dimension-key compare and bail.
 *  - Iterates chunks within each player's `view-distance` window. Loaded shipyard chunks are
 *    scanned too if a player has teleported there; the shipyard check guarantees their
 *    blocks survive.
 *  - Skips shipyard chunks wholesale via [Level.isBlockInShipyard] — every block in a
 *    shipyard chunk is by definition a legitimate ship block, no need to scan or per-voxel
 *    check.
 *  - Skips [LevelChunkSection.hasOnlyAir] sections wholesale — in a void dimension that's
 *    almost every section, so the per-tick cost stays bounded by the sparse stray blocks
 *    that actually need cleaning.
 *  - Within a non-air section, samples [RANDOM_TICK_SPEED] random voxels per tick —
 *    vanilla's random-tick cadence. Fluids are *not* excluded — water/lava block states
 *    have `isAir == false` and pass the sampling check normally — they just take a few
 *    real-time seconds before a sample lands on them, which is the point of random ticks.
 *    (Earlier full-section iteration was wrong: in a non-air section near a shipyard
 *    boundary that could mean thousands of `getLoadedShipManagingPos` lookups per tick,
 *    which spikes hard with multi-chunk ships in view.)
 *  - Destroyed blocks emit the same ender-particle burst + enderman-teleport sound as the
 *    placement-guard rejection, so cleanup and rejection feel like the same effect.
 */
object YgannAbyssBlockSweeper {

    /** Voxels sampled per non-air section per tick — vanilla's `random_tick_speed = 3`. */
    private const val RANDOM_TICK_SPEED = 3

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(TickEvent.ServerLevelTick { level ->
            if (level.dimension() == YgannAbyss.LEVEL_KEY) sweep(level)
        })
    }

    private fun sweep(level: ServerLevel) {
        val players = level.players()
        if (players.isEmpty()) return                     // no observers → no loaded chunks to scan
        val rng = level.random
        val visited = HashSet<Long>()
        val viewDist = level.server.playerList.viewDistance
        for (player in players) {
            val cx = player.blockX shr 4
            val cz = player.blockZ shr 4
            for (dx in -viewDist..viewDist) for (dz in -viewDist..viewDist) {
                val ccx = cx + dx; val ccz = cz + dz
                if (!visited.add(ChunkPos.asLong(ccx, ccz))) continue
                val chunk = level.chunkSource.getChunkNow(ccx, ccz) ?: continue
                sweepChunk(level, chunk, rng)
            }
        }
    }

    private fun sweepChunk(level: ServerLevel, chunk: LevelChunk, rng: RandomSource) {
        // Whole-chunk shortcut: every block inside a VS2 shipyard chunk is, by definition,
        // a legitimate ship block — no need to look at individual voxels.
        val chunkCenter = BlockPos(chunk.pos.minBlockX + 8, level.minBuildHeight + 1, chunk.pos.minBlockZ + 8)
        if (level.isBlockInShipyard(chunkCenter)) return

        val minSection = level.minSection
        val sections = chunk.sections
        val minBlockX = chunk.pos.minBlockX
        val minBlockZ = chunk.pos.minBlockZ
        for (i in sections.indices) {
            val section = sections[i]
            if (section.hasOnlyAir()) continue
            val baseY = (minSection + i) * 16
            repeat(RANDOM_TICK_SPEED) {
                val lx = rng.nextInt(16)
                val ly = rng.nextInt(16)
                val lz = rng.nextInt(16)
                val state = section.getBlockState(lx, ly, lz)
                if (state.isAir) return@repeat
                val pos = BlockPos(minBlockX + lx, baseY + ly, minBlockZ + lz)
                if (level.getLoadedShipManagingPos(pos) != null) return@repeat
                destroyStray(level, pos)
            }
        }
    }

    private fun destroyStray(level: ServerLevel, pos: BlockPos) {
        // [Level.removeBlock] is the WRONG primitive here: it preserves any fluid state at
        // the position by calling `setBlock(pos, fluidState.createLegacyBlock(), …)`. For a
        // water/lava source the fluid state IS that water/lava, so removeBlock resolves to
        // "set water to water" — a no-op. We were emitting the particle burst and then the
        // fluid stayed put. Force the block straight to air so it works for both fluids and
        // solids.
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        // Same FX as [YgannAbyssGuard.onPlace]'s rejection so placement-blocked and
        // sweep-destroyed strays read as the same dimension behaviour.
        level.sendParticles(
            ParticleTypes.PORTAL,
            pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
            24, 0.4, 0.4, 0.4, 0.6,
        )
        level.playSound(
            null, pos,
            SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS,
            0.5f, 1.4f,
        )
    }
}
