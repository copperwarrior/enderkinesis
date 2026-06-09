package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.SimpleParticleType
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.registry.EKParticles

/* Fireflies spawn in any Wohlon biome cell — Wohlon dimension *or* the Overworld
 * Wohlon biome — based on the biome at the player's foot position. */

/**
 * Client-side ambient firefly spawner for Wohlonnogondonia.
 *
 * Per-tick budget:
 *  - Fires only while the local player is standing in a Wohlon biome cell
 *    (dimension or Overworld biome — same biome key).
 *  - One spawn attempt every [TICK_INTERVAL] ticks. With a per-attempt success rate of
 *    ~10 % (most random positions don't satisfy "air voxel adjacent to a solid block"),
 *    typical steady-state spawn rate is ~1/s; multiplied by the particle's
 *    ~15–30 s lifetime that produces roughly 15–30 alive fireflies in the player's
 *    visible volume.
 *
 * Spawn rule:
 *  - Pick a random voxel within an asymmetric box around the player ([SPAWN_RADIUS_XZ]
 *    wide horizontally, [SPAWN_RADIUS_Y] vertically).
 *  - The voxel must be air (firefly needs a body of air to fly in).
 *  - At least one of the six face-adjacent voxels must hold a non-air block (so the
 *    firefly is "next to" something — it gathers around blocks rather than spawning in
 *    pure open air, per the brief).
 *  - Spawn position is jittered inside the voxel so adjacent successes don't all hatch
 *    at the same world coord.
 *
 * Cleanup: no manual tracking needed. Particles are owned by the client `ParticleEngine`,
 * which expires them by `lifetime`. Leaving the dimension just stops new spawns; existing
 * fireflies finish their flicker and disappear naturally.
 */
object WohlonnogondoniaFireflies {

    /** Half-extents of the search box around the player. XZ wider than Y because the
     *  player's visible horizon is wider than tall, and vertical search misses are
     *  costly (most Y positions are mud below feet or open sky above head). */
    private const val SPAWN_RADIUS_XZ: Int = 12
    private const val SPAWN_RADIUS_Y: Int = 6

    /** Ticks between spawn attempts. 2 ticks (10 attempts/s) × ~10 % success ≈ 1
     *  spawn/s. Tune lower (more frequent) for denser fields; higher for sparser. */
    private const val TICK_INTERVAL: Int = 2

    private var tickCounter: Int = 0

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    private fun tick(level: ClientLevel) {
        val player = Minecraft.getInstance().player ?: return
        if (!level.getBiome(player.blockPosition()).`is`(Wohlonnogondonia.BIOME_KEY)) return
        if (++tickCounter < TICK_INTERVAL) return
        tickCounter = 0

        val rng = level.random
        val centerX = player.blockX
        val centerY = player.blockY
        val centerZ = player.blockZ

        val dx = rng.nextInt(SPAWN_RADIUS_XZ * 2 + 1) - SPAWN_RADIUS_XZ
        val dy = rng.nextInt(SPAWN_RADIUS_Y * 2 + 1) - SPAWN_RADIUS_Y
        val dz = rng.nextInt(SPAWN_RADIUS_XZ * 2 + 1) - SPAWN_RADIUS_XZ
        val wx = centerX + dx
        val wy = centerY + dy
        val wz = centerZ + dz

        val pos = BlockPos(wx, wy, wz)
        // Out of loaded chunks → getBlockState returns air; the adjacent-solid check below
        // will fail naturally so we don't need an explicit hasChunkAt() probe.
        if (!level.getBlockState(pos).isAir) return

        // Need at least one solid neighbour. Cheap 6-direction loop; early-exit on first.
        var hasNeighbor = false
        for (dir in Direction.values()) {
            if (!level.getBlockState(pos.relative(dir)).isAir) {
                hasNeighbor = true
                break
            }
        }
        if (!hasNeighbor) return

        // Jitter inside the voxel so two consecutive spawns in the same block don't
        // start at exactly the same coord (they'd then trace identical orbits).
        val px = wx + 0.2 + rng.nextDouble() * 0.6
        val py = wy + 0.2 + rng.nextDouble() * 0.6
        val pz = wz + 0.2 + rng.nextDouble() * 0.6

        level.addParticle(
            EKParticles.WOHLON_FIREFLY.get() as SimpleParticleType,
            px, py, pz,
            0.0, 0.0, 0.0,
        )
    }
}
