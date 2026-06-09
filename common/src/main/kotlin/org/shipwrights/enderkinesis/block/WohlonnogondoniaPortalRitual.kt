package org.shipwrights.enderkinesis.block

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia

/**
 * Ritual logic + teleport target picker for the Heart Candle ⇒
 * Wohlonnogondonia portal.
 *
 * Three pieces, all server-side and stateless:
 *
 *  - [PATTERN] — the 8 light-blue candle offsets + required candle
 *    counts that must all be lit in the player's local biome for
 *    the ritual to fire. Y-aligned with the heart candle; offsets
 *    are (dx, dz) where dz<0 is north, dz>0 is south.
 *  - [checkPattern] — verifies every [PATTERN] slot.
 *  - [VALID_BIOMES_TAG] / [isBiomeValid] — the heart candle's local
 *    biome must be in this tag (data file: plains + forest to start).
 *  - [pickLandingSpot] / [teleportToMotherTree] — pick a random open
 *    ground voxel in a ring around Wohlon's origin (the Mother
 *    Tree's footprint) and move the entity there, handling
 *    cross-dimension transfer for players vs. mobs.
 */
object WohlonnogondoniaPortalRitual {

    private val LOG = LogUtils.getLogger()

    /** A single slot in the ritual pattern: position offset from
     *  the heart candle (X east, Z south) and the exact candle
     *  count required at that slot. All slots must hold a LIT
     *  light_blue_candle with the specified CANDLES value. */
    data class PatternSlot(val dx: Int, val dz: Int, val count: Int)

    /** Ritual pattern (top is north, bottom is south):
     *  ```
     *  X X X 1 X X X
     *  X 2 X X X 2 X
     *  X X X X X X X
     *  2 X X H X X 2
     *  X X X X X X X
     *  X 3 X X X 3 X
     *  X X X 4 X X X
     *  ```
     *  H is the heart candle, numbers are required CANDLES counts
     *  of LIT light_blue_candle at that offset. X slots aren't
     *  checked — anything (or nothing) can occupy them. This is
     *  the canonical (north-aligned) orientation; the ritual
     *  also accepts any 90°-rotated form via [ROTATIONS]. */
    val PATTERN: List<PatternSlot> = listOf(
        PatternSlot(0, -3, 1),
        PatternSlot(-2, -2, 2), PatternSlot(2, -2, 2),
        PatternSlot(-3,  0, 2), PatternSlot(3,  0, 2),
        PatternSlot(-2,  2, 3), PatternSlot(2,  2, 3),
        PatternSlot(0,  3, 4),
    )

    /** All four cardinal rotations of [PATTERN], precomputed at
     *  class init. [checkPattern] succeeds if the candles around
     *  the heart match ANY of these — so the ritual is built
     *  exactly the same shape regardless of which compass
     *  direction the player approaches it from. Rotation rule
     *  for 90° clockwise in MC's (+X east, +Z south) plane is
     *  `(x, z) → (-z, x)`; the four orientations are the
     *  identity, that map applied once, twice, and thrice.
     *  Candle counts move with the slot — i.e. in the 90° CW
     *  form, the "1" lives on the east side and the "4" on the
     *  west side; the *shape* of the ring rotates with the
     *  player's framing, not the numbers. */
    val ROTATIONS: List<List<PatternSlot>> = listOf(
        PATTERN,                                                              // 0°   (canonical, north-up)
        PATTERN.map { PatternSlot(-it.dz,  it.dx, it.count) },                // 90°  CW
        PATTERN.map { PatternSlot(-it.dx, -it.dz, it.count) },                // 180°
        PATTERN.map { PatternSlot( it.dz, -it.dx, it.count) },                // 270° CW (90° CCW)
    )

    /** Biome tag enumerating the overworld biomes in which the
     *  ritual is allowed to succeed. Data file at
     *  `data/enderkinesis/tags/worldgen/biome/wohlon_portal_valid_biomes.json`. */
    val VALID_BIOMES_TAG: TagKey<Biome> = TagKey.create(
        Registries.BIOME,
        ResourceLocation("enderkinesis", "wohlon_portal_valid_biomes"),
    )

    /** True iff every slot of ANY [ROTATIONS] entry, applied
     *  relative to [heartPos], holds a LIT
     *  `minecraft:light_blue_candle` with the required CANDLES
     *  count. Short-circuits as soon as one rotation matches.
     *  Y stays equal to the heart candle's Y — the pattern is a
     *  flat ring on the same plane. */
    fun checkPattern(level: ServerLevel, heartPos: BlockPos): Boolean {
        for (rotation in ROTATIONS) {
            if (matchesRotation(level, heartPos, rotation)) return true
        }
        return false
    }

