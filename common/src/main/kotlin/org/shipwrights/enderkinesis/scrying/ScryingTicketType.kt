package org.shipwrights.enderkinesis.scrying

import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import java.util.Comparator

/**
 * Per-session chunk-force ticket for the scrying camera's view area.
 *
 * Why a custom [TicketType] instead of `ServerLevel.setChunkForced` directly:
 *  - `setChunkForced` writes through `ForcedChunksSavedData` and persists, so a server
 *    crash mid-session leaks chunks until they're manually unforced. Tickets only live
 *    in memory and clean up the moment the session ends (or, worst case, on server stop).
 *  - We can key tickets by `ChunkPos` so each chunk's hold can be released independently
 *    when the mounted view tears down.
 *
 * Distance encoding (vanilla `ChunkLevel.byTicketDistance(d)` = MAX_LEVEL + 1 − d):
 *  - distance = 3 → level 31 = entity-ticking (matches a tracked player).
 *  Picking 3 means the loaded square ticks as if a player were standing there, so
 *  block updates, entity ticks, and weather render correctly from the camera vantage.
 */
object ScryingTicketType {
    val SCRYING: TicketType<ChunkPos> = TicketType.create(
        "enderkinesis_scrying",
        Comparator.comparingLong { it.toLong() },
    )

    const val DISTANCE: Int = 3
}
