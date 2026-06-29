package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientChunkCache
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.sselith.SselithEclipse

/** Client-side bindings for the Sselith Eclipse:
 *   - S2C receiver for [SselithEclipse.TRIGGER_PACKET], mirroring the manual-trigger
 *     start tick so the lightmap blend and the server damage schedule agree.
 *   - Typed level resolver for the block/sky-light eclipse mixins, so client-side
 *     light reads route through the *same* level reference + intensity formula as
 *     the server side. Without this hook the common mixins would no-op on client.
 *   - Transition watcher: dirties all loaded sections when eclipse intensity crosses
 *     the 0/non-0 boundary. Chunk meshes bake light-UVs from the engine reads at
 *     mesh time; without a forced re-mesh, sections meshed during an active eclipse
 *     keep their reduced UVs after the eclipse ends, producing the "loading into an
 *     active eclipse permanently lowers brightness" bug.
 */
object SselithEclipseClient {

    @Volatile private var lastEclipseActive: Boolean = false

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SselithEclipse.TRIGGER_PACKET) { buf, _ ->
            val start = buf.readLong()
            SselithEclipse.setClientManualTrigger(start)
        }
        SselithEclipse.setClientLevelResolver { chunkSource ->
            (chunkSource as? ClientChunkCache)?.level as? Level
        }
    }

    fun clientTick() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null) {
            lastEclipseActive = false
            return
        }
        if (level.dimension() != SselithRepertory.LEVEL_KEY) {
            lastEclipseActive = false
            return
        }
        val active = SselithEclipse.intensity(level.gameTime) > 0f
        if (active != lastEclipseActive) {
            lastEclipseActive = active
            dirtyAllLoadedSections(mc)
        }
    }

    /** Force every loaded section within the player's render distance to re-mesh.
     *  Touches each section box; vanilla/Sodium then rebuilds the vertex buffer with
     *  the new light-UV reads. Bounded by render distance so cost is O(rd³) sections
     *  on each transition — fine for two transitions per eclipse cycle. */
    private fun dirtyAllLoadedSections(mc: Minecraft) {
        val level = mc.level ?: return
        val player = mc.player ?: return
        val rd = mc.options.renderDistance().get()
        val px = player.blockX shr 4
        val pz = player.blockZ shr 4
        val minSy = level.minSection
        val maxSy = level.maxSection
        val renderer = mc.levelRenderer
        for (cx in (px - rd)..(px + rd)) {
            for (cz in (pz - rd)..(pz + rd)) {
                for (sy in minSy until maxSy) {
                    renderer.setSectionDirtyWithNeighbors(cx, sy, cz)
                }
            }
        }
    }
}
