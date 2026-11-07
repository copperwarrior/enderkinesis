package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.registry.EKSounds

/** Teleport target picker for the Wohlonnogondonia portal. The ritual that makes the portal is data: `data/enderkinesis/rituals/wohlon_portal.json`. */
object WohlonnogondoniaPortalRitual {

    /** Pick a random "open ground voxel under the Mother Tree" in
     *  Wohlon.
     *
     *  Constraints (informed by the v1 freeze + Y 37 landing bug):
     *
     *  - **No chunk generation.** A column in a chunk that is not
     *    loaded is skipped; the landing ticket loads the ring off
     *    the server thread, and the caller tries again later.
     *  - **Tight horizontal ring.** Random (x, z) in an 8–12
     *    block radius from origin — just outside the Mother
     *    Tree's typical trunk, under the canopy.
     *  - **Tight vertical range.** Scan Y 100 → 50 only. Below
     *    50 is bedrock-band mud (where the previous version
     *    landed at Y 37 because the scan happily descended past
     *    the actual surface into the mud column); above 100 is
     *    canopy leaves and trunk wood.
     *  - **Body-clearance check.** The candidate ground must
     *    have both Y+1 and Y+2 *passable* (air or leaves) — that
     *    catches the bug where the scan skipped a log/leaf at Y+1
     *    and "found" mud underneath, but the skipped log was
     *    still physically present in the player's body space. */
    fun pickLandingSpot(wohlonLevel: ServerLevel): Vec3? {
        WohlonnogondoniaPortalManager.holdLandingArea(wohlonLevel)
        val chunkSource = wohlonLevel.chunkSource
        val random = wohlonLevel.random
        repeat(50) {
            val theta = random.nextDouble() * 2.0 * Math.PI
            // Ring 35–50 blocks from origin. The Mother Tree's trunk
            // base is up to `MAX_THICKNESS + buttressFlare` ≈ 24
            // blocks radius of wood; below ~30 the column is logs
            // every Y and the LOGS filter never finds non-wood.
            // 35–50 lands the player on open swamp surface with the
            // Mother Tree clearly visible above them.
            val r = 35.0 + random.nextDouble() * 15.0
            val x = (Math.cos(theta) * r).toInt()
            val z = (Math.sin(theta) * r).toInt()
            if (chunkSource.getChunkNow(x shr 4, z shr 4) == null) return@repeat
            // Wider Y sweep so we don't miss the actual surface if
            // it sits outside the old narrow band.
            for (y in 150 downTo 0) {
                val state = wohlonLevel.getBlockState(BlockPos(x, y, z))
                if (state.isAir) continue
                if (state.`is`(BlockTags.LOGS)) continue
                if (state.`is`(BlockTags.LEAVES)) continue
                if (!state.fluidState.isEmpty) return@repeat
                if (!state.canOcclude()) continue
                // Verify the two voxels above (player body height)
                // are passable, not just *previously skipped*. A
                // skipped log at Y+1 in the scan loop is still
                // physically there — clipping into it on spawn
                // is the bug the user saw.
                val above1 = wohlonLevel.getBlockState(BlockPos(x, y + 1, z))
                val above2 = wohlonLevel.getBlockState(BlockPos(x, y + 2, z))
                if (!isPassable(above1)) continue
                if (!isPassable(above2)) continue
                return Vec3(x + 0.5, (y + 1).toDouble(), z + 0.5)
            }
        }
        return null
    }

    /** Air, leaves (transparent, walk-through), or any non-
     *  fully-occluding block. Used to verify a candidate
     *  landing spot has clearance for the player's hitbox. Public so
     *  [WohlonnogondoniaPortalManager]'s async search can reuse the
     *  exact same predicate. */
    fun isPassable(state: BlockState): Boolean {
        return state.isAir ||
            state.`is`(BlockTags.LEAVES) ||
            !state.canOcclude()
    }