    /** Per-rotation matcher: every slot must hold a LIT light
     *  blue candle with the required CANDLES count. Stops on
     *  the first miss within the rotation so we move on to the
     *  next orientation immediately. */
    private fun matchesRotation(
        level: ServerLevel,
        heartPos: BlockPos,
        rotation: List<PatternSlot>,
    ): Boolean {
        for (slot in rotation) {
            val pos = heartPos.offset(slot.dx, 0, slot.dz)
            val state = level.getBlockState(pos)
            if (!state.`is`(Blocks.LIGHT_BLUE_CANDLE)) return false
            if (state.getValue(CandleBlock.CANDLES) != slot.count) return false
            if (!state.getValue(CandleBlock.LIT)) return false
        }
        return true
    }

    /** The heart candle's local biome must be in
     *  [VALID_BIOMES_TAG]. */
    fun isBiomeValid(level: ServerLevel, pos: BlockPos): Boolean {
        return level.getBiome(pos).`is`(VALID_BIOMES_TAG)
    }

    /** Pick a random "open ground voxel under the Mother Tree" in
     *  Wohlon.
     *
     *  Constraints (informed by the v1 freeze + Y 37 landing bug):
     *
     *  - **Bounded chunk count.** The scan stays inside a 4-chunk
     *    window around the dimension origin: chunks `(-1..0,
     *    -1..0)`. These get pre-loaded once at the top of the
     *    function so the per-voxel `getBlockState` calls don't
     *    trigger one-chunk-at-a-time sync chunkgen mid-scan,
     *    which was freezing the server thread on first portal use.
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
        // Pre-load a wider patch so the ring at 35-50 blocks out
        // (past the Mother Tree's trunk + buttress, into the open
        // swamp surface) has chunks ready. Each `getChunk` runs the
        // chunkgen pipeline synchronously; subsequent reads in the
        // same chunk are O(1) array lookups.
        for (cx in -3..3) {
            for (cz in -3..3) {
                wohlonLevel.getChunk(cx, cz)
            }
        }

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
            LOG.warn("WohlonPortal: aborting teleport — Wohlonnogondonia is being wiped.")
            return
        }
        val server = currentLevel.server
        val wohlonLevel = server.getLevel(Wohlonnogondonia.LEVEL_KEY) ?: run {
            LOG.warn("WohlonPortal: aborting teleport — wohlon dimension level not loaded")
            return
        }
        // Prefer the pre-computed landing the async search found at
        // portal-registration time. Falls back to a live `pickLandingSpot`
        // if the search hasn't finished yet OR if the portal predates
        // the landing-storage feature.
        val stored = sourcePortalPos?.let {
            WohlonnogondoniaPortalManager.getStoredLanding(currentLevel, it)
        }
        val target: Vec3 = if (stored != null) {
            Vec3(stored.x + 0.5, (stored.y + 1).toDouble(), stored.z + 0.5)
        } else {
            pickLandingSpot(wohlonLevel) ?: run {
                LOG.warn("WohlonPortal: aborting teleport — no stored landing and live pickLandingSpot returned null")
                return
            }
        }
        LOG.info(
            "WohlonPortal: teleporting {} ({}) to {} (stored landing? {})",
            entity.type.toShortString(), entity.uuid, target, stored != null,
        )

        // Players record their outbound portal so the heart return knows
        // where to send them back. Non-players have no return.
        if (entity is ServerPlayer && sourcePortalPos != null) {
            WohlonnogondoniaPortalManager.recordPlayerEntry(
                server, entity.uuid, currentLevel.dimension(), sourcePortalPos,
            )
        }

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
                    entity.teleportTo(
                        targetLevel,
                        record.pos.x + 0.5, record.pos.y.toDouble(), record.pos.z + 0.5,
                        entity.yRot, entity.xRot,
                    )
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
        val tx = targetPos.x + 0.5
        val ty = targetPos.y.toDouble()
        val tz = targetPos.z + 0.5

        WohlonnogondoniaPortalManager.noteReturnedEntity(entity.uuid, now)
        // Unified teleport — see comment in `teleportToMotherTree`.
        entity.teleportTo(
            targetLevel,
            tx, ty, tz,
            java.util.Collections.emptySet(),
            entity.yRot, entity.xRot,
        )
    }
}