    /** Move [entity] to Wohlon, near the Mother Tree, on a random
     *  open ground voxel. Handles the player vs. non-player cross-
     *  dimension distinction:
     *  - `ServerPlayer.teleportTo(level, x, y, z, …)` for players
     *    (proper portal-style network packet, no entity
     *    duplication).
     *  - `Entity.changeDimension(level)` then `teleportTo(x, y, z)`
     *    for mobs and item entities.
     *
     *  When [entity] is a [ServerPlayer], the *source* portal
     *  position is recorded in the [WohlonnogondoniaPortalManager]'s
     *  player-entry tracker so the Mother Tree heart return-portal
     *  can later send the same player back to the exact portal
     *  they came in at. */
    fun teleportToMotherTree(entity: Entity, currentLevel: ServerLevel, sourcePortalPos: BlockPos?) {
        // Refuse the trip while [WohlonnogondoniaCatastrophe] is
        // mid-wipe — region files are being closed and the dim
        // directory deleted; sending an entity in would land it in
        // a half-torn-down level. Lifted automatically once the
        // wipe completes.
        if (WohlonnogondoniaCatastrophe.isWipeInProgress()) {
            return
        }
        val server = currentLevel.server
        val wohlonLevel = server.getLevel(Wohlonnogondonia.LEVEL_KEY) ?: run {
            return
        }
        // Prefer the pre-computed landing the async search found at
        // portal-registration time. Falls back to a live `pickLandingSpot`
        // if the search hasn't finished yet OR if the portal predates
        // the landing-storage feature.
        val stored = sourcePortalPos?.let {
            WohlonnogondoniaPortalManager.validatedLanding(currentLevel, it, wohlonLevel)
        }
        val target: Vec3 = if (stored != null) {
            Vec3(stored.x + 0.5, (stored.y + 1).toDouble(), stored.z + 0.5)
        } else {
            // While the search can still give a landing, the entity waits in the portal for the next scan.
            if (sourcePortalPos != null &&
                WohlonnogondoniaPortalManager.isSearchPending(currentLevel.dimension(), sourcePortalPos)
            ) return
            pickLandingSpot(wohlonLevel) ?: run {
                return
            }
        }

        // Players record their outbound portal so the heart return knows
        // where to send them back. Non-players have no return.
        if (entity is ServerPlayer && sourcePortalPos != null) {
            WohlonnogondoniaPortalManager.recordPlayerEntry(
                server, entity.uuid, currentLevel.dimension(), sourcePortalPos,
            )
        }

        playTravel(currentLevel, entity.x, entity.y, entity.z)
        // Unified teleport via `Entity.teleportTo(ServerLevel, ...)`.
        // Avoids `Entity.changeDimension`, whose vanilla
        // `findDimensionEntryPoint` returns null for any dim that
        // isn't Nether, End, or Overworld-from-End — silently dropping
        // every non-player entity that hit our portal. The
        // (ServerLevel, …) overload does a direct cross-dim transfer
        // via `getType().create(level) + restoreFrom`, no portal-info
        // lookup. Players' override handles their connection state.
        entity.teleportTo(
            wohlonLevel,
            target.x, target.y, target.z,
            java.util.Collections.emptySet(),
            entity.yRot, entity.xRot,
        )
        playTravel(wohlonLevel, target.x, target.y, target.z)
    }

    /** Heart return-portal dispatch.
     *
     *  - **Player** → back to the outbound portal they came in
     *    at. Players ordinarily enter Wohlon via an outbound
     *    portal (which records their entry on the way through),
     *    but admin `/tp` and similar dimension-jump commands
     *    can drop a player into Wohlon without ever crossing
     *    the tracker. If the record is missing — or names a
     *    dimension that no longer resolves — we fall through to
     *    the same random-other-portal path the non-player
     *    branch uses, so the player still gets somewhere.
     *  - **Non-player** (mob, item entity) → a random outbound
     *    portal in any non-Wohlon dimension. "Random other
     *    portal" per design. */
    fun teleportFromHeart(entity: Entity, currentLevel: ServerLevel) {
        val server = currentLevel.server
        // gameTime for the cooldown is read on the SOURCE dim (any
        // dim works in practice — server tick advances all dims in
        // lockstep — but the source dim is the one whose
        // scanAndTeleport will consult the cooldown).
        val now = server.overworld().gameTime

        if (entity is ServerPlayer) {
            val record = WohlonnogondoniaPortalManager.getPlayerEntry(server, entity.uuid)
            if (record != null) {
                val targetLevel = server.getLevel(record.dim)
                if (targetLevel != null) {
                    // Stamp the cooldown BEFORE teleport so the
                    // source-dim scan that fires next tick sees it.
                    WohlonnogondoniaPortalManager.noteReturnedEntity(entity.uuid, now)
                    val spot = WohlonnogondoniaPortalManager.findReturnSpot(targetLevel, record.pos)
                    playTravel(currentLevel, entity.x, entity.y, entity.z)
                    entity.teleportTo(targetLevel, spot.x, spot.y, spot.z, entity.yRot, entity.xRot)
                    playTravel(targetLevel, spot.x, spot.y, spot.z)
                    return
                }
            }
        }

        // Random other portal: non-players always, and players
        // without a usable record (admin-tp entry path, etc.).
        val candidate = WohlonnogondoniaPortalManager.pickRandomNonWohlonPortal(server, currentLevel.random)
            ?: return
        val targetLevel = candidate.first
        val targetPos = candidate.second
        val spot = WohlonnogondoniaPortalManager.findReturnSpot(targetLevel, targetPos)
        val tx = spot.x
        val ty = spot.y
        val tz = spot.z

        WohlonnogondoniaPortalManager.noteReturnedEntity(entity.uuid, now)
        playTravel(currentLevel, entity.x, entity.y, entity.z)
        // Unified teleport — see comment in `teleportToMotherTree`.
        entity.teleportTo(
            targetLevel,
            tx, ty, tz,
            java.util.Collections.emptySet(),
            entity.yRot, entity.xRot,
        )
        playTravel(targetLevel, tx, ty, tz)
    }

    private fun playTravel(level: ServerLevel, x: Double, y: Double, z: Double) {
        level.playSound(null, x, y, z, EKSounds.WOHLON_PORTAL_TRAVEL.get(), SoundSource.BLOCKS, 1f, 1f)
    }
}
